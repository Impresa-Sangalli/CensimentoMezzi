package it.impresasangalli.censimentomezzi

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Stato dell'invio online di un censimento. */
object SyncState {
    const val PENDING = 0       // da inviare
    const val PHOTO_SENT = 1    // foto caricata, manca la riga Excel
    const val SENT = 2          // inviato
    const val EXCLUDED = 3      // escluso dall'invio (censimenti precedenti all'attivazione)
    const val FAILED = 4        // errore non recuperabile automaticamente
}

/** Un singolo censimento di un mezzo. */
data class Censimento(
    val id: Long,
    val targa: String,
    val zona: String,
    val data: String,      // dd/MM/yyyy
    val ora: String,       // HH:mm:ss
    val nomeFile: String,  // es. AB123CD.jpg
    val uri: String,       // posizione della foto sul telefono
    val ts: Long,          // epoch millis
    val syncState: Int = SyncState.PENDING,
    val remoteName: String? = null,
    val syncError: String? = null,
    val syncedAt: Long = 0,
    val dataUscita: String? = null   // dd/MM/yyyy, facoltativa: compilata quando il mezzo esce
)

/** Archivio locale persistente (SQLite), nessuna connessione richiesta. */
class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "censimenti.db", null, 3) {

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
                data_uscita TEXT)"""
        )
        db.execSQL("CREATE INDEX idx_targa ON censimenti(targa)")
        db.execSQL("CREATE INDEX idx_sync ON censimenti(sync_state)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) {
            db.execSQL("ALTER TABLE censimenti ADD COLUMN sync_state INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE censimenti ADD COLUMN remote_name TEXT")
            db.execSQL("ALTER TABLE censimenti ADD COLUMN sync_error TEXT")
            db.execSQL("ALTER TABLE censimenti ADD COLUMN synced_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync ON censimenti(sync_state)")
        }
        if (oldV < 3) {
            db.execSQL("ALTER TABLE censimenti ADD COLUMN data_uscita TEXT")
        }
    }

    fun insert(c: Censimento): Long {
        val v = ContentValues()
        v.put("targa", c.targa); v.put("zona", c.zona); v.put("data", c.data); v.put("ora", c.ora)
        v.put("nome_file", c.nomeFile); v.put("uri", c.uri); v.put("ts", c.ts)
        v.put("sync_state", c.syncState)
        v.put("data_uscita", c.dataUscita)
        return writableDatabase.insertOrThrow("censimenti", null, v)
    }

    private fun read(cur: Cursor) = Censimento(
        cur.getLong(cur.getColumnIndexOrThrow("id")),
        cur.getString(cur.getColumnIndexOrThrow("targa")),
        cur.getString(cur.getColumnIndexOrThrow("zona")),
        cur.getString(cur.getColumnIndexOrThrow("data")),
        cur.getString(cur.getColumnIndexOrThrow("ora")),
        cur.getString(cur.getColumnIndexOrThrow("nome_file")),
        cur.getString(cur.getColumnIndexOrThrow("uri")),
        cur.getLong(cur.getColumnIndexOrThrow("ts")),
        cur.getInt(cur.getColumnIndexOrThrow("sync_state")),
        cur.getString(cur.getColumnIndexOrThrow("remote_name")),
        cur.getString(cur.getColumnIndexOrThrow("sync_error")),
        cur.getLong(cur.getColumnIndexOrThrow("synced_at")),
        cur.getString(cur.getColumnIndexOrThrow("data_uscita"))
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

    /** Imposta (o cancella, con null) la data di uscita dal piazzale. */
    fun setUscita(id: Long, data: String?) {
        val v = ContentValues()
        v.put("data_uscita", data)
        upd(id, v)
    }

    fun delete(id: Long) {
        writableDatabase.delete("censimenti", "id = ?", arrayOf(id.toString()))
    }

    fun clear() {
        writableDatabase.delete("censimenti", null, null)
    }

    // ---------------------------------------------------------------- invio online

    fun nextPending(): Censimento? =
        query("sync_state IN (${SyncState.PENDING}, ${SyncState.PHOTO_SENT})", null, "ts ASC, id ASC", "1").firstOrNull()

    /** Numero di censimenti per stato di invio. */
    fun syncCounts(): Map<Int, Int> {
        val m = HashMap<Int, Int>()
        readableDatabase.rawQuery("SELECT sync_state, COUNT(*) FROM censimenti GROUP BY sync_state", null).use {
            while (it.moveToNext()) m[it.getInt(0)] = it.getInt(1)
        }
        return m
    }

    private fun upd(id: Long, v: ContentValues) {
        writableDatabase.update("censimenti", v, "id = ?", arrayOf(id.toString()))
    }

    fun setPhotoSent(id: Long, remoteName: String, note: String?) {
        val v = ContentValues()
        v.put("sync_state", SyncState.PHOTO_SENT); v.put("remote_name", remoteName); v.put("sync_error", note)
        upd(id, v)
    }

    fun setSent(id: Long) {
        val v = ContentValues()
        v.put("sync_state", SyncState.SENT); v.put("synced_at", System.currentTimeMillis())
        upd(id, v)
    }

    fun setFailed(id: Long, msg: String) {
        val v = ContentValues()
        v.put("sync_state", SyncState.FAILED); v.put("sync_error", msg)
        upd(id, v)
    }

    /** Rimette in coda i censimenti in errore (riprendendo dal punto in cui si erano fermati). */
    fun retryFailed() {
        writableDatabase.execSQL(
            "UPDATE censimenti SET sync_state = CASE WHEN remote_name IS NULL THEN ${SyncState.PENDING} ELSE ${SyncState.PHOTO_SENT} END, sync_error = NULL WHERE sync_state = ${SyncState.FAILED}"
        )
    }

    /** Esclude dall'invio i censimenti in attesa (es. prove fatte prima dell'attivazione). */
    fun excludePending() {
        writableDatabase.execSQL("UPDATE censimenti SET sync_state = ${SyncState.EXCLUDED} WHERE sync_state = ${SyncState.PENDING}")
    }
}
