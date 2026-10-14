package com.interview.plugindemo.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.interview.plugin.contract.IPluginPage
import com.interview.plugindemo.PluginEntry
import com.interview.plugindemo.R

/**
 * Time: 2026/10/13
 * Author: wgt
 * Description: 插件侧的真 Activity —— 「组件形态」。
 *
 * ─── 为什么它必须继承 `android.app.Activity` 而不是 AppCompatActivity ───
 *
 * 插件要零依赖（见 plugin-demo/build.gradle.kts）。AppCompatActivity 会拉进
 * appcompat + fragment + lifecycle，插件 dex 立刻需要第二条类路径。用平台
 * `Activity` 则只需要 `android.jar` —— 而那是**系统的**引导类加载器提供的，
 * 不需要我们操心。
 *
 * ─── 这个 Activity 怎么被拉起来（本演示最需要说清楚的一条边界）───
 *
 * 它**不在宿主的 manifest 里**，所以系统不认识它：直接
 * `startActivity(Intent().setClassName(pkg, "com.interview.plugindemo.ui.PluginPageActivity"))`
 * 会以 `ActivityNotFoundException` / `SecurityException` 收场。
 *
 * 宿主侧的做法是 **占坑 + 反射注入** —— 而且"占坑"用的就是宿主自己的
 * `PluginLabActivity`（不是另造一个 stub）：它本来就在 manifest 里注册过，
 * 于是天然是"系统认识的那个组件"。关键三步（见 LoadedPlugin.attachActivity）：
 *
 *   // ① 造真身对象：反射 Instrumentation.newActivity(classLoader, 插件类名, intent)
 *   // ② 补齐系统依赖：反射 Activity.attach(…) 把宿主 stub 的
 *   //     mMainThread / mToken / mActivityInfo / mInstrumentation 灌进去
 *   // ③ 走生命周期：反射 Activity.performCreate(Bundle) 真正触发 onCreate
 *   // 插件视图则挂进宿主 stub 传入的容器 ViewGroup（而不是新开一个 task）
 *
 * ⚠️ 第 ①②③ 步是本演示里**最脆**的一段：`Instrumentation.newActivity` 与
 *    `Activity.attach` 的参数签名都属非公开面，随 API 版本变化（API 34 的
 *    `attach` 就比 31 多一个参数）。宿主侧对每一步都按参数**类型**泛化填参、
 *    并做成"失败就明确报错 + 回落到控制器形态"，而不是默默崩溃。
 *    这条退化路径本身就是结论：**Activity 形态的成本不在类加载，在生命周期接管**。
 *
 * ─── 本类的一个额外能力：自杀指令 ───
 *
 * 收到 `action=kill` 时调用 `finish()` 并立刻 `Process.killProcess`。
 * 用途见宿主页的"清除插件"按钮：证明「插件类是随 ClassLoader 一起消失的,
 * 不是被缓存住」—— 清完再点控制器入口会真的加载不到类。
 */
class PluginPageActivity : Activity() {

    private var page: IPluginPage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ⚠️ 这里刻意用插件自己的 R.layout —— 能被 inflate 出来，就说明
        //    宿主注入进来的 Context/Resources 指向的是插件 APK 的资源表。
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.plugin_activity_label)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(mono(
            """
            · 本 Activity 类     : ${javaClass.name}
            · 类加载器           : ${javaClass.classLoader?.javaClass?.name}
            · 加载器 identity    : ${System.identityHashCode(javaClass.classLoader)}
            · 宿主 Activity 类   : 无 —— 我是被宿主反射造出来的 Activity 本体，
                                   不是被 attach 到宿主某个 Activity 上的子视图
            · 我的 mBase（ContextImpl）: ${baseContext.javaClass.name}
            · 我的 mToken 是否有值: ${try { javaClass.getMethod("getActivityToken").invoke(this) != null } catch (t: Throwable) { "读取失败(${t.javaClass.simpleName})" }}
            · 本 Activity 的 Resources identity : ${System.identityHashCode(resources)}
            · 资源包名           : ${resources.getResourcePackageName(R.string.plugin_activity_label)}
            · 生命周期           : onCreate（由宿主反射 performCreate 触发）
            · 摘掉的 intent extra: ${intent?.getStringExtra("plugin.echo") ?: "无"}
            """.trimIndent()
        ))

        // 复用控制器实现，把「同一份插件代码既能当控制器、也能当 Activity 跑」摆出来。
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(holder)
        try {
            val impl = PluginEntry()
            impl.onCreate(this, holder, intent?.getStringExtra("plugin.payload"))
            page = impl
        } catch (t: Throwable) {
            root.addView(mono("控制器实现复用失败：${t.javaClass.simpleName}: ${t.message}"))
        }

        setContentView(ScrollView(this).apply { addView(root) })

        if (intent?.getStringExtra("plugin.action") == "kill") {
            // 自杀：验证"类随加载器消失"。用 Process.killProcess 而不是 finish()，
            // 因为 finish 后进程还在，ClassLoader 的类仍然被 ART 缓存在
            // ClassTable 里 —— 那观察不出隔离，反而会被误读成"清除无效"。
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    override fun onDestroy() {
        page?.onDestroy()
        page = null
        super.onDestroy()
    }

    private fun mono(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()
}
