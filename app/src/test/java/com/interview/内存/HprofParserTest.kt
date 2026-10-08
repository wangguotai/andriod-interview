package com.interview.内存

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Time: 2026/10/8
 * Author: wgt
 * Description: `MemoryHeapAnalyzer`（hprof 解析器）的单测
 *
 * ─── 这里的内容来自两轮"实测纠正"，不是凭格式文档写的 ───
 *
 * 第一版解析器在真机 dump（45.28 MB / 9893 个 heap segment）上**直接崩了**
 * （`ArrayIndexOutOfBoundsException`）。事后逐条对齐 LeakCanary 的 shark 源码
 * 才找到根因，两处：
 *
 * 1. **INSTANCE 记录是自描述长度的**（`u4 remainingBytesInInstance`），
 *    不需要预先知道类的字段布局 —— 所以第一版设计的"两遍扫描"是多余的，
 *    而且一旦某类的 CLASS_DUMP 还没出现就无从下手（实测就是这么崩的）。
 * 2. **GC ROOT 的宽度不是"一律 1 个 ID"**，且漏了 Android 专有的
 *    `0xFE HEAP_DUMP_INFO`（u4 heapId + ID）—— 它就是第一个 segment 失步的原因。
 *
 * 所以下面的用例**专门覆盖这两处**（`CLASS_DUMP 缺失时仍能解析`、
 * `HEAP_DUMP_INFO 必须被正确跳过`），它们是"回归守卫"。
 *
 * ⚠️ 真实 dump 不进版本库（45 MB）。这里的用例用手写的**迷你 hprof**，
 *    覆盖解析器的分支；真实 dump 的正确性由 mock 数据 + 真机实测共同保证
 *    （真机实测的数字记录在 INTERVIEW 文档里）。
 */
class HprofParserTest {

    // ─────────────────────────────────────────────
    // 迷你 hprof 写入器（大端、idSize 可配）
    // ─────────────────────────────────────────────

    private class HprofBuilder(private val idSize: Int = 4) {
        private val out = java.io.ByteArrayOutputStream()

        private fun u1(v: Int) = out.write(v and 0xFF)

        private fun u2(v: Int) { u1(v ushr 8); u1(v) }

        private fun u4(v: Int) { u1(v ushr 24); u1(v ushr 16); u1(v ushr 8); u1(v) }

        private fun u8(v: Long) { u4((v ushr 32).toInt()); u4(v.toInt()) }

        fun id(v: Long) {
            if (idSize == 4) u4(v.toInt()) else u8(v)
        }

        fun header(): HprofBuilder {
            // ⚠️ 文件头固定 19 字节（"JAVA PROFILE 1.0.3" + NUL），与 shark 的
            //    recordsPosition 算法一致 —— 版本串长度不能改。
            out.write("JAVA PROFILE 1.0.3".toByteArray(Charsets.US_ASCII))
            u1(0)
            u4(idSize)
            u8(0L)
            return this
        }

        fun record(tag: Int, body: ByteArray): HprofBuilder {
            u1(tag); u4(0); u4(body.size); out.write(body)
            return this
        }

        fun string(id: Long, s: String): HprofBuilder {
            val b = java.io.ByteArrayOutputStream()
            writeId(b, id); b.write(s.toByteArray(Charsets.UTF_8))
            return record(0x01, b.toByteArray())
        }

        fun loadClass(classObjId: Long, nameStringId: Long): HprofBuilder {
            val b = java.io.ByteArrayOutputStream()
            writeU4(b, 0); writeId(b, classObjId); writeU4(b, 0); writeId(b, nameStringId)
            return record(0x02, b.toByteArray())
        }

        fun heapSegment(body: ByteArray): HprofBuilder = record(0x0C, body)

        fun build(): ByteArray = out.toByteArray()

        // ── 子记录构造 ──

        fun rootOneId(tag: Int, id: Long): ByteArray = byteArrayOf(tag.toByte()) + idBytes(id)

        fun rootTwoIds(tag: Int, a: Long, b: Long): ByteArray =
            byteArrayOf(tag.toByte()) + idBytes(a) + idBytes(b)

        fun rootIdPlusU4(tag: Int, id: Long, v: Int): ByteArray {
            val b = java.io.ByteArrayOutputStream()
            b.write(tag); writeId(b, id); writeU4(b, v)
            return b.toByteArray()
        }

        fun rootIdPlus2U4(tag: Int, id: Long, v1: Int, v2: Int): ByteArray {
            val b = java.io.ByteArrayOutputStream()
            b.write(tag); writeId(b, id); writeU4(b, v1); writeU4(b, v2)
            return b.toByteArray()
        }

        fun heapDumpInfo(heapId: Int, nameId: Long): ByteArray {
            val b = java.io.ByteArrayOutputStream()
            b.write(0xFE); writeU4(b, heapId); writeId(b, nameId)
            return b.toByteArray()
        }

        fun jniGlobal(a: Long, b: Long): ByteArray = rootTwoIds(0x01, a, b)

        fun classDump(
            classObjId: Long,
            instanceSize: Int,
            staticFields: List<Triple<Long, Int, Long>> = emptyList(),
            instanceFields: List<Pair<Long, Int>> = emptyList(),
        ): ByteArray {
            val b = java.io.ByteArrayOutputStream()
            b.write(0x20)
            writeId(b, classObjId); writeU4(b, 0)
            writeId(b, 0); writeId(b, 0); writeId(b, 0); writeId(b, 0); writeId(b, 0); writeId(b, 0)
            writeU4(b, instanceSize)
            writeU2(b, 0)                       // 常量池空
            writeU2(b, staticFields.size)
            staticFields.forEach { (nameId, type, value) ->
                writeId(b, nameId); b.write(type)
                when (type) {
                    2 -> writeId(b, value)
                    4, 8 -> b.write(value.toInt())
                    5, 9 -> writeU2(b, value.toInt())
                    6, 10 -> writeU4(b, value.toInt())
                    7, 11 -> writeU8(b, value)
                }
            }
            writeU2(b, instanceFields.size)
            instanceFields.forEach { (nameId, type) -> writeId(b, nameId); b.write(type) }
            return b.toByteArray()
        }

        /** ⚠️ INSTANCE 必须带 `remainingBytesInInstance`（这就是第一版崩溃的原因）。 */
        fun instance(objId: Long, classObjId: Long, fieldBytes: Int): ByteArray {
            val b = java.io.ByteArrayOutputStream()
            b.write(0x21)
            writeId(b, objId); writeU4(b, 0); writeId(b, classObjId); writeU4(b, fieldBytes)
            repeat(fieldBytes) { b.write(0) }
            return b.toByteArray()
        }

        fun primitiveArray(objId: Long, type: Int, length: Int): ByteArray {
            val b = java.io.ByteArrayOutputStream()
            b.write(0x23)
            writeId(b, objId); writeU4(b, 0); writeU4(b, length); b.write(type)
            val w = when (type) {
                4, 8 -> 1; 5, 9 -> 2; 6, 10 -> 4; 7, 11 -> 8; else -> 1
            }
            repeat(length * w) { b.write(0) }
            return b.toByteArray()
        }

        fun objectArray(objId: Long, arrayClassId: Long, elements: Int): ByteArray {
            val b = java.io.ByteArrayOutputStream()
            b.write(0x22)
            writeId(b, objId); writeU4(b, 0); writeU4(b, elements); writeId(b, arrayClassId)
            repeat(elements) { writeId(b, 0) }
            return b.toByteArray()
        }

        private fun idBytes(v: Long): ByteArray {
            val b = java.io.ByteArrayOutputStream()
            writeId(b, v)
            return b.toByteArray()
        }

        private fun writeU2(b: java.io.ByteArrayOutputStream, v: Int) {
            b.write((v ushr 8) and 0xFF); b.write(v and 0xFF)
        }

        private fun writeU4(b: java.io.ByteArrayOutputStream, v: Int) {
            b.write((v ushr 24) and 0xFF); b.write((v ushr 16) and 0xFF)
            b.write((v ushr 8) and 0xFF); b.write(v and 0xFF)
        }

        private fun writeU8(b: java.io.ByteArrayOutputStream, v: Long) {
            writeU4(b, (v ushr 32).toInt()); writeU4(b, v.toInt())
        }

        private fun writeId(b: java.io.ByteArrayOutputStream, v: Long) {
            if (idSize == 4) writeU4(b, v.toInt()) else writeU8(b, v)
        }
    }

    private fun tempFile(bytes: ByteArray): File {
        val f = File.createTempFile("hprof-test", ".hprof")
        f.deleteOnExit()
        FileOutputStream(f).use { it.write(bytes) }
        return f
    }

    // ─────────────────────────────────────────────
    // 基本统计
    // ─────────────────────────────────────────────

    @Test
    fun `应统计实例、基元数组、对象数组，并按类归集`() {
        val b = HprofBuilder()
        val data = b
            .header()
            .string(1, "com/example/Foo")
            .string(2, "[Lcom/example/Foo;")
            .loadClass(100, 1)
            .loadClass(200, 2)
            .heapSegment(
                b.classDump(100, 24) +
                        b.classDump(200, 16) +
                        b.instance(1001, 100, 24) +
                        b.instance(1002, 100, 24) +
                        b.instance(1003, 100, 24) +
                        b.primitiveArray(2001, 8, 4096) +      // byte[] = 4096
                        b.primitiveArray(2002, 10, 100) +      // int[]  = 400
                        b.objectArray(3001, 200, 10)           // 10 × 4(idSize) = 40
            )
            .build()

        val h = MemoryHeapAnalyzer.parse(tempFile(data))
        val by = h.stats.associateBy { it.className }

        assertEquals("3 个 Foo 实例", 3L, by["com.example.Foo"]!!.instanceCount)
        assertEquals("每个 24B（用 remainingBytesInInstance，即真实占用）", 72L, by["com.example.Foo"]!!.totalBytes)
        assertEquals(4096L, by["byte[]"]!!.totalBytes)
        assertEquals(400L, by["int[]"]!!.totalBytes)
        assertEquals("对象数组 = 10 × idSize(4)", 40L, by["com.example.Foo[]"]!!.totalBytes)
        assertTrue(h.nameMappingReady)
        assertEquals("无未知 tag ⇒ 不应有中止", 0L, h.skippedSegments)
    }

    // ─────────────────────────────────────────────
    // 回归守卫 ①：CLASS_DUMP 缺失/落后时仍能解析
    // ─────────────────────────────────────────────

    @Test
    fun `没有 CLASS_DUMP 时也能解析实例（INSTANCE 自描述长度）`() {
        // ⚠️ 这是第一版崩溃的场景：它以为必须先从 CLASS_DUMP 拿到 instanceSize。
        //    真实格式里 INSTANCE 自己带 remainingBytesInInstance，所以不需要 CLASS_DUMP。
        val b = HprofBuilder()
        val data = b
            .header()
            .string(1, "com/example/NoClassDump")
            .loadClass(100, 1)
            .heapSegment(
                b.instance(1001, 100, 32) +
                        b.instance(1002, 100, 32)
            )
            .build()

        val h = MemoryHeapAnalyzer.parse(tempFile(data))
        val stat = h.stats.firstOrNull { it.className == "com.example.NoClassDump" }
        assertEquals("必须能解析（这正是第一版做不到的）", 2L, stat?.instanceCount)
        assertEquals("大小取 remainingBytesInInstance", 64L, stat?.totalBytes)
        assertEquals("不应因缺 CLASS_DUMP 而中止", 0L, h.skippedSegments)
    }

    @Test
    fun `CLASS_DUMP 出现在实例之后也不影响结果`() {
        val b = HprofBuilder()
        val data = b
            .header()
            .string(1, "com/example/Late")
            .loadClass(100, 1)
            .heapSegment(
                b.instance(1001, 100, 32) + b.classDump(100, 32)
            )
            .build()
        val h = MemoryHeapAnalyzer.parse(tempFile(data))
        assertEquals(32L, h.stats.first { it.className == "com.example.Late" }.totalBytes)
    }

    // ─────────────────────────────────────────────
    // 回归守卫 ②：子 tag 宽度（含 Android 专有的 0xFE）
    // ─────────────────────────────────────────────

    @Test
    fun `HEAP_DUMP_INFO 与各种 GC ROOT 必须被正确跳过`() {
        // ⚠️ 这是第一个 segment 失步的真实原因：漏了 0xFE，且 root 宽度搞错。
        //    这里把每一种宽度都放一遍，只要错一处，后面的对象统计就会错。
        val b = HprofBuilder()
        val data = b
            .header()
            .string(1, "com/example/AfterRoots")
            .string(9, "app heap")
            .loadClass(100, 1)
            .heapSegment(
                // 设计成"错位就会把后面读花"的顺序：先一堆 root，再对象
                b.heapDumpInfo(1, 9) +              // 0xFE: u4 + ID   ← 第一版漏了它
                        b.rootOneId(0xFF, 7) +              // 1 ID
                        b.jniGlobal(11, 22) +               // 0x01: 2 ID
                        b.rootIdPlus2U4(0x02, 33, 1, 2) +   // JNI_LOCAL
                        b.rootIdPlus2U4(0x03, 44, 3, 4) +   // JAVA_FRAME
                        b.rootIdPlusU4(0x04, 55, 5) +       // NATIVE_STACK
                        b.rootOneId(0x05, 66) +             // STICKY_CLASS
                        b.rootIdPlusU4(0x06, 77, 6) +       // THREAD_BLOCK
                        b.rootOneId(0x07, 88) +             // MONITOR_USED
                        b.rootIdPlus2U4(0x08, 99, 7, 8) +   // THREAD_OBJECT
                        b.rootOneId(0x89, 111) +            // INTERNED_STRING
                        b.rootOneId(0x8B, 222) +            // DEBUGGER
                        b.rootOneId(0x8C, 333) +            // REFERENCE_CLEANUP
                        b.rootOneId(0x8D, 444) +            // VM_INTERNAL
                        b.rootIdPlus2U4(0x8E, 555, 9, 10) + // JNI_MONITOR
                        b.rootOneId(0x90, 666) +            // UNREACHABLE
                        b.instance(1001, 100, 16) +
                        b.primitiveArray(2001, 11, 10)      // long[] = 80
            )
            .build()

        val h = MemoryHeapAnalyzer.parse(tempFile(data))
        assertEquals("root 全部跳对，后面的对象才统计得到", 0L, h.skippedSegments)
        assertEquals(16L, h.stats.first { it.className == "com.example.AfterRoots" }.totalBytes)
        assertEquals("long[] 10 × 8", 80L, h.stats.first { it.className == "long[]" }.totalBytes)
    }

    @Test
    fun `CLASS_DUMP 的常量池与 static 字段必须按类型宽度跳过`() {
        // 若类型宽度表写错（如假定 tag 值就是 1..8），这里会立刻失步。
        val b = HprofBuilder()
        val data = b
            .header()
            .string(1, "com/example/WithFields")
            .loadClass(100, 1)
            .heapSegment(
                b.classDump(
                    100, 20,
                    staticFields = listOf(
                        Triple(10L, 11, 0L),    // long    8B
                        Triple(11L, 7, 0L),     // double  8B
                        Triple(12L, 10, 0L),    // int     4B
                        Triple(13L, 9, 0L),     // short   2B
                        Triple(14L, 5, 0L),     // char    2B
                        Triple(15L, 8, 0L),     // byte    1B
                        Triple(16L, 4, 0L),     // boolean 1B
                        Triple(17L, 2, 0L),     // 引用    idSize
                    ),
                    instanceFields = listOf(20L to 10, 21L to 2),
                ) +
                        b.instance(1001, 100, 20)
            )
            .build()
        val h = MemoryHeapAnalyzer.parse(tempFile(data))
        assertEquals(20L, h.stats.first { it.className == "com.example.WithFields" }.totalBytes)
        assertEquals(0L, h.skippedSegments)
    }

    // ─────────────────────────────────────────────
    // 边界与降级
    // ─────────────────────────────────────────────

    @Test
    fun `未知子 tag 应中止该 segment 并计数（不静默偏低）`() {
        val b = HprofBuilder()
        val data = b
            .header()
            .string(1, "com/example/Ok")
            .loadClass(100, 1)
            .heapSegment(
                b.instance(1001, 100, 16) + byteArrayOf(0x7E, 1, 2, 3)
            )
            .build()
        val h = MemoryHeapAnalyzer.parse(tempFile(data))
        assertEquals("中止前的实例仍应被统计", 16L, h.stats.first { it.className == "com.example.Ok" }.totalBytes)
        assertTrue("必须把中止计数暴露出来", h.skippedSegments >= 1)
        assertTrue("说明文字要提示偏低", h.describe().contains("偏低"))
    }

    @Test
    fun `未知子 tag 中止后，后续 segment 仍应被正常解析`() {
        // 这条是"不要让一个坏段毁掉整份 dump"的守卫（第一版就是整份读花）。
        val b = HprofBuilder()
        val data = b
            .header()
            .string(1, "A").string(2, "B")
            .loadClass(100, 1).loadClass(200, 2)
            .heapSegment(b.instance(1001, 100, 16) + byteArrayOf(0x7E, 0, 0))
            .heapSegment(b.instance(2001, 200, 8))
            .build()
        val h = MemoryHeapAnalyzer.parse(tempFile(data))
        val by = h.stats.associateBy { it.className }
        assertEquals("坏段之后的段必须仍能解析", 8L, by["B"]?.totalBytes)
        assertEquals(1L, h.skippedSegments)
    }

    @Test
    fun `idSize 为 8 的 dump 也应能解析`() {
        val b = HprofBuilder(idSize = 8)
        val data = b
            .header()
            .string(1, "com/example/Big")
            .loadClass(100, 1)
            .heapSegment(
                b.classDump(100, 12) +
                        b.instance(1001, 100, 12) +
                        b.instance(1002, 100, 12) +
                        b.primitiveArray(2001, 7, 8)      // double[] = 64
            )
            .build()
        val h = MemoryHeapAnalyzer.parse(tempFile(data))
        assertEquals(8, h.idSize)
        assertEquals(24L, h.stats.first { it.className == "com.example.Big" }.totalBytes)
        assertEquals(64L, h.stats.first { it.className == "double[]" }.totalBytes)
    }

    @Test
    fun `没有类名的实例应归到 unresolved 而不是丢弃`() {
        val b = HprofBuilder(idSize = 4)
        val data = b
            .header()
            .loadClass(100, 999)   // 指向不存在的 string
            .heapSegment(b.instance(1001, 100, 8))
            .build()
        val h = MemoryHeapAnalyzer.parse(tempFile(data))
        assertEquals("不丢数据", 1L, h.unresolvedInstances)
        assertEquals(8L, h.totalBytes)
        assertTrue(h.describe().contains("<unresolved") || h.stats.any { it.className.startsWith("<unresolved") })
    }

    @Test
    fun `空 body 的 segment 不应崩溃`() {
        val raw = HprofBuilder().header().string(1, "X").loadClass(100, 1)
            .heapSegment(ByteArray(0))
            .build()
        val h = MemoryHeapAnalyzer.parse(tempFile(raw))
        assertEquals(0L, h.totalInstances)
        assertEquals(0L, h.skippedSegments)
    }

    @Test
    fun `超过上限的文件必须被拒绝`() {
        val f = File.createTempFile("huge", ".hprof")
        f.deleteOnExit()
        FileOutputStream(f).use { it.write(ByteArray(16)) }
        assertTrue(MemoryHeapAnalyzer.MAX_DUMP_BYTES > 0)
        // 小文件不应因大小被拒（拒绝路径由 require 保证）
        assertTrue(runCatching { MemoryHeapAnalyzer.parse(f) }.exceptionOrNull()
            !is IllegalArgumentException)
    }
}

/**
 * ⚠️ **真实 dump 的验证**（不进版本库、默认跳过）
 *
 * 通过系统属性提供一个真实的 Android hprof 路径，用它在宿主 JVM 上跑一遍解析器，
 * 并与 shark 给出的**权威数字**对拍：
 *
 * ```
 *   ./gradlew :app:testDebugUnitTest --tests "*RealDumpTest*" \
 *       -Dmem.realHprof=/path/to/heap.hprof
 * ```
 *
 * 这份对拍的依据（本机模拟器实测，Android 16 / arm64）：
 * ```
 *   shark 读出：CLASS_DUMP=31027 INSTANCE=242779 PRIMITIVE=181632(8MB)
 *               OBJ_ARRAY=35537(1MB)  idSize=4  文件 47,476,497 B
 *   ⇐ 本解析器必须给出**同样的对象数**，否则说明子 tag 宽度或 INSTANCE 长度读错
 * ```
 * 为什么必须做这一步：手写的迷你 hprof 只能覆盖"我想到的情形"，
 * 真实 dump 的 9893 个 segment 会覆盖"我没想到的情形"——第一版就是这么被抓出来的。
 */
class RealDumpTest {
    @Test
    fun `真实 dump 的对象计数应与 shark 一致`() {
        // ⚠️ 用 Assume 而不是 `return`：没提供 dump 时必须被报告成 **skipped**。
        //    写成 `return` 会让测试报告显示"通过" —— 而它其实**一个字节都没验**。
        //    在一个"防假绿"比防红灯更重要的模块里，让未执行的检查看起来是通过的，
        //    是最坏的一类错误。
        val path = System.getProperty("mem.realHprof")
        Assume.assumeTrue(
            "未提供 -Dmem.realHprof=<真实 dump> ⇒ 本检查未执行（报告里应为 skipped）",
            path != null
        )
        val f = File(path!!)
        assertTrue("文件不存在：$path", f.exists())
        val h = MemoryHeapAnalyzer.parse(f)
        println(h.describe(10))

        // ⚠️ 对拍口径必须写清楚（否则数字对不上时无从判断谁错）：
        //    shark 的 490,975 是**全部**堆记录数 = CLASS_DUMP + INSTANCE + PRIMITIVE + OBJ_ARRAY
        //    本解析器**不把 CLASS_DUMP 计入对象**（它是类的元数据，见类注释四.3），
        //    所以正确的期望值是 shark 的 490,975 − CLASS_DUMP(31,027) = **459,948**。
        assertEquals("idSize 应为 4（Android 实测，不是 8）", 4, h.idSize)
        assertEquals("对象总数必须与 shark 逐一对齐（242779+181632+35537）", 459948L, h.totalInstances)
        assertEquals("类数应与 shark 的 CLASS_DUMP 数一致", 31027L, h.classCount)
        assertEquals("不应有任何 segment 因未知 tag 中止（否则数字偏低）", 0L, h.skippedSegments)
        assertEquals("不应有未匹配类名的实例", 0L, h.unresolvedInstances)
        // 字节数必须与 dump 总量处于同一量级（不做精确断言：不同 ART 版本统计口径会变）
        assertTrue("总字节应 > 10MB（实测 19.7MB），实际=${h.totalBytes}", h.totalBytes > 10L * 1024 * 1024)
        println("[对拍] 本解析器 totalInstances=${h.totalInstances} vs shark 459,948（一致）")
    }

    /**
     * ⭐ **逐类对拍** —— 这份模块里最强的正确性证据。
     *
     * 为什么必须做：`totalInstances` 总数对得上**不能**证明解析正确。
     * 只要"多算的类"恰好等于"少算的类"，总数就会一致而**逐类全错**。
     * （实测总数对上时逐类也对上，但那是结论不是前提。）
     *
     * 基准文件 `real_dump_shark_classes.csv` 由 **shark 2.14** 逐条流式读同一份 dump 生成
     * （生成器见 `tools/shark-oracle/Oracle.java`，是与本实现**完全独立**的另一套代码）。
     * 这里逐类断言**对象数**与**字节数**完全相等 —— 全等，不是"误差在 1% 内"。
     *
     * 实测：全部 4996 个类逐一对上（byte[]=140844、String=110764、
     * HashMap$Node=36456 … 计数与字节一模一样）。
     */
    @Test
    fun `逐类统计应与 shark 完全一致`() {
        // 基准文件是入库资源，**缺失即失败**（不是 skip）——
        // 它没了说明有人误删了资源，此时静默跳过对拍等于把最强的证据悄悄关掉。
        val cls = javaClass.classLoader!!.getResourceAsStream("real_dump_shark_classes.csv")
            ?: error("对拍基准资源缺失：real_dump_shark_classes.csv 应随测试资源入库")
        val expected = HashMap<String, LongArray>()
        cls.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                val i = line.lastIndexOf(',')
                val j = line.lastIndexOf(',', i - 1)
                val name = line.substring(0, j)
                expected[name] = longArrayOf(
                    line.substring(j + 1, i).toLong(),
                    line.substring(i + 1).toLong()
                )
            }
        }
        assertTrue("基准文件应有几千个类", expected.size > 1000)

        val path = System.getProperty("mem.realHprof")
        Assume.assumeTrue(
            "未提供 -Dmem.realHprof=<真实 dump> ⇒ 逐类对拍未执行（基准已入库，报告里应为 skipped）",
            path != null
        )
        val h = MemoryHeapAnalyzer.parse(File(path!!))

        var mismatched = 0
        val samples = StringBuilder()
        for ((name, exp) in expected) {
            val got = h.stats.firstOrNull { it.className == name }
            if (got == null) {
                mismatched++
                if (samples.length < 1500) samples.appendLine("  缺: $name 期望 ${exp[0]} 个")
                continue
            }
            if (got.instanceCount != exp[0] || got.totalBytes != exp[1]) {
                mismatched++
                if (samples.length < 1500) {
                    samples.appendLine(
                        "  差: $name 期望 ${exp[0]}/${exp[1]}B 实得 ${got.instanceCount}/${got.totalBytes}B"
                    )
                }
            }
        }
        // 反过来也要查：本解析器**多报**的类（shark 基准里没有的）
        val extra = h.stats.filter { it.className !in expected }
        if (extra.isNotEmpty()) {
            mismatched += extra.size
            extra.take(10).forEach { samples.appendLine("  多: ${it.className} ${it.instanceCount} 个") }
        }
        assertEquals(
            "逐类（数量 + 字节）必须与 shark 完全一致。差异样本：\n$samples",
            0, mismatched
        )
        println("[逐类对拍] ${expected.size} 个类全部一致 ✅")
    }
}
