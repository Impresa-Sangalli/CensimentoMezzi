package it.impresasangalli.censimentomezzi

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** Errore restituito da Microsoft Graph. */
class GraphException(val code: Int, val graphCode: String?, msg: String) : IOException(msg) {
    /** Errori temporanei: si riprova più tardi. */
    val retryable: Boolean get() = code == 408 || code == 423 || code == 429 || code >= 500 ||
        (code == 409 && graphCode != "nameAlreadyExists")
}

/** L'utente deve rifare l'accesso Microsoft. */
class AuthRequiredException(msg: String) : IOException(msg)

/**
 * Client minimo per Microsoft Graph (SharePoint/OneDrive + API Excel),
 * basato su HttpURLConnection, senza librerie esterne.
 * [token] restituisce l'access token; con force=true deve rinnovarlo.
 */
class Graph(
    private val token: (Boolean) -> String,
    private val base: String = "https://graph.microsoft.com/v1.0"
) {
    private class Resp(val code: Int, val body: String)

    companion object {
        /** Codifica ogni segmento di un percorso (spazi → %20). */
        fun enc(path: String): String = path.split('/').filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }

    private fun setMethod(c: HttpURLConnection, m: String) {
        try {
            c.requestMethod = m
        } catch (e: java.net.ProtocolException) {
            // HttpURLConnection della JVM non supporta PATCH (Android sì)
            val f = HttpURLConnection::class.java.getDeclaredField("method")
            f.isAccessible = true
            f.set(c, m)
        }
    }

    private fun req(method: String, path: String, body: ByteArray? = null, ctype: String? = null, retryAuth: Boolean = true): Resp {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            setMethod(c, method)
            c.connectTimeout = 20000
            c.readTimeout = 120000
            c.useCaches = false
            c.setRequestProperty("Authorization", "Bearer " + token(false))
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", ctype ?: "application/json")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val txt = stream?.use { s -> val b = ByteArrayOutputStream(); s.copyTo(b); b.toString("UTF-8") } ?: ""
            if (code == 401 && retryAuth) {
                token(true)
                return req(method, path, body, ctype, false)
            }
            return Resp(code, txt)
        } finally {
            c.disconnect()
        }
    }

    private fun fail(r: Resp, what: String): GraphException {
        var gc: String? = null
        var msg = ""
        try {
            val e = JSONObject(r.body).optJSONObject("error")
            if (e != null) { gc = e.optString("code").ifEmpty { null }; msg = e.optString("message") }
        } catch (_: Exception) {}
        val text = when (r.code) {
            401 -> "Accesso Microsoft non valido"
            403 -> "Accesso negato ($what): l'account non ha i permessi sul sito SharePoint"
            404 -> "Non trovato ($what)"
            429 -> "Troppe richieste a Microsoft, nuovo tentativo più tardi"
            else -> if (r.code >= 500) "Servizio Microsoft non disponibile (${r.code}), nuovo tentativo più tardi"
                    else "Errore ${r.code} ($what)"
        }
        return GraphException(r.code, gc, if (msg.isNotEmpty() && r.code != 429 && r.code < 500) "$text: $msg" else text)
    }

    private fun json(r: Resp, what: String, vararg ok: Int): JSONObject {
        if (r.code !in ok) throw fail(r, what)
        return if (r.body.isBlank()) JSONObject() else JSONObject(r.body)
    }

    /** ID del sito da un URL come https://azienda.sharepoint.com/sites/NomeSito */
    fun resolveSite(siteUrl: String): String {
        val u = URL(siteUrl.trim())
        val segs = URLDecoder.decode(u.path ?: "", "UTF-8").split('/').filter { it.isNotEmpty() }
        val sitePath = if (segs.size >= 2 && (segs[0].equals("sites", true) || segs[0].equals("teams", true)))
            segs.subList(0, 2).joinToString("/") else ""
        val p = if (sitePath.isEmpty()) "/sites/${u.host}" else "/sites/${u.host}:/${enc(sitePath)}"
        return json(req("GET", p), "sito SharePoint", 200).getString("id")
    }

    /** Raccolta documenti predefinita del sito. */
    fun driveId(siteId: String): String =
        json(req("GET", "/sites/$siteId/drive"), "raccolta Documenti", 200).getString("id")

    /** Elemento per percorso, oppure null se non esiste. */
    fun item(driveId: String, path: String): JSONObject? {
        val r = req("GET", "/drives/$driveId/root:/${enc(path)}")
        if (r.code == 404) return null
        return json(r, path, 200)
    }

    /** Carica un file; restituisce null se esiste già (non sovrascrive mai). */
    fun upload(driveId: String, path: String, data: ByteArray, mime: String): JSONObject? {
        val r = req("PUT", "/drives/$driveId/root:/${enc(path)}:/content?%40microsoft.graph.conflictBehavior=fail", data, mime)
        if (r.code == 409) return null
        return json(r, path, 200, 201)
    }

    fun tables(driveId: String, itemId: String): List<String> {
        val a = json(req("GET", "/drives/$driveId/items/$itemId/workbook/tables"), "tabelle Excel", 200).optJSONArray("value") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it).getString("name") }
    }

    /** Aggiunge una riga alla tabella Excel; restituisce l'indice della riga. */
    fun addRow(driveId: String, itemId: String, table: String, values: JSONArray): Int {
        val body = JSONObject().put("values", JSONArray().put(values)).toString().toByteArray(Charsets.UTF_8)
        val r = req("POST", "/drives/$driveId/items/$itemId/workbook/tables/${enc(table)}/rows/add", body)
        return json(r, "riga Excel", 200, 201).optInt("index", -1)
    }

    /** Imposta il formato numerico (data/ora/testo) della riga appena aggiunta. */
    fun formatRow(driveId: String, itemId: String, table: String, index: Int, formats: JSONArray) {
        val body = JSONObject().put("numberFormat", JSONArray().put(formats)).toString().toByteArray(Charsets.UTF_8)
        val r = req("PATCH", "/drives/$driveId/items/$itemId/workbook/tables/${enc(table)}/rows/itemAt(index=$index)/range", body)
        json(r, "formato riga Excel", 200, 204)
    }
}

/**
 * Archivio del censimento su SharePoint:
 *   <cartella>/foto/TARGA.jpg  (mai sovrascritte: TARGA_2.jpg, TARGA_3.jpg ...)
 *   <cartella>/<file Excel>     (una riga per ogni censimento, in una Tabella Excel)
 */
class RemoteArchive(
    private val g: Graph,
    private val driveId: String,
    folder: String,
    private val excelName: String,
    tableName: String,
    var excelItemId: String?
) {
    private val folder = folder.trim().trim('/')
    var table: String = tableName
        private set

    private fun p(rel: String) = if (folder.isEmpty()) rel else "$folder/$rel"

    fun folderExists(): Boolean = folder.isEmpty() || g.item(driveId, folder) != null

    /** Carica la foto e restituisce il nome effettivo usato su SharePoint. */
    fun uploadPhoto(targa: String, preferred: String, bytes: ByteArray): String {
        var name = preferred
        var n = 2
        repeat(500) {
            val path = p("foto/$name")
            if (g.item(driveId, path) == null && g.upload(driveId, path, bytes, "image/jpeg") != null) return name
            name = "${targa}_$n.jpg"
            n++
        }
        throw IOException("Impossibile assegnare un nome alla foto $preferred")
    }

    private fun value(c: Cell): Any = when (c) {
        is Cell.Text -> c.v
        is Cell.Date -> XlsxWriter.serial(c.v)
        is Cell.Time -> XlsxWriter.fraction(c.v)
        Cell.EmptyDate -> ""
    }

    private fun format(c: Cell): String = when (c) {
        is Cell.Text -> "@"
        is Cell.Date -> "dd/mm/yyyy"
        is Cell.Time -> "hh:mm:ss"
        Cell.EmptyDate -> "dd/mm/yyyy"
    }

    /** Aggiunge la riga al file Excel condiviso (lo crea se non esiste). */
    fun appendRow(sheetName: String, header: List<String>, widths: List<Double>, row: List<Cell>) {
        val path = p(excelName)
        repeat(3) {
            var id = excelItemId ?: g.item(driveId, path)?.getString("id")
            if (id == null) {
                val out = ByteArrayOutputStream()
                XlsxWriter.write(out, sheetName, header, widths, listOf(row), table)
                val created = g.upload(driveId, path, out.toByteArray(), XlsxWriter.MIME)
                if (created != null) { excelItemId = created.getString("id"); return }
                id = g.item(driveId, path)?.getString("id") ?: throw IOException("File Excel non accessibile")
            }
            excelItemId = id
            try {
                val idx = g.addRow(driveId, id, table, JSONArray(row.map { value(it) }))
                if (idx >= 0) try { g.formatRow(driveId, id, table, idx, JSONArray(row.map { format(it) })) } catch (_: Exception) {}
                return
            } catch (e: GraphException) {
                if (e.code != 404) throw e
                if (g.item(driveId, path) == null) {          // file Excel eliminato: verrà ricreato
                    excelItemId = null
                } else {                                      // tabella rinominata: usa la prima del file
                    table = g.tables(driveId, id).firstOrNull()
                        ?: throw GraphException(404, "tableNotFound", "Nel file $excelName non è presente una tabella Excel")
                }
            }
        }
        throw IOException("Impossibile aggiornare il file Excel $excelName")
    }
}
