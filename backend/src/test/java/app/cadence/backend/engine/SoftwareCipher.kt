package app.cadence.backend.engine

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The keystore cipher's AES-256-GCM scheme with a software key, for JVM tests (no Android Keystore there). */
internal class SoftwareCipher(private val key: SecretKey) : SecretCipher {
    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
        val n = sealed[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 1, n))
        c.updateAAD(aad)
        return c.doFinal(sealed, 1 + n, sealed.size - 1 - n)
    }

    companion object {
        fun fresh() = SoftwareCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
    }
}
