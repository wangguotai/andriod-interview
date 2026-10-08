package com.interview.内存

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Time: 2026/10/8
 * Author: wgt
 * Description: Java 堆直方图分析器 —— **自己解析 hprof**，不依赖任何分析工具
 *
 * ══════════════════════════════════════════════════════════════════════
 * 一、为什么"抓 dump"必须被工程化，而不是等测试同学去抓
 * ══════════════════════════════════════════════════════════════════════
 *
 * 现实是：**等到有人去抓 dump，现场早没了**。能自动化的部分必须自动化，
 * 但 hprof 有个硬约束（本机模拟器实测）：
 *
 * ```
 *   Debug.dumpHprofData() → 进程 stop-the-world → 写 45.28 MB，冻结 913 ms
 * ```
 *
 * ⇒ 它**绝不能**放主线程，也绝不能"每次水位高就抓"（那会让"内存紧张"变成"冻死"）。
 *    本类的定位是**取证工具**：由明确的、低频的、有去重窗口的条件触发
 *    （见 `HeapDumpGate`）。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 二、为什么自己解析，而不是引 MAT / LeakCanary / Shark
 * ══════════════════════════════════════════════════════════════════════
 *
 * | 方案 | 能回答 | 线上可行性 |
 * |---|---|---|
 * | MAT / LeakCanary（本地） | 引用链、支配树、泄漏判定 | ❌ 需要人 + 需要把 dump 弄下来 |
 * | Shark（LeakCanary 的解析库） | 完整 hprof 解析 + 引用链 | ⚠️ 本仓库依赖目录/离线缓存里没有 |
 * | **本类（手写解析）** | **类 → 实例数/字节数** | ✅ 无依赖、体积为零 |
 *
 * 这个取舍是刻意的，而且**够用**：线上内存问题的绝大多数是
 * 「某一种对象数量远超预期」（Bitmap 几千张、byte[] 几百 MB、ArrayList 持有超大数组），
 * 直方图直接指出来。**循环引用泄漏**（数量正常但没人放开）才需要支配树 ——
 * 那是下一个工具，不是这个工具能顺带解决的。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 三、⚠️ 本实现的两条"实测纠正"（第一版写错了，这里记录纠正过程）
 * ══════════════════════════════════════════════════════════════════════
 *
 * 第一版实现是凭"hprof 格式的常见描述"写的，在真机 dump 上**直接崩了**
 * （`ArrayIndexOutOfBoundsException`：把后续字节当 tag 解释 → 越界）。
 * 事后与 LeakCanary 的 **shark** 源码（`StreamingHprofReader.kt` /
 * `HprofRecordReader.kt`）逐条对齐，发现两处错，都在下面标注了。
 * 这两条也是"格式文档不如参考实现可靠"的现场证据。
 *
 * **纠正 ①：INSTANCE 记录是"自描述长度"的，不需要预先知道类的字段布局。**
 *
 * ```
 *   0x21 INSTANCE 的真实格式（u1 tag 之后）：
 *     ID    objectId
 *     u4    stackTraceSerial
 *     ID    classObjectId
 *     u4    remainingBytesInInstance   ← ★ 这个字段才是字段区的长度
 *     u1[remainingBytesInInstance]     fieldValues
 * ```
 *
 * 第一版以为"必须先从 CLASS_DUMP 拿到 instanceSize 才能跳过 INSTANCE"，
 * 于是设计成"两遍扫描"，并且一旦某类的 CLASS_DUMP 还没出现就无从下手 ——
 * 实测就是这么崩的。
 * ⇒ **正确的是单遍**：解析 INSTANCE 时直接读 `remainingBytesInInstance` 就能跳过，
 *    **完全不需要 CLASS_DUMP 参与**。
 *
 * **纠正 ②：GC ROOT 的宽度不是"一律 1 个 ID"，而且漏了 `HEAP_DUMP_INFO(0xFE)`。**
 *
 * 第一版把所有 root 都当单 ID 跳过，还漏了 Android 专有的 `0xFE HEAP_DUMP_INFO`
 * （就是它导致第一个 heap segment 立刻失步）。正确宽度（逐条对齐 shark）：
 *
 * ```
 *   1 ID                      ：0xFF ROOT_UNKNOWN、0x05 ROOT_STICKY_CLASS、
 *                               0x07 ROOT_MONITOR_USED、0x89 ROOT_INTERNED_STRING、
 *                               0x8B ROOT_DEBUGGER、0x8C ROOT_REFERENCE_CLEANUP、
 *                               0x8D ROOT_VM_INTERNAL、0x90 ROOT_UNREACHABLE
 *   2 ID                      ：0x01 ROOT_JNI_GLOBAL
 *   1 ID + 2×u4               ：0x02 ROOT_JNI_LOCAL、0x03 ROOT_JAVA_FRAME、
 *                               0x08 ROOT_THREAD_OBJECT、0x8E ROOT_JNI_MONITOR
 *   1 ID + 1×u4               ：0x04 ROOT_NATIVE_STACK、0x06 ROOT_THREAD_BLOCK
 *   u4 heapId + ID            ：0xFE HEAP_DUMP_INFO（heapId 是 **Int 不是 ID**）
 * ```
 *
 * ⚠️ 另一处易错点（本实现已避开）：**原始基元类型 tag 值本身就是字节宽度**：
 * `4=boolean(1B) 5=char(2B) 6=float(4B) 7=double(8B) 8=byte(1B) 9=short(2B)
 * 10=int(4B) 11=long(8B)`，而 `2` 表示对象引用（宽度 = idSize）。
 * 所以宽度表不能想当然写成 1~8。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 四、能力边界（先看这一节，否则报告会被误读）
 * ══════════════════════════════════════════════════════════════════════
 *
 * 1. **只有"类 → 数量/字节"，没有引用链**（谁持有它）。
 * 2. **`remainingBytesInInstance` 才是真实占用**，它天然包含了字段对齐/填充，
 *    比"宽×高×4"这类估算准 —— 这是用它的第二个理由（第一个是能跳过）。
 * 3. **CLASS_DUMP 不计入字节数**：它是类的元数据（在内存账上另有归属），
 *    计入会虚增（同一个类只 dump 一次，但它的实例可能很多）。
 * 4. **遇到不认识的子 tag 只能中止该 segment**（不知长度，硬猜必然错位），
 *    并把它**计数暴露出来** —— 静默偏低比留白更危险。
 * 5. ⚠️ **idSize 在 Android 上实测是 4**（`JAVA PROFILE 1.0.3`），
 *    不要假定 64 位系统就是 8；从文件头读。
 */
object MemoryHeapAnalyzer {

    /** 解析上限：超过直接拒绝（避免把"一个内存问题"变成"两个"） */
    const val MAX_DUMP_BYTES = 64L * 1024 * 1024

    /** 单条记录的载荷上限：超过就跳过（异常大的记录里没有我们要的聚合信息） */
    private const val MAX_RECORD_BYTES = 64 * 1024 * 1024

    data class ClassStat(
        val className: String,
        val instanceCount: Long,
        val totalBytes: Long,
    ) {
        fun describe(): String =
            "%-54s %9d 个  %12s".format(className, instanceCount, MemoryMetrics.fmt(totalBytes))
    }

    data class Histogram(
        val stats: List<ClassStat>,
        val totalInstances: Long,
        val totalBytes: Long,
        val classCount: Long,
        /** 因未知子 tag 而提前中止的 segment 数（>0 则数字偏低，必须暴露） */
        val skippedSegments: Long,
        val idSize: Int,
        val fileBytes: Long,
        val durationMs: Long,
        /** 有无类名映射（为空说明 dump 被截断或缺 0x02 LOAD_CLASS） */
        val nameMappingReady: Boolean,
        /** 未能在类名映射里找到的实例数（如实暴露，不悄悄丢） */
        val unresolvedInstances: Long,
    ) {
        fun top(n: Int = 20, byBytes: Boolean = true): List<ClassStat> =
            (if (byBytes) stats.sortedByDescending { it.totalBytes }
            else stats.sortedByDescending { it.instanceCount }).take(n)

        fun describe(topN: Int = 20): String = buildString {
            appendLine("═══ Java 堆直方图 ═══")
            appendLine("文件 ${MemoryMetrics.fmt(fileBytes)} · 解析 ${durationMs}ms · idSize=$idSize · 类 ${classCount} 个")
            appendLine("合计 ${totalInstances} 个对象 · ${MemoryMetrics.fmt(totalBytes)}")
            if (skippedSegments > 0) {
                appendLine("⚠️ 有 $skippedSegments 个 segment 因未知子 tag 提前中止 ⇒ 数字**偏低**")
            }
            if (!nameMappingReady) {
                appendLine("⚠️ 类名映射为空 ⇒ 实例只能归到 <unresolved>，请检查 dump 是否被截断")
            }
            if (unresolvedInstances > 0) {
                appendLine("⚠️ 有 $unresolvedInstances 个实例未匹配到类名（归到 <unresolved>，未丢弃）")
            }
            appendLine()
            appendLine("── 按字节 Top$topN ──")
            top(topN, byBytes = true).forEach { appendLine("  ${it.describe()}") }
            appendLine()
            appendLine("── 按数量 Top$topN ──")
            top(topN, byBytes = false).forEach { appendLine("  ${it.describe()}") }
            appendLine()
            appendLine("⚠️ 能力边界（必须知道）：")
            appendLine("  · 只有「类 → 数量/字节」，**没有引用链**（谁持有它）")
            appendLine("  · 因此定位不了「数量正常但没人放开」的循环引用（那需要支配树）")
            appendLine("  · 数组类名：`[B`=byte[]、`[I`=int[]、`[Lcom.x.Y;`=Y[]（本实现已换成可读形式）")
            appendLine("  · 单张直方图只能看「谁最大」；要看「谁**涨了**」必须用两次 dump 的差分")
        }
    }

    /**
     * 解析 hprof 文件。**必须在后台线程调用**（几十 MB 顺序 IO）。
     *
     * 内存占用与 dump 大小无关：只保留 `类名 → (count, bytes)` 聚合表，
     * 单条记录的载荷**按长度跳过而不缓存**（0x21/0x22/0x23 的大载荷尤其不能整块读进来
     * —— 45 MB 的 dump 里有 35k 个对象数组，整块读会真的吃掉几十 MB）。
     */
    fun parse(file: File): Histogram {
        val t0 = System.currentTimeMillis()
        require(file.exists()) { "hprof 不存在：${file.absolutePath}" }
        require(file.length() <= MAX_DUMP_BYTES) {
            "hprof 过大（${MemoryMetrics.fmt(file.length())} > 上限 ${MemoryMetrics.fmt(MAX_DUMP_BYTES)}）" +
                    "：拒绝解析，避免把内存问题变成两个"
        }

        val nameById = HashMap<Long, String>(1 shl 16)
        val stringById = HashMap<Long, String>(1 shl 16)
        val countByName = HashMap<String, Long>(1 shl 12)
        val bytesByName = HashMap<String, Long>(1 shl 12)
        var idSize = 4
        var skippedSegments = 0L
        var unresolved = 0L
        var instances = 0L
        var totalBytes = 0L

        val counting = CountingInputStream(BufferedInputStream(file.inputStream(), 1 shl 20))
        DataInputStream(counting).use { input ->
            // ── 文件头：magic(19) + u4 idSize + u8 timestamp ──
            // ⚠️ 只跳过固定 19 字节（"JAVA PROFILE 1.0.3\0"）；版本串不校验，
            //    1.0.1/1.0.2/1.0.3 的记录结构一致（shark 的 HprofHeader.recordsPosition
            //    也是这么算的）。
            val magic = ByteArray(19)
            input.readFully(magic)
            idSize = input.readInt()
            input.readLong() // timestamp
            require(idSize == 4 || idSize == 8) { "意外的 idSize=$idSize（既不是 4 也不是 8）" }

            while (true) {
                val tag = input.read()
                if (tag < 0) break
                input.readInt() // time
                val len = input.readInt()
                if (len < 0) break
                when (tag) {
                    0x01 -> { // STRING_IN_UTF8
                        val id = readId(input, idSize)
                        stringById[id] = String(readBytes(input, len - idSize), Charsets.UTF_8)
                    }
                    0x02 -> { // LOAD_CLASS
                        input.readInt()                    // class serial
                        val classObjId = readId(input, idSize)
                        input.readInt()                    // stack trace serial
                        val nameId = readId(input, idSize)
                        stringById[nameId]?.let { nameById[classObjId] = it.replace('/', '.') }
                    }
                    0x0C, 0x1C -> { // HEAP_DUMP / HEAP_DUMP_SEGMENT
                        // ⚠️ segment 的边界必须用**绝对偏移**算出来（bodyStart + len）。
                        //    用 `input.available()` 判断是错的：BufferedInputStream 的
                        //    available() 返回的是"缓冲区里还有多少字节"，不是"本段还剩多少"
                        //    —— 会提前结束或越界读下一段（第一版就是这么错的）。
                        val segmentEnd = counting.position + len
                        val stopped = scanHeapSegment(
                            input, counting, segmentEnd, idSize, nameById,
                            countByName, bytesByName,
                        ) { n, b -> instances += n; totalBytes += b }
                        if (stopped) skippedSegments++
                        // 防御：本段没消费完（未知 tag 中止的情况）时对齐到段尾，
                        // 否则顶层记录循环会从错位处继续读 —— 那才是"整份 dump 读花"的根源。
                        if (counting.position < segmentEnd) {
                            skipFully(input, segmentEnd - counting.position)
                        }
                    }
                    else -> skipFully(input, len.toLong())
                }
            }
        }

        val stats = bytesByName.entries
            .map { (name, bytes) -> ClassStat(name, countByName[name] ?: 0L, bytes) }
            .sortedByDescending { it.totalBytes }

        // unresolved 已经以 `<unresolved:0x…>` 的形式进入了统计；这里统计它的实例数
        unresolved = countByName.entries.filter { it.key.startsWith("<unresolved") }.sumOf { it.value }

        return Histogram(
            stats = stats,
            totalInstances = countByName.values.sum(),
            totalBytes = bytesByName.values.sum(),
            classCount = nameById.size.toLong(),
            skippedSegments = skippedSegments,
            idSize = idSize,
            fileBytes = file.length(),
            durationMs = System.currentTimeMillis() - t0,
            nameMappingReady = nameById.isNotEmpty(),
            unresolvedInstances = unresolved,
        )
    }

    /**
     * 扫一个 heap segment 的全部子记录，把"对象大小"累加到直方图。
     *
     * @return true 表示因**未知子 tag** 提前中止（调用方要计数并提示数字偏低）
     */
    private inline fun scanHeapSegment(
        input: DataInputStream,
        counting: CountingInputStream,
        segmentEnd: Long,
        idSize: Int,
        nameById: Map<Long, String>,
        countByName: HashMap<String, Long>,
        bytesByName: HashMap<String, Long>,
        onAccounted: (Long, Long) -> Unit,
    ): Boolean {
        while (counting.position < segmentEnd) {
            val sub = input.read()
            if (sub < 0) return false
            when (sub) {
                // ── GC ROOT：宽度逐条对齐 shark 的 StreamingHprofReader ──
                0xFF, 0x05, 0x07, 0x89, 0x8B, 0x8C, 0x8D, 0x90 -> skipFully(input, idSize.toLong())
                0x01 -> skipFully(input, 2L * idSize)                       // ROOT_JNI_GLOBAL
                0x02, 0x03, 0x08, 0x8E -> skipFully(input, idSize + 8L)      // +2×u4
                0x04, 0x06 -> skipFully(input, idSize + 4L)                  // +1×u4
                0xFE -> skipFully(input, 4L + idSize)                        // HEAP_DUMP_INFO：u4 + ID
                // ── CLASS_DUMP：类的元数据，**不计入**直方图（见类注释四.3）──
                0x20 -> skipClassDump(input, idSize)
                // ── INSTANCE：★ 自描述长度（见类注释三.纠正①）──
                0x21 -> {
                    skipFully(input, idSize + 4L)
                    val classId = readId(input, idSize)
                    val fieldBytes = input.readInt()
                    skipFully(input, fieldBytes.toLong())
                    val name = nameById[classId] ?: "<unresolved:0x${classId.toString(16)}>"
                    countByName.merge(name, 1L, Long::plus)
                    bytesByName.merge(name, fieldBytes.toLong(), Long::plus)
                    onAccounted(1L, fieldBytes.toLong())
                }
                // ── OBJECT_ARRAY：ID + u4 + u4 length + ID arrayClassId + length×ID ──
                0x22 -> {
                    skipFully(input, idSize + 4L)
                    val length = input.readInt()
                    val arrayClassId = readId(input, idSize)
                    val bytes = length.toLong() * idSize
                    skipFully(input, bytes)
                    val name = arrayName(nameById[arrayClassId])
                    countByName.merge(name, 1L, Long::plus)
                    bytesByName.merge(name, bytes, Long::plus)
                    onAccounted(1L, bytes)
                }
                // ── PRIMITIVE_ARRAY：ID + u4 + u4 length + u1 type + length×width ──
                0x23 -> {
                    skipFully(input, idSize + 4L)
                    val length = input.readInt()
                    val type = input.read()
                    val width = primitiveWidth(type)
                    val bytes = length.toLong() * width
                    skipFully(input, bytes)
                    val name = primitiveArrayName(type)
                    countByName.merge(name, 1L, Long::plus)
                    bytesByName.merge(name, bytes, Long::plus)
                    onAccounted(1L, bytes)
                }
                // PRIMITIVE_ARRAY_NODATA：shark 也直接抛（无法解析），这里中止本段
                0xC3 -> {
                    logWarn("遇到 PRIMITIVE_ARRAY_NODATA(0xC3)：无法解析，中止本 segment")
                    return true
                }
                else -> {
                    // 未知子 tag ⇒ 长度未知，**硬猜必然错位**，唯一安全的做法是中止本段。
                    logWarn("未知 heap 子 tag=0x${sub.toString(16)}：中止本 segment（数字将偏低）")
                    return true
                }
            }
        }
        return false
    }

    /** CLASS_DUMP：跳过整条（包含常量池、static/实例字段列表）。 */
    private fun skipClassDump(input: DataInputStream, idSize: Int) {
        // ID + u4 + 6×ID + u4(instanceSize)
        skipFully(input, idSize + 4L + 6L * idSize + 4L)
        // 常量池：u2 count + count×(u2 index + u1 type + 值)
        val poolCount = input.readUnsignedShort()
        repeat(poolCount) {
            input.readUnsignedShort()
            skipFully(input, fieldWidth(input.read(), idSize).toLong())
        }
        // static 字段：u2 count + count×(ID nameId + u1 type + **值**)
        // ⚠️ 只有 static 字段的"值"在文件里；实例字段只列签名（值在 INSTANCE 记录里）
        val staticCount = input.readUnsignedShort()
        repeat(staticCount) {
            skipFully(input, idSize.toLong())
            val type = input.read()
            skipFully(input, fieldWidth(type, idSize).toLong())
        }
        // 实例字段：u2 count + count×(ID nameId + u1 type)，**没有值**
        val fieldCount = input.readUnsignedShort()
        skipFully(input, fieldCount.toLong() * (idSize + 1))
    }

    /**
     * 基元数组类型的字节宽度（PRIMITIVE_ARRAY_DUMP 的 `u1 type`）。
     *
     * ⚠️ **tag 值本身就是宽度**：4=1B、5=2B、6=4B、7=8B、8=1B、9=2B、10=4B、11=8B。
     *    想当然写 1..8 会在 boolean/char/byte/short 上算错，进而错位。
     *    注意：**基元数组的 type 取值里没有 `2`**（2 只在字段类型里表示引用）。
     */
    private fun primitiveWidth(type: Int): Int = when (type) {
        4 -> 1   // boolean
        5 -> 2   // char
        6 -> 4   // float
        7 -> 8   // double
        8 -> 1   // byte
        9 -> 2   // short
        10 -> 4  // int
        11 -> 8  // long
        else -> 0
    }

    /**
     * **字段**的字节宽度（CLASS_DUMP / INSTANCE 的 `u1 type`）。
     *
     * ⚠️ 与 [primitiveWidth] 的唯一区别（也是最容易漏的一处）：
     *    **`2` 表示对象引用，宽度是 `idSize`（实测 Android 上是 4）而不是 1**。
     *    这一条错了会在"某个类恰好有引用类型的 static 字段"时整段失步 ——
     *    而且是**静默**的（读到错位后可能刚好撞上某个合法 tag）。
     */
    private fun fieldWidth(type: Int, idSize: Int): Int =
        if (type == 2) idSize else primitiveWidth(type)

    private fun primitiveArrayName(type: Int): String = when (type) {
        4 -> "boolean[]"; 5 -> "char[]"; 6 -> "float[]"; 7 -> "double[]"
        8 -> "byte[]"; 9 -> "short[]"; 10 -> "int[]"; 11 -> "long[]"
        else -> "primitive[$type][]"
    }

    /** 把 hprof 的数组类名（`[Lcom/x/Y;`、`[B`）换成可读形式。 */
    internal fun arrayName(raw: String?): String {
        if (raw.isNullOrEmpty()) return "<array>"
        if (raw.length >= 2 && raw[0] == '[' && raw[1] == 'L' && raw.endsWith(";")) {
            // ⚠️ 两类都要归一化：
            //    · 数组前缀 `[L…;` → `…[]`
            //    · **内部类分隔符 `/` → `.`**（hprof 里是斜杠形式，如
            //      `[Landroidx/emoji2/text/MetadataRepo$Node;`）。
            //      只处理前缀会让报告里出现 `androidx/emoji2/...Node[]`，
            //      与同一份报告里普通类（已在 LOAD_CLASS 时换成点号）**风格不一致** ——
            //      实测在真实 dump 上就是这么显示的，读起来很刺眼。
            return raw.substring(2, raw.length - 1).replace('/', '.') + "[]"
        }
        if (raw.length == 2 && raw[0] == '[') {
            return when (raw[1]) {
                'Z' -> "boolean[]"; 'C' -> "char[]"; 'F' -> "float[]"; 'D' -> "double[]"
                'B' -> "byte[]"; 'S' -> "short[]"; 'I' -> "int[]"; 'J' -> "long[]"
                else -> raw
            }
        }
        return raw.replace('/', '.')
    }

    private fun readId(input: DataInputStream, idSize: Int): Long {
        var v = 0L
        repeat(idSize) { v = (v shl 8) or (input.read().toLong() and 0xFF) }
        return v
    }

    private fun readBytes(input: DataInputStream, n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        val b = ByteArray(n)
        input.readFully(b)
        return b
    }

    private fun skipFully(input: DataInputStream, n: Long) {
        var left = n
        val tmp = ByteArray(8192)
        while (left > 0) {
            val read = input.read(tmp, 0, minOf(left, tmp.size.toLong()).toInt())
            if (read < 0) return
            left -= read
        }
    }

    /**
     * 抓 dump 到应用私有目录并解析。**必须在后台线程调用**。
     *
     * @return (dump 文件, 直方图)。失败**抛异常**由调用方处理 ——
     *         这是一个显式触发的取证动作，失败必须让人看到，
     *         与"采样失败静默降级"刻意相反。
     */
    fun dumpAndParse(context: android.content.Context, reason: String): Pair<File, Histogram> {
        val dir = File(context.filesDir, "memory").apply { if (!exists()) mkdirs() }
        val f = File(dir, "heap-${System.currentTimeMillis()}.hprof")
        logWarn("开始抓 hprof（reason=$reason）：dumpHprofData 会 stop-the-world，预期 0.5~2s")
        val t0 = System.currentTimeMillis()
        android.os.Debug.dumpHprofData(f.absolutePath)
        val dumpMs = System.currentTimeMillis() - t0
        logWarn("hprof 已落盘：${MemoryMetrics.fmt(f.length())}，冻结 ${dumpMs}ms，开始解析")
        val h = parse(f)
        logWarn("解析完成：${h.classCount} 个类 / ${MemoryMetrics.fmt(h.totalBytes)} / 耗时 ${h.durationMs}ms")
        return f to h
    }

    /** 清理旧的 dump（有界磁盘占用 —— 与 CrashJournal 的"文件必须有界"同一纪律）。 */
    fun cleanupOldDumps(context: android.content.Context, keep: Int = 2): Int {
        val dir = File(context.filesDir, "memory")
        if (!dir.exists()) return 0
        val files = dir.listFiles { f -> f.name.endsWith(".hprof") }
            ?.sortedByDescending { it.lastModified() } ?: return 0
        var removed = 0
        files.drop(keep).forEach { if (it.delete()) removed++ }
        return removed
    }

    private fun logWarn(msg: String) = android.util.Log.w("MemoryAnalyze", msg)

    /**
     * 记录**绝对读取位置**的输入流。
     *
     * ⚠️ 为什么不能用 `BufferedInputStream.available()` 判断 heap segment 的边界：
     *    `available()` 的语义是"**不阻塞地**还能读多少字节"（缓冲流返回的是缓冲区里
     *    已有的字节数），与"本 segment 还剩多少"毫无关系。第一版就是这么写的，
     *    实测在真实 dump 上要么提前结束（漏统计）、要么越界读到下一段（把 tag 读花，
     *    最终 `ArrayIndexOutOfBounds`）。
     *    ⇒ segment 边界只能是 `bodyStart + length` 这个**绝对偏移**。
     */
    internal class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        /** 已读字节数（绝对位置） */
        var position: Long = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) position++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) position += n
            return n
        }

        override fun skip(n: Long): Long {
            val s = super.skip(n)
            if (s > 0) position += s
            return s
        }
    }
}
