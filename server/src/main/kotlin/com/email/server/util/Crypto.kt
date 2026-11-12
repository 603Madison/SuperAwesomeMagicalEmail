package com.email.server.util

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Random {
    private val random = SecureRandom()

    fun bytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    /** A URL-safe random token with [bytes] of randomness. */
    fun token(bytes: Int = 32): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(bytes))

    /** A random id for database rows. */
    fun id(): String = token(16)

    /** A password people can type: no look-alike characters. */
    fun password(length: Int = 20): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return (1..length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}

fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

/** Constant-time comparison, for secrets. */
fun secureEquals(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

/**
 * Password hashing: PBKDF2-HMAC-SHA256 with a random salt, stored as
 * `pbkdf2-sha256$<iterations>$<salt>$<hash>`. The iteration count is OWASP's recommendation.
 */
class Passwords(private val iterations: Int = 600_000) {
    fun hash(password: String): String {
        val salt = Random.bytes(16)
        return "pbkdf2-sha256\$$iterations\$${b64(salt)}\$${b64(derive(password, salt, iterations))}"
    }

    fun verify(password: String, stored: String?): Boolean {
        val parts = stored?.split('$')
        if (parts == null || parts.size != 4 || parts[0] != "pbkdf2-sha256") {
            // Same work for unknown users, so timing doesn't tell which usernames exist.
            derive(password, DUMMY_SALT, iterations)
            return false
        }
        val rounds = parts[1].toIntOrNull() ?: return false
        val expected = Base64.getDecoder().decode(parts[3])
        return MessageDigest.isEqual(derive(password, Base64.getDecoder().decode(parts[2]), rounds), expected)
    }

    private fun derive(password: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, rounds, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        val DUMMY_SALT = ByteArray(16)
    }
}

/**
 * Encrypts the credentials people save (Jellyfin tokens, Qobuz and SiriusXM passwords, API keys)
 * with AES-256-GCM, so a copy of the database alone gives none of them away. The key lives in its
 * own file next to the database; each value is bound to the row it belongs to, so values can't
 * be swapped between rows.
 */
class SecretBox(private val key: ByteArray) {
    init {
        require(key.size == 32) { "The secret key must be 32 bytes" }
    }

    fun seal(plain: String, context: String): String {
        val iv = Random.bytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(context.toByteArray())
        val sealed = cipher.doFinal(plain.toByteArray())
        return "v1:" + Base64.getEncoder().encodeToString(iv + sealed)
    }

    fun open(sealed: String, context: String): String {
        require(sealed.startsWith("v1:")) { "Unknown secret format" }
        val bytes = Base64.getDecoder().decode(sealed.removePrefix("v1:"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, 0, 12))
        cipher.updateAAD(context.toByteArray())
        return String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }

    /** A separate key for signing, derived from the main one. */
    private val signingKey: ByteArray = hmac(key, "same url signing".toByteArray())

    /** A keyed hash, for signing URLs the server hands out. */
    fun sign(text: String): String = hmac(signingKey, text.toByteArray()).copyOf(16).base64Url()

    fun verifySignature(text: String, signature: String): Boolean = secureEquals(sign(text), signature)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    companion object {
        /** Loads the key from [file], creating it (readable only by the server) the first time. */
        fun fromFile(file: File): SecretBox {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeBytes(Random.bytes(32))
                runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
            }
            return SecretBox(file.readBytes())
        }
    }
}
