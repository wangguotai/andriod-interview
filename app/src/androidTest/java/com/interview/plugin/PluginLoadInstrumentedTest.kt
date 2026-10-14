package com.interview.plugin

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.interview.plugin.contract.IPluginPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Time: 2026/10/13
 * Author: wgt
 * Description: 插件化 Lab 的**真机金标准**测试 —— 把"能跑通"这件事从截图变成断言。
 *
 * ─── 为什么必须有这一组测试（而不是"人工点一下看日志"）───
 *
 * 本 Lab 的全部命题都是关于**运行期结构**的，而它们全都属于"看图看不出来"的那类：
 *
 *   · 插件的类是不是**另一个** ClassLoader 加载的？（不是"能不能打开"）
 *   · IPluginPage 的转换成功，是因为"委派对了"，还是因为"恰好都叫这个名字"？
 *   · 资源 ID 0x7f… 在两张表里指向的是不是不同资源？
 *   · 宿主 apk 的 dex 里有没有插件的类符号？
 *
 * 这些问题在人眼看到的界面上长得一模一样（都是"插件页面显示出来了"）。
 * 所以本测试逐条断言，并且**每条断言都写清楚它在防哪种假绿**。
 *
 * ─── 为什么放在 :app 而不是 :plugin-demo ───
 *
 * 被测对象是**宿主侧的装载器**（PluginLoader / PluginClassLoader / PluginResources），
 * 它们都在 :app 里。放到插件模块去测会把宿主当测试依赖，方向反了。
 *
 * 运行：
 *   ./gradlew :app:connectedDebugAndroidTest --tests "*PluginLoadInstrumentedTest*"
 *
 * 前提：宿主 assets 里必须有内嵌插件包 —— 由 tools/plugin-package/package.sh 生成。
 */
@RunWith(AndroidJUnit4::class)
class PluginLoadInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    private fun loadFromAssets(): LoadedPlugin =
        PluginLoader.load(
            context,
            PluginLoader.Options(
                source = PluginLoader.Source.ASSETS,
                strategy = PluginResources.Strategy.ISOLATED_PLUGIN_RESOURCES,
            ),
        )

    /**
     * 断言 1：插件类确实由**一个不同于宿主的 ClassLoader** 加载。
     *
     * 防的假绿：如果插件被编进了宿主，`loadClass` 一样会成功 ——
     * 只是 classLoader 会是宿主的 PathClassLoader。所以这里断言的是
     * **identity 不同**，而不是"加载到了"。
     */
    @Test
    fun pluginClass_isLoadedByDifferentClassLoader() {
        val plugin = loadFromAssets()
        try {
            val clazz = plugin.classLoader.loadClass(LoadedPlugin.DEFAULT_ENTRY)
            val hostLoader = context.classLoader

            assertNotEquals(
                "插件类与宿主类用的是同一个 ClassLoader —— 说明插件被编进了宿主，独立打包不成立",
                System.identityHashCode(hostLoader),
                System.identityHashCode(clazz.classLoader),
            )
            assertEquals(
                "插件类的 loader 应当就是我们给它的 DexClassLoader",
                System.identityHashCode(plugin.classLoader),
                System.identityHashCode(clazz.classLoader),
            )
            Log.i(TAG, "plugin class loader identity=${System.identityHashCode(clazz.classLoader)}")
        } finally {
            plugin.release()
        }
    }

    /**
     * 断言 2：宿主 ClassLoader **加载不到**插件的类。
     *
     * 这是整个演示的题眼。它与断言 1 是两条独立证据：
     *   断言 1 说"插件的类是别人加载的"，
     *   断言 2 说"宿主自己根本没有这个符号"。
     * 只有两条都成立，"不进主 App"才是一个事实，而不是一句解释。
     */
    @Test
    fun hostClassLoader_cannotLoadPluginClass() {
        val names = listOf(
            LoadedPlugin.DEFAULT_ENTRY,
            LoadedPlugin.DEFAULT_ACTIVITY,
            "com.interview.plugindemo.R",
            "com.interview.plugindemo.BuildConfig",
        )
        names.forEach { name ->
            try {
                Class.forName(name, false, context.classLoader)
                fail("宿主 ClassLoader 竟然加载到了 $name —— 插件被编进宿主了，本演示的前提已破")
            } catch (_: ClassNotFoundException) {
                // 期望路径
            }
        }
    }

    /**
     * 断言 3：共享接口的类型转换成功 —— 且**不是因为**同名字符串匹配。
     *
     * 防的假绿：ClassCastException 只在运行期出现；如果只断言"拿到了非空对象"，
     * 一个用反射包出来的壳也能过。这里断言的是"宿主源码里的 IPluginPage
     * 与插件委派解析到的 IPluginPage 是**同一个 Class 对象**"，
     * 而这正是转换能成立的原因。
     */
    @Test
    fun sharedApi_isDelegatedToHost_notDuplicatedInPluginDex() {
        val plugin = loadFromAssets()
        try {
            assertEquals(
                "插件 dex 里那份 IPluginPage 的加载器应当就是宿主加载器 —— " +
                    "否则会出现两个不同的 Class 对象，跨加载器转换必然失败",
                System.identityHashCode(context.classLoader),
                System.identityHashCode(IPluginPage::class.java.classLoader),
            )
            val instance = plugin.classLoader.loadClass(LoadedPlugin.DEFAULT_ENTRY)
                .getDeclaredConstructor().newInstance()
            // 这一行就是"委派规则对不对"的最终判定
            val page = instance as IPluginPage
            assertNotNull(page.describe())
            Log.i(TAG, "shared api cast OK; describe=${page.describe().replace("\n", " | ")}")
        } finally {
            plugin.release()
        }
    }

    /**
     * 断言 4：**资源隔离** —— 同一个 ID 在两张表里指向不同资源。
     *
     * 这是本演示里最容易被误解的一条，所以断言写得比较细：
     *
     * 一开始的设想是"宿主解析插件的 ID 会抛 NotFoundException"。真机跑出来**不是**这样：
     * 资源 ID 的 package 段 0x7f 是应用包固定值，宿主与插件各自从 0x7f030000 起编号，
     * 于是**同一个 ID 在两表里都存在**，只是指向不同资源。
     *
     * 所以正确的断言是：
     *   · 插件表解析 → 名字必须是 plugin/… 里的东西
     *   · 宿主表解析 → 要么 NotFoundException，要么**另一个名字**
     *   · 两者**绝不应当**是同一个名字
     */
    @Test
    fun resourceIsolation_sameId_resolvesToDifferentResources() {
        val plugin = loadFromAssets()
        try {
            val ids = PluginAssets.parseResIds(plugin.assets)
            assertTrue(
                "插件包里没有 plugin-res-ids.txt —— 打包脚本没导出资源表，测试无法取证",
                ids.isNotEmpty(),
            )

            var comparable = 0
            ids.forEach { item ->
                val pluginName = plugin.resources.getResourceName(item.id)
                val hostName = try {
                    context.resources.getResourceName(item.id)
                } catch (_: android.content.res.Resources.NotFoundException) {
                    null
                }
                Log.i(TAG, "id=${item.idHex} type=${item.type} plugin=$pluginName host=$hostName")
                if (hostName != null) {
                    comparable++
                }
            }
            Log.i(TAG, "可比对的 ID 数 = $comparable / ${ids.size}")

            // 核心断言：插件表必须能解析出**插件自己的**名字。
            // 这正是"资源隔离"的本体 —— 宿主没有插件的资源表，所以只有插件 Resources 认得这些 ID。
            ids.forEach { item ->
                val pluginName = plugin.resources.getResourceName(item.id)
                assertEquals(
                    "插件表把 ${item.idHex} 解析成了 $pluginName，与插件自带的资源表不符",
                    "${item.type}/${item.name}",
                    pluginName,
                )
            }

            // 对照断言：**同名资源在宿主表里若存在，必须指向不同条目**。
            // 这条是本演示最容易被误解的地方（见 PluginResources 的长注释）：
            // 资源 ID 的 package 段 0x7f 固定，宿主与插件都从 0x7f03xxxx 起编号，
            // 于是同一个 ID 在两表里都存在、只是指向不同资源。所以隔离的判据是**名字**，不是"查不到"。
            val layoutId = ids.first { it.name == "plugin_page" }.id
            val hostLayoutName = try {
                context.resources.getResourceName(layoutId)
            } catch (_: android.content.res.Resources.NotFoundException) {
                null
            }
            if (hostLayoutName != null) {
                assertNotEquals(
                    "插件与宿主对 ${Integer.toHexString(layoutId)} 解析出了同一个名字 —— 隔离不成立",
                    "layout/plugin_page",
                    hostLayoutName,
                )
                Log.i(
                    TAG,
                    "★ 同一 ID 两张表：插件=layout/plugin_page  宿主=$hostLayoutName —— 这就是隔离的证据",
                )
            }
        } finally {
            plugin.release()
        }
    }

    /**
     * 断言 5：插件 apk 的 assets 与**包根文件**都要能读 —— 但走的是**两个不同的 API**。
     *
     * 防的假绿：`AssetManager.open` 失败会被很多实现 catch 成默认值，于是
     * "读不到"与"文件是空的"看起来一样。这里直接断言读到的那两行**只有插件包才有**的文本。
     *
     * 更要紧的是：这一条把"命名空间"钉成事实。曾经的实现（和本测试的早期版本）
     * 以为 `open("plugin-root-probe.txt")` 能读 apk 包根 —— **错了**。
     * android-34 的 `AssetManager.java` 里 `open()` → `nativeOpenAsset` 只查 assets。
     * 所以这里既断言"包根用 openNonAssetFd 读得到"，也断言
     * "同一个名字用 open() 读不到"，两条合起来才是完整的证据。
     */
    @Test
    fun pluginAssets_andPackageRootFile_areMounted() {
        val plugin = loadFromAssets()
        try {
            // ① assets 命名空间：前缀是隐式的
            val asset = PluginAssets.readText(plugin.assets, "plugin-hello.txt")
            assertTrue(
                "读不到插件的 assets/plugin-hello.txt（读到的内容：$asset）",
                asset.contains("这个文件在 plugin-demo.apk 的 assets/ 目录里"),
            )
            // 用带前缀的名字去读**应当失败** —— 这就是"前缀是隐式的"的反证
            val withPrefix = PluginAssets.readText(plugin.assets, "assets/plugin-hello.txt")
            assertTrue(
                "open(\"assets/plugin-hello.txt\") 竟然成功了 —— open 的路径本不该带 assets/ 前缀，" +
                    "说明命名空间语义与预期不符，必须重新核对 AssetManager.java",
                withPrefix.contains("【读取失败】"),
            )

            // ② 包根命名空间：必须换 API
            val root = PluginAssets.readPackageRootFd(plugin.assets, "plugin-root-probe.txt")
            assertTrue(
                "openNonAssetFd 读不到包根文件 plugin-root-probe.txt（读到的内容：$root）\n" +
                    "这条链路有两个前提：① 走 non-asset 通道；② 该 zip 条目必须 STORED（未压缩），" +
                    "因为 openNonAssetFd 内部是 mmap。打包脚本用 `zip -0` 单独写它就是为了这个。",
                root.contains("这个文件躺在 apk 的**包根目录**"),
            )
            // ③ 反证：拿 assets 通道读包根文件，必然失败
            val rootViaOpen = PluginAssets.readText(plugin.assets, "plugin-root-probe.txt")
            assertTrue(
                "open(\"plugin-root-probe.txt\") 竟然读到了包根文件 —— " +
                    "这与 android-34 的 open()→nativeOpenAsset 只查 assets 的事实矛盾",
                rootViaOpen.contains("【读取失败】"),
            )
        } finally {
            plugin.release()
        }
    }

    /**
     * 断言 6：装配元数据里的 sha256 **等于**实际字节的 sha256。
     *
     * 这条断言的价值不在"能过"，而在**它能抓住打包脚本与宿主解析的漂移**：
     * 元数据里的 hash 一旦与包不一致，网络下发那条链路会拒绝加载 ——
     * 而拒绝的理由在真机上往往表现为"下载失败"，排查成本很高。
     * 放在测试里，改一处忘一处会立刻变红。
     */
    @Test
    fun embeddedManifest_sha256_matchesApkBytes() {
        val plugin = loadFromAssets()
        try {
            val manifest = plugin.manifest
            assertNotNull("assets 里的 plugin-package.xml 没解析出来", manifest)
            val actual = PluginDigest.sha256(plugin.apkFile)
            assertEquals(
                "元数据里的 sha256 与实际 apk 字节不一致 —— 网络下发时会拒绝加载",
                manifest!!.sha256,
                actual,
            )
            assertEquals(
                "元数据里的 size 与实际字节数不一致",
                manifest.size,
                plugin.apkFile.length(),
            )
            assertEquals(
                "元数据里的入口类名与宿主默认值不一致，会导致找不到入口",
                LoadedPlugin.DEFAULT_ENTRY,
                manifest.entryClass,
            )
        } finally {
            plugin.release()
        }
    }

    /**
     * 断言 7：两次装载互不复用 —— identity 每次都是新的。
     *
     * 防的假绿：ART 对同一个 dex 路径有内部缓存，所以第二次装载**很快**。
     * 若不看 identity，很容易得出"第二次复用了第一次的结果"这种错误结论，
     * 进而写出"清除插件后类已经没了"这样站不住的文档。
     */
    @Test
    fun reload_doesNotReusePreviousLoader() {
        val first = loadFromAssets()
        val second = loadFromAssets()
        try {
            assertNotEquals(
                "两次装载拿到了同一个 ClassLoader 实例 —— 装载过程被复用了",
                System.identityHashCode(first.classLoader),
                System.identityHashCode(second.classLoader),
            )
            assertNotEquals(
                "两次装载拿到了同一个 Resources 实例",
                System.identityHashCode(first.resources),
                System.identityHashCode(second.resources),
            )
            assertNotEquals(
                "两次装载的类身份相同（同一个 Class 对象）—— 说明第二次没有重新加载",
                System.identityHashCode(first.classLoader.loadClass(LoadedPlugin.DEFAULT_ENTRY)),
                System.identityHashCode(second.classLoader.loadClass(LoadedPlugin.DEFAULT_ENTRY)),
            )
        } finally {
            first.release()
            second.release()
        }
    }

    /**
     * 断言 8：宿主与插件的 Resources **不是同一个对象**（隔离策略下）。
     *
     * 这条最直白也最容易被忽略：如果插件用的是宿主 Resources（很多"能跑就行"的
     * 实现就是这么干的），那么断言 4 之所以能过，就纯粹是因为 ID 恰好撞上了别的资源，
     * 而插件自己的布局其实永远解析不出来。两条断言合起来才构成完整证据。
     */
    @Test
    fun isolatedStrategy_pluginResourcesIsNotHostResources() {
        val plugin = loadFromAssets()
        try {
            assertNotSame(
                "隔离策略下插件 Resources 竟然就是宿主 Resources",
                context.resources,
                plugin.resources,
            )
            assertEquals(
                "资源包名不是插件的包名 —— 资源表没挂对",
                "com.interview.plugindemo",
                plugin.resources.getResourcePackageName(
                    PluginAssets.parseResIds(plugin.assets).first { it.name == "plugin_title" }.id
                ),
            )
        } finally {
            plugin.release()
        }
    }

    /**
     * 断言 9：**插件包真的在宿主 apk 之外**。
     *
     * 做法：把内嵌的插件包从 assets 拷出来后，它的路径必须是**私有目录**里的一个文件
     * （而不是宿主 apk 内的某个虚拟路径）。这条断言把"内嵌"这个实现细节
     * 与"独立文件"这个事实分开：内嵌只是分发手段，加载的仍然是一个独立文件。
     */
    @Test
    fun stagedPlugin_isAStandaloneFile() {
        val plugin = loadFromAssets()
        try {
            assertTrue("插件文件不存在：${plugin.apkFile}", plugin.apkFile.isFile)
            assertTrue(
                "插件文件不在宿主私有目录里（实际：${plugin.apkFile.absolutePath}）—— " +
                    "加载的应该是一个独立文件，而不是宿主 apk 内的条目",
                plugin.apkFile.absolutePath.startsWith(context.filesDir.absolutePath) ||
                    plugin.apkFile.absolutePath.startsWith(context.getExternalFilesDir(null)!!.absolutePath),
            )
            val hostApk = context.applicationInfo.sourceDir
            assertNotEquals("插件文件竟然是宿主 apk 本身", hostApk, plugin.apkFile.absolutePath)
            // 内嵌副本的字节必须与盘上的一致（拷贝没有损坏）
            val embedded = context.assets.open(PluginLoader.EMBEDDED_APK).use { PluginDigest.sha256(it) }
            assertEquals(
                "从 assets 拷出来的插件包与内嵌字节不一致 —— 拷贝过程损坏了文件",
                embedded,
                PluginDigest.sha256(plugin.apkFile),
            )
        } finally {
            plugin.release()
        }
    }

    /**
     * 断言 10：**私有目录来源**能加载到一个**盘上真实存在的独立文件**。
     *
     * ⚠️ 这条测试**自己**把包落到私有目录，而不是等 `deploy.sh` 先推 ——
     *    原因是实测出来的：`connectedAndroidTest` 在跑之前会重装宿主，
     *    而重装会清掉 `/sdcard/Android/data/<pkg>/`（也就是 getExternalFilesDir）。
     *    于是"先跑 deploy.sh 再跑测试"这种用法**必然**失败，且失败原因与代码无关。
     *    把落盘动作放进测试里，验的才是 PRIVATE_DIR 那条**装载路径**，
     *    而不是"设备上碰巧有没有文件"。
     *
     *    （真机手动演示时仍用 tools/plugin-package/deploy.sh 推包 —— 见该脚本注释。）
     */
    @Test
    fun privateDirSource_loadsStandaloneFileFromDisk() {
        // 落一份到私有目录（与 deploy.sh 推的同一个候选位置：filesDir/plugin/）
        val dir = File(context.filesDir, "plugin").apply { mkdirs() }
        val staged = File(dir, PluginLoader.EMBEDDED_APK)
        context.assets.open(PluginLoader.EMBEDDED_APK).use { input ->
            staged.outputStream().use { input.copyTo(it) }
        }
        Log.i(TAG, "已落盘：${staged.absolutePath} (${staged.length()} bytes)")

        val plugin = PluginLoader.load(
            context,
            PluginLoader.Options(source = PluginLoader.Source.PRIVATE_DIR),
        )
        try {
            assertTrue(
                "PRIVATE_DIR 来源拿到的路径是 ${plugin.apkFile.absolutePath}，" +
                    "不是我们落盘的那个 ${staged.absolutePath}",
                plugin.apkFile.absolutePath == staged.absolutePath,
            )
            assertEquals(
                "私有目录来源的字节与内嵌包不一致 —— 装载过程被弄脏了",
                PluginDigest.sha256(staged),
                PluginDigest.sha256(plugin.apkFile),
            )
            val instance = plugin.classLoader.loadClass(LoadedPlugin.DEFAULT_ENTRY)
                .getDeclaredConstructor().newInstance() as IPluginPage
            val desc = instance.describe()
            assertTrue(
                "私有目录来源拿到的实例自证里没有 PluginEntry —— 实际内容：\n$desc",
                desc.contains("PluginEntry"),
            )
        } finally {
            plugin.release()
        }
    }

    private companion object {
        const val TAG = "PluginInstrumented"
    }
}
