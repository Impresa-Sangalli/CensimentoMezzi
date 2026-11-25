package it.impresasangalli.censimentomezzi

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Invio automatico dei censimenti a SharePoint.
 * Ogni censimento salvato entra in coda; la coda viene svuotata appena
 * c'è connessione (anche ad app chiusa, tramite JobScheduler).
 */
object SyncEngine {
    enum class Outcome { DONE, RETRY, STOP }

    @Volatile var running = false
        private set
    @Volatile var cancel = false
    @Volatile var listener: (() -> Unit)? = null

    private fun notifyUi() {
        try { listener?.invoke() } catch (_: Exception) {}
    }

    private fun friendly(e: IOException): String = when (e) {
        is UnknownHostException, is ConnectException -> "Nessuna connessione internet: invio rimandato"
        is SocketTimeoutException -> "Connessione troppo lenta: invio rimandato"
        is SSLException -> "Connessione interrotta: invio rimandato"
        else -> e.message ?: e.toString()
    }

    fun run(ctx0: Context): Outcome {
        val ctx = ctx0.applicationContext
        synchronized(this) {
            if (running) return Outcome.DONE
            running = true
            cancel = false
        }
        notifyUi()
        val cfg = Config(ctx)
        val db = Db(ctx)
        try {
            if (!cfg.ready() || !Auth.signedIn(ctx)) return Outcome.STOP
            if (!db.hasWork()) return Outcome.DONE

            val g = Graph({ force -> Auth.accessToken(ctx, force) })
            val drive = cfg.driveId ?: g.driveId(g.resolveSite(cfg.siteUrl)).also { cfg.driveId = it }
            val ra = RemoteArchive(g, drive, cfg.folder, cfg.excelName, Config.TABLE, cfg.excelItemId)

            while (!cancel) {
                val c = db.nextPending() ?: break
                try {
                    if (c.syncState == SyncState.PENDING) {
                        val bytes = PhotoStore.readBytes(ctx, c.uri)
                        if (bytes == null) {
                            db.setPhotoSent(c.id, c.nomeFile, "Foto non trovata sul telefono: inviata solo la riga Excel")
                        } else {
                            db.setPhotoSent(c.id, ra.uploadPhoto(c.targa, c.nomeFile, bytes), null)
                        }
                        notifyUi()
                    }
                    val cur = db.get(c.id) ?: continue
                    val row = Exporter.cells(cur.copy(nomeFile = cur.remoteName ?: cur.nomeFile))
                    ra.appendRow(Config.SHEET, Exporter.HEADER, Exporter.WIDTHS, row)
                    cfg.excelItemId = ra.excelItemId
                    db.setSent(c.id, cur.dataUscita)
                    cfg.lastOk = System.currentTimeMillis()
                    cfg.lastError = null
                } catch (e: GraphException) {
                    // errori temporanei o di configurazione: si ferma e riprova più tardi
                    if (e.retryable || e.code == 401 || e.code == 403) throw e
                    db.setFailed(c.id, e.message ?: "Errore ${e.code}")
                }
                notifyUi()
            }

            // Date di uscita inserite o modificate dopo l'invio: aggiorna la riga già su SharePoint
            while (!cancel) {
                val c = db.nextUscitaUpdate() ?: break
                try {
                    val row = Exporter.cells(c.copy(nomeFile = c.remoteName ?: c.nomeFile))
                    val found = ra.updateRow(row, Exporter.KEY_COLUMN)
                    cfg.excelItemId = ra.excelItemId
                    db.setUscitaSynced(c.id, c.dataUscita,
                        if (found) null else "Riga non trovata nel file Excel su SharePoint: data di uscita non aggiornata")
                    cfg.lastOk = System.currentTimeMillis()
                    cfg.lastError = null
                } catch (e: GraphException) {
                    if (e.retryable || e.code == 401 || e.code == 403) throw e
                    db.setUscitaSynced(c.id, c.dataUscita, "Data di uscita non aggiornata su SharePoint: ${e.message ?: e.code}")
                }
                notifyUi()
            }
            return if (db.hasWork()) Outcome.RETRY else Outcome.DONE
        } catch (e: AuthRequiredException) {
            cfg.lastError = "Accesso Microsoft scaduto: accedere di nuovo dalla scheda ONLINE"
            return Outcome.STOP
        } catch (e: GraphException) {
            if (e.code == 404) cfg.clearRemoteCache()
            cfg.lastError = e.message
            return Outcome.RETRY
        } catch (e: IOException) {
            cfg.lastError = friendly(e)
            return Outcome.RETRY
        } catch (e: Exception) {
            cfg.lastError = "Errore imprevisto: $e"
            return Outcome.RETRY
        } finally {
            running = false
            notifyUi()
        }
    }
}

/** Job di sistema che esegue l'invio quando la rete è disponibile. */
class SyncJobService : JobService() {
    companion object {
        private const val JOB_ID = 4242

        /** Accoda un invio: parte subito se c'è rete, altrimenti appena torna. */
        fun schedule(ctx: Context) {
            if (SyncEngine.running) return
            val cfg = Config(ctx)
            if (!cfg.ready() || !Auth.signedIn(ctx)) return
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, SyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build()
            js.schedule(job)
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val r = SyncEngine.run(this)
            jobFinished(params, r == SyncEngine.Outcome.RETRY)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        SyncEngine.cancel = true
        return true
    }
}
