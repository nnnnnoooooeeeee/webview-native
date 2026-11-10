package com.webview.builder.build

import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

//========== APK WRITER ==========

/** Repacks the template APK with replaced entries and signs it. Plain JVM code, so it is unit tested. */
object ApkWriter {

    /** The old JAR (v1) signature files of the template, signed with the template's debug key. */
    private val V1_SIGNATURE = Regex("^META-INF/([^/]+\\.(SF|RSA|DSA|EC)|MANIFEST\\.MF)$", RegexOption.IGNORE_CASE)

    /**
     * Copies [template] to [output] (unsigned), with the entries named in
     * [replacements] replaced. Every entry keeps its compression method, so
     * resources.arsc stays stored (uncompressed) as Android 11+ requires; the
     * signer aligns it. Fails when a replacement names an entry the template
     * does not have.
     */
    fun repack(template: File, output: File, replacements: Map<String, ByteArray>) {
        val used = mutableSetOf<String>()
        ZipFile(template).use { zip ->
            ZipOutputStream(output.outputStream().buffered()).use { out ->
                out.setLevel(9)
                for (entry in zip.entries()) {
                    if (entry.isDirectory || V1_SIGNATURE.matches(entry.name)) continue
                    val data = replacements[entry.name]?.also { used += entry.name }
                        ?: zip.getInputStream(entry).use { it.readBytes() }
                    val copy = ZipEntry(entry.name).apply {
                        time = entry.time
                        method = entry.method
                        if (method == ZipEntry.STORED) {
                            size = data.size.toLong()
                            compressedSize = data.size.toLong()
                            crc = CRC32().apply { update(data) }.value
                        }
                    }
                    out.putNextEntry(copy)
                    out.write(data)
                    out.closeEntry()
                }
            }
        }
        val missing = replacements.keys - used
        require(missing.isEmpty()) { "Template APK does not contain: ${missing.joinToString()}" }
    }

    /**
     * Signs [unsigned] into [output] with APK Signature Scheme v2 and v3 (the
     * generated apps need Android 7.0+, so the old v1 scheme is not needed).
     * apksig also aligns uncompressed entries to 4 bytes.
     */
    fun sign(unsigned: File, output: File, key: PrivateKey, chain: List<X509Certificate>) {
        val signer = ApkSigner.SignerConfig.Builder("CERT", KeyConfig.Jca(key), chain).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(unsigned)
            .setOutputApk(output)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }
}
