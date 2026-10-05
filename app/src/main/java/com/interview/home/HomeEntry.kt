package com.interview.home

import android.app.Activity
import java.io.Serializable

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 首页可直达的一个功能页描述。
 *
 * ─── 为什么要有这个模型 ───
 *
 * 这个工程的功能页（Activity）散落在 com.wgt / com.example / com.interview 三套包名里，
 * 过去每加一个 Demo 就改一次 Manifest 里的 LAUNCHER，谁最后改谁就是入口 ——
 * 结果入口不稳定，也没人知道「现在到底有哪些功能」。
 *
 * 所以首页不靠反射、不靠包扫描，而是**显式登记**：
 * 想出现在首页的功能页，就在 [HomeCatalog] 里加一条。
 * 类名写错 / Activity 被删，编译期就报错，不会等到线上点进去才崩。
 */
data class HomeEntry(
    /** 主标题，如「大图加载 Lab」 */
    val title: String,
    /** 副标题，一句话说清这个页要验证什么 */
    val subtitle: String,
    /** 目标 Activity，编译期类型安全 */
    val activityClass: Class<out Activity>,
    /** 归类标签，仅用于展示，如 image / net / thread */
    val tag: String = "",
) : Serializable
