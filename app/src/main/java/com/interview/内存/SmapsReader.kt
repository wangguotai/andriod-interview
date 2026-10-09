package com.interview.内存

import java.io.File

/**
 * Time: 2026/10/9
 * Author: wgt
 * Description: 内存监控的**第 4 条观测线** —— `/proc/self/smaps` 的逐行解析（映射级归因）
 *
 * ══════════════════════════════════════════════════════════════════════
 * 为什么还需要这一条（补的是哪块死角）
 * ══════════════════════════════════════════════════════════════════════
 *
 * `MemoryAnalyze` 的三条路（alloc hook / Java 直方图 / BitmapTracker）已经能回答
 * 「谁要的内存」。但它们有一个共同的**盲区**：都是「分配器/虚拟机口径」——
 * 只看得见**经过我们代码或 ART 的映射**。真机上常见的一类现场是：
 *
 * ```
 *   Java 堆 不涨、nativeAlloc 不涨、BitmapTracker 不涨
 *   但 VmRSS / totalPss 一路涨
 * ```
 *
 * 这时钱花在了**三方 .so、按需加载的 dex/oat、字体、GPU 驱动映射、命名匿名段**上，
 * 三条路都照不到。`smaps` 是内核给的**逐映射**账单，是唯一能把这笔钱落到
 * 「哪一段地址、哪个文件、哪一类」的东西。
 *
 * 所以本文件是**下钻入口**：先回答「涨的是哪一类」（[Result.byCategory]），
 * 再回答「具体是哪个映射」（[Result.top]）。graphics 那条线也靠它下钻到
 * `/dev/kgsl*`（见 §三）。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 诚实边界（先读这节，否则报告会被误读）
 * ══════════════════════════════════════════════════════════════════════
 *
 * 1. **RSS 是"多进程共享页每份都算一份"的口径**。两个进程映射同一个 .so，
 *    它的 RSS 会各算一次；要看"我到底还欠多少不可回收的内存"必须看 **Private_Dirty**，
 *    要看"系统层面的占用"才看 PSS。三者不能混着比。
 * 2. **`readSelf` 只读得到本进程**。`/proc/<pid>/smaps` 读别的 pid 需要相应权限，
 *    线上进程读自己是最稳的。
 * 3. **一次 smaps 读是"重"的**：映射多时输出可达百 KB~MB 量级，解析也要毫秒级开销。
 *    因此它**不进 [MemoryMetrics.capture] 的采样热路径**，只在排查/定时下钻时调用。
 * 4. **本类不做时间序列**。smaps 是单点快照，要判断"涨没涨"必须自己跨两次 diff
 *    （拿 [Mapping.startAddr] 做 key 对齐）。
 * 5. **未知属性键一律忽略而不是报错**（见 §二.2）——不同内核/ROM 的属性行集合不同，
 *    把"新键"当错误会让监控在升级内核后突然全线失效。
 */
object SmapsReader {

    private const val TAG = "SmapsReader"

    /** 匿名映射的占位名。⚠️ 与 `[anon:...]` 命名段不是一回事（后者有名字，见 §三）。 */
    const val ANON = "[anon]"

    // ─────────────────────────────────────────────
    // 一、数据模型
    // ─────────────────────────────────────────────

    /**
     * 单条映射的聚合结果。
     *
     * ⚠️ 单位全部是 **KB**（smaps 原文就是 kB），字段名带 `Kb` 后缀防止误当字节。
     * [startAddr]/[endAddr] 保持 16 进制原文 —— 它们是**跨两次采样的对齐 key**，
     * 转成十进制反而不好和 `cat /proc/self/smaps` 对照。
     */
    data class Mapping(
        val startAddr: String,      // 例如 "7f9c1a000000"（保持 16 进制原文）
        val endAddr: String,
        val perms: String,          // 例如 "rw-p"
        val path: String,           // 映射名；匿名映射用 "[anon]" 之类的占位
        val rssKb: Long,
        val pssKb: Long,
        val privateDirtyKb: Long,
        val swapKb: Long,
        val sizeKb: Long,           // 虚拟大小（从地址范围算）
    )

    /**
     * 解析结果：明细 + 按类别聚合。
     *
     * [parsedLines]/[skippedLines] 是**自检口径**，必须如实。
     * 见 §二.3：监控最不能犯的错是"静默给一个像模像样的错值"。
     */
    data class Result(
        val mappings: List<Mapping>,        // 全部映射，按 rssKb 降序
        val totalRssKb: Long,
        val totalPssKb: Long,
        val parsedLines: Int,               // 成功解析的映射条数（自检用）
        val skippedLines: Int,              // 无法解析/被跳过的行数（必须如实计数，不静默）
    ) {
        fun top(n: Int): List<Mapping> = mappings.take(n.coerceAtLeast(0))

        fun byCategory(): List<CategorySum> = categorySums(mappings)

        fun describe(topN: Int = 15): String = buildDescribe(this, topN)
    }

    /** 按"归属类别"聚合——这是给人看的那一层 */
    data class CategorySum(val name: String, val rssKb: Long, val pssKb: Long, val count: Int)

    // ─────────────────────────────────────────────
    // 二、解析（纯函数，可在宿主 JVM 上单测）
    // ─────────────────────────────────────────────

    /**
     * 映射头行。形如：
     * ```
     * 7f9c1a000000-7f9c1a021000 rw-p 00000000 00:00 0 /path/to/file
     * 7f9c1a400000-7f9c1a401000 rw-p 00000000 00:00 0          ← 匿名：行尾就是 "00:00 0"
     * ```
     *
     * ⚠️ 为什么用**正则一次抓全**而不是 `split(" ")`：路径里可能带空格
     *    （如 `[anon:dalvik-main space (region space)]`），也可能带 `(deleted)` 后缀。
     *    固定切分会把这些行的路径切碎。第 7 组是"0 之后剩下的全部内容"，也就是路径。
     */
    private val HEADER = Regex(
        "^([0-9a-fA-F]+)-([0-9a-fA-F]+)" +   // 1,2 地址范围
                "\\s+(\\S{4})" +                     // 3   perms，如 rw-p
                "\\s+(\\S+)" +                       // 4   offset
                "\\s+(\\S+)" +                       // 5   dev，如 00:00
                "\\s+(\\S+)" +                       // 6   inode
                "(?:\\s+(.*))?$"                     // 7   路径（可缺省 = 匿名）
    )

    /** 属性行：`Key:` 后面可能有两个甚至更多空格，因此不假定分隔符个数。 */
    private val ATTR = Regex("^(\\S[^:]*):\\s*(.*)$")

    /**
     * 解析 smaps 文本。**纯函数**：不碰文件系统、不碰 Android API，可直接喂单测样本。
     *
     * ─── §二.1 一行分三种角色 ───
     *
     * 1. **映射头行** → 结束上一条、开一条新的；
     * 2. **属性行**（`Rss:`/`Pss:`/`Size:`/`VmFlags:`/任意未知键）→ 只更新当前这条；
     * 3. **空行** → 跳过，**不计入 skipped**（它是格式的一部分，不是"无法解析"）。
     *
     * 剩下的才进 [Result.skippedLines]。
     *
     * ─── §二.2 为什么"未知键忽略"而不是报错 ───
     *
     * 属性行集合是**内核版本相关**的：老内核只有 Rss/Pss/Private_Dirty/Swap，
     * 新内核会多出 `SwapPss:`、`Anonymous:`、`Locked:`、`ThpEligible:` 等。
     * 若把"不认识的键"当错误，一次内核升级就会让线上监控全线失效 ——
     * 这是**探针不能改变被测语义、也不能被被测环境变更打断**这条纪律的直接推论。
     * 所以这里对未知键**静默忽略**：它不影响已知字段的正确性，就不该影响解析。
     *
     * ─── §二.3 为什么 skippedLines 必须如实统计 ───
     *
     * smaps 的唯一价值是"如实"。如果某天某 ROM 换了属性行格式、或某行被截断，
     * 而解析器**静默**跳过它，产出的 RSS 合计会**偏低且看不出来** ——
     * 那正是本模块存在的理由（"监控自己不能给错值"）被违反。
     * 所以宁可让 skipped > 0 显式暴露，也不吞掉。
     */
    fun parse(text: String): Result {
        val out = ArrayList<Mapping>()
        var parsed = 0
        var skipped = 0

        // 当前映射的可变累加器（处理完头部后逐行填，遇到下一条头行时定稿）
        var cur: Header? = null
        // 当前映射已解析出的属性（缺省 0，与 smaps 语义一致：没写就是没有）
        var rss = 0L
        var pss = 0L
        var privateDirty = 0L
        var swap = 0L

        /** 把正在累加的这条定稿进 [out]。 */
        fun flush() {
            val h = cur ?: return
            out.add(
                Mapping(
                    startAddr = h.start,
                    endAddr = h.end,
                    perms = h.perms,
                    path = h.path,
                    rssKb = rss,
                    pssKb = pss,
                    privateDirtyKb = privateDirty,
                    swapKb = swap,
                    sizeKb = h.sizeKb,
                )
            )
            parsed++
            cur = null
            rss = 0; pss = 0; privateDirty = 0; swap = 0
        }

        // ⚠️ 逐行处理。用 split('\n') 而不是 readLines()，因为这里输入是内存里的字符串，
        //    且 smaps 行尾是 '\n'，split 后最后会多一个空串 —— 空串会被 isBlank() 吃掉。
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r') // 兼容 CRLF（Windows 上导出的样本/ADB pull）

            // 空行：格式的一部分，不计 skipped（否则每两条映射之间都会 +1）
            if (line.isBlank()) continue

            // ── 角色 2：属性行 ──
            // 先试头行，再试属性行：头行不含冒号，两者不会误判。
            // 但 VmFlags 行形如 "VmFlags: rd wr mr ..." 会命中 ATTR，需单独忽略。
            val header = HEADER.matchEntire(line)
            if (header != null) {
                flush() // 上一条定稿
                val start = header.groupValues[1]
                val end = header.groupValues[2]
                val pathToken = header.groupValues.getOrNull(7)?.trim().orEmpty()
                val path = if (pathToken.isEmpty()) ANON else pathToken
                cur = Header(
                    start = start,
                    end = end,
                    perms = header.groupValues[3],
                    path = path,
                    sizeKb = sizeKbOf(start, end),
                )
                continue
            }

            val attr = ATTR.matchEntire(line)
            if (attr != null) {
                val key = attr.groupValues[1].trim()
                val value = attr.groupValues[2]

                // 属性行出现在任何头行之前 = 文件被截断/从中间喂进来 → 如实算 skip
                if (cur == null) {
                    skipped++
                    continue
                }

                when (key) {
                    // ⚠️ 已知键才取值；值非法（如 "Rss: abc kB"）→ 算 skip，
                    //    因为"少算了 RSS"必须暴露，不能悄悄当 0。
                    "Rss" -> parseKb(value)?.let { rss = it } ?: skipped++
                    "Pss" -> parseKb(value)?.let { pss = it } ?: skipped++
                    "Private_Dirty" -> parseKb(value)?.let { privateDirty = it } ?: skipped++
                    "Swap" -> parseKb(value)?.let { swap = it } ?: skipped++
                    // Size / VmFlags / SwapPss / Anonymous / Locked / ... 一律忽略：
                    // · Size 不取用，因为 sizeKb 由地址范围算（地址范围是唯一权威，
                    //   两者不一致时以地址为准，见 §二.4）
                    // · VmFlags 是内核 flag 串，不参与内存口径
                    // · 其余是"未知键忽略"策略的覆盖对象
                    else -> Unit
                }
                continue
            }

            // ── 角色 3：既不是头行也不是属性行 = 畸形行 → 如实记账 ──
            skipped++
        }
        flush() // 文件末尾的那条

        // 按 RSS 降序：人看 smaps 的第一问永远是"谁最大"
        val sorted = out.sortedByDescending { it.rssKb }
        return Result(
            mappings = sorted,
            totalRssKb = sorted.sumOf { it.rssKb },
            totalPssKb = sorted.sumOf { it.pssKb },
            parsedLines = parsed,
            skippedLines = skipped,
        )
    }

    /**
     * §二.4 从地址范围算虚拟大小。
     *
     * 为什么不用 smaps 自带的 `Size:` 字段：`Size` 是内核按**页对齐**报的，
     * 而地址范围是**唯一不会自相矛盾**的量（同一行里的两端）。两者理论上应相等；
     * 一旦不等（截断/坏样本），以范围为准更可诊断 —— 至少它可复算。
     *
     * ⚠️ 地址是 16 进制且可能超过 `Int`，必须 `toLong(16)`；解析失败返回 0
     *    （不属于"少算内存"，只是这一条的 size 不可得，不单独记 skip）。
     */
    private fun sizeKbOf(start: String, end: String): Long {
        val s = start.toLongOrNull(16) ?: return 0L
        val e = end.toLongOrNull(16) ?: return 0L
        // 不支持地址范围倒置的坏行：返回 0 而不是负数（负数会被求和污染）
        if (e < s) return 0L
        return (e - s) / 1024
    }

    /**
     * 取属性值里的数字。
     *
     * ⚠️ **不能按固定个数的分隔符切分**：smaps 行是
     * `Rss:  1234 kB`（冒号后**两个**空格），但不同内核/字段可能是一个或更多空格，
     * 有的甚至形如 `Rss:\t1234 kB`。所以先 `trimEnd`，再按**任意空白**取第一个 token，
     * 只认这个 token 是不是数字 —— 单位 `kB` 在哪、空格几个都不关心。
     */
    private fun parseKb(value: String): Long? =
        value.trim().split(' ', '\t')
            .firstOrNull { it.isNotEmpty() }
            ?.toLongOrNull()

    /** 头行的中间态（还没定稿成 [Mapping]）。 */
    private data class Header(
        val start: String,
        val end: String,
        val perms: String,
        val path: String,
        val sizeKb: Long,
    )

    // ─────────────────────────────────────────────
    // 三、归类（"该动谁"的直接答案）
    // ─────────────────────────────────────────────

    // 类别名是**给人读**的，直接进 logcat / 报告，所以用中文且自带例子。
    private const val CAT_GPU = "GPU/图形 (kgsl/dri/gralloc)"
    private const val CAT_ALLOC = "分配器堆 (heap/malloc/scudo)"
    private const val CAT_STACK = "线程栈"
    private const val CAT_SHMEM = "共享内存 (ashmem/shmem)"
    private const val CAT_CODE = "代码/资源 (so/apk/dex/oat)"
    private const val CAT_FONT = "字体/资源"
    private const val CAT_DALVIK_ANON = "Dalvik/ART 匿名段"
    private const val CAT_NAMED_ANON = "命名匿名段 (其它 [anon:...])"
    private const val CAT_ANON = "匿名映射 (无路径)"
    private const val CAT_KERNEL = "内核特殊映射 (vdso/vvar)"
    private const val CAT_OTHER = "其它文件映射"

    /**
     * 按 path 归类，**按优先级自上而下第一个命中**。
     *
     * ─── 为什么顺序本身是设计 ───
     *
     * 因为一条路径可能同时满足多个模式。例如 `[anon:libc_malloc]` 既是"命名匿名段"
     * 又是"分配器堆"；若先命中"命名匿名段"，看报告的人就**认不出**这是 malloc 堆，
     * 也就答不出"该动谁"。所以最"该动谁"越具体的类别排越前面：
     *
     * 1. GPU/图形：`/dev/kgsl*`、`/dev/dri*`、gralloc —— 这是 **graphics 线的下钻入口**。
     *    图形内存不走 malloc，`summary.graphics` 只能给总量，落到映射就靠这条。
     *    单独归类的原因：它涨通常意味着"纹理/帧缓冲没释放"，处理动作和"代码/字体"完全不同。
     * 2. 分配器堆：`[heap]` / `[anon:libc_malloc]` / `[anon:scudo:*]` ——
     *    这三类其实是**同一个东西的不同分配器实现**（bionic malloc → scudo、ART → libc_malloc），
     *    必须并成一类，否则同一个泄漏会在报告里裂成三行、比例被稀释、看不出问题。
     * 3. 线程栈 / 4. 共享内存 / 5. 代码资源 / 6. 字体 / 7. 命名匿名段 / 8. 匿名
     */
    private fun categoryOf(m: Mapping): String {
        val p = m.path
        return when {
            p.startsWith("/dev/kgsl") || p.startsWith("/dev/dri") || p.contains("gralloc") ->
                CAT_GPU

            // "[heap]" 是 brk 堆；scudo 的命名段形如 "[anon:scudo:primary]"，
            // libc_malloc 形如 "[anon:libc_malloc]"（bionic 的 malloc 映射名）。
            p == "[heap]" || p.startsWith("[anon:libc_malloc") || p.startsWith("[anon:scudo") ->
                CAT_ALLOC

            // 主线程栈是 "[stack]"；线程栈在部分内核里显示为 "[stack:<tid>]"
            p == "[stack]" || p.startsWith("[stack:") -> CAT_STACK

            // ashmem（Android 传统共享内存）与新内核的 [anon_shmem:*]，以及 memfd
            p.startsWith("/dev/ashmem") || p.startsWith("[anon_shmem") || p.contains("/memfd:") ->
                CAT_SHMEM

            // 代码与资源。⚠️ .so 可能藏在 apk 内，路径形如
            //   "/data/app/.../base.apk!/lib/arm64-v8a/libfoo.so"，endsWith(".so") 仍能命中
            p.endsWith(".so") || p.endsWith(".apk") || p.endsWith(".dex") ||
                    p.endsWith(".oat") || p.endsWith(".vdex") || p.endsWith(".art") ->
                CAT_CODE

            // 字体：路径在 system/data 下，或按扩展名兜底（含 apk 内字体）
            p.contains("/system/fonts/") || p.contains("/data/fonts/") ||
                    p.endsWith(".ttf") || p.endsWith(".otf") || p.endsWith(".ttc") ->
                CAT_FONT

            // dalvik/ART 的命名匿名段（region space、main space 等）单独成类，
            // 因为它是"Java 堆在 native 侧的映射"，与纯 native 的命名段动作不同
            p.startsWith("[anon:dalvik") -> CAT_DALVIK_ANON

            // 其余命名匿名段（如 [anon:stack_and_tls:...]、[anon:.bss]）
            p.startsWith("[anon:") -> CAT_NAMED_ANON

            // 真正的匿名映射：无路径（占位为 ANON），或内核直接标 [anon]
            p == ANON || p.isBlank() -> CAT_ANON

            // vdso/vvar/vectors 是内核映射进来的，不占我们的物理页（除 vdso 的少量），
            // 但会让"匿名/其它"看着有个小尾巴，单列以免干扰判断
            p == "[vvar]" || p == "[vdso]" || p == "[vectors]" -> CAT_KERNEL

            else -> CAT_OTHER
        }
    }

    /** 归类汇总，按 rssKb 降序 —— 报告里第一个类别就是"该动谁"的候选。 */
    private fun categorySums(mappings: List<Mapping>): List<CategorySum> =
        mappings
            .groupBy { categoryOf(it) }
            .map { (name, list) ->
                CategorySum(
                    name = name,
                    rssKb = list.sumOf { it.rssKb },
                    pssKb = list.sumOf { it.pssKb },
                    count = list.size,
                )
            }
            .sortedByDescending { it.rssKb }

    // ─────────────────────────────────────────────
    // 四、IO 包装层（读真实 /proc/self/smaps）
    // ─────────────────────────────────────────────

    /**
     * 读真实 `/proc/{pid}/smaps` 并解析。
     *
     * ─── 为什么失败**抛异常**而不是返回空 Result ───
     *
     * `MemoryMetrics.capture` 那一层失败要降级（缺项填 0），因为它是**常驻采样**，
     * 崩了会拖垮主流程。但本方法不同：它只在下钻排查时**显式**调用，
     * 返回一个"0 条映射"的空 Result 和"真的没有映射"**无法区分** ——
     * 调用方会把"读失败"误读成"内存很干净"，这正是本模块最忌讳的静默错值。
     * 所以这里把 IO 失败**原样抛出**，让调用方明确选：要么看到失败，要么用
     * [readSelfOrNull] 显式接受 null。
     *
     * ⚠️ [parse] 才是纯函数；本方法只做"读文件 + 交给 parse"，不含任何解析逻辑。
     */
    fun readSelf(pid: Int = android.os.Process.myPid()): Result {
        val file = File("/proc/$pid/smaps")
        val text = try {
            file.readText()
        } catch (e: Exception) {
            // 常见原因：SELinux 拒绝 / 进程已死 / 该 pid 不是自己
            throw IllegalStateException("读取 ${file.path} 失败（pid=$pid）", e)
        }
        return parse(text)
    }

    /** 显式接受失败版本：排查代码里不想被 IO 异常打断时用（失败给 null，绝不假装空结果）。 */
    fun readSelfOrNull(pid: Int = android.os.Process.myPid()): Result? =
        runCatching { readSelf(pid) }.getOrNull()

    // ─────────────────────────────────────────────
    // 五、报告
    // ─────────────────────────────────────────────

    private fun buildDescribe(r: Result, topN: Int): String = buildString {
        appendLine("══════ smaps 映射归因（/proc/self/smaps）══════")
        appendLine(
            "RSS 合计 ${fmtKb(r.totalRssKb)} | PSS 合计 ${fmtKb(r.totalPssKb)}" +
                    " | 映射 ${r.mappings.size} 条" +
                    (if (r.skippedLines > 0) "  ⚠️ skipped=${r.skippedLines} 行未解析" else "  skipped=0")
        )

        appendLine()
        appendLine("── Top${topN} 明细（按 RSS 降序）──")
        appendLine("        地址范围                          perms    RSS       PSS      PrivDirty  路径")
        r.top(topN).forEach { m ->
            appendLine(
                "  ${m.startAddr}-${m.endAddr}".padEnd(36) +
                        " ${m.perms.padEnd(6)}" +
                        " ${fmtKb(m.rssKb).padStart(9)}" +
                        " ${fmtKb(m.pssKb).padStart(9)}" +
                        " ${fmtKb(m.privateDirtyKb).padStart(9)}" +
                        "  ${m.path}"
            )
        }

        appendLine()
        appendLine("── 分类汇总（按 RSS 降序）──")
        val cats = r.byCategory()
        cats.forEach { c ->
            val pct = if (r.totalRssKb > 0) c.rssKb * 100.0 / r.totalRssKb else 0.0
            appendLine(
                "  ${c.name.padEnd(30)}" +
                        " RSS=${fmtKb(c.rssKb).padStart(9)}" +
                        " PSS=${fmtKb(c.pssKb).padStart(9)}" +
                        " ${"%5.1f%%".format(pct)}  ×${c.count}"
            )
        }

        appendLine()
        append("结论：")
        val top = cats.firstOrNull()
        if (top == null) {
            append("没有解析到任何映射（样本为空或全部被跳过）。")
        } else {
            val pct = if (r.totalRssKb > 0) top.rssKb * 100.0 / r.totalRssKb else 0.0
            append("占比最大的是「${top.name}」（${"%.1f".format(pct)}%，").append(fmtKb(top.rssKb)).append("）")
            append("；若它持续增长 → ")
            append(
                when (top.name) {
                    CAT_GPU -> "查图形/纹理生命周期（下钻到具体 /dev/kgsl 映射）"
                    CAT_ALLOC -> "查 native 分配与碎片（结合 MemoryAllocTracker / malloc_info）"
                    CAT_CODE -> "查 so/dex 增量加载（是否重复加载或未释放的 dex/oat）"
                    CAT_STACK -> "查线程数是否失控（栈随线程创建而增长）"
                    CAT_SHMEM -> "查 ashmem/memfd 是否只增不减（Bitmap/共享缓冲）"
                    CAT_FONT -> "查字体/资源是否被反复映射"
                    else -> "结合 startAddr 跨两次采样 diff，定位具体是哪条映射在涨"
                }
            )
            append("。")
        }
        if (r.skippedLines > 0) {
            appendLine()
            append("⚠️ 有 ${r.skippedLines} 行未解析：合计可能偏低，先确认是否为内核属性行差异或样本截断。")
        }
    }

    /**
     * 统一用 MB、两位小数 —— 直接复用 [MemoryMetrics.fmt]，保证与其它报告口径一致。
     * （本方法只多一层 KB→bytes 换算，不另立格式约定。）
     */
    private fun fmtKb(kb: Long): String = MemoryMetrics.fmt(kb * 1024)
}
