package com.interview.image

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.interview.thread.ThreadPools
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Time: 2026/10/3
 * Author: wgt
 * Description: 「大图加载」教学的第一步 —— 把原图下载到磁盘缓存，
 * 供后续 BitmapFactory 逐步解码（inJustDecodeBounds / inSampleSize）使用。
 *
 * 本类的两个设计红线（都是这份教学能成立的前提）：
 *
 * 一、网络请求必须走本仓库的统一泳道 [ThreadPools.network]。
 *   绝不能在类里 new Thread / 用 Executors / HandlerThread —— 那不是「实现细节」，
 *   而是会绕过全 App 的命名、配额与背压治理，让线程快照失真、让某个模块能把
 *   整条网络泳道吃满。所以下载任务统一用 `ThreadPools.network.execute(caller, task)` 提交。
 *
 * 二、泳道的拒绝是**有意的背压**，不能静默吞掉。
 *   execute 返回 false 表示「单调用方配额超限」或「队列已满」，这是系统在告诉你
 *   「现在别再压任务了」。如果这里假装没发生、直接忽略返回值，一方面调用方的
 *   降级逻辑永远不会被触发，另一方面泳道的拒绝指标（rejectedByQueue/Quota）
 *   会持续上涨却没有任何业务侧反馈 —— 问题被掩盖到线上才暴露。
 *   因此：首次被拒 → 短暂延迟后重试**一次**（给瞬时过载一个自愈窗口）；
 *   重试仍被拒 → 通过 callback.onError 明确上报，交给上层降级。
 *
 * 其他约束：
 * - 同一张图并发请求**不得重复下载**：用按 spec.id 归并的在途表，后到的请求
 *   挂到同一个在途条目上，共享同一次网络下载的结果。
 * - callback 一律在主线程回调（教学 demo 里调用方基本都要更新 UI）。
 * - OkHttpClient 是重量级资源（连接池 + 线程池 + Dispatcher），必须全局单例，
 *   绝不能每个 Activity 建一个。
 */
class ImageDownloader(context: Context) {

    interface Callback {
        fun onSuccess(file: File)
        fun onError(error: Throwable)
    }

    // applicationContext：下载器可能比 Activity 活得久，持有 Activity 会泄漏。
    private val appContext = context.applicationContext

    private val cacheDir: File by lazy { File(appContext.cacheDir, CACHE_DIR_NAME) }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 在途表：spec.id -> 等待该图结果的 callback 列表。
     *
     * 为什么按 id 归并而不是按 URL：id 是本 demo 的稳定身份（见 [ImageFeed]），
     * 同一张图无论被谁、在哪次刷新请求，都应该共享同一次下载。
     *
     * 用 ConcurrentHashMap + 在列表上同步：value 的创建是原子的
     * （computeTab 保证同一 key 只建一次），后续 add 在列表锁内完成，
     * 从而并发调用 `download` 时能准确判断「我是不是第一个」。
     */
    private val inflight = ConcurrentHashMap<String, MutableList<Callback>>()

    /**
     * 提交下载。
     *
     * @return 首次向泳道提交是否被接受。返回 false 表示**首次**被拒（配额/队列满），
     *         本类仍会延迟重试一次；重试结果通过 callback 通知，调用方也可据此提前降级。
     */
    fun download(spec: ImageSpec, callback: Callback): Boolean {
        // 1) 归并在途请求：非第一个到达的调用方不重复提交网络任务，只等结果。
        val isFirst = register(spec.id, callback)
        if (!isFirst) {
            Log.d(TAG, "并入在途下载：id=${spec.id}")
            return true
        }

        // 2) 第一个到达者负责把任务提交进网络泳道。
        return submit(spec, attempt = 0)
    }

    /** 磁盘缓存文件路径。纯路径计算，不产生 IO。 */
    fun cachedFile(spec: ImageSpec): File = File(cacheDir, "${spec.id}.jpg")

    /**
     * 清空磁盘缓存。
     *
     * 放到 background 泳道执行：删除文件是磁盘 IO，且可能涉及较多条目，
     * 不应占用调用方（可能是主线程）的时间。
     */
    fun clearCache() {
        ThreadPools.background.execute(CALLER) {
            val files = cacheDir.listFiles()
            if (files == null) {
                Log.d(TAG, "clearCache：缓存目录不存在，无需清理")
                return@execute
            }
            var deleted = 0
            files.forEach { if (it.delete()) deleted++ }
            Log.i(TAG, "clearCache：删除 $deleted/${files.size} 个缓存文件")
        }
    }

    // ─────────────────────────────────────────
    // 提交与背压
    // ─────────────────────────────────────────

    /**
     * 把「下载 spec」这件事提交进网络泳道。
     *
     * @param attempt 0 为首次，1 为因背压被拒后的重试
     * @return 本次提交是否被接受
     */
    private fun submit(spec: ImageSpec, attempt: Int): Boolean {
        val accepted = ThreadPools.network.execute(CALLER) {
            runDownload(spec)
        }
        if (accepted) return true

        // 被拒 = 泳道显式背压。首次被拒时延迟重试一次；不是第一次则明确失败。
        if (attempt == 0) {
            Log.w(TAG, "网络泳道拒绝（配额/队列满），${RETRY_DELAY_MS}ms 后重试一次：id=${spec.id}")
            // 用统一的定时泳道做延迟，避免自己在主线程上 ad-hoc postDelayed，
            // 也符合「延时任务收口到 scheduled」的约定。
            runCatching {
                ThreadPools.scheduled.schedule(
                    { submit(spec, attempt = 1) },
                    RETRY_DELAY_MS,
                    TimeUnit.MILLISECONDS,
                )
            }.onFailure {
                failAll(spec, IOException("重试调度失败：id=${spec.id}", it))
            }
            return false
        }

        // 重试仍被拒：不能再吞，必须把背压变成调用方可见的失败。
        failAll(spec, IOException("网络泳道持续拒绝（配额/队列满），已放弃：id=${spec.id}"))
        return false
    }

    // ─────────────────────────────────────────
    // 实际下载（运行在网络泳道内）
    // ─────────────────────────────────────────

    private fun runDownload(spec: ImageSpec) {
        // 缓存命中直接回调，不走网络 —— 这是重复实验能快速复现的关键。
        val target = cachedFile(spec)
        if (target.exists() && target.length() > 0) {
            Log.d(TAG, "磁盘缓存命中：id=${spec.id}")
            successAll(spec, target)
            return
        }

        val tmp = File(cacheDir, "${spec.id}.jpg.tmp")
        try {
            cacheDir.mkdirs()
            val request = Request.Builder().url(spec.url).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} 下载失败：${spec.url}")
                }
                val body = response.body
                    ?: throw IOException("响应体为空：${spec.url}")
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            // 先写临时文件再改名：避免下载中途失败/被杀留下半个文件，
            // 让下次启动误判成「缓存命中」而解码出损坏位图。
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                throw IOException("缓存落盘失败（rename）：id=${spec.id}")
            }
            Log.i(TAG, "下载完成：id=${spec.id} -> ${target.length()} bytes")
            successAll(spec, target)
        } catch (e: Throwable) {
            tmp.delete()
            failAll(spec, e)
        }
    }

    // ─────────────────────────────────────────
    // 在途表与主线程回调
    // ─────────────────────────────────────────

    /** 注册 callback，返回它是否是该 id 的第一个（即需要负责发起下载的）调用方。 */
    private fun register(id: String, callback: Callback): Boolean {
        var isFirst = false
        inflight.compute(id) { _, existing ->
            val list = existing ?: mutableListOf()
            isFirst = list.isEmpty()
            list.add(callback)
            list
        }
        return isFirst
    }

    private fun successAll(spec: ImageSpec, file: File) {
        dispatch(spec.id) { it.onSuccess(file) }
    }

    private fun failAll(spec: ImageSpec, error: Throwable) {
        dispatch(spec.id) { it.onError(error) }
    }

    /**
     * 摘除在途条目并把结果派发到主线程。
     * 先 remove 再派发：这样随后到达的同 id 请求会被识别为新的一次，重新走缓存检查，
     * 不会挂到一个已经完成的条目上永远等不到回调。
     */
    private fun dispatch(id: String, action: (Callback) -> Unit) {
        val callbacks = inflight.remove(id) ?: return
        mainHandler.post {
            callbacks.forEach { cb ->
                runCatching { action(cb) }
                    .onFailure { Log.e(TAG, "callback 抛异常：id=$id", it) }
            }
        }
    }

    companion object {
        private const val TAG = "ImageDownloader"

        /**
         * 调用方标识：参与泳道配额与归因。
         * net 泳道 quotaPerCaller=16，本模块单页最多 20 张，靠下载单飞 + 缓存把在途压住。
         */
        private const val CALLER = "interview.image"

        private const val CACHE_DIR_NAME = "ii-images"
        private const val RETRY_DELAY_MS = 400L

        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 20L
        private const val WRITE_TIMEOUT_S = 20L
        private const val CALL_TIMEOUT_S = 30L

        /**
         * 全局单例 OkHttpClient。
         * 连接池 / 线程池 / Dispatcher 都是重量级且可复用的，每个 Activity 建一个
         * 会导致连接无法复用、线程数随页面数膨胀。
         *
         * 超时必须设置：picsum 返回的是数 MB 的原图，弱网下没有读超时会让任务
         * 长期占住 net 泳道线程，进而把配额耗光、拖垮同模块其他请求。
         */
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
                // 原图可能较大，允许对响应体做透明 gzip（OkHttp 默认已开，
                // 这里显式写出，避免日后被人误关）。
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
