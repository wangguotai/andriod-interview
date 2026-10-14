package com.interview.plugin

import android.content.res.AssetManager

/**
 * Time: 2026/10/13
 * Author: wgt
 * Description: 通过**插件自己的** AssetManager 读 assets 与 apk 包根文件。
 *
 * ─── 为什么这层要单独存在（为什么不直接 assets.open("…")）───
 *
 * 因为 `AssetManager.open(name)` 里的 `name` 落在**哪一个命名空间**，
 * 决定了要调用哪个 API —— 而这件事极其容易写错，且错了是**静默**的：
 *
 *   · assets 命名空间：`open("plugin-hello.txt")` 读的是 apk 的
 *     `assets/plugin-hello.txt`。**`assets/` 前缀是隐式的** ——
 *     写 `open("assets/plugin-hello.txt")` 去找的是 `assets/assets/...`，必然失败。
 *   · 包根命名空间（apk 里不在 assets/ 下的条目）：`open()` **读不到**，
 *     必须走 `openNonAssetFd()`，且该条目在 zip 里要 STORED（未压缩）。
 *
 * 依据不是记忆，是源码：android-34 的 `AssetManager.java` 里
 * `open()` → `nativeOpenAsset`，`openNonAsset()` → `nativeOpenNonAsset`，
 * 是两个不同的 native 入口。这两条边界都曾被本文件注释写错，所以现在
 * [selfCheck] 里放了一条**注定失败**的对照（拿 open 去读包根文件），
 * 让"命名空间"成为一个每次运行都会重演一次的事实，而不是一句注解。
 *
 * ─── 为什么 list() 也要包一层 ───
 *
 * `AssetManager.list("")` 在挂了多个 apk 时的返回内容是**并集**，
 * 且不会告诉你某个名字来自哪个包。所以这里的做法是：把 list 结果原样给出，
 * **再对每个感兴趣的名字逐个尝试读取**。读成功 = 那个包确实提供它。
 * 这比"看 list 里有没有"可靠得多 —— list 是"有人提供"，open 才是"我能拿到"。
 */
object PluginAssets {

    /**
     * 读 **assets 命名空间**下的文本（`open(name)` 的 `name` **不带** `assets/` 前缀）。
     *
     * ⚠️ 这个前缀的隐式性是有出处的，不是猜的：android-34 的
     *    `AssetManager.open(String)` → `nativeOpenAsset`，native 层只查 assets 命名空间。
     *    所以 `open("plugin-hello.txt")` 读的是 apk 里的 `assets/plugin-hello.txt`。
     *    早期本文件的注释写成"包根文件也能用 open() 读"，是**错的** —— 见 [readPackageRootFd]。
     */
    fun readText(assets: AssetManager, name: String): String = try {
        assets.open(name).bufferedReader().use { it.readText() }
    } catch (t: Throwable) {
        "【读取失败】$name → ${t.javaClass.simpleName}: ${t.message}"
    }

    /**
     * 用 `openNonAssetFd` 读 **apk 包根**（非 assets 命名空间）的文件 —— 这是公开 API。
     *
     * 两个前提缺一不可，且都容易踩：
     *   ① 这个名字**不在** assets 命名空间里，所以必须走 non-asset 通道
     *      （`open(name)` 对它必然 FileNotFoundException）；
     *   ② 该条目在 zip 里必须**未压缩**（STORED），否则会抛
     *      "This file can not be opened as a file descriptor; it is probably compressed"
     *      —— 它内部是 mmap。打包脚本用 `zip -0` 单独写这个文件就是为了这一条。
     *
     * 之所以值得在演示里保留这条通道：它把"AssetManager 里其实有两个命名空间"
     * 这个常被忽略的事实变成可执行的对照（见 [selfCheck]）。
     */
    fun readPackageRootFd(assets: AssetManager, name: String): String = try {
        assets.openNonAssetFd(name).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    } catch (t: Throwable) {
        "【读取失败】$name → ${t.javaClass.simpleName}: ${t.message}"
    }

    /** 列出某个目录（相对挂载点）下的条目。空目录返回空数组而不是 null。 */
    fun list(assets: AssetManager, dir: String): List<String> = try {
        assets.list(dir)?.toList() ?: emptyList()
    } catch (t: Throwable) {
        listOf("【list 失败】${t.javaClass.simpleName}: ${t.message}")
    }

    /**
     * 插件的自检：对两个命名空间各做一次读取，把"哪一层能读到"变成可读结果。
     *
     * 这两个名字全部由 `tools/plugin-package/package.sh` 决定，
     * 且都**不在宿主的 apk 里** —— 所以"读到了"这件事本身就是在证明
     * addAssetPath 确实把插件包挂上了。
     *
     * ⚠️ 关键对照（真机实测 + android-34 源码双重复核）：
     *    · assets 命名空间：`open("plugin-hello.txt")` → 拿到 apk 的
     *      `assets/plugin-hello.txt`。**前缀是隐式的**，写 `assets/plugin-hello.txt`
     *      反而会去找 `assets/assets/plugin-hello.txt` 而失败 —— 这一点很多人会写错。
     *    · 包根命名空间：`open("plugin-root-probe.txt")` **必然失败**，
     *      必须走 `openNonAssetFd`，且该条目在 zip 里要 STORED（未压缩）。
     */
    fun selfCheck(assets: AssetManager): String = buildString {
        append("· 宿主 apk 里没有以下文件，读到即证明资源来自插件：\n")
        append("  [assets 命名空间] open(\"plugin-hello.txt\")            → ")
        append(firstLine(readText(assets, "plugin-hello.txt"))).append('\n')
        append("  [assets 命名空间] open(\"plugin-res-ids.txt\")          → ")
        append(firstLine(readText(assets, "plugin-res-ids.txt"))).append('\n')
        append("  [包根命名空间]     openNonAssetFd(\"plugin-root-probe.txt\") → ")
        append(firstLine(readPackageRootFd(assets, "plugin-root-probe.txt"))).append('\n')
        append('\n')
        append("  ⚠️ 对照实验：拿 assets 通道去读包根文件，**必然**失败 ——\n")
        append("     open(\"plugin-root-probe.txt\") → ")
        append(firstLine(readText(assets, "plugin-root-probe.txt"))).append('\n')
        append("     这就是「AssetManager 有两个命名空间」的可执行证据：\n")
        append("     open() 只查 assets（android-34 里 open→nativeOpenAsset），\n")
        append("     包根文件只能由 openNonAsset/openNonAssetFd 读到。\n")
        append("\n· 挂载点根目录 list(\"\")：").append(list(assets, "").joinToString(", ")).append('\n')
        append("· assets/ 目录 list：").append(list(assets, "assets").joinToString(", "))
    }

    private fun firstLine(s: String): String {
        val line = s.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (s.contains("【读取失败】")) s.lineSequence().first().trim()
        else if (line.isEmpty()) "（空文件）" else line
    }

    /**
     * 解析插件自带的 `plugin-res-ids.txt`（由打包脚本从 aapt2 dump 导出）。
     *
     * 格式：每行 `type name 0x7f030000`，`#` 或空行忽略。
     *
     * ⚠️ 为什么让插件**自己携带**这张表：
     * 宿主**不能**引用插件的 R 类（那会把插件编回宿主，整个演示就没意义了）。
     * 但"同一个 ID 在两张表里指向不同资源"这条对照必须有个 ID 来源。
     * 把表放进插件包，对照组的两边就都能拿到同一个数字 —— 而宿主里依然
     * 没有一行代码知道插件的资源。
     */
    fun parseResIds(assets: AssetManager): List<PluginResId> {
        // ⚠️ 走 assets 命名空间（open 的隐式前缀），不是包根 ——
        //    打包脚本把这张表放进了 apk 的 assets/ 下，理由见 package.sh [3b/6]。
        val raw = readText(assets, "plugin-res-ids.txt")
        if (raw.contains("【读取失败】")) return emptyList()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 3) return@mapNotNull null
                val id = parts[2].removePrefix("0x").toLongOrNull(16)?.toInt() ?: return@mapNotNull null
                PluginResId(type = parts[0], name = parts[1], id = id)
            }
            .toList()
    }
}

/** 插件资源表里的一项：类型 / 名字 / 打包后的 ID */
data class PluginResId(val type: String, val name: String, val id: Int) {
    val idHex: String get() = "0x" + Integer.toHexString(id)
}
