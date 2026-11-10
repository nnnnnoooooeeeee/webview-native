package com.webview.builder.build

import com.android.apksig.internal.apk.AndroidBinXmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ManifestPatcherTest {

    private val name = "Toko Ébène 🛒 " + "x".repeat(150) // non-ASCII and longer than a 1-byte length

    @Test
    fun patchesUtf16Pool() = checkPatch(utf8 = false)

    @Test
    fun patchesUtf8Pool() = checkPatch(utf8 = true)

    private fun checkPatch(utf8: Boolean) {
        val patched = ManifestPatcher.patch(
            FakeManifest.build(utf8),
            packageName = "com.webview.template" to "com.example.shop",
            strings = mapOf("WebView Template App" to name, "0.0.0-template" to "2.3.4"),
            versionCode = 1234567,
        )
        val values = parse(patched)
        assertEquals("com.example.shop", values["manifest/package"])
        assertEquals(1234567, values["manifest/versionCode"])
        assertEquals("2.3.4", values["manifest/versionName"])
        assertEquals(name, values["application/label"])
        // Names that start with the package name follow it; similar names do not.
        assertEquals(listOf("com.example.shop.PERMISSION", "com.webview.templateX"), values["uses-permission/name"])
    }

    @Test
    fun failsWhenAPlaceholderIsMissing() {
        assertThrows(IllegalArgumentException::class.java) {
            ManifestPatcher.patch(
                FakeManifest.build(utf8 = false),
                packageName = "com.webview.template" to "com.example.shop",
                strings = mapOf("Not In The Manifest" to "x"),
                versionCode = 2,
            )
        }
    }

    /** element/attribute -> value, read with apksig's own parser (an independent reader). */
    private fun parse(xml: ByteArray): Map<String, Any> {
        val parser = AndroidBinXmlParser(ByteBuffer.wrap(xml))
        val values = mutableMapOf<String, Any>()
        val permissions = mutableListOf<String>()
        while (true) {
            val event = parser.next()
            if (event == AndroidBinXmlParser.EVENT_END_DOCUMENT) break
            if (event != AndroidBinXmlParser.EVENT_START_ELEMENT) continue
            for (i in 0 until parser.attributeCount) {
                val key = "${parser.name}/${parser.getAttributeName(i)}"
                val value: Any = if (parser.getAttributeValueType(i) == AndroidBinXmlParser.VALUE_TYPE_INT) {
                    parser.getAttributeIntValue(i)
                } else {
                    parser.getAttributeStringValue(i)
                }
                if (parser.name == "uses-permission") permissions += value as String else values[key] = value
            }
        }
        values["uses-permission/name"] = permissions
        return values
    }
}

/** Writes a small compiled AndroidManifest.xml like aapt2 does, with UTF-8 or UTF-16 strings. */
internal object FakeManifest {
    private val strings = listOf(
        "versionCode", "versionName", "label", "name", // attribute names, in the resource map
        "package", "http://schemas.android.com/apk/res/android",
        "manifest", "application", "uses-permission",
        "com.webview.template", "0.0.0-template", "WebView Template App",
        "com.webview.template.PERMISSION", "com.webview.templateX",
    )
    private val resourceIds = intArrayOf(0x0101021b, 0x0101021c, 0x01010001, 0x01010003)
    private fun s(value: String) = strings.indexOf(value)
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10

    private class Attr(val ns: Int, val name: Int, val type: Int, val data: Int)

    fun build(utf8: Boolean): ByteArray {
        val android = s("http://schemas.android.com/apk/res/android")
        fun str(ns: Int, name: String, value: String) = Attr(ns, s(name), TYPE_STRING, s(value))
        val body = ByteArrayOutputStream()
        body.write(stringPool(utf8))
        body.write(chunk(0x0180, 8, ByteBuffer.allocate(resourceIds.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { resourceIds.forEach { putInt(it) } }.array()))
        body.write(start("manifest", listOf(
            str(-1, "package", "com.webview.template"),
            Attr(android, s("versionCode"), TYPE_INT_DEC, 1),
            str(android, "versionName", "0.0.0-template"),
        )))
        body.write(start("uses-permission", listOf(str(android, "name", "com.webview.template.PERMISSION"))))
        body.write(end("uses-permission"))
        body.write(start("uses-permission", listOf(str(android, "name", "com.webview.templateX"))))
        body.write(end("uses-permission"))
        body.write(start("application", listOf(str(android, "label", "WebView Template App"))))
        body.write(end("application"))
        body.write(end("manifest"))
        return chunk(0x0003, 8, body.toByteArray())
    }

    private fun chunk(type: Int, headerSize: Int, payload: ByteArray, header: ByteArray = ByteArray(0)): ByteArray {
        val size = 8 + header.size + payload.size
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(type.toShort()).putShort(headerSize.toShort()).putInt(size)
            .put(header).put(payload).array()
    }

    private fun start(name: String, attrs: List<Attr>): ByteArray {
        val b = ByteBuffer.allocate(8 + 20 + 20 * attrs.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(1).putInt(-1) // line number, comment
        b.putInt(-1).putInt(s(name)) // namespace, name
        b.putShort(20).putShort(20).putShort(attrs.size.toShort()).putShort(0).putShort(0).putShort(0)
        for (a in attrs) {
            b.putInt(a.ns).putInt(a.name).putInt(if (a.type == TYPE_STRING) a.data else -1)
            b.putShort(8).put(0).put(a.type.toByte()).putInt(a.data)
        }
        return chunk(0x0102, 16, b.array())
    }

    private fun end(name: String): ByteArray {
        val b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(-1).putInt(-1).putInt(s(name))
        return chunk(0x0103, 16, b.array())
    }

    private fun stringPool(utf8: Boolean): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = strings.map { value ->
            val offset = data.size()
            if (utf8) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                data.write(value.length); data.write(bytes.size); data.write(bytes); data.write(0)
            } else {
                data.write(value.length); data.write(0)
                value.forEach { data.write(it.code and 0xff); data.write(it.code shr 8) }
                data.write(0); data.write(0)
            }
            offset
        }
        while (data.size() % 4 != 0) data.write(0)
        val stringsStart = 28 + 4 * strings.size
        val header = ByteBuffer.allocate(20 + 4 * strings.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(strings.size).putInt(0).putInt(if (utf8) 0x100 else 0).putInt(stringsStart).putInt(0)
        offsets.forEach { header.putInt(it) }
        return chunk(0x0001, 28, data.toByteArray(), header.array())
    }
}
