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
 * ─── 「取 min」到底如何逼近目标尺寸（用实测数据说明，别想当然）───
 *
 * 直觉上会以为：inSampleSize 只能取 2 的幂，所以解码结果会比目标大一圈。
 * 大量博客也是这么写的（"解码尺寸是目标的 1~2 倍"）。**实测证伪了它。**
 *
 * 真实链路是**两段式的**：
 *   第一段 inSampleSize 粗采样（只能是 2 的幂）→ 得到一个偏大的位图
 *   第二段 BitmapFactory 的 density 缩放 → 精确缩到 target
 *
 * 所以 Glide 交到 ImageView 手里的位图，尺寸**精确等于 target**。
 *
 * 本项目在 API 36 模拟器上实测（卡片徽标显示的就是这一段）：
 *   原图 3068 × 2012，目标 515 × 338
 *     CENTER_OUTSIDE: scale = max(515/3068, 338/2012) = 0.167992
 *                     out  = round(scale × src) = 515 × 338
 *     scaleFactor = min(3068/515, 2012/338) = min(5, 5) = 5
 *     inSampleSize = highestOneBit(5) = 4        ← 第一段只能到 4
 *     inSampleSize 单独解码 = 3068/4 × 2012/4 = 767 × 503 = 1.47 MB
 *     实测最终交付       = 515 × 338          = 0.66 MB   ← 第二段又缩了一半
 *
 * 所以徽标上那个"÷N"（这里 N=6）是**两段缩放叠加**的结果，不是单纯的 inSampleSize。
 * 想看清第一段，去「解剖台」页 —— 那里用裸 BitmapFactory，只有 inSampleSize，没有第二段。
 */
class WaterfallAdapter(
    private val requestManager: RequestManager,
) : RecyclerView.Adapter<WaterfallAdapter.ImageHolder>() {

    private val data = mutableListOf<ImageSpec>()

    /**
     * 对照组开关：true 时用 `.override(Target.SIZE_ORIGINAL)` 强制按原图尺寸解码。
     *
     * 这是本页最重要的"反面实验"：同一个列表、同一批图、只改这一行，
     * 内存立刻从几十 MB 涨到几百 MB。让学生亲眼看到"降采样到底省了什么"。
     */
    var rawSizeMode: Boolean = false

    /**
     * Rust 主色调占位色开关。
     *
     * ─── 这个开关为什么做成可关闭 ───
     *
     * 它是 M4 端到端复测的**唯一变量**：开着与关着跑同一段滚动，
     * 其余条件完全不变，Perfetto 的对比才有意义。若不给开关，
     * 「接入前/接入后」就得靠切 commit 重新装机，引入的差异（构建、缓存状态）
     * 会让结论没法归因。
     *
     * ⚠️ 关于这个功能**能**与**不能**带来什么（避免误解成"性能优化"）：
     * - 能：图片解码完成后，用 Rust 算一次主色调并缓存；该图**再次出现**时
     *   （滑走再滑回、或同一 id 重新 bind）立刻铺上底色，不再闪一下灰底。
     * - 不能：**首次**加载时没有占位色 —— 因为主色调来自已解码的位图，
     *   图还没解码就没有位图。要做到首屏就有色，得让上游（网络/解码）
     *   先给一张极小的图，那是另一条链路（缩略图/BlurHash）的事。
     *
     * 把这条边界写在注释里很重要：不然极易被当成"Rust 让图片显示更快了"，
     * 而它其实只影响**重复出现**时的观感。
     */
    var placeholderMode: Boolean = false

    /** 统计：主色调计算里实际走 Rust / Java 各多少次（结论里要引用）。 */
    var paletteNativeCount = 0
        private set
    var paletteJavaCount = 0
        private set

    /** item 之间的间距，计算目标宽度时要从列宽里扣掉（与 XML 里的 margin 保持一致） */
    private val itemMarginPx = 4f

    /**
     * 单列宽度（像素）。**由 Activity 在布局完成后设置**。
     *
     * ─── 为什么不在这里自己从 `itemView.parent` 取（这是一个真实的坑）───
     *
     * `onBindViewHolder` 的执行时机是 RecyclerView 的 `tryGetViewHolderForPositionByDeadline`：
     * **先 bind，后 `addView`**。所以此刻 `holder.itemView.parent` 是 **null**，
     * 用 `itemView.parent as? RecyclerView` 取宽度会拿到 0，
     * 目标尺寸随之退化成 1px —— 列表看着"有东西"但每张图都被压成一条线，
     * 而且瀑布流依赖的高度参差也全丢了（所有 height 都被 coerce 到同一个最小值）。
     *
     * 实测验证（API 36 模拟器，最小复现工程）：
     *   onBindViewHolder pos=0  itemView.parent=null
     *   onBindViewHolder pos=1  itemView.parent=null
     *
     * 所以宽度必须由外部显式喂进来 —— 这是本类唯一的必需外部状态。
     */
    var columnWidthPx: Int = 0

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
        //
        // ⚠️ 宽度来自 columnWidthPx（Activity 在布局完成后测好），**不要**改成
        //    从 itemView.parent 取 —— bind 阶段 itemView 还没被 addView，
        //    parent 是 null，会静默退化成 1px。原因见 columnWidthPx 的注释。
        val columnWidth = columnWidthPx.coerceAtLeast(1)
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

        // ── Rust 主色调占位色 ──
        // 分两种情况：已有缓存（上次算过）就立即铺色；没有就先把底色清成灰，
        // 等这一轮解码完成后再由 onResourceReady 里算（只对**重复出现**有意义）。
        // 注意 holder.palette 的画法：给 ImageView 上 tint 会污染真实图片的显示，
        // 所以用一个独立的 View 铺在图片下方。
        if (placeholderMode) {
            val cached = PlaceholderPalette.peek(spec.id)
            if (cached != null) {
                holder.palette.setBackgroundColor(cached)
                holder.palette.visibility = View.VISIBLE
            } else {
                holder.palette.visibility = View.GONE
            }
        } else {
            holder.palette.visibility = View.GONE
        }

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

                    // ── Rust 主色调：拿刚解码好的位图算一次并缓存 ──
                    // 这一步放在 onResourceReady 里是必然的：主色调的来源就是这张
                    // 已解码的位图。所以它算出来的色，只能让**这张图再次出现**时
                    // 用得上 —— 首次显示仍然是"先图后无占位"，这是本方案的固有边界。
                    if (placeholderMode) {
                        PlaceholderPalette.extractAsync(spec.id, resource) { r ->
                            // 回调在主线程；此刻 holder 可能已被复用给别的 spec，
                            // 必须核对身份再写 UI —— 这是 RecyclerView 异步回调的经典坑。
                            val bound = data.getOrNull(holder.bindingAdapterPosition)
                            if (bound?.id == r.id) {
                                holder.palette.setBackgroundColor(r.argb)
                                holder.palette.visibility = View.VISIBLE
                            }
                            if (r.usedNative) paletteNativeCount++ else paletteJavaCount++
                            if (!r.cached) {
                                holder.state.append("\n占位色 #%06X (%.1fms)".format(r.argb and 0xFFFFFF, r.elapsedMs))
                            }
                        }
                    }
                    return false
                }
            })
            .into(holder.image)
    }

    /** 供 Activity 显示「主色调走了哪条路径」的证据摘要。 */
    fun paletteSummary(): String {
        val total = paletteNativeCount + paletteJavaCount
        if (total == 0) return "占位色：尚无计算"
        return "占位色：rust=$paletteNativeCount java=$paletteJavaCount（共 $total）"
    }

    fun resetPaletteStats() {
        paletteNativeCount = 0
        paletteJavaCount = 0
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

        /**
         * 主色调占位色块：铺在 ImageView 之下（XML 里先于 ImageView 声明）。
         *
         * 为什么不直接给 ImageView 设 background 或 tint：tint 会参与图片的
         * 实际绘制（改变真实颜色），而占位色的语义是「图片还没到，先垫一层」，
         * 图片一旦画上来就应该完全遮住它。用一个独立 View 垫底，语义最清楚，
         * 也不会在图片半透明/带 alpha 时产生意外混合。
         */
        val palette: View = view.findViewById(R.id.v_palette)
    }
}
