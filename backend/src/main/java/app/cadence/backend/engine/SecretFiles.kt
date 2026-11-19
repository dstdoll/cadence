package app.cadence.backend.engine

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals and opens secret bytes bound to [aad]. The app uses [KeystoreCipher]; tests a software key. */
interface SecretCipher {
    /** Returns IV length (1 byte) | IV | ciphertext with its tag. */
    fun seal(plain: ByteArray, aad: ByteArray): ByteArray

    fun open(sealed: ByteArray, aad: ByteArray): ByteArray
}

/**
 * Secrets at rest: the signed-in account (`sessions/active.json`: tokens, email, name), the API session
 * (`engine/amp.json`) and the account's music token (`MUSIC_TOKEN`) are stored AES-256-GCM encrypted,
 * each bound to its file name, so a file copied over another does not open.
 *
 * File format: `CDS1` | IV length | IV | ciphertext and 16-byte tag. A file still in plain text (from
 * before this, from the native sign-in, or from a session import) is read as is and sealed in place.
 * One that cannot be opened (the key is gone, the file was altered) reads as absent: the app asks to
 * sign in again instead of failing.
 *
 * The Apple libraries' own files (`adi.pb`, `token_cache.json`, ...) stay as they are: those libraries
 * read them directly.
 */
object SecretFiles {
    private val MAGIC = "CDS1".toByteArray()

    /** Swapped for a software key in JVM tests, where there is no Android Keystore. */
    @Volatile
    var cipher: SecretCipher = KeystoreCipher

    fun isSealed(bytes: ByteArray): Boolean =
        bytes.size > MAGIC.size + 1 && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    /** The plain contents of [file], or null when it is missing or cannot be opened. Plain files get sealed. */
    fun readBytes(file: File): ByteArray? {
        if (!file.isFile) return null
        val raw = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (!isSealed(raw)) {
            runCatching { writeBytes(file, raw) }.onFailure { CadenceLog.e("secrets", "${file.name} could not be sealed", it) }
            return raw
        }
        return runCatching { cipher.open(raw.copyOfRange(MAGIC.size, raw.size), aad(file)) }
            .onFailure { CadenceLog.e("secrets", "${file.name} could not be opened (${it.javaClass.simpleName}); treated as absent") }
            .getOrNull()
    }

    fun read(file: File): String? = readBytes(file)?.toString(Charsets.UTF_8)

    /** Seals [plain] into [file], replacing it atomically. */
    fun writeBytes(file: File, plain: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(MAGIC + cipher.seal(plain, aad(file)))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun write(file: File, text: String) = writeBytes(file, text.toByteArray(Charsets.UTF_8))

    /** Seals [file] if it is still plain text (after an import or a native write). */
    fun sealInPlace(file: File) {
        if (file.isFile && !isSealed(file.readBytes())) readBytes(file)
    }

    /** Seals every secret Cadence keeps under [filesDir] that is still plain (run at startup). */
    fun sealAll(filesDir: File) {
        SECRETS.map { File(filesDir, it) }.forEach { runCatching { sealInPlace(it) } }
    }

    /** The secret files, relative to the app's files directory. */
    val SECRETS = listOf("sessions/active.json", "engine/amp.json", "MUSIC_TOKEN")

    private fun aad(file: File) = ("cadence:" + file.name).toByteArray(Charsets.UTF_8)
}

/**
 * AES-256-GCM with a key generated inside the Android Keystore (StrongBox when the phone has one); the
 * key never leaves it and is not usable by other apps. It needs no user authentication, so background
 * playback and downloads can read the session.
 */
object KeystoreCipher : SecretCipher {
    private const val ALIAS = "cadence-secrets-v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    @Volatile
    private var cached: SecretKey? = null

    private fun key(): SecretKey = cached ?: synchronized(this) {
        cached ?: run {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey) ?: generate(strongBox = true) ?: generate(strongBox = false)
                ?: throw IllegalStateException("no keystore key")
        }.also { cached = it; CadenceLog.i("secrets", "keystore key ready (security=${securityLevel(it)})") }
    }

    /** Where the key lives, as the keystore reports it: StrongBox, TEE (trusted environment) or software. */
    private fun securityLevel(key: SecretKey): String = runCatching {
        val info = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                else -> "unknown(${info.securityLevel})"
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "secure hardware" else "software"
        }
    }.getOrDefault("unknown")


    private fun generate(strongBox: Boolean): SecretKey? = runCatching {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }.onSuccess { CadenceLog.i("secrets", "keystore key created (strongbox=$strongBox)") }.getOrNull()

    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(aad)
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
        val n = sealed[0].toInt()
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 1, n))
        c.updateAAD(aad)
        return c.doFinal(sealed, 1 + n, sealed.size - 1 - n)
    }
}
