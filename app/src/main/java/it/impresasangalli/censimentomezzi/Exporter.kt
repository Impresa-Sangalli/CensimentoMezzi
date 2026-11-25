package it.impresasangalli.censimentomezzi

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Esportazione del censimento completo:
 *  - file Excel (.xlsx)
 *  - archivio ZIP con il file Excel e tutte le fotografie (cartella "foto/")
 * I file vengono salvati in Download/CensimentoMezzi.
 */
object Exporter {
    const val FOLDER = "CensimentoMezzi"
    const val MIME_XLSX = XlsxWriter.MIME
    const val MIME_ZIP = "application/zip"
    val HEADER = listOf("Targa", "Zona parcheggio", "Data censimento", "Ora censimento", "Nome file fotografia", "Data uscita")
    val WIDTHS = listOf(16.0, 18.0, 18.0, 17.0, 28.0, 16.0)
    /** Colonna "Nome file fotografia": unica per ogni censimento, identifica la riga su SharePoint. */
    const val KEY_COLUMN = 4
    private val D_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val T_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun legacyDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)

    fun folderDescription(): String = "Download › $FOLDER"

    /** Risultato di un'esportazione. */
    data class Result(val name: String, val uri: Uri, val mime: String, val rows: Int, val photos: Int, val missing: List<String>)

    private fun rowsFor(list: List<Censimento>): List<List<Cell>> = list.map { cells(it) }

    /** Riga Excel di un censimento: Targa, Zona, Data, Ora, Nome file foto. */
    fun cells(c: Censimento): List<Cell> {
        val dt: LocalDateTime? = try {
            LocalDateTime.of(LocalDate.parse(c.data, D_FMT), LocalTime.parse(c.ora, T_FMT))
        } catch (e: Exception) {
            null
        } ?: LocalDateTime.ofInstant(Instant.ofEpochMilli(c.ts), ZoneId.systemDefault())
        return listOf(
            Cell.Text(c.targa),
            Cell.Text(c.zona),
            Cell.Date(dt!!.toLocalDate()),
            Cell.Time(dt.toLocalTime().withNano(0)),
            Cell.Text(c.nomeFile),
            c.dataUscita?.let { u ->
                try { Cell.Date(LocalDate.parse(u, D_FMT)) } catch (e: Exception) { Cell.Text(u) }
            } ?: Cell.EmptyDate
        )
    }

    fun writeXlsx(list: List<Censimento>, out: OutputStream) {
        XlsxWriter.write(out, "Censimento", HEADER, WIDTHS, rowsFor(list))
    }

    private fun stamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))

    /** Crea un file nella cartella Download/CensimentoMezzi e restituisce (uri, stream, nome). */
    private fun createDownload(ctx: Context, baseName: String, ext: String, mime: String): Triple<Uri, OutputStream, String> {
        if (Build.VERSION.SDK_INT < 29) {
            val dir = legacyDir()
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Impossibile creare la cartella $dir")
            var f = File(dir, "$baseName.$ext")
            var i = 2
            while (f.exists()) { f = File(dir, "${baseName}_$i.$ext"); i++ }
            return Triple(FilesProvider.uriFor(FilesProvider.ROOT_EXPORT, f.name), FileOutputStream(f), f.name)
        }
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.$ext")
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
        values.put("relative_path", Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/")
        values.put("is_pending", 1)
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Impossibile creare il file in Download")
        val os = resolver.openOutputStream(uri) ?: throw IOException("Impossibile scrivere il file")
        var name = "$baseName.$ext"
        try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.let { n -> name = n }
            }
        } catch (_: Exception) {}
        return Triple(uri, os, name)
    }

    private fun finish(ctx: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues(); v.put("is_pending", 0)
            ctx.contentResolver.update(uri, v, null, null)
        } else {
            val f = File(legacyDir(), uri.lastPathSegment ?: return)
            MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), null, null)
        }
    }

    private fun abort(ctx: Context, uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.delete(uri, null, null)
            else File(legacyDir(), uri.lastPathSegment ?: return).delete()
        } catch (_: Exception) {}
    }

    @Throws(IOException::class)
    fun exportXlsx(ctx: Context, db: Db): Result {
        val list = db.allChronological()
        val (uri, os, name) = createDownload(ctx, "Censimento_Mezzi_${stamp()}", "xlsx", MIME_XLSX)
        try {
            os.use { writeXlsx(list, it) }
            finish(ctx, uri)
        } catch (e: Exception) {
            abort(ctx, uri)
            throw if (e is IOException) e else IOException(e.message, e)
        }
        return Result(name, uri, MIME_XLSX, list.size, 0, emptyList())
    }

    /** ZIP: Censimento_Mezzi_xxx.xlsx + foto/<nome>.jpg */
    @Throws(IOException::class)
    fun exportZip(ctx: Context, db: Db, progress: (Int, Int) -> Unit): Result {
        val list = db.allChronological()
        val base = "Censimento_Mezzi_${stamp()}"
        val (uri, os, name) = createDownload(ctx, base, "zip", MIME_ZIP)
        val missing = ArrayList<String>()
        var photos = 0
        try {
            os.use { raw ->
                val zip = ZipOutputStream(raw.buffered(64 * 1024))
                zip.setLevel(Deflater.BEST_SPEED)
                val xlsx = ByteArrayOutputStream()
                writeXlsx(list, xlsx)
                zip.putNextEntry(ZipEntry("$base.xlsx"))
                zip.write(xlsx.toByteArray())
                zip.closeEntry()
                val used = HashSet<String>()
                list.forEachIndexed { i, c ->
                    progress(i + 1, list.size)
                    if (!used.add(c.nomeFile.lowercase())) return@forEachIndexed
                    val input = PhotoStore.open(ctx, c.uri)
                    if (input == null) {
                        missing.add(c.nomeFile)
                    } else {
                        input.use {
                            zip.putNextEntry(ZipEntry("foto/" + c.nomeFile))
                            it.copyTo(zip, 64 * 1024)
                            zip.closeEntry()
                        }
                        photos++
                    }
                }
                zip.finish()
                zip.flush()
            }
            finish(ctx, uri)
        } catch (e: Exception) {
            abort(ctx, uri)
            throw if (e is IOException) e else IOException(e.message, e)
        }
        return Result(name, uri, MIME_ZIP, list.size, photos, missing)
    }
}
