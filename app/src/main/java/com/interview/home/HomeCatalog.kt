package com.interview.home

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 首页功能的**唯一登记处**。
 *
 * ─── 约定 ───
 *
 * 1. 新增一个可被首页直达的 Activity，只需在 [LABS] 或 [BASICS] 里加一行；
 * 2. 两个列表分别对应底部 duotab 的「实验台 / 基础」两栏；
 * 3. 不要在这里写反射或 Class.forName —— 直接引用 KClass 才能在编译期发现拼写错误。
 *    这几个类横跨三套包名（com.wgt / com.example / com.interview），
 *    用 import 显式列出来，顺带也是一份「哪些页还活着」的清单。
 *
 * ─── 与 Manifest 的关系 ───
 *
 * 登记表负责「点得到」，Manifest 负责「声明了 exported 才能被系统拉起」。
 * 两者是互补的，不要合并 —— Manifest 无法表达分组、副标题这些展示信息。
 */
object HomeCatalog {

    /** 实验台：有成体系的证据链、按里程碑推进的 Lab */
    val LABS: List<HomeEntry> = listOf(
        HomeEntry(
            title = "大图加载 Lab",
            subtitle = "瀑布流 + Glide 降采样，观察内存水位回落",
            activityClass = com.interview.image.ImageLabActivity::class.java,
            tag = "image",
        ),
        HomeEntry(
            title = "降采样解剖台",
            subtitle = "裸 BitmapFactory 逐步展开 inSampleSize 的推导过程",
            activityClass = com.interview.image.DecodeInspectorActivity::class.java,
            tag = "image",
        ),
        HomeEntry(
            title = "三态对照实验",
            subtitle = "normal / wrap_content / override 三种目标尺寸的持有内存对比",
            activityClass = com.interview.image.DecodeComparisonActivity::class.java,
            tag = "image",
        ),
        HomeEntry(
            title = "网络优化 Lab",
            subtitle = "OkHttp 感知 / 度量 / DNS / 重试四层证据链",
            activityClass = com.interview.net.NetLabActivity::class.java,
            tag = "net",
        ),
        HomeEntry(
            title = "网络度量仪表盘",
            subtitle = "图形化：阶段堆叠 / 长尾分位 / 切网事件 / 弱网模拟器",
            activityClass = com.interview.net.NetDashboardActivity::class.java,
            tag = "net",
        ),
        HomeEntry(
            title = "线程治理 Demo",
            subtitle = "四层防线分步演示，证明「谁在跑」",
            activityClass = com.interview.thread.ThreadGovernanceActivity::class.java,
            tag = "thread",
        ),
    )

    /** 基础：单点知识 / 控件练习页 */
    val BASICS: List<HomeEntry> = listOf(
        HomeEntry(
            title = "LiveData 计数",
            subtitle = "ViewModel + LiveData 观察者与生命周期感知",
            activityClass = com.interview.liveData.ui.LiveDataDemoActivity::class.java,
            tag = "livedata",
        ),
        HomeEntry(
            title = "Handler 内存泄漏",
            subtitle = "非静态内部类持有 Activity 的引用链与弱引用自证",
            activityClass = com.interview.leak.HandlerLeakActivity::class.java,
            tag = "leak",
        ),
        HomeEntry(
            title = "自定义 ViewGroup",
            subtitle = "FlowLayout 流式布局的 onMeasure / onLayout 全流程",
            activityClass = com.example.myapplication.activity.MainActivity::class.java,
            tag = "view",
        ),
        HomeEntry(
            title = "ViewPager 轮播",
            subtitle = "PageTransformer 3D 效果 + 自动轮播与指示器",
            activityClass = com.example.myapplication.activity.Main2Activity::class.java,
            tag = "view",
        ),
        HomeEntry(
            title = "RecyclerView Demo",
            subtitle = "LayoutManager 布局与 ItemDecoration / 预取开关",
            activityClass = com.wgt.recyclerview.MainActivity::class.java,
            tag = "recyclerview",
        ),
        HomeEntry(
            title = "自定义绘制",
            subtitle = "SimpleColorChangeTextView 属性动画驱动重绘",
            activityClass = com.wgt.draw_text.MainActivity::class.java,
            tag = "draw",
        ),
    )

    /** 底部 duotab 的两栏 */
    val TABS: List<List<HomeEntry>> = listOf(LABS, BASICS)

    val TAB_TITLES: List<String> = listOf("实验台", "基础")
}
