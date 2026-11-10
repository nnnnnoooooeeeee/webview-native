package com.webview.builder.sign

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.EncryptedPrivateKeyInfo

//========== JKS READER ==========

/**
 * Reads the first private key from a Java KeyStore (.jks) file. Android has no
 * JKS support, but older keytool versions create this format, so existing
 * signing keys are often JKS files.
 *
 * Format: magic 0xFEEDFEED, version, entry count, then entries. A private key
 * entry holds the alias, a timestamp, the key encrypted with Sun's "JKS key
 * protector" and its certificate chain.
 */
object JksReader {

    private const val MAGIC = 0xFEEDFEED.toInt()

    fun isJks(bytes: ByteArray) = bytes.size >= 4 &&
        ((bytes[0].toInt() and 0xFF) shl 24 or ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)) == MAGIC

    /** Returns the first key entry; throws [WrongPasswordException] for a wrong [keyPassword]. */
    fun readFirstKey(bytes: ByteArray, keyPassword: CharArray): KeyEntry {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "Not a JKS file" }
        val version = input.readInt()
        val count = input.readInt()
        val certificates = CertificateFactory.getInstance("X.509")
        repeat(count) {
            val tag = input.readInt()
            val alias = input.readUTF()
            input.readLong() // timestamp
            when (tag) {
                1 -> { // private key
                    val encrypted = ByteArray(input.readInt()).also { input.readFully(it) }
                    val chain = List(input.readInt()) {
                        if (version == 2) input.readUTF() // certificate type, "X.509"
                        val der = ByteArray(input.readInt()).also { input.readFully(it) }
                        certificates.generateCertificate(der.inputStream()) as X509Certificate
                    }
                    return KeyEntry(alias, decrypt(encrypted, keyPassword), chain)
                }
                2 -> { // trusted certificate, skipped
                    if (version == 2) input.readUTF()
                    input.skipBytes(input.readInt())
                }
                else -> error("Unknown JKS entry type $tag")
            }
        }
        error("The keystore has no private key")
    }

    /**
     * The JKS key protector: data = salt (20 bytes) | encrypted key | check (20 bytes).
     * The key stream is SHA-1 blocks: d1 = SHA1(password | salt), d2 = SHA1(password | d1)...
     * XOR gives the PKCS#8 key; SHA1(password | key) must equal the check bytes.
     */
    private fun decrypt(encryptedKeyInfo: ByteArray, password: CharArray): PrivateKey {
        val data = EncryptedPrivateKeyInfo(encryptedKeyInfo).encryptedData
        val salt = data.copyOfRange(0, 20)
        val encrypted = data.copyOfRange(20, data.size - 20)
        val check = data.copyOfRange(data.size - 20, data.size)
        // Password as UTF-16BE bytes, two per char.
        val pass = ByteArray(password.size * 2).also { b ->
            password.forEachIndexed { i, c -> b[i * 2] = (c.code shr 8).toByte(); b[i * 2 + 1] = c.code.toByte() }
        }
        val sha1 = MessageDigest.getInstance("SHA-1")
        val plain = ByteArray(encrypted.size)
        var digest = salt
        var pos = 0
        while (pos < encrypted.size) {
            sha1.update(pass)
            sha1.update(digest)
            digest = sha1.digest()
            for (i in digest.indices) {
                if (pos >= encrypted.size) break
                plain[pos] = (encrypted[pos].toInt() xor digest[i].toInt()).toByte()
                pos++
            }
        }
        sha1.update(pass)
        sha1.update(plain)
        if (!MessageDigest.isEqual(sha1.digest(), check)) throw WrongPasswordException()

        val spec = PKCS8EncodedKeySpec(plain)
        return runCatching { KeyFactory.getInstance("RSA").generatePrivate(spec) }
            .getOrElse { KeyFactory.getInstance("EC").generatePrivate(spec) }
    }
}

class KeyEntry(val alias: String, val privateKey: PrivateKey, val chain: List<X509Certificate>)

class WrongPasswordException : Exception("Wrong password")
