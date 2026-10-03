package com.interview.image

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.myapplication.R
import kotlin.math.roundToInt

/**
 * Time: 2026/10/3
 * Author: wgt
 * Description: 瀑布流适配器 —— 每一张卡片都是一次「可被验证的降采样实验」。
 *
 * ─── 这张卡片要同时展示三件事，缺一不可 ───
 *
 * 降采样这件事，如果只给学生看一张图，他是学不会的。必须让他看到
 * **输入 → 决策 → 输出** 这条链上的三个数字：
 *
 * 1. `tv_state`（顶部，输入）：原图尺寸 / 本次请求的目标尺寸。
 *    → 目标尺寸是 inSampleSize 的唯一输入。没有它就没有后面的一切。
 * 2. 中间的图（决策的产物）：Glide 实际交给 ImageView 的那张位图。
 * 3. `tv_badge`（底部，输出）：**真实解码尺寸 · 真实内存 · 采样倍数 · 省了多少**。
 *    → 全部取自 Bitmap 自身（width/height/byteCount），不是我们的估算。
 *      这一点很关键：学生不必相信我们说的任何话，他看的是系统给的数。
 *
 * ─── 为什么用 asBitmap() + RequestListener 取真实位图 ───
 *
 * 想证明"inSampleSize 生效了"，最直接的证据就是解码出来的 Bitmap 的宽高。
 * 用 `asBitmap()` 让 Glide 交付 Bitmap（而非 Drawable），在 onResourceReady
 * 里拿到的就是最终产物。这比去反射 Glide 内部的 Options 可靠得多 ——
 * 那个 Options 是 DecodeHelper 里的局部变量，没有稳定钩子，反射代码会随
 * Glide 版本碎掉，教学 demo 不该建立在那种假设上。
 *
 * ─── 关于「解码结果为什么比目标大」───
 *
 * 这里有一个流传很广的错误说法（网上大量博客都这么写）：
 * 「ImageView 用 centerCrop 时 Glide 会自动选 AT_LEAST」。
 * 翻 Glide 4.12 源码可以证伪：
 *
 *   DownsampleStrategy.java:87   public static final DownsampleStrategy DEFAULT = CENTER_OUTSIDE;
 *   BaseRequestOptions.java:722  centerCrop() → transform(DownsampleStrategy.CENTER_OUTSIDE, ...)
 *
 * 而且 `AT_LEAST` 在整个 Glide 核心代码里**从未被引用过**（只有它的声明）。
 * 所以 centerCrop 配的是 **CENTER_OUTSIDE**：
 *
 *   getScaleFactor = max(reqW/srcW, reqH/srcH)     // 铺满
 *   getSampleSizeRounding = QUALITY                 // → calculateScaling 里取 min
 *
 * 「取 min」才是关键：它保证**采样后的尺寸不会小于目标**，即宁可多留像素也不糊。
 * 所以你会看到解码结果常常是目标尺寸的 1~2 倍，而不是恰好相等 ——
 * 这不是 bug，是 Glide 为了画质主动付的内存代价。徽标上那个"÷N"就是它的读数。
 *
 * 另外记一个真实的踩坑点：`downsample(strategy)` 会**覆盖** transform 路径设置过的
 * 策略，但如果先调 downsample 再调 centerCrop，后者又会把它盖回 CENTER_OUTSIDE ——
 * 两次调用谁在后面谁生效。想精确控制就只用其中一个，别混用。
 * 本类只用 centerCrop()，不重复设置。
 */
class WaterfallAdapter(
    private val requestManager: RequestManager,
    private val spanCount: Int,
) : RecyclerView.Adapter<WaterfallAdapter.ImageHolder>() {

    private val data = mutableListOf<ImageSpec>()

    /**
     * 对照组开关：true 时用 `.override(Target.SIZE_ORIGINAL)` 强制按原图尺寸解码。
     *
     * 这是本页最重要的"反面实验"：同一个列表、同一批图、只改这一行，
     * 内存立刻从几十 MB 涨到几百 MB。让学生亲眼看到"降采样到底省了什么"。
     */
    var rawSizeMode: Boolean = false

    /** item 之间的间距，计算目标宽度时要从列宽里扣掉（与 XML 里的 margin 保持一致） */
    private val itemMarginPx = 4f

    private var minHeightPx = 120
    private var maxHeightPx = 640

    fun setMargins(minPx: Int, maxPx: Int) {
        minHeightPx = minPx
        maxHeightPx = maxPx
    }

    fun submit(specs: List<ImageSpec>, append: Boolean) {
        if (!append) data.clear()
        val start = data.size
        data.addAll(specs)
        if (append) notifyItemRangeInserted(start, specs.size) else notifyDataSetChanged()
    }

    fun clear() {
        val n = data.size
        data.clear()
        notifyItemRangeRemoved(0, n)
    }

    fun itemAt(position: Int): ImageSpec? = data.getOrNull(position)

    override fun getItemCount(): Int = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image_card, parent, false)
        return ImageHolder(view)
    }

    override fun onBindViewHolder(holder: ImageHolder, position: Int) {
        val spec = data[position]
        val marginPx = (itemMarginPx * holder.itemView.resources.displayMetrics.density).toInt()

        // ── 目标尺寸怎么来的：列宽 → 扣掉 margin → 按原图宽高比算出高度 ──
        // 瀑布流的"参差"就是这么来的：高度由每张图自己的宽高比决定。
        // 而这一步同时在回答降采样的第一个问题：「图片要显示多大？」
        val rv = holder.itemView.parent as? RecyclerView
        val available = ((rv?.width ?: 0) - (rv?.paddingLeft ?: 0) - (rv?.paddingRight ?: 0))
            .coerceAtLeast(1)
        val columnWidth = available / spanCount
        val targetWidth = (columnWidth - marginPx * 2).coerceAtLeast(1)

        // ratio = 原图宽 / 原图高；显示高度 = 显示宽度 / ratio
        val ratio = spec.originalWidth.toFloat() / spec.originalHeight.toFloat()
        val targetHeight = (targetWidth / ratio).roundToInt()
            .coerceIn(minHeightPx, maxHeightPx)

        holder.image.layoutParams = holder.image.layoutParams.apply {
            width = targetWidth
            height = targetHeight
        }

        // 清空上一轮的证据，避免复用时残留旧数字造成误读
        holder.badge.text = ""
        holder.state.text = "原图 ${spec.originalWidth} × ${spec.originalHeight}"

        // ── 输入侧：把"这次请求的目标尺寸"直接写出来 ──
        // 对照组下目标尺寸就是原图本身 —— 这正是要演示的危险情形。
        holder.state.append(
            if (rawSizeMode) "\n目标 SIZE_ORIGINAL（原尺寸！）"
            else "\n目标 ${targetWidth} × ${targetHeight}"
        )

        requestManager
            .asBitmap()
            .load(spec.url)
            // ★ 全实验的核心开关：这两行决定了内存差一个数量级。
            //   注意 RequestBuilder 继承自 BaseRequestOptions，override / centerCrop
            //   都是它的成员方法，可以链式直写，不必另建 RequestOptions 对象。
            .override(
                if (rawSizeMode) Target.SIZE_ORIGINAL else targetWidth,
                if (rawSizeMode) Target.SIZE_ORIGINAL else targetHeight,
            )
            // 不再显式调 downsample(...)：BaseRequestOptions.centerCrop() 内部
            // 用的就是 DownsampleStrategy.CENTER_OUTSIDE（见源码 BaseRequestOptions:722，
            // `transform(DownsampleStrategy.CENTER_OUTSIDE, new CenterCrop())`）。
            // 重复设置只会多一次 put，教学代码更应该展示"默认值已经是对的"。
            .centerCrop()
            .listener(object : RequestListener<Bitmap> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Bitmap>,
                    isFirstResource: Boolean,
                ): Boolean {
                    holder.state.text = "加载失败：${e?.message ?: "unknown"}"
                    return false
                }

                override fun onResourceReady(
                    resource: Bitmap,
                    model: Any?,
                    target: Target<Bitmap>,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    // ★ 证据全部取自这里：resource 就是 Glide 最终解码出来的位图，
                    //   它的 width/height/byteCount 是系统给的 ground truth。
                    val size = "${resource.width} × ${resource.height}"
                    val mem = MemoryProbe.format(resource.byteCount.toLong())

                    // 采样倍数：由「原图宽 / 解码宽」反推，比猜 inSampleSize 可靠
                    val divider = (spec.originalWidth.toFloat() / resource.width)
                        .roundToInt().coerceAtLeast(1)

                    // 省了多少：按 ARGB_8888 每像素 4 字节算原图的全尺寸体积
                    val rawBytes = spec.originalWidth.toLong() * spec.originalHeight * 4L
                    val saving = if (resource.byteCount > 0) {
                        "%.1f×".format(rawBytes.toFloat() / resource.byteCount)
                    } else "?"

                    holder.badge.text = "解码 $size · $mem · ÷$divider · 省 $saving"

                    // 记入累计账本：当前内存会横盘，累计值不会撒谎
                    DecodeLedger.record(resource.byteCount)
                    return false
                }
            })
            .into(holder.image)
    }

    override fun onViewRecycled(holder: ImageHolder) {
        super.onViewRecycled(holder)
        // 列表回收时必须取消请求：否则滑走的 item 仍会完成解码并占用内存，
        // 在高频滚动下会堆积成一次典型的"看起来没道理"的 OOM。
        requestManager.clear(holder.image)
    }

    class ImageHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.iv_image)
        val badge: TextView = view.findViewById(R.id.tv_badge)
        val state: TextView = view.findViewById(R.id.tv_state)
    }
}
