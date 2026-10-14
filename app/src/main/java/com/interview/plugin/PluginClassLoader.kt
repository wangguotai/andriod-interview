package com.interview.plugin

import dalvik.system.DexClassLoader
import java.io.File

/**
 * Time: 2026/10/13
 * Author: wgt
 * Description: 插件的类加载器 —— 本演示里**最需要解释清楚的一个类**。
 *
 * ─── 默认 DexClassLoader 的两个致命细节 ───
 *
 * `DexClassLoader(dexPath, optDir, libPath, parent)` 的 `parent` 决定了双亲委派
 * 的第一站。用**默认 parent** 会同时踩两个坑：
 *
 * ① **共享接口会变成两个类。**
 *    默认 parent 是构造时传进来的（通常是 `context.classLoader`，
 *    即宿主的 PathClassLoader）。这看起来对，但只对"我们显式拿到的那个 loader"成立。
 *    插件里 `IPluginPage::class.java` 会被解析成宿主那份 —— 正好是我们想要的。
 *    ⚠️ 真正的问题在于**反过来**：宿主侧对插件返回的对象做类型转换时，
 *    转换用的是**宿主的** IPluginPage；只要插件侧解析到的不是同一个 Class 对象，
 *    就会得到那句著名的 `X cannot be cast to X`。
 *    因此这里不能"随它去"，而要**显式把 com.interview.* 钉死在宿主一侧**（见 findClass）。
 *
 * ② **kotlin-stdlib 会被加载两遍。**
 *    本仓库的插件是 Kotlin 写的、宿主也是。若两个加载器各加载一份 `kotlin.*`，
 *    后果不是"性能差一点"，而是三类的运行期崩溃：
 *      · `is String` / `as Int` 这类**内建类型判断**由 kotlin 编译器生成的
 *        `Intrinsics.checkNotNull` 走 stdlib —— 两份 stdlib 时行为可能不一致；
 *      · 跨加载器传 `Unit` / `Pair` / 函数类型（lambda）时会 ClassCastException；
 *      · 反射/序列化库里以 `Class` 为 key 的缓存会命中错误条目。
 *    Android 系统在 14 之后对 `kotlin.*` 做了 shared libraries 优化，但那是
 *    **bootclasspath 级别**的事，我们的插件 dex 不在那条路径上。
 *
 * 所以本类在做的事只有一件：**把"该共享的"与"该隔离的"划清楚**。
 *
 * ─── 边界怎么划（这条边界就是插件化的权力结构）───
 *
 *   共享（委派宿主，插件自己 dex 里那份被忽略）：
 *     · com.interview.*  —— 共享接口 IPluginPage
 *     · kotlin.* / kotlinx.* —— 语言运行时，必须同一份
 *     · android.* / java.* / javax.* / dalvik.* —— 系统引导类，本来也只能有一份
 *   隔离（由插件自己的 dex 提供）：
 *     · 其余全部，包括插件自己的 com.interview.plugindemo.*
 *
 * ⚠️ 注意 `com.interview.plugindemo.*` 落在"隔离"一侧，这不是笔误：
 *    它**必须**由插件 dex 提供，否则插件的实现代码就"泄漏"进宿主了。
 *    这也解释了共享接口为什么不能放在 `com.interview.plugindemo` 下 ——
 *    前缀规则是 `com.interview.` 而不是包名精确匹配。
 *
 * ─── 为什么不重写 parent 而是覆写 findClass ───
 *
 * 如果把宿主 loader 直接当 super 的 parent，那么"插件的 com.interview.plugindemo.X"
 * 永远不会被走到（宿主先答，答不出才轮到插件）—— 实际上是能工作的，
 * 但**失败模式极难查**：宿主哪天新增了一个同名类，插件的行为就静默变了。
 * 显式覆写 findClass、按前缀判断，能把这条规则写在一处、且可打印出来核对。
 */
class PluginClassLoader(
    dexFile: File,
    optimizedDir: File,
    librarySearchPath: String?,
    /** 共享面的宿主侧加载器。传 `null` 时退化为默认行为（仅用于对照实验）。 */
    private val hostLoader: ClassLoader?,
) : DexClassLoader(dexFile.absolutePath, optimizedDir.absolutePath, librarySearchPath, hostLoader) {

    /** 委派记录：仅供页面展示"哪条路径被走了"，不参与逻辑。 */
    val delegationLog = LinkedHashSet<String>()

    private var sharedDecisions = 0
    private var isolatedDecisions = 0

    override fun findClass(name: String): Class<*>? {
        if (hostLoader != null && isShared(name)) {
            sharedDecisions++
            delegationLog.add("共享 → 宿主  $name")
            try {
                return hostLoader.loadClass(name)
            } catch (t: ClassNotFoundException) {
                // ⚠️ 这里**不要**吞掉异常然后回落去加载插件自己那份。
                //    依据前面的 ②：一旦共享面被插件 dex 里的副本顶替，
                //    错误会以"很久以后的一次类型转换失败"出现，与这里毫无关联。
                //    宁可现在就抛出，把问题钉在加载点。
                throw ClassNotFoundException(
                    "共享面 $name 在宿主里加载失败。这类类**必须**由宿主提供，" +
                        "不允许回落到插件 dex —— 否则会得到两个不同的 Class 对象。", t
                )
            }
        }
        isolatedDecisions++
        return super.findClass(name)
    }

    private fun isShared(name: String): Boolean = SHARED_PREFIXES.any { name.startsWith(it) }

    fun describe(): String = buildString {
        append("· DexClassLoader parent : ").append(parent?.javaClass?.name).append('\n')
        append("    identity=").append(System.identityHashCode(parent)).append('\n')
        append("· 委派统计             : 共享=").append(sharedDecisions)
        append("  隔离=").append(isolatedDecisions).append('\n')
        append("· 共享前缀             : ").append(SHARED_PREFIXES.joinToString(" "))
    }

    private companion object {
        val SHARED_PREFIXES = arrayOf(
            // 共享接口所在包。**只**共享到 contract 这一层，不共享整个 com.interview ——
            // 否则插件的实现类也会被 "别人家的" com.interview.plugindemo 顶替。
            "com.interview.plugin.contract.",
            // 语言运行时：必须同一份，理由见类注释 ②
            "kotlin.",
            "kotlinx.",
            // 系统的引导类（写出来是为了"边界可见"，它们本来就只能有一份）
            "android.",
            "androidx.", // ⚠️ 见下
            "java.",
            "javax.",
            "dalvik.",
            "org.xmlpull.",
            "org.json.",
            "org.apache.http.",
        )
    }
}

/*
 * ⚠️ 关于上面把 androidx. 也划进"共享"，需要一句诚实的说明：
 *
 * 本演示的插件是**零 androidx 依赖**的（见 plugin-demo/build.gradle.kts），所以
 * 这条前缀当前**不会**被触发。把它写进来是出于两点：
 *   1. 一旦有人在插件里用了 appcompat，行为与"插件零依赖"这条前提冲突 ——
 *      写在这里能让冲突在"插件加载 principled 地失败"而不是"宿主的 appcompat
 *      被插件带的那份版本顶替、然后某个主题解析出怪颜色"。
 *   2. 它是真实的插件化工程里最常见的踩坑（插件带自己的 support 库版本，
 *      宿主与插件 Activity 的 Theme 互相不认识）。
 *
 * 若哪天插件真的要带三方 UI 库，正确做法**不是**从这里删掉 androidx.，
 * 而是让插件改用宿主暴露的接口 —— 共享面一膨胀，插件的"可升级性"就没了。
 */
