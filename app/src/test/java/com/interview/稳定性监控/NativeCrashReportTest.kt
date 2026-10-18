package com.interview.稳定性监控

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Time: 2026/10/16
 * Author: wgt
 * Description: `NativeCrashReport` 解析器的单测 + **格式契约的一致性检查**
 *
 * ══════════════════════════════════════════════════════════════════════
 * ⚠️ 样本必须用 `joinToString` 拼接，**不能用 Kotlin 的 raw string（`"""`）**
 * ══════════════════════════════════════════════════════════════════════
 *
 * 这是本文件第一版踩到的坑，值得写下来：raw string + `trimIndent()` 只处理**缩进**，
 * **不去掉行尾空格**。而样本里 `Name:` 这类字段在 `/proc` 里本来就是
 * 「冒号 + Tab + 值」（`Name:\tcom.example`）。我照抄成 `Name: com.example` 之后，
 * 只要任何一处留下行尾空格，`trimIndent()` 的公共缩进就会被"最短的非空行"带偏，
 * 结果是**整个样本的缩进都被多删一段** —— 断言看起来对，解析出来却是错的。
 *
 * ⇒ 样本一律用 `listOf(...).joinToString("\n")` 显式拼：每一行都是**精确**的
 *    字符串字面量（Tab 与行尾空格都可控），diff 里也能看清每一行改了什么。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 本文件解决的两个不同问题（别混在一起看）
 * ══════════════════════════════════════════════════════════════════════
 *
 * **A. 解析器对不对** —— 用**手写样本**核对"完整报告"与"被截断的报告"两种极端，
 *    以及各段缺失的情形。
 *
 * ⚠️ 为什么用手写样本而不是读真机报告：真机报告**不能进版本库**
 *    （含设备信息、路径、可能含业务字符串；而且每次都不一样，钉不住断言）。
 *
 * **B. 格式契约有没有漂移** —— [formatVersionMatchesNativeSource] 与
 *    [sectionHeadersMatchNativeSource] 直接**读 C++ 源码**核对。
 *
 * ⚠️ 这是本文件最重要的两个测试。C++ 侧和 Kotlin 侧**不可能互相 import**，
 *    "版本字符串 / 段名字面量"是两边唯一的锚点。若它漂移了
 *    （例如有人把 C++ 改成 v2 却忘了改 Kotlin），现象是
 *    **报告写出来了但一条都解析不到** —— 静默失败，debug 与 release 下**都静默**。
 *    所以只能靠测试挡。
 *
 *    这也是本仓库既有的一种做法：把"两处必须一致"变成"一处能在构建期查"。
 *    （同类：`tools/native-mem-symbolize.sh` 靠**格式**而不是配置与 memtrace 对齐。）
 */
class NativeCrashReportTest {

    // ══════════════════════════════════════════════════════════════════════
    // B. 格式契约一致性（最重要）
    // ══════════════════════════════════════════════════════════════════════

    private fun nativeSource(): File {
        val candidates = listOf(
            "src/main/cpp/native_crash_handler.cpp",
            "app/src/main/cpp/native_crash_handler.cpp",
            "../app/src/main/cpp/native_crash_handler.cpp",
        )
        // Gradle 的 testDebugUnitTest 默认以**模块目录**（app/）为 cwd，
        // 所以第一个候选通常就对；多给几个候选是为了换构建方式时不至于误红。
        // ⚠️ 都找不到时**失败**而不是跳过 —— 跳过等于这个保护静默失效。
        return candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error(
                "找不到 native_crash_handler.cpp（试过：$candidates）。" +
                        "这个测试**不能跳过** —— 跳过等于格式契约失去保护。"
            )
    }

    /** 直接读 C++ 源码，确认它写出的头部与 Kotlin 侧的常量一致 */
    @Test
    fun formatVersionMatchesNativeSource() {
        val src = nativeSource().readText()
        // C++ 侧的写法是 outStr("NCREPORT v1")
        val m = Regex("""outStr\("(NCREPORT v[0-9]+)"\)""").find(src)
        assertNotNull(
            "在 C++ 源码里找不到 outStr(\"NCREPORT v<N>\") —— 输出语句被改写了。" +
                    "请同步更新本测试与 NATIVE_CRASH_FORMAT.md。",
            m,
        )
        assertEquals(
            "❌ 格式版本漂移：C++ 侧输出 '${m!!.groupValues[1]}'，" +
                    "Kotlin 侧期望 '${NativeCrashReport.FORMAT_VERSION}'。\n" +
                    "  后果：报告写出来但**一条都解析不到**，且 debug/release 都静默。\n" +
                    "  修法：改 NATIVE_CRASH_FORMAT.md + FORMAT_VERSION + C++ 输出，三处一起。",
            NativeCrashReport.FORMAT_VERSION,
            m.groupValues[1],
        )
    }

    /**
     * 段落分隔符的一致性。
     *
     * 解析器靠 `--- ` 前缀切段、靠精确段名识别。段名少一个字符
     * （如 `--- MAPS--`）就会让该段被**整段静默丢弃** —— 不报错，只是少了字段。
     */
    @Test
    fun sectionHeadersMatchNativeSource() {
        val src = nativeSource().readText()
        // 解析器实际识别的段名（见 NativeCrashReport.parse 的 when 分支）
        listOf(
            "--- REGISTERS ---",
            "--- MAPS ---",
            "--- FD ---",
            "--- STATUS ---",
            "--- CMDLINE ---",
            "--- END ---",
        ).forEach { header ->
            assertTrue(
                "C++ 侧没有输出 '$header' 的字面量 —— 解析器会认不出来，" +
                        "该段会被**静默丢弃**（不报错，只是字段变少）。",
                src.contains("\"$header\""),
            )
        }
        // BACKTRACE 段标题是拼出来的（要带线程名与 tid），核对前缀即可
        assertTrue(
            "C++ 侧没有输出 '--- BACKTRACE ' 前缀",
            src.contains("\"--- BACKTRACE \""),
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // A. 解析器行为
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun parsesFullReport() {
        val r = NativeCrashReport.parse(fullSample(), recoveredAtMillis = 1_729_000_000_000L)
        assertNotNull("完整样本必须解析成功", r)
        r!!

        // ── 头部 ──
        assertEquals(12345, r.pid)
        assertEquals(12346, r.tid)
        assertEquals(11, r.signal)
        assertEquals("SIGSEGV", r.signalName)
        assertEquals(1, r.code)
        assertEquals("SEGV_MAPERR", r.codeName)
        assertEquals(0L, r.faultAddr)
        assertEquals("RenderThread", r.threadName)
        assertEquals(77, r.sdk)
        assertEquals(1_234_567L, r.uptimeMs)
        assertEquals("1.0", r.versionName)
        assertEquals("com.example.myapplication:remote", r.processName)
        assertTrue("样本含 --- END ---，必须判为完整", r.complete)

        // ── 寄存器 ──
        // ⚠️ 这里断言的形状来自实测核对过的 `struct sigcontext`：
        //    aarch64 是 regs[31]（x0..x30）+ 独立的 sp / pc / pstate。
        //    写成 regs[32] 会读到 sp 并少一个寄存器 —— 属于"看着对但是错的"。
        assertEquals(0L, r.registers["x0"])
        assertEquals(1L, r.registers["x1"])
        assertEquals(0xdeadbeefL, r.registers["x29"])
        assertTrue("sp 必须在（它不在 regs[31] 里，是单独字段）", r.registers.containsKey("sp"))
        assertTrue("pc 必须在", r.registers.containsKey("pc"))

        // ── 栈：两个线程，崩溃线程靠 tid 识别（不是靠名字）──
        assertEquals(2, r.backtraces.size)
        val crashed = r.crashedBacktrace
        assertNotNull("必须能识别出崩溃线程（tid 与头部 tid 相等）", crashed)
        assertEquals(2, crashed!!.frames.size)
        assertEquals("libnative_crash.so", crashed.frames[0].module)
        assertEquals(0x1a3cL, crashed.frames[0].offset)
        // 匿名映射帧的 module 必须是 null（而不是空串或 "(anonymous)"）
        assertNull("匿名映射的 module 应为 null", crashed.frames[1].module)

        // ── maps / fd / status / cmdline ──
        assertEquals(3, r.maps.size)
        // fault_addr=0 落在任何 VMA 之外 —— 这是**正确**结果，不是 bug
        assertNull("fault_addr=0 不该落在任何 VMA 里", r.faultVma)
        assertEquals(2, r.fdLines.size)
        assertTrue("status 段必须解析出来", r.statusLines.any { it.startsWith("Name:") })
        assertEquals("cmdline 是 NUL 分隔的，应取第一段", "com.example.myapplication:remote", r.cmdline)

        // ── 符号化 token（交给 native-mem-symbolize.sh 的格式）──
        assertEquals("libnative_crash.so+0x1a3c", crashed.frames[0].symbolToken())
    }

    /**
     * **最重要的一条行为测试**：报告被截断（缺 `--- END ---`）。
     *
     * 这是 native 侧的**真实常见情形**：报告写到一半，debuggerd 就把进程杀了。
     * 已解析的字段仍然可信（前面已经写进文件了），但必须**明确标为不完整** ——
     * 否则使用者会把一个截断的栈当成完整调用链，据此得出错误结论。
     */
    @Test
    fun detectsTruncatedReport() {
        val r = NativeCrashReport.parse(truncatedSample(), recoveredAtMillis = 1_729_000_000_000L)
        assertNotNull(r)
        r!!
        assertFalse("缺 --- END --- 必须判为不完整", r.complete)
        // 截断之前的内容仍要解析出来（"部分可信"的语义）
        assertEquals(11, r.signal)
        assertTrue("崩溃线程栈在截断之前，必须仍被解析到", r.crashedBacktrace != null)
        // describe() 必须把"不完整"显式说出来
        assertTrue(
            "describe() 必须显式警告报告不完整，否则截断的栈会被当成完整的",
            r.describe().contains("报告不完整"),
        )
    }

    /**
     * `fault_addr` 落在 VMA 内 / 落在 VMA 外，两种都要算对。
     *
     * 这是 native 归因里最有用的一步：同一个 `SEGV_MAPERR`，
     * 落在哪个区间（或不在任何区间）指向完全不同的 bug。
     */
    @Test
    fun locatesFaultAddressInMaps() {
        // ⚠️ 地址必须是 **48 位**（0x00007f0000001234），不能写成 0x0000007f00001234 ——
        //    后者只有 40 位（= 0x7f00001234），远小于 VMA 起点 0x7f0000000000，
        //    会落到所有 VMA 之外。真实 aarch64 用户态地址就是 48 位，
        //    这正是"样本必须照真实数据写、不能凭手感写"的实例。
        val inside = NativeCrashReport.parse(
            fullSample(faultAddr = "0x00007f0000001234"),
            recoveredAtMillis = 1L,
        )!!
        assertNotNull("地址在 VMA 内时必须能定位", inside.faultVma)
        assertEquals("/system/lib64/libc.so", inside.faultVma!!.path)
        assertTrue(inside.faultVma!!.contains(inside.faultAddr))

        // 0x1 不在任何 VMA 里（真实的野指针 / 已 munmap 的地址）
        val outside = NativeCrashReport.parse(
            fullSample(faultAddr = "0x0000000000000001"),
            recoveredAtMillis = 1L,
        )!!
        assertNull("越界的 fault_addr 不该被硬塞进某个 VMA", outside.faultVma)
    }

    /** 非本模块产出的文本，以及版本号不同的报告，都必须返回 null（不能抛、也不能解析出垃圾） */
    @Test
    fun rejectsForeignReport() {
        assertNull(
            "不是本模块产出的文本必须返回 null（启动路径上不能抛）",
            NativeCrashReport.parse("hello world\nsome other log", 1L),
        )
        assertNull(
            "版本号不同的报告必须返回 null（而不是尽力解析出垃圾）",
            NativeCrashReport.parse("NCREPORT v99\npid=1\n", 1L),
        )
    }

    /**
     * 各段的**缺失**必须被容忍，且不失真。
     *
     * ⚠️ 这里要验的不是"不抛异常"，而是**语义正确**：
     *    `--- FD ---` 缺失时，maps 段不能被后面的内容污染 ——
     *    这正是"按行状态机"而不是"按段落 split"的理由。
     */
    @Test
    fun toleratesMissingSections() {
        val sample = lines(
            "NCREPORT v1",
            "pid=999",
            "tid=999",
            "signal=6 SIGABRT",
            "code=-6 SI_TKILL",
            "fault_addr=0x0000000000000000",
            "thread_name=main",
            "abort_message=Lab: 主动 SIGABRT",
            "sdk=31",
            "android_release=12",
            "uptime_ms=100",
            "version_name=1.0",
            "version_code=1",
            "process_name=com.example.myapplication",
            "",
            "--- BACKTRACE main (tid=999) ---",
            "  #00 0x0000000000000abc libnative_crash.so+0xabc",
            "  #01 0x0000000000000def libnative_crash.so+0xdef",
            "",
            "--- MAPS ---",
            "7f0000000000-7f0000100000 r-xp 00000000 fd:00 1234 /system/lib64/libc.so",
            "",
            "--- END ---",
        )

        val r = NativeCrashReport.parse(sample, 1L)
        assertNotNull(r)
        r!!
        assertTrue(r.complete)
        assertEquals("只有一个线程栈", 1, r.backtraces.size)
        assertEquals("maps 只有一行（FD 段缺失不该污染它）", 1, r.maps.size)
        assertEquals("FD 段缺失 → 空列表（而不是 null/异常）", 0, r.fdLines.size)
        assertEquals(0, r.statusLines.size)
        // 主线程崩溃：tid == pid
        assertTrue("tid==pid 时该判为主线程", r.tid == r.pid)
        assertEquals("abort_message 必须带出来", "Lab: 主动 SIGABRT", r.abortMessage)
    }

    /**
     * `toEvent` 的映射语义 —— 这是本模块与既有上报管道的**接缝**。
     * 错在这里会让 native 崩溃在服务端被算进 Java 崩溃里
     * （服务端就聚合不出"原生崩溃占比"这个维度了）。
     */
    @Test
    fun mapsToCrashNativeEventKind() {
        val r = NativeCrashReport.parse(fullSample(), 1L)!!
        val e = NativeCrashReport.toEvent(r)
        assertEquals(
            "必须用 CRASH_NATIVE 而不是 CRASH —— 服务端靠 kind 聚合原生崩溃占比",
            StabilityReporter.Kind.CRASH_NATIVE,
            e.kind,
        )
        assertEquals(12345, e.pid)
        assertEquals("RenderThread", e.threadName)
        assertEquals("SIGSEGV/SEGV_MAPERR", e.name)
        // 崩溃线程 tid=12346 != pid=12345 → 不是主线程
        assertFalse("tid != pid 时必须判为非主线程", e.isMainThread)
        // 栈里必须有崩溃线程的帧，且是 module+offset 形态（便于复制去符号化）
        assertTrue("stack 里必须有崩溃线程的栈", e.stack.contains("native backtrace"))
        assertTrue("stack 里必须有 module+offset 帧", e.stack.contains("libnative_crash.so"))
        // detail 里必须带 fault_addr 的 VMA 归因结论
        assertTrue("detail 必须说明 fault_addr 的归属", e.detail.contains("VMA"))
    }

    /**
     * 时间戳换算：崩溃时刻 = 回捞时刻 - (回捞时 monotonic - 崩溃时 monotonic)。
     *
     * ⚠️ 这里直接测纯函数，而不是 `Report.estimatedEpochMillis`：宿主 JVM 上
     *    `SystemClock.uptimeMillis()` 被 mock 成 0（`isReturnDefaultValues = true`），
     *    那个 getter 在测试里必然算错 —— 而它在设备上是对的。
     *    这正是把算式抽成 `estimateEpoch(...)` 的理由（见其注释）。
     */
    @Test
    fun estimatesEpochFromUptime() {
        val recoveredAt = 1_729_000_000_000L
        val crashUptime = 1_000_000L   // 崩溃发生在开机后 1000s
        val nowUptime = 1_030_000L     // 回捞时是开机后 1030s → 差 30s
        assertEquals(
            "崩溃时刻应比回捞时刻早 30 秒（差 = 回捞uptime - 崩溃uptime）",
            recoveredAt - 30_000L,
            NativeCrashReport.estimateEpoch(recoveredAt, crashUptime, nowUptime),
        )

        // 方向性检查：绝不能算出"未来时间"
        val r = NativeCrashReport.parse(fullSample(), recoveredAtMillis = recoveredAt)!!
        assertTrue(
            "崩溃时刻必须早于回捞时刻（换算方向错了会得到未来时间）",
            NativeCrashReport.estimateEpoch(recoveredAt, r.uptimeMs, r.uptimeMs + 5_000L) < recoveredAt,
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // 样本（用 joinToString 拼，见文件头：raw string 不可控）
    // ══════════════════════════════════════════════════════════════════════

    private fun lines(vararg l: String) = l.joinToString("\n")

    /**
     * 完整报告：两个线程栈、寄存器、maps、fd、status、cmdline、END。
     *
     * @param faultAddr 可替换，用于测 VMA 定位（默认 0x0 = 空指针形态）
     */
    private fun fullSample(faultAddr: String = "0x0000000000000000") = lines(
        "NCREPORT v1",
        "pid=12345",
        "tid=12346",
        "signal=11 SIGSEGV",
        "code=1 SEGV_MAPERR",
        "fault_addr=$faultAddr",
        "sender_pid=0",
        "sender_uid=0",
        "thread_name=RenderThread",
        "abort_message=(无)",
        "sdk=77",
        "android_release=12",
        "uptime_ms=1234567",
        "version_name=1.0",
        "version_code=1",
        "process_name=com.example.myapplication:remote",
        "",
        "--- REGISTERS ---",
        "  x0 0x0000000000000000",
        "  x1 0x0000000000000001",
        "  x29 0x00000000deadbeef",
        "  x30 0x000000007f12345678",
        "  sp 0x000000007ff00000",
        "  pc 0x000000007f12345678",
        "  pstate 0x0000000060000000",
        "",
        "--- BACKTRACE RenderThread (tid=12346) ---",
        "  #00 0x0000007f12345678 libnative_crash.so+0x1a3c",
        "  #01 0x0000007f98765432 (anonymous)",
        "",
        "--- BACKTRACE main (tid=12345) ---",
        "  #00 0x0000007f11111111 libc.so+0x2222",
        "",
        "--- MAPS ---",
        "7f0000000000-7f0000100000 r-xp 00000000 fd:00 1234 /system/lib64/libc.so",
        "7f1000000000-7f1000100000 rw-p 00000000 00:00 0",
        "7f2000000000-7f2000200000 r-xp 00000000 fd:00 5678 /data/app/libnative_crash.so",
        "",
        "--- FD ---",
        "  0 -> /dev/null",
        "  12 -> /data/user/0/com.example.myapplication/files/x",
        "",
        "--- STATUS ---",
        // ⚠️ /proc 里是「冒号 + Tab」而不是「冒号 + 空格」，样本照实写
        "Name:\tcom.example.myapplication",
        "State:\tS (sleeping)",
        "Tgid:\t12345",
        "Threads:\t18",
        "",
        "--- CMDLINE ---",
        "com.example.myapplication:remote",
        "",
        "--- END ---",
    )

    /** 被截断的报告：写到 maps 就没了（进程在写完之前被 debuggerd 杀掉） */
    private fun truncatedSample() = lines(
        "NCREPORT v1",
        "pid=12345",
        "tid=12346",
        "signal=11 SIGSEGV",
        "code=1 SEGV_MAPERR",
        "fault_addr=0x0000000000000000",
        "thread_name=RenderThread",
        "abort_message=(无)",
        "sdk=77",
        "android_release=12",
        "uptime_ms=999",
        "version_name=1.0",
        "version_code=1",
        "process_name=com.example.myapplication",
        "",
        "--- BACKTRACE RenderThread (tid=12346) ---",
        "  #00 0x0000007f12345678 libnative_crash.so+0x1a3c",
        "",
        "--- MAPS ---",
        "7f0000000000-7f0000100000 r-xp 00000000 fd:00 1234 /system/lib64/libc.so",
        // 刻意没有 --- END ---
    )
}
