package it.impresasangalli.censimentomezzi

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLException

/**
 * Collegamento al portale eDepositeria (https://edepositeria.impresasangalli.it, rete aziendale).
 * Il telefono si collega una volta con il codice di attivazione generato dall'ufficio e riceve un token,
 * conservato cifrato con una chiave del Keystore Android.
 */
class ServerConfig(ctx: Context) {
    companion object {
        const val URL_PREDEFINITO = "https://edepositeria.impresasangalli.it"
        const val CONFIG = "server"
    }

    private val p = ctx.applicationContext.getSharedPreferences(CONFIG, Context.MODE_PRIVATE)
    private fun put(k: String, v: String?) = p.edit().putString(k, v).commit()

    var url: String get() = p.getString("url", URL_PREDEFINITO) ?: URL_PREDEFINITO
        set(v) { put("url", v.trim().trimEnd('/')) }
    var token: String? get() = Secure.dec(p.getString("token", null)); set(v) { put("token", v?.let { Secure.enc(it) }) }
    var nomeTelefono: String? get() = p.getString("nome", null); set(v) { put("nome", v) }
    var cursore: String? get() = p.getString("cursore", null); set(v) { put("cursore", v) }
    var ultimaSync: Long get() = p.getLong("ultimaSync", 0); set(v) { p.edit().putLong("ultimaSync", v).commit() }
    var ultimoErrore: String? get() = p.getString("errore", null); set(v) { put("errore", v) }
    var scollegato: Boolean get() = p.getBoolean("scollegato", false); set(v) { p.edit().putBoolean("scollegato", v).commit() }

    // Dati di configurazione ricevuti dal portale (zone, tipi di veicolo, provvedimenti), usati anche senza rete
    var zone: List<String>
        get() = p.getString("zone", null)?.split(',')?.filter { it.isNotBlank() } ?: MainActivity.ZONE
        set(v) { put("zone", v.joinToString(",")) }
    var tipiJson: String? get() = p.getString("tipi", null); set(v) { put("tipi", v) }
    var provvedimenti: List<String>
        get() = p.getString("provv", null)?.split('|')?.filter { it.isNotBlank() } ?: listOf("Sequestro", "Fermo amministrativo", "Rimozione", "Confisca", "Custodia", "Altro")
        set(v) { put("provv", v.joinToString("|")) }
    fun tipi(): List<Pair<Int, String>> = try {
        val a = JSONArray(tipiJson ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getInt("id") to o.getString("nome") } }
    } catch (e: Exception) { emptyList() }

    fun collegato(): Boolean = token != null

    // PIN dell'amministratore: protegge collegamento, scollegamento e indirizzo del server
    fun pinImpostato(): Boolean = p.getString("pin", null) != null
    fun impostaPin(pin: String) { val sale = ByteArray(16).also { SecureRandom().nextBytes(it) }; put("pinSale", b64(sale)); put("pin", hashPin(pin, sale)) }
    fun verificaPin(pin: String): Boolean {
        val sale = p.getString("pinSale", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        return MessageDigest.isEqual(hashPin(pin, sale).toByteArray(), (p.getString("pin", "") ?: "").toByteArray())
    }
    private fun hashPin(pin: String, sale: ByteArray): String {
        var h = sale + pin.toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        repeat(20000) { h = md.digest(h + sale) }
        return b64(h)
    }
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    fun scollega() { token = null; cursore = null; nomeTelefono = null; ultimoErrore = null; scollegato = true }
}

/** Errore restituito dal portale: [codice] è la chiave dell'errore (es. "dati_non_validi", "non_autorizzato"). */
class ErroreServer(val http: Int, val codice: String, msg: String) : IOException(msg) {
    /** Errori definitivi per quell'operazione: riprovare non serve (dati non validi, permanenza eliminata, ...). */
    val definitivo: Boolean get() = http == 400 || http == 404 || http == 410 || http == 413
    val nonAutorizzato: Boolean get() = http == 401
}

/** Client HTTP minimo per le API /api/v1 (HttpURLConnection, senza librerie esterne). */
class Server(private val ctx: Context) {
    private val cfg = ServerConfig(ctx)

    private fun versioneApp(): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    private fun apri(metodo: String, percorso: String, token: Boolean = true): HttpURLConnection {
        val c = URL(cfg.url + "/api/v1/" + percorso).openConnection() as HttpURLConnection
        c.requestMethod = metodo
        c.connectTimeout = 10000
        c.readTimeout = 60000
        c.useCaches = false
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("X-App-Versione", versioneApp())
        if (token) {
            val t = cfg.token ?: throw ErroreServer(401, "non_autorizzato", "Telefono non collegato al server")
            c.setRequestProperty("Authorization", "Bearer $t")
            c.setRequestProperty("X-Token", t)
        }
        return c
    }

    private fun leggi(c: HttpURLConnection): JSONObject {
        try {
            val code = c.responseCode
            val txt = (if (code >= 400) c.errorStream else c.inputStream)?.use { s -> val b = ByteArrayOutputStream(); s.copyTo(b); b.toString("UTF-8") } ?: ""
            val j = try { JSONObject(txt) } catch (e: Exception) { JSONObject() }
            if (code in 200..299 && j.optBoolean("ok", false)) return j
            throw ErroreServer(code, j.optString("codice", "errore"),
                j.optString("error").ifEmpty { if (code == 404) "Servizio non trovato sul server (indirizzo errato?)" else "Errore del server ($code)" })
        } finally {
            c.disconnect()
        }
    }

    fun get(percorso: String, token: Boolean = true): JSONObject = leggi(apri("GET", percorso, token))

    fun post(percorso: String, corpo: JSONObject, token: Boolean = true): JSONObject {
        val c = apri("POST", percorso, token)
        val b = corpo.toString().toByteArray(Charsets.UTF_8)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setFixedLengthStreamingMode(b.size)
        c.outputStream.use { it.write(b) }
        return leggi(c)
    }

    /** Invio di una foto JPEG in multipart/form-data. */
    fun postFoto(percorso: String, campi: Map<String, String>, nomeFile: String, dati: ByteArray): JSONObject {
        val c = apri("POST", percorso)
        val confine = "----CensimentoMezzi" + System.currentTimeMillis()
        val corpo = ByteArrayOutputStream()
        DataOutputStream(corpo).use { o ->
            for ((k, v) in campi) {
                o.writeBytes("--$confine\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n")
                o.write(v.toByteArray(Charsets.UTF_8)); o.writeBytes("\r\n")
            }
            o.writeBytes("--$confine\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$nomeFile\"\r\nContent-Type: image/jpeg\r\n\r\n")
            o.write(dati); o.writeBytes("\r\n--$confine--\r\n")
        }
        val b = corpo.toByteArray()
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$confine")
        c.setFixedLengthStreamingMode(b.size)
        c.outputStream.use { it.write(b) }
        return leggi(c)
    }

    /** Scarica una foto (ridotta) nella cache dell'app; restituisce il file. */
    fun scaricaFoto(id: Long, lato: Int): File {
        val dir = File(ctx.cacheDir, "foto_server").apply { mkdirs() }
        val f = File(dir, "${id}_$lato.jpg")
        if (f.exists() && f.length() > 0) return f
        val c = apri("GET", "foto/$id?lato=$lato")
        try {
            if (c.responseCode != 200) throw ErroreServer(c.responseCode, "foto", "Foto non disponibile")
            val tmp = File(dir, "${id}_$lato.part")
            c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            tmp.renameTo(f)
        } finally {
            c.disconnect()
        }
        return f
    }

    /** Collega il telefono con il codice di attivazione; salva token e nome. */
    fun attiva(codice: String, nome: String): String {
        val j = post("dispositivi/attiva", JSONObject()
            .put("codice", codice).put("nome", nome)
            .put("modello", "${Build.MANUFACTURER} ${Build.MODEL}".trim()).put("versione_app", versioneApp()), token = false)
        cfg.token = j.getString("token")
        cfg.nomeTelefono = j.getJSONObject("dispositivo").getString("nome")
        cfg.cursore = null
        cfg.scollegato = false
        return cfg.nomeTelefono ?: nome
    }

    companion object {
        /** Messaggio comprensibile per un errore di rete. */
        fun messaggio(e: Exception): String = when (e) {
            is ErroreServer -> e.message ?: "Errore del server"
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> "Server non raggiungibile: il telefono deve essere sul Wi-Fi aziendale"
            is SocketTimeoutException -> "Il server non risponde (rete lenta o assente)"
            is SSLException -> "Connessione sicura non riuscita"
            else -> e.message ?: e.toString()
        }
    }
}

/** Cifratura AES-GCM con chiave non esportabile nel Keystore Android. */
object Secure {
    private const val ALIAS = "censimento_mezzi_token"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return kg.generateKey()
    }

    fun enc(plain: String): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    fun dec(stored: String?): String? {
        if (stored == null) return null
        return try {
            val parts = stored.split(':')
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
