package com.webview.builder.sign

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

//========== SIGNING KEYS ==========

/** The key the builder signs with, and what it is. */
class SigningKey(
    val alias: String,
    val privateKey: PrivateKey,
    val chain: List<X509Certificate>,
    /** True for the automatic key made on first build (no key created or imported). */
    val automatic: Boolean,
) {
    val certificate get() = chain.first()

    /** SHA-256 fingerprint of the certificate, as keytool and Play Console show it. */
    val fingerprint: String
        get() = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it) }
}

/**
 * Stores one signing key as a PKCS#12 file in the app's private storage. Its
 * password is kept encrypted with a key from the Android Keystore, so the file
 * alone (for example from a backup) is useless without the password.
 *
 * Every APK is signed with this key. Android only installs an update when it is
 * signed with the same key as the installed app, so it must never change.
 */
class SigningKeys(private val context: Context) {

    private val dir = File(context.filesDir, "keys")
    private val file = File(dir, "signing.p12")
    private val prefs = context.getSharedPreferences("signing", Context.MODE_PRIVATE)

    fun hasKey() = file.isFile && prefs.contains(KEY_PASSWORD)

    /** The stored key, or null when there is none yet. */
    fun load(): SigningKey? {
        if (!hasKey()) return null
        val password = decryptPassword(prefs.getString(KEY_PASSWORD, null)!!).toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, password) } }
        val alias = store.aliases().toList().first { store.isKeyEntry(it) }
        val key = store.getKey(alias, password) as PrivateKey
        val chain = store.getCertificateChain(alias).map { it as X509Certificate }
        return SigningKey(alias, key, chain, automatic = prefs.getBoolean(KEY_AUTOMATIC, false))
    }

    /** The stored key, or a new automatic one when there is none. */
    fun loadOrCreateAutomatic(): SigningKey = load() ?: run {
        create("auto", randomPassword(), "APK Builder", "", "", years = 30, automatic = true)
        load()!!
    }

    /** Creates a new RSA 2048 key with a self-signed certificate valid for [years]. */
    fun create(
        alias: String,
        password: String,
        commonName: String,
        organization: String,
        country: String,
        years: Int,
        automatic: Boolean = false,
    ) {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate = SelfSignedCertificate.create(keys, commonName, organization, country, years)
        save(alias, keys.private, listOf(certificate), password, automatic)
    }

    /**
     * Imports the first key of a .jks or .p12/.pfx file. [keyPassword] defaults to
     * the store password (keytool's default when none was given separately).
     */
    fun import(bytes: ByteArray, storePassword: String, keyPassword: String?) {
        val keyPass = (keyPassword?.takeIf { it.isNotEmpty() } ?: storePassword).toCharArray()
        val entry = if (JksReader.isJks(bytes)) {
            JksReader.readFirstKey(bytes, keyPass)
        } else {
            val store = try {
                KeyStore.getInstance("PKCS12").apply { load(bytes.inputStream(), storePassword.toCharArray()) }
            } catch (e: Exception) {
                throw WrongPasswordException()
            }
            val alias = store.aliases().toList().firstOrNull { store.isKeyEntry(it) }
                ?: error("The keystore has no private key")
            val key = runCatching { store.getKey(alias, keyPass) as PrivateKey }.getOrElse { throw WrongPasswordException() }
            KeyEntry(alias, key, store.getCertificateChain(alias).map { it as X509Certificate })
        }
        save(entry.alias, entry.privateKey, entry.chain, storePassword, automatic = false)
    }

    /** The stored PKCS#12 file and its password, for a backup. */
    fun exportBytes(): ByteArray = file.readBytes()

    fun exportPassword(): String = decryptPassword(prefs.getString(KEY_PASSWORD, null)!!)

    fun delete() {
        file.delete()
        prefs.edit { clear() }
    }

    private fun save(alias: String, key: PrivateKey, chain: List<X509Certificate>, password: String, automatic: Boolean) {
        dir.mkdirs()
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        store.setKeyEntry(alias, key, password.toCharArray(), chain.toTypedArray())
        file.outputStream().use { store.store(it, password.toCharArray()) }
        prefs.edit {
            putString(KEY_PASSWORD, encryptPassword(password))
            putBoolean(KEY_AUTOMATIC, automatic)
        }
    }

    //========== PASSWORD PROTECTION ==========
    // AES-GCM with a key that never leaves the Android Keystore. Stored as
    // base64(iv | ciphertext).

    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun encryptPassword(password: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val data = cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decryptPassword(stored: String): String {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, data, 0, GCM_IV_SIZE))
        }
        return String(cipher.doFinal(data, GCM_IV_SIZE, data.size - GCM_IV_SIZE), Charsets.UTF_8)
    }

    companion object {
        private const val KEY_PASSWORD = "password"
        private const val KEY_AUTOMATIC = "automatic"
        private const val KEYSTORE_ALIAS = "signing-password"
        private const val GCM_IV_SIZE = 12

        /** 20 characters from a 56-symbol set: 20 x log2(56) = about 116 bits of randomness. */
        fun randomPassword(): String {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
            val random = SecureRandom()
            return String(CharArray(20) { chars[random.nextInt(chars.length)] })
        }
    }
}
