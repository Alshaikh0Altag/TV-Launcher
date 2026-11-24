package com.arabrock.tvlauncher.security

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Administrator PIN.
 * - Only a salted PBKDF2-HMAC-SHA256 hash is kept; the PIN itself is never stored or logged.
 * - The hash record is additionally encrypted with an AES-GCM key held in the Android Keystore.
 * - Failed attempts add a growing delay (persisted, so restarting the launcher does not reset it).
 * - A one-time recovery code (shown once at setup) can reset the PIN.
 */
class PinManager private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("security", Context.MODE_PRIVATE)
    private var unlockedUntilElapsed = 0L

    val isPinSet: Boolean get() = prefs.contains(K_PIN)

    // ---- Session ----
    fun isUnlocked(): Boolean = SystemClock.elapsedRealtime() < unlockedUntilElapsed

    fun unlockFor(seconds: Int) {
        unlockedUntilElapsed = SystemClock.elapsedRealtime() + seconds * 1000L
    }

    fun touch(seconds: Int) { if (isUnlocked()) unlockFor(seconds) }

    fun lock() { unlockedUntilElapsed = 0L }

    // ---- Setup ----
    /** Stores a new PIN and returns a fresh recovery code (show it once to the administrator). */
    fun setPin(pin: String): String {
        require(pin.length in 4..8 && pin.all { it.isDigit() })
        prefs.edit().putString(K_PIN, seal(hashRecord(pin))).apply()
        val recovery = newRecoveryCode()
        prefs.edit().putString(K_RECOVERY, seal(hashRecord(recovery)))
            .putInt(K_FAILS, 0).putLong(K_LOCKED_UNTIL, 0L).apply()
        return recovery
    }

    /** Changes the PIN but keeps the existing recovery code. */
    fun changePin(pin: String) {
        require(pin.length in 4..8 && pin.all { it.isDigit() })
        prefs.edit().putString(K_PIN, seal(hashRecord(pin))).apply()
    }

    // ---- Verification ----
    /** Remaining lockout in seconds, 0 when a new attempt is allowed. */
    fun lockoutRemainingSec(): Long {
        val until = prefs.getLong(K_LOCKED_UNTIL, 0L)
        val now = System.currentTimeMillis()
        return if (until > now) (until - now + 999) / 1000 else 0
    }

    sealed class Result {
        object Ok : Result()
        data class Wrong(val lockoutSec: Long) : Result()
        data class LockedOut(val seconds: Long) : Result()
    }

    fun verify(pin: String, failDelayBaseSec: Int): Result {
        val wait = lockoutRemainingSec()
        if (wait > 0) return Result.LockedOut(wait)
        val record = prefs.getString(K_PIN, null)?.let { unseal(it) } ?: return Result.Wrong(0)
        return if (checkRecord(record, pin)) {
            prefs.edit().putInt(K_FAILS, 0).putLong(K_LOCKED_UNTIL, 0L).apply()
            Result.Ok
        } else registerFailure(failDelayBaseSec)
    }

    fun verifyRecovery(code: String, failDelayBaseSec: Int): Result {
        val wait = lockoutRemainingSec()
        if (wait > 0) return Result.LockedOut(wait)
        val record = prefs.getString(K_RECOVERY, null)?.let { unseal(it) } ?: return Result.Wrong(0)
        val normalized = code.uppercase().filter { it.isLetterOrDigit() }
        return if (checkRecord(record, normalized)) {
            prefs.edit().putInt(K_FAILS, 0).putLong(K_LOCKED_UNTIL, 0L).apply()
            Result.Ok
        } else registerFailure(failDelayBaseSec)
    }

    private fun registerFailure(base: Int): Result {
        val fails = prefs.getInt(K_FAILS, 0) + 1
        // First 3 attempts are free, then base, 2x base, 4x base ... capped at 30 minutes.
        val delay = if (fails < 3) 0L else (base.toLong() shl (fails - 3).coerceAtMost(6)).coerceAtMost(1800L)
        prefs.edit().putInt(K_FAILS, fails)
            .putLong(K_LOCKED_UNTIL, if (delay > 0) System.currentTimeMillis() + delay * 1000 else 0L).apply()
        return Result.Wrong(delay)
    }

    // ---- Hashing ----
    private fun hashRecord(secret: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(secret, salt, ITERATIONS)
        return "$ITERATIONS:${b64(salt)}:${b64(hash)}"
    }

    private fun checkRecord(record: String, secret: String): Boolean {
        val parts = record.split(":")
        if (parts.size != 3) return false
        val iterations = parts[0].toIntOrNull() ?: return false
        val salt = unb64(parts[1])
        val expected = unb64(parts[2])
        return MessageDigest.isEqual(expected, pbkdf2(secret, salt, iterations))
    }

    private fun pbkdf2(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun newRecoveryCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        return (1..12).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    // ---- Keystore wrapping ----
    private fun key(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    } catch (e: Exception) {
        null
    }

    /** "k:" prefix = Keystore encrypted, "p:" = plain hash record (device without a usable Keystore). */
    private fun seal(record: String): String {
        val k = key() ?: return "p:$record"
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k)
            "k:${b64(c.iv)}:${b64(c.doFinal(record.toByteArray()))}"
        } catch (e: Exception) {
            "p:$record"
        }
    }

    private fun unseal(stored: String): String? = when {
        stored.startsWith("p:") -> stored.substring(2)
        stored.startsWith("k:") -> try {
            val parts = stored.substring(2).split(":")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(parts[0])))
            String(c.doFinal(unb64(parts[1])))
        } catch (e: Exception) {
            null
        }
        else -> null
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        private const val K_PIN = "pin_record"
        private const val K_RECOVERY = "recovery_record"
        private const val K_FAILS = "fail_count"
        private const val K_LOCKED_UNTIL = "locked_until"
        private const val KEY_ALIAS = "tvlauncher_pin_wrap"
        private const val ITERATIONS = 60_000

        @Volatile private var instance: PinManager? = null
        fun get(context: Context): PinManager =
            instance ?: synchronized(this) {
                instance ?: PinManager(context.applicationContext).also { instance = it }
            }
    }
}
