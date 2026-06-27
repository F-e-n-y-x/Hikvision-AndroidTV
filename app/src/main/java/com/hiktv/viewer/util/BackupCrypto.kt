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
 *   { "hiktv_enc": 1, "salt": b64, "iv": b64, "data": b64(AES-GCM ciphertext) }
 *
 * Key = PBKDF2(HMAC-SHA256, pin, salt). A wrong PIN fails the GCM tag and returns null.
 */
object BackupCrypto {

    private const val ITERATIONS = 60_000
    private const val KEY_BITS = 256

    fun isEncrypted(text: String): Boolean =
        runCatching { JSONObject(text).optInt("hiktv_enc", 0) == 1 }.getOrDefault(false)

    fun encrypt(plain: String, pin: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val key = deriveKey(pin, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject().apply {
            put("hiktv_enc", 1)
            put("salt", b64(salt))
            put("iv", b64(iv))
            put("data", b64(ct))
        }.toString()
    }

    /** Returns the plaintext JSON, or null on a wrong PIN / corrupt file. */
    fun decrypt(text: String, pin: String): String? = runCatching {
        val obj = JSONObject(text)
        val salt = unb64(obj.getString("salt"))
        val iv = unb64(obj.getString("iv"))
        val ct = unb64(obj.getString("data"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, deriveKey(pin, salt), GCMParameterSpec(128, iv))
        }
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    private fun deriveKey(pin: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
