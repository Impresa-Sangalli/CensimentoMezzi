package it.impresasangalli.censimentomezzi

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Configurazione dell'invio a SharePoint e stato della sessione Microsoft.
 * I token di accesso sono cifrati con una chiave del Keystore Android.
 */
class Config(ctx: Context) {
    companion object {
        // Valori predefiniti, così sui telefoni basta accedere con l'account Microsoft.
        // Non sono segreti: senza un account aziendale autorizzato non danno accesso a nulla.
        // Registrazione app Entra ID "Censimento Mezzi" (tenant singolo, client pubblico con PKCE).
        const val DEFAULT_TENANT = "59ff1ff3-833a-47c5-b37b-7f83e0e47aae"      // ID directory (tenant)
        const val DEFAULT_CLIENT_ID = "234311e4-c481-490a-a3ee-0e9353e4456a"   // ID applicazione (client)
        const val DEFAULT_SITE_URL = "https://impresasangalli.sharepoint.com/sites/CensimentoMezzi"
        const val DEFAULT_FOLDER = "Censimento Mezzi"
        const val DEFAULT_EXCEL = "Censimento_Mezzi.xlsx"
        const val TABLE = "TabellaCensimento"
        const val SHEET = "Censimento"
    }

    private val p = ctx.applicationContext.getSharedPreferences("config", Context.MODE_PRIVATE)

    private fun s(k: String, d: String) = p.getString(k, d) ?: d
    private fun put(k: String, v: String?) = p.edit().putString(k, v).commit()

    var tenant: String get() = s("tenant", DEFAULT_TENANT); set(v) { put("tenant", v.trim()) }
    var clientId: String get() = s("clientId", DEFAULT_CLIENT_ID); set(v) { put("clientId", v.trim()) }
    var siteUrl: String get() = s("siteUrl", DEFAULT_SITE_URL); set(v) { put("siteUrl", v.trim()) }
    var folder: String get() = s("folder", DEFAULT_FOLDER); set(v) { put("folder", v.trim().trim('/')) }
    var excelName: String get() = s("excelName", DEFAULT_EXCEL); set(v) { put("excelName", v.trim()) }

    fun ready(): Boolean = tenant.isNotBlank() && clientId.isNotBlank() && siteUrl.isNotBlank() && excelName.isNotBlank()

    // Sessione Microsoft (cifrata)
    var refreshToken: String? get() = Secure.dec(p.getString("rt", null)); set(v) { put("rt", v?.let { Secure.enc(it) }) }
    var accessToken: String? get() = Secure.dec(p.getString("at", null)); set(v) { put("at", v?.let { Secure.enc(it) }) }
    var accessExp: Long get() = p.getLong("atExp", 0); set(v) { p.edit().putLong("atExp", v).commit() }
    var account: String? get() = p.getString("account", null); set(v) { put("account", v) }
    var pkceVerifier: String? get() = p.getString("pkce", null); set(v) { put("pkce", v) }
    var authState: String? get() = p.getString("state", null); set(v) { put("state", v) }
    var authExpired: Boolean get() = p.getBoolean("authExpired", false); set(v) { p.edit().putBoolean("authExpired", v).commit() }

    // Cache degli identificativi SharePoint
    var driveId: String? get() = p.getString("driveId", null); set(v) { put("driveId", v) }
    var excelItemId: String? get() = p.getString("excelItemId", null); set(v) { put("excelItemId", v) }

    // Stato dell'ultimo invio
    var lastOk: Long get() = p.getLong("lastOk", 0); set(v) { p.edit().putLong("lastOk", v).commit() }
    var lastError: String? get() = p.getString("lastError", null); set(v) { put("lastError", v) }
    var backlogAsked: Boolean get() = p.getBoolean("backlogAsked", false); set(v) { p.edit().putBoolean("backlogAsked", v).commit() }

    fun clearRemoteCache() { driveId = null; excelItemId = null }

    fun clearTokens() { refreshToken = null; accessToken = null; accessExp = 0 }
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
