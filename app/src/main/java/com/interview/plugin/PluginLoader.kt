package com.interview.plugin

import android.content.Context
import android.util.Log
import com.interview.net.NetClient
import com.interview.thread.ThreadPools
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Time: 2026/10/13
 * Author: wgt
 * Description: 插件的**获取与装载**编排器 —— 「从资源文件」与「从网络接口」两条来源在这里汇合。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  一、三条来源，各自回答不同的问题
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   assets（离线兜底）｜ 宿主的**编译期**就带着插件包。回答的问题是：
 *       "在完全无网、插件包也不在设备上时，这套机制还能不能跑起来。"
 *       它是演示与 CI 的基线 —— 也是唯一一条"不依赖任何外部条件"的路径。
 *
 *   privateDir（可替换）｜ 插件包在 `getExternalFilesDir()` 或 filesDir 下。
 *       回答的问题是："同一份宿主，换一个插件包，行为会不会跟着变。"
 *       这条路径是调试插件最常用的（adb push 就能换），也最接近真实的"灰度替换"。
 *
 *   network（接口下发）｜ 插件包与元数据都从 HTTPS 取。
 *       回答的问题是："宿主怎么知道要加载什么、以及怎么确认取到的字节是对的。"
 *       答案在 [PluginManifest]：**下载地址与 sha256 一起下发**，宿主只信任校验通过的字节。
 *
 * ⚠️ 三条路径**不是三套代码**：它们只负责把「一个 apk 文件 + 一份元数据」摆到
 *    同一个位置，之后走完全相同的装载流程（[loadFromFile]）。这个结构是刻意的 ——
 *    "来源"与"装载"混在一起的插件框架，通常会在只有一个来源时工作良好，
 *    而第二个来源一加就出现"某条路上少了 sha 校验"这类不一致。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  二、为什么 sha256 校验不是一个可选项
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 网络下发插件最常见的三种真实故障，全都**不是**"能不能加载"能发现的：
 *
 *   ① 下载被截断：文件小了一截，dex 头仍在 —— ART 有时能接受它并加载一部分类，
 *      于是表现成"插件能打开、某个按钮点了没反应"。
 *   ② 中间人 / 镜像回源错误：拿到的是另一个版本甚至另一个插件。
 *   ③ 元数据与包不匹配：服务端发版时 apk 与 xml 没同步更新。
 *
 * 三种都只能靠"字节级核对"发现。所以本类的规则是：
 * **校验失败一律拒绝加载**，且错误信息要写清楚"期望什么、实际什么、差多少"。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  三、线程与超时（与本仓库既有收口的关系）
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 下载走 [NetClient.downloads]（下载档客户端：长超时、不重试 —— 大文件重下不划算），
 * 并提交到 [ThreadPools.network] 泳道（网络任务归网络泳道，不新起裸线程）。
 * 这是本仓库两条既有纪律，本类只是遵守，没有新建管道。
 *
 * 本方法**同步等待**下载完成（页面点一下 → 拿到结果 → 展示日志）。
 * 之所以能同步，是因为调用它的页面把整件事放在后台线程里做，
 * 而不是因为它"很快" —— 元数据里特意记了 bytes 与耗时，不让人以为下载是零成本的。
 */
object PluginLoader {

    private const val TAG = "PluginLoader"

    /** 内嵌在宿主 assets 里的插件包文件名（由 tools/plugin-package/package.sh 决定） */
    const val EMBEDDED_APK = "plugin-demo-debug.apk"
    const val EMBEDDED_XML = "plugin-package.xml"

    enum class Source(val title: String) {
        ASSETS("assets 内嵌（离线兜底）"),
        PRIVATE_DIR("宿主私有目录（adb push / 可替换）"),
        NETWORK("网络接口下载（HTTPS + sha256 校验）"),
    }

    data class Options(
        val source: Source,
        /** 仅 [Source.NETWORK] 用。指向 plugin-package.xml（apk 地址由元数据里的约定推导）。 */
        val manifestUrl: String = "",
        /** 仅 [Source.NETWORK] 用。apk 的 URL；留空则用 manifestUrl 同目录下的约定名。 */
        val apkUrl: String = "",
        val strategy: PluginResources.Strategy = PluginResources.Strategy.ISOLATED_PLUGIN_RESOURCES,
    )

    /**
     * 装载插件。**失败即抛**，且异常 message 面向人（页面直接展示，不进 Logcat 就完）。
     *
     * 调用方负责放到后台线程（见 [prepareOnBackground]）。
     */
    fun load(context: Context, options: Options): LoadedPlugin {
        val apk: File
        val manifest: PluginManifest?
        /** 从网络接口取回的原始报文（plugin-package.xml 文本）。离线来源为 null。 */
        var networkPayload: String? = null
        val prepLog = StringBuilder()

        when (options.source) {
            Source.ASSETS -> {
                prepLog.append("── 来源：assets 内嵌 ──\n")
                apk = stageFromAssets(context, prepLog)
                manifest = readEmbeddedManifest(context, prepLog)
            }

            Source.PRIVATE_DIR -> {
                prepLog.append("── 来源：私有目录 ──\n")
                apk = locateInPrivateDir(context, prepLog)
                manifest = readManifestBeside(apk, prepLog)
            }

            Source.NETWORK -> {
                prepLog.append("── 来源：网络接口 ──\n")
                val pair = fetchFromNetwork(context, options, prepLog)
                apk = pair.first
                manifest = pair.second
                networkPayload = pair.third
            }
        }

        return loadFromFile(context, apk, manifest, networkPayload, options.strategy, prepLog)
    }

    // ═══════════════════════════════════════════════════════════════
    //  来源 1：assets 内嵌
    // ═══════════════════════════════════════════════════════════════

    /**
     * 把 assets 里的插件包拷贝到私有目录再加载。
     *
     * ─── 为什么不直接加载 assets 里的文件 ───
     *
     * 这个问题值得写清楚，因为它几乎每个做插件化的人都会问一次：
     *
     *   · `DexClassLoader` 的 dexPath 需要**真实文件路径**，它内部要 mmap。
     *     assets 里的 apk 是**打包在宿主 apk 内部**的一个条目，没有独立路径；
     *     取它的唯一方式是从 `assets.open()` 读出字节，那就已经是"拷贝"了。
     *   · `AssetManager.addAssetPath` 同理要路径。
     *   · 更实际的一点：把内嵌包解出来之后，它就与"私有目录来源"共用同一条
     *     装载路径，也就共享了同一份校验与同一份缓存目录 —— 少一条分支。
     *
     * 拷贝策略：**按 sha 决定是否重写**。宿主升级后内嵌包会变，
     * 所以不能"存在就跳过"；但每次启动都重写 16KB 也无所谓。
     * 这里选了"hash 不同才写"，因为它是唯一不会出现"新宿主用着旧插件包"的做法。
     */
    private fun stageFromAssets(context: Context, log: StringBuilder): File {
        val dir = File(context.filesDir, "plugin/assets").apply { mkdirs() }
        val out = File(dir, EMBEDDED_APK)
        val embedded = context.assets.open(EMBEDDED_APK)
        val embeddedHash = embedded.use { PluginDigest.sha256(it) }
        log.append("· assets 内嵌包 : ").append(EMBEDDED_APK).append('\n')
        log.append("  sha256        : ").append(PluginDigest.short(embeddedHash)).append('\n')

        if (out.exists() && PluginDigest.sha256(out) == embeddedHash) {
            log.append("· 已存在且字节一致，跳过拷贝 : ").append(out.absolutePath).append('\n')
            return out
        }
        context.assets.open(EMBEDDED_APK).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        log.append("· 已拷出到私有目录 : ").append(out.absolutePath)
            .append("  (").append(out.length()).append(" bytes)").append('\n')
        return out
    }

    private fun readEmbeddedManifest(context: Context, log: StringBuilder): PluginManifest? = try {
        context.assets.open(EMBEDDED_XML).use { PluginManifest.parse(it) }
            .also { log.append("· 内嵌元数据已读取 : id=").append(it.id).append('\n') }
    } catch (t: Throwable) {
        log.append("⚠️ 读不到内嵌元数据（不影响加载，只是少了入口类名的来源）：")
            .append(t.javaClass.simpleName).append('\n')
        null
    }

    // ═══════════════════════════════════════════════════════════════
    //  来源 2：私有目录
    // ═══════════════════════════════════════════════════════════════

    /**
     * 在几个候选位置找插件包。**列出全部候选**是因为"文件放哪了"是这类实验里
     * 最高频的沟通成本：页面直接把候选路径连"存在与否"一起打出来，省掉一轮猜。
     */
    private fun locateInPrivateDir(context: Context, log: StringBuilder): File {
        val candidates = listOf(
            File(context.getExternalFilesDir(null), EMBEDDED_APK),
            File(context.filesDir, "plugin/$EMBEDDED_APK"),
            File(context.cacheDir, EMBEDDED_APK),
        )
        candidates.forEach {
            log.append("· 候选 ").append(if (it.exists()) "✅ " else "❌ ")
                .append(it.absolutePath)
                .append(if (it.exists()) "  (${it.length()} bytes)" else "").append('\n')
        }
        val found = candidates.firstOrNull { it.exists() && it.length() > 0 }
            ?: error(
                "私有目录里没有插件包。用它推一个进去：\n" +
                    "    tools/plugin-package/deploy.sh\n" +
                    "候选路径（都已检查，见上方列表）：\n" +
                    candidates.joinToString("\n") { "    " + it.absolutePath }
            )
        log.append("· 选中 : ").append(found.absolutePath).append('\n')
        return found
    }

    private fun readManifestBeside(apk: File, log: StringBuilder): PluginManifest? {
        val xml = File(apk.parentFile, EMBEDDED_XML)
        return try {
            if (xml.exists()) {
                xml.inputStream().use { PluginManifest.parse(it) }.also {
                    log.append("· 同目录元数据已读取 : ").append(xml.name).append('\n')
                }
            } else {
                log.append("· 同目录无元数据（").append(xml.absolutePath)
                    .append("），将使用内置默认入口类名\n")
                null
            }
        } catch (t: Throwable) {
            log.append("⚠️ 元数据解析失败：").append(t.javaClass.simpleName)
                .append(": ").append(t.message).append('\n')
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  来源 3：网络接口
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从 HTTPS 取元数据 → 取 apk → **按元数据核验 sha256** → 落盘。
     *
     * 返回 (apk 文件, 元数据, 元数据原文)。返回原文是为了让插件界面能展示
     * "宿主到底从接口拿到了什么" —— 那是这条链路的可核对产物。
     *
     * 取回顺序（元数据在前）不是随便定的：
     *   ① 元数据里带 size 与 sha256 ⇒ 下载 apk 时就知道"应该多大"；
     *   ② 元数据里带 minSdk ⇒ 可以在**下载之前**就拒绝不适合本机的包，
     *      省掉一次无用的大文件传输（移动网络下这是真实成本）。
     */
    private fun fetchFromNetwork(
        context: Context,
        options: Options,
        log: StringBuilder,
    ): Triple<File, PluginManifest, String> {
        require(options.manifestUrl.startsWith("https://")) {
            "只接受 https:// 的插件接口地址（当前：'${options.manifestUrl}'）。\n" +
                "理由是插件包会被加载成代码 —— 明文 http 下这条链路可被替换，" +
                "sha256 校验也就失去意义（攻击者可以同时改 apk 与元数据）。"
        }
        val manifestBytes = httpGetBytes(options.manifestUrl)
        val manifest = PluginManifest.parse(manifestBytes.inputStream())
        log.append("· 元数据解析成功 : id=").append(manifest.id)
            .append(" version=").append(manifest.version).append('\n')
        log.append("  期望大小      : ").append(manifest.size).append(" bytes\n")
        log.append("  期望 sha256   : ").append(PluginDigest.short(manifest.sha256)).append('\n')
        log.append("  minSdk        : ").append(manifest.minSdk).append('\n')

        val sdk = android.os.Build.VERSION.SDK_INT
        require(manifest.minSdk <= sdk) {
            "插件要求 minSdk=${manifest.minSdk}，本机是 API $sdk —— 在下载前就拒绝，" +
                "避免「下完才发现不能用」（移动网络下那是一次真实的流量成本）。"
        }

        val apkUrl = options.apkUrl.ifBlank {
            options.manifestUrl.substringBeforeLast('/') + "/" + EMBEDDED_APK
        }
        val dir = File(context.filesDir, "plugin/net").apply { mkdirs() }
        val tmp = File(dir, "download.tmp")
        val target = File(dir, EMBEDDED_APK)
        val startedAt = android.os.SystemClock.elapsedRealtime()

        val bytes = httpGetToFile(apkUrl, tmp, log)
        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
        log.append("· 下载完成       : ").append(apkUrl).append('\n')
        log.append("  ").append(bytes).append(" bytes / ").append(elapsed).append(" ms")
            .append("  (").append(if (elapsed > 0) bytes * 1000 / elapsed / 1024 else 0)
            .append(" KB/s)").append('\n')

        // ── 字节级核对：这是整条网络链路的验收线 ──
        val actual = PluginDigest.sha256(tmp)
        if (actual != manifest.sha256) {
            tmp.delete()
            error(
                "❌ sha256 不匹配，拒绝加载（已删除临时文件）：\n" +
                    "  期望 : ${manifest.sha256}\n" +
                    "  实际 : $actual\n" +
                    "  大小 : 期望 ${manifest.size} / 实际 $bytes\n" +
                    "常见原因：下载被截断、服务端 apk 与 xml 版本不同步、被中间人替换。\n" +
                    "⚠️ 这里刻意不做「校验失败就用上次的缓存」这种兜底 —— " +
                    "那会让「服务端发错包」变成静默行为，排查时看不到任何异常。"
            )
        }
        log.append("· ✅ sha256 校验通过 : ").append(PluginDigest.short(actual)).append('\n')
        if (target.exists()) target.delete()
        tmp.renameTo(target)
        // 元数据也留在旁边，下次用私有目录来源时能读到同一份契约
        val payloadText = String(manifestBytes, Charsets.UTF_8)
        File(dir, EMBEDDED_XML).writeText(payloadText)
        return Triple(target, manifest, payloadText)
    }

    private fun httpGetBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        NetClient.downloads.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} ${resp.message} —— $url")
            val body = resp.body ?: error("响应体为空：$url")
            return body.bytes()
        }
    }

    /**
     * 同步 GET 到文件。返回字节数。
     *
     * 线程模型：同步阻塞，所以**必须**跑在 [ThreadPools.network] 泳道里 ——
     * 见 [prepareOnBackground]。使用 [NetClient.downloads]（下载档：长超时、不重试）。
     */
    private fun httpGetToFile(url: String, dest: File, log: StringBuilder): Long {
        val request = Request.Builder().url(url).get().build()
        NetClient.downloads.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} ${resp.message} —— $url")
            val body = resp.body ?: error("响应体为空：$url")
            body.byteStream().use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            return dest.length()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  装载（三条来源共用，这里才是"插件化"本身）
    // ═══════════════════════════════════════════════════════════════

    private fun loadFromFile(
        context: Context,
        apk: File,
        manifest: PluginManifest?,
        networkPayload: String?,
        strategy: PluginResources.Strategy,
        prepLog: StringBuilder,
    ): LoadedPlugin {
        require(apk.exists() && apk.length() > 0) { "插件包不存在或为空：${apk.absolutePath}" }

        val log = PluginLoadLog()
        log.add("══════════ 插件装载报告 ══════════")
        log.add("apk     : ${apk.absolutePath}  (${apk.length()} bytes)")
        log.add("sha256  : ${PluginDigest.short(PluginDigest.sha256(apk))}")
        log.add(prepLog.toString().trimEnd())

        // dex 的优化产物（odex/vdex）放 codeCacheDir 而不是 filesDir：
        // Android 会在磁盘紧张时**自动清理** codeCacheDir，而优化产物正是
        // "删了也能重建"的纯缓存 —— 语义刚好对上。
        val dexDir = File(context.codeCacheDir, "plugin-opt").apply { mkdirs() }

        log.section("① 类加载")
        val classLoader = PluginClassLoader(
            dexFile = apk,
            optimizedDir = dexDir,
            // 插件不带 native 库（零依赖的直接结果），librarySearchPath 传 null。
            // 真实插件若带 .so，正确做法**不是**在这里给个路径就完事：
            // libs 目录受 Android 的 linker namespace 限制，通常还要配合显式
            // System.load / System.loadLibrary 与 nativeLibraryDir 的处理。
            librarySearchPath = null,
            hostLoader = context.classLoader,
        )
        log.add(classLoader.describe())

        log.section("② 资源加载（策略=$strategy）")
        val loaded = PluginResources.load(context, apk, strategy)
        log.add(loaded.buildLog.trimEnd())
        log.add("· " + PluginResources.hostAssetManagerIdentity(context.resources))

        // 资源与 ClassLoader 都就绪之后**一次性**构造 —— 不把 resources 做成可空字段。
        return LoadedPlugin(
            apkFile = apk,
            dexDir = dexDir,
            classLoader = classLoader,
            resources = loaded.resources,
            assets = loaded.assets,
            strategy = strategy,
            manifest = manifest,
            networkPayload = networkPayload,
            log = log,
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //  后台执行
    // ═══════════════════════════════════════════════════════════════

    /**
     * 把 [load] 放到 [ThreadPools.network] 泳道上跑，同步等结果。
     *
     * 为什么不用 coroutines：本仓库没有 coroutines 依赖（见 gradle/libs.versions.toml），
     * 而 ThreadPools 已经提供了「命名 / 配额 / 异常上报」三件事。
     * 为了这一个调用引入 coroutines 是**为了写法好看而增加依赖**，不划算。
     *
     * 为什么"同步等"而不是回调：调用点是页面上的一个按钮，用户需要的是
     * **一条完整的、有顺序的日志**（加载过程本身就是这个 Lab 的产出物）。
     * 回调会把日志拆散到多个线程上下文里，反而更难看。
     * 前提是调用方自己在后台线程上 —— 本方法内部把整件事提交给泳道，因此满足。
     *
     * ⚠️ 本方法会**阻塞调用线程**（最多 [timeoutSeconds] 秒）。
     *    页面必须从主线程之外调用它。反过来说，这也是件好事：
     *    "加载插件"这件事本来就不该在主线程上发生。
     */
    fun prepareOnBackground(
        context: Context,
        options: Options,
        timeoutSeconds: Long = 60,
    ): kotlin.Result<LoadedPlugin> {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<kotlin.Result<LoadedPlugin>>()
        val accepted = ThreadPools.network.execute("PluginLoader") {
            holder.set(runCatching { load(context, options) })
            latch.countDown()
        }
        // ⚠️ 泳道可能因配额/队列满而拒绝（见 ThreadPools.Lane.execute 的返回值）。
        //    这里必须处理：否则 latch 永远不会 countDown，页面就永久卡在"加载中" ——
        //    那是这类实验页最容易出现的假死。返回值检查是最低成本的防御。
        if (!accepted) {
            return kotlin.Result.failure(
                IOException("网络泳道拒绝了本次任务（配额超限或队列已满）。稍后重试，或先看线程治理页的泳道状态。")
            )
        }
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            return kotlin.Result.failure(
                IOException("等待超时（${timeoutSeconds}s）。可能是网络不可达或接口未响应 —— 见上方日志。")
            )
        }
        return holder.get() ?: kotlin.Result.failure(IllegalStateException("任务结束但没有结果"))
    }

    /** 供页面与文档引用的常量集合 */
    fun defaultPluginId() = "com.interview.plugindemo"
}
