package com.hiktv.viewer.util

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional PIN encryption for the settings backup (which contains passwords). Format is a small
 * JSON envelope so restore can detect an encrypted file and prompt for the PIN:
 *
 *   { "hiktv_enc": 1, "salt": b64, "iv": b64, "iter": n, "data": b64(AES-GCM ciphertext) }
 *
 * Key = PBKDF2(HMAC-SHA256, pin, salt, iter). A wrong PIN fails the GCM tag and returns null.
 * The iteration count is stored in the envelope so it can be raised over time without breaking
 * older backups (files written before "iter" existed used [LEGACY_ITERATIONS]).
 */
object BackupCrypto {

    private const val ITERATIONS = 210_000        // current cost; stored per-file as "iter"
    private const val LEGACY_ITERATIONS = 60_000  // pre-"iter" files were all encrypted at this cost
    private const val KEY_BITS = 256

    fun isEncrypted(text: String): Boolean =
        runCatching { JSONObject(text).optInt("hiktv_enc", 0) == 1 }.getOrDefault(false)

    fun encrypt(plain: String, pin: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val key = deriveKey(pin, salt, ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject().apply {
            put("hiktv_enc", 1)
            put("salt", b64(salt))
            put("iv", b64(iv))
            put("iter", ITERATIONS)
            put("data", b64(ct))
        }.toString()
    }

    /** Returns the plaintext JSON, or null on a wrong PIN / corrupt file. */
    fun decrypt(text: String, pin: String): String? = runCatching {
        val obj = JSONObject(text)
        val salt = unb64(obj.getString("salt"))
        val iv = unb64(obj.getString("iv"))
        val ct = unb64(obj.getString("data"))
        val iter = obj.optInt("iter", LEGACY_ITERATIONS)   // old files omit it → 60k
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, deriveKey(pin, salt, iter), GCMParameterSpec(128, iv))
        }
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    private fun deriveKey(pin: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
