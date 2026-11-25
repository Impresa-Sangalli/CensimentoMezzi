package it.impresasangalli.censimentomezzi

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Salva le fotografie nella cartella pubblica Pictures/CensimentoMezzi,
 * con nome = targa (es. AB123CD.jpg). Le targhe duplicate ricevono un
 * suffisso progressivo (AB123CD_2.jpg, AB123CD_3.jpg, ...) e non
 * sovrascrivono mai le foto esistenti.
 */
object PhotoStore {
    const val FOLDER = "CensimentoMezzi"
    private const val COL_RELATIVE_PATH = "relative_path"   // MediaStore.MediaColumns.RELATIVE_PATH (API 29)
    private const val COL_IS_PENDING = "is_pending"         // MediaStore.MediaColumns.IS_PENDING (API 29)
    val REL_PATH = Environment.DIRECTORY_PICTURES + "/" + FOLDER + "/"

    /** Android 8 e 9 usano l'accesso diretto ai file (permesso di scrittura). */
    val legacy: Boolean get() = Build.VERSION.SDK_INT < 29

    fun legacyDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)

    /** Crea la cartella (su Android 10+ viene creata automaticamente al primo salvataggio). */
    fun ensureFolder() {
        if (legacy) legacyDir().mkdirs()
    }

    fun folderDescription(): String = "Immagini (Pictures) › $FOLDER"

    private fun existsInMediaStore(ctx: Context, name: String): Boolean {
        return try {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "$COL_RELATIVE_PATH = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(REL_PATH, name), null
            )?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun nameTaken(ctx: Context, db: Db, name: String): Boolean {
        if (db.fileNameExists(name)) return true
        return if (legacy) File(legacyDir(), name).exists() else existsInMediaStore(ctx, name)
    }

    /** Primo nome libero: TARGA.jpg, poi TARGA_2.jpg, TARGA_3.jpg ... */
    fun uniqueName(ctx: Context, db: Db, targa: String): String {
        var name = "$targa.jpg"
        var i = 2
        while (nameTaken(ctx, db, name)) {
            name = "${targa}_$i.jpg"
            i++
        }
        return name
    }

    data class Saved(val name: String, val uri: String)

    /** Copia la foto scattata (file temporaneo) nella cartella pubblica. */
    @Throws(IOException::class)
    fun save(ctx: Context, db: Db, targa: String, src: File): Saved {
        val name = uniqueName(ctx, db, targa)
        if (legacy) {
            val dir = legacyDir()
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Impossibile creare la cartella $dir")
            val dst = File(dir, name)
            if (dst.exists()) throw IOException("Il file $name esiste già")
            src.copyTo(dst, overwrite = false)
            MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath), arrayOf("image/jpeg"), null)
            return Saved(name, Uri.fromFile(dst).toString())
        }

        val resolver = ctx.contentResolver
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        values.put(COL_RELATIVE_PATH, REL_PATH)
        values.put(COL_IS_PENDING, 1)
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Impossibile creare la foto nella galleria")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Impossibile scrivere la foto")
            out.use { o -> FileInputStream(src).use { it.copyTo(o) } }
            val done = ContentValues()
            done.put(COL_IS_PENDING, 0)
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw if (e is IOException) e else IOException(e.message, e)
        }
        // Nome effettivo assegnato dal sistema (per sicurezza, nel caso lo abbia modificato)
        var realName = name
        try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.let { n -> realName = n }
            }
        } catch (_: Exception) {}
        return Saved(realName, uri.toString())
    }

    fun open(ctx: Context, uriString: String): InputStream? {
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") FileInputStream(File(uri.path!!))
            else ctx.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        }
    }

    fun readBytes(ctx: Context, uriString: String): ByteArray? =
        try { open(ctx, uriString)?.use { it.readBytes() } } catch (e: Exception) { null }

    fun delete(ctx: Context, uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") File(uri.path!!).delete()
            else ctx.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }
}
