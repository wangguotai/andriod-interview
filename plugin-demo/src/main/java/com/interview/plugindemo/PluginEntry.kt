package com.interview.plugindemo

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.interview.plugin.contract.IPluginPage
import java.io.FileInputStream

/**
 * Time: 2026/10/13
 * Author: wgt
 * Description: 插件侧入口实现 —— 「控制器形态」。
 *
 * ─── 这个类是本演示的"物证" ───
 *
 * 它的全限定名 `com.interview.plugindemo.PluginEntry` **只存在于插件 dex 里**。
 * 宿主源码里没有任何一行 import 它、没有任何一行 new 它 —— 宿主只知道一个字符串。
 * 这就是「不进主 App」在代码层面的样子。
 *
 * ─── 它演示四条链路，每条都能单独证伪 ───
 *
 * ① 类：由 DexClassLoader 加载进来（`describe()` 里打出加载器身份与 dex 出处）
 * ② 资源：`R.layout.plugin_page` / `R.string.*` 是**插件自己的**资源 ID，
 *          宿主解析同一个 ID 必然 Resources$NotFoundException
 * ③ assets：`plugin-hello.txt` 只在插件 APK 的 assets/ 里，宿主 APK 里没有它
 * ④ 网络：报文由宿主取回后**传进来**（插件自己没有网络能力，也没有权限），
 *          于是"网络接口"这条链路与"插件零依赖"不冲突
 *
 * ⚠️ 必须用 [pluginContext]（插件资源）而不是其他 Context 去 getString / inflate。
 *    这是"资源隔离"落到代码上的样子：插件**没有**宿主的 Resources 可用。
 */
class PluginEntry : IPluginPage {

    private val log = StringBuilder()
    private var logTarget: TextView? = null

    override fun onCreate(pluginContext: Context, container: ViewGroup, networkPayload: String?) {
        val res: Resources = pluginContext.resources

        // ── ① assets：从插件 APK 的 assets/ 读 ──
        // 这一步检验宿主构造的 AssetManager 确实 addAssetPath 到了插件包 ——
        // 宿主 apk 里没有这个文件，所以"读到了"本身就证明资源来自插件。
        // ⚠️ open() 的路径**不带** assets/ 前缀：那条前缀是隐式的。
        //    写成 "assets/plugin-hello.txt" 会去找 assets/assets/...，必然失败。
        val assetText = try {
            pluginContext.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            "【assets 读取失败】${t.javaClass.simpleName}: ${t.message}"
        }

        // ── ①b 包根命名空间：同一个 AssetManager，换一个 API 才读得到 ──
        // openNonAssetFd 是公开 API，但它要求该 zip 条目**未被压缩**（走 mmap）。
        // 打包脚本用 `zip -0` 单独写这个文件就是为了它。这条与上一条并排展示，
        // 是为了让"AssetManager 有两个命名空间"变成一个看得见的事实。
        val rootText = try {
            pluginContext.assets.openNonAssetFd(ROOT_PROBE_NAME).use { fd ->
                FileInputStream(fd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
            }
        } catch (t: Throwable) {
            "【包根读取失败】${t.javaClass.simpleName}: ${t.message}"
        }

        // ── ② 资源：inflate 插件自己的布局 ──
        // 这是「资源隔离」最直接的证据：inflate 会按 ID 去 resources 表里查 layout，
        // 只有插件的 Resources 认得这个 ID。
        val (inflatedView, inflateError) = try {
            LayoutInflater.from(pluginContext).inflate(R.layout.plugin_page, container, false) to null
        } catch (t: Throwable) {
            null to "【inflate 失败】${t.javaClass.simpleName}: ${t.message}"
        }

        val root = LinearLayout(pluginContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(res, 12), dp(res, 12), dp(res, 12), dp(res, 12))
        }
        root.addView(buildHeader(pluginContext))
        root.addView(sectionTitle(pluginContext, "② 插件 assets 与包根（宿主 apk 里都没有这两个文件）"))
        root.addView(mono(pluginContext, "[assets 命名空间] open(\"$ASSET_NAME\")\n$assetText\n\n" +
            "[包根命名空间] openNonAssetFd(\"$ROOT_PROBE_NAME\")\n$rootText"))

        root.addView(sectionTitle(pluginContext, "③ 插件 resources/ 里的布局"))
        if (inflatedView != null) {
            root.addView(TextView(pluginContext).apply {
                text = res.getString(R.string.plugin_from_layout)
                textSize = 11f
            })
            root.addView(inflatedView)
        } else {
            root.addView(mono(pluginContext, inflateError ?: "（未知失败）"))
        }

        root.addView(sectionTitle(pluginContext, "④ 网络接口回传（宿主取回后传入）"))
        root.addView(
            mono(
                pluginContext,
                networkPayload ?: "（本次无报文：从 assets 离线兜底启动，宿主没联网）\n" +
                    "在宿主页点「从网络下载插件」会真实发起一次 HTTPS 请求，报文会出现在这里。"
            )
        )

        root.addView(sectionTitle(pluginContext, "⑤ 插件自证（下列答案宿主里都没有）"))
        root.addView(mono(pluginContext, describe()))

        val interactive = TextView(pluginContext).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        logTarget = interactive
        root.addView(
            Button(pluginContext).apply {
                text = "点我：证明插件代码在跑"
                setOnClickListener {
                    log.append("· 点击 ${System.currentTimeMillis()}，线程=${Thread.currentThread().name}\n")
                    logTarget?.text = log.toString()
                }
            }
        )
        root.addView(interactive)

        container.addView(ScrollView(pluginContext).apply { addView(root) })
    }

    override fun onDestroy() {
        logTarget = null
    }

    override fun describe(): String {
        val selfLoader = javaClass.classLoader
        val apiLoader = IPluginPage::class.java.classLoader
        return buildString {
            append("· 实现类            : ").append(javaClass.name).append('\n')
            append("· 本类的加载器      : ").append(selfLoader?.javaClass?.name)
            append("  identity=").append(System.identityHashCode(selfLoader)).append('\n')
            append("· 共享接口的加载器  : ").append(apiLoader?.javaClass?.name)
            append("  identity=").append(System.identityHashCode(apiLoader)).append('\n')
            append("· 两者是否同一加载器: ")
            append(
                if (selfLoader === apiLoader) "是（接口被委派给了宿主，正常）"
                else "否 ⚠️ 会出现 ClassCastException"
            ).append('\n')
            append("· 本类 dex 出处     : ")
            append(
                javaClass.protectionDomain?.codeSource?.location?.path
                    ?.substringAfterLast('/') ?: "未知"
            ).append('\n')
            append("· kotlin.Unit 出处  : ")
            append(kotlin.Unit::class.java.classLoader?.javaClass?.simpleName)
            append("  identity=").append(System.identityHashCode(kotlin.Unit::class.java.classLoader))
        }
    }

    // ─────────────── 小工具（刻意手写，不用任何三方库）───────────────

    private fun buildHeader(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(ctx).apply {
            // R.string / R.drawable 全部来自**插件**的 res/
            text = ctx.resources.getString(R.string.plugin_title)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundResource(R.drawable.plugin_badge)
            setPadding(dp(ctx.resources, 10), dp(ctx.resources, 10), dp(ctx.resources, 10), dp(ctx.resources, 10))
            gravity = Gravity.CENTER_VERTICAL
        })
        addView(TextView(ctx).apply {
            text = ctx.resources.getString(R.string.plugin_subtitle)
            textSize = 11f
            setPadding(0, dp(ctx.resources, 4), 0, 0)
        })
        addView(sectionTitle(ctx, "① 插件 resources（宿主解析同一 ID 必失败）"))
        addView(mono(ctx, """
            · R.string.plugin_title 的值     : 「${ctx.resources.getString(R.string.plugin_title)}」
            · 该资源来自哪个包               : ${ctx.resources.getResourcePackageName(R.string.plugin_title)}
            · 资源 ID（16 进制）             : 0x${Integer.toHexString(R.string.plugin_title)}
            · 本页 Resources identity        : ${System.identityHashCode(ctx.resources)}
        """.trimIndent()))
    }

    private fun sectionTitle(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTypeface(typeface, Typeface.BOLD)
        textSize = 14f
        setPadding(0, dp(ctx.resources, 14), 0, dp(ctx.resources, 4))
    }

    private fun mono(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    private fun dp(res: Resources, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), res.displayMetrics).toInt()

    private companion object {
        const val ASSET_NAME = "plugin-hello.txt"
        const val ROOT_PROBE_NAME = "plugin-root-probe.txt"
    }
}
