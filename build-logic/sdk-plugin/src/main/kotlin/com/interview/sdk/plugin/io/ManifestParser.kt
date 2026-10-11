package com.interview.sdk.plugin.io

import java.io.File
import java.util.zip.ZipFile

/**
 * 解析 AAR 里的 `AndroidManifest.xml` 提取 `uses-permission`。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  ⚠️ 为什么不能直接把 AAR 里的 manifest 当 XML 读
 * ══════════════════════════════════════════════════════════════════════
 *
 * AAR 里的 `AndroidManifest.xml` 在 release AAR 中是 **AXML（二进制 XML）**，
 * 不是文本 XML —— 用任何 XML parser 直接读都会抛 SAXParseException。
 *
 * 二进制的结构是：
 *   ResChunk_header(type=XML_TYPE, headerSize, size)
 *   ├─ StringPool（所有字符串集中存放，后面都用索引引用）
 *   └─ 一系列 START_ELEMENT / END_ELEMENT 块
 *
 * 权限名通常以字符串池索引的形式出现在 `startElement` 块里，
 * 所以有两种解析路径，本实现**两条都走**以求稳：
 *   ① 结构化解析：按 AXML 块协议逐块读（准确，但格式细节多）
 *   ② 字符串池兜底：从 StringPool 里捞出所有形如 `android.permission.*` 的字符串
 *
 * ⚠️ 为什么要有兜底：不同 AGP/打包工具产出的 manifest 细节有差异，
 *    结构化解析遇到不认识的块可能提前中断。而权限名一定在字符串池里 ——
 *    这是「宁可多报也不漏报」的取舍，符合权限审计的安全方向。
 *    代价是理论上可能报出未被真正 use 的权限名（如出现在注释/无效节点里），
 *    所以报告里把「声明来源」标为 AAR-MANIFEST，与宿主 manifest 区分开。
 */
internal object ManifestParser {

    private const val PERMISSION_PREFIX = "android.permission."
    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_START_ELEMENT = 0x0102

    /** AAR 里声明的权限（短名集合，已去掉 android.permission. 前缀）。 */
    fun permissionsFromAar(aar: File): Set<String> {
        val result = HashSet<String>()
        runCatching {
            ZipFile(aar).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml") ?: return@use
                val bytes = zip.getInputStream(entry).readBytes()
                result.addAll(parse(bytes))
            }
        }
        return result
    }

    /** 直接对宿主的 src/main/AndroidManifest.xml（文本）提取权限。 */
    fun permissionsFromTextManifest(manifest: File): Set<String> {
        if (!manifest.isFile) return emptySet()
        val text = runCatching { manifest.readText() }.getOrDefault("")
        return Regex("""android\.permission\.([A-Z0-9_]+)""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun parse(bytes: ByteArray): Set<String> {
        val out = HashSet<String>()
        if (bytes.size < 8) return out

        // ─── 头部：magic(0x00080003) 之后才是第一个 chunk ───
        // 有些 AAR 省略 magic，所以两种偏移都试。
        val firstChunkOffset = if (readInt(bytes, 0) == 0x00080003) 8 else 0
        var offset = firstChunkOffset
        val strings = ArrayList<String>()

        while (offset + 8 <= bytes.size) {
            val type = readShort(bytes, offset)
            val headerSize = readShort(bytes, offset + 2)
            val chunkSize = readInt(bytes, offset + 4)
            // 防御：chunkSize 异常（0 或越界）必须立刻退出，
            // 否则会在畸形 AAR 上死循环 —— 依赖是外部输入，不能假设它们合法。
            if (chunkSize <= 0 || offset + chunkSize > bytes.size || headerSize < 8) break

            when (type) {
                CHUNK_STRING_POOL -> readStringPool(bytes, offset, strings)
                CHUNK_START_ELEMENT -> {
                    // startElement 里权限名以属性值出现，属性值有两种：
                    //   · 字符串索引（属性类型 0x03）→ 查字符串池
                    //   · 直接字符串（较少见）
                    val nameIdx = readInt(bytes, offset + headerSize + 16)
                    if (nameIdx in strings.indices) {
                        val n = strings[nameIdx]
                        if (n.startsWith(PERMISSION_PREFIX)) out.add(n.removePrefix(PERMISSION_PREFIX))
                    }
                }
            }
            offset += chunkSize
        }

        // ─── 兜底路径：直接从字符串池捞 ───
        // 结构化解析若因块格式差异中断，这里保证权限名仍能被发现。
        strings.filter { it.startsWith(PERMISSION_PREFIX) }
            .forEach { out.add(it.removePrefix(PERMISSION_PREFIX)) }
        return out
    }

    /**
     * 读取 StringPool chunk。
     *
     * 结构（ResStringPool_header）：
     *   header(8) + stringCount(4) + styleCount(4) + flags(4) + stringsStart(4) + stylesStart(4)
     *   → 之后是 stringCount 个 offset（u32），指向字符串数据区。
     */
    private fun readStringPool(bytes: ByteArray, chunkOffset: Int, out: MutableList<String>) {
        val headerSize = readShort(bytes, chunkOffset + 2)
        val stringCount = readInt(bytes, chunkOffset + 8)
        val flags = readInt(bytes, chunkOffset + 16)
        val stringsStart = readInt(bytes, chunkOffset + 20)
        // UTF-8 flag：0x100 = UTF8_FLAG
        val utf8 = (flags and 0x100) != 0
        val offsetsBase = chunkOffset + headerSize
        val dataBase = chunkOffset + stringsStart

        for (i in 0 until stringCount.coerceAtMost(4096)) { // 上限防御，避免畸形数据拖死构建
            val strOffset = dataBase + readInt(bytes, offsetsBase + i * 4)
            if (strOffset < 0 || strOffset >= bytes.size) continue
            val s = if (utf8) readUtf8(bytes, strOffset) else readUtf16(bytes, strOffset)
            out.add(s)
        }
    }

    /** AXML 的 UTF-8 字符串：u8 字符数 + u8 字节数 + 数据 + \0（都可能是 1~2 字节变长）。 */
    private fun readUtf8(bytes: ByteArray, offset: Int): String {
        var p = offset
        val (_, p1) = readLength8(bytes, p)
        val (byteLen, p2) = readLength8(bytes, p1)
        if (byteLen <= 0 || p2 + byteLen > bytes.size) return ""
        return String(bytes, p2, byteLen, Charsets.UTF_8)
    }

    /** AXML 的 UTF-16 字符串：u16 字符数 + 数据 + \0。 */
    private fun readUtf16(bytes: ByteArray, offset: Int): String {
        var p = offset
        val (charLen, p1) = readLength16(bytes, p)
        val byteLen = charLen * 2
        if (byteLen <= 0 || p1 + byteLen > bytes.size) return ""
        return String(bytes, p1, byteLen, Charsets.UTF_16LE)
    }

    /** 1~2 字节变长长度：最高位为 1 表示还需读下一个字节。 */
    private fun readLength8(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        val b0 = bytes[offset].toInt() and 0xFF
        return if (b0 and 0x80 != 0) {
            val b1 = bytes[offset + 1].toInt() and 0xFF
            (((b0 and 0x7F) shl 8) or b1) to (offset + 2)
        } else b0 to (offset + 1)
    }

    private fun readLength16(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        val v0 = readShort(bytes, offset)
        return if (v0 and 0x8000 != 0) {
            val v1 = readShort(bytes, offset + 2)
            (((v0 and 0x7FFF) shl 16) or v1) to (offset + 4)
        } else v0 to (offset + 2)
    }

    private fun readShort(b: ByteArray, o: Int): Int =
        if (o + 2 > b.size) 0
        else (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun readInt(b: ByteArray, o: Int): Int =
        if (o + 4 > b.size) 0
        else (b[o].toInt() and 0xFF) or
                ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or
                ((b[o + 3].toInt() and 0xFF) shl 24)
}
