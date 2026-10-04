package com.interview.image

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.bumptech.glide.Glide
import com.example.myapplication.R

/**
 * Time: 2026/10/3
 * Author: wgt
 * Description: 「Glide 降采样」Lab 主场景 —— 瀑布流大图加载。
 *
 * ─── 这个页面要让学生亲眼看到的因果链 ───
 *
 *   ImageView 的显示尺寸
 *        ↓ （Glide 在解码前就能拿到）
 *   目标尺寸 target
 *        ↓ （DownsampleStrategy 算出 inSampleSize）
 *   BitmapFactory 按 2 的幂次采样解码
 *        ↓
 *   小得多的 Bitmap → 内存骤降
 *
 * 每一环都有可读的数字：卡片顶部是**输入**（原图 vs 目标），
 * 卡片底部是**输出**（真实解码尺寸 / 内存 / 倍数 / 倍数），
 * 顶部面板是**整体水位**（graphics + native + Java 三路互证）。
 *
 * ─── 为什么是瀑布流（StaggeredGrid），不是普通列表 ───
 *
 * 两个原因，都跟教学有效性直接相关：
 *
 * 1. **瀑布流天然产生"每张图目标尺寸都不同"**。普通列表里所有 item 尺寸一致，
 *    学生容易误以为 inSampleSize 是个固定值；瀑布流里每张图的宽高比不同、
 *    显示高度不同，每张卡片的"采样倍数"都不一样，一眼就能看出
 *    **inSampleSize 是逐图算出来的，不是一个全局常量**。
 * 2. 瀑布流必须为每张图算出高度才能布局，这就逼着代码显式写出
 *    「显示尺寸」这一步 —— 正好把降采样里最容易被忽略的第一环摆到台面上。
 *
 * ─── 一次实验怎么做（建议的课堂步骤）───
 *
 *  1. 默认模式滚几屏，记下顶部「累计解码」与「graphics 水位」。
 *  2. 点「重置账本」，打开右上「对照组 override(SIZE_ORIGINAL)」开关。
 *  3. 滚同样多的屏，对比累计解码体积 —— 通常是十几倍到几十倍的差距。
 *  4. 关掉开关，说明这就是"确定目标尺寸"这一步的价值。
 *
 * 日志 TAG：ImageLab，证据全部同时打到 logcat，便于课后复盘。
 */
class ImageLabActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: WaterfallAdapter
    private lateinit var downloader: ImageDownloader

    private lateinit var tvMem: TextView
    private lateinit var tvLedger: TextView
    private lateinit var pbGraphics: ProgressBar
    private lateinit var swRawSize: Switch
    private lateinit var swAutoLoad: Switch
    private lateinit var swPlaceholder: Switch

    private lateinit var memSampler: MemoryProbe.Sampler

    /** 已请求过的页码；切 overct 对照时必须重放，否则缓存命中看不出差异 */
    private var loadedPages = 0

    /** 自动翻页是否已放行；见 maybeAutoLoad 的说明 */
    private var autoLoadReleased = false
    private var autoLoadPending = false

    /** 上一次采样的 graphics PSS，用来算增量 —— 增量比绝对值更能说明"这一屏花了多少" */
    private var lastGraphicsKb = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_waterfall)

        downloader = ImageDownloader(this)

        tvMem = findViewById(R.id.tv_mem)
        tvLedger = findViewById(R.id.tv_ledger)
        pbGraphics = findViewById(R.id.pb_graphics)
        swRawSize = findViewById(R.id.sw_raw_size)
        swAutoLoad = findViewById(R.id.sw_auto_load)
        swPlaceholder = findViewById(R.id.sw_placeholder)
        recyclerView = findViewById(R.id.rv_images)

        // 瀑布流的关键：2 列 + 每张图自己的高度 → 参差错落的目标尺寸
        recyclerView.layoutManager = StaggeredGridLayoutManager(
            SPAN_COUNT,
            StaggeredGridLayoutManager.VERTICAL,
        )

        val density = resources.displayMetrics.density
        adapter = WaterfallAdapter(Glide.with(this)).apply {
            setMargins((MIN_ITEM_HEIGHT_DP * density).toInt(), (MAX_ITEM_HEIGHT_DP * density).toInt())
        }
        recyclerView.adapter = adapter

        // ─── 首屏加载的时序（这里踩过一个真实且不报错的坑，值得讲清楚）───
        //
        // 目标尺寸依赖 RecyclerView 的最终测量宽度，所以必须等第一次布局完成。
        // 但「等布局完成」有正确与错误的做法：
        //
        // ✗ 直接用 doOnLayout { loadNextPage() }
        //   doOnLayout 的回调发生在 **layout 遍历过程中**（此时 View.isInLayout == true）。
        //   在遍历中途 notifyDataSetChanged，requestLayout() 确实被调用了，
        //   但紧接着 View.layout() 会清掉 PFLAG_FORCE_LAYOUT，而 ViewRootImpl 复查
        //   「布局中发起的请求」时只认这个位 —— 于是这一帧被当成"已处理"，
        //   第二遍 layout 遍历被跳过；而这次遍历的 step2 又早已跑完，不会再看新数据。
        //   结果是永久空白：adapter 里有 20 条数据，界面上一个 child 都没有，
        //   且 onBindViewHolder 一次都不触发（实测确认）。
        //
        //   注意：这**不是** mInterceptRequestLayoutDepth 造成的 —— 实测该值为 0，
        //   RecyclerView 的覆写照常放行到了 super.requestLayout()。详见 NOTES 第 7/9 节。
        //
        // ✓ 先等遍历结束，下一帧再填数据
        //   doOnLayout 里再 post 一帧：此时 isInLayout == false，notifyDataSetChanged
        //   的 requestLayout 能正常生效，下一帧就会走完整的 measure→layout→bind。
        //
        // 实测对比（API 36 模拟器）：
        //   doOnLayout 内直接 submit : isInLayout=true  → childCount=0，onBind 触发 0 次
        //   doOnLayout 内 post 后 submit: isInLayout=false → childCount=19，onBind 触发 19 次
        //
        // 注意：这个坑**只在 RecyclerView 复写了 requestLayout() 时才出现**，
        // 普通 View 在 layout 中 requestLayout 是会被 framework 正常接管的。
        recyclerView.doOnLayout {
            if (adapter.columnWidthPx == 0) {
                // 宽度在此刻才可信（onLayout 之后）；bind 阶段拿不到 parent 宽度，只能在这里量
                val available = (recyclerView.width - recyclerView.paddingLeft - recyclerView.paddingRight)
                    .coerceAtLeast(1)
                adapter.columnWidthPx = available / SPAN_COUNT
            }
            // 布局还没结束就填数据会被"吞掉"，必须等到下一帧
            recyclerView.post {
                if (loadedPages == 0) loadNextPage()
                // 首屏补齐后再放行自动翻页：onCreate 里 RecyclerView 宽度还是 0，
                // 此刻若触发翻页，adapter 的目标宽度会退化成 1px（见 WaterfallAdapter.columnWidthPx）
                autoLoadReleased = true
            }
        }

        bindAutoLoad()
        bindControls()
        startMemorySampler()
    }

    /**
     * 滚动到底自动加载下一页。
     *
     * ─── 为什么不是简单地"最后一条可见就 loadNextPage"───
     *
     * 有三个必须处理的点，任何一个漏掉都会在教学里制造假象：
     *
     * 1. **`loadNextPage` 要等到布局完成**。onCreate 里 RecyclerView 宽度还是 0，
     *    此时触发的 adapter.submit 会让目标宽度退化成 1px（见 WaterfallAdapter.columnWidthPx
     *    注释里的那个坑）。所以用 autoLoadReleased 把首屏之后的翻页放行。
     * 2. **触底判定要跨两列**。StaggeredGridLayoutManager 不能只看 findLastVisibleItemPositions()[0]。
     *    它返回的是**每个 span 各自最后可见的 position**，两个 span 拼接顺序在该 API 上
     *    不做保证，必须取 max 才是"整屏最靠下的 item"。取 min 或取 [0] 都会在
     *    一侧先到底时就提前翻页，出现"还没滑到底就跳下一页"。
     * 3. **节流**。滚动中 onScrolled 每帧都回调，不加约束会在一次甩动里连翻好几页
     *    （最后一屏的 item 在整个甩动过程中都可见）。两道闸门：
     *    · `autoLoadPending` —— 一帧内只排一次 post，避免同一帧连排多次；
     *    · post 内**重新判定**触底 —— `loadNextPage()` 是同步的，itemCount 立刻增加，
     *      于是本帧后续的 onScrolled 不再满足阈值，直到用户真的又滑下去。
     *    两者合起来把"连续回调"收敛成"每越过一次阈值只翻一页"。
     *
     * 预加载阈值 [AUTO_LOAD_THRESHOLD]：还剩 4 个 item 就提前加载，
     * 这样加载动作发生在用户滑到底之前，滚动手感是连续的（而不是到底后干等一帧）。
     */
    private fun bindAutoLoad() {
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                maybeAutoLoad()
            }
        })
    }

    private fun maybeAutoLoad() {
        if (!swAutoLoad.isChecked) return
        if (!autoLoadReleased) return
        if (loadedPages == 0) return // 首屏还没填，避免与首屏加载竞态
        if (autoLoadPending) return  // 已经排了一帧，避免一次甩动里连排多次

        val lm = recyclerView.layoutManager as? StaggeredGridLayoutManager ?: return
        val lastPos = lm.findLastVisibleItemPositions(null).maxOrNull() ?: return

        if (lastPos < adapter.itemCount - 1 - AUTO_LOAD_THRESHOLD) return

        // ★ 必须推迟到下一帧：onScrolled 可能跑在 measure/layout 遍历里，
        //   此时改 adapter 会命中 RecyclerView.assertNotInLayoutOrScroll 的告警
        //   （日志：Cannot call this method in a scroll callback...）。
        //   这是本页注释里反复强调的"布局中改数据"的同一类坑，只是触发路径不同。
        autoLoadPending = true
        recyclerView.post {
            autoLoadPending = false
            if (!swAutoLoad.isChecked || !autoLoadReleased) return@post
            // 重新判定一次：这一帧期间用户可能已经滑离底部（或别的路径刚好加载完了）
            val pos = lm.findLastVisibleItemPositions(null).maxOrNull() ?: return@post
            if (pos >= adapter.itemCount - 1 - AUTO_LOAD_THRESHOLD) {
                Log.i(TAG, "触底自动加载：lastVisible=$pos / itemCount=${adapter.itemCount}，加载第 $loadedPages 页")
                loadNextPage()
            }
        }
    }

    private fun bindControls() {
        findViewById<Button>(R.id.btn_next_page).setOnClickListener { loadNextPage() }

        swAutoLoad.setOnCheckedChangeListener { _, checked ->
            Log.i(TAG, if (checked) "自动加载下一页：开" else "自动加载下一页：关（改用底部按钮手动翻页）")
        }

        swPlaceholder.setOnCheckedChangeListener { _, checked ->
            adapter.placeholderMode = checked
            if (!checked) {
                // 关掉时清缓存，保证「关 → 开」是一次干净的对比，而不是捡上一轮的缓存结果
                PlaceholderPalette.clear()
                adapter.resetPaletteStats()
            }
            Log.i(
                TAG,
                if (checked) "Rust 主色调占位色：开（图片**再次**出现时铺底色）"
                else "Rust 主色调占位色：关",
            )
            replay()
        }

        findViewById<Button>(R.id.btn_open_inspector).setOnClickListener {
            startActivity(Intent(this, DecodeInspectorActivity::class.java))
        }

        findViewById<Button>(R.id.btn_open_compare).setOnClickListener {
            startActivity(Intent(this, DecodeComparisonActivity::class.java))
        }

        findViewById<Button>(R.id.btn_reset_ledger).setOnClickListener {
            DecodeLedger.reset()
            renderLedger()
        }

        findViewById<Button>(R.id.btn_clear_cache).setOnClickListener {
            downloader.clearCache()
            // 清缓存的同时把已解码的位图也放掉，避免"磁盘清空了但内存还是满的"造成误判。
            // 注意 clearMemory() 在 Glide 类上，不在 RequestManager 上 —— RequestManager
            // 只负责按生命周期管请求，缓存池归 Glide 单例。
            // clearMemory() 内部有 assertMainThread()，所以这里必须在主线程调用（按钮回调天然满足）。
            Glide.get(this).clearMemory()
            tvMem.append("\n（已清磁盘缓存 + Glide 内存缓存）")
        }

        swRawSize.setOnCheckedChangeListener { _, checked ->
            adapter.rawSizeMode = checked
            Log.w(
                TAG,
                if (checked) "【对照组】override(SIZE_ORIGINAL)：目标尺寸 = 原图尺寸，降采样被架空"
                else "【对照组】已关闭：恢复按显示尺寸降采样",
            )
            // ★ 必须重放：Glide 的缓存 key 包含 override 的尺寸，
            //   但如果不重放，屏幕上还是旧图，学生看不到"内存飙升"的瞬间。
            replay()
        }
    }

    private fun loadNextPage() {
        val page = loadedPages
        val specs = ImageFeed.page(page)
        loadedPages++

        adapter.submit(specs, append = page > 0)
        Log.i(TAG, "加载第 $page 页，共 ${specs.size} 张；${DecodeLedger.summary()}")

        // 预下载原图到磁盘缓存，供「解剖台」页做裸 BitmapFactory 逐步解码。
        // 走的是统一网络泳道，带单飞与背压 —— 被拒不会崩，只是这一张稍后再来。
        specs.forEach { downloader.download(it, DebugCallback) }
    }

    /**
     * 重放当前已加载的所有页。
     *
     * 为什么不直接 notifyDataSetChanged：需要把每张图上旧的徽标清掉，
     * 否则切换开关后新旧数字并存，学生会误读成"降采样没生效"。
     */
    private fun replay() {
        val specs = (0 until loadedPages).flatMap { ImageFeed.page(it) }
        adapter.submit(specs, append = false)
        Log.i(TAG, "重放 $loadedPages 页（${specs.size} 张）以让新配置生效")
    }

    // ─────────────────────────────────────────
    // 内存证据
    // ─────────────────────────────────────────

    private fun startMemorySampler() {
        memSampler = MemoryProbe.Sampler(this, intervalMs = 1000L) { snap -> renderMemory(snap) }
        memSampler.start()
    }

    private fun renderMemory(snap: MemoryProbe.Snapshot) {
        val deltaKb = if (lastGraphicsKb == 0) 0 else snap.graphicsPssKb - lastGraphicsKb
        lastGraphicsKb = snap.graphicsPssKb

        // 进度条用 graphics 相对"当前实测峰值"的占用率来显示。
        // 这里刻意不用 Java 堆：API 26+ 位图不在 Java 堆，用它做水位的条
        // 会出现"内存爆了但条没动"的教学事故。
        val graphicsMb = snap.graphicsPssKb / 1024f
        val ratio = (graphicsMb / GRAPHICS_BAR_MAX_MB).coerceIn(0f, 1f)
        pbGraphics.progress = (ratio * 100).toInt()

        tvMem.text = buildString {
            appendLine("graphics ${MemoryProbe.formatKb(snap.graphicsPssKb)}  (Δ%+d KB)".format(deltaKb))
            appendLine("native   ${MemoryProbe.format(snap.nativeHeapBytes)}")
            append("java     ${MemoryProbe.format(snap.javaUsedBytes)} / ${MemoryProbe.format(snap.javaMaxBytes)}")
        }
    }

    private fun renderLedger() {
        tvLedger.text = buildString {
            append(DecodeLedger.summary())
            append('\n')
            append(adapter.paletteSummary())
        }
    }

    override fun onResume() {
        super.onResume()
        if (::memSampler.isInitialized) memSampler.start()
    }

    override fun onPause() {
        super.onPause()
        // 页面不可见还持续采样没有意义，也会在后台白耗电
        if (::memSampler.isInitialized) memSampler.stop()
        renderLedger()
    }

    /**
     * 预下载回调：这里只记日志。
     *
     * 失败不能弹 UI：picsum 在弱网下偶发超时。预下载只是为「解剖台」备料，
     * 而瀑布流本身是 Glide 自己走网络加载的，两者互不依赖 ——
     * 预下载失败不该影响主教学链路，这正是"路径解耦"的价值。
     */
    private object DebugCallback : ImageDownloader.Callback {
        override fun onSuccess(file: java.io.File) {
            Log.d(TAG, "原图已缓存：${file.name} (${file.length()} bytes)")
        }

        override fun onError(error: Throwable) {
            Log.w(TAG, "原图预下载失败（不影响 Glide 主链路）：${error.message}")
        }
    }

    companion object {
        private const val TAG = "ImageLab"

        /** 2 列：手机竖屏下每列约 180dp，目标尺寸与 2000px+ 的原图形成足够大的落差 */
        private const val SPAN_COUNT = 2
        private const val MIN_ITEM_HEIGHT_DP = 120f
        private const val MAX_ITEM_HEIGHT_DP = 320f

        /** 水位条的视觉满量程。超过它按满格画，但数字仍是真实值。 */
        private const val GRAPHICS_BAR_MAX_MB = 256f

        /**
         * 自动翻页的预加载阈值：还剩这么多个 item 可见时就提前加载下一页。
         * 取 4 是为了"滑到底之前刚好接上"，太大容易在慢网下连续翻页。
         */
        private const val AUTO_LOAD_THRESHOLD = 4
    }
}
