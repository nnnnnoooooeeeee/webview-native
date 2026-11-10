package com.webview.builder.build

import com.android.apksig.ApkVerifier
import com.android.apksig.internal.apk.AndroidBinXmlParser
import com.webview.builder.sign.SelfSignedCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Builds an app from the real template APK, the way the phone does. The
 * template path and placeholders come from builder/build.gradle.kts; the test
 * is skipped when the template has not been built (e.g. run from the IDE alone).
 */
class ApkWriterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun prop(name: String) = System.getProperty("template.$name").orEmpty()

    @Test
    fun buildsAndSignsAnAppFromTheTemplate() {
        val template = File(prop("apk"))
        assumeTrue("Template APK not built: $template", template.isFile)

        val manifest = ZipFile(template).use { zip -> zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes() }
        val patched = ManifestPatcher.patch(
            manifest,
            packageName = prop("applicationId") to "com.example.shop",
            strings = mapOf(prop("appName") to "My Shop", prop("versionName") to "2.3.4"),
            versionCode = 42,
        )
        val config = """{"url": "https://example.com", "pullToRefresh": false}""".toByteArray()

        val unsigned = temp.newFile("unsigned.apk")
        val signed = File(temp.root, "signed.apk")
        ApkWriter.repack(template, unsigned, mapOf("AndroidManifest.xml" to patched, "assets/webview.json" to config))

        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate = SelfSignedCertificate.create(keys, "Test", "", "", years = 1)
        ApkWriter.sign(unsigned, signed, keys.private, listOf(certificate))

        val result = ApkVerifier.Builder(signed).build().verify()
        assertTrue("Not verified: ${result.errors}", result.isVerified)
        assertTrue(result.isVerifiedUsingV2Scheme)
        assertTrue(result.isVerifiedUsingV3Scheme)
        assertEquals(certificate, result.signerCertificates.single())

        ZipFile(signed).use { zip ->
            val values = manifestValues(zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes())
            assertEquals("com.example.shop", values["manifest/package"])
            assertEquals(42, values["manifest/versionCode"])
            assertEquals("2.3.4", values["manifest/versionName"])
            assertEquals("My Shop", values["application/label"])
            assertEquals(String(config), zip.getInputStream(zip.getEntry("assets/webview.json")).reader().readText())
            // The template's old v1 signature is gone.
            assertTrue(zip.entries().toList().none { it.name.endsWith(".SF") || it.name.endsWith(".RSA") })
            // Android 11+ only installs apps whose resources.arsc is stored and 4-byte aligned.
            assertEquals(ZipEntry.STORED, zip.getEntry("resources.arsc").method)
        }
        assertEquals(0L, dataOffset(signed, "resources.arsc") % 4)
    }

    private fun manifestValues(xml: ByteArray): Map<String, Any> {
        val parser = AndroidBinXmlParser(ByteBuffer.wrap(xml))
        val values = mutableMapOf<String, Any>()
        while (parser.next() != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
            if (parser.eventType != AndroidBinXmlParser.EVENT_START_ELEMENT) continue
            for (i in 0 until parser.attributeCount) {
                values["${parser.name}/${parser.getAttributeName(i)}"] =
                    if (parser.getAttributeValueType(i) == AndroidBinXmlParser.VALUE_TYPE_INT) parser.getAttributeIntValue(i)
                    else parser.getAttributeStringValue(i)
            }
        }
        return values
    }

    /** Where [name]'s data starts in the ZIP file, from the central directory and the local header. */
    private fun dataOffset(apk: File, name: String): Long = RandomAccessFile(apk, "r").use { file ->
        fun u16(pos: Long) = file.run { seek(pos); read() or (read() shl 8) }
        fun u32(pos: Long) = u16(pos).toLong() or (u16(pos + 2).toLong() shl 16)
        var eocd = file.length() - 22
        while (u32(eocd) != 0x06054b50L) eocd--
        var pos = u32(eocd + 16)
        repeat(u16(eocd + 10)) {
            val nameLength = u16(pos + 28)
            val entryName = ByteArray(nameLength).also { file.seek(pos + 46); file.readFully(it) }.decodeToString()
            if (entryName == name) {
                val local = u32(pos + 42)
                return local + 30 + u16(local + 26) + u16(local + 28)
            }
            pos += 46 + nameLength + u16(pos + 30) + u16(pos + 32)
        }
        error("$name not in $apk")
    }
}
