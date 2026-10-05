package com.interview.net

import okhttp3.HttpUrl

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: Rust 传输的**路由判定** —— 纯逻辑，零 Android / 零 JNI，可秒级单测。
 *
 * ─── 为什么判定要单独抽出来 ───
 *
 * 「这次请求走不走 Rust」是整个接入里最容易出事、也最需要被反复解释的一条规则：
 *   · 判宽了 → 明文请求、超大 body、主线程调用都被塞进 Rust，出的是安全/稳定性事故；
 *   · 判窄了 → 功能看起来「没生效」，实际是判定默默把它挡了，调用方无从判断。
 *
 * 所以判定做成**纯函数**（[decideRustRoute]）：给定 URL/方法/body 大小/网络档位
 * 与配置，返回一个**带原因**的决定。[RouteReason] 让「为什么没走 Rust」可被日志
 * 与实验页直接展示 —— 这比返回 bool 强得多，因为 bool 只告诉你「没走」，
 * 不告诉你「为什么没走」，而排查这类问题时原因才是一切。
 *
 * 判定顺序**有优先级，不可调换**：安全类判据（是否主线程、是否 https、是否代理）
 * 排在「机会类」判据（弱网、白名单）之前 —— 先问「允不允许」，再问「划不划算」。
 */

/** 路由决定的原因。用于日志/实验页展示，也是单测的断言对象。 */
enum class RouteReason {
    /** 命中，交给 Rust 传输 */
    ELIGIBLE,
    /** 总开关关闭（默认） */
    DISABLED,

    // ── 安全 / 平台契约类（优先级高）──
    /** 主线程调用：拒走 Rust，交回 OkHttp 以保留它自己的 NetworkOnMainThreadException 语义 */
    MAIN_THREAD,
    /** 非 https：拒走 Rust（安全红线，不绕开平台明文策略） */
    NOT_HTTPS,
    /** 需要代理：第一版不支持，明确拒绝而非静默直连 */
    HAS_PROXY,

    // ── 能力范围类 ──
    /** 方法不在允许列表（v1 只支持幂等安全方法） */
    METHOD_NOT_ALLOWED,
    /** body 超过内存缓冲上限（v1 不支持流式） */
    BODY_TOO_LARGE,

    // ── 机会类（划不划算）──
    /** host 不在白名单 */
    HOST_NOT_ALLOWED,
    /** 当前网络档位未达到触发条件（如非弱网） */
    QUALITY_NOT_TRIGGERED,
}

/** 路由决定。用 sealed 而非 bool，强制调用方处理「被跳过」这一分支。 */
sealed interface RouteDecision {
    data class Route(val reason: RouteReason) : RouteDecision
    data class Skip(val reason: RouteReason) : RouteDecision
}

/**
 * Rust 传输配置。**默认关闭**是刻意的安全默认：
 *
 * 新传输通道默认不接管任何流量，要显式打开（且要配白名单）才生效。
 * 反面做法是「默认全开」，那样一次误发布就会让全部请求走一条尚未经过
 * 真实业务流量检验的路径 —— 这类事故的代价远高于「默认关、按需开」的不便。
 */
data class RustTransportConfig(
    /** 总开关。默认 false：装上代码 ≠ 开始接管流量。 */
    val enabled: Boolean = false,

    /**
     * 触发档位。默认只在 [NetworkLevel.WEAK] 下启用 —— 这是设计文档 §2 的
     * 保守判据：弱网才是 QUIC 的连接迁移/抗丢包真正有价值的场景。
     */
    val triggerLevels: Set<NetworkLevel> = setOf(NetworkLevel.WEAK),

    /**
     * host 白名单。为空且 [allowAnyHost] 为 false 时**不会有任何请求走 Rust**
     * （即「未配置 = 不生效」）。这是刻意的：避免「开了开关就全站生效」。
     */
    val hostAllowlist: Set<String> = emptySet(),

    /** 显式放开全部 host。用于受控实验，**生产不要打开**。 */
    val allowAnyHost: Boolean = false,

    /**
     * 请求体的内存缓冲上限。超过则不走 Rust（v1 不做流式，见设计 §7）。
     * 64KB 是「小 body」的合理上界，也是避免把大文件整个读进内存的护栏。
     */
    val maxRequestBodyBytes: Long = 64 * 1024,
)

/**
 * 纯函数路由判定。**无副作用、无 Android 依赖**，因此每条分支都能在 JVM 上钉死。
 *
 * @param url            目标 URL
 * @param method         HTTP 方法（大小写不敏感）
 * @param hasProxy       是否要求走代理
 * @param bodySize       请求体字节数（无 body 传 0）
 * @param level          当前网络质量档位
 * @param onMainThread   当前是否在主线程（由调用方注入，避免本函数依赖 Android）
 * @param config         配置
 */
internal fun decideRustRoute(
    url: HttpUrl,
    method: String,
    hasProxy: Boolean,
    bodySize: Long,
    level: NetworkLevel,
    onMainThread: Boolean,
    config: RustTransportConfig,
): RouteDecision {
    fun skip(r: RouteReason) = RouteDecision.Skip(r)

    if (!config.enabled) return skip(RouteReason.DISABLED)

    // ① 主线程：绝不在主线程做阻塞式 native 传输。
    //    为什么这条很重要：若我们在主线程合成 Response、而不去碰 socket，
    //    OkHttp 就**不会**抛 NetworkOnMainThreadException —— 等于我们悄悄
    //    绕过了平台对「主线程网络」的既有保护，把异常变成了 ANR。
    //    故这里主动退回 OkHttp，让它按原语义抛异常。
    if (onMainThread) return skip(RouteReason.MAIN_THREAD)

    // ② 明文：安全红线。非 https 一律不走 Rust。
    if (!url.isHttps) return skip(RouteReason.NOT_HTTPS)

    // ③ 代理：v1 不支持。明确拒绝，而不是静默直连（那是合规问题，不只是功能问题）。
    if (hasProxy) return skip(RouteReason.HAS_PROXY)

    // ④ 方法白名单（与 Rust 侧 request::ALLOWED_METHODS 一致）。
    if (method.uppercase() !in ALLOWED_METHODS) return skip(RouteReason.METHOD_NOT_ALLOWED)

    // ⑤ body 上限：超了说明这是大上传，v1 不碰。
    if (bodySize > config.maxRequestBodyBytes) return skip(RouteReason.BODY_TOO_LARGE)

    // ⑥ 白名单：未配置即不生效（见 RustTransportConfig 注释）。
    if (!config.allowAnyHost && url.host !in config.hostAllowlist) {
        return skip(RouteReason.HOST_NOT_ALLOWED)
    }

    // ⑦ 机会判据：网络档位是否达到触发条件。
    if (level !in config.triggerLevels) return skip(RouteReason.QUALITY_NOT_TRIGGERED)

    return RouteDecision.Route(RouteReason.ELIGIBLE)
}

/** 与 Rust 侧 `request::ALLOWED_METHODS` 一致（两端一致性由单测与对拍钉住）。 */
internal val ALLOWED_METHODS = setOf("GET", "HEAD", "PUT", "DELETE")
