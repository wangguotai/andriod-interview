package com.interview.vmp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.interview.vmp.VmpBridge
import com.interview.vmp.databinding.ActivityVmpLabBinding
import java.util.concurrent.Executors
import kotlin.system.measureNanoTime

/**
 * Time: 2026/10/6
 * Author: wgt
 * Description: VMP 加固实验台 —— 把「加固做了什么」与「代价是多少」变成可点的按钮。
 *
 * ─── 四个动作，对应四个必答题 ───
 *
 * 1. **加固状态**：「你到底保护了什么？」→ 打印每个程序的密文长度、头部完好性、
 *    VM 语义自检结果。全部是**可核对的事实**，不是宣传词。
 * 2. **逐位对拍**：「加固后还算得对吗？」→ 三个算子 × 多个尺寸，VM 与原生逐字节比。
 *    这是本 Lab 的验收红线：**加固后算错比不加固更糟**。
 * 3. **A/B 对照**：「两条路径处理同一张图，看起来一样吗？」→ 在界面上并排显示
 *    原生 vs VM 的结果位图，并给出平均绝对误差。
 * 4. **跑基准**：「代价是多少？」→ 同机、同输入、同一份 `.so` 的两条路径，
 *    暖机 + 多次取中位数。数字之外的取证（触达页数、命中率）一并打印。
 *
 * ─── 线程纪律 ───
 *
 * 基准与对拍都可能跑几十到几百毫秒到秒级，放主线程必 ANR。统一丢到单线程
 * executor，结果回主线程刷 UI。**不新建裸线程** —— 与仓库其它 Lab 一致的做法；
 * 这里的 executor 是「本页面的工作线程」，命名清晰便于线程快照归因。
 * 注意：native 侧用 `thread_local!` 存 VM 执行器，所以**所有 native 调用都
 * 必须落在同一个线程上**才能复用执行器（否则每次都新建 VM，基准会失真）。
 * 单线程 executor 天然保证这一点 —— 这不只是线程纪律，也是**测量前提**。
 */
class VmpLabActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVmpLabBinding

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "vmp-lab-worker") }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val log = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVmpLabBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.vmpStatus.text = "${VmpBridge.describe()}\n${summaryLine()}"

        binding.vmpBtnStatus.setOnClickListener { runOnWorker("加固状态") { printHardeningStatus() } }
        binding.vmpBtnGolden.setOnClickListener { runOnWorker("逐位对拍") { goldenCrossCheck() } }
        binding.vmpBtnAb.setOnClickListener { runOnWorker("A/B 对照") { abSample() } }
        binding.vmpBtnBench.setOnClickListener { runOnWorker("基准") { benchmark() } }

        appendLine("VMP 加固 Lab 就绪。")
        appendLine("ABI=${VmpBridge.EXPECTED_ABI_VERSION} available=${VmpBridge.available}")
        appendLine("四个按钮：状态 / 对拍 / A-B 对照 / 基准。证据只留在本页，切走即失去上下文。")
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
        // 清掉本线程的 direct buffer 池：这些缓冲不在 Java 堆上，
        // 留着会一直占住 native 内存直到 Cleaner 跑（不确定何时）。
        VmpBridge.clearPool()
    }

    // ───────────────────────── 状态 ─────────────────────────

    private fun summaryLine(): String {
        if (!VmpBridge.available) return "native 不可用（本 Lab 只编 arm64-v8a；x86 模拟器上属预期）"
        val ok = VmpBridge.selftest()
        return "VM 自检=${if (ok) "PASS" else "FAIL"} 程序头完好=${VmpBridge.programsIntact()}"
    }

    private fun printHardeningStatus() {
        appendLine("[加固状态]")
        appendLine("  ${VmpBridge.describe()}")
        appendLine("  ${VmpBridge.hardeningStatus()}")
        appendLine("  VM 自检（算术语义 vs ISA 规范）: ${if (VmpBridge.selftest()) "PASS" else "FAIL"}")
        appendLine("  程序容器头完好: ${VmpBridge.programsIntact()}")
        appendLine("  ↳ 说明：程序 = 明文头(20B) + ChaCha20 密文载荷。")
        appendLine("     头里只有 magic/版本/长度/校验和 —— 反编译者能看见「这里有个程序」，")
        appendLine("     但看不见任何一条指令。这是**刻意的取舍**，不是疏漏。")
    }

    // ───────────────────────── 逐位对拍 ─────────────────────────

    private fun goldenCrossCheck() {
        appendLine("[逐位对拍] VM 加密字节码 vs 原生机器码（同一份 .so）")
        var allOk = true
        for (case in GOLDEN_CASES) {
            val src = makeImage(case.w, case.h)
            val srcBuf = VmpBridge.acquire(case.w * case.h * 4)
            VmpBridge.readPixels(src, srcBuf)

            // dominant：返回 int，直接比
            val dv = VmpBridge.dominantBytes(srcBuf, case.w, case.h, VmpBridge.Path.VM)
            val dn = VmpBridge.dominantBytes(srcBuf, case.w, case.h, VmpBridge.Path.NATIVE)
            val okD = dv.color == dn.color && dv.color >= 0
            allOk = allOk && okD
            appendLine(
                "  dominant  ${case.w}x${case.h}  vm=#%06x native=#%06x  %s".format(
                    dv.color and 0xFFFFFF, dn.color and 0xFFFFFF, if (okD) "OK" else "MISMATCH",
                ),
            )

            // downscale：逐字节比
            val dw = case.w / 2
            val dh = case.h / 2
            val outV = VmpBridge.acquire(dw * dh * 4, slot = 1)
            val outN = VmpBridge.acquire(dw * dh * 4, slot = 2)
            val rv = VmpBridge.downscaleBytes(srcBuf, case.w, case.h, outV, dw, dh, VmpBridge.Path.VM)
            val rn = VmpBridge.downscaleBytes(srcBuf, case.w, case.h, outN, dw, dh, VmpBridge.Path.NATIVE)
            val bv = VmpBridge.snapshot(outV, dw * dh * 4)
            val bn = VmpBridge.snapshot(outN, dw * dh * 4)
            val diffD = VmpBridge.firstByteDiff(bv, bn)
            val okDown = rv.ok && rn.ok && diffD < 0
            allOk = allOk && okDown
            appendLine("  downscale ${case.w}x${case.h}→${dw}x$dh  vm_rc=${rv.rc} native_rc=${rn.rc}  " + if (okDown) {
                "逐字节一致（${bv.size}B）"
            } else {
                "首字节差 @ $diffD（vm=${bv.getOrNull(diffD)} native=${bn.getOrNull(diffD)}）"
            })

            // blur：逐字节比
            val outV2 = VmpBridge.acquire(case.w * case.h * 4, slot = 3)
            val outN2 = VmpBridge.acquire(case.w * case.h * 4, slot = 4)
            val radius = case.radius
            val brv = VmpBridge.blurBytes(srcBuf, case.w, case.h, outV2, radius, VmpBridge.Path.VM)
            val brn = VmpBridge.blurBytes(srcBuf, case.w, case.h, outN2, radius, VmpBridge.Path.NATIVE)
            val bbv = VmpBridge.snapshot(outV2, case.w * case.h * 4)
            val bbn = VmpBridge.snapshot(outN2, case.w * case.h * 4)
            val diffB = VmpBridge.firstByteDiff(bbv, bbn)
            val okBlur = brv.ok && brn.ok && diffB < 0
            allOk = allOk && okBlur
            appendLine("  blur      ${case.w}x${case.h} r=$radius  " + if (okBlur) {
                "逐字节一致（${bbv.size}B, VM 解密 ${brv.fetchPages} 页 / 命中 ${brv.cacheHits}）"
            } else {
                "首字节差 @ $diffB"
            })
        }
        appendLine(if (allOk) "  ⇒ 结论：三个算子全部逐位一致（这是集成的前提，不是目标）" else "  ⇒ 结论：存在不一致，**必须先修**再谈性能")
    }

    // ───────────────────────── A/B 对照 ─────────────────────────

    private fun abSample() {
        appendLine("[A/B 对照] 同一张图，两条路径的结果与差异")
        val bmp = makeImage(256, 192)
        for (radius in intArrayOf(0, 2, 6)) {
            val (vmBmp, vmOut) = VmpBridge.blur(bmp, radius, VmpBridge.Path.VM)
            val (naBmp, naOut) = VmpBridge.blur(bmp, radius, VmpBridge.Path.NATIVE)
            if (vmBmp == null || naBmp == null) {
                appendLine("  blur r=$radius 失败：vm_rc=${vmOut.rc} native_rc=${naOut.rc}")
                continue
            }
            val a = bitmapBytes(vmBmp)
            val b = bitmapBytes(naBmp)
            val diff = VmpBridge.firstByteDiff(a, b)
            val mae = VmpBridge.meanAbsDiff(a, b)
            appendLine(
                "  blur r=$radius  逐位差=${if (diff < 0) "无" else "@$diff"}  MAE=%.4f  VM(解密%s页/命中%s) path=%s"
                    .format(mae, vmOut.fetchPages, vmOut.cacheHits, vmOut.path),
            )
        }
        val (dsVm, dsOut) = VmpBridge.downscale(bmp, 64, 48, VmpBridge.Path.VM)
        val (dsNa, dsNaOut) = VmpBridge.downscale(bmp, 64, 48, VmpBridge.Path.NATIVE)
        if (dsVm != null && dsNa != null) {
            val diff = VmpBridge.firstByteDiff(bitmapBytes(dsVm), bitmapBytes(dsNa))
            appendLine("  downscale 256x192→64x48  逐位差=${if (diff < 0) "无" else "@$diff"}  VM(解密${dsOut.fetchPages}页/命中${dsOut.cacheHits})")
            appendLine("  native 路径 rc=${dsNaOut.rc}（对照物，不加固）")
        }
    }

    // ───────────────────────── 基准 ─────────────────────────

    /**
     * 简单基准：**暖机 + 多次取中位数**。
     *
     * 纪律（沿用 `NOTES-rust-pipeline.md` 的方法论）：
     *   - **中位数而不是均值**：GC 停顿与 CPU 调频会制造离群点，均值会被单次 STW 拉偏；
     *   - **必须暖机**：JNI 首次调用、页缓存预热、分支预测都要先跑掉；
     *   - **同一线程**：native 的 VM 执行器是 thread_local，跨线程会重新分配。
     *
     * ⚠️ 这是**模拟器/真机都能跑但对结论有区别**的一类测量：本页打印的绝对毫秒数
     * 只用于「两条路径的相对关系」，跨设备的绝对值不可比。详见 NOTES 的诚实边界一节。
     */
    private fun benchmark() {
        appendLine("[基准] VM vs 原生（同机 / 同输入 / 同一份 .so；暖机 5 次、计时 15 次取中位数）")
        val bmp = makeImage(256, 192)
        val srcBytes = bitmapBytes(bmp)
        val srcBuf = VmpBridge.acquire(srcBytes.size)
        srcBuf.put(srcBytes)
        srcBuf.rewind()

        // dominant（返回 int，无输出缓冲）
        bench("dominant 256x192") { path ->
            VmpBridge.dominantBytes(srcBuf, 256, 192, path)
        }

        // downscale
        val dw = 64; val dh = 48
        val dstBuf = VmpBridge.acquire(dw * dh * 4, slot = 1)
        bench("downscale 256x192→64x48") { path ->
            VmpBridge.downscaleBytes(srcBuf, 256, 192, dstBuf, dw, dh, path)
        }

        // blur
        val blurOut = VmpBridge.acquire(srcBytes.size, slot = 2)
        bench("blur 256x192 r=2") { path ->
            VmpBridge.blurBytes(srcBuf, 256, 192, blurOut, 2, path)
        }

        val lastV = lastVmOutcome
        if (lastV != null) {
            appendLine("  取证：最后一次 VM 调用解密 ${lastV.fetchPages} 页、命中 ${lastV.cacheHits} 次" +
                "（命中率 %.2f%%)——证明不是「启动时全解密」".format(lastV.hitRate * 100))
        }
    }

    private var lastVmOutcome: VmpBridge.Outcome? = null

    private inline fun bench(label: String, call: (VmpBridge.Path) -> VmpBridge.Outcome) {
        val warmup = 5
        val iters = 15
        // 两次测量共用同一个 lambda，保证「唯一的差别是 path」
        for (path in listOf(VmpBridge.Path.NATIVE, VmpBridge.Path.VM)) {
            repeat(warmup) { call(path) }
            val times = LongArray(iters)
            for (i in 0 until iters) {
                times[i] = measureNanoTime { call(path) }
            }
            times.sort()
            val medianUs = times[iters / 2] / 1000.0
            val minUs = times[0] / 1000.0
            val p90Us = times[(iters * 9) / 10] / 1000.0
            if (path == VmpBridge.Path.VM) lastVmOutcome = call(path)
            appendLine("  %-28s %-6s median=%.1fus min=%.1fus p90=%.1fus".format(label, path, medianUs, minUs, p90Us))
        }
        appendLine("  ── 上面两行同一 label 的比值就是「加固代价」；绝对值跨设备不可比。")
    }

    // ───────────────────────── 工具 ─────────────────────────

    private fun runOnWorker(tag: String, block: () -> Unit) {
        binding.vmpStatus.text = "${VmpBridge.describe()}\n正在执行：$tag"
        worker.execute {
            val t = try {
                block()
                null
            } catch (e: Throwable) {
                e
            }
            main.post {
                if (t != null) appendLine("$tag 失败：${t.javaClass.simpleName}: ${t.message}")
                render()
                binding.vmpStatus.text = "${VmpBridge.describe()}\n${summaryLine()}"
            }
        }
    }

    private fun appendLine(s: String) {
        log.append(s).append('\n')
    }

    private fun render() {
        binding.vmpLog.text = log.toString()
        binding.vmpLogScroll.post { binding.vmpLogScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 造一张有梯度的测试图：纯色图会让很多 bug「看起来也对」，梯度能暴露边界问题。 */
    private fun makeImage(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        p.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE),
            null, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return bmp
    }

    private fun bitmapBytes(bmp: Bitmap): ByteArray {
        val buf = VmpBridge.acquire(bmp.width * bmp.height * 4, slot = 9)
        VmpBridge.readPixels(bmp, buf)
        return VmpBridge.snapshot(buf, bmp.width * bmp.height * 4)
    }

    /** 对拍用例：覆盖常见缩略图量级与几个边界尺寸。 */
    private data class Case(val w: Int, val h: Int, val radius: Int)

    companion object {
        private val GOLDEN_CASES = listOf(
            Case(64, 64, 1),
            Case(128, 96, 3),
            Case(256, 192, 2),
            Case(33, 17, 2),
        )
    }
}
