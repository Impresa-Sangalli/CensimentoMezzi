package it.impresasangalli.censimentomezzi

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Accesso con account Microsoft 365 (OAuth 2.0 authorization code + PKCE),
 * tramite il browser del telefono. Nessuna libreria esterna.
 */
object Auth {
    const val SCHEME = "it.impresasangalli.censimentomezzi"
    const val REDIRECT_URI = "$SCHEME://auth"
    const val SCOPES = "openid profile offline_access User.Read Files.ReadWrite.All"

    private val rnd = SecureRandom()

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun random(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    private fun base(cfg: Config) = "https://login.microsoftonline.com/${Uri.encode(cfg.tenant.trim())}/oauth2/v2.0"

    fun signedIn(ctx: Context) = Config(ctx).refreshToken != null

    /** Intent che apre la pagina di accesso Microsoft (Custom Tab se disponibile). */
    fun loginIntent(ctx: Context): Intent {
        val cfg = Config(ctx)
        val verifier = b64(random(48))
        val state = b64(random(16))
        cfg.pkceVerifier = verifier
        cfg.authState = state
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val uri = Uri.parse(base(cfg) + "/authorize").buildUpon()
            .appendQueryParameter("client_id", cfg.clientId.trim())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("prompt", "select_account")
            .build()
        val i = Intent(Intent.ACTION_VIEW, uri)
        // Protocollo Custom Tabs (senza libreria): apre la pagina dentro l'app
        val extras = Bundle()
        extras.putBinder("android.support.customtabs.extra.SESSION", null)
        i.putExtras(extras)
        i.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", 0xFF1B7F3B.toInt())
        return i
    }

    fun isRedirect(uri: Uri?): Boolean = uri != null && uri.scheme == SCHEME && uri.host == "auth"

    /** Completa l'accesso dopo il ritorno dal browser (da eseguire fuori dal thread UI). */
    fun completeLogin(ctx: Context, uri: Uri): String {
        uri.getQueryParameter("error")?.let { err ->
            throw IOException(uri.getQueryParameter("error_description")?.lineSequence()?.firstOrNull() ?: err)
        }
        val cfg = Config(ctx)
        val code = uri.getQueryParameter("code") ?: throw IOException("Risposta di accesso non valida")
        if (uri.getQueryParameter("state") != cfg.authState) throw IOException("Sessione di accesso non valida, riprovare")
        val verifier = cfg.pkceVerifier ?: throw IOException("Sessione di accesso scaduta, riprovare")
        val j = tokenRequest(
            cfg, linkedMapOf(
                "grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT_URI,
                "code_verifier" to verifier, "scope" to SCOPES
            )
        )
        store(cfg, j)
        cfg.pkceVerifier = null
        cfg.authState = null
        cfg.authExpired = false
        cfg.lastError = null
        return cfg.account ?: ""
    }

    private fun store(cfg: Config, j: JSONObject) {
        cfg.accessToken = j.getString("access_token")
        cfg.accessExp = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000
        j.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { cfg.refreshToken = it }
        j.optString("id_token").takeIf { it.isNotEmpty() }?.let { idt ->
            try {
                val payload = JSONObject(String(Base64.decode(idt.split('.')[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8))
                val name = payload.optString("preferred_username").ifEmpty { payload.optString("upn") }.ifEmpty { payload.optString("name") }
                if (name.isNotEmpty()) cfg.account = name
            } catch (_: Exception) {}
        }
    }

    private fun tokenRequest(cfg: Config, params: Map<String, String>): JSONObject {
        val all = LinkedHashMap(params)
        all["client_id"] = cfg.clientId.trim()
        val body = all.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
            .toByteArray(Charsets.UTF_8)
        val c = URL(base(cfg) + "/token").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val txt = (if (code >= 400) c.errorStream else c.inputStream)?.use { s ->
                val b = ByteArrayOutputStream(); s.copyTo(b); b.toString("UTF-8")
            } ?: ""
            val j = try { JSONObject(txt) } catch (e: Exception) { JSONObject() }
            if (code == 200) return j
            val err = j.optString("error")
            val desc = j.optString("error_description").lineSequence().firstOrNull()?.ifEmpty { null } ?: err
            if (err in setOf("invalid_grant", "interaction_required", "consent_required", "login_required"))
                throw AuthRequiredException(desc)
            throw IOException("Errore di accesso Microsoft ($code): $desc")
        } finally {
            c.disconnect()
        }
    }

    /** Access token valido; lo rinnova con il refresh token quando serve. */
    @Synchronized
    fun accessToken(ctx: Context, force: Boolean = false): String {
        val cfg = Config(ctx)
        if (!force) {
            val t = cfg.accessToken
            if (t != null && cfg.accessExp > System.currentTimeMillis() + 120_000) return t
        }
        val rt = cfg.refreshToken ?: throw AuthRequiredException("Accesso Microsoft non effettuato")
        try {
            store(cfg, tokenRequest(cfg, mapOf("grant_type" to "refresh_token", "refresh_token" to rt, "scope" to SCOPES)))
        } catch (e: AuthRequiredException) {
            cfg.clearTokens()
            cfg.authExpired = true
            throw e
        }
        return cfg.accessToken ?: throw AuthRequiredException("Accesso Microsoft non valido")
    }

    fun signOut(ctx: Context) {
        val cfg = Config(ctx)
        cfg.clearTokens()
        cfg.account = null
        cfg.authExpired = false
    }
}
