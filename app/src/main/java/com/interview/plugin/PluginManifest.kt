package com.interview.plugin

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Time: 2026/10/13
 * Author: wgt
 * Description: 插件元数据 —— 「从网络接口下发插件」这条链路的契约文件。
 *
 * 对应 tools/plugin-package/package.sh 产出的 `plugin-package.xml`：
 *
 * ```xml
 * <plugin id="com.interview.plugindemo" version="1.0.0"
 *         sha256="…" size="16136"
 *         entryClass="com.interview.plugindemo.PluginEntry"
 *         activityClass="com.interview.plugindemo.ui.PluginPageActivity"
 *         minSdk="24" builtAt="…" />
 * ```
 *
 * ─── 这张表存在的理由 ───
 *
 * 没有它，宿主加载插件就只能**硬编码**类名（`com.interview.plugindemo.PluginEntry`）。
 * 硬编码在演示里没问题，但它掩盖了真实插件体系的第一个工程问题：
 * **宿主怎么知道要加载什么**。答案是"服务端下发时一并给"，而不是"宿主里写死"。
 *
 * 所以本类只做三件事，都围绕"信任边界"：
 *   · 解析下发内容（解析失败必须报错，不能吞掉给默认值）
 *   · 用 sha256 **核对字节** —— 下载到一半、被中间人替换、服务端传错文件，
 *     这三种都是网络下发插件最常见的真实故障，且它们在"能加载吗"这一层
 *     是**看不见**的（损坏的 dex 有时仍能被 ART 接受一部分）。
 *   · 列出最低系统版本，让宿主能在加载前给出可读的拒绝理由。
 */
data class PluginManifest(
    val id: String,
    val version: String,
    val sha256: String,
    val size: Long,
    val entryClass: String,
    val activityClass: String,
    val minSdk: Int,
) {
    /** 界面展示用；不参与任何判定逻辑 */
    fun describe(): String = buildString {
        append("id=").append(id).append("  version=").append(version).append('\n')
        append("size=").append(size).append("  minSdk=").append(minSdk).append('\n')
        append("entryClass=").append(entryClass).append('\n')
        append("activityClass=").append(activityClass).append('\n')
        append("sha256=").append(sha256)
    }

    companion object {
        fun parse(input: InputStream): PluginManifest {
            val parser = Xml.newPullParser()
            parser.setInput(input, null)
            var id = ""; var ver = ""; var sha = ""; var size = 0L
            var entry = ""; var activity = ""; var minSdk = 0
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "plugin") {
                    id = parser.getAttributeValue(null, "id").orEmpty()
                    ver = parser.getAttributeValue(null, "version").orEmpty()
                    sha = parser.getAttributeValue(null, "sha256").orEmpty()
                    size = parser.getAttributeValue(null, "size")?.toLongOrNull() ?: 0L
                    entry = parser.getAttributeValue(null, "entryClass").orEmpty()
                    activity = parser.getAttributeValue(null, "activityClass").orEmpty()
                    minSdk = parser.getAttributeValue(null, "minSdk")?.toIntOrNull() ?: 0
                    break
                }
                event = parser.next()
            }
            // ⚠️ 这里**不**给"解析不到就当作空字符串继续跑"的宽容：
            //    元数据缺字段意味着下发的契约不完整，早失败早定位。
            //    （真实插件化里这类静默降级最贵：表现为"插件加载成功但白屏"，
            //      排查要从 dex 看到网络配置，最后才发现是元数据少一个属性。）
            require(id.isNotEmpty() && entry.isNotEmpty()) {
                "plugin-package.xml 缺少必要属性（id/entryClass），拒绝加载"
            }
            return PluginManifest(id, ver, sha, size, entry, activity, minSdk)
        }
    }
}

/**
 * 字节核对工具。刻意手写而不是引三方：这里只需要一个方法，且它在**信任边界**上，
 * 依赖越少越好审。
 */
object PluginDigest {

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 只比对前缀可读部分，用于日志（完整 64 位十六进制在手机屏幕上一行放不下） */
    fun short(hash: String): String = if (hash.length > 16) hash.take(16) + "…" else hash
}
