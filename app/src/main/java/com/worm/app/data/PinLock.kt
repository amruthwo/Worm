package com.worm.app.data

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted PBKDF2 PIN check. Only the hash and salt are stored. */
class PinLock(private val prefs: Prefs) {
    val isSet: Boolean get() = prefs.pinHash != null

    fun set(pin: String) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        prefs.pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        prefs.pinHash = Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP)
    }

    fun check(pin: String): Boolean {
        val salt = prefs.pinSalt?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val expected = prefs.pinHash?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        return MessageDigest.isEqual(hash(pin, salt), expected)
    }

    fun clear() {
        prefs.pinHash = null
        prefs.pinSalt = null
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
