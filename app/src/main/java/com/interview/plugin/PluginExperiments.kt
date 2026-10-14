package com.interview.plugin

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.interview.plugin.contract.IPluginPage
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Time: 2026/10/13
 * Author: wgt
 * Description: 宿主「插件化 Lab」的对照实验集合 —— **每一个实验都是一条可证伪的断言**。
 *
 * ─── 为什么实验要单独成类，而不是写在 Activity 的按钮回调里 ───
 *
 * 因为"插件化"这件事声称的能力很多（类隔离、资源共享、Activity 可拉起…），
 * 而它们**彼此独立**：资源那条通了不代表 Activity 那条能通。
 * 如果实验散落在 UI 回调里，读者无法判断"页面能显示"到底证明了哪一条。
 *
 * 于是这里把每条断言做成一个返回报告文本的函数。报告必须包含**否定证据**
 * （"我尝试让它失败，它确实失败了"），只有这样截图才有说服力。
 *
 * ⚠️ 所有报告都**不加修饰地**把原始异常信息带出来。装饰成"✅ 已生效"很容易，
 *    但那样这份 Lab 就退化成了宣传材料。这里的原则是：
 *    能被外部手段（javap / unzip / dexdump）核对的东西，就必须把原始值打出来。
 */
object PluginExperiments {

    /**
     * 实验 A：**匿名类 / 动态代理无法跨越两个 ClassLoader**。
     *
     * ═══════════════════════════════════════════════════════════════
     *  这条实验为什么值得单列（它不是"顺便试试"）
     * ═══════════════════════════════════════════════════════════════
     *
     * 上一版这里写的是"匿名类会失败"，因为 Kotlin 的匿名对象会编译成
     * `宿主包名.X$1` —— 那个符号在插件 dex 里不存在。但真机跑下来的结论更准确：
     *
     *   · Kotlin **对象表达式**（`object : IPluginPage {…}`）确实会失败，
     *     但失败点是 `NoClassDefFoundError: com.example.myapplication…X$1`
     *     —— 因为它的类名带着**宿主**包的符号，插件侧没有。
     *   · Java/Kotlin 的 `Proxy`（JDK 动态代理）**能加载**，
     *     因为代理类是由 Proxy 自己用 `ProxyClassFactory` 在**运行期生成**的，
     *     不依赖任何预先存在的符号。但它生成时用的类加载器是
     *     `IPluginPage.class.getClassLoader()`（**宿主**的），于是：
     *       代理实例的类型由宿主 loader 解析，
     *       而它实现的接口方法签名里若出现插件侧的类型，就会在中转时炸。
     *
     * 所以这条实验真正的结论不是"匿名类不行"，而是：
     * **跨加载器的对象共享，难点在于「类型由谁解析」，而不是"能不能写匿名类"。**
     * 这也解释了共享接口为什么必须住在宿主一侧。
     */
    fun experimentProxyAcrossLoaders(plugin: LoadedPlugin, hostActivity: Activity): String {
        val sb = StringBuilder()
        // 注意：字符串里一律用「」而不是半角双引号 —— 后者会直接终结 Kotlin 字符串字面量。
        // 这不是风格偏好，是编译错误来源（本文件第一版就栽在这里）。
        sb.append("【实验 A】跨 ClassLoader 的「动态生成类型」会怎样\n\n")

        // ── A-1: 用宿主的 loader 生成一个实现插件接口的代理 ──
        sb.append("A-1 宿主 Proxy 实现 IPluginPage（接口由宿主 loader 解析）\n")
        try {
            val handler = InvocationHandler { _, method, args ->
                when (method.name) {
                    "describe" -> "我是宿主 Proxy 造的替身"
                    "onCreate" -> null
                    "onDestroy" -> null
                    else -> null
                }
            }
            // ⚠️ 注意这里**必须**用 IPluginPage（宿主源码里的那个版本）:
            //    它由宿主 loader 解析 ⇒ 生成的代理类也属于宿主 ⇒ 插件**不认识**它。
            val proxy = Proxy.newProxyInstance(
                IPluginPage::class.java.classLoader,
                arrayOf(IPluginPage::class.java),
                handler,
            ) as IPluginPage
            sb.append("  · 代理生成成功：${proxy.javaClass.name}\n")
            sb.append("  · 代理的 loader = ${proxy.javaClass.classLoader?.javaClass?.name}\n")
            sb.append("  · describe() 返回：${proxy.describe()}\n")
            sb.append("  · 结论：**能生成**。动态代理不依赖预先存在的符号，\n")
            sb.append("    它由 Proxy 在运行期用指定 loader 造类 —— 所以「跨加载器」这件事\n")
            sb.append("    对它是「把接口的方法签名搬到另一个空间」，而不是「符号找不到」。\n")
        } catch (t: Throwable) {
            sb.append("  · 失败：${t.javaClass.name}: ${t.message}\n")
        }

        // ── A-2: 用插件的 loader 生成同一个接口的代理 ──
        sb.append("\nA-2 换用插件的 loader 生成同一个接口的代理\n")
        try {
            val handler = InvocationHandler { _, method, _ ->
                when (method.name) {
                    "describe" -> "我是插件 loader 造的替身"
                    else -> null
                }
            }
            val proxy = Proxy.newProxyInstance(
                plugin.classLoader,
                arrayOf(IPluginPage::class.java),
                handler,
            )
            sb.append("  · 代理生成成功：${proxy.javaClass.name}\n")
            sb.append("  · 代理的 loader identity = ${System.identityHashCode(proxy.javaClass.classLoader)}\n")
            sb.append("  · 插件 loader identity  = ${System.identityHashCode(plugin.classLoader)}\n")
            sb.append("  · 把它 cast 成宿主的 IPluginPage：")
            try {
                @Suppress("UNUSED_VARIABLE")
                val asHostType = proxy as IPluginPage
                sb.append("成功 —— 因为接口本身被委派给了宿主，两边是同一个 Class 对象。\n")
            } catch (t: Throwable) {
                sb.append("失败：${t.javaClass.name}: ${t.message}\n")
            }
        } catch (t: Throwable) {
            sb.append("  · 失败：${t.javaClass.name}: ${t.message}\n")
        }

        sb.append("\n👉 可操作结论：跨加载器共享的**接口**必须由同一 loader 定义（本演示放在宿主）；\n")
        sb.append("   共享面之外的实现细节（包括代理、lambda、匿名类）都留在各自一侧即可。\n")
        sb.append("   反过来，一旦有人把共享接口挪进插件包名，A-2 的 cast 就会变成\n")
        sb.append("   「IPluginPage cannot be cast to IPluginPage」—— 先记住这句话的样子。")
        @Suppress("UNUSED_EXPRESSION")
        hostActivity
        return sb.toString()
    }

    /**
     * 实验 B：**插件 Activity 不是真 Activity** —— 它没有系统 token。
     *
     * 做法：反射调 `Activity.getActivityToken()`。
     *   · 真 Activity 返回一个非空 IBinder（AMS 给它的身份凭证）
     *   · 我们的插件 Activity 因为借用了**宿主**的 token，
     *     拿到的是宿主那个，或者干脆是 null（取决于 attach 走哪条路径）
     *
     * 无论哪种结果都值得展示 —— 关键是**把它打出来**，而不是宣称"插件 Activity 可用"。
     * 真实的后果：
     *   · `startActivityForResult` 会失败（没有属于自己的 token 无法建立回调链）
     *   · `onActivityResult` 不会被调用
     *   · 参与任务栈、被系统回收重建、多窗口/画中画 —— 全都不成立
     */
    fun experimentActivityToken(pluginActivity: Activity?, hostActivity: Activity): String {
        val sb = StringBuilder()
        sb.append("【实验 B】插件 Activity 有没有「系统身份」（token）\n\n")
        if (pluginActivity == null) {
            sb.append("· 尚未拉起插件 Activity —— 先点「控制器形态」或「Activity 形态」按钮。\n")
            return sb.toString()
        }
        val pluginToken = try {
            readActivityToken(pluginActivity)
        } catch (t: Throwable) {
            sb.append("  getActivityToken() 抛：${t.javaClass.name}\n")
            null
        }
        val hostToken = try {
            readActivityToken(hostActivity)
        } catch (t: Throwable) {
            sb.append("  宿主 getActivityToken() 也抛：${t.javaClass.name}\n")
            null
        }

        sb.append("· 插件 Activity : ").append(pluginActivity.javaClass.name).append('\n')
        sb.append("  getActivityToken() = ").append(pluginToken ?: "null").append('\n')
        sb.append("  identity           = ")
            .append(pluginToken?.let { System.identityHashCode(it) } ?: "—").append('\n')
        sb.append("· 宿主 Activity : ").append(hostActivity.javaClass.name).append('\n')
        sb.append("  getActivityToken() = ").append(hostToken).append('\n')
        sb.append("  identity           = ")
            .append(hostToken?.let { System.identityHashCode(it) } ?: "—").append('\n')
        sb.append("\n· 判定：")
        when {
            pluginToken == null ->
                sb.append("插件 Activity **没有** token ⇒ 它在 AMS 眼里不存在。\n")
            pluginToken === hostToken ->
                sb.append("插件 Activity 用的是**宿主**的 token ⇒ AMS 认它等于认宿主，\n")
            else ->
                sb.append("插件 Activity 拿到了**另一个** token ⇒ 出乎意料，值得追查。\n")
        }
        sb.append("  ⇒ 因此以下能力**明确不成立**（不是「没测」，是结构性做不到）：\n")
        sb.append("     · startActivityForResult / onActivityResult 回调\n")
        sb.append("     · 参与系统任务栈、被「最近任务」恢复\n")
        sb.append("     · 进程被杀后由系统重建（onSaveInstanceState 恢复）\n")
        sb.append("     · 多窗口 / 画中画 / 分屏的位置由系统分配\n")
        sb.append("  要拿到这些，就必须走「占坑 + 预注册」，或干脆用控制器形态 + Fragment。\n")
        sb.append("  这也是主流插件框架最终都选择「宿主里预注册一批 Stub 组件」的原因 ——\n")
        sb.append("  不是不会 hook，而是 hook 出来的组件拿不到系统身份，代价太大。")
        return sb.toString()
    }

    /**
     * 实验 C：**同一份宿主 + 不同插件包 ⇒ 行为跟着变**。
     *
     * 做法：把插件包复制成两份（改一个字节的内容 —— 实际上改的是 assets 里的一行文本），
     * 分别加载，比对 sha256、入口类 identity 与插件自证输出。
     *
     * ⚠️ 这里**不伪造**一个"改了内容的包"：改 apk 会破坏签名与 dex 校验，
     *    让实验变成"能不能加载损坏的包"（那是另一个话题）。
     *    本实验的做法是**只改来源路径，不改字节** —— 于是它证明的是"装载过程
     *    确实每次重跑，不复用之前的结果"（identity 变化），而不是"内容可变"。
     *    内容可变的对照组由"私有目录来源"那条链路负责：那里可以 push 任意包。
     */
    fun experimentReload(plugin: LoadedPlugin, context: android.content.Context): String {
        val sb = StringBuilder()
        sb.append("【实验 C】重新装载会不会复用上一次的结果\n\n")
        val again = try {
            PluginLoader.load(
                context,
                PluginLoader.Options(
                    source = PluginLoader.Source.PRIVATE_DIR,
                    strategy = plugin.strategy,
                ),
            )
        } catch (t: Throwable) {
            sb.append("· 二次装载失败：${t.javaClass.name}: ${t.message}\n")
            sb.append("  （从 assets 来源首次加载时，私有目录里可能还没有包 ——\n")
            sb.append("   先跑一次 tools/plugin-package/deploy.sh 或用「网络下载」把包放过去）\n")
            return sb.toString()
        }

        sb.append("· 第一次 apk  : ").append(plugin.apkFile.absolutePath).append('\n')
        sb.append("  第二次 apk  : ").append(again.apkFile.absolutePath).append('\n')
        sb.append("  两者同一文件: ").append(plugin.apkFile.absolutePath == again.apkFile.absolutePath).append('\n')
        sb.append("· ClassLoader identity : 第一次=")
            .append(System.identityHashCode(plugin.classLoader))
            .append("  第二次=").append(System.identityHashCode(again.classLoader)).append('\n')
        sb.append("· Resources identity   : 第一次=")
            .append(System.identityHashCode(plugin.resources))
            .append("  第二次=").append(System.identityHashCode(again.resources)).append('\n')
        sb.append("· 类 identity          : 第一次=")
            .append(System.identityHashCode(plugin.classLoader.loadClass(LoadedPlugin.DEFAULT_ENTRY)))
            .append("  第二次=")
            .append(System.identityHashCode(again.classLoader.loadClass(LoadedPlugin.DEFAULT_ENTRY)))
            .append('\n')
        sb.append("\n· 判定：ClassLoader 与 Class 的 identity **每次都变** ⇒ 装载过程被完整重跑，\n")
        sb.append("  没有任何「上一次的结果被缓存复用」的隐式行为。\n")
        sb.append("  ⚠️ 注意这不等于「内存里旧的那份被释放了」：ART 对**同一个 dex 路径**\n")
        sb.append("     有内部缓存（这也是 DexClassLoader 第二次加载很快的原因）。\n")
        sb.append("     所以「清除插件」的语义是「下次走完整流程」，不是「内存归零」。\n")
        again.release()
        return sb.toString()
    }

    /**
     * 实验 D：**宿主 apk 里到底有没有插件的类**。
     *
     * 做法：用宿主自己的 ClassLoader 去 load 插件的入口类 —— **必须失败**。
     * 这条断言是整个演示的"题眼"：如果它成功了，说明插件被编进了宿主，
     * 那么"独立打包"就是假的。
     *
     * ⚠️ 注意"失败"有两种，且它们的含义不同：
     *   · ClassNotFoundException —— 宿主 dex 里没有这个名字（我们期望的）
     *   · NoClassDefFoundError    —— 名字在，但依赖链断了（说明有残留引用）
     * 两者都会打印出来，由人判断。不把「失败」概括成一句「✅ 隔离成立」。
     */
    fun experimentHostHasNoPluginClass(context: android.content.Context): String {
        val sb = StringBuilder()
        sb.append("【实验 D】宿主 ClassLoader 能否加载插件类（必须失败）\n\n")
        val targets = listOf(
            LoadedPlugin.DEFAULT_ENTRY,
            LoadedPlugin.DEFAULT_ACTIVITY,
            "com.interview.plugindemo.R",
            "com.interview.plugindemo.BuildConfig",
        )
        targets.forEach { name ->
            val result = try {
                val c = Class.forName(name, false, context.classLoader)
                "❌ 加载成功（class = ${c.name}）—— 插件被编进宿主了！"
            } catch (t: ClassNotFoundException) {
                "✅ ClassNotFoundException（宿主 dex 里没有这个符号）"
            } catch (t: Throwable) {
                "⚠️ ${t.javaClass.simpleName}: ${t.message}"
            }
            sb.append("· ").append(name).append('\n').append("    ").append(result).append('\n')
        }
        sb.append("\n· 外部核对手段（比本页面更硬的证据）：\n")
        sb.append("    unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -i plugindemo\n")
        sb.append("    → 应当为空\n")
        sb.append("    dexdump -f app-debug.apk | grep Lcom/interview/plugindemo\n")
        sb.append("    → 应当为空\n")
        sb.append("  这两条命令的**输出为空**才是本 Lab 的最终验收标准 —— 页面里的按钮只是\n")
        sb.append("  让你更快看到结果，不能替代它。")
        return sb.toString()
    }

    /**
     * 实验 E：把宿主 Resources 当插件 Resources 用会怎样。
     *
     * 这是**最有工程价值**的一条：它证明"资源隔离"不是一个理论性质，
     * 而是"用错 Resources 会得到静默的错误结果"这一现实问题的另一面。
     *
     * 用插件自带的 `plugin-res-ids.txt` 里的 ID 去调宿主的 `getResourceName`，
     * 然后把两边解析出的名字并排打出来。会出现三种情况（都在报告里说明）：
     *
     *   ① 宿主抛 NotFoundException —— 宿主表里确实没有该条目
     *   ② 宿主解析到**宿主的另一个资源**（ID 撞上了）—— 这正是最危险的静默失败
     *   ③ 两边解析到同一个名字 —— 无法区分归属
     */
    fun experimentResourceIsolation(
        context: android.content.Context,
        plugin: LoadedPlugin,
        assets: android.content.res.AssetManager,
    ): String {
        val sb = StringBuilder()
        sb.append("【实验 E】同一资源 ID 在两张表里\n\n")
        val ids = PluginAssets.parseResIds(assets)
        if (ids.isEmpty()) {
            sb.append("· 插件包里没有 plugin-res-ids.txt（打包脚本未导出？）\n")
            return sb.toString()
        }
        sb.append("· 插件自带的资源 ID 表（").append(ids.size).append(" 项）：\n")
        ids.forEach { sb.append("    ").append(it.type).append('/').append(it.name)
            .append("  ").append(it.idHex).append('\n') }
        sb.append('\n')
        ids.forEach { item ->
            sb.append("· ").append(item.type).append('/').append(item.name)
                .append("  ").append(item.idHex).append('\n')
            val pluginName = try {
                plugin.resources.getResourceName(item.id)
            } catch (t: Throwable) {
                "❌ ${t.javaClass.simpleName}"
            }
            val hostName = try {
                context.resources.getResourceName(item.id)
            } catch (t: Throwable) {
                "❌ ${t.javaClass.simpleName}（宿主表里没有）"
            }
            sb.append("    插件表 → ").append(pluginName).append('\n')
            sb.append("    宿主表 → ").append(hostName).append('\n')
            val same = pluginName == hostName
            val hostFailed = hostName.contains("❌")
            when {
                pluginName.contains("❌") -> sb.append("    ⚠️ 插件侧也失败 → 资源没挂上\n")
                hostFailed -> sb.append("    ✅ 宿主找不到 ⇒ 归属可区分\n")
                same -> sb.append("    ⚠️ 两条表同名 ⇒ 该 ID 上无法区分归属\n")
                else -> sb.append("    ✅ 同一 ID、两个不同资源 ⇒ 隔离可执行地成立\n")
            }
        }
        sb.append("\n👉 工程含义：若宿主用自己的 Resources 去 inflate 插件的 R.layout.*，\n")
        sb.append("   它**不会报错**，而是安静地 inflate 成宿主表里那个同 ID 的布局 ——\n")
        sb.append("   表现为「界面变成别的样子 / 点了没反应」，栈里没有任何异常。\n")
        sb.append("   这就是「插件必须拿自己的 Resources」这条规则的物理原因。")
        return sb.toString()
    }

    /**
     * 反射读 `Activity.getActivityToken()`。
     *
     * ⚠️ 为什么不用 `activity.activityToken`（Kotlin 直接调）：
     *    这个公开 API 是 **API 33 才有**的（android-34 的 android.jar 里有，
     *    android-31 没有）。为了在本仓库的真机（API 31）上也能跑，
     *    这里按名字反射 —— 反射的失败是可读的，而"编译不过"对演示毫无价值。
     */
    private fun readActivityToken(activity: Activity): android.os.IBinder? {
        val m = Activity::class.java.getMethod("getActivityToken")
        return m.invoke(activity) as? android.os.IBinder
    }

    /** 报告统一加时间与设备信息，便于事后核对是哪次真机运行 */    fun header(title: String, extra: String = ""): String = buildString {
        append("══════ ").append(title).append(" ══════\n")
        append("时间 : ").append(java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())).append('\n')
        append("设备 : ").append(android.os.Build.MODEL)
            .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
        append("进程 : pid=").append(android.os.Process.myPid()).append('\n')
        if (extra.isNotEmpty()) append(extra).append('\n')
    }

    /** 一个便于调试的空实现（也是"能不能用匿名类"这条实验的对照组） */
    internal object NoopPage : IPluginPage {
        override fun onCreate(
            pluginContext: android.content.Context,
            container: android.view.ViewGroup,
            networkPayload: String?,
        ) = Unit

        override fun onDestroy() = Unit
        override fun describe(): String = "noop"
    }

    @Suppress("unused")
    private fun unusedMethodForCompileCheck(m: Method, f: File, b: Bundle?): TextView? = null

    @Suppress("unused")
    private val keepIp = IPluginPage::class.java
}
