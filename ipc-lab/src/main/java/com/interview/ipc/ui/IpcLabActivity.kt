package com.interview.ipc.ui

import android.os.Bundle
import android.os.Process
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.interview.ipc.DemoResult
import com.interview.ipc.IpcDemo
import com.interview.ipc.IpcDemoRunner
import com.interview.ipc.IpcLabCatalog
import com.interview.ipc.IpcLayer
import com.interview.ipc.nativebridge.IpcNativeBridge
import com.interview.ipc.databinding.ActivityIpcLabBinding
import com.interview.ipc.databinding.ItemIpcDemoBinding
import java.util.concurrent.Executors

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 跨进程通信 Lab 主界面 —— 演示清单 + 证据日志面板。
 *
 * ─── 交互模型 ───
 *
 * 左侧/上方是演示列表（按层级分组），点击某条即在本页内**运行**它，结果显示在下方的
 * 日志面板。不做「一个演示一个 Activity」——那样会切走证据；把结果留在同一屏，
 * 才能边跑边对照。
 *
 * ─── 线程纪律 ───
 *
 * 所有 runner 都在单线程 executor 上跑：演示里有 sleep / 阻塞 IO / 绑定等待，
 * 放主线程必 ANR。串行执行还保证了「上一次演示的远端进程状态」不会和下一次交叉。
 * 结果回到主线程更新 UI。
 */
class IpcLabActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIpcLabBinding

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ipc-lab-demo")
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val logBuffer = StringBuilder()
    private var running = false

    private val adapter = DemoAdapter { demo -> runDemo(demo) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIpcLabBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.ipcPid.text = "pid=${Process.myPid()}  ${IpcNativeBridge.describe()}"
        binding.ipcList.layoutManager = LinearLayoutManager(this)
        binding.ipcList.adapter = adapter
        adapter.submit(IpcLabCatalog.DEMOS)

        binding.ipcClear.setOnClickListener { logBuffer.setLength(0); renderLog() }
        // 汇总页：把每种机制按「模型」摆在一起做结论（不是又一个演示，而是对比）。
        binding.ipcSummary.setOnClickListener { showSummary() }

        appendLine("跨进程通信 Lab 就绪。")
        appendLine("点击上方任一演示开始；结果追加到本面板。")
        appendLine("所有演示都跑在进程 pid=${Process.myPid()}（远端组件在 :ipc_remote）。")
        appendLine("")
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun runDemo(demo: IpcDemo) {
        if (running) {
            appendLine("⏳ 上一个演示还在跑，请稍候…")
            return
        }
        running = true
        binding.ipcStatus.text = "运行中：${demo.title}"
        appendLine("════════ ${demo.layer.label} · ${demo.title} ════════")
        worker.execute {
            val result = IpcDemoRunner.run(this, demo.id)
            main.post {
                when (result) {
                    is DemoResult.Success -> appendLine(result.log)
                    is DemoResult.Failure -> {
                        appendLine("❌ 未能完成：${result.reason}")
                        appendLine("")
                    }
                }
                appendLine("──────── ${demo.title} 结束 ────────")
                appendLine("")
                binding.ipcStatus.text = "就绪"
                running = false
            }
        }
    }

    /** 汇总对比：把机制按「通信模型」归纳，给出选型判断，而不是罗列名字。 */
    private fun showSummary() {
        val sb = StringBuilder()
        sb.appendLine("════════ 汇总对比：按通信模型选型 ════════")
        sb.appendLine("")
        IpcLabCatalog.DEMOS.groupBy { it.model }.forEach { (model, list) ->
            sb.appendLine("● ${model.label}")
            list.forEach { d -> sb.appendLine("    - [${d.layer.label}] ${d.title}") }
            sb.appendLine("    适用：${modelGuidance(model)}")
            sb.appendLine("")
        }
        sb.appendLine("● 两条线的分工")
        sb.appendLine("    Android framework：进程模型清晰、有权限体系、与系统/其它 App 互操作方便；")
        sb.appendLine("                       代价是多了 Parcel 序列化与 AMS/Binder 驱动开销。")
        sb.appendLine("    Linux 原生(JNI/Rust)：直接操作内核原语（fd/共享内存/信号），零序列化；")
        sb.appendLine("                       代价是要自己处理跨架构、SELinux、生命周期与安全问题。")
        sb.appendLine("    二者不是替代关系：Binder 本身就跑在 Linux 之上，ashmem/fd 传递都是内核能力。")
        sb.appendLine("")
        sb.appendLine("（本页是静态结论文本；上面每个演示的日志才是可复核的证据。）")
        appendLine(sb.toString())
        binding.ipcLogScroll.post { binding.ipcLogScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun modelGuidance(model: com.interview.ipc.IpcModel): String = when (model) {
        com.interview.ipc.IpcModel.RPC -> "需要「调用方法拿结果」。同 App 内跨进程首选 AIDL；轻量异步可用 Messenger。"
        com.interview.ipc.IpcModel.PUBSUB -> "一对多、不关心谁收到、无返回值。注意隐式广播限制，尽量用显式 action。"
        com.interview.ipc.IpcModel.DATA_ACCESS -> "要共享结构化数据或把文件/大块数据交给别的进程。openFile 传 fd 可绕开对端权限。"
        com.interview.ipc.IpcModel.BYTE_STREAM -> "长连接、双向、流式。本机优先 AF_UNIX（尤其 abstract 命名空间）；跨机才用 TCP。"
        com.interview.ipc.IpcModel.PIPE -> "单向父子/近亲进程的简单数据流。容量与阻塞语义要心里有数（PIPE_BUF=4096）。"
        com.interview.ipc.IpcModel.SHARED_MEMORY -> "大块数据、追求零拷贝。Android 上对应 ashmem/MemoryFile + Binder 传 fd。"
        com.interview.ipc.IpcModel.SIGNAL -> "只做「通知」，信息量极小、会合并（实时信号除外）。适合系统/原生层，不适合传业务数据。"
        com.interview.ipc.IpcModel.LOCK -> "只做互斥，不传数据。单实例、临界区串行化用它；注意它是劝告锁。"
    }

    private fun appendLine(text: String) {
        logBuffer.append(text).append('\n')
        renderLog()
    }

    private fun renderLog() {
        binding.ipcLog.text = logBuffer
        binding.ipcLogScroll.post { binding.ipcLogScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ══════════════════════════════════════════════════════════════════
    // 列表适配器（很简单，直接写在这里，不值得单开文件）
    // ══════════════════════════════════════════════════════════════════

    private class DemoAdapter(
        private val onClick: (IpcDemo) -> Unit,
    ) : RecyclerView.Adapter<DemoAdapter.VH>() {

        private val items = mutableListOf<IpcDemo>()

        fun submit(list: List<IpcDemo>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemIpcDemoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], onClick)

        override fun getItemCount(): Int = items.size

        class VH(private val b: ItemIpcDemoBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(demo: IpcDemo, onClick: (IpcDemo) -> Unit) {
                b.demoTitle.text = demo.title
                b.demoSubtitle.text = demo.subtitle
                b.demoLayer.text = demo.layer.label
                b.demoLayer.setBackgroundColor(
                    if (demo.layer == IpcLayer.FRAMEWORK) 0xFFE3F2FD.toInt() else 0xFFFFF3E0.toInt()
                )
                b.root.setOnClickListener { onClick(demo) }
            }
        }
    }
}
