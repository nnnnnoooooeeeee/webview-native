package com.webview.builder.sign

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

//========== SELF-SIGNED CERTIFICATE ==========

/**
 * Builds the self-signed X.509 certificate an APK signing key needs, the same
 * kind keytool makes. Android has no public API for this, so the DER encoding
 * is written by hand (it is a small, fixed structure):
 *
 *   Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signature BIT STRING }
 *   tbsCertificate ::= SEQUENCE { [0] version, serial, signatureAlgorithm,
 *                                 issuer, validity, subject, subjectPublicKeyInfo }
 */
object SelfSignedCertificate {

    private const val OID_SHA256_RSA = "1.2.840.113549.1.1.11"
    private const val OID_COMMON_NAME = "2.5.4.3"
    private const val OID_ORGANIZATION = "2.5.4.10"
    private const val OID_COUNTRY = "2.5.4.6"

    fun create(keys: KeyPair, commonName: String, organization: String, country: String, years: Int): X509Certificate {
        val notBefore = Date()
        val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, years) }.time
        val name = name(commonName, organization, country)
        val algorithm = sequence(oid(OID_SHA256_RSA), byteArrayOf(0x05, 0x00)) // AlgorithmIdentifier with NULL params

        val tbs = sequence(
            tagged(0, integer(BigInteger.valueOf(2))), // v3
            integer(BigInteger(63, SecureRandom())),  // positive random serial
            algorithm,
            name,
            sequence(time(notBefore), time(notAfter)),
            name,
            keys.public.encoded, // already a DER SubjectPublicKeyInfo
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(tbs)
            sign()
        }
        val der = sequence(tbs, algorithm, bitString(signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    //========== DER ENCODING ==========

    private fun name(commonName: String, organization: String, country: String): ByteArray {
        val parts = mutableListOf(rdn(OID_COMMON_NAME, utf8String(commonName)))
        if (organization.isNotBlank()) parts += rdn(OID_ORGANIZATION, utf8String(organization))
        if (country.length == 2) parts += rdn(OID_COUNTRY, printableString(country.uppercase()))
        return sequence(*parts.toTypedArray())
    }

    private fun rdn(oid: String, value: ByteArray) = tlv(0x31, sequence(oid(oid), value)) // SET { SEQUENCE }

    private fun sequence(vararg items: ByteArray) = tlv(0x30, concat(*items))
    private fun tagged(number: Int, content: ByteArray) = tlv(0xA0 or number, content)
    private fun integer(value: BigInteger) = tlv(0x02, value.toByteArray())
    private fun utf8String(value: String) = tlv(0x0C, value.toByteArray(Charsets.UTF_8))
    private fun printableString(value: String) = tlv(0x13, value.toByteArray(Charsets.US_ASCII))
    private fun bitString(bytes: ByteArray) = tlv(0x03, byteArrayOf(0) + bytes) // 0 unused bits

    /** UTCTime until 2049, GeneralizedTime from 2050 on (RFC 5280). */
    private fun time(date: Date): ByteArray {
        val year = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = date }.get(Calendar.YEAR)
        val (tag, pattern) = if (year < 2050) 0x17 to "yyMMddHHmmss'Z'" else 0x18 to "yyyyMMddHHmmss'Z'"
        val text = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)
        return tlv(tag, text.toByteArray(Charsets.US_ASCII))
    }

    /** OID: first byte = 40 * first + second, then each number in base 128 (high bit = more follows). */
    private fun oid(dotted: String): ByteArray {
        val numbers = dotted.split(".").map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((40 * numbers[0] + numbers[1]).toInt())
        for (n in numbers.drop(2)) {
            val groups = mutableListOf<Int>()
            var v = n
            do {
                groups.add(0, (v and 0x7F).toInt())
                v = v shr 7
            } while (v > 0)
            groups.forEachIndexed { i, g -> out.write(if (i < groups.size - 1) g or 0x80 else g) }
        }
        return tlv(0x06, out.toByteArray())
    }

    /** Tag, length (short form below 128, else 0x80 + number of length bytes), value. */
    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = value.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(value)
        return out.toByteArray()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}
