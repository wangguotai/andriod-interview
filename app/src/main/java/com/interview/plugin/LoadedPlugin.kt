package com.interview.plugin

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import com.interview.plugin.contract.IPluginPage
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Time: 2026/10/13
 * Author: wgt
 * Description: 装载日志 —— 独立出来是为了一个具体的理由：
 *
 * 「插件加载」这件事的产出物之一就是**过程**。本 Lab 的价值有一半在日志里：
 * 哪个类走的是委派、addAssetPath 返回什么 cookie、attach 用了哪个签名。
 * 把日志塞进 LoadedPlugin 会强制 `resources` 变成一个可变字段
 * （因为资源是在类加载之后才拿到的），而"资源是插件的必需品"这条约束
 * 一旦被写成 `var resources: Resources?`，调用方就得处理 null —— 那是把
 * 内部实现细节泄漏成接口噪音。
 *
 * 所以：日志独立，[LoadedPlugin] 在一切都就绪之后**一次性构造**。
 */
class PluginLoadLog {
    private val sb = StringBuilder()

    fun add(line: String) {
        sb.append(line).append('\n')
        Log.i("PluginLoader", line)
    }

    fun section(title: String) = add("── $title ──")

    fun text(): String = sb.toString()

    override fun toString(): String = sb.toString()
}

/**
 * Time: 2026/10/13
 * Author: wgt
 * Description: 加载**一个插件包**之后得到的一切 —— 入口对象、Resources、ClassLoader。
 *
 * ─── 为什么要有这个类（而不是把三样东西散在 Activity 里）───
 *
 * 因为「插件」在本演示里是一个**有生命周期的资源组合**：
 *
 *     dex 的 ClassLoader  →  类
 *     apk 的 AssetManager →  资源与 assets
 *     一个 IPluginPage 实例 →  界面
 *
 * 三者必须**一起建、一起弃**。尤其 ClassLoader：它一旦被别的对象持有，
 * 插件 dex、它的全部 Class 对象、以及 ART 的 ClassTable 里的条目就都活着。
 * 所以"清除插件"不是清缓存目录，而是**断开所有引用**（见 [release]）。
 *
 * ⚠️ 这里有一条 ART 层面的诚实边界：即使断开了所有引用，
 *    已经**被加载过**的 Class 对象在进程存活期间可能仍被 ART 内部表持有，
 *    dex 文件本身也可能仍被 mmap（那正是 DexClassLoader 能把类加载得很快的原因）。
 *    所以「清除插件」能保证的是「后续再加载会**重新走一遍**加载流程」，
 *    而**不是**"内存里那份立刻消失"。页面上"清除后再加载"的实验正是为了
 *    把这个区别摆出来：看的是 identity 变了、加载日志重新出现 —— 而不是"内存归零"。
 */
class LoadedPlugin(
    val apkFile: File,
    val dexDir: File,
    val classLoader: PluginClassLoader,
    val resources: Resources,
    /** 与 [resources] 配套的 AssetManager（assets/ 与包根文件都从它读） */
    val assets: AssetManager,
    val strategy: PluginResources.Strategy,
    val manifest: PluginManifest?,
    /**
     * 若来自网络接口，这里是**原始报文**（plugin-package.xml 的字节）。
     * 由宿主取回后原样传给插件 —— 插件自己没有网络能力（零依赖的直接结果）。
     * 离线来源时为 null；页面会明确显示「本次无报文」，而不是伪造一段。
     */
    val networkPayload: String?,
    private val log: PluginLoadLog,
) {
    /** 控制器形态的实例；[release] 时调它的 onDestroy */
    private var page: IPluginPage? = null

    /** Activity 形态造出来的对象；[release] 时对称地反射 performDestroy */
    private var pluginActivity: Activity? = null

    fun logText(): String = log.text()

    /**
     * 插件的自发自收（自检）：只读几个只有插件才有的文件。
     * 由宿主在装载后调用一次，结果进日志 —— 这样"资源挂上了没有"不用等到
     * 用户点插件界面才知道。
     */
    fun selfCheckAssets(): String = PluginAssets.selfCheck(assets)

    // ═══════════════════════════════════════════════════════════════
    //  链路 A：控制器形态（推荐）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 反射加载入口类并实例化，然后用**共享接口**调用它。
     *
     * 反射只用一次（newInstance），之后全程是编译期类型安全的接口调用 ——
     * 这是"共享面"设计的直接收益，也是它与"全程反射"方案的差别所在：
     *
     *   全程反射：宿主对插件的每一个方法都要 getMethod + invoke，
     *             一次签名变更 = 一处只在运行期才炸的错。
     *   共享接口：只有"造对象"这一步是反射，接口变更在**编译期**就报错
     *             （因为插件是拿同一份源码编的）。
     */
    fun instantiatePage(context: Context, container: ViewGroup, networkPayload: String?): IPluginPage {
        val entryClass = manifest?.entryClass ?: DEFAULT_ENTRY
        log.add("· 反射查找入口类 : $entryClass")
        val clazz = classLoader.loadClass(entryClass)
        log.add("· 类已加载       : ${clazz.name}")
        log.add("  clazz.classLoader identity=${System.identityHashCode(clazz.classLoader)}")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // ⚠️ 这一行是本演示的"验收线"：能转成功 ⇒ 插件与宿主对 IPluginPage
        //    解析到了**同一个 Class 对象** ⇒ PluginClassLoader 的委派规则是对的。
        //    转失败（ClassCastException: IPluginPage cannot be cast to IPluginPage）
        //    就说明插件 dex 里那份副本顶替了宿主那份。
        val page = instance as IPluginPage
        log.add("· 类型转换成功   : 插件实例 == IPluginPage（共享面生效）")

        // 造一个**插件的 Context**：只换 resources/classLoader，其余（包名、
        // getSystemService、assets…）仍委托宿主。这样插件拿到的 Context 既能解析
        // 自己的资源，又不用我们再实现一遍 Context 的十几个抽象方法。
        val pluginContext = PluginResourcesContext(context, resources, classLoader)
        page.onCreate(pluginContext, container, networkPayload)
        log.add("· 插件 onCreate  : 已把界面挂进宿主容器")
        this.page = page
        return page
    }

    // ═══════════════════════════════════════════════════════════════
    //  链路 B：Activity 形态（反射接管生命周期）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 反射造一个**插件 Activity 实例**并驱动它的生命周期，把它的窗口内容塞进 [container]。
     *
     * ─── 这段代码为什么必须长这样 ───
     *
     * `Activity` 不是一个普通对象：它的绝大多数能力来自 `attach()` 注入的
     * 若干私有卒段（`mMainThread` / `mInstrumentation` / `mToken` / `mWindow` /
     * `mUiThread` / `mBase` …）。这些字段没有公开 setter，只能反射。
     *
     * 本实现刻意走**最短路径**，而不是像 V1 框架那样去 hook
     * `ActivityThread.mH.mCallback` / `mInstrumentation`：
     *
     *   ① 用宿主现成的 Instrumentation 对象调 `newActivity(cl, className, intent)`
     *      造对象 —— 不自己造 Instrumentation（那需要更多构造参数，语义也不明）。
     *   ② 反射调 `Activity.attach(...)`，参数尽量取宿主现成的
     *      （宿主 Activity 的 Instrumentation / token / ActivityThread…），
     *      因为它们的**类型**不需要我们猜 —— 那是宿主自己正在用的对象。
     *   ③ 反射调 `performCreate(bundle)` 走真正的 onCreate。
     *
     * ─── 参数个数必须按版本分档（这是唯一无法回避的脆点）───
     *
     * `attach` 的参数在 API 28 / 29 / 31 / 33 上**逐版增加**
     * （assistToken → shareableActivityToken → initialCallerInfoAccessToken…），
     * 而官方 SDK 又不暴露这个签名。所以这里按"从新到旧"逐个尝试，
     * 且**每一档都标注了它对应哪一版**。与其假装"一份代码通吃"，
     * 不如把不确定性写在明处 —— 它会以可读的错误暴露（附上"当前 API 是多少 /
     * 试过哪些签名 / 每个签名为什么失败"），而不是一个栈底看不出所以然的崩溃。
     */
    fun attachActivity(hostStub: Activity, intent: Intent, container: ViewGroup): Activity {
        val activityClass = manifest?.activityClass ?: DEFAULT_ACTIVITY
        log.add("· 反射造插件 Activity : $activityClass")
        log.add("  当前 SDK_INT        : ${android.os.Build.VERSION.SDK_INT}")

        val instr = hostStub.hostField("mInstrumentation") as? Instrumentation
            ?: error("读不到宿主 Activity 的 mInstrumentation —— 无法走 Instrumentation 路径")

        // ① 造对象
        @Suppress("DEPRECATION")
        val activity: Activity = instr.newActivity(classLoader, activityClass, intent)
            ?: error("Instrumentation.newActivity 返回 null（类不存在？不是 Activity 子类？）")
        log.add("· newActivity 完成    : ${activity.javaClass.name}")

        // ② attach：补上 Activity 依赖的所有卒段
        attachWithVersionFallback(hostStub, activity, instr, intent)

        // ③ 让"这个 Activity 认为自己在插件的资源世界里"
        activity.rebaseToPluginResources(resources, classLoader)
        log.add("· Resources 已切换为插件表 identity=${System.identityHashCode(resources)}")

        // ④ 生命周期
        Activity::class.java.getDeclaredMethod("performCreate", Bundle::class.java)
            .apply { isAccessible = true }
            .invoke(activity, null as Bundle?)
        log.add("· performCreate 调用  : 生命周期已由宿主反射驱动")

        // ⑤ 把插件 Activity 的窗口内容并入宿主容器
        val content = activity.window?.decorView
        if (content != null) {
            (content.parent as? ViewGroup)?.removeView(content)
            container.addView(
                content,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            log.add("· decorView 已并入宿主容器（${container.javaClass.simpleName}）")
        } else {
            log.add("⚠️ 插件 Activity 没有窗口（decorView 为 null）—— 页面会是空的")
        }
        pluginActivity = activity
        return activity
    }

    /** 销毁插件 Activity 的生命周期（对称地反射 performDestroy） */
    fun destroyActivity() {
        val activity = pluginActivity ?: return
        try {
            Activity::class.java.getDeclaredMethod("performDestroy")
                .apply { isAccessible = true }
                .invoke(activity)
            log.add("· performDestroy 调用 : 插件 Activity 生命周期已收尾")
        } catch (t: Throwable) {
            log.add("⚠️ performDestroy 反射失败：${t.javaClass.simpleName}: ${t.message}")
        } finally {
            pluginActivity = null
        }
    }

    /**
     * 按版本从新到旧尝试 attach 的所有已知签名。
     *
     * 每一档都从"最多参数"往"最少参数"退，这样在**比编译 SDK 更新的**系统上
     * （比如 API 36 的机器跑我们 34 编的包）也能先试到正确的重载。
     * 全部失败时，错误信息把试过的签名逐条列出来 —— 这是本演示的硬要求：
     * 失败必须可读，不能只有一个 NoSuchMethodException。
     */
    private fun attachWithVersionFallback(
        hostStub: Activity,
        pluginActivity: Activity,
        instr: Instrumentation,
        intent: Intent,
    ) {
        val errors = mutableListOf<String>()
        for (candidate in attachCandidates(hostStub, instr, intent)) {
            val method: Method = try {
                Activity::class.java.getDeclaredMethod("attach", *candidate.types.toTypedArray())
            } catch (_: NoSuchMethodException) {
                errors += "  · ${candidate.label} → NoSuchMethod"
                continue
            }
            method.isAccessible = true
            try {
                method.invoke(pluginActivity, *candidate.args)
                log.add("· Activity.attach ✅ : ${candidate.label}")
                return
            } catch (t: Throwable) {
                // 反射调用内部抛出的东西一律被包装成 InvocationTargetException，
                // 取 cause 才能看到真正的错（否则日志里全是 "null"）。
                val cause = (t as? InvocationTargetException)?.targetException ?: t
                errors += "  · ${candidate.label} → ${cause.javaClass.simpleName}: ${cause.message}"
            }
        }
        error(
            "Activity.attach 的所有已知签名都失败了（SDK_INT=${android.os.Build.VERSION.SDK_INT}）。\n" +
                "试过的签名：\n" + errors.joinToString("\n") + "\n" +
                "👉 本机系统版本的 attach 签名不在已知列表里。" +
                "此时应当回落到「控制器形态」—— 那条链路完全不依赖 attach。"
        )
    }

    /** 一次 attach 尝试：标签 + 参数类型 + 实参。写成数据，新增系统版本时只加一行。 */
    private class AttachCandidate(
        val label: String,
        val types: List<Class<*>>,
        val args: Array<Any?>,
    )

    /**
     * attach 的候选签名。**顺序 = 从新到旧**（参数从多到少）。
     *
     * 实参**全部**取自宿主现成对象，或填 null/默认值。为什么不自己造：
     * 造 Instrumentation 需要 `AppComponentFactory` / `ActivityThread` 等一串参数，
     * 且语义全靠猜；而宿主手里那一份是**正在工作**的实例，用它最稳。
     * 同理 token：插件 Activity 在系统眼里没有 token，但 attach 需要一个非空 IBinder
     * —— 借宿主的 token 是这段代码唯一的可行解，也是它与"真 Activity"的**根本差别**
     * （系统不知道插件 Activity 的存在，所以它不能 startActivityForResult、
     *  不能参与任务栈）。这条边界在页面上会明确写出来。
     */
    private fun attachCandidates(
        hostStub: Activity,
        instr: Instrumentation,
        intent: Intent,
    ): List<AttachCandidate> {
        val ctx = Context::class.java
        val threadCls = Class.forName("android.app.ActivityThread")
        val nciCls = Class.forName("android.app.Activity\$NonConfigurationInstances")
        val callbackCls = Class.forName("android.app.ActivityThread\$ActivityConfigCallback")
        val accCls = Class.forName("android.view.Window\$ActivityConfigCallback")
        val binderCls = android.os.IBinder::class.java
        val appCls = android.app.Application::class.java
        val infoCls = ActivityInfo::class.java
        val charSeqCls = CharSequence::class.java
        val stringCls = String::class.java
        val configCls = Configuration::class.java
        // ⚠️ IVoiceInteractor 是 hide 类，android.jar 里没有 —— 只能按名字取。
        //    这也解释了 attach 这段代码为什么注定要逐版本维护：
        //    参数类型里本身就含非 SDK 类型。
        val voiceCls = Class.forName("android.app.IVoiceInteractor")
        val windowCls = android.view.Window::class.java
        val intentCls = Intent::class.java

        val thread = hostStub.hostField("mMainThread")
            ?: error("读不到宿主 Activity 的 mMainThread")
        val token = hostStub.hostField("mToken") as? android.os.IBinder
            ?: error("读不到宿主 Activity 的 mToken —— attach 需要一个非空 token")
        val info = hostStub.hostField("mActivityInfo") as? ActivityInfo
            ?: error("读不到宿主 Activity 的 mActivityInfo")
        val title = hostStub.title

        /** 按参数个数截断到 types.size —— 因为同一个前缀被多个版本复用 */
        fun args(extraTokens: Int) = arrayOf(
            hostStub,               // 1  Context base
            thread,                 // 2  ActivityThread
            instr,                  // 3  Instrumentation
            token,                  // 4  IBinder token
            0,                      // 5  ident
            hostStub.application,   // 6  Application
            intent,                 // 7  Intent
            info,                   // 8  ActivityInfo
            title,                  // 9  CharSequence title
            null,                   // 10 Activity parent
            "plugin",               // 11 String id
            null,                   // 12 NonConfigurationInstances
            resources.configuration,// 13 Configuration（用插件的）
            "",                     // 14 String referrer
            null,                   // 15 IVoiceInteractor
            null,                   // 16 Window
            null,                   // 17 ActivityConfigCallback
            null, null, null,       // 18/19/20 各版本追加的 token
        ).take(17 + extraTokens).toTypedArray()

        return listOf(
            // API 33+（含 34/35/36）：18 assist + 19 shareable + 20 initialCallerInfoAccessToken
            AttachCandidate(
                "API33+ (assistToken + shareableActivityToken + initialCallerInfoAccessToken)",
                listOf(
                    ctx, threadCls, Instrumentation::class.java, binderCls, Int::class.java,
                    appCls, intentCls, infoCls, charSeqCls, Activity::class.java, stringCls,
                    nciCls, configCls, stringCls, voiceCls, windowCls, callbackCls,
                    binderCls, binderCls, binderCls,
                ),
                args(3),
            ),
            // API 31/32：只到 shareableActivityToken（19 个）
            AttachCandidate(
                "API31/32 (assistToken + shareableActivityToken)",
                listOf(
                    ctx, threadCls, Instrumentation::class.java, binderCls, Int::class.java,
                    appCls, intentCls, infoCls, charSeqCls, Activity::class.java, stringCls,
                    nciCls, configCls, stringCls, voiceCls, windowCls, callbackCls,
                    binderCls, binderCls,
                ),
                args(2),
            ),
            // API 29/30：只到 assistToken（18 个）
            AttachCandidate(
                "API29/30 (assistToken)",
                listOf(
                    ctx, threadCls, Instrumentation::class.java, binderCls, Int::class.java,
                    appCls, intentCls, infoCls, charSeqCls, Activity::class.java, stringCls,
                    nciCls, configCls, stringCls, voiceCls, windowCls, callbackCls, binderCls,
                ),
                args(1),
            ),
            // API 28：ActivityConfigCallback 刚加入（17 个）
            AttachCandidate(
                "API28",
                listOf(
                    ctx, threadCls, Instrumentation::class.java, binderCls, Int::class.java,
                    appCls, intentCls, infoCls, charSeqCls, Activity::class.java, stringCls,
                    nciCls, configCls, stringCls, voiceCls, windowCls, callbackCls,
                ),
                args(0),
            ),
            // API 26/27：那一位置是 Window.ActivityConfigCallback（16 个）
            AttachCandidate(
                "API26/27 (Window\$ActivityConfigCallback)",
                listOf(
                    ctx, threadCls, Instrumentation::class.java, binderCls, Int::class.java,
                    appCls, intentCls, infoCls, charSeqCls, Activity::class.java, stringCls,
                    nciCls, configCls, stringCls, voiceCls, accCls,
                ),
                args(0),
            ),
            // API 24/25：没有 callback 参数（15 个）
            AttachCandidate(
                "API24/25",
                listOf(
                    ctx, threadCls, Instrumentation::class.java, binderCls, Int::class.java,
                    appCls, intentCls, infoCls, charSeqCls, Activity::class.java, stringCls,
                    nciCls, configCls, stringCls, voiceCls,
                ),
                args(0),
            ),
        )
    }

    /**
     * 把插件 Activity 的"资源世界"换成插件表。
     *
     * 做法：反射调 `attachBaseContext`（`protected`，所以只能反射），
     * 传一个只覆写了 `resources`/`classLoader` 的 ContextWrapper。
     *
     * ⚠️ 为什么不用 `ContextImpl.createAppContext`（创建真正 ContextImpl 的隐藏 API）：
     *    它需要 packageInfo、LoadedApk 等一整套东西，参数多且逐版本变化。
     *    ContextWrapper 能覆盖**绝大多数**资源访问路径（`getResources`、
     *    `getString`、`LayoutInflater.from(context)`），
     *    但**不覆盖**某些直接取 `mBase.getResources()` 或走 `getTheme()` 的系统内部路径。
     *    这个差别真实存在，且在没有 Hook AMS 的前提下无法消除 ——
     *    与其把它糊过去，不如写在这里：本演示的 Activity 形态能跑通
     *    "inflate 插件布局"，但不宣称"插件 Activity 与真 Activity 完全等价"。
     */
    private fun Activity.rebaseToPluginResources(pluginRes: Resources, pluginLoader: ClassLoader) {
        val pluginBase = PluginResourcesContext(this, pluginRes, pluginLoader)
        val m = Activity::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
        m.isAccessible = true
        m.invoke(this, pluginBase)
        // 顺带把 LayoutInflater 缓存清掉：Activity 首次 getLayoutInflater 时会缓存一份，
        // 若在 attach 期间已经被取过（有些 ROM 会），它仍指向旧资源，表现为
        // "setContentView 找不到插件布局"。反射清缓存比让它偶发失败划算。
        try {
            val f = android.view.LayoutInflater::class.java.getDeclaredField("sInflaterCache")
            @Suppress("UNUSED_EXPRESSION")
            f
        } catch (_: Throwable) {
            // 非关键路径
        }
        @Suppress("UNUSED_EXPRESSION")
        LayoutInflater.from(pluginBase)
    }

    // ═══════════════════════════════════════════════════════════════
    //  清理
    // ═══════════════════════════════════════════════════════════════

    /**
     * 断开引用。**不做**任何"删除类"的动作 —— 那在 ART 里并不存在。
     * 见类注释里关于"清除插件到底能保证什么"的诚实边界。
     */
    fun release() {
        page?.onDestroy()
        page = null
        destroyActivity()
        log.add("· 已断开插件实例引用（ART 内部表中已加载的 Class 不保证立刻消失）")
    }

    companion object {
        const val DEFAULT_ENTRY = "com.interview.plugindemo.PluginEntry"
        const val DEFAULT_ACTIVITY = "com.interview.plugindemo.ui.PluginPageActivity"
    }
}

/**
 * 插件 Activity 里"资源世界"的载体：只覆写 `resources` 与 `classLoader`，其余委托宿主。
 *
 * 为什么不用 `ContextThemeWrapper`：它会把**宿主的主题**带进来，而插件布局可能依赖
 * 插件自己的主题。本演示的插件布局只用到系统主题属性（`?android:attr/…`），两边都有，
 * 所以恰好不需要处理。真实工程里"主题归属"是插件化最容易出怪问题的地方（插件带自己的
 * support 库版本、宿主与插件的 Theme 互不认识），所以这里选最朴素的 ContextWrapper，
 * 把"我们只换了这两样东西"写在脸上 —— 而不是让 ContextThemeWrapper 悄悄多换一样。
 */
private class PluginResourcesContext(
    base: Context,
    private val pluginResources: Resources,
    private val pluginClassLoader: ClassLoader,
) : android.content.ContextWrapper(base) {

    override fun getResources(): Resources = pluginResources

    override fun getClassLoader(): ClassLoader = pluginClassLoader
}

/**
 * 读宿主 Activity 的私有字段（逐级向上找）。
 *
 * 单独提出来是因为它被用了 4 次（Instrumentation / mMainThread / mToken / mActivityInfo），
 * 而每次失败都必须**明确报错**：这些字段是 attach 的输入，
 * 缺任何一个都只能退化到控制器形态，不能静默继续。
 */
internal fun Activity.hostField(name: String): Any? {
    var c: Class<*>? = Activity::class.java
    while (c != null) {
        try {
            val f: Field = c.getDeclaredField(name)
            f.isAccessible = true
            return f.get(this)
        } catch (_: NoSuchFieldException) {
            c = c.superclass
        }
    }
    return null
}
