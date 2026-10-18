/**
 * Time: 2026/10/16
 * Author: wgt
 * Description: Native 崩溃现场取证 —— 在信号处理器里抓「全线程栈 + 寄存器上下文
 *              + 内存 maps + fd 表 + 进程/线程状态」，落盘后由下次启动回捞上报。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 一、先纠正一个既有结论：`CrashMonitor` 说「App 内不该接 native 崩溃」
 * ══════════════════════════════════════════════════════════════════════
 *
 * `app/src/main/java/com/interview/稳定性监控/CrashMonitor.kt` 的类注释第四条写着：
 *
 * > 可以在 native 侧注册 signal handler 自己抓，但在 App 内做这件事有三个硬伤，
 * > 本项目**不做**。
 *
 * 那三个理由（信号上下文不安全 / 会破坏 debuggerd 取证 / 抓得不如系统好）**都是真的**，
 * 但它们只否决了「**用自研 handler 替换系统取证**」这一种做法。本文件做的是**另一种**：
 *
 * ```
 *   系统默认:   崩溃 → 内核投递信号 → bionic 的 debuggerd handler → fork debuggerd → tombstone
 *   本实现:     崩溃 → 内核投递信号 → [我们的 handler：只抓现场+落盘] → 链式交回
 *                                     └──────► 原 handler → debuggerd → tombstone 照旧
 * ```
 *
 * 也就是 **Crashpad / xCrash 的做法**：取证是**增量**，系统 tombstone **一个都不少**。
 * 那三条理由因此不再成立，但换来了两条**新的**、必须写在明面上的边界（见 §五）。
 *
 * ⚠️ 这段更正已同步到 `CrashMonitor.kt` 的注释里 ——
 *    否则仓库里会同时存在两处互相矛盾的结论，那比写错更难发现。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 二、为什么「链式交回」是硬性要求，而不是可选的客气
 * ══════════════════════════════════════════════════════════════════════
 *
 * `sigaction` **没有** `SA_CHAIN` 之类的语义：新 handler 装上就把旧的顶掉了。
 * 而系统那条 debuggerd 链路（bionic 的 `debuggerd_signal_handler`）除了生成
 * tombstone，还负责几件**只有它能做**的事：
 *
 *   · fork 出 debuggerd，用 ptrace 抓**真正的**寄存器与内存（不依赖崩溃现场还活着）；
 *   · 写 `/data/tombstones/` —— `ApplicationExitInfo(REASON_CRASH_NATIVE)` 的 proto
 *     trace 就是从那儿来的；
 *   · 与 `tombstoned` 串行化，避免崩溃风暴打爆磁盘。
 *
 * 所以「抓完不交回」= 亲手删掉平台证据。本实现抓完**必须**把它交回去。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 三、为什么不用 `unw_init_remote` / ptrace 抓其他线程
 * ══════════════════════════════════════════════════════════════════════
 *
 * 想让**崩溃线程**去解**别的线程**的栈，需要 `unw_init_remote` + 读对方内存
 * （ptrace 或 `/proc/<tid>/mem`）。三个理由否决：
 *
 *   1. 它要遍历对方的 `.eh_frame`，在信号处理器里做这件事的锁与分配风险极高；
 *   2. 需要暂停对方线程，否则读到的是**不一致**的栈 ——
 *      抓出一个"看起来合理但是错的"栈，比抓不到更危险；
 *   3. 平台自己的 debuggerd 就是这么做的，我们再实现一遍只会比它差。
 *
 * ⇒ 改用 **Crashpad 的做法：让每个线程在自己的上下文里抓自己的栈**。
 *   崩溃线程给其他线程发一个专用实时信号，各线程的 handler 里抓栈、写进
 *   **自己的槽位**，崩线程汇总。这样每个线程的栈都取它**自己的**上下文
 *   （寄存器天然正确，不需要读别人内存）。
 *
 * ─── ⚠️ 这里第一个大坑：槽位**不能用 `__thread`**（实测，两条路都堵死）───
 *
 * 直觉做法是 `static __thread Slot t_slot;`（无锁、无 malloc）。**在 Android 上不行**：
 *
 * ```
 *   dlopen failed: TLS symbol "(null)" in dlopened ".../libnativecrash.so"
 *   referenced from ".../libnativecrash.so" using IE access model
 * ```
 *
 * （Redmi K40 / Android 12 与 API 36 arm64 模拟器**都复现**，来自 bionic 的 linker。）
 *
 * | 方案 | 结果 |
 * |---|---|
 * | `-ftls-model=initial-exec`（静态 TLS） | ❌ **装不起来**。bionic 的 linker 不允许被 dlopen 的 .so 引用它自己的**非导出** static TLS 符号。那个 `"(null)"` 就是"拿不到 local 符号名"的直接体现 —— 这条限制**文档里没有**，是 logcat 试出来的 |
 * | 动态模型 / emulated TLS | ⚠️ 能加载，但每次访问都要拿 **linker 的锁**（Android 的 TLS 是 lazy 模块化分配的）。而崩溃现场恰恰可能已经持有各种锁 ⇒ **自己把自己锁死**：表现为点了崩溃按钮后 App 假死，既不崩也不落盘 |
 *
 * ⇒ 唯一正确结论：**信号处理器里根本不该碰 TLS**（无论哪种模型）。
 *   现在改用「`tid` → 槽位」的**开放寻址表**（见 §2 的实现与理由）。
 *
 * ─── ⚠️ 第二个坑：`__thread` 是每线程一份，"我登记别人"物理上做不到 ───
 *
 * 就算 TLS 能用，也还有一个**语义**错误（本文件第一版就栽在这）：
 * 崩溃线程**不可能**替别的线程写入对方的 `t_slot` —— 它拿到的永远是**自己那份**的地址。
 * 错误写法 `g_slots[idx] = &t_slot;` 登记的是崩溃线程自己的槽位，
 * 于是汇总时会打印 N 遍同一个栈，真正的其他线程栈全丢。
 * **这个 bug 不崩、不报错，只让报告"有内容但是错的"** —— 最危险的一类。
 *
 * 现在的写法从根上避免了它：每个线程在**自己的 handler** 里调
 * `slotForThisThread()` 定位/占用自己的槽位，槽位由 `tid` 判定归属，
 * 崩溃线程只负责**读**别人的槽位。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 四、`android_set_abort_message` 的实测修正（写代码前撞到的一个坑）
 * ══════════════════════════════════════════════════════════════════════
 *
 * 按印象，「主动 abort 并带上原因」应该是一对 API：`android_set_abort_message` /
 * `android_get_abort_message`。实测：
 *
 *   · NDK 25 与 NDK 27 的 `android/set_abort_message.h` 里**只有 `set`**
 *     （`__INTRODUCED_IN(21)`），**没有 `get`**；
 *   · 设备侧（Redmi K40 / Android 12）`libc.so` 的导出符号里也只有 `set`。
 *
 * 所以本实现**自己缓存一份** abort message：`crashNow` 同时做两件事 ——
 * 设给 bionic（让 **tombstone** 带上），存进本模块静态缓冲（让**我们的报告**带上）。
 * 如果照印象写成 `get`，会得到一个编译期缺声明、或运行期缺符号的实现。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 五、诚实边界（两条，都会真实咬人）
 * ══════════════════════════════════════════════════════════════════════
 *
 * **边界 1：`_Unwind_Backtrace` 在信号处理器里是「尽力而为」。**
 *
 * 它要走 `.eh_frame`，首次使用会 `dl_iterate_phdr` + 可能 mmap。若这次崩溃恰好发生在
 * **动态链接器锁被持有**时（例如崩溃点是 `dlopen` 内部的代码），本 handler 可能自己
 * 死锁 → 我们的报告落不下来。但交回系统的 tombstone **照旧**（见 §二），证据不会全丢。
 *
 * 真正生产级的做法是 **libunwindstack 的内存解析**（自己读 `/proc/self/mem` + 手工解
 * `.eh_frame`，不依赖运行态 unwinder 及其锁）。但那要自带一份 AOSP libunwindstack ——
 * 实测 `/system/lib64/libunwindstack.so` **不在** `public.libraries.txt` 里，
 * App **不能** `dlopen` 它（那是平台私有库）。
 * ⇒ 本仓库选择「用 NDK 自带的 `_Unwind_Backtrace`，并把这条边界写在明面上」。
 *
 * **边界 2：报告只能携带 `模块名+偏移`，不能在设备上符号化。**
 *
 * Release APK 里没有 `.symtab`（只有 `.dynsym`），`dladdr` 又会在信号处理器里重入
 * 动态链接器锁（同 `memtrace.cpp` 的既有判断）。所以与 `memtrace` 保持同一约定：
 * **只记 `module+offset`，事后用 `tools/native-mem-symbolize.sh` 还原**
 * —— 那个脚本第 155 行的正则会直接抓本文件的输出格式，**无需改动一行**。
 * 这也正是全平台的通行做法（`ndk-stack` / perfetto 的 symbolize 同理）。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 六、信号处理器里**只允许**这些调用（POSIX async-signal-safe 白名单）
 * ══════════════════════════════════════════════════════════════════════
 *
 * 允许：`write` `open` `close` `read` `rename` `fsync` `readlink` `clock_gettime`
 *       `getpid` `gettid` `tgkill`（**按内核 tid 发信号**）`pthread_sigmask` `nanosleep`
 *       （⚠️ `pthread_kill` 虽在 POSIX 白名单里，但它的第一个参数是 pthread_t
 *        句柄而非内核 tid —— 传 tid 会让 bionic 在 handler 里 abort，见 §②
 *        的实测记录。所以本文件**只用 tgkill**。）
 *       `sigaction` `sigemptyset/sigaddset` `memcpy/strlen`（无锁）
 *       `_Unwind_Backtrace`（见边界 1）`android_set_abort_message`
 *       `getdents64`（**raw syscall** —— `opendir` 会 malloc，不能用）
 *
 * 禁止：`malloc/free/new`、`printf/fopen`、`std::string/vector`、`dlopen/dladdr`、
 *       **任何 JNI 调用**、任何加锁、任何 locale 相关格式化。
 *
 * 因此本文件里：
 *   · 所有缓冲是 `.bss` 里的**静态定长数组**（`g_out` / `g_maps` / `g_namePool`）；
 *   · 数字格式化是自己写的 `outU64` / `outHex`（**不用 `snprintf`** —— 它受 locale
 *     影响，且严格说不在 AS-safe 白名单里）；
 *   · **完全不用 TLS**（见 §三 的实测记录：`__thread` 在 dlopen 的 .so 里要么装不起来、
 *     要么会拿 linker 锁）；
 *   · 线程间共享只靠**原子操作 + tid 归属判定**，不用任何锁。
 *
 * 报告格式契约：`app/src/main/java/com/interview/稳定性监控/NATIVE_CRASH_FORMAT.md`
 */

#include <jni.h>

#include <android/log.h>
#include <android/set_abort_message.h>

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <time.h>
#include <ucontext.h>
#include <unwind.h>
#include <unistd.h>

#include <sys/syscall.h>
#include <sys/types.h>

#include <stdint.h>
#include <stdlib.h>  // abort()
#include <string.h>

#define LOG_TAG "NativeCrash"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ═══════════════════════════════════════════════════════════════════════════
// 0. 常量与静态缓冲（信号处理器里唯一允许的"分配"方式：.bss 定长数组）
// ═══════════════════════════════════════════════════════════════════════════

/** 报告正文缓冲。1 MB 够放上千帧 + 完整 maps + fd 表，且不与崩溃现场争内存 */
static const size_t kOutCap = 1u << 20;
static char g_out[kOutCap];
static size_t g_outLen;

/** `/proc/self/maps` 的原始文本。512 KB —— 本机实测约 2000 个 VMA × ~90 B ≈ 180 KB */
static const size_t kMapsCap = 512u << 10;
static char g_maps[kMapsCap];
static size_t g_mapsLen;

/** VMA 索引（把帧地址映射成 module+offset）。上限 4096 个 VMA */
static const int kMaxVma = 4096;
struct VmaRec {
  uintptr_t start;
  uintptr_t end;
  /** maps 第 4 列（段在**后备文件**里的偏移）。
   *  ⚠️ 它不是装饰字段，是符号化正确性的关键，见 resolveFrame 的注释 */
  uintptr_t file_off;
  uint32_t name_off;   // 指向 g_namePool；0 = 匿名映射（对回溯无意义）
  uint8_t executable;  // 只有可执行段的"模块名"对回溯有意义（诊断用）
};
static VmaRec g_vmas[kMaxVma];
static int g_vmaCount;

/** 模块名池（只存 basename，如 "libnative_crash.so"）。64 KB 够 ~1500 个模块 */
static const size_t kNamePoolCap = 64u << 10;
static char g_namePool[kNamePoolCap];
static size_t g_namePoolLen;

/** 每个线程抓栈的最大帧数。memtrace 只用 4 帧（那要的是"归因签名"），
 *  而崩溃要的是**完整调用链**，所以给到 64 */
static const int kMaxFrames = 64;

/** abort message 自缓存（见文件头 §四：android_get_abort_message 不存在） */
static const size_t kAbortMsgCap = 512;
static char g_abortMsg[kAbortMsgCap];

/** 落盘目录 + 版本元信息（后者是**符号化的前提**，见 NATIVE_CRASH_FORMAT.md） */
static const size_t kDirCap = 512;
static char g_dir[kDirCap];
static const size_t kMetaCap = 128;
static char g_versionName[kMetaCap];
static char g_processName[kMetaCap];
static char g_release[32];
static int g_versionCode;
static int g_sdkInt;

/** 抓其他线程栈用的信号。
 *
 *  ⚠️ 选 `SIGRTMIN+3` 的三个理由（缺一个都会踩坑）：
 *   1. **实时信号可排队**，不会像普通信号那样被合并 —— 我们要连发 N 个不同 tid 的信号，
 *      普通信号在 pending 态可能被合并，导致部分线程永远抓不到；
 *   2. `SIGRTMIN`/`+1`/`+2` 已被 ART 与 profiler 占用，从 `+3` 起是社区约定留给崩溃处理的；
 *   3. 它**不能**是 SIGQUIT（那是 ANR 的，本仓库 `anr_signal_hook.cpp` 在抢），
 *      也**不能**是 SIGSEGV 系（否则真崩溃与我们的人造信号混在一起，分不清元凶）。 */
static const int kThreadDumpSignal = SIGRTMIN + 3;

/** 等待其他线程回栈的总预算（ms）。超时就用已拿到的部分，
 *  **绝不无限等** —— 阻塞在不可中断状态（D 态）的线程不会回信号 */
static const int kCollectBudgetMs = 400;

/** 线程槽位上限。正常 App 几十~几百个线程 */
static const int kMaxSlots = 256;

/** 崩溃信号清单。
 *
 *  ⚠️ **不接管 SIGQUIT**：那是 ANR 信号，本仓库有一条专门通道
 *  （`anr_signal_hook.cpp`），两者抢同一个信号只会互相破坏。
 *  ⚠️ **不接管 SIGPIPE**：它不是崩溃（写已关闭 socket 会触发），
 *  接管等于改掉正常的 EPIPE 处理流程。 */
static const int kSignals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE, SIGTRAP, SIGSYS};
static const int kSignalCount = (int)(sizeof(kSignals) / sizeof(kSignals[0]));

// ═══════════════════════════════════════════════════════════════════════════
// 1. async-signal-safe 的最小工具集
// ═══════════════════════════════════════════════════════════════════════════

static inline size_t slen(const char* s) {
  size_t n = 0;
  if (!s) return 0;
  while (s[n]) n++;
  return n;
}

static inline void outRaw(const char* s, size_t n) {
  if (!s || n == 0) return;
  if (g_outLen + n >= kOutCap) return;  // 静默截断：宁可少写，绝不越界
  memcpy(g_out + g_outLen, s, n);
  g_outLen += n;
}

static inline void outStr(const char* s) { outRaw(s, slen(s)); }

/** 十进制无符号。**不用 snprintf**：它受 locale 影响，且严格不在 AS-safe 白名单里 */
static void outU64(uint64_t v) {
  char t[24];
  int i = 0;
  if (v == 0) {
    t[i++] = '0';
  } else {
    while (v > 0 && i < 24) {
      t[i++] = (char)('0' + (v % 10));
      v /= 10;
    }
  }
  while (i > 0) outRaw(&t[--i], 1);
}

static void outI64(int64_t v) {
  if (v < 0) {
    outStr("-");
    outU64((uint64_t)(-(v + 1)) + 1u);  // 这样写避免 INT64_MIN 取负溢出
  } else {
    outU64((uint64_t)v);
  }
}

/** 十六进制，minWidth 用于对齐成 0x0000007f12345678 这种可读形态 */
static void outHex(uint64_t v, int minWidth) {
  static const char* kDigit = "0123456789abcdef";
  char t[20];
  int i = 0;
  if (v == 0) {
    t[i++] = '0';
  } else {
    while (v > 0 && i < 20) {
      t[i++] = kDigit[v & 0xf];
      v >>= 4;
    }
  }
  while (i < minWidth && i < 20) t[i++] = '0';
  outStr("0x");
  while (i > 0) outRaw(&t[--i], 1);
}

static inline void outNL() { outRaw("\n", 1); }

/** si_signo → 名字 */
static const char* signalName(int sig) {
  switch (sig) {
    case SIGSEGV: return "SIGSEGV";
    case SIGABRT: return "SIGABRT";
    case SIGBUS:  return "SIGBUS";
    case SIGILL:  return "SIGILL";
    case SIGFPE:  return "SIGFPE";
    case SIGTRAP: return "SIGTRAP";
    case SIGSYS:  return "SIGSYS";
    default:      return "SIG?";
  }
}

/** si_code → 名字。
 *
 *  ⚠️ 这是**归因的关键**，不是装饰：SEGV_MAPERR（地址未映射，空指针/野指针/UAF）
 *     与 SEGV_ACCERR（地址已映射但无权限，越界写只读段）的修复方向完全不同。 */
static const char* codeName(int sig, int code) {
  if (sig == SIGSEGV) {
    switch (code) {
      case SEGV_MAPERR: return "SEGV_MAPERR";
      case SEGV_ACCERR: return "SEGV_ACCERR";
      default:          return "SEGV_?";
    }
  }
  if (sig == SIGBUS) {
    switch (code) {
      case BUS_ADRALN: return "BUS_ADRALN";  // 未对齐访问
      case BUS_ADRERR: return "BUS_ADRERR";  // 地址不存在（典型：mmap 的文件被截断）
      case BUS_OBJERR: return "BUS_OBJERR";
      default:         return "BUS_?";
    }
  }
  if (sig == SIGILL) {
    switch (code) {
      case ILL_ILLOPC: return "ILL_ILLOPC";
      case ILL_ILLOPN: return "ILL_ILLOPN";
      case ILL_ILLADR: return "ILL_ILLADR";
      default:         return "ILL_?";
    }
  }
  if (sig == SIGFPE) {
    switch (code) {
      case FPE_INTDIV: return "FPE_INTDIV";
      case FPE_INTOVF: return "FPE_INTOVF";
      case FPE_FLTDIV: return "FPE_FLTDIV";
      default:         return "FPE_?";
    }
  }
  switch (code) {
    case SI_USER:  return "SI_USER";   // kill() 发出
    case SI_QUEUE: return "SI_QUEUE";  // sigqueue() 发出
    case SI_TKILL: return "SI_TKILL";  // tgkill() 发出（含 bionic 的 abort() 与 pthread_kill）
    case SI_TIMER: return "SI_TIMER";
    default:       return "SI_?";
  }
}

static inline pid_t gettidFast() { return (pid_t)syscall(__NR_gettid); }

static inline int64_t uptimeMs() {
  struct timespec ts;
  // CLOCK_MONOTONIC 走 vDSO：无锁、无 syscall —— 在信号处理器里这点很重要
  if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return -1;
  return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/** 只用于 /proc 下的小文件；不做重试、不分配 */
static ssize_t readSmallFile(const char* path, char* dst, size_t cap) {
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return -1;
  size_t total = 0;
  while (total < cap - 1) {
    ssize_t n = read(fd, dst + total, cap - 1 - total);
    if (n == 0) break;
    if (n < 0) {
      if (errno == EINTR) continue;
      break;
    }
    total += (size_t)n;
  }
  close(fd);
  dst[total] = '\0';
  return (ssize_t)total;
}

/** 手工把 pid 追加进路径缓冲（不用 snprintf，理由同 outU64） */
static void appendPathNum(char* path, size_t cap, size_t* p, uint32_t v) {
  char num[16];
  int i = 0;
  if (v == 0) {
    num[i++] = '0';
  } else {
    while (v > 0 && i < 16) {
      num[i++] = (char)('0' + (v % 10));
      v /= 10;
    }
  }
  while (i > 0 && *p < cap - 1) path[(*p)++] = num[--i];
}

static void appendPathStr(char* path, size_t cap, size_t* p, const char* s) {
  while (*s && *p < cap - 1) path[(*p)++] = *s++;
}

/** 读 `/proc/self/task/<tid>/comm` 拿线程名。
 *  ⚠️ 不用 `pthread_getname_np`：它内部可能加锁，不是 AS-safe 的 */
static void readThreadName(pid_t tid, char* dst, size_t cap) {
  char path[96];
  size_t p = 0;
  appendPathStr(path, sizeof(path), &p, "/proc/self/task/");
  appendPathNum(path, sizeof(path), &p, (uint32_t)(tid < 0 ? 0 : tid));
  appendPathStr(path, sizeof(path), &p, "/comm");
  path[p] = '\0';

  char buf[64];
  ssize_t n = readSmallFile(path, buf, sizeof(buf));
  if (n <= 0) {
    dst[0] = '?';
    dst[1] = '\0';
    return;
  }
  size_t k = 0;
  while (k < (size_t)n && buf[k] && buf[k] != '\n' && k < cap - 1) {
    dst[k] = buf[k];
    k++;
  }
  dst[k] = '\0';
}

// ═══════════════════════════════════════════════════════════════════════════
// 2. 每线程槽位：**不用 TLS，用「tid → 槽位」的开放寻址表**
// ═══════════════════════════════════════════════════════════════════════════
//
// ⚠️⚠️ 这里原本用的是 `__thread`（每线程 TLS 槽位），**在真机上装不起来**。
//      实测错误（Redmi K40 / Android 12 与 API 36 模拟器都复现，bionic 的 linker）：
//
//        dlopen failed: TLS symbol "(null)" in dlopened ".../libnativecrash.so"
//        referenced from ".../libnativecrash.so" using IE access model
//
//      **两条路都堵死了**，这是本模块最贵的一个教训：
//
//      ┌ `initial-exec`（编译期静态 TLS）
//      │   bionic 的 linker 有一条明确限制：**被 dlopen 的 .so 不能引用
//      │   它自己的、非导出的 static TLS 符号**。我们的 `t_slot` 是 `static`
//      │   （不导出），于是报上面那条 `TLS symbol "(null)"` —— 注意那个 "(null)"
//      │   就是"符号名查不到"的直接体现（它是 local 符号，linker 拿不到名字）。
//      │   ⚠️ 这一条**官方文档里没有**，是靠真机 logcat 试出来的。
//      └ `__tls_get_addr` / emulated TLS（动态模型）
//          能加载，但每次访问 TLS 都要拿 **linker 的锁**（Android 的 TLS 是 lazy
//          模块化分配的），而我们的信号处理器恰恰运行在"崩溃线程可能已持有各种锁"
//          的时刻 —— 那是**自己把自己锁死**（表现为点崩溃按钮后 App 假死，
//          既不崩也不落盘）。
//
//      ⇒ 结论：**信号处理器里根本不该碰 TLS。** 改用下面这套无锁结构。
//
// ── 替代方案：`tid` 直接定位槽位 ──
//
// 每个线程在 handler 里 `open` 一次 `/proc/self/task/<tid>/stat`，取它的
// **最后一个字段**（`processor`，即该线程上次运行在哪个 CPU 上）。
//
// ```
//   idx = (tid + processor * 97) % kMaxSlots      ← 乘法错开，避免同 CPU 的线程聚簇
//   冲突则线性探测下一个空槽（开放寻址）
// ```
//
// 为什么这样能保证"每个线程写的是自己的槽位、且不互相覆盖"：
//   · 判据是 `slot.tid == 0`（空槽），而非"能不能覆盖"；
//   · 若探到 `slot.tid == 我自己的 tid` → 说明本线程已登记过（重复请求），
//     直接复用，**不会二次占用**（这一点是幂等性的关键）；
//   · 表满（>kMaxSlots 个线程）时**放弃登记**而不是覆盖别人 ——
//     覆盖会让两个线程共用一个槽位，产出的是"混在一起的错栈"，
//     比少抓一个线程糟得多（与文件头 §三 那个 bug 同一类后果）。
//
// 代价（诚实标注）：
//   · 每个线程多做一次 `open`+`read`（~10µs 量级），只在崩溃路径上发生一次；
//   · `tgkill` 可能在目标线程写完槽位之前返回（异步投递），所以要给足"看不到"的容忍 ——
//     这也是我们本来就有的 400ms 等待预算（见文件头 §三 的等待逻辑）。
//
// ⚠️ `processor` 这个字段有**版本差异**（Android 12 起 `/proc/.../stat` 里
//    `exit_code` 之类的字段有增删），所以**不能按固定列号取**。这里用
//    "以最后一个 `)` 之后按空格切分、取最后一段"的办法 —— 稳妥且与内核版本无关。
//
// 这一整段是**实测修正**，不是设计偏好：CMakeLists 里那两个 TLS flag
// 现在必须去掉（留着会让 .so 装不起来），见那里的注释。

struct Slot {
  int tid;
  int count;
  uintptr_t frames[kMaxFrames];
};

static Slot g_slots[kMaxSlots];
static volatile int g_slotCount;      // 已登记的线程数（含崩溃线程）
static volatile int g_expectThreads;  // 发出请求的线程数（用于等待判据）
static volatile int g_slotLost;       // 表满/冲突失败的次数（诊断：告诉使用者"少抓了几个"）

/**
 * 从 `/proc/self/task/<tid>/stat` 取该线程最后运行在哪个 CPU。
 *
 * 文件格式：`12345 (comm) S ppid pgrp ... processor exit_signal ...`
 * ⚠️ `comm` 可能含空格与括号（线程名可以叫 `(foo)`），所以必须从**最后一个 `)`**
 *    之后开始切 —— 否则遇到名字里带括号的线程就会整体错位。
 * 取不到时返回 -1（调用方退化为只用 tid 定位，仍然能工作）。
 */
static int threadCpu(pid_t tid) {
  char path[96];
  size_t p = 0;
  appendPathStr(path, sizeof(path), &p, "/proc/self/task/");
  appendPathNum(path, sizeof(path), &p, (uint32_t)(tid < 0 ? 0 : tid));
  appendPathStr(path, sizeof(path), &p, "/stat");
  path[p] = '\0';

  // ⚠️ 必须**栈上缓冲**，不能 static：本函数会被**多个线程并发**执行
  //    （每个收到 dump 信号的线程都在自己的 handler 里调它）。
  //    static 意味着所有线程共用同一块 —— 并发写 = 互相覆盖/读到半截数据。
  //    512 字节在信号栈上毫无压力（备用栈 32KB；普通线程用自己的栈更没问题）。
  //    本文件里其它 static 缓冲（g_out/g_maps/collectTids 的 ents…）都只在
  //    **崩溃线程**上执行，不并发 —— 那是刻意的；这里不一样。
  char buf[512];
  ssize_t n = readSmallFile(path, buf, sizeof(buf));
  if (n <= 0) return -1;
  // 从最后一个 ')' 之后开始
  ssize_t last = -1;
  for (ssize_t i = (ssize_t)n - 1; i >= 0; i--) {
    if (buf[i] == ')') {
      last = i;
      break;
    }
  }
  if (last < 0) return -1;
  // 取这一段里的最后一个数字字段（processor）
  int val = -1;
  ssize_t i = last + 1;
  while (i < n) {
    while (i < n && buf[i] == ' ') i++;
    if (i >= n) break;
    // 解析一个可能的十进制数
    int v = 0;
    int digits = 0;
    while (i < n && buf[i] >= '0' && buf[i] <= '9') {
      v = v * 10 + (buf[i] - '0');
      i++;
      digits++;
      if (digits > 9) { digits = 0; break; }  // 超长说明不是我们要的字段，跳
    }
    if (digits > 0) val = v;
    // 跳到下一个空格
    while (i < n && buf[i] != ' ') i++;
  }
  return val;
}

/**
 * 取（或登记）本线程的槽位。**幂等**：同一 tid 重复调用返回同一个槽位。
 *
 * ⚠️ 占坑必须用 **CAS**，不能"读 0 就写"：
 *    多个线程的 handler 并发跑到这里，两个都读到 `tid==0`、都把自己写进去 ——
 *    后写的把先写的覆盖掉，先写的线程栈就**静默丢了**，而且先写的线程
 *    还认为自己登记成功（它拿着的槽位已经被别人改了归属）。
 *    这正是 §三 里"覆盖会让两个线程共用一个槽位，产出混在一起的错栈"
 *    那条警告的具体形态 —— 错栈比少栈更危险，所以宁可 CAS 失败继续探测。
 */
static Slot* slotForThisThread() {
  const int tid = (int)gettidFast();
  const int cpu = threadCpu(tid);
  // 乘法错开：同一 CPU 上的线程若只按 tid 取模会聚簇（tid 连续分配）
  int idx = (int)(((uint32_t)tid + (uint32_t)(cpu < 0 ? 0 : cpu) * 97u) % (uint32_t)kMaxSlots);
  for (int probe = 0; probe < kMaxSlots; probe++) {
    Slot* s = &g_slots[idx];
    int cur = __atomic_load_n(&s->tid, __ATOMIC_ACQUIRE);
    if (cur == tid) return s;  // 本线程已登记过 → 复用（幂等）
    if (cur == 0) {
      // CAS 抢占 0 → tid。失败说明被别人先占了，继续探测下一个槽。
      int expected = 0;
      if (__atomic_compare_exchange_n(&s->tid, &expected, tid, false,
                                      __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        s->count = 0;
        return s;
      }
      // CAS 失败后 expected 里是被写入的新值；若恰好是自己的 tid（极罕见：
      // 本线程重入），也按复用处理
      if (expected == tid) return s;
    }
    idx = (idx + 1) % kMaxSlots;  // 线性探测
  }
  __atomic_fetch_add(&g_slotLost, 1, __ATOMIC_SEQ_CST);
  return nullptr;
}

static bool unwindInto(Slot* s) {
  if (!s) return false;
  s->count = 0;
  _Unwind_Backtrace(
      [](struct _Unwind_Context* ctx, void* arg) -> _Unwind_Reason_Code {
        Slot* st = static_cast<Slot*>(arg);
        if (st->count >= kMaxFrames) return _URC_END_OF_STACK;
        uintptr_t ip = _Unwind_GetIP(ctx);
        if (ip != 0) st->frames[st->count++] = ip;
        return _URC_NO_REASON;
      },
      s);
  return s->count > 0;
}

/** 其他线程的 handler：**只做**"抓自己的栈 + 登记自己的槽位"。
 *  绝不碰 g_out（那是崩溃线程的缓冲，并发写会切碎报告）—— 写权只有一份。 */
static void threadDumpHandler(int sig, siginfo_t* info, void* ucontext) {
  (void)sig;
  (void)info;
  (void)ucontext;
  Slot* s = slotForThisThread();
  if (!s) return;
  unwindInto(s);
  __atomic_fetch_add(&g_slotCount, 1, __ATOMIC_SEQ_CST);
}

// ═══════════════════════════════════════════════════════════════════════════
// 3. VMA 索引：帧地址 → module+offset
// ═══════════════════════════════════════════════════════════════════════════

static inline void namePoolPush(const char* s, size_t n, uint32_t* outOff) {
  if (g_namePoolLen + n + 1 >= kNamePoolCap) {
    *outOff = 0;
    return;
  }
  *outOff = (uint32_t)g_namePoolLen;
  memcpy(g_namePool + g_namePoolLen, s, n);
  g_namePoolLen += n;
  g_namePool[g_namePoolLen++] = '\0';
}

static inline uintptr_t hexVal(char c) {
  if (c >= '0' && c <= '9') return (uintptr_t)(c - '0');
  if (c >= 'a' && c <= 'f') return (uintptr_t)(c - 'a' + 10);
  if (c >= 'A' && c <= 'F') return (uintptr_t)(c - 'A' + 10);
  return (uintptr_t)-1;
}

/** 解析一行 maps：`7f0000-7f1000 r-xp 00000000 fd:00 1234 /path/to/lib.so` */
static void parseMapsLine(char* line, size_t len) {
  if (g_vmaCount >= kMaxVma) return;
  size_t i = 0;
  uintptr_t start = 0, end = 0;
  while (i < len && line[i] != '-') {
    uintptr_t v = hexVal(line[i++]);
    if (v == (uintptr_t)-1) continue;
    start = (start << 4) | v;
  }
  if (i < len && line[i] == '-') i++;
  while (i < len && line[i] != ' ') {
    uintptr_t v = hexVal(line[i++]);
    if (v == (uintptr_t)-1) continue;
    end = (end << 4) | v;
  }
  if (end <= start) return;

  while (i < len && line[i] == ' ') i++;
  uint8_t exec = (i + 2 < len && line[i + 2] == 'x') ? 1 : 0;

  // ── 第 4 列：file offset（`r-xp 00470000` 的那个十六进制数）──
  //
  // ⚠️ 必须先**跳过 perms 段**再取下一个 token。第一版少了这一步：
  // i 还指着 'r'（r-xp 的开头）就开始按十六进制解析，'r' 不是合法十六进制
  // 字符 → 立即 break → fileOff 恒为 0 → resolveFrame 的修正项等于没加，
  // 表现为"代码改了、注释写了、输出一个字没变"。这类"解析器静默吞错"的
  // 检查办法只有一个：拿真值对（tombstone 的 abort+168=0x8aa28）核对。
  uintptr_t fileOff = 0;
  {
    while (i < len && line[i] != ' ') i++;   // 跳过 perms（r-xp / rw-p / …）
    while (i < len && line[i] == ' ') i++;   // 跳过随后的空格
    size_t st = i;
    while (i < len && line[i] != ' ') i++;   // 第 4 列到下一个空格为止
    for (size_t k = st; k < i; k++) {
      uintptr_t v = hexVal(line[k]);
      if (v == (uintptr_t)-1) { fileOff = 0; break; }
      fileOff = (fileOff << 4) | v;
    }
  }

  uint32_t nameOff = 0;
  for (size_t k = i; k < len; k++) {
    if (line[k] == '/' && (k == 0 || line[k - 1] == ' ')) {
      size_t e = k;
      while (e < len && line[e] != '\n' && line[e] != '\r') e++;
      size_t b = e;
      while (b > k && line[b - 1] != '/') b--;
      namePoolPush(line + b, e - b, &nameOff);
      break;
    }
  }

  g_vmas[g_vmaCount].start = start;
  g_vmas[g_vmaCount].end = end;
  g_vmas[g_vmaCount].file_off = fileOff;
  g_vmas[g_vmaCount].name_off = nameOff;
  g_vmas[g_vmaCount].executable = exec;
  g_vmaCount++;
}

static void buildVmaIndex() {
  g_mapsLen = 0;
  g_vmaCount = 0;
  g_namePoolLen = 0;
  g_namePool[0] = '\0';

  int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
  if (fd < 0) return;
  // ⚠️ maps 可能被并发修改（别的线程在 dlopen），可能读到"半行"。
  //    不是问题：解析失败的行跳过即可，地址→区间的映射不要求逐行一致。
  size_t total = 0;
  while (total < kMapsCap - 1) {
    ssize_t n = read(fd, g_maps + total, kMapsCap - 1 - total);
    if (n == 0) break;
    if (n < 0) {
      if (errno == EINTR) continue;
      break;
    }
    total += (size_t)n;
  }
  close(fd);
  g_maps[total] = '\0';
  g_mapsLen = total;

  size_t lineStart = 0;
  for (size_t i = 0; i <= total; i++) {
    if (i == total || g_maps[i] == '\n') {
      if (i > lineStart) parseMapsLine(g_maps + lineStart, i - lineStart);
      lineStart = i + 1;
    }
  }
}

/**
 * 查地址落在哪个 VMA。maps 按 start 升序 ⇒ 二分。**纯内存操作，无 syscall**。
 *
 * ⚠️⚠️ offset 的口径是本模块**第三个实测坑**，必须写清楚：
 *
 *   错误口径：`offset = addr - vma.start`（"段内偏移"）
 *     真机对照：我们输出 `libc.so+0x43a28`，而 tombstone 对同一帧输出
 *     `libc.so (abort+168) pc 0x8aa28` —— 恰好差 0x47000，
 *     正是 maps 第 4 列（file offset）。
 *
 *   正确口径：`offset = (addr - vma.start) + file_off`（"后备文件内偏移"）
 *     对 .so 直接映射的段（系统库都是这种）：这个值 == ELF 的 link-time
 *     虚拟地址（Android 的 lld 产物里 p_vaddr == p_offset），正是
 *     `llvm-symbolizer --obj=xxx.so <off>`、`ndk-stack`、tombstone 三者共用的口径。
 *
 *   ⇒ 不加 file_off 的后果不是"解不出来"，而是**解出错误的函数**：
 *     偏移小了 0x47000，落在 ELF 里更早的位置 —— symbolizer 会返回一个
 *     看起来完全合理的别的函数。这比解析失败危险得多（也是
 *     native-mem-symbolize.sh 头部那条"对不上的符号表比解析不出来更危险"
 *     警告的同型错误，只是这次错在偏移而不是符号表）。
 *
 *   ⚠️ APK 内嵌 .so 的诚实边界（真机 API 31 实测）：
 *     本机 maps 里我们自己的 .so 是以 `r-xp 00610000 … base.apk` 形态映射的
 *     —— 后备文件是 **base.apk**，不是 .so。所以：
 *       · 模块名只能是 `base.apk`（诚实）；
 *       · 此时的 offset 是 **APK 文件内偏移**，不是 .so 内偏移。
 *     debuggerd 能写出 `base.apk!libnativecrash.so`，是因为它在 **fork 出的
 *     crash_dump 进程里解析了 ELF**（读 SONAME + BuildId）。我们刻意不在信号
 *     处理器里做这件事（读 ELF 头 + 遍历 program header 在崩溃现场太重）。
 *     ⇒ 离线还原 .so 内偏移的办法（报告里自带全部信息）：
 *        1. 从 APK 解出 lib/arm64-v8a/libnativecrash.so（`unzip -p`）
 *        2. `readelf -l` 取可执行段的 p_offset（记 X）与该 .so 在 APK 内的
 *           起始偏移（记 S；由 zip 目录可得，且满足 S + X == maps 的 file_off）
 *        3. .so 内偏移 = 报告里的 offset - S
 *        （完整 MAPS 段就在报告里，S、X 离线都可复核）
 */
static void resolveFrame(uintptr_t addr, const char** moduleName, uintptr_t* offset) {
  *moduleName = nullptr;
  *offset = 0;
  int lo = 0, hi = g_vmaCount - 1;
  while (lo <= hi) {
    int mid = lo + (hi - lo) / 2;
    if (addr < g_vmas[mid].start) {
      hi = mid - 1;
    } else if (addr >= g_vmas[mid].end) {
      lo = mid + 1;
    } else {
      const VmaRec& v = g_vmas[mid];
      *moduleName = v.name_off ? (g_namePool + v.name_off) : nullptr;
      // 口径见函数头注释：必须是"后备文件内偏移"，不是"段内偏移"
      *offset = (addr - v.start) + v.file_off;
      return;
    }
  }
}

// ═══════════════════════════════════════════════════════════════════════════
// 4. 各段落写出
// ═══════════════════════════════════════════════════════════════════════════

static void writeOneBacktrace(const char* threadName, const Slot* slot) {
  outStr("--- BACKTRACE ");
  outStr(threadName);
  outStr(" (tid=");
  outU64((uint64_t)(slot ? slot->tid : 0));
  outStr(") ---");
  outNL();
  if (!slot || slot->count <= 0) {
    outStr("  (无：该线程未在预算内回栈)");
    outNL();
    return;
  }
  for (int i = 0; i < slot->count; i++) {
    outStr("  #");
    if (i < 10) outStr("0");
    outU64((uint64_t)i);
    outStr(" ");
    outHex(slot->frames[i], 16);
    outStr(" ");
    const char* mod = nullptr;
    uintptr_t off = 0;
    resolveFrame(slot->frames[i], &mod, &off);
    if (mod) {
      // ⚠️ 沿用 memtrace 的 `module+offset` 格式 —— tools/native-mem-symbolize.sh
      //    第 155 行的正则会直接抓这一串，脚本零改动
      outStr(mod);
      outStr("+");
      outHex(off, 0);
    } else {
      outStr("(anonymous)");
    }
    outNL();
  }
}

/** LinuxDirent64 的手写定义：<dirent.h> 的 struct dirent 依赖平台，
 *  而我们要自己调 getdents64 raw syscall（opendir 会 malloc） */
struct LinuxDirent64 {
  uint64_t d_ino;
  int64_t d_off;
  unsigned short d_reclen;
  unsigned char d_type;
  char d_name[];
};

static int collectTids(pid_t* out, int maxOut) {
  int fd = open("/proc/self/task", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  if (fd < 0) return 0;
  static char ents[32 << 10];
  int count = 0;
  for (;;) {
    ssize_t n = syscall(__NR_getdents64, fd, ents, sizeof(ents));
    if (n <= 0) break;
    ssize_t off = 0;
    while (off < n && count < maxOut) {
      LinuxDirent64* d = reinterpret_cast<LinuxDirent64*>(ents + off);
      if (d->d_reclen == 0) break;
      const char* name = d->d_name;
      pid_t tid = 0;
      int ok = (name[0] != '\0');
      for (const char* p = name; *p; p++) {
        if (*p < '0' || *p > '9') {
          ok = 0;
          break;
        }
        tid = tid * 10 + (*p - '0');
      }
      if (ok && tid > 0 && count < maxOut) out[count++] = tid;
      off += d->d_reclen;
    }
    if (count >= maxOut) break;
  }
  close(fd);
  return count;
}

static void writeFdTable() {
  outStr("--- FD ---");
  outNL();
  int dfd = open("/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  if (dfd < 0) return;
  static char ents[16 << 10];
  ssize_t n = syscall(__NR_getdents64, dfd, ents, sizeof(ents));
  if (n > 0) {
    ssize_t off = 0;
    while (off < n) {
      LinuxDirent64* d = reinterpret_cast<LinuxDirent64*>(ents + off);
      if (d->d_reclen == 0) break;
      const char* name = d->d_name;
      int isNum = (name[0] != '\0');
      for (const char* q = name; *q; q++) {
        if (*q < '0' || *q > '9') {
          isNum = 0;
          break;
        }
      }
      if (isNum) {
        outStr("  ");
        outStr(name);
        outStr(" -> ");
        char linkPath[64];
        size_t p = 0;
        appendPathStr(linkPath, sizeof(linkPath), &p, "/proc/self/fd/");
        appendPathStr(linkPath, sizeof(linkPath), &p, name);
        linkPath[p] = '\0';
        char target[256];
        ssize_t k = readlink(linkPath, target, sizeof(target) - 1);
        if (k > 0) {
          target[k] = '\0';
          outStr(target);
        } else {
          outStr("?");
        }
        outNL();
      }
      off += d->d_reclen;
    }
  }
  close(dfd);
}

static void writeProcFile(const char* header, const char* path) {
  // ⚠️ 段名必须**整条字面量**写出来（不能拼 "--- " + header + " ---"）。
  //    理由：Java 侧有两个测试直接读本文件、核对段名字面量
  //    （NativeCrashReportTest.sectionHeadersMatchNativeSource）——
  //    那是 C++/Kotlin 之间唯一能在构建期挡住"段名漂移"的手段。
  //    段名漂移的后果是**该段被整段静默丢弃**（解析器认不出来也不报错）。
  //    ⚠️ 注意 STATUS 与 CMDLINE 的段名共用这一个函数，所以下面按字符串比较分发。
  if (strcmp(header, "STATUS") == 0) {
    outStr("--- STATUS ---");
  } else if (strcmp(header, "CMDLINE") == 0) {
    outStr("--- CMDLINE ---");
  } else {
    outStr("--- ");
    outStr(header);
    outStr(" ---");
  }
  outNL();
  static char tmp[16 << 10];
  ssize_t n = readSmallFile(path, tmp, sizeof(tmp));
  if (n <= 0) {
    outStr("  (读取失败)");
    outNL();
    return;
  }
  outRaw(tmp, (size_t)n);
  if (tmp[n - 1] != '\n') outNL();
}

// ═══════════════════════════════════════════════════════════════════════════
// 5. 注册表 / 交回系统
// ═══════════════════════════════════════════════════════════════════════════

static volatile sig_atomic_t g_installed;
static volatile sig_atomic_t g_inHandler;   // 防重入
static volatile sig_atomic_t g_crashCount;  // 诊断读数

struct SavedAction {
  int sig;
  struct sigaction old;
};
static const int kMaxSig = 8;
static SavedAction g_saved[kMaxSig];
static int g_savedCount;

static void nativeCrashHandler(int sig, siginfo_t* info, void* ucontext);
static void writeReportFile(pid_t pid);

/** 把控制权交回系统 —— **本文件存在的前提**（见文件头 §二）。
 *
 *  两条路径，按原 handler 是什么来选：
 *   A) 原本是别的 handler（最常见：bionic 的 debuggerd handler，带 SA_SIGINFO）
 *      → **直接函数调用**它。这比"恢复后重发"更稳：重发会丢掉当前 siginfo/ucontext，
 *        而 debuggerd 正是靠这两个参数干活的。
 *   B) 原本就是 SIG_DFL（或 SIGH_IGN）
 *      → 恢复默认处置后用 `tgkill` **原样重发**。 */
static void handOffToSystem(int sig, siginfo_t* info, void* ucontext) {
  for (int i = 0; i < g_savedCount; i++) {
    if (g_saved[i].sig != sig) continue;
    const struct sigaction& old = g_saved[i].old;

    if (old.sa_flags & SA_SIGINFO) {
      if (old.sa_sigaction != nullptr) {
        old.sa_sigaction(sig, info, ucontext);
        return;
      }
    } else {
      if (old.sa_handler == SIG_IGN) {
        // 原本忽略（极少见）：恢复忽略并返回
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = SIG_IGN;
        sigemptyset(&sa.sa_mask);
        sigaction(sig, &sa, nullptr);
        return;
      }
      if (old.sa_handler != nullptr && old.sa_handler != SIG_DFL) {
        old.sa_handler(sig);
        return;
      }
    }
    break;  // 是 SIG_DFL → 走下面的重发路径
  }

  // 路径 B：三个细节都必要，少一个就出问题
  //
  //  ① 先设 `SIG_DFL`：否则重发的信号会**再进我们的 handler** → 无限递归。
  //  ② 先 `pthread_sigmask(SIG_UNBLOCK)`：信号掩码是 **per-thread** 的，
  //     我们此刻正屏蔽着 sig（handler 的 sa_mask 里就有）。不解除则重发的信号
  //     只会挂在 pending 而**永不投递** → 进程卡死，而不是崩溃。
  //     （这是本模块最危险的一处：写错的表现是"点击崩溃按钮后 App 假死"，
  //       而不是"崩溃"——会被误判成 handler 把进程弄挂了。）
  //  ③ 用 `tgkill(pid, tid, sig)`，不是 `raise`：明确发给**同一个线程**，
  //     这样 debuggerd 看到的"崩溃线程"与我们抓的栈是同一条。
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_handler = SIG_DFL;
  sigemptyset(&sa.sa_mask);
  sigaction(sig, &sa, nullptr);

  sigset_t set;
  sigemptyset(&set);
  sigaddset(&set, sig);
  pthread_sigmask(SIG_UNBLOCK, &set, nullptr);

  syscall(__NR_tgkill, getpid(), gettidFast(), sig);

  // 正常情况走不到这里（信号同步投递，进程当场就没了）。
  // 若真走到（例如信号又被屏蔽），退化为 `_exit` —— **绝不能让崩溃线程继续跑**：
  // 那会让 App 带着损坏的状态"假活"下去，比崩溃更难查（见 CrashMonitor 细节 2）。
  _exit(128 + sig);
}

// ═══════════════════════════════════════════════════════════════════════════
// 6. 主 handler
// ═══════════════════════════════════════════════════════════════════════════

static pid_t g_tids[512];
static const int kMaxTids = (int)(sizeof(g_tids) / sizeof(g_tids[0]));

static void nativeCrashHandler(int sig, siginfo_t* info, void* ucontext) {
  // ── 防重入：handler 自身再崩（例如 _Unwind 死锁后的二次故障）→ 立即交回系统 ──
  if (g_inHandler) {
    handOffToSystem(sig, info, ucontext);
    return;
  }
  g_inHandler = 1;
  g_crashCount++;

  const pid_t crashTid = gettidFast();
  const pid_t pid = getpid();
  const int signo = info ? info->si_signo : sig;

  // ══ ① 最高优先级：先抓**崩溃线程自己**的栈 ══
  //
  // 顺序不可调换。后面每一步（遍历 /proc、给 N 个线程发信号、读 maps）都可能失败或超时，
  // 唯独"崩溃线程的栈"是**其他任何手段都替代不了**的 —— 系统 tombstone 也抓，
  // 但那要等 debuggerd fork 起来、且依赖 debuggerd 还能跑。
  //
  // ⚠️ 用 slotForThisThread() 而不是"直接写一个自己的 __thread 变量"：
  //    本模块**完全不用 TLS**（原因见 §2 的实测记录）。
  Slot* mySlot = slotForThisThread();
  if (mySlot) {
    unwindInto(mySlot);
    __atomic_fetch_add(&g_slotCount, 1, __ATOMIC_SEQ_CST);
  }

  // 崩溃线程名（在任何耗时操作**之前**取好：读 /proc 失败也不影响主信息）
  char crashThreadName[64];
  readThreadName(crashTid, crashThreadName, sizeof(crashThreadName));

  // ══ ② 让其他线程各自抓自己的栈 ══
  int tidCount = collectTids(g_tids, kMaxTids);
  g_expectThreads = 0;
  for (int i = 0; i < tidCount; i++) {
    pid_t tid = g_tids[i];
    if (tid == crashTid) continue;
    // ⚠️⚠️ 必须用 **tgkill（raw syscall）**，绝不能用 `pthread_kill(tid, …)`。
    //
    //    这是本模块**实测撞出来的第二个大坑**（第一个是 TLS，见 §三），
    //    错误的后果链完整得像个教学案例：
    //
    //      `pthread_kill(pthread_t, sig)` 的第一个参数是 **pthread_t 句柄**
    //      （bionic 里是 unsigned long，指向线程内部结构），**不是内核 tid**。
    //      把 tid（一个小整数）传进去 = 无效句柄。bionic 对无效 pthread_t 的
    //      处理是 `__pthread_internal_find` 里直接 `async_safe_fatal()` → abort()：
    //
    //        #03 pthread_kill+52
    //        #02 __pthread_internal_gettid+12
    //        #01 __pthread_internal_find+200
    //        #00 abort+168      ← bionic 在【我们的 handler 内部】abort 了
    //
    //      （真机 tombstone 实测栈就是这四帧。）于是：二次 SIGABRT →
    //      防重入守卫直接交回系统 → **采集在第①步后就中断，报告永远落不了盘**。
    //      三个现象同源于此：进程死了 ✓、tombstone 有 ✓、报告目录空 ✗。
    //
    //    为什么编译期毫无提示：bionic 的 `pthread_t` 是 `unsigned long`，
    //    `pid_t`（int）能**隐式转换**过去 —— 不报错、不警告，只在运行期炸。
    //    这类"类型系统放过、平台兜底逻辑引爆"的错，只能在真机上撞见。
    //
    //    tgkill(pid, tid, sig) 才是"按内核 tid 定向发信号"的正确接口，
    //    且是 async-signal-safe 的 raw syscall（本仓库 `anr_signal_hook.cpp`
    //    转发 SIGQUIT 用的就是它）。目标线程已退出时返回 -1/ESRCH，跳过即可。
    if (syscall(__NR_tgkill, pid, tid, kThreadDumpSignal) == 0) g_expectThreads++;
  }

  // ══ ③ 有限等待（绝不无限等：D 态线程不会回信号）══
  //
  // 用 `nanosleep` 轮询，**不用条件变量** —— 条件变量要 pthread_mutex，
  // 而"在信号处理器里持锁"正是 §六 明令禁止的。
  // 5ms × 80 ≈ 400ms 预算：对崩溃路径可接受（进程马上就没了）。
  {
    // ⚠️ 等待判据用 g_expectThreads（"我发出去几个请求"）而不是
    //    g_slotCount + 期望值：因为 slotForThisThread() 是幂等的，
    //    重复请求不会重复计数，把两个数相加会把期望值算大 → **每次都等到超时**。
    //    这正是本模块第一版的另一处错（在静态 TLS 时代就存在，一并修掉）。
    const int expected = 1 + g_expectThreads;
    struct timespec req;
    req.tv_sec = 0;
    req.tv_nsec = 5 * 1000 * 1000;
    int spins = kCollectBudgetMs / 5;
    for (int i = 0; i < spins; i++) {
      if (g_slotCount >= expected) break;
      nanosleep(&req, nullptr);
    }
  }

  // ══ ④ maps 索引（必须早于写帧：帧要靠它解析成 module+offset）══
  buildVmaIndex();

  // ══ ⑤ 组装报告 ══
  g_outLen = 0;
  outStr("NCREPORT v1");
  outNL();

  outStr("pid="); outI64(pid); outNL();
  outStr("tid="); outI64(crashTid); outNL();
  outStr("signal="); outI64(signo); outStr(" "); outStr(signalName(signo)); outNL();
  outStr("code="); outI64(info ? info->si_code : 0); outStr(" ");
  outStr(codeName(signo, info ? info->si_code : 0)); outNL();
  outStr("fault_addr="); outHex(info ? (uintptr_t)info->si_addr : 0, 16); outNL();
  outStr("sender_pid="); outI64(info ? info->si_pid : 0); outNL();
  outStr("sender_uid="); outI64(info ? info->si_uid : 0); outNL();
  outStr("thread_name="); outStr(crashThreadName); outNL();
  outStr("abort_message="); outStr(g_abortMsg[0] ? g_abortMsg : "(无)"); outNL();
  outStr("sdk="); outI64(g_sdkInt); outNL();
  outStr("android_release="); outStr(g_release); outNL();
  // ⚠️ 只写 uptime_ms，**不写墙钟时间**：在信号处理器里换算墙钟要么调
  //    gettimeofday（非 AS-safe），要么依赖启动时缓存的偏移。换算责任交给
  //    Java 侧（那里可以放心调 System.currentTimeMillis）。见格式契约文档。
  outStr("uptime_ms="); outI64(uptimeMs()); outNL();
  outStr("version_name="); outStr(g_versionName); outNL();
  outStr("version_code="); outI64(g_versionCode); outNL();
  outStr("process_name="); outStr(g_processName[0] ? g_processName : "?"); outNL();

  // ── 崩溃线程的寄存器 ──
  //
  // ⚠️ 结构体形状是**实测核对过**的，不是印象：
  //    Android aarch64 的 `struct sigcontext` 是
  //      { __u64 fault_address; __u64 regs[31]; __u64 sp; __u64 pc; __u64 pstate;
  //        __u8 __reserved[4096] __attribute__((aligned(16))) }
  //    （见 NDK 的 aarch64-linux-android/asm/sigcontext.h）
  //    ⇒ `regs` 只有 **31** 个（x0..x30），`sp` **不在** regs 里。
  //       第一版按 `regs[32]` 取值是错的（会读到 sp 并且少一个寄存器）。
  outStr("--- REGISTERS ---"); outNL();
#if defined(__aarch64__)
  if (ucontext) {
    const ucontext_t* uc = static_cast<const ucontext_t*>(ucontext);
    static const char* kRegs[] = {
        "x0",  "x1",  "x2",  "x3",  "x4",  "x5",  "x6",  "x7",
        "x8",  "x9",  "x10", "x11", "x12", "x13", "x14", "x15",
        "x16", "x17", "x18", "x19", "x20", "x21", "x22", "x23",
        "x24", "x25", "x26", "x27", "x28", "x29", "x30"};
    for (int i = 0; i < 31; i++) {
      outStr("  "); outStr(kRegs[i]); outStr(" ");
      outHex(uc->uc_mcontext.regs[i], 16); outNL();
    }
    outStr("  sp "); outHex(uc->uc_mcontext.sp, 16); outNL();
    outStr("  pc "); outHex(uc->uc_mcontext.pc, 16); outNL();
    outStr("  pstate "); outHex(uc->uc_mcontext.pstate, 16); outNL();
  } else {
    outStr("  (无 ucontext)"); outNL();
  }
#else
  outStr("  (本实现只在 arm64 上取寄存器 —— 见 CMakeLists 的 ABI 说明)"); outNL();
#endif

  writeOneBacktrace(crashThreadName[0] ? crashThreadName : "#崩溃线程", mySlot);

  // ── 其他线程（遍历整张表；跳过崩溃线程自己，它的栈已在上面写过）──
  //
  // ⚠️ 这里必须遍历**整张表**（kMaxSlots），不能只遍历前 g_slotCount 个 ——
  //    新的槽位分配是"按 tid 取模 + 线性探测"，槽位索引**不再与登记顺序一致**。
  //    只遍历前 N 个会漏掉索引大于 N 的线程（静默少抓线程，不报错）。
  //    空的槽位由 `s->tid == 0` 判掉，代价只是 256 次整数比较。
  int written = 0;
  for (int i = 0; i < kMaxSlots; i++) {
    Slot* s = &g_slots[i];
    // ⚠️ 原子读：s->tid 是其它线程在自己的 handler 里 CAS 写入的
    const int slotTid = __atomic_load_n(&s->tid, __ATOMIC_ACQUIRE);
    if (slotTid == 0) continue;
    if (slotTid == crashTid) continue;
    if (s->count <= 0) continue;
    char nm[64];
    readThreadName(s->tid, nm, sizeof(nm));
    writeOneBacktrace(nm, s);
    written++;
  }
  if (g_slotLost > 0) {
    // 表满时我们放弃了登记（而不是覆盖别人）——必须让使用者知道"少了几个"
    outStr("--- NOTE ---"); outNL();
    outStr("  ⚠️ 有 ");
    outU64((uint64_t)g_slotLost);
    outStr(" 个线程因槽位表满未能登记（线程数 > ");
    outU64((uint64_t)kMaxSlots);
    outStr("）。已抓到的栈不受影响。"); outNL();
  }

  outStr("--- MAPS ---"); outNL();
  outRaw(g_maps, g_mapsLen);
  if (g_mapsLen == 0 || g_maps[g_mapsLen - 1] != '\n') outNL();

  writeFdTable();
  writeProcFile("STATUS", "/proc/self/status");
  // ⚠️ cmdline 用 \0 分隔，原样写出（解析方按 NUL 切）；这样能识别 :remote 这类
  //    多进程场景崩的是哪个进程
  writeProcFile("CMDLINE", "/proc/self/cmdline");

  outStr("--- END ---"); outNL();

  // ══ ⑥ 落盘：先写 .tmp 再 rename ══
  //
  // 为什么不直接写目标文件：进程可能写到一半就没了（下面就要交回系统，
  // debuggerd 会接管并杀掉它）。写 .tmp + rename 保证目标文件**要么不存在、
  // 要么是完整报告**，不会留半截文件被误当成"完整报告"解析。
  // rename 在 AS-safe 白名单里，可放心用。
  writeReportFile(pid);

  // ══ ⑦ 交回系统（tombstone 照旧产生）══
  g_inHandler = 0;
  handOffToSystem(sig, info, ucontext);
}

static void writeReportFile(pid_t pid) {
  if (g_dir[0] == '\0') return;

  char base[768];
  size_t p = 0;
  appendPathStr(base, sizeof(base), &p, g_dir);
  if (p > 0 && base[p - 1] != '/') base[p++] = '/';
  appendPathStr(base, sizeof(base), &p, "report-");
  appendPathNum(base, sizeof(base), &p, (uint32_t)pid);
  base[p++] = '-';
  int64_t up = uptimeMs();
  appendPathNum(base, sizeof(base), &p, (uint32_t)(up < 0 ? 0 : up));

  char tmp[800];
  memcpy(tmp, base, p);
  size_t q = p;
  appendPathStr(tmp, sizeof(tmp), &q, ".tmp");
  tmp[q] = '\0';

  appendPathStr(base, sizeof(base), &p, ".txt");
  base[p] = '\0';

  int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
  if (fd < 0) return;
  size_t written = 0;
  while (written < g_outLen) {
    ssize_t n = write(fd, g_out + written, g_outLen - written);
    if (n < 0) {
      if (errno == EINTR) continue;
      break;
    }
    if (n == 0) break;
    written += (size_t)n;
  }
  // fsync：不 sync 的话数据还在 page cache，而进程马上会被 debuggerd 杀掉，
  //        "报告已落盘"会退化成"什么都没写"（与 CrashJournal 同一条判断）
  fsync(fd);
  close(fd);

  if (written == g_outLen) {
    rename(tmp, base);
  }
  // 写不全就保留 .tmp：解析方会因为缺 `--- END ---` 把它标为"报告不完整"
}

// ═══════════════════════════════════════════════════════════════════════════
// 7. JNI 边界
// ═══════════════════════════════════════════════════════════════════════════

static void copyJString(JNIEnv* env, jstring s, char* dst, size_t cap) {
  if (!s) {
    dst[0] = '\0';
    return;
  }
  const char* c = env->GetStringUTFChars(s, nullptr);
  if (!c) {
    dst[0] = '\0';
    return;
  }
  size_t n = slen(c);
  if (n > cap - 1) n = cap - 1;
  memcpy(dst, c, n);
  dst[n] = '\0';
  env->ReleaseStringUTFChars(s, c);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_interview_nativecrash_NativeCrashHandler_installNative(
    JNIEnv* env, jclass clazz, jstring jDir, jstring jVersionName, jint versionCode,
    jstring jProcessName, jstring jRelease, jint sdkInt) {
  (void)clazz;
  if (g_installed) return JNI_TRUE;

  copyJString(env, jDir, g_dir, sizeof(g_dir));
  copyJString(env, jVersionName, g_versionName, sizeof(g_versionName));
  copyJString(env, jProcessName, g_processName, sizeof(g_processName));
  copyJString(env, jRelease, g_release, sizeof(g_release));
  g_versionCode = versionCode;
  g_sdkInt = sdkInt;
  g_abortMsg[0] = '\0';

  // ── 线程回栈信号 ──
  struct sigaction tsa;
  memset(&tsa, 0, sizeof(tsa));
  tsa.sa_sigaction = threadDumpHandler;
  tsa.sa_flags = SA_SIGINFO | SA_RESTART;
  sigemptyset(&tsa.sa_mask);
  sigaddset(&tsa.sa_mask, kThreadDumpSignal);  // 自己屏蔽自己，避免嵌套
  sigaction(kThreadDumpSignal, &tsa, nullptr);

  // ── 备用信号栈 ──
  //
  // 为什么需要：**栈溢出**触发的 SIGSEGV 发生时，线程自己的栈已经不能用了 ——
  // 不切到备用栈的话，我们的 handler 一压栈就二次 SIGSEGV，报告一个字都落不下来。
  //
  // ⚠️ 边界：`sigaltstack` 是 **per-thread** 的。这里只能给"执行 install 的那个线程"
  //    （正常是主线程）装上。其他线程如果 bionic 已经给它们装了（bionic 在
  //    `pthread_create` 里会给新线程准备 alt stack），我们就**不动它** ——
  //    替换掉平台自己的 alt stack 有可能干扰 debuggerd handler 的栈切换逻辑。
  {
    stack_t cur;
    memset(&cur, 0, sizeof(cur));
    if (sigaltstack(nullptr, &cur) == 0) {
      if (cur.ss_flags & SS_DISABLE) {
        static char altStack[SIGSTKSZ * 4];  // .bss，不在崩溃现场的内存里争抢
        stack_t ss;
        memset(&ss, 0, sizeof(ss));
        ss.ss_sp = altStack;
        ss.ss_size = sizeof(altStack);
        ss.ss_flags = 0;
        if (sigaltstack(&ss, nullptr) != 0) {
          ALOGW("sigaltstack 安装失败：errno=%d（栈溢出崩溃可能抓不到）", errno);
        } else {
          ALOGI("备用信号栈已装（%zu 字节，per-thread）", sizeof(altStack));
        }
      } else {
        ALOGI("已有备用信号栈（%zu 字节），不替换 —— 避免干扰平台 handler 的栈切换",
              (size_t)cur.ss_size);
      }
    }
  }

  // ── 接管崩溃信号，并**保存原 handler**（交回时要用）──
  g_savedCount = 0;
  for (int i = 0; i < kSignalCount; i++) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = nativeCrashHandler;
    // ⚠️ SA_ONSTACK 必须带：栈溢出时只有切到 alt stack 才写得下报告。
    //    若该线程没有 alt stack，内核会退回用当前栈（不会出错，只是那次救不回来）。
    sa.sa_flags = SA_SIGINFO | SA_RESTART | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    // 把其他崩溃信号也加进 sa_mask：一次崩溃处理期间不该被另一个崩溃信号打断
    for (int k = 0; k < kSignalCount; k++) sigaddset(&sa.sa_mask, kSignals[k]);

    struct sigaction old;
    memset(&old, 0, sizeof(old));
    if (sigaction(kSignals[i], &sa, &old) != 0) {
      ALOGW("sigaction(%d) 失败：errno=%d", kSignals[i], errno);
      continue;
    }
    g_saved[g_savedCount].sig = kSignals[i];
    g_saved[g_savedCount].old = old;
    g_savedCount++;

    // 这一步在**安装期**做（不在 handler 里），目的是让"我们有没有顶掉系统链路"
    // 这件事在 logcat 里可查 —— 是自证的一部分。
    ALOGI("接管信号 %d(%s)：原 handler=%p flags=0x%x",
          kSignals[i], signalName(kSignals[i]), (void*)old.sa_sigaction, old.sa_flags);
  }

  g_installed = 1;
  ALOGI("native 崩溃取证已安装：dir=%s ver=%s(%d) 接管 %d 个信号",
        g_dir, g_versionName, g_versionCode, g_savedCount);
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_interview_nativecrash_NativeCrashHandler_uninstallNative(JNIEnv* env, jclass clazz) {
  (void)env;
  (void)clazz;
  if (!g_installed) return;
  // 严格还原我们改过的那几个（用安装时保存的 old 原样写回）
  for (int i = 0; i < g_savedCount; i++) {
    sigaction(g_saved[i].sig, &g_saved[i].old, nullptr);
  }
  g_savedCount = 0;
  g_installed = 0;
  ALOGI("native 崩溃取证已卸载（系统 handler 已还原）");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_interview_nativecrash_NativeCrashHandler_isInstalledNative(JNIEnv* env, jclass clazz) {
  (void)env;
  (void)clazz;
  return g_installed ? JNI_TRUE : JNI_FALSE;
}

/** 设置 abort message：**同时**做两件事，缺一不可（见文件头 §四） */
extern "C" JNIEXPORT void JNICALL
Java_com_interview_nativecrash_NativeCrashHandler_setAbortMessageNative(
    JNIEnv* env, jclass clazz, jstring jMsg) {
  (void)clazz;
  if (!jMsg) return;
  const char* c = env->GetStringUTFChars(jMsg, nullptr);
  if (!c) return;
  size_t n = slen(c);
  if (n > kAbortMsgCap - 1) n = kAbortMsgCap - 1;
  memcpy(g_abortMsg, c, n);
  g_abortMsg[n] = '\0';
  android_set_abort_message(g_abortMsg);  // 给 bionic（tombstone 会带上）
  env->ReleaseStringUTFChars(jMsg, c);
}

static void recurseForever(volatile int depth) {
  volatile char pad[4096];
  pad[0] = (char)depth;
  recurseForever(depth + 1);
  if (pad[0] == 127) ALOGW("unreachable");  // 防止被优化成尾调用
}

/** 主动制造一次 native 崩溃（实验页用）。
 *
 *  为什么用 `abort()` 而不是 `*(int*)0 = 0`：
 *   · `abort()` 走 SIGABRT，`si_code = SI_TKILL` —— **来源可辨**（就是"我们主动的"）；
 *   · 空指针写走 SIGSEGV/SEGV_MAPERR，与真实业务崩溃**无法区分** ——
 *     实验数据混进真实数据里，是比自己写错更难发现的问题。
 *   · 另外 abort message 会进 debuggerd，tombstone 里能看到原因。 */
extern "C" JNIEXPORT void JNICALL
Java_com_interview_nativecrash_NativeCrashHandler_crashNowNative(
    JNIEnv* env, jclass clazz, jstring jMsg) {
  (void)clazz;
  if (jMsg) {
    const char* c = env->GetStringUTFChars(jMsg, nullptr);
    if (c) {
      size_t n = slen(c);
      if (n > kAbortMsgCap - 1) n = kAbortMsgCap - 1;
      memcpy(g_abortMsg, c, n);
      g_abortMsg[n] = '\0';
      android_set_abort_message(g_abortMsg);
      env->ReleaseStringUTFChars(jMsg, c);
    }
  }
  ALOGW("⚠️ 主动触发 native 崩溃（SIGABRT）：%s", g_abortMsg);
  abort();
}

/** 空指针解引用 → SIGSEGV / SEGV_MAPERR（fault_addr=0x0）。
 *  与 crashNowNative 配对，构成"si_code 归因对照"。 */
extern "C" JNIEXPORT void JNICALL
Java_com_interview_nativecrash_NativeCrashHandler_nullDerefNative(JNIEnv* env, jclass clazz) {
  (void)env;
  (void)clazz;
  volatile int* p = nullptr;
  *p = 42;  // 未映射地址 → SEGV_MAPERR
}

/** 无限递归 → 栈溢出 SIGSEGV。
 *  ⚠️ 它的 fault_addr 会贴着**栈底**，而不是 0 —— 这正是"必须看 fault_addr，
 *     不能只看 si_code"的实例（两者 si_code 都是 SEGV_MAPERR）。
 *     所以实验页把 si_code 与 fault_addr 一起打出来。 */
extern "C" JNIEXPORT void JNICALL
Java_com_interview_nativecrash_NativeCrashHandler_stackOverflowNative(JNIEnv* env, jclass clazz) {
  (void)env;
  (void)clazz;
  recurseForever(0);
}

/** 诊断读数：`installed:crashCount:savedSignals`
 *  拆成纯函数由 Java 侧解析（与 `AnrSigquitHook.Stats.parse` 同一约定）。 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_interview_nativecrash_NativeCrashHandler_statsNative(JNIEnv* env, jclass clazz) {
  (void)clazz;
  char buf[128];
  size_t p = 0;
  buf[p++] = g_installed ? '1' : '0';
  buf[p++] = ':';
  auto putNum = [&](int v) {
    char num[16];
    int i = 0;
    uint32_t u = (uint32_t)(v < 0 ? 0 : v);
    if (u == 0) num[i++] = '0';
    while (u > 0 && i < 16) {
      num[i++] = (char)('0' + (u % 10));
      u /= 10;
    }
    while (i > 0 && p < sizeof(buf) - 1) buf[p++] = num[--i];
  };
  putNum((int)g_crashCount);
  buf[p++] = ':';
  putNum(g_savedCount);
  buf[p] = '\0';
  return env->NewStringUTF(buf);
}
