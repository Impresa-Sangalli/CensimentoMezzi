package it.impresasangalli.censimentomezzi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException

/**
 * Aggiornamento automatico dell'app dalle Release di GitHub.
 *
 * Ogni Release pubblicata dal workflow contiene l'APK firmato e un file version.json:
 *   {"versionCode": 4, "versionName": "1.3", "apkUrl": "https://github.com/…/CensimentoMezzi-1.3.apk", "sha256": "…"}
 * L'app scarica version.json dell'ultima Release; se la versione è più recente scarica l'APK,
 * ne verifica l'impronta SHA-256 e lo installa con PackageInstaller.
 * Android installa l'aggiornamento solo se è firmato con la stessa chiave dell'app presente.
 *
 * Da Android 12 l'installazione avviene senza conferma (l'app aggiorna se stessa);
 * su Android 8-11 il sistema chiede comunque un tocco su "Installa".
 * In entrambi i casi serve, una sola volta, l'autorizzazione "Installa app sconosciute".
 */
object Updater {
    const val REPO = "Impresa-Sangalli/CensimentoMezzi"
    private const val MANIFEST_URL = "https://github.com/$REPO/releases/latest/download/version.json"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    const val ACTION_STATUS = "it.impresasangalli.censimentomezzi.UPDATE_STATUS"
    private const val CHECK_INTERVAL = 30 * 60 * 1000L          // controllo all'apertura: al massimo ogni 30 minuti
    private const val PROMPT_PAUSE = 4 * 3600 * 1000L           // dopo un annullamento non richiede per 4 ore
    private const val MAX_APK = 100L * 1024 * 1024
    private const val CHANNEL = "aggiornamenti"
    private const val NOTIF_ID = 77

    /** Schermata principale visibile. */
    @Volatile var foreground = false
    @Volatile var listener: (() -> Unit)? = null
    @Volatile var running = false
        private set

    data class Info(val code: Long, val name: String, val url: String, val sha256: String)

    fun notifyUi() {
        try { listener?.invoke() } catch (_: Exception) {}
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("update", Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    fun currentCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /**
     * Modulo di censimento con dati non ancora salvati: non installare, perché l'aggiornamento
     * chiude l'app. Salvato su disco, così vale anche se il sistema chiude l'app in background.
     */
    fun formBusy(ctx: Context): Boolean = prefs(ctx).getBoolean("formBusy", false)
    fun setFormBusy(ctx: Context, busy: Boolean) {
        prefs(ctx).edit().putBoolean("formBusy", busy).apply()
    }

    fun lastCheck(ctx: Context): Long = prefs(ctx).getLong("lastCheck", 0)
    fun lastError(ctx: Context): String? = prefs(ctx).getString("lastError", null)
    fun readyName(ctx: Context): String? = if (readyFile(ctx) != null) prefs(ctx).getString("readyName", null) else null
    fun checkDue(ctx: Context): Boolean = System.currentTimeMillis() - lastCheck(ctx) > CHECK_INTERVAL

    private fun setError(ctx: Context, msg: String?) {
        prefs(ctx).edit().putString("lastError", msg).apply()
    }

    /** APK già scaricato e verificato, più recente della versione installata. */
    fun readyFile(ctx: Context): File? {
        val p = prefs(ctx)
        val path = p.getString("readyPath", null) ?: return null
        val f = File(path)
        if (p.getLong("readyCode", 0) <= currentCode(ctx) || !f.exists()) {
            clearDownload(ctx)
            return null
        }
        return f
    }

    private fun updateDir(ctx: Context) = File(ctx.filesDir, "update")

    private fun clearDownload(ctx: Context) {
        updateDir(ctx).listFiles()?.forEach { it.delete() }
        prefs(ctx).edit().remove("readyPath").remove("readyCode").remove("readyName").apply()
    }

    /** Alla prima apertura dopo un aggiornamento restituisce la nuova versione (una sola volta). */
    fun justUpdated(ctx: Context): String? {
        val p = prefs(ctx)
        val cur = currentCode(ctx)
        val prev = p.getLong("lastRunCode", 0)
        if (prev == cur) return null
        p.edit().putLong("lastRunCode", cur).putString("lastError", null).apply()
        return if (prev in 1 until cur) currentName(ctx) else null
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException -> "Nessuna connessione internet"
        is SocketTimeoutException -> "Connessione troppo lenta"
        is SSLException -> "Connessione interrotta"
        else -> e.message ?: e.toString()
    }

    private fun open(url: String, ctx: Context): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.useCaches = false
        c.instanceFollowRedirects = true
        c.setRequestProperty("Cache-Control", "no-cache")
        c.setRequestProperty("User-Agent", "CensimentoMezzi/${currentName(ctx)} (Android)")
        return c
    }

    /** Legge version.json dell'ultima Release; null se non è ancora stata pubblicata alcuna Release. */
    private fun fetchInfo(ctx: Context): Info? {
        val c = open(MANIFEST_URL, ctx)
        try {
            val code = c.responseCode
            if (code == 404) return null
            if (code != 200) throw IOException("GitHub ha risposto con il codice $code")
            val j = JSONObject(c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
            val info = Info(j.getLong("versionCode"), j.getString("versionName"), j.getString("apkUrl"), j.getString("sha256").lowercase())
            if (!info.url.startsWith(DOWNLOAD_PREFIX)) throw IOException("Indirizzo di download non valido")
            if (!Regex("^[0-9a-f]{64}$").matches(info.sha256)) throw IOException("Impronta dell'aggiornamento non valida")
            return info
        } finally {
            c.disconnect()
        }
    }

    /** Scarica l'APK, ne verifica impronta, package e versione. */
    private fun download(ctx: Context, info: Info): File {
        val dir = updateDir(ctx)
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, "CensimentoMezzi-${info.code}.apk.part")
        val c = open(info.url, ctx)
        try {
            if (c.responseCode != 200) throw IOException("Download non riuscito (codice ${c.responseCode})")
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_APK) throw IOException("File di aggiornamento troppo grande")
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (hex != info.sha256) throw IOException("Aggiornamento danneggiato durante il download (impronta diversa)")
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            c.disconnect()
        }
        @Suppress("DEPRECATION")
        val pkg = ctx.packageManager.getPackageArchiveInfo(part.absolutePath, 0)
        @Suppress("DEPRECATION")
        val pkgCode = if (pkg == null) -1L else if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong()
        if (pkg == null || pkg.packageName != ctx.packageName || pkgCode != info.code) {
            part.delete()
            throw IOException("Il file scaricato non corrisponde all'aggiornamento atteso")
        }
        val apk = File(dir, "CensimentoMezzi-${info.code}.apk")
        if (!part.renameTo(apk)) throw IOException("Impossibile salvare l'aggiornamento")
        prefs(ctx).edit().putString("readyPath", apk.absolutePath).putLong("readyCode", info.code)
            .putString("readyName", info.name).apply()
        return apk
    }

    /**
     * Controlla se esiste una versione più recente e la scarica.
     * Da eseguire fuori dal thread UI. Restituisce true se un aggiornamento è pronto da installare.
     */
    fun check(ctx0: Context): Boolean {
        val ctx = ctx0.applicationContext
        synchronized(this) {
            if (running) return false
            running = true
        }
        notifyUi()
        try {
            val info = fetchInfo(ctx)
            prefs(ctx).edit().putLong("lastCheck", System.currentTimeMillis()).putString("lastError", null).apply()
            if (info == null || info.code <= currentCode(ctx)) {
                clearDownload(ctx)
                return false
            }
            if (prefs(ctx).getLong("readyCode", 0) == info.code && readyFile(ctx) != null) return true
            download(ctx, info)
            return true
        } catch (e: Exception) {
            prefs(ctx).edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            setError(ctx, "Controllo aggiornamenti non riuscito: ${friendly(e)}")
            return readyFile(ctx) != null
        } finally {
            running = false
            notifyUi()
        }
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Evita di riproporre subito l'installazione se l'operatore l'ha appena annullata. */
    fun promptAllowed(ctx: Context): Boolean =
        System.currentTimeMillis() - prefs(ctx).getLong("declinedAt", 0) > PROMPT_PAUSE

    fun declined(ctx: Context) {
        prefs(ctx).edit().putLong("declinedAt", System.currentTimeMillis()).apply()
    }

    /** "Installa app sconosciute" già proposto per questa versione dell'app. */
    fun permissionAsked(ctx: Context): Boolean = prefs(ctx).getLong("permAskedCode", 0) == currentCode(ctx)
    fun setPermissionAsked(ctx: Context) {
        prefs(ctx).edit().putLong("permAskedCode", currentCode(ctx)).apply()
    }

    /**
     * Avvia l'installazione dell'aggiornamento scaricato. L'app viene chiusa dal sistema
     * al termine; l'esito arriva a [UpdateReceiver]. Da eseguire fuori dal thread UI.
     */
    fun install(ctx0: Context): Boolean {
        val ctx = ctx0.applicationContext
        val f = readyFile(ctx) ?: return false
        if (formBusy(ctx)) return false
        if (!canInstall(ctx)) {
            setError(ctx, "Per installare gli aggiornamenti serve l'autorizzazione \"Installa app sconosciute\"")
            notifyUi()
            return false
        }
        return try {
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            params.setSize(f.length())
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = installer.createSession(params)
            installer.openSession(id).use { s ->
                f.inputStream().use { input ->
                    s.openWrite("CensimentoMezzi.apk", 0, f.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        s.fsync(out)
                    }
                }
                val i = Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(ctx, id, i, flags).intentSender)
            }
            true
        } catch (e: Exception) {
            setError(ctx, "Installazione non riuscita: ${e.message ?: e}")
            notifyUi()
            false
        }
    }

    /** Errore restituito dal sistema al termine dell'installazione. */
    fun installFailed(ctx: Context, status: Int, msg: String?) {
        val text = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> { declined(ctx); "Aggiornamento annullato" }
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "Aggiornamento non installabile: firma o versione non compatibile con l'app presente"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Spazio insufficiente sul telefono per l'aggiornamento"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installazione bloccata dal telefono"
            else -> "Installazione non riuscita" + (msg?.let { ": $it" } ?: "")
        }
        setError(ctx, text)
        notifyUi()
    }

    /** Su Android 8-11, ad app chiusa, avvisa che l'aggiornamento attende la conferma. */
    fun notifyPending(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Aggiornamenti dell'app", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Aggiornamento di Censimento Mezzi")
            .setContentText("Nuova versione ${prefs(ctx).getString("readyName", "") ?: ""} pronta: tocca per installare")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }
}

/** Esito dell'installazione inviato da PackageInstaller. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Updater.ACTION_STATUS) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null && Updater.foreground) {
                    try {
                        ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        Updater.notifyPending(ctx)
                    }
                } else {
                    Updater.notifyPending(ctx)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.notifyUi()
            else -> Updater.installFailed(ctx, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}

/** Controllo periodico in background (ogni 6 ore, quando c'è rete). */
class UpdateJobService : JobService() {
    companion object {
        private const val JOB_ID = 4243

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6 * 3600 * 1000L)
                .setPersisted(true)
                .build()
            js.schedule(job)
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val ready = Updater.check(this)
            // con l'app aperta l'installazione la gestisce la schermata principale
            if (ready && !Updater.foreground) Updater.install(this)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
