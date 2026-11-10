package com.webview.builder.build

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

//========== BINARY MANIFEST PATCHER ==========

/**
 * Rewrites the compiled (binary XML) AndroidManifest.xml of the template APK.
 *
 * The template keeps its package name, app name and version name as plain
 * strings in the manifest's string pool, so they are replaced there without
 * touching any index: every reference stays valid. The versionCode is an
 * integer attribute of <manifest> and is set in place.
 *
 * Format: frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h
 */
object ManifestPatcher {

    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180

    private const val SORTED_FLAG = 0x1
    private const val UTF8_FLAG = 0x100

    /** android:versionCode */
    const val ATTR_VERSION_CODE = 0x0101021b
    private const val TYPE_INT_DEC = 0x10

    /**
     * Returns a patched copy of [manifest].
     *
     * [packageName] (old to new) replaces the package name, and also names that
     * start with it ("com.old.app.SOME_PERMISSION"). [strings] replaces other
     * whole strings. Every old value must be present, so a template that does
     * not match its template.json fails here instead of producing a broken APK.
     */
    fun patch(
        manifest: ByteArray,
        packageName: Pair<String, String>,
        strings: Map<String, String>,
        versionCode: Int,
    ): ByteArray {
        val buf = ByteBuffer.wrap(manifest).order(ByteOrder.LITTLE_ENDIAN)
        require(manifest.size >= 8 && buf.u16(0) == RES_XML_TYPE) { "AndroidManifest.xml is not a compiled XML file" }
        val headerSize = buf.u16(2)
        require(buf.u16(headerSize) == RES_STRING_POOL_TYPE) { "AndroidManifest.xml has no string pool" }
        val poolSize = buf.getInt(headerSize + 4)

        val pool = StringPool.read(buf, headerSize)
        val original = pool.strings
        val (oldPackage, newPackage) = packageName
        val found = mutableSetOf<String>()
        pool.strings = original.map { s ->
            when {
                s == oldPackage -> newPackage.also { found += s }
                s.startsWith("$oldPackage.") -> newPackage + s.substring(oldPackage.length)
                s in strings -> strings.getValue(s).also { found += s }
                else -> s
            }
        }
        val missing = (strings.keys + oldPackage) - found
        require(missing.isEmpty()) { "Template manifest does not contain: ${missing.joinToString()}" }

        // Everything after the string pool keeps its layout; only versionCode changes.
        val rest = manifest.copyOfRange(headerSize + poolSize, manifest.size)
        setVersionCode(rest, original, versionCode)

        val newPool = pool.write()
        val out = ByteArrayOutputStream(manifest.size + 256)
        out.write(manifest, 0, headerSize)
        out.write(newPool)
        out.write(rest)
        val result = out.toByteArray()
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, result.size)
        return result
    }

    /** Sets android:versionCode on the <manifest> element inside [chunks] (the XML after the string pool). */
    private fun setVersionCode(chunks: ByteArray, strings: List<String>, versionCode: Int) {
        val buf = ByteBuffer.wrap(chunks).order(ByteOrder.LITTLE_ENDIAN)
        var resourceIds = IntArray(0)
        var pos = 0
        while (pos + 8 <= chunks.size) {
            val type = buf.u16(pos)
            val headerSize = buf.u16(pos + 2)
            val size = buf.getInt(pos + 4)
            require(size >= 8 && pos + size <= chunks.size) { "AndroidManifest.xml is damaged" }
            when (type) {
                RES_XML_RESOURCE_MAP_TYPE ->
                    resourceIds = IntArray((size - headerSize) / 4) { buf.getInt(pos + headerSize + it * 4) }
                RES_XML_START_ELEMENT_TYPE -> {
                    val ext = pos + headerSize
                    val name = buf.getInt(ext + 4)
                    if (strings.getOrNull(name) == "manifest") {
                        val attrStart = buf.u16(ext + 8)
                        val attrSize = buf.u16(ext + 10)
                        val attrCount = buf.u16(ext + 12)
                        for (i in 0 until attrCount) {
                            val attr = ext + attrStart + i * attrSize
                            if (resourceIds.getOrNull(buf.getInt(attr + 4)) == ATTR_VERSION_CODE) {
                                buf.putInt(attr + 8, -1) // no raw string value
                                buf.put(attr + 15, TYPE_INT_DEC.toByte())
                                buf.putInt(attr + 16, versionCode)
                                return
                            }
                        }
                        error("Template manifest has no android:versionCode")
                    }
                }
            }
            pos += size
        }
        error("Template manifest has no <manifest> element")
    }

    private fun ByteBuffer.u16(index: Int) = getShort(index).toInt() and 0xffff

    //========== STRING POOL ==========

    private class StringPool(
        val flags: Int,
        var strings: List<String>,
        val styleOffsets: IntArray,
        val styleData: ByteArray,
    ) {
        val utf8 get() = flags and UTF8_FLAG != 0

        fun write(): ByteArray {
            val data = ByteArrayOutputStream()
            val offsets = IntArray(strings.size)
            strings.forEachIndexed { i, s ->
                offsets[i] = data.size()
                if (utf8) {
                    val bytes = s.toByteArray(Charsets.UTF_8)
                    writeLength8(data, s.length)
                    writeLength8(data, bytes.size)
                    data.write(bytes)
                    data.write(0)
                } else {
                    writeLength16(data, s.length)
                    for (c in s) {
                        data.write(c.code and 0xff)
                        data.write(c.code shr 8)
                    }
                    data.write(0)
                    data.write(0)
                }
            }
            while (data.size() % 4 != 0) data.write(0)

            val header = 28
            val stringsStart = header + 4 * strings.size + 4 * styleOffsets.size
            val size = stringsStart + data.size() + styleData.size
            val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            out.putShort(RES_STRING_POOL_TYPE.toShort())
            out.putShort(header.toShort())
            out.putInt(size)
            out.putInt(strings.size)
            out.putInt(styleOffsets.size)
            // Replaced strings are no longer in sorted order.
            out.putInt(flags and SORTED_FLAG.inv())
            out.putInt(stringsStart)
            out.putInt(if (styleOffsets.isEmpty()) 0 else stringsStart + data.size())
            offsets.forEach { out.putInt(it) }
            styleOffsets.forEach { out.putInt(it) }
            out.put(data.toByteArray())
            out.put(styleData)
            return out.array()
        }

        companion object {
            fun read(buf: ByteBuffer, start: Int): StringPool {
                val headerSize = buf.u16(start + 2)
                val size = buf.getInt(start + 4)
                val count = buf.getInt(start + 8)
                val styleCount = buf.getInt(start + 12)
                val flags = buf.getInt(start + 16)
                val stringsStart = buf.getInt(start + 20)
                val stylesStart = buf.getInt(start + 24)
                val utf8 = flags and UTF8_FLAG != 0

                val strings = List(count) { i ->
                    val pos = start + stringsStart + buf.getInt(start + headerSize + i * 4)
                    if (utf8) readUtf8(buf, pos) else readUtf16(buf, pos)
                }
                val styleOffsets = IntArray(styleCount) { buf.getInt(start + headerSize + (count + it) * 4) }
                val styleData = if (styleCount == 0) ByteArray(0) else ByteArray(size - stylesStart).also {
                    buf.duplicate().apply { position(start + stylesStart) }.get(it)
                }
                return StringPool(flags, strings, styleOffsets, styleData)
            }

            private fun readUtf8(buf: ByteBuffer, start: Int): String {
                var pos = start
                // UTF-16 length first (skipped), then the UTF-8 byte length.
                pos += if (buf.get(pos).toInt() and 0x80 != 0) 2 else 1
                val b0 = buf.get(pos).toInt() and 0xff
                val length = if (b0 and 0x80 != 0) ((b0 and 0x7f) shl 8) or (buf.get(pos + 1).toInt() and 0xff) else b0
                pos += if (b0 and 0x80 != 0) 2 else 1
                val bytes = ByteArray(length)
                buf.duplicate().apply { position(pos) }.get(bytes)
                return String(bytes, Charsets.UTF_8)
            }

            private fun readUtf16(buf: ByteBuffer, start: Int): String {
                var pos = start
                val v0 = buf.u16(pos)
                val length = if (v0 and 0x8000 != 0) ((v0 and 0x7fff) shl 16) or buf.u16(pos + 2) else v0
                pos += if (v0 and 0x8000 != 0) 4 else 2
                return String(CharArray(length) { buf.getChar(pos + it * 2) })
            }

            private fun writeLength8(out: ByteArrayOutputStream, length: Int) {
                require(length <= 0x7fff) { "Text too long" }
                if (length > 0x7f) out.write(0x80 or (length shr 8))
                out.write(length and 0xff)
            }

            private fun writeLength16(out: ByteArrayOutputStream, length: Int) {
                require(length <= 0x7fff) { "Text too long" }
                out.write(length and 0xff)
                out.write(length shr 8)
            }
        }
    }
}
