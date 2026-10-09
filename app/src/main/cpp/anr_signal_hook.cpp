/**
 * Time: 2026/10/8
 * Author: wgt
 * Description: 通道 4 —— 拦截系统发给本进程的 SIGQUIT（ANR 信号）
 *
 * ══════════════════════════════════════════════════════════════════════
 * 为什么这条路这么绕：SIGQUIT 的所有权本来在 ART 手里
 * ══════════════════════════════════════════════════════════════════════
 *
 * ```
 *   zygote 启动  → Runtime::BlockSignals(): SIGQUIT/SIGUSR1/SIGPIPE 被 Block
 *                  （zygote 是单线程，之后 fork 出的 App 进程**继承**这个掩码）
 *   App fork     → StartSignalCatcher → 起 "SignalCatcher" 线程
 *                    while(true) sigwait(&set, &sig)   ← 阻塞式同步等待
 *                        case SIGQUIT: HandleSigQuit()  // DumpForSigQuit
 * ```
 *
 * 两条推论决定了本文件的全部写法：
 *
 * 1. **仅 `sigaction` 收不到信号。** POSIX：发给进程的信号只投递给**一个不阻塞它**的
 *    线程。SIGQUIT 被 Block 了 → 内核只能挂成 pending 交给 `sigwait` → handler 永远
 *    等不到。⇒ 必须**先 `pthread_sigmask(SIG_UNBLOCK)`**。
 *
 * 2. **自己起线程 `sigwait` 也不行。** 两个线程 sigwait 同一信号时，内核**随意**挑一个，
 *    投递不确定。⇒ 唯一可行的形态是 sigaction + 再转发回去。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 三步链路（少一步就是「假成功」）
 * ══════════════════════════════════════════════════════════════════════
 *
 * ```
 *   主线程              signal handler                 dumper 线程
 *     │ installSigquitHook()
 *     │  ├ 缓存 SignalCatcher tid（遍历 /proc/self/task）
 *     │  ├ 建 pipe
 *     │  ├ pthread_sigmask(UNBLOCK, SIGQUIT)  ← ③
 *     │  └ sigaction(SIGQUIT, handler)
 *     │
 *     │                系统发来 SIGQUIT
 *     │                     │ ① write(pipe, 载荷)      ← 只允许 async-signal-safe
 *     │                     │ ② tgkill(SignalCatcher)   ← ★ 不转发，系统就拿不到 trace
 *     │                     │                            ─▶ read() 阻塞唤醒
 *     │                     │                               ─▶ JNI 回调 Java（重活都在这）
 * ```
 *
 * ─── ② 为什么是硬性要求（不是「优化」）───
 *
 * 抢到 SIGQUIT 后，SignalCatcher 的 `sigwait` **不再收到**它，系统 dump 堆栈的流程
 * 就断了。后果（微信 Matrix 原文实测）：
 *   · AMS 一直等不到 ANR 进程写堆栈，直到 **20 秒超时**才被迫继续 →
 *     ANR 弹窗慢 20s，`/data/anr` 下生成不出完整 trace。
 * 源码依据：`ActivityManagerService.dumpStackTraces()` 里 `long remainingTime = 20 * 1000`。
 *
 * ─── ① 为什么 handler 里只许 write + tgkill ───
 *
 * handler 运行在**被中断的那个线程的栈上**。若中断的恰好是持锁的主线程，
 * 在 handler 里再拿同一把锁 = 死锁；在里面做 JNI / malloc / 日志都可能二次崩溃。
 * 所以重活全部推到独立线程，中间用 pipe 隔**一跳** ——
 * 这与本项目 `CrashMonitor` 的「崩溃路径上不做 IO，中间隔一跳」是同一条原则。
 *
 * ══════════════════════════════════════════════════════════════════════
 * ⚠️ 本项目对这条通道的定位：**实验，不是生产通道**
 * ══════════════════════════════════════════════════════════════════════
 *
 *   · 默认不安装（`StabilityMonitor.installOnCreate` 里注释掉了），只在实验页手动装；
 *   · 抢信号的动作会与系统的取证链路竞争，属于「动了平台的既有行为」；
 *   · 本仓库的既有结论是「权威数据从 `ApplicationExitInfo` 回捞」，
 *     这条通道的**增量**只在两点：self ANR 拿不到 trace、以及「当次进程内」要立刻上报。
 *     而这两点在实验页上必须能被证伪（见 StabilityLabActivity 的对照按钮）。
 */

#include <jni.h>

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>

#include <dirent.h>        // opendir/readdir：遍历 /proc/self/task
#include <fcntl.h>         // open 的 O_* 常量（本文件暂未用到，保留以便扩展）
#include <pthread.h>       // pthread_create / pthread_sigmask
#include <signal.h>        // sigaction / sigemptyset / SIGQUIT
#include <sys/syscall.h>   // SYS_tgkill
#include <unistd.h>        // read / write / close / getpid / gettid

#include <android/log.h>

#define LOG_TAG "AnrSigquit"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────
// 常量
// ─────────────────────────────────────────────────────────────────────

/** SignalCatcher 线程名。
 *
 * ⚠️ **带空格，没有空格** —— 两种都要认。这不是谨慎过头，是实测撞到的：
 *    - AOSP 源码里注册名是 `"Signal Catcher"`（art/runtime/signal_catcher.cc 的
 *      `AttachCurrentThread("Signal Catcher", …)`）；
 *    - 而**本机 Redmi K40 / Android 12 上 `/proc/<pid>/task/<tid>/comm` 读出来就是
 *      `Signal Catcher`（带空格）** —— 实测确认，`只匹配 "SignalCatcher" 会找不到`。
 *    所以这里按「去掉空白后比较」来匹配，两种写法都成立。 */
static const char* kSignalCatcherName = "SignalCatcher";

/** 一次性扫描 /proc/self/task 的最大 tid 数（防御性上限，正常进程几十~几百） */
static const int kMaxTidScan = 4096;

// si_code 语义（<asm-generic/siginfo.h>，Android 各版本一致）
//   注意：**不要**用 si_code 判断来源。ANR 流程里高版本用 sigqueue(SI_QUEUE)、
//   某些低版本用 kill(SI_USER)，并不统一（微信原文实测）。这里只作为「补充线索」
//   透传给 Java 侧，不作为判据。
static const int32_t kSiUser = 0;     // kill() 发送
static const int32_t kSiQueue = -1;   // sigqueue() 发送

// ─────────────────────────────────────────────────────────────────────
// 模块状态（全部为进程级单例；线程安全靠 atomic + 「先占坑再干活」）
// ─────────────────────────────────────────────────────────────────────

namespace {

JavaVM* g_jvm = nullptr;
jclass g_callback_class = nullptr;                       // GlobalRef
jmethodID g_on_sigquit = nullptr;                        // (Ljava/lang/String;)V

std::atomic<bool> g_installed{false};
std::atomic<bool> g_hook_active{false};

/** SignalCatcher 的 tid。⚠️ 必须在安装时缓存，**绝不能**在 handler 里现查 */
std::atomic<int> g_signal_catcher_tid{-1};

/** 通知管道：handler 端 write，dumper 线程 read */
int g_notify_fd[2] = {-1, -1};

/** 诊断计数（供实验页读数，证明「链路确实被走到了」） */
std::atomic<int64_t> g_sigquit_count{0};     // handler 命中次数
std::atomic<int64_t> g_rethrow_count{0};     // 转发给 SignalCatcher 的次数
std::atomic<int64_t> g_rethrow_fail{0};      // 转发失败次数
/** 最近一次收到信号时的 SignalCatcher tid（读数为 -1 说明当时没找到，转发会被跳过） */
std::atomic<int> g_last_catcher_tid{-1};

/** 安装前的旧 action / 旧掩码，卸载时用于还原 */
struct sigaction g_old_action;
sigset_t g_old_mask;
bool g_has_old_action = false;

/**
 * handler → dumper 的载荷，仅 16 字节（PIPE_BUF 之内 → write 是原子的，不会交错）。
 * 不用 struct 拆字段是为了让 write/read 的字节数固定、无需处理半包。
 *
 * ⚠️ **字段名不能叫 si_pid / si_uid / si_code / si_addr**：
 *    它们是 `<asm-generic/siginfo.h>` 里的**宏**
 *      `#define si_pid _sifields._kill._pid`
 *    一旦结构体成员同名，宏会在 `p.si_pid` 处展开成 `p._sifields._kill._pid`
 *    → 编译期报 "member reference base type 'int32_t' is not a structure or union"。
 *    这是**编译期就暴露**的坑（本次就是这么发现的），但名字取错时错误信息
 *    指向 siginfo.h，很容易误以为是头文件用法有问题。故统一用 sender_/recv_ 前缀。
 */
struct SigPayload {
    int32_t sender_pid;    // 发信号的进程 pid（判「自己发的」用）
    int32_t sender_uid;    // 发信号者的 uid（补充线索）
    int32_t sender_code;   // SI_USER / SI_QUEUE …
    int32_t recv_pid;      // 收信号进程的 pid（冗余一份，便于 Java 侧自校验）
};

// ─────────────────────────────────────────────────────────────────────
// 工具：解析 /proc/self/task 找 SignalCatcher 的 tid
// ─────────────────────────────────────────────────────────────────────

/**
 * 读 /proc/self/task/<tid>/comm，判断是不是 SignalCatcher。
 *
 * ⚠️ 用 `comm` 而不是 `status`：comm 是一整行、无 "Name:\t" 前缀，解析更省事。
 *
 * ⚠️ **必须忽略空白再比较**：实测本机（Redmi K40 / Android 12）读出来是
 *    `Signal Catcher`（带空格），而 AOSP 源码里的字面量写法在资料中常被写成
 *    `SignalCatcher`。只匹配一种就会找不到线程 —— 而找不到的后果是
 *    「有拦截、没转发」，会拖慢系统 dump trace 最多 20s（见文件头注释）。
 *    这里顺便也绕开了 comm 15 字符截断的问题（"Signal Catcher" 恰好 14 字符，
 *    但若某 ROM 加了后缀被截断，去掉空白后前缀匹配仍可能命中）。
 */
bool isSignalCatcherThread(const char* tid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/task/%s/comm", tid);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;

    char buf[32] = {0};
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return false;

    // 去掉结尾换行
    while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r')) buf[--n] = '\0';

    // 归一化：丢掉所有空白，得到 "SignalCatcher"
    char norm[32];
    size_t j = 0;
    for (size_t i = 0; buf[i] != '\0' && j < sizeof(norm) - 1; ++i) {
        char c = buf[i];
        if (c == ' ' || c == '\t') continue;
        norm[j++] = c;
    }
    norm[j] = '\0';

    return strcmp(norm, kSignalCatcherName) == 0;
}

/**
 * 找 SignalCatcher 的 tid。
 *
 * 返回 -1 表示**找不到**（低概率但会发生：ART 尚未起该线程 / 某些定制 ROM 改名）。
 * 调用方必须处理 -1 —— 那种情况下只剩「拦截」而没有「转发」，会拖慢系统取证。
 */
int findSignalCatcherTid() {
    DIR* dir = opendir("/proc/self/task");
    if (dir == nullptr) return -1;

    int found = -1;
    int scanned = 0;
    struct dirent* e;
    while ((e = readdir(dir)) != nullptr) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;  // 跳过 . / ..
        if (++scanned > kMaxTidScan) break;
        if (isSignalCatcherThread(e->d_name)) {
            found = atoi(e->d_name);
            break;
        }
    }
    closedir(dir);
    return found;
}

// ─────────────────────────────────────────────────────────────────────
// Signal handler —— 这里只允许 async-signal-safe 调用
// ─────────────────────────────────────────────────────────────────────

/**
 * SIGQUIT handler。
 *
 * 【硬性纪律】本函数里**只能**调 `write` / `syscall(SYS_tgkill)` / 原子变量。
 *   ❌ 不能调：JNI（CallStaticVoidMethod）、malloc/free、__android_log_print、
 *      任何加锁的函数、opendir。都可能在「栈已损坏 / 目标线程持锁」时把进程搞死。
 *   ⚠️ 连日志都不打是有意的：日志内部要拿 libc 的锁。
 *      要看现场请用 getStats() 的计数，或看 Java 侧的事件流水。
 */
void sigquitHandler(int sig, siginfo_t* info, void* /*ucontext*/) {
    if (sig != SIGQUIT) return;

    SigPayload p{};
    // ⚠️ 这里的 `info->si_pid` 是**宏展开**（`info->_sifields._kill._pid`），是正确用法；
    //    只有我们自己的结构体成员不能叫 si_pid（见 SigPayload 注释）。
    p.sender_pid = info != nullptr ? info->si_pid : 0;
    p.sender_uid = info != nullptr ? info->si_uid : 0;
    p.sender_code = info != nullptr ? info->si_code : 0;
    p.recv_pid = getpid();

    g_sigquit_count.fetch_add(1, std::memory_order_relaxed);

    // ① 只做入队（16 字节 < PIPE_BUF=4096 → write 原子，多线程同时来也不会交错）。
    //    pipe 满 / 未建好时丢弃：**绝不阻塞 handler**。
    if (g_notify_fd[1] >= 0) {
        ssize_t ignored = write(g_notify_fd[1], &p, sizeof(p));
        (void) ignored;
    }

    // ② ★ 把信号转回 SignalCatcher —— 漏了这步，AMS 要等到 20s 超时
    //    （system/core/... debuggerd_trigger_dump 那一侧等的是「进程写完堆栈」）
    int tid = g_signal_catcher_tid.load(std::memory_order_relaxed);
    if (tid > 0) {
        long r = syscall(SYS_tgkill, p.recv_pid, tid, SIGQUIT);
        if (r == 0) {
            g_rethrow_count.fetch_add(1, std::memory_order_relaxed);
        } else {
            g_rethrow_fail.fetch_add(1, std::memory_order_relaxed);
        }
    } else {
        g_rethrow_fail.fetch_add(1, std::memory_order_relaxed);
    }

    // ③ 刻意**不**恢复掩码、不 forward 通道旧 handler：
    //    旧 handler 是 ART 的 SignalCatcher（走 sigwait，不是 sigaction），
    //    调它没有任何意义；信号语义已由 ② 的 tgkill 还原。
}

// ─────────────────────────────────────────────────────────────────────
// dumper 线程：读 pipe → 回调 Java（重活都在这里）
// ─────────────────────────────────────────────────────────────────────

/** 低频诊断（每条信号最多打一行，不在 handler 里打） */
void logSigquit(const SigPayload& p) {
    const char* kind = (p.sender_code == kSiUser) ? "SI_USER(kill)"
                     : (p.sender_code == kSiQueue) ? "SI_QUEUE(sigqueue)"
                     : "SI_?";
    if (p.sender_pid == p.recv_pid) {
        ALOGW("收到 SIGQUIT：sender=自己(pid=%d) code=%d(%s) → 大概率不是 ANR，交给 Java 侧判定",
              p.sender_pid, p.sender_code, kind);
    } else {
        ALOGI("收到 SIGQUIT：sender pid=%d code=%d(%s) -> 已转发 SignalCatcher(tid=%d)",
              p.sender_pid, p.sender_code, kind, g_signal_catcher_tid.load());
    }
}

void* dumperRun(void* /*arg*/) {
    JNIEnv* env = nullptr;
    bool attached = false;

    for (;;) {
        SigPayload p{};
        ssize_t n = read(g_notify_fd[0], &p, sizeof(p));
        if (n < 0) {
            if (errno == EINTR) continue;   // 被信号打断（很常见，因为本进程就在收信号）
            break;                          // pipe 被关（卸载）→ 退出
        }
        if (n != static_cast<ssize_t>(sizeof(p))) continue;  // 半包（理论上不会出现）

        logSigquit(p);
        g_last_catcher_tid.store(g_signal_catcher_tid.load(), std::memory_order_relaxed);

        // ── 回调 Java（二次判定 + 上报都在 Java 侧，见 AnrSigquitChannel）──
        if (g_jvm == nullptr || g_callback_class == nullptr || g_on_sigquit == nullptr) continue;

        if (!attached) {
            int status = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
            if (status == JNI_EDETACHED) {
                // AttachCurrentThread 只做一次：本线程要长期存活，
                // 反复 attach/detach 既慢又容易在退出路径上踩坑。
                if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                    ALOGE("AttachCurrentThread 失败，本次回调跳过");
                    continue;
                }
                attached = true;
            } else if (status != JNI_OK) {
                continue;
            }
        }

        // 载荷序列化为定长字符串："senderPid:uid:code:selfPid"
        // 只用基本类型（int32），`%d` 解析在 Java 侧是纯逻辑、可被 JVM 单测覆盖。
        char payload[64];
        snprintf(payload, sizeof(payload), "%d:%d:%d:%d",
                 p.sender_pid, p.sender_uid, p.sender_code, p.recv_pid);

        jstring js = env->NewStringUTF(payload);
        if (js != nullptr) {
            env->CallStaticVoidMethod(g_callback_class, g_on_sigquit, js);
            if (env->ExceptionCheck()) env->ExceptionClear();  // 回调抛异常不能把 dumper 带走
            env->DeleteLocalRef(js);
        }
    }

    if (attached) g_jvm->DetachCurrentThread();
    return nullptr;
}

}  // namespace

// ─────────────────────────────────────────────────────────────────────
// JNI 导出
//
// 【命名规则】Java_<包名下划线化>_<类名>_<方法名>
//   com.interview.anrsignal.AnrSigquitHook.installNative
//   → Java_com_interview_anrsignal_AnrSigquitHook_installNative
//   ⚠️ 包名与类名里**不能**有下划线，否则要转义成 _1 —— 这也是本模块
//      特意不改用它现有中文包（com.interview.稳定性监控）的原因之一。
// ─────────────────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_interview_anrsignal_AnrSigquitHook_installNative(JNIEnv* env, jclass clazz) {
    (void) clazz;
    if (g_installed.exchange(true)) {
        ALOGI("已安装，跳过");
        return;
    }

    env->GetJavaVM(&g_jvm);

    // 回调类/方法（拿不到就退化：只拦截 + 转发，不上报）
    jclass local = env->FindClass("com/interview/anrsignal/AnrSigquitHook");
    if (local == nullptr) {
        ALOGE("找不到回调类 AnrSigquitHook");
        env->ExceptionClear();
        g_installed.store(false);
        return;
    }
    g_callback_class = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_on_sigquit = env->GetStaticMethodID(
            g_callback_class, "onSigquitFromNative", "(Ljava/lang/String;)V");
    if (g_on_sigquit == nullptr) {
        ALOGE("找不到回调方法 onSigquitFromNative");
        env->ExceptionClear();
        g_installed.store(false);
        return;
    }

    // ── 步骤 1：缓存 SignalCatcher 的 tid（必须在 handler 之外做，涉及大量 IO）──
    int catcherTid = findSignalCatcherTid();
    g_signal_catcher_tid.store(catcherTid);
    if (catcherTid > 0) {
        ALOGI("SignalCatcher tid=%d", catcherTid);
    } else {
        // ⚠️ 找不到时**仍然安装**，但必须在日志和实验页上明确告警：
        //    此时「拦截生效、转发缺失」，会拖慢系统的 ANR 取证（直到 20s 超时）。
        ALOGE("未找到 SignalCatcher 线程！转发将不可用，这会拖慢系统 dump trace（最长 20s）");
    }

    // ── 步骤 2：建通知管道 ──
    if (pipe(g_notify_fd) != 0) {
        ALOGE("pipe 创建失败 errno=%d", errno);
        g_notify_fd[0] = g_notify_fd[1] = -1;
        g_installed.store(false);
        return;
    }
    // 读端设为非阻塞也无必要：dumper 就是专职阻塞读。保持默认阻塞语义更简单。

    // ── 步骤 3：起 dumper 线程（先起线程，避免信号先到而无处投递）──
    pthread_t th;
    if (pthread_create(&th, nullptr, dumperRun, nullptr) != 0) {
        ALOGE("dumper 线程创建失败");
        close(g_notify_fd[0]);
        close(g_notify_fd[1]);
        g_notify_fd[0] = g_notify_fd[1] = -1;
        g_installed.store(false);
        return;
    }
    pthread_detach(th);

    // ── 步骤 4：解除 SIGQUIT 屏蔽（否则 sigaction 收不到任何信号）──
    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set, SIGQUIT);
    // ⚠️ 传 &g_old_mask 保存旧掩码，卸载时还原
    if (pthread_sigmask(SIG_UNBLOCK, &set, &g_old_mask) != 0) {
        ALOGE("pthread_sigmask(UNBLOCK) 失败 errno=%d", errno);
        g_installed.store(false);
        return;
    }
    // ⚠️ 已知限制（诚实边界）：POSIX 的信号掩码是 **per-thread** 的，
    //    这里只解除了**调用线程**（约定为主线程）的屏蔽。
    //    主线程是 ANR 时最常被投递的目标，所以绝大多数场景够用；
    //    但若某次内核把信号投给别的仍屏蔽它的线程，该信号会退回 SignalCatcher
    //    （即「漏捕获」而不是「丢证据」）—— 这是本方案可接受的失败模式。

    // ── 步骤 5：注册 handler ──
    // 注意：进程内 SIGQUIT 的 action 是全局共享的（信号描述符 per-process，
    //       掩码才是 per-thread）。所以这一句会覆盖 ART 的既有 action ——
    //       但 ART 的 SignalCatcher 走的是 sigwait，不依赖 action，
    //       真正需要还原的是「信号别被我们抢走」，即步骤 6 的 tgkill 转发。
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sigfillset(&sa.sa_mask);
    sa.sa_sigaction = sigquitHandler;
    sa.sa_flags = SA_RESTART | SA_SIGINFO | SA_ONSTACK;
    if (sigaction(SIGQUIT, &sa, &g_old_action) != 0) {
        ALOGE("sigaction(SIGQUIT) 失败 errno=%d", errno);
        pthread_sigmask(SIG_SETMASK, &g_old_mask, nullptr);
        g_installed.store(false);
        return;
    }
    g_has_old_action = true;

    g_hook_active.store(true);
    ALOGI("SIGQUIT 监控已安装：catcherTid=%d 转发=%s",
          catcherTid, catcherTid > 0 ? "on" : "OFF(警告)");
}

extern "C" JNIEXPORT void JNICALL
Java_com_interview_anrsignal_AnrSigquitHook_uninstallNative(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (!g_installed.exchange(false)) return;

    g_hook_active.store(false);

    // 顺序很重要：先关 pipe → dumper 从 read 醒来退出；
    // 再还原 action/掩码 → 不再有新信号进来。
    if (g_notify_fd[1] >= 0) { close(g_notify_fd[1]); g_notify_fd[1] = -1; }
    if (g_notify_fd[0] >= 0) { close(g_notify_fd[0]); g_notify_fd[0] = -1; }

    if (g_has_old_action) {
        sigaction(SIGQUIT, &g_old_action, nullptr);
        g_has_old_action = false;
    }
    // 还原掩码：把 SIGQUIT 重新屏蔽回去，回到 ART 的原状
    pthread_sigmask(SIG_SETMASK, &g_old_mask, nullptr);

    g_signal_catcher_tid.store(-1);
    ALOGI("SIGQUIT 监控已卸载");
}

/**
 * 诊断读数：`hit:rethrow:rethrowFail:catcherTid:lastCatcherTid:active`
 *
 * 为什么用字符串而不是分别导 6 个 JNI 方法：实验页只做一次展示，
 * 一个方法返回全部读数，Java 侧用纯函数解析即可（可被 JVM 单测覆盖）。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_interview_anrsignal_AnrSigquitHook_statsNative(JNIEnv* env, jclass clazz) {
    (void) clazz;
    char buf[128];
    snprintf(buf, sizeof(buf), "%lld:%lld:%lld:%d:%d:%d",
             static_cast<long long>(g_sigquit_count.load()),
             static_cast<long long>(g_rethrow_count.load()),
             static_cast<long long>(g_rethrow_fail.load()),
             g_signal_catcher_tid.load(),
             g_last_catcher_tid.load(),
             g_hook_active.load() ? 1 : 0);
    return env->NewStringUTF(buf);
}
