package com.interview.image

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import com.interview.thread.ThreadPools

/**
 * Time: 2026/10/3
 * Author: wgt
 * Description: 降采样解剖台 —— 把 inSampleSize 的推导过程逐步摊开给学生看。
 *
 * ─── 这个页面存在的理由 ───
 *
 * 原理课讲「先 inJustDecodeBounds 读尺寸，再算 inSampleSize，最后真解码」，
 * 学生记住的是**顺序**，不是**数字**。而这个知识点真正的难点全在数字上：
 *
 * · 为什么 3000×4000 缩到 600×800 得到的是 inSampleSize=4 而不是 5？
 * · 为什么解码出来是 750×1000，比我要的 600×800 大一圈？
 * · 为什么 wrap_content 会让内存涨十几倍？
 *
 * 这些问题的答案必须能被逐行打印出来才讲得清。所以这里用裸 BitmapFactory
 * 完整复刻这条链，并且把每一步的中间值都摊在屏幕上。
 *
 * ─── 与瀑布流页的分工 ───
 *
 * · 瀑布流页（[ImageLabActivity]）：证明 **Glide 真的交付了小图**（结果可信）。
 * · 本页：证明 **小图是怎么算出来的**（过程可信）。
 *   两边都可信，学生才会真的接受这个结论，而不是"背下来应付面试"。
 */
class DecodeInspectorActivity : AppCompatActivity() {

    private lateinit var downloader: ImageDownloader
    private lateinit var tvSpec: TextView
    private lateinit var tvTrace: TextView
    private lateinit var etW: EditText
    private lateinit var etH: EditText
    private lateinit var ivPreview: ImageView

    private var page = 0
    private var index = 0
    private var spec: ImageSpec = ImageFeed.page(0).first()

    /** 当前这张图的本地文件；null 表示还在下载 */
    private var cachedFile: java.io.File? = null

    /** 上一次解码出的位图。持有它是为了在换目标尺寸时能主动回收，避免本页自己泄漏 */
    private var lastBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_decode_inspector)

        downloader = ImageDownloader(this)
        tvSpec = findViewById(R.id.tv_spec)
        tvTrace = findViewById(R.id.tv_trace)
        etW = findViewById(R.id.et_req_w)
        etH = findViewById(R.id.et_req_h)
        ivPreview = findViewById(R.id.iv_preview)

        // 默认目标给 180 —— 正好是手机竖屏 2 列瀑布流里单个 item 的典型宽度
        etW.setText("180")
        etH.setText("180")

        findViewById<Button>(R.id.btn_decode).setOnClickListener { runDecode() }

        // ── 快捷目标：每个预设都对应一个"面试里会被问到的场景" ──
        bindPreset(R.id.btn_preset_wrap, 0, 0)          // wrap_content：拿不到尺寸
        bindPreset(R.id.btn_preset_full, -1, -1)        // 原尺寸：最坏情况
        bindPreset(R.id.btn_preset_grid, 180, 180)      // 瀑布流列宽
        bindPreset(R.id.btn_preset_thumb, 90, 90)       // 缩略图

        findViewById<Button>(R.id.btn_prev).setOnClickListener { step(-1) }
        findViewById<Button>(R.id.btn_next).setOnClickListener { step(1) }

        load(0, 0)
    }

    private fun bindPreset(buttonId: Int, w: Int, h: Int) {
        findViewById<Button>(buttonId).setOnClickListener {
            // -1 是"原尺寸"的哨兵值：换成本图的真实宽高
            if (w < 0) {
                etW.setText(spec.originalWidth.toString())
                etH.setText(spec.originalHeight.toString())
            } else {
                etW.setText(w.toString())
                etH.setText(h.toString())
            }
            runDecode()
        }
    }

    private fun step(delta: Int) {
        val next = index + delta
        if (next < 0) {
            if (page == 0) return
            page--; index = ImageFeed.PAGE_SIZE - 1
        } else if (next >= ImageFeed.PAGE_SIZE) {
            page++; index = 0
        } else {
            index = next
        }
        load(page, index)
    }

    private fun load(p: Int, i: Int) {
        spec = ImageFeed.page(p)[i]
        cachedFile = null
        tvSpec.text = "【$p/$i】${spec.id}\n原图 ${spec.originalWidth} × ${spec.originalHeight}\n正在下载原图…"
        tvTrace.text = ""

        downloader.download(spec, object : ImageDownloader.Callback {
            override fun onSuccess(file: java.io.File) {
                cachedFile = file
                tvSpec.text = "【$p/$i】${spec.id}\n" +
                        "原图 ${spec.originalWidth} × ${spec.originalHeight}\n" +
                        "本地 ${file.length() / 1024} KB\n" +
                        "全尺寸像素内存 ${MemoryProbe.format(bytesOfOriginal())}"
                runDecode()
            }

            override fun onError(error: Throwable) {
                tvSpec.text = "【$p/$i】展示图下载失败\n${error.message}\n\n" +
                        "点「下一张」换一张即可。\n" +
                        "注意：这一步失败走的是泳道背压回调，不是崩溃 —— 这就是有界队列的价值。"
            }
        })
    }

    private fun bytesOfOriginal(): Long =
        spec.originalWidth.toLong() * spec.originalHeight * 4L

    /**
     * 执行一次真实解码。
     *
     * 两个纪律：
     * 1. **必须在后台线程**。BitmapFactory.decodeFile 是同步重 CPU 操作，
     *    在 3000×4000 的图上能跑几十上百毫秒，放主线程就是一次可感知的卡顿，
     *    严重时直接 ANR —— 这正是本仓库 lint 规则要拦下的行为。
     * 2. **解码产生的中间位图要自己回收**。教学 demo 最容易犯的错是
     *    "讲内存泄漏的页面自己泄漏"，那就成了反面教材里的反面教材。
     */
    private fun runDecode() {
        val file = cachedFile ?: run {
            tvTrace.text = "原图还没下载完，稍等…"
            return
        }
        val reqW = etW.text.toString().toIntOrNull() ?: 0
        val reqH = etH.text.toString().toIntOrNull() ?: 0

        tvTrace.text = "解码中…"

        val accepted = ThreadPools.background.execute(CALLER) {
            // 计时包含读文件 + 解码全过程，与 Glide 的 DecodeJob 是同一段工作
            val result = DownsampleKit.decode(file.absolutePath, reqW, reqH, Bitmap.Config.ARGB_8888)
            val fullBytes = bytesOfOriginal()

            runOnUiThread {
                // 换新图前先放掉旧图，避免这个"教内存的页面"自己把内存攒起来
                lastBitmap?.takeIf { !it.isRecycled }?.recycle()
                lastBitmap = result.bitmap
                ivPreview.setImageBitmap(result.bitmap)

                tvTrace.text = buildString {
                    appendLine(result.trace)
                    appendLine()
                    appendLine("── 对照 ──")
                    appendLine("若全程不解码，仅按原图尺寸分配：")
                    appendLine("  ${MemoryProbe.format(fullBytes)}（就是 API 26 之前 OOM 的元凶）")
                }
                Log.i(TAG, "${spec.id} req=${reqW}×$reqH -> ${result.decodedWidth}×${result.decodedHeight} " +
                        "sample=${result.inSampleSize} ${MemoryProbe.format(result.byteCount.toLong())}")
            }
        }

        if (!accepted) {
            // 泳道拒绝是显式背压：这里如实告诉用户，而不是假装无事发生
            tvTrace.text = "后台泳道繁忙（配额/队列已满），请稍后重试。\n" +
                    "这是有界队列的背压，不是错误。"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 主动回收：Activity 销毁后这张位图不会再被用到，等 GC 回收 native 内存
        // 的时机不可控，在教学场景里显式回收能让"内存下降"这件事立刻可见。
        lastBitmap?.takeIf { !it.isRecycled }?.recycle()
        lastBitmap = null
    }

    companion object {
        private const val TAG = "DecodeInspector"
        private const val CALLER = "interview.image.inspector"
    }
}
