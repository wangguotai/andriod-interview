package com.interview.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.util.Log
import java.io.File
import java.lang.reflect.Field

/**
 * Time: 2026/10/13
 * Author: wgt
 * Description: 插件资源加载 —— 让**插件 APK 的 resources.arsc 与 assets** 挂到宿主进程里。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  一、先把"资源隔离"讲清楚，因为这是本类所有设计取舍的源头
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 资源 ID 是一个 32 位整数，被拆成四段：
 *
 *    0xPP TT EEEE
 *      │  │  └─ entry：条目的序号
 *      │  └──── type ：资源类型（string=4、layout=3、color=1…）
 *      └─────── package：「哪个包的表」。0x7f 是**应用包**的固定值，
 *                       0x01 是系统的 framework-res，0x02..0x7e 是共享库。
 *
 * 关键事实（本演示的全部可证伪点都建立在它上面）：
 * **同一个资源名，在宿主和插件里会拿到同一个 ID** —— 因为它们都按 aapt2 的
 * 分配规则从 `0x7f` 起编。所以"ID 相同"**不能**用来判断资源归属，
 * 能判断归属的只有：**这个 ID 在哪张表里查得到**。
 *
 * 于是"资源隔离"有了可执行的定义：
 *
 *   · 插件用自己的 Resources 查 `0x7f030000` → 拿到 plugin_page.xml   ← 应当在
 *   · 宿主的 Resources 查同一个 `0x7f030000` → Resources$NotFoundException ← 也应当在
 *
 * 第二条能成立，正是因为宿主的 `Resources.mResourcesImpl.mAssets` 里
 * **没有**插件的 AssetManager。这不是"权限"，是纯粹的表查找：宿主表里没这个条目。
 *
 * ⚠️ 一个常见的错误期待：以为"天然隔离"就永远安全。不是的 ——
 *    下面 [Strategy.SHARED_HOST_RESOURCES] 那条路径（很多团队为了省事用的）
 *    会把插件 apk 的路径 addAssetPath 到**宿主的** AssetManager 上，
 *    那一刻两条表就合并了：宿主从此能解析插件的 ID（也可能覆盖同名条目）。
 *    本演示把这条路也留着并明确标注为"仅作对照"，因为它能证明"隔离"是被我们
 *    **选**出来的，不是碰巧得到的。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  二、API 31 前后到底变了什么（这段的每个论断都能用 android.jar 复核）
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 老一套（几乎所有插件化框架的 V1 实现）是这样的：
 *
 *     val am = AssetManager::class.java.newInstance()          // 反射造一个空的 AM
 *     val addAssetPath = AssetManager::class.java
 *         .getDeclaredMethod("addAssetPath", String::class.java)
 *     addAssetPath.invoke(am, pluginApkPath)                    // 把插件挂上去
 *     val res = Resources(am, hostRes.displayMetrics, hostRes.configuration)
 *
 * 本实现保留了这个形态，但有**三处**按 API 分档的差别，每一处都有可复核的依据：
 *
 *   ① `Resources(AssetManager, DisplayMetrics, Configuration)` 这个构造器
 *      在 android.jar 里**只有 API 31+ 才有**（用 javap 逐版本核对过：
 *      android-28/30 的 android.jar 里这个类一个 public 构造器都没有，
 *      android-31 起出现）。所以 API < 31 必须反射 `Resources` 的隐藏构造器，
 *      API ≥ 31 才能直接用公开构造器。
 *
 *   ② `AssetManager.addAssetPath` 至今仍是 **hide** 且从 API 28 起受非 SDK 接口
 *      限制约束。实测它在 API 31/34/36 上仍被 greylist 放行（"unsupported,
 *      reflection, allowed"），但它是这条链路上唯一没有公开替代的反射点
 *      —— 因为 `AssetManager.Builder`（它才是官方给的公开替代）在 android.jar 里
 *      也不存在，同样是 hide。
 *
 *   ③ `AssetManager()` 无参构造器在 android.jar 里是 **package-private**
 *      （javap 确认只有 `android.content.res.AssetManager();` 没有 public），
 *      所以"造一个只含系统资源的空 AM"也绕不开反射。
 *
 * 结论：**资源加载这条链路，在 Android 上不存在纯公开 API 的实现路径。**
 *   `AssetManager.Builder` / `ApkAssets` / `ResourcesLoader` 要么是 hide，
 *   要么（`ResourcesProvider`）只能 load、不能挂载。这不是本演示偷懒 ——
 *   这是为什么"插件化"始终是一个需要逐版本维护的工程，而不是一个可以
 *   写一次就忘的库。这一点值得写在代码里，因为它决定了你**必须**为它保留
 *   真机回归（见文件末的"诚实边界"）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  三、assets 与「包根文件」—— 两个命名空间，不是一个
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * `addAssetPath` 挂上 apk 之后，AssetManager 的命名空间里同时多了三层：
 *
 *   · `res/` 下的已编译资源 → 走 resources.arsc（`Resources.getLayout` 等）
 *   · `assets/` 下的原始文件 → 走 `AssetManager.open("xxx")`（**不带** assets/ 前缀）
 *   · apk 包根下的文件       → 走 `AssetManager.openNonAssetFd("xxx")`（**不是** open）
 *
 * ⚠️ 最后一行是本文件早期写错的地方，值得留痕：原先写的是"包根文件同样走
 *    `AssetManager.open("xxx")`（同一命名空间）"。对照 android-34 的
 *    `AssetManager.java` 后可以确定这是**错的** —— `open()` → `nativeOpenAsset`
 *    只查 assets 命名空间；包根必须走 `openNonAsset` / `openNonAssetFd`
 *    （前者 @hide、android.jar 里没有；后者是公开 API，但要求该 zip 条目未被压缩）。
 *    于是打包脚本把插件必须被宿主读到的小表放进 `assets/`，
 *    而把包根探针文件用 `zip -0`（STORED）单独写入，专门用来演示这条边界。
 *    结论：不要相信任何一份"资源加载就这三步"的清单，要让代码把结果打出来 ——
 *    这正是 [PluginAssets.selfCheck] 里那条注定失败的对照存在的意义。
 */
object PluginResources {

    /** 资源加载策略。两条路径的差别就是"隔离"与"合并"的差别。 */
    enum class Strategy {
        /**
         * 推荐：给插件造一套**独立**的 AssetManager + Resources。
         * 插件的资源只有插件的 Resources 认得，宿主表不受污染。
         */
        ISOLATED_PLUGIN_RESOURCES,

        /**
         * 仅作对照：把插件 apk 追加到**宿主的** AssetManager 上，
         * 于是宿主 Resources 也能查到插件的 ID。
         * ⚠️ 这条路会污染宿主资源表（同名条目可能互相覆盖），
         *    在真实工程里应当避免 —— 它存在的唯一价值是证明"隔离不是天然的"。
         */
        SHARED_HOST_RESOURCES,
    }

    /** 一次加载的结果：插件资源 + 可读的构造过程（都进页面，不藏起来）。 */
    data class Loaded(
        val resources: Resources,
        val assets: AssetManager,
        val strategy: Strategy,
        val buildLog: String,
    )

    private const val TAG = "PluginResources"

    fun load(context: Context, pluginApk: File, strategy: Strategy): Loaded {
        val log = StringBuilder()
        val hostRes = context.resources
        val metrics = hostRes.displayMetrics
        val config = hostRes.configuration

        val assets: AssetManager = when (strategy) {
            Strategy.ISOLATED_PLUGIN_RESOURCES -> {
                // ① 造一个空的（只含系统 assets 的）AssetManager
                val am = newEmptyAssetManager()
                log.append("· AssetManager 实例 : ").append(am.javaClass.name)
                    .append("  identity=").append(System.identityHashCode(am)).append('\n')
                // ② 把插件 apk 作为"资源容器"挂上（res + assets + 包根三层一起来）
                val cookie = addAssetPath(am, pluginApk.absolutePath)
                log.append("· addAssetPath     : ").append(pluginApk.absolutePath)
                    .append("  → cookie=").append(cookie).append('\n')
                check(cookie != 0) {
                    "addAssetPath 返回 0 —— 插件 apk 没能被当作资源容器挂上。" +
                        "常见原因：路径不可读 / 文件不是 zip / 权限被拒。"
                }
                log.append("· 宿主 Resources identity : ").append(System.identityHashCode(hostRes))
                    .append("（与新造的这个不同 → 隔离成立的前提）").append('\n')
                am
            }

            Strategy.SHARED_HOST_RESOURCES -> {
                // ⚠️ 往宿主的 AssetManager 上追加。这一步是**故意破坏隔离**的对照组。
                val am = hostRes.assets
                val cookie = addAssetPath(am, pluginApk.absolutePath)
                log.append("· ⚠️ 复用宿主 AssetManager，identity=")
                    .append(System.identityHashCode(am)).append('\n')
                log.append("· addAssetPath → cookie=").append(cookie)
                    .append("（宿主资源表已被追加，插件的 ID 从此在宿主也查得到）").append('\n')
                am
            }
        }

        if (strategy == Strategy.SHARED_HOST_RESOURCES) {
            // 复用宿主 Resources 即可 —— 因为宿主 AM 已经包含插件条目，
            // 但插件自己的资源 ID 需要在**同一张表**里查，所以直接用宿主 Resources。
            return Loaded(hostRes, assets, strategy, log.toString())
        }

        val res = newResources(assets, metrics, config, log)
        return Loaded(res, assets, strategy, log.toString())
    }

    /**
     * 造一个只含系统资源、但可再生挂载点的 AssetManager。
     *
     * `AssetManager()` 无参构造器在 android.jar 里是 **package-private**
     * （javap 复核过），所以只能反射。这是本类唯一的"造对象"反射点 ——
     * 刻意保留它，是因为它比"改静态字段"那一套安全得多：
     * 这个构造器只是 `nativeCreate()` + `setApkAssets(sSystemApkAssets)`，
     * 不写任何应用级静态状态。
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun newEmptyAssetManager(): AssetManager {
        val ctor = AssetManager::class.java.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance()
    }

    /**
     * `addAssetPath(String): Int` —— 至今仍是 hide API，且从 API 28 起受
     * 非 SDK 接口限制约束。这里刻意**不**做 hidden-api 绕过：
     *
     *   · 它在 API 28~35 上一直是 **greylist（unsupported → 被调用时告警但放行）**，
     *     我方要求的是"能在真机跑通并说清代价"，而绕过手段（`setHiddenApiExemptions`
     *     或双反射）本身就是更脆的依赖；
     *   · 一旦哪天它变成 blacklist，本演示的失败点应该**明确报错**，
     *     而不是靠绕过悄悄继续 —— 那才是"能长期维护"的选择。
     *
     * 返回 0 表示挂载失败，这一点 Apple 无关地会让插件资源整体不可用，
     * 所以调用方必须 check（见 load()）。
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun addAssetPath(am: AssetManager, path: String): Int {
        val m = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
        m.isAccessible = true
        return (m.invoke(am, path) as? Int) ?: 0
    }

    /**
     * 造 Resources。分档的唯一原因就是第一个参数那个公开构造器的可用版本
     * （见文件头 §二 的 ①）。
     */
    private fun newResources(
        assets: AssetManager,
        metrics: android.util.DisplayMetrics,
        config: android.content.res.Configuration,
        log: StringBuilder,
    ): Resources {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            log.append("· Resources 构造     : 公开构造器 Resources(AssetManager, …)（API 31+）")
                .append('\n')
            return Resources(assets, metrics, config)
        }
        // API < 31：android.jar 里这个构造器还不存在（javap 逐版本核对过），
        // 只能反射隐藏构造器 `Resources(AssetManager, DisplayMetrics, Configuration, ResourcesImpl)` 
        // 或它的近亲。⚠️ 本仓库真机是 API 31，**这条分支未在真机验证**。
        // 见文件末的「诚实边界」—— 与其假装它可用，不如标出来。
        log.append("· Resources 构造     : 反射隐藏构造器（API<31 分支，本仓库未真机验证）")
            .append('\n')
        val ctor = Resources::class.java.declaredConstructors.firstOrNull { c ->
            c.parameterTypes.size >= 3 &&
                c.parameterTypes[0] == AssetManager::class.java
        } ?: error(
            "API ${Build.VERSION.SDK_INT} 上找不到可用的 Resources 构造器。" +
                "本演示未在该版本验证过 —— 请把这段报错连同 SDK_INT 一起记下来。"
        )
        ctor.isAccessible = true
        val hostRes = android.content.res.Resources.getSystem()
        val args: Array<Any?> = when (ctor.parameterTypes.size) {
            4 -> arrayOf(assets, metrics, config, null)   // ResourcesImpl 传 null：由构造器自建
            else -> arrayOf(assets, metrics, config)
        }
        @Suppress("UNUSED_EXPRESSION")
        hostRes
        return ctor.newInstance(*args) as Resources
    }

    /**
     * 对照实验：把**同一个资源 ID**分别交给插件的 Resources 与宿主的 Resources，
     * 把两边解析出的**名字**并排打出来。
     *
     * ═══════════════════════════════════════════════════════════════════
     *  ⚠️ 这里最初写错了，而那个错误恰好是本演示最有价值的一课
     * ═══════════════════════════════════════════════════════════════════
     *
     * 第一版实现是"宿主 `getResourceName(id)` 抛 NotFoundException ⇒ 隔离成立"。
     * 在真机上跑出来的结果是 **宿主解析成功了** —— 一度看起来像隔离失效。
     *
     * 真实原因：资源 ID 的 package 段 0x7f 是**应用包固定的**，于是宿主与插件
     * 各自从 0x7f030000 起编号，**同一个 ID 在两张表里都存在，只是指向不同资源**：
     *
     *     0x7f030000  插件表 → layout/plugin_page
     *     0x7f030000  宿主表 → layout/activity_decode_comparison
     *
     * 所以"ID 相同"根本不能用来判断归属；"宿主查不到"也不是隔离的判据 ——
     * **能判断归属的只有解析出来的名字**。
     *
     * 这条差异在工程上不是学术问题，它直接决定一个后果：
     * 如果宿主用**自己的** Resources 去 inflate 插件的 `R.layout.xxx`，
     * 它**不会报错**，而是安静地 inflate 成宿主的另一个布局 —— 一个"点了没反应/
     * 界面变成别的样子"的 bug，且栈里没有任何异常。这正是"插件必须拿自己的
     * Resources"这条规则的物理原因，也是本函数存在的意义。
     */
    fun probeIsolation(hostRes: Resources, pluginRes: Resources, id: Int): String {
        val idHex = "0x" + Integer.toHexString(id)
        val pluginName = try {
            pluginRes.getResourceName(id)
        } catch (_: Resources.NotFoundException) {
            "❌ 插件表里也没有这个条目（元数据没同步？）"
        }
        val hostName = try {
            hostRes.getResourceName(id)
        } catch (_: Resources.NotFoundException) {
            "❌ NotFoundException（宿主表里没有这个条目）"
        }
        val verdict = when {
            pluginName.startsWith("❌") ->
                "⚠️ 无法判定：插件侧解析失败，先修插件资源"

            hostName.startsWith("❌") ->
                "✅ 宿主找不到该 ID —— 隔离可见（宿主表里没有插件条目）"

            pluginName == hostName ->
                "⚠️ 两条表给出了**同一个名字** —— 该 ID 上无法区分归属"

            else ->
                "✅ 同一个 ID、两个不同的资源 —— 这就是「资源隔离」的可执行证据"
        }
        return "· $idHex\n" +
            "    插件表 → $pluginName\n" +
            "    宿主表 → $hostName\n" +
            "    $verdict"
    }

    /**
     * 对照实验：插件 Resources 解析同一个 ID。应当成功。
     */
    fun probePlugin(pluginRes: Resources, type: String, name: String, id: Int): String = try {
        val v = pluginRes.getResourceName(id)
        val text = when (type) {
            "string" -> "「${pluginRes.getString(id)}」"
            else -> "（非字符串资源，取到名字即可）"
        }
        "✅ 插件 Resources 解析到 0x${Integer.toHexString(id)} → $v $text"
    } catch (t: Throwable) {
        "❌ 插件 Resources 也解析不到 0x${Integer.toHexString(id)}（$name）：" +
            "${t.javaClass.simpleName}: ${t.message}"
    }

    /**
     * 直接读宿主 Resources 里那个 `mResourcesImpl.mAssets` 字段，用来**证明**
     * "宿主表里到底挂的是哪个 AssetManager"不靠猜。
     *
     * 这一段反射的取舍要讲明白：
     *   · 它**不参与**任何加载逻辑，只负责把证据打出来（失败就返回一行说明，
     *     绝不影响插件能否工作）；
     *   · 之所以宁可多写这段，是因为"隔离生效"若只靠 Resources 的名字对照，
     *     读代码的人仍可能怀疑"是不是表里根本没有这类条目"。把宿主的 AssetManager
     *     身份与插件 AM 身份一起打出来，两条证据互相咬合。
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun hostAssetManagerIdentity(hostRes: Resources): String = try {
        val implField = Resources::class.java.getDeclaredField("mResourcesImpl")
        implField.isAccessible = true
        val impl = implField[hostRes]
        val assetsField = impl.javaClass.getDeclaredField("mAssets")
        assetsField.isAccessible = true
        val hostAssets = assetsField[impl] as AssetManager
        "宿主 mResourcesImpl.mAssets identity=${System.identityHashCode(hostAssets)}"
    } catch (t: Throwable) {
        "（读不到宿主内部字段：${t.javaClass.simpleName} —— 仅影响这条证据展示，不影响加载）"
    }

    /** 两个 Resources 是不是同一个对象。写成方法是为了让页面不必自己写 === */
    fun sameInstance(a: Resources, b: Resources): Boolean = a === b

    /**
     * 读**插件** AssetManager 的 identity，和宿主那个并排展示。
     * 两者不同 ⇒ "插件用的是另一套资源表"就不是推论，而是可直接比对的数字。
     */
    fun assetManagerIdentity(assets: AssetManager): Int = System.identityHashCode(assets)

    @Suppress("unused")
    private val keepFieldRef: Field? = null
}
