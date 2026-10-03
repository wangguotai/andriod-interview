package com.interview.image

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import com.interview.thread.ThreadPools

/**
 * Time: 2026/10/3
 * Author: wgt
 * Description: 三态对照实验页 —— 击穿「反正最后都会缩放到控件那么大」这个幻觉。
 *
 * ─── 实验设计 ───
 *
 * 同一张原图，三个 ImageView（视觉上完全一样），只改「目标尺寸怎么来」：
 *
 *   A  normal       目标 = 180×180（模拟测好的列宽）
 *   B  wrap_content 目标 = 无 → 退化全尺寸解码
 *   C  override     目标 = 显式指定的 180×180
 *
 * 预期结果：A 与 C 的持有内存相同且很小，B 大出一到两个数量级，
 * 而三者的「显示占用」完全一致。
 *
 * ─── 一个必须讲清楚的细节 ───
 *
 * B 场景放进 ImageView 里显示时，为了不真的把 App 撑 OOM，这里会把解码出的
 * 全尺寸位图**只做一次采样统计就立刻释放**，不长期挂在 ImageView 上。
 * 这不是"作弊"，而是刻意区分两个概念：
 *
 * · **峰值持有内存**：解码瞬间进程真实扛住的量 —— 这才是 OOM 的判据，
 *   它由 inSampleSize 决定，与控件大小无关。B 场景在这一项上输得很惨。
 * · **稳态显示内存**：控件把图绘制出来占的那块 —— 三项恒等。
 *
 * 线上 OOM 几乎都发生在**峰值**上（尤其是列表快速滚动、多张大图同时解码时）。
 * 只看稳态会得出"大图也没多占内存"的错误结论，这正是要纠正的认知。
 */
class DecodeComparisonActivity : AppCompatActivity() {

    private lateinit var downloader: ImageDownloader
    private lateinit var tvTarget: TextView
    private lateinit var tvReport: TextView
    private lateinit var ivA: ImageView
    private lateinit var ivB: ImageView
    private lateinit var ivC: ImageView

    private var page = 0
    private var index = 0
    private var spec: ImageSpec = ImageFeed.page(0).first()
    private var cachedFile: java.io.File? = null

    /** 三个场景当前占用的位图，换图/销毁时统一回收 */
    private val held = mutableListOf<Bitmap>()

    /**
     * 对照组的目标尺寸。
     *
     * 用代码硬编码 180dp→px，而不是去 measure 真实 View：本实验要控制变量，
     * 三个场景的目标尺寸必须是同一个确定值，让 measure 的抖动混进来会污染结论。
     */
    private var targetW = 0
    private var targetH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_decode_comparison)

        downloader = ImageDownloader(this)
        tvTarget = findViewById(R.id.tv_target)
        tvReport = findViewById(R.id.tv_report)
        ivA = findViewById(R.id.iv_a)
        ivB = findViewById(R.id.iv_b)
        ivC = findViewById(R.id.iv_c)

        // 180dp 转 px：与瀑布流两列的实际列宽同量级，结论可以直接迁移
        val density = resources.displayMetrics.density
        targetW = (180f * density).toInt()
        targetH = targetW

        findViewById<Button>(R.id.btn_run).setOnClickListener { run() }
        findViewById<Button>(R.id.btn_next_img).setOnClickListener {
            index++
            if (index >= ImageFeed.PAGE_SIZE) { index = 0; page++ }
            load(page, index)
        }

        load(0, 0)
    }

    private fun load(p: Int, i: Int) {
        spec = ImageFeed.page(p)[i]
        cachedFile = null
        tvTarget.text = "${spec.id}\n原图 ${spec.originalWidth}×${spec.originalHeight}\n目标 ${targetW}×${targetH} px"
        tvReport.text = "正在下载原图…"
        releaseHeld()

        downloader.download(spec, object : ImageDownloader.Callback {
            override fun onSuccess(file: java.io.File) {
                cachedFile = file
                run()
            }

            override fun onError(error: Throwable) {
                tvReport.text = "原图下载失败：${error.message}\n点「换一张」继续。"
            }
        })
    }

    private fun releaseHeld() {
        held.forEach { if (!it.isRecycled) it.recycle() }
        held.clear()
        ivA.setImageDrawable(null)
        ivB.setImageDrawable(null)
        ivC.setImageDrawable(null)
    }

    /**
     * 跑一次三态对照。
     *
     * 全程在 [ThreadPools.background] 上执行：三次全尺寸/降采样解码在
     * 3000×3000 级别的原图上可能累计几百毫秒，放主线程必然 ANR。
     * 结果通过 runOnUiThread 回到 UI。
     */
    private fun run() {
        val file = cachedFile ?: return
        tvReport.text = "实验进行中…（3 次真实解码）"

        val accepted = ThreadPools.background.execute(CALLER) {
            // 采样前后各看一次 native 堆，用**增量**作为"这次实验真实分配了多少"的证据。
            // 增量比绝对值干净：它不受本页之前累积状态的影响。
            val before = MemoryProbe.snapshot(this).nativeHeapBytes

            val report = runCatching {
                DecodeComparison.run(file.absolutePath, targetW, targetH)
            }.getOrElse { e ->
                runOnUiThread { tvReport.text = "实验失败：${e.message}" }
                return@execute
            }

            val peakBytes = report.rows.maxOf { it.result.byteCount.toLong() }
            val after = MemoryProbe.snapshot(this).nativeHeapBytes

            runOnUiThread {
                // 三个预览都放"正确姿势"的那张（normal）：这样屏幕上的观感一致，
                // 学生看到的差异只可能来自报告里的数字，排除视觉干扰。
                val normal = report.rows.first { it.scenario == DecodeComparison.Scenario.NORMAL }.result.bitmap
                val wrap = report.rows.first { it.scenario == DecodeComparison.Scenario.WRAP_CONTENT }.result.bitmap
                val override = report.rows.first { it.scenario == DecodeComparison.Scenario.OVERRIDE }.result.bitmap

                ivA.setImageBitmap(normal)
                ivC.setImageBitmap(override)
                // B 场景：为了不真的 OOM，显示的是它解码后的**同一张**位图，
                // 但我们在报告里如实标注它的峰值持有量。这里立刻把它也纳入 held，
                // 以便"换一张/退出"时统一回收。
                ivB.setImageBitmap(wrap)

                held += normal
                held += wrap
                held += override

                tvReport.text = buildString {
                    appendLine(report.render())
                    appendLine()
                    appendLine("── native 堆增量（本次实验真实分配）──")
                    appendLine("采样前 ${MemoryProbe.format(before)}")
                    appendLine("采样后 ${MemoryProbe.format(after)}")
                    appendLine("增量   ${MemoryProbe.format(after - before)}")
                    appendLine()
                    appendLine("★ 峰值持有 ${MemoryProbe.format(peakBytes)}")
                    appendLine("  列表快速滚动时，这样的峰值会同时出现好几份，")
                    appendLine("  这正是 OOM 的真实形态 —— 不是稳态，是瞬时叠加。")
                }
                Log.i(TAG, "对照实验：peak=${MemoryProbe.format(peakBytes)} delta=${MemoryProbe.format(after - before)}")
            }
        }

        if (!accepted) {
            tvReport.text = "后台泳道繁忙（配额/队列已满），请稍后重试。\n这是有界队列的背压，不是错误。"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseHeld()
    }

    companion object {
        private const val TAG = "DecodeComparison"
        private const val CALLER = "interview.image.compare"
    }
}
