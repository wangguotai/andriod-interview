package com.interview.vmp

import android.graphics.Bitmap
import android.util.Log
import com.interview.vmp.nativebridge.VmpNative
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Time: 2026/10/6
 * Author: wgt
 * Description: 加固路径的统一收口 —— 上层只认「算子 + 走哪条路径」，不碰 JNI。
 *
 * ─── 两层 API，两种用途 ───
 *
 * - **字节层**（`*Bytes`）：直接收发 `ByteBuffer`，是**金标准对拍唯一可用的层**。
 *   因为「逐位一致」的定义就是字节流相等，它必须能拿到未被 Bitmap 包装的原始字节。
 * - **位图层**（`dominant` / `downscale` / `blur`）：面向 UI 与业务，负责
 *   Bitmap ↔ RGBA 字节流的转换与缓冲池管理。
 *
 * 把两层分开，是为了避免「为了做对拍而被迫走一遍 Bitmap」——`Bitmap.sameAs` 的
 * 比较口径（是否含 alpha、内部是否走 native）会掩盖差异，而我们要的是逐字节。
 *
 * ─── 两条路径，一个契约 ───
 *
 * [Path.VM] 走加密字节码的虚拟机；[Path.NATIVE] 走未加固的机器码。
 * 两者结果必须**逐位一致**。这不是「最好一致」，而是本 Lab 的验收红线：
 * 加固后算错，比不加固更糟。
 *
 * ─── 像素布局约定（与 app 侧 imagepipeline 完全一致）───
 *
 * 统一按 **RGBA8888 字节序**（每像素 4 字节，R,G,B,A），与
 * `Bitmap.copyPixelsToBuffer` 对 `Config.ARGB_8888` 的语义一致。
 *
 * ⚠️ `ARGB_8888` 是按**整型数值**（0xAARRGGBB）命名的，**不代表内存字节顺序**。
 * 若改用 `IntArray` + `getPixels()` 再按 `u32` 传给 native，会 R/B 互换、全图偏色 ——
 * 不崩、只是颜色不对，是 native 图像最阴的坑之一。app 侧用 `probeLayout` 把它变成
 * 可断言的证据；本 Lab 直接沿用同一套 `ByteBuffer` 通路，不引入第二套布局。
 *
 * ─── 缓冲复用 ───
 *
 * direct buffer 的分配要走系统调用、回收依赖 Cleaner，在基准循环里会把分配成本
 * 摊进耗时（于是测的是分配器而不是算法）。这里按 `(slot, 字节数)` 用
 * `ThreadLocal` 缓存 —— 与 `ImagePipelineBridge.RgbaBufferPool` 同一做法与理由。
 * `slot` 维度是必需的：blur / downscale 的输入输出尺寸可能相同，
 * 若只按字节数缓存，第二次 acquire 会拿回**同一块** buffer，破坏 native 侧
 * 「输入输出不别名」的契约，结果是自读自写、静默算错。
 */
object VmpBridge {

    private const val TAG = "VmpLab"

    /** 与 `rust/vmp-android/src/exec.rs` 的 `ABI_VERSION` 对齐。 */
    const val EXPECTED_ABI_VERSION = 1

    /** 走哪条路径。 */
    enum class Path { VM, NATIVE }

    /** 一次算子的结果：错误码 + 走 VM 时的取证数据。 */
    class Outcome(
        val path: Path,
        /** native 返回的错误码；`0` 或 `>=0`（dominant）表示成功。 */
        val rc: Int,
        /** dominant 专用：`0xRRGGBB`；其它算子为 -1。 */
        val color: Int = -1,
        /** 走 VM 时是本次**未命中**（真正解密）的页数；走 NATIVE 时恒为 -1。 */
        val fetchPages: Int = -1,
        /** 走 VM 时是本次命中页缓存的次数；走 NATIVE 时恒为 -1。 */
        val cacheHits: Int = -1,
        /**
         * 走 VM 时是本次执行的指令条数；走 NATIVE 时恒为 -1（它没有「指令」这个概念）。
         *
         * 这是「加固代价」里**唯一与设备无关**的量：毫秒数换个机器就变，
         * 指令数不会。它也是唯一能拆开「字节码太长」与「解释器太慢」的指标。
         */
        val steps: Long = -1L,
    ) {
        val ok: Boolean get() = rc >= 0
        /** VM 页缓存命中率。分母为 0（无取指）时记 0。 */
        val hitRate: Double
            get() {
                val total = (fetchPages.coerceAtLeast(0) + cacheHits.coerceAtLeast(0))
                return if (total == 0) 0.0 else cacheHits.toDouble() / total
            }
    }

    /**
     * native 是否可用（库加载成功 **且** ABI 匹配）。
     *
     * ABI 不匹配时必须显式判 false：APK 里残留旧 `.so` 时，宁可「明确降级」
     * 也不要「用错布局静默算错」——与 `ImagePipelineBridge.available` 同一逻辑。
     */
    val available: Boolean by lazy {
        if (!VmpNative.loaded) {
            Log.w(TAG, "native 未加载：${VmpNative.loadError}")
            false
        } else {
            val abi = VmpNative.abiVersion()
            if (abi != EXPECTED_ABI_VERSION) {
                Log.e(TAG, "ABI 不匹配：so=$abi, kotlin=$EXPECTED_ABI_VERSION")
                false
            } else {
                true
            }
        }
    }

    /** 人类可读的运行时信息，打日志/上屏用。 */
    fun describe(): String = buildString {
        append("native=").append(if (available) "loaded" else "unavailable")
        if (VmpNative.loaded) {
            append(" abi=").append(VmpNative.abiVersion())
            VmpNative.versionString()?.let { append(" (").append(it).append(')') }
        } else {
            append(" reason=").append(VmpNative.loadError)
        }
    }

    /** 加固状态摘要（程序密文长度、头部完好性、VM 语义自检等）。 */
    fun hardeningStatus(): String =
        if (available) VmpNative.hardeningStatus() ?: "native 返回空" else "native 不可用"

    /** VM 内建自检。证明的是「VM 自己没坏」，不是「结果对」——后者靠金标准对拍。 */
    fun selftest(): Boolean = available && VmpNative.selftest()

    /** 三个受保护程序的容器头是否完好。 */
    fun programsIntact(): Boolean = available && VmpNative.programsIntact()

    // ───────────────────────── direct buffer 池 ─────────────────────────

    private val pool: ThreadLocal<HashMap<Long, ByteBuffer>> =
        ThreadLocal.withInitial { HashMap() }

    private fun key(slot: Int, bytes: Int): Long = (slot.toLong() shl 40) or bytes.toLong()

    /** 取一块 capacity ≥ [bytes] 的 direct buffer（小端、位置归零）。 */
    fun acquire(bytes: Int, slot: Int = 0): ByteBuffer {
        require(bytes > 0) { "字节数必须为正" }
        val k = key(slot, bytes)
        val map = pool.get() ?: HashMap<Long, ByteBuffer>().also { pool.set(it) }
        map[k]?.let {
            it.clear()
            return it
        }
        val fresh = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)
        map[k] = fresh
        return fresh
    }

    /** 清空当前线程的缓冲池（测试收尾用）。 */
    fun clearPool() = pool.get()?.clear()

    /** 把 Bitmap 像素读进 direct buffer（RGBA8888）。 */
    fun readPixels(bitmap: Bitmap, into: ByteBuffer) {
        into.rewind()
        bitmap.copyPixelsToBuffer(into)
        into.rewind()
    }

    /** 从 direct buffer 写回 Bitmap（RGBA8888）。 */
    fun writePixels(bitmap: Bitmap, from: ByteBuffer) {
        from.rewind()
        bitmap.copyPixelsFromBuffer(from)
        from.rewind()
    }

    /** 把 direct buffer 的 `size` 字节取成 `ByteArray`（对拍用）。 */
    fun snapshot(buf: ByteBuffer, size: Int): ByteArray {
        val out = ByteArray(size)
        buf.rewind()
        buf.get(out, 0, size)
        buf.rewind()
        return out
    }

    // ───────────────────────── 字节层（对拍唯一可用的一层）─────────────────────────

    /**
     * 字节层主色调。返回 [Outcome]，`color` 为 `0xRRGGBB`（失败时 `rc < 0`）。
     *
     * 结果以 `int` 返回而不是位图：颜色只占 24 位，塞得进 `int`；用负数表示错误码，
     * 调用方判 `< 0` 即可。JNI 边界上因此不构造任何 Java 对象。
     */
    fun dominantBytes(src: ByteBuffer, w: Int, h: Int, path: Path): Outcome {
        if (!available) return Outcome(path, rc = -1)
        val rc = when (path) {
            Path.VM -> VmpNative.dominantColorVm(src, w, h)
            Path.NATIVE -> VmpNative.dominantColorNative(src, w, h)
        }
        return Outcome(
            path = path,
            rc = rc,
            color = if (rc >= 0) rc else -1,
            fetchPages = if (path == Path.VM) VmpNative.lastFetchPages() else -1,
            cacheHits = if (path == Path.VM) VmpNative.lastCacheHits() else -1,
            steps = if (path == Path.VM) VmpNative.lastSteps() else -1L,
        )
    }

    /** 字节层降采样。返回 [Outcome]；`rc == 0` 表示成功。 */
    fun downscaleBytes(
        src: ByteBuffer, sw: Int, sh: Int, dst: ByteBuffer, dw: Int, dh: Int, path: Path,
    ): Outcome {
        if (!available) return Outcome(path, rc = -1)
        val rc = when (path) {
            Path.VM -> VmpNative.downscaleVm(src, sw, sh, dst, dw, dh)
            Path.NATIVE -> VmpNative.downscaleNative(src, sw, sh, dst, dw, dh)
        }
        return Outcome(
            path = path,
            rc = rc,
            fetchPages = if (path == Path.VM) VmpNative.lastFetchPages() else -1,
            cacheHits = if (path == Path.VM) VmpNative.lastCacheHits() else -1,
            steps = if (path == Path.VM) VmpNative.lastSteps() else -1L,
        )
    }

    /** 字节层盒式模糊。返回 [Outcome]；`rc == 0` 表示成功。 */
    fun blurBytes(
        src: ByteBuffer, w: Int, h: Int, dst: ByteBuffer, radius: Int, path: Path,
    ): Outcome {
        if (!available) return Outcome(path, rc = -1)
        val rc = when (path) {
            Path.VM -> VmpNative.blurVm(src, w, h, dst, radius)
            Path.NATIVE -> VmpNative.blurNative(src, w, h, dst, radius)
        }
        return Outcome(
            path = path,
            rc = rc,
            fetchPages = if (path == Path.VM) VmpNative.lastFetchPages() else -1,
            cacheHits = if (path == Path.VM) VmpNative.lastCacheHits() else -1,
            steps = if (path == Path.VM) VmpNative.lastSteps() else -1L,
        )
    }

    // ───────────────────────── 位图层（UI / 业务用）─────────────────────────

    /** 主色调（位图入口）。 */
    fun dominant(bitmap: Bitmap, path: Path): Outcome {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return Outcome(path, rc = -1)
        val buf = acquire(w * h * 4)
        readPixels(bitmap, buf)
        return dominantBytes(buf, w, h, path)
    }

    /**
     * 区域降采样（位图入口）。
     *
     * ⚠️ 输入与输出用**不同的 pool slot**（0 给输入、1 给输出）：native 侧假设
     * 二者不别名。尺寸不同时天然不会撞，但一旦某次 `dw*dh == sw*sh`（放大到等面积），
     * 只按字节数缓存就会拿回同一块 buffer —— 这类 bug 只在特定尺寸下出现，
     * 所以约定必须是无条件的。
     */
    fun downscale(bitmap: Bitmap, dw: Int, dh: Int, path: Path): Pair<Bitmap?, Outcome> {
        val sw = bitmap.width
        val sh = bitmap.height
        if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return null to Outcome(path, rc = -1)
        val src = acquire(sw * sh * 4, slot = 0)
        readPixels(bitmap, src)
        val dst = acquire(dw * dh * 4, slot = 1)
        dst.clear()
        val outcome = downscaleBytes(src, sw, sh, dst, dw, dh, path)
        if (!outcome.ok) return null to outcome
        val out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        writePixels(out, dst)
        return out to outcome
    }

    /** 盒式模糊（位图入口）。输入输出同尺寸，故必须用不同 slot。 */
    fun blur(bitmap: Bitmap, radius: Int, path: Path): Pair<Bitmap?, Outcome> {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0 || radius < 0) return null to Outcome(path, rc = -1)
        val bytes = w * h * 4
        val src = acquire(bytes, slot = 0)
        readPixels(bitmap, src)
        val dst = acquire(bytes, slot = 1)
        dst.clear()
        val outcome = blurBytes(src, w, h, dst, radius, path)
        if (!outcome.ok) return null to outcome
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        writePixels(out, dst)
        return out to outcome
    }

    // ───────────────────────── 对拍工具 ─────────────────────────

    /** 逐字节比较，返回首个不同的下标（-1 表示完全一致；长度不同返回较短长度）。 */
    fun firstByteDiff(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            if (a[i] != b[i]) return i
        }
        return if (a.size == b.size) -1 else n
    }

    /** 两个字节流的平均绝对误差（用于「A/B 对照」时给出可比的数量级）。 */
    fun meanAbsDiff(a: ByteArray, b: ByteArray): Double {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        var sum = 0L
        for (i in 0 until n) {
            sum += kotlin.math.abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        }
        return sum.toDouble() / n
    }
}
