package it.impresasangalli.censimentomezzi

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Provider minimo (equivalente a FileProvider) per:
 *  - "tmp": file temporaneo in cui l'app fotocamera scrive lo scatto;
 *  - "exp": condivisione dei file esportati su Android 8-9.
 */
class FilesProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "it.impresasangalli.censimentomezzi.files"
        const val ROOT_TMP = "tmp"
        const val ROOT_EXPORT = "exp"
        fun uriFor(root: String, name: String): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(root).appendPath(name).build()
        fun tmpDir(ctx: android.content.Context): File = File(ctx.cacheDir, "foto").apply { mkdirs() }
    }

    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File {
        val seg = uri.pathSegments
        if (seg.size != 2) throw FileNotFoundException(uri.toString())
        val name = seg[1]
        if (name.contains('/') || name.contains("..")) throw FileNotFoundException(uri.toString())
        val base = when (seg[0]) {
            ROOT_TMP -> tmpDir(context!!)
            ROOT_EXPORT -> Exporter.legacyDir()
            else -> throw FileNotFoundException(uri.toString())
        }
        return File(base, name)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileFor(uri)
        if (uri.pathSegments[0] == ROOT_EXPORT && mode != "r") throw SecurityException("Sola lettura")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val keep = cols.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }.toTypedArray()
        val c = MatrixCursor(keep, 1)
        c.addRow(keep.map { if (it == OpenableColumns.DISPLAY_NAME) f.name else f.length() }.toTypedArray())
        return c
    }

    override fun getType(uri: Uri): String {
        val n = uri.lastPathSegment ?: ""
        return when {
            n.endsWith(".jpg", true) -> "image/jpeg"
            n.endsWith(".xlsx", true) -> Exporter.MIME_XLSX
            n.endsWith(".zip", true) -> Exporter.MIME_ZIP
            else -> "application/octet-stream"
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
