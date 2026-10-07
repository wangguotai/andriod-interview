package com.example.myapplication

import com.interview.vmp.VmpBridge
import java.nio.ByteBuffer

/**
 * Time: 2026/10/6
 * Author: wgt
 * Description: androidTest 的公共测试素材 —— 造图与 direct buffer 取用。
 *
 * 金标准对拍与基准**必须用同一套输入**，否则两条证据链不可比：
 * 「对拍用的图」和「基准用的图」如果不一样，基准测的就不是被验证过的那条路径。
 * 所以把造图逻辑提到这里共用，而不是各写一份。
 */
internal object TestImages {

    /**
     * 确定性伪随机测试图（RGBA8888）。
     *
     * 刻意混入：全透明像素、灰阶、纯 R/G/B、以及随机噪声。
     * - 纯色图会让大量 bug「看起来也对」（尤其是通道顺序错位、累加器初值错）；
     * - 全透明像素走 alpha!=0 的分支之外的路（若实现按 alpha 加权，这里是分水岭）；
     * - 灰阶像素覆盖 dominant 里 r==g==b 的 `delta==0` 灰度回退分支。
     *
     * 用自实现的 LCG 而不是 `Random(seed)`：跨设备/跨 JDK 版本结果**必须完全一致**，
     * 否则「同一张图在 A 机器对拍通过、在 B 机器对拍失败」会变成不可复现的幽灵问题。
     */
    fun synthRgba(w: Int, h: Int, seed: Long): ByteArray {
        var s = seed
        fun next(): Int {
            s = s * 6364136223846793005L + 1442695040888963407L
            return ((s ushr 33).toInt()) and 0xFF
        }
        val v = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            val o = i * 4
            when (i % 7) {
                0 -> { v[o] = 0; v[o + 1] = 0; v[o + 2] = 0; v[o + 3] = 0 }
                1 -> { v[o] = 200.toByte(); v[o + 1] = 200.toByte(); v[o + 2] = 200.toByte(); v[o + 3] = 255.toByte() }
                2 -> { v[o] = 255.toByte(); v[o + 1] = 0; v[o + 2] = 0; v[o + 3] = 255.toByte() }
                3 -> { v[o] = 0; v[o + 1] = 255.toByte(); v[o + 2] = 0; v[o + 3] = 255.toByte() }
                4 -> { v[o] = 0; v[o + 1] = 0; v[o + 2] = 255.toByte(); v[o + 3] = 255.toByte() }
                5 -> { v[o] = 0; v[o + 1] = 128.toByte(); v[o + 2] = 255.toByte(); v[o + 3] = 255.toByte() }
                else -> { v[o] = next().toByte(); v[o + 1] = next().toByte(); v[o + 2] = next().toByte(); v[o + 3] = 255.toByte() }
            }
        }
        return v
    }
}

/** 把字节塞进 direct buffer（小端），供 native 直接读取。 */
internal fun bufOf(bytes: ByteArray, slot: Int = 0): ByteBuffer =
    VmpBridge.acquire(bytes.size, slot).also { it.put(bytes); it.rewind() }
