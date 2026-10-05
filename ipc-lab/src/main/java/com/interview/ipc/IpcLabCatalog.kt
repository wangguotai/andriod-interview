package com.interview.ipc

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 本 Lab 的**唯一演示登记处**（与主 app 的 HomeCatalog 同一套约定）。
 *
 * 新增一个演示 = 在 [DEMOS] 里加一条 + 在 [IpcDemoRunner] 里注册实现。
 * 首页与汇总页都只读这里，不硬编码任何演示。
 *
 * 本文件随里程碑逐步生长：每完成一个里程碑，就在 [DEMOS] 追加对应条目。
 */
object IpcLabCatalog {

    val DEMOS: List<IpcDemo> = listOf()

    /** 汇总页按「层级 → 模型」分组展示用。 */
    fun byLayer(layer: IpcLayer): List<IpcDemo> = DEMOS.filter { it.layer == layer }
}
