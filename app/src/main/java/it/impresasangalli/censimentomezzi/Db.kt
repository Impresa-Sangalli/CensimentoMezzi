package it.impresasangalli.censimentomezzi

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

/**
 * Un mezzo nel piazzale (una "permanenza" sul sito).
 * Sul telefono c'è la copia del piazzale ricevuta dal sito più le registrazioni fatte qui e non ancora inviate.
 */
data class Censimento(
    val id: Long,
    val targa: String,
    val zona: String,
    val data: String,      // dd/MM/yyyy (entrata)
    val ora: String,       // HH:mm:ss
    val nomeFile: String,  // foto scattata su questo telefono (es. AB123CD.jpg), "" se la foto è solo sul sito
    val uri: String,       // posizione della foto sul telefono, "" se assente
    val ts: Long,          // epoch millis dell'entrata
    val dataUscita: String? = null,   // dd/MM/yyyy
    val uuid: String = "",
    val marca: String? = null,
    val modello: String? = null,
    val colore: String? = null,
    val tipoId: Int? = null,
    val tipoNome: String? = null,
    val pratica: String? = null,
    val ente: String? = null,
    val provvedimento: String? = null,
    val note: String? = null,
    val fotoIds: List<Long> = emptyList(),   // foto presenti sul sito
    val sulServer: Boolean = false,
)

/** Operazione in attesa di invio al sito. */
data class Operazione(val id: Long, val tipo: String, val uuid: String, val dati: JSONObject, val tentativi: Int, val errore: String?, val fallita: Boolean)

object TipoOp {
    const val INGRESSO = "ingresso"
    const val FOTO = "foto"
    const val USCITA = "uscita"
    const val ZONA = "zona"
    const val CORREZIONE = "correzione"
}

/** Archivio locale (SQLite): funziona anche senza rete. */
class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "censimenti.db", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE censimenti(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                targa TEXT NOT NULL,
                zona TEXT NOT NULL,
                data TEXT NOT NULL,
                ora TEXT NOT NULL,
                nome_file TEXT NOT NULL,
                uri TEXT NOT NULL,
                ts INTEGER NOT NULL,
                sync_state INTEGER NOT NULL DEFAULT 0,
                remote_name TEXT,
                sync_error TEXT,
                synced_at INTEGER NOT NULL DEFAULT 0,
                data_uscita TEXT,
                uscita_dirty INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX idx_targa ON censimenti(targa)")
        aggiungiV5(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) {
            db.execSQL("ALTER TABLE censimenti ADD COLUMN sync_state INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE censimenti ADD COLUMN remote_name TEXT")
            db.execSQL("ALTER TABLE censimenti ADD COLUMN sync_error TEXT")
            db.execSQL("ALTER TABLE censimenti ADD COLUMN synced_at INTEGER NOT NULL DEFAULT 0")
        }
        if (oldV < 3) db.execSQL("ALTER TABLE censimenti ADD COLUMN data_uscita TEXT")
        if (oldV < 4) db.execSQL("ALTER TABLE censimenti ADD COLUMN uscita_dirty INTEGER NOT NULL DEFAULT 0")
        if (oldV < 5) {
            aggiungiV5(db)
            // i censimenti già presenti ricevono un identificativo univoco: verranno inviati al sito al collegamento
            db.rawQuery("SELECT id FROM censimenti", null).use { c ->
                while (c.moveToNext()) {
                    val v = ContentValues(); v.put("uuid", UUID.randomUUID().toString())
                    db.update("censimenti", v, "id = ?", arrayOf(c.getLong(0).toString()))
                }
            }
        }
    }

    /** Versione 5 (app 1.5): dati facoltativi, collegamento al sito, coda di invio. */
    private fun aggiungiV5(db: SQLiteDatabase) {
        for (col in listOf("uuid TEXT", "marca TEXT", "modello TEXT", "colore TEXT", "tipo_id INTEGER", "tipo_nome TEXT",
                "pratica TEXT", "ente TEXT", "provvedimento TEXT", "note TEXT", "foto_ids TEXT", "sul_server INTEGER NOT NULL DEFAULT 0"))
            db.execSQL("ALTER TABLE censimenti ADD COLUMN $col")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_uuid ON censimenti(uuid)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS coda(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tipo TEXT NOT NULL,
                uuid TEXT NOT NULL,
                dati TEXT NOT NULL,
                tentativi INTEGER NOT NULL DEFAULT 0,
                errore TEXT,
                fallita INTEGER NOT NULL DEFAULT 0,
                creato INTEGER NOT NULL)"""
        )
    }

    private fun str(c: Cursor, k: String): String? { val i = c.getColumnIndexOrThrow(k); return if (c.isNull(i)) null else c.getString(i) }

    private fun read(cur: Cursor) = Censimento(
        cur.getLong(cur.getColumnIndexOrThrow("id")),
        cur.getString(cur.getColumnIndexOrThrow("targa")),
        cur.getString(cur.getColumnIndexOrThrow("zona")),
        cur.getString(cur.getColumnIndexOrThrow("data")),
        cur.getString(cur.getColumnIndexOrThrow("ora")),
        cur.getString(cur.getColumnIndexOrThrow("nome_file")),
        cur.getString(cur.getColumnIndexOrThrow("uri")),
        cur.getLong(cur.getColumnIndexOrThrow("ts")),
        str(cur, "data_uscita"),
        str(cur, "uuid") ?: "",
        str(cur, "marca"), str(cur, "modello"), str(cur, "colore"),
        str(cur, "tipo_id")?.toIntOrNull(), str(cur, "tipo_nome"),
        str(cur, "pratica"), str(cur, "ente"), str(cur, "provvedimento"), str(cur, "note"),
        str(cur, "foto_ids")?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList(),
        cur.getInt(cur.getColumnIndexOrThrow("sul_server")) != 0,
    )

    private fun query(where: String?, args: Array<String>?, order: String, limit: String? = null): List<Censimento> {
        val out = ArrayList<Censimento>()
        readableDatabase.query("censimenti", null, where, args, null, null, order, limit).use { cur ->
            while (cur.moveToNext()) out.add(read(cur))
        }
        return out
    }

    fun allNewestFirst(): List<Censimento> = query(null, null, "ts DESC, id DESC")
    fun allChronological(): List<Censimento> = query(null, null, "ts ASC, id ASC")
    fun byTarga(targa: String): List<Censimento> = query("targa = ?", arrayOf(targa), "ts DESC")
    fun get(id: Long): Censimento? = query("id = ?", arrayOf(id.toString()), "id", "1").firstOrNull()
    fun perUuid(uuid: String): Censimento? = query("uuid = ?", arrayOf(uuid), "id", "1").firstOrNull()

    private fun valori(c: Censimento): ContentValues {
        val v = ContentValues()
        v.put("targa", c.targa); v.put("zona", c.zona); v.put("data", c.data); v.put("ora", c.ora); v.put("ts", c.ts)
        v.put("data_uscita", c.dataUscita)
        v.put("marca", c.marca); v.put("modello", c.modello); v.put("colore", c.colore)
        if (c.tipoId == null) v.putNull("tipo_id") else v.put("tipo_id", c.tipoId)
        v.put("tipo_nome", c.tipoNome); v.put("pratica", c.pratica); v.put("ente", c.ente)
        v.put("provvedimento", c.provvedimento); v.put("note", c.note)
        v.put("foto_ids", c.fotoIds.joinToString(","))
        return v
    }

    /** Nuovo censimento fatto sul telefono. */
    fun insert(c: Censimento): Long {
        val v = valori(c)
        v.put("uuid", c.uuid.ifBlank { UUID.randomUUID().toString() })
        v.put("nome_file", c.nomeFile); v.put("uri", c.uri); v.put("sul_server", if (c.sulServer) 1 else 0)
        return writableDatabase.insertOrThrow("censimenti", null, v)
    }

    /** Aggiorna la copia locale con i dati di una permanenza ricevuta dal sito (foto scattate qui conservate). */
    fun salvaDalServer(c: Censimento) {
        val v = valori(c)
        v.put("sul_server", 1)
        val n = writableDatabase.update("censimenti", v, "uuid = ?", arrayOf(c.uuid))
        if (n == 0) {
            v.put("uuid", c.uuid); v.put("nome_file", ""); v.put("uri", "")
            writableDatabase.insertOrThrow("censimenti", null, v)
        }
    }

    fun rimuoviPerUuid(uuid: String) { writableDatabase.delete("censimenti", "uuid = ?", arrayOf(uuid)) }

    fun fileNameExists(name: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM censimenti WHERE nome_file = ? COLLATE NOCASE LIMIT 1", arrayOf(name)).use {
            return it.moveToFirst()
        }
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM censimenti", null).use {
            it.moveToFirst(); return it.getInt(0)
        }
    }

    /** Modifica locale dei campi (correzione, zona, uscita); l'invio al sito passa dalla coda. */
    fun aggiorna(id: Long, campi: ContentValues) {
        writableDatabase.update("censimenti", campi, "id = ?", arrayOf(id.toString()))
    }

    fun setUscita(id: Long, data: String?) {
        val v = ContentValues(); v.put("data_uscita", data); aggiorna(id, v)
    }

    fun delete(id: Long) {
        get(id)?.let { writableDatabase.delete("coda", "uuid = ?", arrayOf(it.uuid)) }
        writableDatabase.delete("censimenti", "id = ?", arrayOf(id.toString()))
    }

    /** Svuota la copia sul telefono (i dati restano sul sito). Le operazioni non inviate restano in coda. */
    fun clear() {
        writableDatabase.delete("censimenti", null, null)
    }

    // ---------------------------------------------------------------- coda di invio

    fun accoda(tipo: String, uuid: String, dati: JSONObject = JSONObject()) {
        val v = ContentValues()
        v.put("tipo", tipo); v.put("uuid", uuid); v.put("dati", dati.toString()); v.put("creato", System.currentTimeMillis())
        writableDatabase.insertOrThrow("coda", null, v)
    }

    private fun op(c: Cursor) = Operazione(c.getLong(0), c.getString(1), c.getString(2), try { JSONObject(c.getString(3)) } catch (e: Exception) { JSONObject() },
        c.getInt(4), if (c.isNull(5)) null else c.getString(5), c.getInt(6) != 0)

    fun prossimaOperazione(): Operazione? {
        readableDatabase.rawQuery("SELECT id, tipo, uuid, dati, tentativi, errore, fallita FROM coda WHERE fallita = 0 ORDER BY id LIMIT 1", null).use {
            return if (it.moveToFirst()) op(it) else null
        }
    }

    fun operazioniFallite(): List<Operazione> {
        val out = ArrayList<Operazione>()
        readableDatabase.rawQuery("SELECT id, tipo, uuid, dati, tentativi, errore, fallita FROM coda WHERE fallita = 1 ORDER BY id", null).use {
            while (it.moveToNext()) out.add(op(it))
        }
        return out
    }

    fun completata(id: Long) { writableDatabase.delete("coda", "id = ?", arrayOf(id.toString())) }

    fun tentativo(id: Long, errore: String, definitivo: Boolean) {
        writableDatabase.execSQL("UPDATE coda SET tentativi = tentativi + 1, errore = ?, fallita = ? WHERE id = ?", arrayOf<Any>(errore, if (definitivo) 1 else 0, id))
    }

    fun inCoda(): Int = conta("SELECT COUNT(*) FROM coda WHERE fallita = 0")
    fun fallite(): Int = conta("SELECT COUNT(*) FROM coda WHERE fallita = 1")
    fun haOperazioni(uuid: String): Boolean = conta("SELECT COUNT(*) FROM coda WHERE uuid = '" + uuid.replace("'", "") + "'") > 0
    fun erroreDi(uuid: String): String? {
        readableDatabase.rawQuery("SELECT errore FROM coda WHERE uuid = ? AND errore IS NOT NULL ORDER BY id DESC LIMIT 1", arrayOf(uuid)).use {
            return if (it.moveToFirst()) it.getString(0) else null
        }
    }
    fun riprovaFallite() { writableDatabase.execSQL("UPDATE coda SET fallita = 0, errore = NULL WHERE fallita = 1") }
    fun scartaFallite() { writableDatabase.delete("coda", "fallita = 1", null) }

    private fun conta(sql: String): Int { readableDatabase.rawQuery(sql, null).use { it.moveToFirst(); return it.getInt(0) } }

    /** Al collegamento: mette in coda i censimenti fatti sul telefono e non ancora presenti sul sito. */
    fun accodaNonInviati(): Int {
        var n = 0
        for (c in query("sul_server = 0", null, "ts ASC, id ASC")) {
            if (haOperazioni(c.uuid)) continue
            accoda(TipoOp.INGRESSO, c.uuid)
            if (c.uri.isNotEmpty()) accoda(TipoOp.FOTO, c.uuid, JSONObject().put("foto_uuid", UUID.randomUUID().toString()))
            n++
        }
        return n
    }

    fun segnaSulServer(uuid: String) {
        val v = ContentValues(); v.put("sul_server", 1)
        writableDatabase.update("censimenti", v, "uuid = ?", arrayOf(uuid))
    }
}
