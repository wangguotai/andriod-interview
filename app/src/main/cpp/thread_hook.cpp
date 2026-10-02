/**
 * Time: 2026/9/27
 * Author: wgt
 * Description: 第 4 步「兜底」—— Native Hook pthread_create
 *
 * ─── 为什么要 Hook 到 Native 层 ───
 * 不管 Java 层怎么写线程（new Thread / 线程池 / 反射 / HandlerThread），最终都会走到：
 *
 *   Thread.start()
 *     → nativeCreate()          [java_lang_Thread.cc]
 *       → Thread::CreateNativeThread()
 *         → pthread_create()     ← 唯一的底层出口
 *
 * 所以 Hook pthread_create 是**唯一**能捕获「所有线程创建（含三方 SDK）」的位置。
 *
 * ─── 关键工程细节（本 Demo 踩过的坑）───
 * 1. 目标模块是 **libart.so**，不是 libc.so。
 *    libart 通过自己的 PLT/GOT 调用 pthread_create，所以必须改 libart 的 GOT 表项；
 *    改 libc 自己的 GOT 拦不到 libart 发出的调用。
 * 2. libart.so 在 Android 10+ **无法 dlopen**（受 linker namespace 限制），
 *    必须用 dl_iterate_phdr 遍历已加载模块来定位它。
 * 3. 解析 PT_DYNAMIC 段时要用 **p_vaddr** 而非 p_offset。
 *    两者在非首个 PT_LOAD 段中不相等，用错会读到垃圾内存并 SIGSEGV。
 *
 * ─── 方案选型 ───
 *   GOT Hook   兼容性优 / 范围差 / 性能优   ← 本实现（可上线）
 *   Inline Hook 兼容性差 / 范围优 / 性能优   ← 线下工具可选
 *   Trap Hook  兼容性优 / 范围优 / 性能差
 *
 * ═══════════════════════════════════════════════════════════════════
 * 【C++ 语法速查索引】—— 想快速恢复某个语法点记忆，按下表定位行号
 * ═══════════════════════════════════════════════════════════════════
 *  · #include 的 <> 与 "" 区别 .................... 「头文件区」
 *  · #define 宏 + 可变参数 __VA_ARGS__ ............ ALOGD 三兄弟
 *  · 匿名命名空间 namespace { } ................... namespace {
 *  · 函数指针的声明 / 赋值 / 调用（最易忘）......... original_pthread_create
 *  · 嵌套函数指针 void* (*)(void*) ................ 同上
 *  · std::atomic<bool> + exchange/store ........... g_hooked
 *  · const 修饰指针的三种位置 ..................... g_jvm / strtab
 *  · 结构体的默认成员初始化 struct X { int a = 0; } TargetModule
 *  · 值初始化 X x{}; 与聚合初始化 ................. TargetModule mod{};
 *  · Lambda [捕获](参数) -> 返回类型 { } ........... toAbs
 *  · static_cast 与 reinterpret_cast 的区别 ....... reportToJava / hookGotEntry
 *  · void** 指针出参（模拟多返回值）............... old_value
 *  · 范围 for  for (const char* x : arr) .......... candidates
 *  · switch + break 穿透陷阱 ...................... 解析 .dynamic
 *  · 位运算页对齐 x & ~(page - 1) ................. page_start
 *  · 条件编译 #if defined(__LP64__) ............... ELF64_R_SYM / ELF32_R_SYM
 *  · extern "C" 与 JNI 导出符号命名规则 ........... 文件末尾两个函数
 *  · 文件末尾还有一张「复习卡片」，汇总以上全部要点
 * ═══════════════════════════════════════════════════════════════════
 */

// ───────────────────────────────────────────────
// 【头文件区】#include 的两种写法
//   <xxx.h> : 从系统/工具链的 include 搜索路径里找（此处由 NDK sysroot 提供）
//   "xxx.h" : 先找当前源文件所在目录，找不到再退回系统路径
//   这里全是标准库或 NDK 头，所以统一用尖括号。
// ───────────────────────────────────────────────
#include <jni.h>        // JNI 接口：JNIEnv / jclass / jmethodID / jstring 等
#include <android/log.h>// __android_log_print，输出到 logcat
#include <dlfcn.h>      // dl_iterate_phdr（遍历已加载 so）等动态链接 API
#include <pthread.h>    // pthread_create、pthread_t、pthread_attr_t
#include <string>       // std::string（本例未直接用，保留供扩展）
#include <atomic>       // std::atomic，无锁的跨线程标志位
#include <unistd.h>     // getpagesize()，取内存页大小
#include <string.h>     // strstr / strcmp，C 风格字符串操作
#include <stdlib.h>     // 通用工具函数声明
#include <stdio.h>      // snprintf，格式化字符串
#include <sys/prctl.h>  // prctl(PR_GET_NAME)：读线程名，API 24 可用
#include <sys/resource.h> // setpriority：给新创建的线程设 nice
#include <time.h>       // clock_gettime：速率限制的时间基准
#include <errno.h>      // EAGAIN 等错误码
#include <sys/mman.h>   // mprotect，修改内存页读写权限
#include <elf.h>        // ELF 结构体：ElfW(Dyn) / ElfW(Sym) / ElfW(Rela) 等
#include <link.h>       // struct dl_phdr_info、dl_iterate_phdr 的声明

// ───────────────────────────────────────────────
// 【宏定义】#define 是纯文本替换，不是函数，没有类型检查。
//   格式：#define 名字 替换内容
//   __VA_ARGS__ 是「可变参数宏」的内置关键字，代表 ... 传入的全部实参，
//   连同逗号一起原样替换到展开位置。这是 C99 引入的特性。
//   调用示例：ALOGD("base=%p", ptr);
//     展开后 → __android_log_print(ANDROID_LOG_DEBUG, "ThreadHook", "base=%p", ptr);
// ───────────────────────────────────────────────
#define LOG_TAG "ThreadHook"   // 字符串字面量，用于 logcat 过滤
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
// 格式符必须与实参类型严格匹配：%p 配指针、%zu 配 size_t、%ld 配 long、%d 配 int。

// ───────────────────────────────────────────────
// 【匿名命名空间】
//   namespace { ... } 没有名字，其中定义的符号只在「本编译单元（本 .cpp）」内可见，
//   效果等价于 C 里的 static，是现代 C++ 中更推荐的写法。
//
//   ⚠️ 一个关键约束：JNI 导出函数（Java_xxx）必须写在匿名命名空间「之外」。
//      因为匿名命名空间会给符号名加上编译器内部后缀，导致 Java 侧按固定名字
//      找不到 native 实现。本文件就是这么组织的：
//        匿名 namespace { 所有内部实现 }  →  再写 extern "C" JNIEXPORT 导出函数
// ───────────────────────────────────────────────
namespace {

// ───────────────────────────────────────────────
// 【函数指针的声明语法】—— C/C++ 里最容易写错、最容易忘的一处
//
//   普通函数声明：  int  add(int a, int b);
//   同形的函数指针：int (*fp)(int a, int b);
//                    ↑     ↑ 变量名必须用括号包住！
//   若漏掉括号写成 int *fp(int a, int b); 那就变成了
//   「返回 int* 的函数声明」，语义完全不同。
//
// 下面这行读法：
//   original_pthread_create 是一个指针，指向
//   「参数为 (pthread_t*, const pthread_attr_t*, void*(*)(void*), void*)、
//     返回 int」的函数。
//
//   ⚠️ 第三个参数 void* (*)(void*) 是一个「嵌套的函数指针」——
//      它是 pthread 的线程入口回调类型：接收 void*、返回 void*。
//      这里的 (*) 省略了参数名，因为处在纯类型位置。
//
//   末尾的 = nullptr 是 C++11 空指针常量，比 C 的 NULL（本质是 0）更安全：
//   它有独立类型 std::nullptr_t，不会与整数重载混淆。
// ───────────────────────────────────────────────
int (*original_pthread_create)(pthread_t*, const pthread_attr_t*,
                               void* (*)(void*), void*) = nullptr;

// ───────────────────────────────────────────────
// 【std::atomic】线程安全的标志位，读写都是原子操作，无需加锁。
//
//   std::atomic<bool> g_hooked{false};
//   ↑ 变量名后面的 {false} 是 C++11「列表初始化（brace initialization）」。
//   相比 std::atomic<bool> g_hooked(false); 的圆括号写法，
//   花括号能防止「窄化转换」，也能规避经典的
//   「最令人烦恼的解析（most vexing parse）」陷阱。
//
//   常用成员函数：
//     .store(v)     原子写入
//     .load()       原子读取
//     .exchange(v)  原子写入并返回旧值（CAS 风格，本文件用到了）
// ───────────────────────────────────────────────
std::atomic<bool> g_hooked{false};

// ───────────────────────────────────────────────
// 【全局缓存】JNI 相关句柄，只在 installNative 里赋值一次。
//
//   JavaVM*  ：JVM 入口句柄，供「非 Java 线程」attach 使用
//   jclass   ：必须是「全局引用（GlobalRef）」，不能用局部引用——
//              局部引用在 JNI 方法返回后立即失效。
//   jmethodID：方法 ID，由 GetStaticMethodID 取得，本身不受引用回收影响。
//
// 【const 与指针的组合，三种写法务必分清】
//   const char* p        → 指向的内容不可改（不能 *p = 'x'），p 本身可重新指向
//   char* const p        → p 本身不可改，指向的内容可改
//   const char* const p  → 两者都不可改
//   口诀：const 修饰它「左边」的类型；若它在最左边，则修饰右边的类型。
// ───────────────────────────────────────────────
JavaVM* g_jvm = nullptr;
jclass g_callback_class = nullptr;
jmethodID g_on_thread_created = nullptr;
/** 治理决策回调：int onThreadCreateRequested(String callerSite) → 0放行/1降级/2拒绝 */
jmethodID g_on_create_requested = nullptr;

/** 缓存 FindClass 结果，避免每次创建都重复查找（JNI FindClass 有明显开销） */
jclass g_thread_cls = nullptr;
jclass g_ste_cls = nullptr;
jmethodID g_mid_current_thread = nullptr;
jmethodID g_mid_get_stack = nullptr;
jmethodID g_mid_ste_to_string = nullptr;
jmethodID g_mid_ste_get_class = nullptr;

/**
 * 调用点缓存（thread_local，避免跨线程竞争）。
 *
 * ⚠️ 抓 Java 堆栈是本 Hook 里最重的操作。绝不能每次创建都抓 ——
 *    那样会把「建线程」这件事本身拖慢，反而制造了新问题。
 *
 * 节流策略：线程给自己缓存一个「本节拍内的调用点」。
 * 同一条线程连续建多个线程时（SDK 批量建线程的典型形态），
 * 调用点几乎必然相同，缓存命中率很高。
 */
constexpr long CALLER_SITE_TTL_MS = 500;
struct CallerSiteCache {
    char site[192];
    long stamp_ms;
};

/** 单调时钟毫秒 */
long nowMillis();

/** 抓 Java 堆栈取调用点，定义在下方（先声明，供上面的节流缓存调用） */
void resolveCallerSite(JNIEnv* env, char* out, size_t out_size);

CallerSiteCache& callerSiteCache() {
    static thread_local CallerSiteCache c{{0}, 0};
    return c;
}

/** 取调用点（带 500ms 缓存）。命中缓存时零 JNI 开销。 */
const char* callerSiteThrottled(JNIEnv* env) {
    CallerSiteCache& c = callerSiteCache();
    long now = nowMillis();
    if (c.site[0] != '\0' && (now - c.stamp_ms) < CALLER_SITE_TTL_MS) {
        return c.site;   // 缓存命中
    }
    resolveCallerSite(env, c.site, sizeof(c.site));
    c.stamp_ms = now;
    return c.site;
}

// 观测：创建动作来自 Java 线程还是纯 native 线程
std::atomic<int> g_fromJavaCount{0};
std::atomic<int> g_fromNativeCount{0};

// ═══════════════════════════════════════════════════════════════
// 控制层：从「观测」升级为「治理」
//
// 这是 Native Hook 相对 ASM/Lint 唯一不可替代的价值：
// ASM 只能改你能重编译的代码，Lint 只能拦你写的新代码，
// 而 Hook 能管住**已经在 APK 里、你改不了源码的三方 SDK**。
//
// 三个动作梯度（由轻到重）：
//   1. 观测   —— 只记录，不干预（默认）
//   2. 降级   —— 允许创建，但把优先级压低 + 缩小栈
//   3. 拒绝   —— 直接返回 EAGAIN，让 SDK 的线程创建失败
//
// 生产上应当「先只观测一段时间，用真实数据定策略，再逐步加码」。
// 一上来就全局拒绝会把 SDK 搞崩。
// ═══════════════════════════════════════════════════════════════

/** 治理动作 */
enum class Action {
    ALLOW,     // 放行，原样创建
    DEMOTE,    // 放行但降级：设低 nice + 缩小栈
    REJECT,    // 拒绝创建
};

/** 单条策略：按调用点前缀匹配 */
struct Rule {
    const char* caller_prefix;  // 调用点前缀（"com.thirdparty.sdk" 等），空串=兜底规则
    Action action;
};

/**
 * 策略表。按顺序匹配，第一条 caller_prefix 命中即生效。
 *
 * ⚠️ 这里用静态常量字符串，是因为规则在编译期就固定了。
 *    生产环境应改为从配置/服务端下发。
 */
const Rule g_rules[] = {
        // 演示：把「即将失控」的创建点降级，而不是拒绝 —— 保留功能但压低影响
        {"com.interview.thread.ThreadMisuseScenarios", Action::DEMOTE},
        // 兜底规则（前缀为空匹配一切）
        {"", Action::ALLOW},
};

// ─── 全局令牌桶：限制整个进程的线程创建速率 ───
//
// 为什么需要它：单看某个调用点可能都「合理」，但 10 个 SDK 同时
// 各建 5 个线程就是雪崩。速率限制是防雪崩的最后一道闸。
constexpr int RATE_LIMIT_PER_SEC = 20;     // 每秒最多新建 20 个线程
constexpr size_t RATE_BUCKET_CAPACITY = 40; // 突发容量

std::atomic<long> g_rate_tokens{RATE_BUCKET_CAPACITY};
std::atomic<long> g_last_refill_ms{0};
std::atomic<int> g_rejected_count{0};
std::atomic<int> g_demoted_count{0};
std::atomic<int> g_stackShrunkCount{0};

/** 降级时把线程栈压到这个大小。256KB 足够常规任务，且大幅减少虚拟地址空间占用。 */
constexpr size_t SHRINK_STACK_BYTES = 256 * 1024;

/** 单调时钟毫秒 */
long nowMillis() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

/**
 * 令牌桶取令牌。返回 true 表示允许，false 表示超速。
 *
 * 这个实现故意做成「近似」的：不做 CAS 循环，宁可偶尔多放行一个，
 * 也不要在 Hook 点引入自旋 —— Hook 点越轻越好。
 */
bool acquireRateToken() {
    long now = nowMillis();
    long last = g_last_refill_ms.load(std::memory_order_relaxed);
    if (now - last >= 1000) {
        // 用 CAS 保证只有一个线程做补充
        if (g_last_refill_ms.compare_exchange_strong(last, now)) {
            g_rate_tokens.store(RATE_BUCKET_CAPACITY, std::memory_order_relaxed);
        }
    }
    long cur = g_rate_tokens.load(std::memory_order_relaxed);
    while (cur > 0) {
        if (g_rate_tokens.compare_exchange_weak(cur, cur - 1,
                                               std::memory_order_relaxed)) {
            return true;
        }
    }
    return false;
}

/**
 * 把「原生线程创建」转为「受治理的创建」—— 这是 Hook 作为控制点的核心。
 *
 * 关键：pthread_create 的 attr 是可修改的！我们可以：
 *   · pthread_attr_setstacksize  —— 缩小线程栈（治内存）
 *   · 在新线程入口包一层          —— 设 nice、改名（治调度）
 *
 * @param out_wrapped 若需要包装入口函数，这里返回新的 start_routine
 */
struct CreatePlan {
    Action action = Action::ALLOW;
    size_t stack_size = 0;          // 0 表示不改
    void* (*wrapped_entry)(void*) = nullptr;
    void* wrapped_arg = nullptr;
};

/*
 * ═══════════════════════════════════════════════════════════════════
 * 【已废弃方案：在 pthread 入口设 nice】—— 保留记录，避免后人重蹈
 *
 * 曾实现过一个入口包装函数：在 new 线程的 start_routine 里调
 * setpriority(PRIO_PROCESS, 0, 10)，想把 SDK 的线程降为后台优先级。
 *
 * 实测证明**这条路走不通**（API 36 模拟器，探针回读 /proc/PID/task/TID/stat）：
 *
 *   [demote-probe] setpriority rc=0 nice_at_entry=10    ← 设置成功
 *   [demote-probe] entry 返回，nice_end=0               ← 又变回 0
 *   业务运行期间从外部读该 tid：nice=0                   ← 实际执行时是 0
 *
 * 原因：ART 在 Java 线程真正进入 run() 时会重新应用 java.lang.Thread
 * 的 priority 字段，把我们在更早的 native 入口设的 nice 覆盖掉。
 * 那个 10 只存在于「pthread 入口」到「ART 线程初始化」之间的几微秒窗口。
 *
 * 结论：
 *   · native 入口**不是**设优先级的正确位置；正确位置是 Java 层
 *     ThreadFactory（包一层 Runnable，在 run() 内设置 —— 见
 *     ThreadPools.NamedThreadFactory，那里实测能稳定生效）。
 *   · native 能稳定控制的是**栈大小**（attr 在 pthread_create 时
 *     就 mmap 定死，ART 无法事后更改），见下方 DEMOTE 分支。
 * ═══════════════════════════════════════════════════════════════════
 */


/**
 * 查询当前线程的 JNIEnv。
 *
 * 【关键认知】Hook 点执行在「调用 pthread_create 的那个线程」上，
 * 而不是新建的线程上。若调用者是 Java 线程（绝大多数情况），
 * 它早已 attach，GetEnv 直接返回它的 env。
 *
 * 若是纯 native 线程（三方 SDK 的 native 代码建线程），返回
 * JNI_EDETACHED —— 此时**不**去 Attach：
 * Attach 得到的 env 代表「正卡在 native 里的这个线程」，
 * 反查它自己的 Java 帧没有意义（它本来就没有 Java 栈）。
 */
JNIEnv* queryCurrentEnv() {
    if (g_jvm == nullptr) return nullptr;
    JNIEnv* env = nullptr;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }
    return nullptr;
}

/** 线程创建动作的来源 */
enum CreationSource {
    FROM_JAVA,      // 调用者是 Java 线程 —— 能拿到创建者堆栈
    FROM_NATIVE,    // 调用者是纯 native 线程 —— 无 Java 栈
};

/**
 * 读当前线程的名字。
 *
 * 用 prctl(PR_GET_NAME) 而非 pthread_getname_np：后者需要 API 26，
 * 而本项目 minSdk = 24。prctl 是 Linux 原生接口，在所有 API 级别可用，
 * 且返回的就是内核里设置的 comm 名。
 *
 * Java 线程在 ART 里其 comm 名通常已被设成 Java 线程名，
 * 所以从 native 侧调用也常能拿到可读名字。
 * 但三方 SDK 的 native 线程往往没设名，此时返回 "unnamed"。
 */
const char* resolveCurrentThreadName() {
    static thread_local char buf[64];
    buf[0] = '\0';
    // PR_GET_NAME 把当前线程的 comm 名拷进 buf（最多 16 字节有效）
    if (prctl(PR_GET_NAME, buf, 0, 0, 0) != 0 || buf[0] == '\0') {
        return "unnamed";
    }
    return buf;
}

/**
 * 上报「有新线程被创建」，并标记创建者类型。
 *
 * ⚠️ 这里**不在 native 侧抓堆栈**，而是只把一个布尔（是否来自 Java）
 * 传给 Java 侧回调。原因：
 *   1. 回调本身就在调用者线程上执行，Java 侧一句
 *      `Thread.currentThread().stackTrace` 就能拿到同样的信息；
 *   2. 在 native 侧用 JNI 拼 StackTraceElement 需要查 4 个类、
 *      循环取数组元素、逐帧转字符串，代码量是 Java 侧的 10 倍，
 *      且每帧一次 JNI 往返。
 *
 * 「能不跨语言就别跨语言」—— 这是 JNI 编程的基本判断。
 */
void reportWithSource(JNIEnv* env, CreationSource source) {
    if (g_callback_class == nullptr || g_on_thread_created == nullptr) return;

    // 把来源编码进字符串前缀，避免再加一个 JNI 方法（保持接口最小）
    // 形如："java|com.example.Foo.bar" 或 "native|some-thread"
    const char* prefix = (source == FROM_JAVA) ? "java|" : "native|";
    const char* tail = (source == FROM_JAVA) ? "" : resolveCurrentThreadName();

    char combined[160];
    snprintf(combined, sizeof(combined), "%s%s", prefix, tail);

    jstring jtext = env->NewStringUTF(combined);
    if (jtext != nullptr) {
        env->CallStaticVoidMethod(g_callback_class, g_on_thread_created, jtext);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(jtext);
    }
}

/**
 * 只在 Hook 点做极轻量记录，绝不在这里抓全量堆栈。
 *
 * 【函数签名】 void reportToJava(const char* thread_name)
 *   · void        —— 不返回值
 *   · const char* —— C 风格字符串：指向字符数组首元素的指针，以 '\0' 结尾。
 *                    它没有长度信息，这是 C 字符串的惯例。
 */
void reportToJava(const char* thread_name) {
    // 【卫语句 / 提前返回】把最坏情况挡在最前面，避免后续套多层 if 缩进。
    // 这三个变量只在 installNative 成功后才非空，所以必须判空。
    if (g_jvm == nullptr || g_callback_class == nullptr || g_on_thread_created == nullptr) {
        return;
    }

    // 初始化为 nullptr，避免未初始化的野指针。
    JNIEnv* env = nullptr;
    bool attached = false;   // 记录本函数是否自己 Attach 上来的，决定要不要 Detach

    // ───────────────────────────────────────────────
    // 【类型转换】GetEnv 的形参是 void**（「任意类型指针的地址」），
    //   而我们手上是 JNIEnv**。二者语义上就是同一块内存，只是类型名不同，
    //   这种「仅重新解释位模式」的转换用 reinterpret_cast。
    //
    // ★ 四种 cast 的记忆要点：
    //     static_cast       相关类型间的转换（数值、void*↔具体指针、继承体系内）
    //     reinterpret_cast  不相关的指针/整数之间重新解释，最危险
    //     const_cast        增删 const
    //     dynamic_cast      运行期带检查的向下转型（需要 RTTI）
    // ───────────────────────────────────────────────
    int status = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);

    // JNI_EDETACHED / JNI_OK 是 jni.h 里的整型常量，用 == 直接比较。
    if (status == JNI_EDETACHED) {
        // 当前线程尚未挂到 JVM 上，先 Attach。
        // 第二个参数在标准 C++ 签名里是 void**，写 &env 由编译器适配。
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;   // attach 失败直接放弃，绝不带空 env 继续往下走
        }
        attached = true;
    } else if (status != JNI_OK) {
        return;       // 其它错误码（如版本不匹配）一律放弃
    }

    // 走到这里 env 必然可用，但仍保留防御式判断——成本极低。
    if (env != nullptr) {
        // 【三元运算符】条件 ? 真值 : 假值
        // 做空指针兜底，保证传给 Java 的字符串永不为 null。
        jstring jname = env->NewStringUTF(thread_name != nullptr ? thread_name : "unknown");

        if (jname != nullptr) {
            // 【调用 Java 静态方法】CallStaticVoidMethod(类, 方法ID, 参数...)
            //   "Void" 对应 Java 的 void 返回类型。
            //   方法签名 "(Ljava/lang/String;)V" 表示「接收 String，返回 void」。
            env->CallStaticVoidMethod(g_callback_class, g_on_thread_created, jname);

            // 【JNI 异常处理】Java 侧抛出的异常不会自动打断 native 执行流，
            //   必须显式检查并清除，否则异常会一直挂起，后续 JNI 调用行为异常。
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }

            // 【局部引用释放】JNI 局部引用有数量上限（默认约 512 个）。
            //   本函数可能被高频调用（每次建线程都触发），必须及时释放。
            env->DeleteLocalRef(jname);
        }
    }

    // 「谁 Attach 谁 Detach」：若当前线程本就属于 JVM（Java 线程创建新线程时
    // 执行到这里仍是 Java 线程），就不能 Detach，否则会把 JVM 自己的线程摘掉。
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

/**
 * 向 Java 侧征询治理决策。
 *
 * 【设计】策略留在 Java（可配置、可热更新、好测试），
 *   执行放在 native（只有这里能改 attr、能拦截）——
 *   「决策与执行分离」。
 *
 * 回调签名：int onThreadCreateRequested(String callerSite)
 *   返回 0 = 放行，1 = 降级，2 = 拒绝
 *
 * ⚠️ 这个回调在 Hook 点被同步调用，Java 侧必须极快返回，
 *    绝不能在回调里做 IO 或抓全量堆栈。
 */
int queryPolicyFromJava(JNIEnv* env, jmethodID mid, const char* caller_site) {
    if (env == nullptr || mid == nullptr || g_callback_class == nullptr) {
        return 0;   // 拿不到策略时一律放行，绝不因治理机制本身阻断业务
    }
    jstring jsite = env->NewStringUTF(caller_site != nullptr ? caller_site : "");
    if (jsite == nullptr) return 0;

    jint decision = env->CallStaticIntMethod(g_callback_class, mid, jsite);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        decision = 0;
    }
    env->DeleteLocalRef(jsite);
    return decision;
}

/**
 * 取当前调用点（"类名.方法:行号"）。用于策略匹配与归因。
 * 拿不到就返回空串 —— 空串会匹配兜底规则。
 *
 * ⚠️ 本函数会抓 Java 堆栈，开销不可忽略。调用方必须自己做节流：
 *    只在「需要策略决策」或「还在采样窗口内」时才调用。
 */
void resolveCallerSite(JNIEnv* env, char* out, size_t out_size) {
    out[0] = '\0';
    if (env == nullptr || g_thread_cls == nullptr) return;

    jmethodID currentMid = g_mid_current_thread;
    jmethodID stackMid = g_mid_get_stack;
    jmethodID toStrMid = g_mid_ste_to_string;
    jmethodID getClsMid = g_mid_ste_get_class;

    if (currentMid && stackMid && toStrMid && getClsMid) {
        jobject cur = env->CallStaticObjectMethod(g_thread_cls, currentMid);
        if (cur != nullptr) {
            jobjectArray frames = reinterpret_cast<jobjectArray>(
                    env->CallObjectMethod(cur, stackMid));
            if (frames != nullptr) {
                jsize len = env->GetArrayLength(frames);
                for (jsize i = 0; i < len && i < 16; ++i) {
                    jobject ste = env->GetObjectArrayElement(frames, i);
                    if (ste == nullptr) continue;
                    jstring cls = reinterpret_cast<jstring>(env->CallObjectMethod(ste, getClsMid));
                    const char* clsChars = cls ? env->GetStringUTFChars(cls, nullptr) : nullptr;
                    bool skip = false;
                    if (clsChars != nullptr) {
                        // 跳过「建线程」机制自身的帧
                        skip = strncmp(clsChars, "java.lang.Thread", 16) == 0 ||
                               strncmp(clsChars, "java.lang.Throwable", 19) == 0 ||
                               strncmp(clsChars, "java.util.concurrent", 20) == 0 ||
                               strncmp(clsChars, "com.interview.thread.NativeThreadHook", 37) == 0 ||
                               strncmp(clsChars, "dalvik.system", 13) == 0;
                    }
                    if (clsChars != nullptr && !skip) {
                        jstring line = reinterpret_cast<jstring>(env->CallObjectMethod(ste, toStrMid));
                        const char* lineChars = line ? env->GetStringUTFChars(line, nullptr) : nullptr;
                        if (lineChars != nullptr) {
                            // toString 形如 "com.foo.Bar.baz(Bar.java:42)"，
                            // 截到 '(' 之前 + 行号，拼成 "com.foo.Bar.baz:42"
                            snprintf(out, out_size, "%s", lineChars);
                            char* paren = strchr(out, '(');
                            if (paren != nullptr) {
                                char* colon = strrchr(paren, ':');
                                if (colon != nullptr) {
                                    char lineNo[12];
                                    snprintf(lineNo, sizeof(lineNo), "%s", colon + 1);
                                    char* close = strchr(lineNo, ')');
                                    if (close) *close = '\0';
                                    *paren = '\0';
                                    size_t used = strlen(out);
                                    snprintf(out + used, out_size - used, ":%s", lineNo);
                                } else {
                                    *paren = '\0';
                                }
                            }
                            env->ReleaseStringUTFChars(line, lineChars);
                        }
                        if (line) env->DeleteLocalRef(line);
                    }
                    if (clsChars != nullptr) env->ReleaseStringUTFChars(cls, clsChars);
                    if (cls) env->DeleteLocalRef(cls);
                    env->DeleteLocalRef(ste);
                    if (out[0] != '\0') break;
                }
                env->DeleteLocalRef(frames);
            }
            env->DeleteLocalRef(cur);
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
}

// ───────────────────────────────────────────────
// 【替换函数】签名必须与原 pthread_create「完全一致」。
// 因为我们是把它的地址直接塞进 GOT 表项，被调用时栈上的参数布局
// 按原签名解释——签名不一致会立刻崩溃。
//
// 它位于匿名命名空间内，不需要被外部看见，只是「取地址」用（&hooked_pthread_create）。
// ───────────────────────────────────────────────
int hooked_pthread_create(pthread_t* thread, const pthread_attr_t* attr,
                          void* (*start_routine)(void*), void* arg) {
    // ───────────────────────────────────────────────
    // 【关键认知修正】Hook 点是跑在「调用者线程」上的，不是新线程上。
    //
    // 原注释写「拿不到创建者的 Java 堆栈，因为新线程尚未 attach」——
    // 这个推理是错的：新线程确实没 attach，但**执行 hooked_pthread_create
    // 的线程正是调用者本身**（通常是已 attach 的 Java 线程）。
    //
    // 于是能力边界比原以为的宽得多：
    //   · 调用者是 Java 线程 → 能拿到完整的「谁创建了这个线程」Java 堆栈
    //   · 调用者是纯 native 线程 → 无 Java 栈，退化为只报线程名
    // 而且这个区分本身就很有价值：它能直接回答
    // 「这个线程是 Java 代码建的，还是某个 SDK 的 native 代码建的」。
    // ───────────────────────────────────────────────
    JNIEnv* env = queryCurrentEnv();
    if (env != nullptr) {
        // 当前是 Java 线程：让 Java 侧抓「自己」的堆栈（见 Java 侧说明）
        g_fromJavaCount.fetch_add(1);
        reportWithSource(env, FROM_JAVA);
    } else {
        // 纯 native 线程创建：拿不到 Java 栈，退化为上报线程名
        g_fromNativeCount.fetch_add(1);
        reportToJava(resolveCurrentThreadName());
    }

    // ═══════════════════════════════════════════════════════
    // 【控制段】到这里为止都只是「观测」。下面开始真正干预。
    //
    // 三个可直接操作的点，改的都是 pthread_create 的参数或返回值：
    //   ① attr  → pthread_attr_setstacksize  缩小栈（治内存）
    //   ② 包装 start_routine → 在新线程内设 nice/改名（治调度）
    //   ③ 直接 return EAGAIN → 拒绝创建（治雪崩）
    //
    // ①③ 不需要新线程跑起来就能生效；② 必须在新线程内执行。
    // ═══════════════════════════════════════════════════════

    // 先看速率：无论什么调用点，全局超速都要挡 —— 这是防雪崩的最后一道闸
    bool rate_ok = acquireRateToken();
    if (!rate_ok) {
        g_rejected_count.fetch_add(1);
        ALOGW("速率限制命中，拒绝线程创建（总拒绝=%d）",
              g_rejected_count.load(std::memory_order_relaxed));
        return EAGAIN;
    }

    // 再向 Java 侧要策略（决策与执行分离：策略在 Java，执行在 native）
    // 只有需要决策时才抓调用点，且带 500ms 缓存 —— 绝不每次创建都抓堆栈
    const char* site = callerSiteThrottled(env);
    int decision = queryPolicyFromJava(env, g_on_create_requested, site);

    Action action = Action::ALLOW;
    if (decision == 1) action = Action::DEMOTE;
    else if (decision == 2) action = Action::REJECT;

    if (action == Action::REJECT) {
        g_rejected_count.fetch_add(1);
        ALOGW("策略拒绝线程创建：%s", site);
        return EAGAIN;   // 让调用方（SDK）看到创建失败
    }

    // ───────────────────────────────────────────────
    // ① 栈压缩 —— native 层**真正靠谱**的控制手段
    //
    // 为什么它有效，而「设 nice」无效（实测结论）：
    //   · 栈大小是在 pthread_create 时就确定并 mmap 好的，线程跑起来后
    //     不会再变 —— ART 无法像重置 nice 那样把它改回去。
    //   · 而 nice 会被 ART 覆盖：实测在入口设成 10，
    //     业务真正执行时读 /proc 却是 0（ART 在 Java 线程 run() 时
    //     重新应用 Java 层 priority）。那个 10 只存在于几微秒窗口内。
    //
    // 所以「治内存」交给 native，「治调度」必须交给 Java 层
    // （ThreadFactory 里包 Runnable 的方式）。
    // ───────────────────────────────────────────────
    if (action == Action::DEMOTE && attr != nullptr) {
        // attr 是 const，但实际由调用方以可写内存传入；
        // 这里合法地转掉 const 来修改它（Hook 场景的标准做法）
        pthread_attr_t* mutable_attr = const_cast<pthread_attr_t*>(attr);
        size_t cur = 0;
        if (pthread_attr_getstacksize(mutable_attr, &cur) == 0 && cur > SHRINK_STACK_BYTES) {
            if (pthread_attr_setstacksize(mutable_attr, SHRINK_STACK_BYTES) == 0) {
                g_stackShrunkCount.fetch_add(1);
                ALOGD("策略降级：栈 %zuKB → %zuKB  %s", cur / 1024,
                      SHRINK_STACK_BYTES / 1024, site);
            }
        }
    }

    // ② 全局速率兜底：无论什么调用点，超速都拒绝 —— 防雪崩的最后一道闸
    //    （放在最后，避免它掩盖了上面的策略归因日志）

    // 【空指针保护 + 原样透传】
    // Hook 的本质是 AOP「环绕」——先做自己的事，再把调用原封不动转给原函数。
    // 若 original_pthread_create 为空（说明前面 hook 链路有异常），
    // 返回 -1 表示失败，避免空指针解引用导致崩溃。
    if (original_pthread_create != nullptr) {
        return original_pthread_create(thread, attr, start_routine, arg);
    }
    return -1;
}

// ───────────────────────────────────────────────
// 模块定位：用 dl_iterate_phdr 找目标 so（libart.so 无法 dlopen）
// ───────────────────────────────────────────────

/**
 * 【结构体 + 默认成员初始化器】
 * 每个成员后面跟的 `= 0` / `= nullptr` / `= false` 是 C++11 特性：
 * 「若构造时没有显式给值，就使用这个默认值」。
 * 好处是不用写构造函数，且可以用 `TargetModule mod{};` 一步拿到确定的初始状态。
 *
 * 【ElfW 宏】elf.h 提供的平台适配宏，64 位展开为 Elf64_Xxx、32 位展开为 Elf32_Xxx。
 *   所以代码里写 ElfW(Phdr) 在 arm64-v8a / armeabi-v7a / x86_64 上都能编译——
 *   这是「一份源码、多 ABI 编译」的关键技巧。
 *
 * 【uintptr_t】能装下指针的无符号整数类型，做地址运算比裸指针更直观可靠。
 */
struct TargetModule {
    uintptr_t base = 0;                 // so 的加载基址（load bias）
    const ElfW(Phdr)* phdr = nullptr;   // 程序头表指针
    size_t phnum = 0;                   // 程序头表条目数
    const char* full_name = nullptr;    // 完整路径（来自 dlpi_name）
    const char* name_part = nullptr;    // 要匹配的片段，如 "libart.so"
    bool found = false;                 // 是否已命中
};

/**
 * 【回调函数】dl_iterate_phdr 会对「每个已加载模块」调用一次本函数。
 *
 * 签名由 dl_iterate_phdr 规定，不能改：
 *   int callback(struct dl_phdr_info* info, size_t size, void* data)
 *
 * 返回值语义：
 *   0  → 继续遍历下一个模块
 *   非0 → 立即停止遍历
 *
 * 【void* data 的用途】C 风格的「泛型传参」：
 *   调用方把任意类型指针转成 void* 传进来，回调里再转回去。
 *   这里传入的是 &mod。
 *
 * 【省略形参名的写法】源码里 size_t 后面第二个参数直接不写名字，
 *   表示该参数本函数用不到（也有写成注释掉形参名的做法），
 *   同时避免 -Wunused-parameter 告警。这是 C++ 里很常见的省事写法。
 */
int findModuleCallback(struct dl_phdr_info* info, size_t /*size*/, void* data) {
    // static_cast 把 void* 转回具体类型指针，这是 static_cast 的合法场景之一。
    // auto* 让编译器推导为 TargetModule*，省去重复书写类型名。
    auto* target = static_cast<TargetModule*>(data);

    // dlpi_name 为空说明是主程序自身（可执行文件），不是我们要找的动态库。
    if (info->dlpi_name == nullptr || info->dlpi_name[0] == '\0') {
        return 0; // 主程序自身，跳过
    }

    // strstr 在 info->dlpi_name 中查找子串 target->name_part。
    // 返回 nullptr 表示未找到；非空时返回「首次出现的位置」的指针。
    // 用子串匹配而非全等匹配：不同 ROM 上 so 的路径前缀差异很大。
    if (strstr(info->dlpi_name, target->name_part) != nullptr) {
        // 把命中信息填回调用方提供的结构体（通过指针直接改原对象）。
        target->base = info->dlpi_addr;
        target->phdr = info->dlpi_phdr;
        target->phnum = info->dlpi_phnum;
        target->full_name = info->dlpi_name;
        target->found = true;
        return 1; // 找到即停止遍历（返回非 0）
    }
    return 0;
}

/**
 * 在目标模块的 GOT 表中查找并替换 pthread_create 的地址。
 *
 * 原理：PLT/GOT 机制下，对外部函数的调用先经 PLT 跳到 GOT 表项取地址。
 * 改写该表项即可重定向调用，无需修改任何指令 → 兼容性最好。
 *
 * 【函数签名解读】
 *   bool hookGotEntry(const TargetModule& mod,   ← 引用传参，避免拷贝整个结构体
 *                     const char* symbol_name,   ← 要找的符号名
 *                     void* replacement,         ← 替换目标（新函数地址）
 *                     void** old_value);         ← 出参：回传原函数地址
 *
 *   ★ const TargetModule& mod ——「const 引用」是 C++ 传大对象的标准姿势：
 *       既不像值传递那样复制整个结构体，又不像裸指针那样可能为空，
 *       且 const 承诺函数内部不修改它。
 *
 *   ★ void** old_value ——「指向 void* 的指针」，用来把结果写回调用方。
 *       C 语言没有多返回值，只能通过指针出参来模拟。
 *       调用方写法：void* old = nullptr;  …… &old
 *       函数内写法：*old_value = 某个地址;
 */
bool hookGotEntry(const TargetModule& mod, const char* symbol_name,
                  void* replacement, void** old_value) {
    // 【auto 类型推导】这里等价于：
    //     const uintptr_t base = static_cast<uintptr_t>(mod.base);
    // uintptr_t 是「能装下指针的无符号整数」，做地址算术比裸指针更清晰。
    // 「指针 → 整数」这种平台相关转换允许用 static_cast。
    const auto base = static_cast<uintptr_t>(mod.base);

    // 1. 定位 PT_DYNAMIC —— 必须用 p_vaddr + base，不是 p_offset！
    //    （在非首个 PT_LOAD 段中 p_offset != p_vaddr，用错会读到垃圾内存并 SIGSEGV）
    const ElfW(Dyn)* dyn = nullptr;
    for (size_t i = 0; i < mod.phnum; ++i) {
        // PT_DYNAMIC 是 elf.h 中定义的段类型常量。
        if (mod.phdr[i].p_type == PT_DYNAMIC) {
            // 【下标访问即指针算术】mod.phdr[i] 等价于 *(mod.phdr + i)。
            // 这里同时涉及「整数 → 指针」和「无关类型 → 目标类型」两种转换，
            // 必须用 reinterpret_cast（static_cast 做不到，会编译报错）。
            dyn = reinterpret_cast<const ElfW(Dyn)*>(base + mod.phdr[i].p_vaddr);
            break;  // 找到就退出循环，不必再往下扫
        }
    }
    if (dyn == nullptr) {
        ALOGE("[%s] 未找到 PT_DYNAMIC 段", mod.name_part);
        return false;
    }

    // 2. 收集动态符号表信息
    //
    // ⚠️ 关键平台差异：与 glibc 不同，**Android 的 linker 不重定位 .dynamic 段中的指针**。
    // （这样 .dynamic 可以保持只读；linker 内部在使用时自行加 load_bias。）
    // 因此 d_ptr 拿到的是「相对基址的偏移」，必须自己加上 load_bias 才是有效地址。
    // 先用试探法兼容两种情形：值小于 base 视为未重定位偏移。
    //
    // ───────────────────────────────────────────────
    // 【Lambda 表达式】语法逐段拆解：
    //     [base]                 ← 捕获列表：按值把外部的 base 拷贝进闭包
    //     (ElfW(Addr) v)         ← 参数列表（类型是 ElfW(Addr)，即无符号整数）
    //     -> uintptr_t           ← 尾置返回类型（把返回类型写在参数列表之后）
    //     { return ...; }        ← 函数体
    //
    // 要点：
    //   · Lambda 本质是一个匿名「函数对象（functor）」，调用方式与函数一致：toAbs(x)
    //   · 按值捕获 [base] 是拷贝，闭包在生命周期内始终安全；
    //     按引用捕获 [&base] 则要求 base 在被调用时仍然存活。
    //     此处 base 是局部 const 整数，按值捕获既安全又高效。
    //   · 只能捕获外部「自动存储期」的变量。
    //   · 类型无法手写（编译器生成唯一类型名），所以必须用 auto 接住。
    // ───────────────────────────────────────────────
    const auto toAbs = [base](ElfW(Addr) v) -> uintptr_t {
        // 【三元表达式】若 v 小于 base，视为「未重定位的偏移」，加上基址修正；
        // 否则认为它已经是绝对地址，原样返回。这就是注释所说的「试探法」。
        return (v < base) ? (base + v) : static_cast<uintptr_t>(v);
    };

    // 【一次声明多个同类型变量】
    //   注意 const 的位置：const char* strtab 意为「指向 const char 的指针」，
    //   即不能通过 strtab 改写内容，但 strtab 自身可以被重新赋值。
    const char* strtab = nullptr;     // 动态字符串表（.dynstr）
    const ElfW(Sym)* symtab = nullptr;// 动态符号表（.dynsym）
    void* jmprel = nullptr;           // 重定位表首地址（PLT 相关）
    size_t pltrelsz = 0;              // 重定位表字节长度
    ElfW(Sxword) pltrel_type = DT_RELA;// 重定位类型，默认先按 RELA 处理
    int dyn_count = 0;                // 遍历计数，用于防御性检查

    // 【遍历 .dynamic 数组】
    //   d->d_tag != DT_NULL 是终止条件：ELF 规范要求 .dynamic 以 DT_NULL 结尾。
    //   ++d 是前置自增，指针前进「一个 ElfW(Dyn) 元素」——
    //   指针算术会自动乘以 sizeof(ElfW(Dyn))，不是前进 1 字节。这是指针算术的关键点。
    for (const ElfW(Dyn)* d = dyn; d->d_tag != DT_NULL; ++d) {
        ++dyn_count;
        // 若遍历很多次仍未遇到 DT_NULL，说明前面解析出的 dyn 指针是错的。
        // 这种「护栏式计数」在解析不可信内存时非常必要，能把 SIGSEGV 变成一次安全返回。
        if (dyn_count > 4096) {
            ALOGE("[%s] DT_NULL 未在合理范围内出现，疑似 PT_DYNAMIC 解析错误", mod.name_part);
            return false;
        }
        // 【switch 分发】d_un 是一个 union，同一块内存有两种解读：
        //   取地址用 d->d_un.d_ptr，取数值用 d->d_un.d_val。这是 ELF 规范决定的。
        switch (d->d_tag) {
            case DT_STRTAB:
                strtab = reinterpret_cast<const char*>(toAbs(d->d_un.d_ptr));
                break;
            case DT_SYMTAB:
                symtab = reinterpret_cast<const ElfW(Sym)*>(toAbs(d->d_un.d_ptr));
                break;
            case DT_JMPREL:
                jmprel = reinterpret_cast<void*>(toAbs(d->d_un.d_ptr));
                break;
            case DT_PLTRELSZ: pltrelsz = d->d_un.d_val; break;
            case DT_PLTREL:   pltrel_type = d->d_un.d_val; break;
            // ★ 每个 case 末尾都必须 break，否则会「穿透（fall-through）」执行下一个 case。
            //   这里 default 也是空实现 + break，属于防御性写法。
            default: break;
        }
    }

    // 【格式化输出的类型陷阱】%p 收指针、%zu 收 size_t、%ld 收 long。
    //   类型不匹配在 64 位下极易打出垃圾值。所以这里把 uintptr_t 与各类指针
    //   统一 static_cast<const void*> 再交给 %p；分类枚举转成 long 配 %ld。
    ALOGD("[%s] base=%p dyn=%p 条目数=%d strtab=%p symtab=%p jmprel=%p pltrelsz=%zu pltrel=%ld",
          mod.name_part, reinterpret_cast<const void*>(base),
          static_cast<const void*>(dyn), dyn_count, static_cast<const void*>(strtab),
          static_cast<const void*>(symtab), jmprel, pltrelsz,
          static_cast<long>(pltrel_type));

    // 3. 防御性校验：任何异常直接返回，绝不带着垃圾指针继续跑。
    //    注意：不要检查 *strtab != '\0'——ELF 规范里 .dynstr 首项就是空字符串，
    //    首字节为 0 是正常且必然的。
    if (strtab == nullptr || symtab == nullptr || jmprel == nullptr || pltrelsz == 0) {
        ALOGE("[%s] 动态表信息不完整 (strtab=%p symtab=%p jmprel=%p pltrelsz=%zu)",
              mod.name_part, static_cast<const void*>(strtab),
              static_cast<const void*>(symtab), jmprel, pltrelsz);
        return false;
    }
    // 【字面量后缀】64u 里的 u 表示 unsigned。64MB 的上限纯粹是
    // 「不合常理即视为解析错误」的护栏。
    if (pltrelsz > (64u * 1024u * 1024u)) {
        ALOGE("[%s] PLTRELSZ 异常偏大 (%zu)，疑似解析错误", mod.name_part, pltrelsz);
        return false;
    }

    // 4. 遍历重定位表，找到 pthread_create 对应的 GOT 表项
    //    注意 REL 与 RELA 结构体大小不同（REL 无 addend），用错会错位
    //
    // 【为什么这里不用范围 for？】
    //   jmprel 是 void*，元素大小又随 REL/RELA 而变，无法用范围 for 表达，
    //   只能手动维护 offset 步进。
    const bool is_rela = (pltrel_type == DT_RELA);

    for (size_t offset = 0; offset < pltrelsz; ) {
        // 【就近声明】每轮迭代都是全新的变量，不需要手动清零，这就是「就近声明」的好处。
        uintptr_t r_offset = 0;
        uint32_t sym_index = 0;

        if (is_rela) {
            // 【边界检查】先确认剩余长度足够容纳一个结构体，再读取，避免越界。
            if (offset + sizeof(ElfW(Rela)) > pltrelsz) break;
            // 【void* 不能做算术】C++ 里 void* 没有元素大小概念，p+1 无法定义，
            //   所以先把地址转成 uintptr_t 再加偏移，最后转回结构体指针。
            auto* r = reinterpret_cast<const ElfW(Rela)*>(
                    reinterpret_cast<uintptr_t>(jmprel) + offset);
            offset += sizeof(ElfW(Rela));   // 手动步进：RELA 带 addend，更大
#if defined(__LP64__)
            // 【条件编译】__LP64__ 在 64 位目标（arm64-v8a / x86_64）上由编译器预定义。
            // r_info 把「符号索引 + 重定位类型」打包进一个整数，两种位宽的位域
            // 划分不同，因此必须分开写。宏会在预处理阶段二选一，另一分支不参与编译。
            sym_index = ELF64_R_SYM(r->r_info);
#else
            sym_index = ELF32_R_SYM(r->r_info);
#endif
            r_offset = r->r_offset;
        } else {
            // REL 分支：结构体与 RELA 相同但少了 addend，所以 sizeof 更小。
            if (offset + sizeof(ElfW(Rel)) > pltrelsz) break;
            auto* r = reinterpret_cast<const ElfW(Rel)*>(
                    reinterpret_cast<uintptr_t>(jmprel) + offset);
            offset += sizeof(ElfW(Rel));
#if defined(__LP64__)
            sym_index = ELF64_R_SYM(r->r_info);
#else
            sym_index = ELF32_R_SYM(r->r_info);
#endif
            r_offset = r->r_offset;
        }

        // 【还原符号名】st_name 是「在 .dynstr 中的字节偏移」，
        //   strtab + st_name 即得 C 字符串首地址。
        //   sym_index 用于从符号表中取出对应表项（再读它的 st_name）。
        const char* name = strtab + symtab[sym_index].st_name;

        // strcmp 返回 0 表示「完全相同」。切勿写成 if (strcmp(...))——
        // 那恰恰表示「不相同」，这是 C/C++ 最常见的逻辑反转 bug。
        if (strcmp(name, symbol_name) != 0) {
            continue;   // 不是目标符号，看下一条重定位
        }

        // 【双重指针的典型用法】
        //   slot 是「GOT 表项本身在内存中的地址」，类型 void**（存函数指针的格子）。
        //   r_offset 是相对模块基址的偏移，故有 base + r_offset。
        auto* slot = reinterpret_cast<void**>(base + r_offset);
        // 解引用一次，得到格子里存的值 —— 也就是原函数的真实地址。
        void* current = *slot;

        ALOGD("[%s] 命中 GOT 表项 %s @ %p (当前值=%p)", mod.name_part, symbol_name,
              static_cast<void*>(slot), current);

        // 【出参回传】*old_value 表示「顺着指针找到调用方的变量并写入」。
        //   形参 old_value 本身是局部副本没关系——副本里存的地址正指向调用方的变量。
        *old_value = current;

        // 5. 改写 GOT：该段通常只读且受 RELRO 保护，需先放开写权限
        const size_t page = static_cast<size_t>(getpagesize());
        // ───────────────────────────────────────────────
        // 【位运算实现页对齐】C 系统里最经典的技巧之一：
        //   页大小通常是 2 的幂（4096 = 1<<12），于是
        //     page - 1     = 4095 = 0b00000000000000000000111111111111
        //     ~(page - 1)         = 0b11111111111111111111000000000000
        //   x & ~(page - 1) 就把低 12 位清零 → 得到该地址所在页的起始地址。
        //   ★ 操作数必须够宽（64 位下用 uintptr_t），否则高 32 位会被截断。
        // ───────────────────────────────────────────────
        auto page_start = reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(slot) & ~(page - 1));

        // mprotect 返回 0 表示成功，非 0 表示失败（同时会设置 errno）。
        if (mprotect(page_start, page, PROT_READ | PROT_WRITE) != 0) {
            ALOGE("[%s] mprotect 失败（可能受 RELRO/CFI 保护），放弃改写", mod.name_part);
            return false;
        }
        // 替换该指针宽度的写入（地址对齐，天然原子），这是本次 Hook 的核心动作。
        *slot = replacement;
        // 写完立即恢复只读，尽量减少暴露面（不留一段可写代码段）。
        // 注意：此处未检查返回值，严格说应当检查。
        mprotect(page_start, page, PROT_READ);

        ALOGD("[%s] GOT Hook 成功: %s", mod.name_part, symbol_name);
        return true;   // 已找到并改写成功，无需继续遍历
    }

    ALOGW("[%s] GOT 表中未找到符号: %s", mod.name_part, symbol_name);
    return false;
}

} // namespace  ← 匿名命名空间到此结束。以上所有符号仅本文件可见。

// ───────────────────────────────────────────────
// 【JNI 导出函数】
//
// extern "C" 的作用：C++ 支持函数重载，因此编译器会对函数名做「名称修饰
//   （name mangling）」，例如把 f(int) 编成 _Z1fi。而 Java 侧是按固定名字
//   查找 native 实现的，所以必须用 extern "C" 关闭修饰，让符号名保持原样。
//
// JNIEXPORT / JNICALL 是 jni.h 里的平台宏：
//   JNIEXPORT → 控制符号可见性（如 __attribute__((visibility("default")))）
//   JNICALL   → 控制调用约定，在 ARM / Windows 上有实际意义
//
// 【命名规则（硬性要求）】
//   Java_<包名下划线化>_<类名>_<方法名>
//     com.interview.thread.NativeThreadHook.installNative
//     → Java_com_interview_thread_NativeThreadHook_installNative
//   包名里的 '.' 全部换成 '_'，类名与方法名之间也是 '_'。
//   ★ 若包名中本身含下划线，需转义为 _1；本例包名没有下划线，故无需处理。
//
// 【参数】JNI 方法的固定前置参数：
//   JNIEnv* env   —— 线程相关的 JNI 接口表，只能在本线程使用
//   jobject/jclass—— 实例方法传 this，静态方法传所属类的 jclass
// ───────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_interview_thread_NativeThreadHook_installNative(JNIEnv* env, jclass clazz) {
    // 【atomic 的 exchange 用法】原子地设为 true 并返回旧值。
    //   若旧值已是 true，说明装过了，幂等返回。
    //   这样即使多线程同时调用 installNative 也不会重复安装——
    //   注意这是「先占坑再干活」的模式，所以失败路径里会把标志复位。
    if (g_hooked.exchange(true)) {
        ALOGD("Hook 已安装，跳过");
        return;
    }

    // 【取 JavaVM】把当前 VM 句柄写入 g_jvm，后续跨线程 attach 会用到。
    env->GetJavaVM(&g_jvm);

    // 【FindClass】按「斜杠分隔」的 JVM 内部名查找类。
    //   这里用 com/interview/thread/NativeThreadHook（斜杠），不是点号——
    //   这与导出符号名用下划线、Java 源码用点号，是三套不同的规则。
    jclass local = env->FindClass("com/interview/thread/NativeThreadHook");
    if (local == nullptr) {
        ALOGE("找不到回调类");
        // FindClass 失败通常伴随 ClassNotFoundException 挂起，必须清除。
        env->ExceptionClear();
        g_hooked.store(false);   // 失败则释放「坑位」，允许下次重试
        return;
    }

    // 【全局引用】局部引用 local 在本函数返回后即失效，
    //   但我们需要长期持有这个类，因此要转成 GlobalRef。
    //   代价：全局引用不会被自动回收，需在合适时机 DeleteGlobalRef
    //   （本例是进程级单例，故意不释放）。
    g_callback_class = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);   // 转成全局引用后，局部引用立刻释放

    // 【取静态方法 ID】第三个参数是方法签名（JNI descriptor）：
    //     (Ljava/lang/String;)V
    //      ()                    参数列表括起来
    //      Ljava/lang/String;    一个 String 参数（L...; 表示对象类型，分号结尾）
    //      V                     返回 void
    g_on_thread_created = env->GetStaticMethodID(
            g_callback_class, "onThreadCreatedFromNative", "(Ljava/lang/String;)V");
    if (g_on_thread_created == nullptr) {
        ALOGE("找不到回调方法");
        env->ExceptionClear();
        g_hooked.store(false);
        return;
    }

    // 治理决策回调（可选：拿不到就退化为「一律放行」，不影响观测能力）
    g_on_create_requested = env->GetStaticMethodID(
            g_callback_class, "onThreadCreateRequested", "(Ljava/lang/String;)I");
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (g_on_create_requested == nullptr) {
        ALOGW("未找到策略回调 onThreadCreateRequested，本次仅观测不干预");
    }

    // 预缓存建栈所需的类与方法 ID。
    // 这些在 Hook 点会被高频使用，每次 FindClass/GetMethodID 都很贵。
    g_thread_cls = static_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/Thread")));
    g_ste_cls = static_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/StackTraceElement")));
    if (g_thread_cls != nullptr) {
        g_mid_current_thread = env->GetStaticMethodID(g_thread_cls, "currentThread", "()Ljava/lang/Thread;");
        g_mid_get_stack = env->GetMethodID(g_thread_cls, "getStackTrace", "()[Ljava/lang/StackTraceElement;");
    }
    if (g_ste_cls != nullptr) {
        g_mid_ste_to_string = env->GetMethodID(g_ste_cls, "toString", "()Ljava/lang/String;");
        g_mid_ste_get_class = env->GetMethodID(g_ste_cls, "getClassName", "()Ljava/lang/String;");
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    ALOGD("JNI 句柄预缓存完成（stackTrace 可用=%d）",
          g_mid_get_stack != nullptr ? 1 : 0);

    // ───────────────────────────────────────────────
    // 【C 风格字符串数组】
    //   const char* candidates[] 声明「元素为 const char* 的数组」，
    //   数组长度由初始化列表自动推断（这里是 2）。
    //   ★ 与 const char** 的区别：这是数组类型，sizeof 能得到正确总长度。
    //   C++11 里更严谨的写法是 const char* const candidates[] = {...};
    //   即数组元素（指针本身）也不可修改。
    // ───────────────────────────────────────────────
    const char* candidates[] = {"libart.so", "libc.so"};

    // 【范围 for 循环】C++11 语法： for (声明 : 容器) { 循环体 }
    //   完全等价于传统写法：
    //     for (size_t i = 0; i < 2; ++i) { const char* candidate = candidates[i]; ... }
    //   读作「对 candidates 中的每一个元素 candidate」。
    //   ★ 若元素是复杂对象，应写 const auto& 以避免拷贝；
    //     这里元素只是指针，按值拷贝与引用差别不大。
    for (const char* candidate : candidates) {
        // 【值初始化】TargetModule mod{}; 中那个空花括号表示「用默认值初始化所有成员」，
        //   即启用结构体里的默认成员初始化器。
        //   若写成 TargetModule mod;（无花括号），对 POD 类型属「默认初始化」，
        //   成员值不确定。带上 {} 是更安全、更明确的写法。
        TargetModule mod{};
        mod.name_part = candidate;

        // 遍历已加载模块，回调把命中的模块信息写进 &mod。
        //   若没找到，mod.found 保持 false。
        dl_iterate_phdr(findModuleCallback, &mod);

        if (!mod.found) {
            ALOGW("未在已加载模块中找到 %s", candidate);
            continue;   // 尝试下一个候选模块
        }
        ALOGD("定位到 %s @ base=%p (%s)", candidate,
              reinterpret_cast<void*>(mod.base), mod.full_name);

        void* old = nullptr;
        // &old 的类型正是 void**，函数内通过 *old_value 把原地址写回来——
        // 这就是前面说的「指针出参」模式的实际使用现场。
        if (hookGotEntry(mod, "pthread_create",
                         reinterpret_cast<void*>(hooked_pthread_create), &old)) {
            // 【多行函数指针类型转换】把 void* 转回精确的函数指针类型。
            //   括号位置与声明时严格对应：
            //     reinterpret_cast<返回类型 (*)(参数类型列表)>(值)
            //   ★ 那对括号必须包住 *，否则会被解析成「返回指针的函数类型」。
            //   ★ 这里必须与 hooked_pthread_create 的签名完全一致，
            //     否则后续通过该指针调用会触发未定义行为。
            original_pthread_create =
                    reinterpret_cast<int (*)(pthread_t*, const pthread_attr_t*,
                                             void* (*)(void*), void*)>(old);
            ALOGD("pthread_create Hook 安装完成（模块=%s，原始地址=%p）", candidate, old);
            return;   // 成功即返回，不再尝试后续候选模块
        }
        ALOGW("在 %s 上 Hook 失败，尝试下一个候选模块", candidate);
    }

    ALOGE("所有候选模块均 Hook 失败——该 ROM 可能开启了 CFI/RELRO/FakeGOT 保护");
    g_hooked.store(false);   // 全部失败：复位标志，允许下次重试
}

// ───────────────────────────────────────────────
// 卸载函数：注意这里「并不真的回滚 GOT 表项」。
//   原因：表项已被改写，回滚需要保存原值并再次 mprotect；
//   而且若此刻仍有线程正在调用，回滚可能引入竞态。
//   所以只清标志位，让进程重启后自然复位——这是一个「够用且安全」的工程折中。
//
// 【未使用参数】JNI 固定签名要求保留 (JNIEnv* env, jclass clazz)，
//   即便函数体不用。想消警告可写成 (JNIEnv* /*env*/, jclass /*clazz*/)。
// ───────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_interview_thread_NativeThreadHook_uninstallNative(JNIEnv* env, jclass clazz) {
    g_hooked.store(false);
    ALOGD("Hook 标记已清除（GOT 表项未回滚，进程重启后复位）");
}

/* ═══════════════════════════════════════════════════════════════════
 * 【复习卡片：本文件涉及的 C++ 语法点全景速查】
 *
 * 1. 类型转换
 *    static_cast<T>(x)       相关类型间转换（数值、void*↔具体指针、继承体系内）
 *    reinterpret_cast<T>(x)  位模式重解释（指针↔整数、无关指针类型互转）
 *    const_cast<T>(x)        增删 const
 *    dynamic_cast<T>(x)      运行期带检查的向下转型（需 RTTI）
 *    原则：能用 static_cast 就别用 reinterpret_cast。
 *
 * 2. 指针与引用
 *    T*   可空、可重新指向          T&   不可空、不可重绑定、用法像值
 *    入参大对象惯用 const T&；出参惯用 T* / T**（模拟多返回值）。
 *    指针算术按元素大小步进：p + 1 实际前进 sizeof(*p) 字节。
 *    void* 不能做算术，需先转 uintptr_t 或 char*。
 *
 * 3. 函数指针（务必记住括号）
 *    声明：类型 (*名字)(参数类型列表);
 *    赋值：名字 = reinterpret_cast<返回类型 (*)(参数类型列表)>(地址);
 *    调用：名字(实参...);        // 写法与调用普通函数完全一样
 *
 * 4. 现代 C++ 惯用法
 *    nullptr                 空指针常量
 *    auto                    类型推导
 *    namespace { }           文件内私有（替代 static）
 *    struct S { int a = 0; };默认成员初始化器
 *    X x{};                  值初始化（比 X x; 更安全）
 *    [捕获](参数) -> T { }    Lambda 表达式
 *    for (auto& e : c)       范围 for 循环
 *    std::atomic<T>          无锁原子操作（.store / .load / .exchange）
 *
 * 5. ELF / Hook 领域的类型与宏
 *    ElfW(Xxx)               平台自适应（64 位→Elf64_Xxx，32 位→Elf32_Xxx）
 *    uintptr_t               能装下指针的无符号整数，地址运算首选
 *    d_un.d_ptr / d_un.d_val ELF union 的两种解读（地址 / 数值）
 *    ELF64_R_SYM(r_info)     从 r_info 中抽取符号索引
 *
 * 6. 极易踩的坑
 *    · strcmp 相等返回 0，判「不相等」必须写 != 0
 *    · switch 的每个 case 都要 break，否则会穿透
 *    · 页对齐掩码 x & ~(page - 1) 的操作数要在 64 位下足够宽
 *    · %p 必须配 void*，%zu 配 size_t，类型不匹配会打出垃圾值
 *    · JNI 局部引用要及时 DeleteLocalRef；跨方法持有必须 NewGlobalRef
 *    · JNI 抛出的异常不会自动传播，需 ExceptionCheck + ExceptionClear
 *    · extern "C" 必须写在匿名命名空间之外，否则导出符号名被改写
 * ═══════════════════════════════════════════════════════════════════ */
