package com.orion.proxy

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

private val Context.credentialsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "direct_proxy_credentials"
)

enum class WifiBandPreference(
    val storedValue: String,
    val shortLabel: String,
    val subtitle: String
) {
    BAND_2GHZ("2.4ghz", "2.4 GHz", "Wider range"),
    BAND_5GHZ("5ghz", "5 GHz", "Max speed");

    companion object {
        fun fromStored(value: String?): WifiBandPreference {
            return entries.firstOrNull { it.storedValue == value } ?: BAND_5GHZ
        }
    }
}

data class CustomHotspotConfig(
    val enabled: Boolean = false,
    val ssidTag: String = DEFAULT_SSID_TAG,
    val ssidName: String = DEFAULT_SSID_NAME,
    val passphrase: String = "",
    val preferredBand: WifiBandPreference = WifiBandPreference.BAND_5GHZ
) {
    val fullSsid: String
        get() = WifiDirectCredentialValidator.buildFullSsid(ssidTag, ssidName)

    val isValid: Boolean
        get() = WifiDirectCredentialValidator.validateAll(ssidTag, ssidName, passphrase).isValid

    companion object {
        const val DEFAULT_SSID_TAG = "OR"
        const val DEFAULT_SSID_NAME = "Orion"
    }
}

data class CredentialValidationResult(
    val tagError: String? = null,
    val nameError: String? = null,
    val passphraseError: String? = null
) {
    val isValid: Boolean
        get() = tagError == null && nameError == null && passphraseError == null
}

/**
 * Enforces Android WifiP2pConfig.Builder & Wi-Fi Alliance P2P specification constraints:
 * - SSID must start with "DIRECT-" + 2 ASCII alphanumeric chars ([A-Za-z0-9]{2})
 * - Total SSID UTF-8 length must be 9..32 bytes
 * - WPA2-PSK passphrase must be 8..63 printable ASCII characters
 */
object WifiDirectCredentialValidator {
    const val MANDATORY_PREFIX = "DIRECT-"
    const val MAX_SSID_BYTES = 32
    const val MIN_PASSPHRASE_LENGTH = 8
    const val MAX_PASSPHRASE_LENGTH = 63

    private val TAG_REGEX = Regex("^[A-Za-z0-9]{2}$")
    private const val PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
    private val secureRandom = SecureRandom()

    fun buildFullSsid(tag: String, name: String): String {
        val cleanTag = tag.trim()
        val cleanName = name.trim()
        return if (cleanName.isEmpty()) {
            "$MANDATORY_PREFIX$cleanTag"
        } else {
            "$MANDATORY_PREFIX$cleanTag-$cleanName"
        }
    }

    fun ssidByteLength(tag: String, name: String): Int {
        return buildFullSsid(tag, name).toByteArray(Charsets.UTF_8).size
    }

    fun validateAll(tag: String, name: String, passphrase: String): CredentialValidationResult {
        val trimmedTag = tag.trim()
        val trimmedName = name.trim()

        val tagError = when {
            trimmedTag.length != 2 -> "Must be exactly 2 letters or digits"
            !TAG_REGEX.matches(trimmedTag) -> "Use A-Z, a-z, or 0-9 only"
            else -> null
        }

        val fullBytes = buildFullSsid(trimmedTag, trimmedName).toByteArray(Charsets.UTF_8).size
        val nameError = when {
            trimmedName.any { it == '\r' || it == '\n' } -> "Line breaks are not allowed"
            fullBytes > MAX_SSID_BYTES -> "Full SSID exceeds $MAX_SSID_BYTES bytes ($fullBytes/$MAX_SSID_BYTES)"
            else -> null
        }

        val passphraseError = when {
            passphrase.length < MIN_PASSPHRASE_LENGTH ->
                "Must be at least $MIN_PASSPHRASE_LENGTH characters"
            passphrase.length > MAX_PASSPHRASE_LENGTH ->
                "Must be at most $MAX_PASSPHRASE_LENGTH characters"
            passphrase.any { it.code !in 32..126 } ->
                "Use standard ASCII printable characters only"
            else -> null
        }

        return CredentialValidationResult(
            tagError = tagError,
            nameError = nameError,
            passphraseError = passphraseError
        )
    }

    fun generatePassphrase(length: Int = 12): String {
        val chars = CharArray(length) {
            PASSWORD_ALPHABET[secureRandom.nextInt(PASSWORD_ALPHABET.length)]
        }
        return String(chars)
    }
}

object CredentialsPreferences {
    private val KEY_ENABLED = booleanPreferencesKey("custom_credentials_enabled")
    private val KEY_SSID_TAG = stringPreferencesKey("custom_ssid_tag")
    private val KEY_SSID_NAME = stringPreferencesKey("custom_ssid_name")
    private val KEY_PASSPHRASE = stringPreferencesKey("custom_passphrase")
    private val KEY_PREFERRED_BAND = stringPreferencesKey("preferred_wifi_band")
    private val KEY_AUTO_PASSPHRASE = stringPreferencesKey("auto_band_passphrase")

    fun observeConfig(context: Context): Flow<CustomHotspotConfig> {
        return context.applicationContext.credentialsDataStore.data.map { prefs ->
            CustomHotspotConfig(
                enabled = prefs[KEY_ENABLED] ?: false,
                ssidTag = prefs[KEY_SSID_TAG] ?: CustomHotspotConfig.DEFAULT_SSID_TAG,
                ssidName = prefs[KEY_SSID_NAME] ?: CustomHotspotConfig.DEFAULT_SSID_NAME,
                passphrase = prefs[KEY_PASSPHRASE] ?: "",
                preferredBand = WifiBandPreference.fromStored(prefs[KEY_PREFERRED_BAND])
            )
        }
    }

    suspend fun getConfig(context: Context): CustomHotspotConfig {
        return observeConfig(context).first()
    }

    /**
     * Android's WifiP2pConfig.Builder requires both networkName and passphrase whenever
     * specifying setGroupOperatingBand (2.4 GHz or 5 GHz). If the user has not saved custom
     * credentials yet, we persist a stable generated passphrase so band selection always works.
     */
    suspend fun getOrGenerateBandFallbackPassphrase(context: Context): String {
        val store = context.applicationContext.credentialsDataStore
        val existing = store.data.first()[KEY_AUTO_PASSPHRASE]
        if (!existing.isNullOrBlank() && existing.length >= WifiDirectCredentialValidator.MIN_PASSPHRASE_LENGTH) {
            return existing
        }
        val generated = WifiDirectCredentialValidator.generatePassphrase(12)
        store.edit { prefs ->
            prefs[KEY_AUTO_PASSPHRASE] = generated
        }
        return generated
    }

    suspend fun saveCustomConfig(
        context: Context,
        ssidTag: String,
        ssidName: String,
        passphrase: String
    ) {
        context.applicationContext.credentialsDataStore.edit { prefs ->
            prefs[KEY_ENABLED] = true
            prefs[KEY_SSID_TAG] = ssidTag.trim()
            prefs[KEY_SSID_NAME] = ssidName.trim()
            prefs[KEY_PASSPHRASE] = passphrase
        }
    }

    suspend fun savePreferredBand(
        context: Context,
        band: WifiBandPreference
    ) {
        context.applicationContext.credentialsDataStore.edit { prefs ->
            prefs[KEY_PREFERRED_BAND] = band.storedValue
        }
    }

    suspend fun clearCustomConfig(context: Context) {
        context.applicationContext.credentialsDataStore.edit { prefs ->
            prefs[KEY_ENABLED] = false
        }
    }
}
