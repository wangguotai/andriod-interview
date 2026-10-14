package com.interview.plugin

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import com.interview.thread.ThreadPools

/**
 * Time: 2026/10/13
 * Author: wgt
 * Description: 宿主侧「插件化 Lab」—— 独立 dex / 资源隔离 / 网络下发的可运行演示宿主。
 *
 * ─── 每个按钮对应一条可证伪的判断（沿用本仓库其它 Lab 的设计原则）───
 *
 *  ① assets 加载        → 完全离线时这套机制能不能跑（演示与 CI 的基线）
 *  ② 私有目录加载       → 同一份宿主换一个插件包，行为是否跟着变
 *  ③ 网络下载并加载     → 宿主怎么知道加载什么、以及怎么确认字节是对的
 *  ④ 拉起插件 Activity  → 那条"更脆"的路到底脆在哪（token / 生命周期）
 *  A~E 对照实验         → 见 [PluginExperiments]，每条都带否定证据
 *  资源策略切换         → 证明「隔离」是我们**选**的，不是碰巧得到的
 *
 * ─── 线程纪律（本仓库既有约定，这里只是遵守）───
 *
 * 加载（含网络下载与 dex 优化）**全部**在 [ThreadPools.network] 泳道上做，
 * 主线程只负责画界面。这不是"优化"，是必须：
 *   · DexClassLoader 首次加载要写 odex/vdex，是磁盘 IO；
 *   · 网络下载是几十 KB 的读。
 * 任何一件放主线程上都会形成可感知的卡顿。
 */
class PluginLabActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private lateinit var outputScroll: ScrollView
    private lateinit var container: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var strategyButton: Button

    private val log = StringBuilder()
    private var ready: LoadedPlugin? = null
    private var pluginActivity: Activity? = null

    /** 当前资源策略。默认**隔离**；另一条路只作对照，见 [PluginResources.Strategy]。 */
    private var strategy = PluginResources.Strategy.ISOLATED_PLUGIN_RESOURCES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugin_lab)

        output = findViewById(R.id.tv_output)
        outputScroll = findViewById(R.id.sv_output)
        container = findViewById(R.id.plugin_container)
        urlInput = findViewById(R.id.et_manifest_url)
        strategyButton = findViewById(R.id.btn_strategy_toggle)

        emit(PluginExperiments.header("插件化 Lab", "插件 id = ${PluginLoader.defaultPluginId()}"))
        emit(
            "宿主里**没有**插件的任何字节。三条可外部核对的命令：\n" +
                "  unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -i plugindemo  → 应为空\n" +
                "  dexdump -f app-debug.apk | grep Lcom/interview/plugindemo                → 应为空\n" +
                "  （第二条最硬：它证明宿主 dex 里没有插件的类符号）\n\n" +
                "打包插件：tools/plugin-package/package.sh\n" +
                "推到设备：tools/plugin-package/deploy.sh（走 ② 私有目录加载）"
        )

        bind(R.id.btn_load_assets) { doLoad(PluginLoader.Source.ASSETS) }
        bind(R.id.btn_load_dir) { doLoad(PluginLoader.Source.PRIVATE_DIR) }
        bind(R.id.btn_load_net) { doLoad(PluginLoader.Source.NETWORK) }
        bind(R.id.btn_launch_activity) { launchPluginActivity() }

        bind(R.id.btn_exp_proxy) {
            runExperiment { p, host -> PluginExperiments.experimentProxyAcrossLoaders(p, host) }
        }
        bind(R.id.btn_exp_token) {
            runExperiment { _, host -> PluginExperiments.experimentActivityToken(pluginActivity, host) }
        }
        bind(R.id.btn_exp_reload) {
            runExperiment { p, _ -> PluginExperiments.experimentReload(p, this) }
        }
        bind(R.id.btn_exp_host) {
            emit("\n" + PluginExperiments.experimentHostHasNoPluginClass(this))
        }
        bind(R.id.btn_exp_res) {
            runExperiment { p, _ ->
                PluginExperiments.experimentResourceIsolation(this, p, p.assets)
            }
        }

        bind(R.id.btn_strategy_toggle) { toggleStrategy() }
        bind(R.id.btn_clear_plugin) { clearPlugin() }
        bind(R.id.btn_clear_log) { log.setLength(0); output.text = "" }
    }

    override fun onDestroy() {
        // ⚠️ 退出页面时释放插件。这是**纪律**而不是可选项：
        //    插件持有 Context / View，可能还有静态引用；不释放会让插件侧对象
        //    存活到进程结束 —— 这个 Lab 本身就会因此变成"内存泄漏演示"。
        //    注意这是 release()（断引用），**不是**卸载类 —— 见 LoadedPlugin 注释。
        releasePluginQuietly()
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════════
    //  装载
    // ═══════════════════════════════════════════════════════════════

    private fun doLoad(source: PluginLoader.Source) {
        // 先释放旧的：否则反复点会把多个 ClassLoader 叠加起来，每个都带着
        // 一份插件 dex —— 那不是"演示插件化"，那是"演示泄漏"。
        releasePluginQuietly()

        val options = PluginLoader.Options(
            source = source,
            manifestUrl = urlInput.text?.toString()?.trim().orEmpty(),
            strategy = strategy,
        )
        emit("\n>>> 开始装载：${source.title}  策略=$strategy")
        emit("（在 ${ThreadPools.network.name} 泳道执行 —— 加载过程本身就是本 Lab 的产出物）")

        val accepted = ThreadPools.network.execute(CALLER_LOAD) {
            val started = SystemClock.elapsedRealtime()
            val result = PluginLoader.prepareOnBackground(applicationContext, options)
            val elapsed = SystemClock.elapsedRealtime() - started
            runOnUiThread {
                result.onSuccess { plugin ->
                    ready = plugin
                    emit(plugin.logText())
                    emit(">>> 装载成功（含下载与 dex 优化，共 ${elapsed} ms）")
                    emit(">>> 插件自检：\n" + plugin.selfCheckAssets())
                    mountPage(plugin)
                }.onFailure { t ->
                    emit(">>> 装载失败（${elapsed} ms）")
                    emit(t.message ?: t.toString())
                    emit(
                        "\n排查入口：\n" +
                            "  ① assets 那条是离线兜底。它失败 ⇒ 插件包没打出来，先跑 tools/plugin-package/package.sh\n" +
                            "  ② 私有目录失败 ⇒ 先跑 tools/plugin-package/deploy.sh\n" +
                            "  ③ 网络那条要求 https，且元数据里的 sha256 必须与 apk 字节一致"
                    )
                }
            }
        }
        // 泳道可能因配额/队列满拒绝 —— 必须处理，否则页面看起来"点了没反应"。
        if (!accepted) {
            emit("⚠️ 网络泳道拒绝了本次任务（配额超限或队列已满）。稍后重试，或先看线程治理页。")
        }
    }

    /** 控制器形态：宿主给容器，插件把界面挂进来 */
    private fun mountPage(plugin: LoadedPlugin) {
        container.removeAllViews()
        try {
            plugin.instantiatePage(this, container, plugin.networkPayload)

            emit("\n── 资源归属对照（逐 ID，ID 表由插件自带）──")
            emit("· 宿主 Resources identity = ${System.identityHashCode(resources)}")
            emit("· 插件 Resources identity = ${System.identityHashCode(plugin.resources)}")
            emit("· 两者是否同一对象        = ${PluginResources.sameInstance(resources, plugin.resources)}")
            val ids = PluginAssets.parseResIds(plugin.assets)
            if (ids.isEmpty()) {
                emit("  （插件包里没有 plugin-res-ids.txt —— 重新跑一次 package.sh）")
            }
            ids.forEach { item ->
                emit(
                    PluginResources.probeIsolation(resources, plugin.resources, item.id)
                        .replaceFirst("· 0x", "· ${item.type}/${item.name} 0x")
                )
            }
        } catch (t: Throwable) {
            emit("❌ 挂载插件界面失败：${t.javaClass.name}: ${t.message}")
            emit(t.stackTraceToString().lineSequence().take(12).joinToString("\n"))
        }
    }

    /** ④ Activity 形态 */
    private fun launchPluginActivity() {
        val plugin = ready ?: run { emit("先装载一个插件（①②③ 任一）"); return }
        emit("\n>>> 拉起插件 Activity（Instrumentation.newActivity + 反射 attach + performCreate）")
        try {
            pluginActivity = plugin.attachActivity(this, intent, container)
            emit(">>> 插件 Activity 已挂到宿主容器：${pluginActivity?.javaClass?.name}")
            emit(plugin.logText())
            emit("\n接着点「B token」看它与真 Activity 的**结构性**差别。")
        } catch (t: Throwable) {
            emit("❌ Activity 形态失败：${t.javaClass.name}")
            emit(t.message ?: "")
            emit(
                "\n👉 这正是本演示要展示的：Activity 形态的成本不在类加载，在**生命周期接管**。\n" +
                    "   控制器形态（①②③ 自动走的那条）不依赖 attach，因此在所有版本都能跑。"
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  对照实验与状态
    // ═══════════════════════════════════════════════════════════════

    private fun runExperiment(block: (LoadedPlugin, Activity) -> String) {
        val plugin = ready ?: run { emit("先装载一个插件，再跑实验"); return }
        try {
            emit("\n" + block(plugin, this))
        } catch (t: Throwable) {
            emit("❌ 实验失败：${t.javaClass.name}: ${t.message}")
        }
    }

    private fun toggleStrategy() {
        strategy = when (strategy) {
            PluginResources.Strategy.ISOLATED_PLUGIN_RESOURCES ->
                PluginResources.Strategy.SHARED_HOST_RESOURCES

            PluginResources.Strategy.SHARED_HOST_RESOURCES ->
                PluginResources.Strategy.ISOLATED_PLUGIN_RESOURCES
        }
        strategyButton.text = when (strategy) {
            PluginResources.Strategy.ISOLATED_PLUGIN_RESOURCES ->
                "切换资源策略：当前=隔离（点击换成「合并到宿主」作对照）"

            PluginResources.Strategy.SHARED_HOST_RESOURCES ->
                "⚠️ 当前=合并到宿主（点回隔离）"
        }
        emit(
            "\n资源策略已切换为：$strategy\n" +
                "  · ISOLATED : 插件有独立的 AssetManager/Resources，宿主表不受污染（推荐）\n" +
                "  · SHARED   : 把插件 apk 追加到**宿主的** AssetManager 上 —— 仅作对照。\n" +
                "    切到 SHARED 后重新装载，再看「E 资源」：宿主表会开始认插件的 ID，\n" +
                "    那一刻「隔离」就从「天然性质」变成了「我们刚刚放弃的一项选择」。\n" +
                "    ⚠️ SHARED 之后即使切回来，宿主的 AssetManager 上已经挂着插件包了\n" +
                "    （addAssetPath 不可撤销）。要么重启进程，要么接受这个污染。\n" +
                "    这条限制本身就是「不要在生产里用 SHARED」的理由之一。"
        )
    }

    private fun clearPlugin() {
        emit("\n>>> 清除插件")
        releasePluginQuietly()
        emit(
            "· 已断开：插件实例 onDestroy / performDestroy / ClassLoader 引用置空\n" +
                "⚠️ 这不是「卸载类」。ART 对同一个 dex 路径有内部缓存，已加载的 Class\n" +
                "   对象在进程存活期间可能仍被持有，dex 也可能仍被 mmap。\n" +
                "   所以「清除」保证的是：**下次装载会完整重跑一遍流程**（实验 C 会证明\n" +
                "   identity 每次都变），而不是「内存里那份立刻消失」。\n" +
                "   想要真正隔离，唯一可靠的做法是另起进程。"
        )
    }

    private fun releasePluginQuietly() {
        try {
            ready?.destroyActivity()
            pluginActivity = null
            ready?.release()
        } catch (t: Throwable) {
            emit("⚠️ 释放插件时异常：${t.javaClass.simpleName}: ${t.message}")
        } finally {
            ready = null
            container.removeAllViews()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  小工具
    // ═══════════════════════════════════════════════════════════════

    private fun emit(text: String) {
        log.append(text).append('\n')
        output.text = log.toString()
        // 自动滚到底：日志是本页的主要产出，停在顶部等于看不到结果。
        // 用 post 是因为文本刚刚被替换，测量尚未完成 —— 直接 scrollTo 会滚到旧高度。
        outputScroll.post { outputScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun bind(id: Int, action: () -> Unit) {
        findViewById<Button>(id).setOnClickListener {
            try {
                action()
            } catch (t: Throwable) {
                emit("❌ 按钮执行异常：${t.javaClass.name}: ${t.message}")
                Toast.makeText(this, t.message ?: "出错", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object {
        /** 调用方标识：ThreadPools 的配额与归因用它。写清楚比"某处"强。 */
        const val CALLER_LOAD = "PluginLab.load"
    }
}
