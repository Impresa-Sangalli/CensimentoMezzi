package it.impresasangalli.censimentomezzi

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Sincronizzazione con il portale:
 *  1. configurazione (zone, tipi di veicolo, provvedimenti);
 *  2. invio della coda (entrate, foto, uscite, spostamenti, correzioni) in ordine;
 *  3. ricezione delle modifiche del piazzale fatte dall'ufficio o dagli altri telefoni.
 * Senza rete (fuori dal Wi-Fi aziendale) tutto resta in coda e riparte appena il portale è raggiungibile.
 */
object SyncEngine {
    @Volatile var running = false
        private set
    @Volatile var listener: (() -> Unit)? = null

    private val D_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val T_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")

    private fun notifyUi() { try { listener?.invoke() } catch (_: Exception) {} }

    /** Restituisce true se tutto è stato sincronizzato, false se va ripetuto più tardi. */
    fun run(ctx0: Context): Boolean {
        val ctx = ctx0.applicationContext
        synchronized(this) { if (running) return true; running = true }
        notifyUi()
        val cfg = ServerConfig(ctx)
        val db = Db(ctx)
        try {
            if (!cfg.collegato()) return true
            val srv = Server(ctx)
            aggiornaConfig(srv, cfg)
            while (true) {
                val op = db.prossimaOperazione() ?: break
                try {
                    esegui(ctx, srv, db, op)
                } catch (e: ErroreServer) {
                    if (e.nonAutorizzato || !e.definitivo) throw e
                    db.tentativo(op.id, e.message ?: "Errore", true)   // dato rifiutato dal portale: resta visibile, la coda prosegue
                }
                notifyUi()
            }
            ricevi(srv, db, cfg)
            cfg.ultimaSync = System.currentTimeMillis()
            cfg.ultimoErrore = null
            return true
        } catch (e: ErroreServer) {
            if (e.nonAutorizzato) {
                cfg.token = null
                cfg.ultimoErrore = "Telefono scollegato dall'ufficio: serve un nuovo codice di attivazione"
                return true
            }
            cfg.ultimoErrore = Server.messaggio(e)
            return false
        } catch (e: IOException) {
            cfg.ultimoErrore = Server.messaggio(e)
            return false
        } catch (e: Exception) {
            cfg.ultimoErrore = "Errore imprevisto: ${e.message ?: e}"
            return false
        } finally {
            running = false
            notifyUi()
        }
    }

    private fun aggiornaConfig(srv: Server, cfg: ServerConfig) {
        val j = srv.get("config")
        val z = j.optJSONArray("zone") ?: JSONArray()
        if (z.length() > 0) cfg.zone = (0 until z.length()).map { z.getString(it) }
        cfg.tipiJson = (j.optJSONArray("tipi_veicolo") ?: JSONArray()).toString()
        val p = j.optJSONArray("provvedimenti") ?: JSONArray()
        if (p.length() > 0) cfg.provvedimenti = (0 until p.length()).map { p.getString(it) }
        cfg.nomeTelefono = j.optJSONObject("dispositivo")?.optString("nome") ?: cfg.nomeTelefono
    }

    /* ---------- invio ---------- */
    private fun esegui(ctx: Context, srv: Server, db: Db, op: Operazione) {
        val c = db.perUuid(op.uuid)
        val risposta: JSONObject? = when (op.tipo) {
            TipoOp.INGRESSO -> if (c == null) null else srv.post("ingressi", corpoIngresso(c))
            TipoOp.FOTO -> {
                // foto non più presente sul telefono (eliminata dalla galleria): l'operazione si chiude senza invio
                if (c != null && c.uri.isNotEmpty()) {
                    val bytes = PhotoStore.readBytes(ctx, c.uri)
                    if (bytes != null) srv.postFoto("ingressi/${op.uuid}/foto",
                        mapOf("foto_uuid" to op.dati.optString("foto_uuid"), "scattata_il" to isoIngresso(c)), c.nomeFile.ifEmpty { "${c.targa}.jpg" }, bytes)
                }
                null
            }
            TipoOp.USCITA -> srv.post("ingressi/${op.uuid}/uscita", JSONObject().put("data_uscita", op.dati.opt("data_uscita") ?: JSONObject.NULL))
            TipoOp.ZONA -> srv.post("ingressi/${op.uuid}/zona", JSONObject().put("zona", op.dati.optString("zona")))
            TipoOp.CORREZIONE -> srv.post("ingressi/${op.uuid}/correzione", op.dati)
            else -> null
        }
        db.completata(op.id)
        if (op.tipo == TipoOp.INGRESSO) db.segnaSulServer(op.uuid)
        // la risposta del portale diventa la copia locale, se non ci sono altre modifiche del telefono ancora da inviare
        risposta?.optJSONObject("permanenza")?.let { if (!db.haOperazioni(op.uuid)) db.salvaDalServer(daJson(it)) }
    }

    private fun isoIngresso(c: Censimento): String = try {
        LocalDateTime.of(LocalDate.parse(c.data, D_FMT), LocalTime.parse(c.ora, T_FMT)).toString()
    } catch (e: Exception) {
        LocalDateTime.now().withNano(0).toString()
    }

    private fun corpoIngresso(c: Censimento): JSONObject = JSONObject()
        .put("uuid", c.uuid).put("targa", c.targa).put("zona", c.zona)
        .put("data_ingresso", isoIngresso(c))
        .put("data_uscita", c.dataUscita?.let { try { LocalDate.parse(it, D_FMT).toString() } catch (e: Exception) { null } } ?: JSONObject.NULL)
        .put("marca", c.marca ?: JSONObject.NULL).put("modello", c.modello ?: JSONObject.NULL).put("colore", c.colore ?: JSONObject.NULL)
        .put("tipo_veicolo_id", c.tipoId ?: JSONObject.NULL)
        .put("pratica_numero", c.pratica ?: JSONObject.NULL).put("ente", c.ente ?: JSONObject.NULL)
        .put("provvedimento", c.provvedimento ?: JSONObject.NULL).put("note", c.note ?: JSONObject.NULL)

    /* ---------- ricezione ---------- */
    private fun ricevi(srv: Server, db: Db, cfg: ServerConfig) {
        val j = srv.get("piazzale" + (cfg.cursore?.let { "?dal=$it" } ?: ""))
        val a = j.optJSONArray("permanenze") ?: JSONArray()
        for (i in 0 until a.length()) {
            val p = a.getJSONObject(i)
            val uuid = p.getString("uuid")
            if (db.haOperazioni(uuid)) continue          // prima si inviano le modifiche fatte sul telefono
            if (p.optBoolean("rimuovi")) db.rimuoviPerUuid(uuid) else db.salvaDalServer(daJson(p))
        }
        cfg.cursore = j.getString("cursore")
    }

    private fun testo(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }

    /** Permanenza del portale → copia locale. */
    fun daJson(p: JSONObject): Censimento {
        val ing = try { LocalDateTime.parse(p.getString("data_ingresso")) } catch (e: Exception) { LocalDateTime.now() }
        val foto = p.optJSONArray("foto") ?: JSONArray()
        return Censimento(
            id = 0, targa = p.getString("targa"), zona = p.getString("zona"),
            data = ing.toLocalDate().format(D_FMT), ora = ing.toLocalTime().withNano(0).format(T_FMT),
            nomeFile = "", uri = "", ts = ing.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            dataUscita = testo(p, "data_uscita")?.let { try { LocalDate.parse(it).format(D_FMT) } catch (e: Exception) { null } },
            uuid = p.getString("uuid"),
            marca = testo(p, "marca"), modello = testo(p, "modello"), colore = testo(p, "colore"),
            tipoId = if (p.isNull("tipo_veicolo_id")) null else p.optInt("tipo_veicolo_id"),
            tipoNome = testo(p, "tipo_veicolo"),
            pratica = testo(p, "pratica_numero"), ente = testo(p, "ente"), provvedimento = testo(p, "provvedimento"), note = testo(p, "note"),
            fotoIds = (0 until foto.length()).map { foto.getJSONObject(it).getLong("id") },
            sulServer = true,
        )
    }
}

/** Job di sistema: sincronizza appena c'è rete e comunque ogni 15 minuti (anche ad app chiusa). */
class SyncJobService : JobService() {
    companion object {
        private const val JOB_SUBITO = 4242
        private const val JOB_PERIODICO = 4244

        /** Avvia una sincronizzazione appena possibile (e assicura quella periodica). */
        fun schedule(ctx: Context) {
            if (!ServerConfig(ctx).collegato()) return
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            val comp = ComponentName(ctx, SyncJobService::class.java)
            if (!SyncEngine.running) js.schedule(JobInfo.Builder(JOB_SUBITO, comp)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true).build())
            if (js.getPendingJob(JOB_PERIODICO) == null) js.schedule(JobInfo.Builder(JOB_PERIODICO, comp)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true).build())
        }

        fun annulla(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            js.cancel(JOB_SUBITO); js.cancel(JOB_PERIODICO)
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val ok = SyncEngine.run(this)
            jobFinished(params, !ok && params.jobId == JOB_SUBITO)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
