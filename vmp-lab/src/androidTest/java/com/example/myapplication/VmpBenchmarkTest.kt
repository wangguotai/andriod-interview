package com.example.myapplication

import com.interview.vmp.VmpBridge
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.system.measureNanoTime

/**
 * Time: 2026/10/6
 * Author: wgt
 * Description: 「加固的代价是多少」—— 设备端基准。
 *
 * ─── 方法论（照抄不过时就别信数字）───
 *
 * 1. **同一个 `.so`、同一次运行、同一份输入**。原生对照刻意编在 vmp_android 里
 *    （`rust/vmp-android/src/exec.rs` 的 `native` 模块），所以两条路径的差异
 *    只来自「解释字节码」vs「直接跑机器码」，不含编译选项/库加载的差别。
 * 2. **暖机**：JNI 首次调用有符号解析、页缓存为空、分支预测未收敛。先跑掉再计时。
 * 3. **中位数**：GC 停顿、CPU 调频会制造离群点，均值会被单次 STW 拉偏。
 * 4. **单线程**：native 的 VM 执行器是 `thread_local`，跨线程会重新分配。
 * 5. **先出表、后断言**：测量结果**必须**先打印。断言写在打印之前，
 *    一旦某个比值超界就连数字都看不到 —— 那等于把「发现异常」变成了「失去数据」。
 *    本测试第一版就犯了这个错（dominant 517x 时整张表被吃掉）。
 *
 * ─── 结果怎么读 ───
 *
 * **比值**（VM/native）与**指令数**是可迁移的结论，绝对毫秒数不是。
 * 同一份代码在高端机与低端机上，native 部分的差距可能到 2 倍，
 * 而解释器额外开销的**占比**差异更大。因此本测试只断言方向与量级上界。
 */
class VmpBenchmarkTest {

    @Before
    fun requireNative() {
        assumeTrue("native 不可用（本 Lab 只编 arm64-v8a）", VmpBridge.available)
    }

    /**
     * ⚠️ 关于「长基准被冻结」的实测记录（不要删，这是踩过的坑）。
     *
     * 本测试原先尝试在 `@Before` 里用 `startActivitySync` 把被测 App 拉到前台，
     * 以防进程被系统的 `cached_apps_freezer` 冻结 —— **这条路走不通**：
     * `startActivitySync` 要等目标 Activity 的主线程进入 idle 才返回，
     * 在这个纯计算、无 UI 交互的场景里它直接 45 秒超时失败
     * （`Could not launch intent … Perhaps the main thread has not gone idle`）。
     *
     * 实测结论：整轮基准约 25 秒，期间进程会进入 `freezer` cgroup
     * （`/proc/<pid>/cgroup` 里的 `/perf/frozen`，线程状态 `D`、CPU 时间不涨）。
     * **可靠的做法是在跑测试之前先手动把 App 拉到前台**，见 NOTES 里的运行命令。
     * 诊断步骤也记在那里：`cat /proc/<pid>/cgroup` 一眼就能区分
     * 「被冻结」与「真死循环」—— 后者 CPU 时间会一直涨。
     */
    private data class Stat(
        val medianUs: Double,
        val minUs: Double,
        val p90Us: Double,
        val steps: Long,
    )

    /**
     * 暖机 + 计时。返回中位/最小/p90 与**最后一次**的指令数。
     *
     * 指令数与迭代次数无关（同一个输入 → 同一段字节码 → 同样的步数），
     * 所以取最后一次即可，不必求平均。
     */
    private fun measure(warmup: Int = 5, iters: Int = 21, call: () -> VmpBridge.Outcome): Stat {
        repeat(warmup) { call() }
        val us = LongArray(iters)
        var last: VmpBridge.Outcome? = null
        for (i in 0 until iters) {
            us[i] = measureNanoTime { last = call() } / 1000
        }
        us.sort()
        return Stat(
            medianUs = us[iters / 2].toDouble(),
            minUs = us[0].toDouble(),
            p90Us = us[(iters * 9) / 10].toDouble(),
            steps = last?.steps ?: -1L,
        )
    }

    @Test
    fun reportVmOverheadVersusNative() {
        // ── 输入准备（全部在计时循环之外）──
        val (w, h) = 256 to 192
        val src: ByteBuffer = bufOf(TestImages.synthRgba(w, h, 12345L), slot = 0)
        val (dw, dh) = 64 to 48
        val dsDst: ByteBuffer = VmpBridge.acquire(dw * dh * 4, slot = 1)
        val blDst: ByteBuffer = VmpBridge.acquire(w * h * 4, slot = 2)

        // ── 先把三组测量都做完，不做任何断言 ──
        val naDom = measure { VmpBridge.dominantBytes(src, w, h, VmpBridge.Path.NATIVE) }
        val vmDom = measure { VmpBridge.dominantBytes(src, w, h, VmpBridge.Path.VM) }
        val naDs = measure { VmpBridge.downscaleBytes(src, w, h, dsDst, dw, dh, VmpBridge.Path.NATIVE) }
        val vmDs = measure { VmpBridge.downscaleBytes(src, w, h, dsDst, dw, dh, VmpBridge.Path.VM) }
        val naBl = measure { VmpBridge.blurBytes(src, w, h, blDst, 2, VmpBridge.Path.NATIVE) }
        val vmBl = measure { VmpBridge.blurBytes(src, w, h, blDst, 2, VmpBridge.Path.VM) }

        // ── 页缓存取证 ──
        val proof = VmpBridge.downscaleBytes(src, w, h, dsDst, dw, dh, VmpBridge.Path.VM)

        // ── 先打印，再断言 ──
        val sb = StringBuilder()
        sb.appendLine("=== VMP 加固代价（同 .so / 同输入 / 暖机 5 次 / 计时 21 次取中位数）===")
        sb.appendLine("设备：${android.os.Build.MODEL}（${android.os.Build.SOC_MODEL}），ABI=${android.os.Build.SUPPORTED_ABIS.first()}")
        sb.appendLine()
        sb.appendLine("算子                                native中位    VM中位     比值    VM指令数   ns/指令   p90抖动")
        sb.appendLine("                              (us)        (us)              (条)              (us)")
        sb.appendLine(row("dominant ${w}x$h", naDom, vmDom))
        sb.appendLine(row("downscale ${w}x$h→${dw}x$dh", naDs, vmDs))
        sb.appendLine(row("blur ${w}x$h r=2", naBl, vmBl))
        sb.appendLine()
        sb.appendLine("页缓存取证（downscale ${w}x$h）：解密 ${proof.fetchPages} 页 / 命中 ${proof.cacheHits} 次" +
            "（命中率 %.2f%%）".format(proof.hitRate * 100))
        sb.appendLine("  ↳ 命中率高、解密页数是个位数 ⇒ 「惰性解密」的边际成本≈0，")
        sb.appendLine("     代价主要来自**解释执行本身**（每条指令一次 match 派发）。")
        sb.appendLine()
        sb.appendLine("读法：比值与指令数可迁移，绝对 us 跨设备不可比。")
        sb.appendLine("      ns/指令 ≈ 单条 VM 指令的解释开销（含取指、可选解密、match 派发、栈操作）。")

        println(sb.toString())

        // ── 断言：只钉「方向」与「量级上界」 ──
        // 上界取 1000x：它不用于评价性能好不好，只用于抓「灾难性回归」
        // （页缓存失效、每次调用重建 VM、误把 release 编成 debug 之类）。
        // 具体倍数不写成断言 —— 那会让测试在换台设备时变成噪音。
        assertRatioSane("dominant", naDom, vmDom)
        assertRatioSane("downscale", naDs, vmDs)
        assertRatioSane("blur", naBl, vmBl)
        assertTrue("VM 指令数应为正数", vmDom.steps > 0 && vmDs.steps > 0 && vmBl.steps > 0)
        assertTrue(
            "页缓存命中率应很高（实际 %.2f%%）—— 否则说明页窗口太小".format(proof.hitRate * 100),
            proof.hitRate > 0.9,
        )
    }

    private fun row(label: String, na: Stat, vm: Stat): String {
        val ratio = vm.medianUs / na.medianUs
        val nsPerInstr = if (vm.steps > 0) vm.medianUs * 1000.0 / vm.steps else Double.NaN
        val jitter = vm.p90Us / vm.medianUs
        return "%-28s %10.1f %10.1f %7.1fx %10d %8.2f %6.2fx".format(
            label, na.medianUs, vm.medianUs, ratio, vm.steps, nsPerInstr, jitter,
        )
    }

    /**
     * 上界取得很宽（5000x）是**有意的，也是被实测数据修正过的**。
     *
     * 第一版拍了个 8x，结果模拟器上实测 1062~1552x，测试直接红 —— 那是我对
     * 「栈机解释执行 + 每像素上百条指令」的成本估计错了，不是代码有问题。
     * 这条断言的用途**不是**「评价加固值不值」，而是「抓灾难性回归」：
     * 页缓存失效（实测会让 downscale 从 0.08s 退化到 46s）、每次调用重建 VM、
     * 误把 release 编成 debug。这些会让比值再跳一个数量级。
     * 把「性能好不好」写成断言，只会让测试在换设备时无意义地翻红。
     */
    private fun assertRatioSane(name: String, na: Stat, vm: Stat) {
        val ratio = vm.medianUs / na.medianUs
        assertTrue("$name: native 中位耗时应为正（实际 ${na.medianUs}）", na.medianUs > 0.0)
        assertTrue("$name: VM 应慢于 native（实际 ${"%.1f".format(ratio)}x）", ratio > 1.0)
        assertTrue(
            "$name: VM/native 比值 ${"%.1f".format(ratio)}x 超出 5000x —— " +
                "这通常意味着灾难性回归（页缓存失效 / 每次调用重建 VM / 误编成 debug），" +
                "而不是解释执行的固有开销",
            ratio < 5000.0,
        )
    }
}
