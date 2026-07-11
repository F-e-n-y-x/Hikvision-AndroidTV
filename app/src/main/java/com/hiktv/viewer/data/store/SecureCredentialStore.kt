package com.hiktv.viewer.data.store

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.first

// One process-wide DataStore file for the credential store.
private val Context.credsDataStore by preferencesDataStore(name = "nvr_creds_ds")

/**
 * SCAFFOLD — NOT YET LIVE.
 *
 * Future replacement for [NvrStore]'s EncryptedSharedPreferences backend, built on Jetpack
 * DataStore + Google Tink — the official path after `androidx.security-crypto` was deprecated in
 * 2025. Nothing in the app calls this at runtime; [NvrStore] stays the live store while
 * [NvrStore.USE_DATASTORE] is false.
 *
 * Before flipping that flag:
 *   1. Verify [migrateFromEncryptedPrefs] on a device that carries real EncryptedSharedPreferences
 *      data — a wrong migration silently drops saved NVR/EZVIZ credentials on app update.
 *   2. Convert [NvrStore]'s ~9 synchronous call sites to coroutines (DataStore is suspend/Flow),
 *      or preload once into memory in a lifecycle scope. See MODERNIZATION_REPORT.md, Phase C7.
 *
 * Values are Tink-AEAD encrypted (AES-256-GCM) under a Keystore-wrapped keyset, then Base64-stored
 * in a plain Preferences DataStore, so encryption does not depend on the deprecated library.
 */
class SecureCredentialStore(private val context: Context) {

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    private fun encrypt(plain: String): String =
        Base64.encodeToString(aead.encrypt(plain.toByteArray(), AAD), Base64.NO_WRAP)

    private fun decrypt(stored: String): String =
        String(aead.decrypt(Base64.decode(stored, Base64.NO_WRAP), AAD))

    suspend fun putString(key: String, value: String) {
        val enc = encrypt(value)
        context.credsDataStore.edit { it[stringPreferencesKey(key)] = enc }
    }

    suspend fun getString(key: String): String? {
        val prefs = context.credsDataStore.data.first()
        return prefs[stringPreferencesKey(key)]?.let { runCatching { decrypt(it) }.getOrNull() }
    }

    /**
     * One-time import of every entry from the legacy EncryptedSharedPreferences file into the
     * encrypted DataStore. Safe to re-run (overwrites keys). Values are coerced to strings —
     * [NvrStore] already round-trips ints/booleans through JSON in its export/import path, so a
     * string-typed backend is compatible.
     *
     * UNVERIFIED — do not enable in production until tested on real data.
     */
    suspend fun migrateFromEncryptedPrefs() {
        val legacy = runCatching {
            val mk = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                LEGACY_PREFS,
                mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrNull() ?: return
        for ((k, v) in legacy.all) {
            if (v != null) putString(k, v.toString())
        }
    }

    companion object {
        private const val LEGACY_PREFS = "nvr_secure_prefs"
        private const val KEYSET_NAME = "nvr_ds_keyset"
        private const val KEYSET_PREF_FILE = "nvr_ds_keyset_prefs"
        private const val MASTER_KEY_URI = "android-keystore://nvr_ds_master_key"
        private val AAD = "hiktv-nvr".toByteArray()
    }
}
