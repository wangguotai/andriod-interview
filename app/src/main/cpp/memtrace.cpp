// Time: 2026/10/8
// Author: wgt
// Description: Native 内存**分配归因** —— 回答「native 堆涨的那几 MB 是谁要走的」
//
// ═══════════════════════════════════════════════════════════════════════════
// 一、为什么必须 hook malloc，而不能靠现成的 API（这是本条链路的立足点）
// ═══════════════════════════════════════════════════════════════════════════
//
// 现有手段能给到的**只有总量**，给不到**来源**：
//
//   Debug.getNativeHeapAllocatedSize()   → 一个数（malloc 净账）
//   MemoryInfo.nativePss                 → 一个数（内核视角）
//   malloc_info() / mallinfo2()          → 尺寸档分布（**按大小**，不按代码）
//
// 「native 涨了 30 MB」这句话在排查时几乎没有价值，因为：
//   · Bitmap 的解码缓冲、Glide 的三方 .so、我们自己的 Rust pipeline、
//     ART 的 JIT code cache、Thread stack……**全都在 native 堆里**，
//     总量涨了根本分不清是谁；
//   · 而它们的处置方式完全不同（该采样降采样 / 该释放 / 该改算法）。
//
// ⇒ 唯一能把「字节」和「代码」连起来的办法，是在**分配发生的那一刻**记下调用栈。
//   这就是 PLT/GOT Hook：malloc 是外部符号，任何 .so 调它都要经过自己的 GOT 表项，
//   改掉那个表项就能在不改目标代码的情况下接管（本仓库的 `thread_hook.cpp`
//   用同一原理 hook pthread_create —— 那一个是抓"线程是谁建的"，这一个是抓
//   "内存是谁要的"）。
//
// ═══════════════════════════════════════════════════════════════════════════
// 二、为什么用 bytehook 而不是自己写 GOT Hook（与 thread_hook.cpp 的对比）
// ═══════════════════════════════════════════════════════════════════════════
//
// 本仓库已经有一份手写的 GOT Hook（thread_hook.cpp，改 libart 的 GOT 表项）。
// 它**不适合**照搬到这里，原因有三（都是硬约束，不是偏好）：
//
// | | 手写 GOT Hook（thread_hook） | bytehook |
// |---|---|---|
// | 目标 | 1 个符号 × 1 个模块 | **4 个符号 × 所有模块**（malloc/free/calloc/realloc） |
// | 新加载的 .so | 拦不到（GOT 是 dlopen 时填的） | `bytehook_add_dlopen_callback` 可覆盖 |
// | 调用方是 libc 自己 | 不涉及 | 需要 ignore 列表 + **递归防护** |
// | 性能敏感度 | 低（线程创建频率） | **极高**（malloc 每秒上万次） |
//
// 第三条是决定性的：malloc hook 是**热路径上的热路径**，手写实现要处理
// 递归（我们的 hook 自己也会 malloc）、并发（多线程同时 malloc）、
// 以及新旧模块共存。bytehook 把这些都做完了（且是字节跳动在生产上验证过的实现）。
//
// ⚠️ 版本选择是**实测卡出来的**，不是随手挑的：
//    bytehook 1.1.2 的 AAR 里 `minCompileSdk=37`（AAR metadata），
//    而本仓库 `compileSdk = 34` + AGP 8.3 —— 直接依赖会让 **所有** 构建失败，
//    报的是「依赖要求 compileSdk ≥ 37」这种与内存毫无关系的错。
//    实测 1.0.10 的 `minCompileSdk=1`，可正常使用，且已带 prefab + 四个 ABI。
//
// ═══════════════════════════════════════════════════════════════════════════
// 三、设计取舍：这是一个**有损采样器**，不是一个精确记账器（必须说清楚）
// ═══════════════════════════════════════════════════════════════════════════
//
// 任何在 malloc 里做事的实现都要面对同一个矛盾：
//
//     **要准确 → 每次分配都要抓栈 + 记账 → 单次成本 >100µs → 应用直接卡死**
//     **要便宜 → 少采 → 数字不全 → 可能漏掉泄漏点**
//
// 本实现的选择（三条，每条都有代价说明）：
//
// **① 按大小分档 + 抽样。** 只跟踪 `>= 8192 B` 的分配（**全部**），
//    以及 `[64, 8192)` 的分配**随机 1/256**。理由：native 泄漏的体量
//    几乎总是由大块分配构成的（缓冲、位图、字符串池），而小分配数量巨大、
//    单个体量小、并且大多数是短命的。
//    ⚠️ 代价：**高频小对象泄漏会被漏掉**。要抓那类问题得改用
//    `malloc_debug` / heapprofd（Perfetto），不是本模块的定位。
//
// **② 栈只抓 4 帧。** 更深的栈需要更多 unwind table 遍历，成本线性上升，
//    而归因到「哪个函数要的内存」4 帧足够（第 1 帧往往是 `__rust_alloc`
//    这类包装，真正有用的信息在第 2~4 帧 —— 这个现象在 Rust cdylib 上尤其明显，
//    见下文 §四）。
//    ⚠️ 代价：无法区分「同一个函数的不同调用路径」，只能归因到函数级。
//
// **③ 拿不到锁就放弃。** 记账表用 spinlock 保护，但**只自旋有限次**，
//    拿不到就跳过这次记账（计入 `skippedContended`）。
//    ⚠️ 代价：**并发高峰期的数字会偏低**。这一条刻意的选择，理由是
//    「统计数字偏低」的后果是"少看见一点"，而「在 malloc 里等锁」的后果是
//    **全局吞吐下降**（所有线程都在 malloc 上排队）——这两者的严重性差一个数量级。
//    native 归因是**诊断工具**，不能让诊断本身成为事故。
//
// ═══════════════════════════════════════════════════════════════════════════
// 四、一个只有真机/真编译产物才会发现的现象（本模块的核心证据）
// ═══════════════════════════════════════════════════════════════════════════
//
// Rust 的 `Vec::with_capacity(n)` / `vec![]` 走到 malloc 的链路是：
//
//     our_code()  →  alloc::alloc::alloc()  →  __rust_alloc()  →  malloc()
//                    ^^^^^^^^^^^^^^^^^^^^^     ^^^^^^^^^^^^^^
//                    第 3 帧                   **第 1 帧（malloc 的调用者）**
//
// 也就是说，**只记录 malloc 的直接调用者（1 帧）会把所有 Rust 分配
// 归并成同一个 `__rust_alloc` 站点**，报告看起来像"整个 App 的 native 内存
// 都是从 __rust_alloc 来的"——等于没归因。
// 这个坑在 C++ 里不明显（`new` 之后往往直接就是业务栈帧），在 Rust cdylib 上
// 几乎是必然踩到。本实现固定抓 4 帧，就是为了跨过 `__rust_alloc` 这一层包装，
// 详见 `docs/INTERVIEW-线上内存监控方案.md` 的实测记录。
//
// ═══════════════════════════════════════════════════════════════════════════
// 五、符号化：设备上只给"模块+偏移"，名字要离线还原（能力边界）
// ═══════════════════════════════════════════════════════════════════════════
//
// 设备上**没有符号表**（APK 里的 .so 是 strip 过的，且 NDK 的 llvm-symbolizer
// 不能跑在 Android 上）。所以本模块的产物是：
//
//     libimagepipeline_android.so+0x2a5c
//
// 这个信息是**可离线还原的、且不依赖任何服务**：拿构建目录里带符号的 .so，
// 用 NDK 的 llvm-symbolizer 一跑就出函数名 + 行号。
// 配套脚本见 `tools/native-mem-symbolize.sh`。
//
// ⚠️ 不要试图在设备上做符号化（读 .symtab 自己解析）：收益是把"一次脚本"
//    变成"一堆不可维护的 ELF 解析代码"，且 .so 是 strip 过的，**根本没有 .symtab**。
//    这条结论与 ANR 的 native trace 处理原则一致（那个也只上传不解析）。
//
// ═══════════════════════════════════════════════════════════════════════════
// 六、符号命名规则（与 anrsignal 同一约束）
// ═══════════════════════════════════════════════════════════════════════════
//
// JNI 导出符号里的包名不能有非 ASCII 字符（C++ 标识符限制），
// 所以声明层落在 ASCII 包 `com.interview.memtrace.MemTraceNative`，
// 业务层仍在 `com.interview.内存`，中间由一个薄类隔开。

#include <jni.h>

#include <android/log.h>
#include <bytehook.h>
#include <dlfcn.h>
#include <link.h>
#include <malloc.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include <unwind.h>

#define LOG_TAG "MemTrace"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// ─────────────────────────────────────────────────────────────────────────────
// 配置（改这些数之前先想清楚代价，见文件头 §三）
// ─────────────────────────────────────────────────────────────────────────────

/** 无条件跟踪的分配下限（字节） */
constexpr size_t kAlwaysTrackSize = 8192;
/** 小于该值完全不看（噪音，且短命） */
constexpr size_t kIgnoreBelowSize = 64;
/** [kIgnoreBelowSize, kAlwaysTrackSize) 区间的抽样掩码：& 掩码 == 0 才跟踪（1/256） */
constexpr uint64_t kSmallSampleMask = 0xFF;

/** 每个站点抓的栈帧数。**必须 ≥ 3**，理由见文件头 §四（Rust 的 __rust_alloc 包装） */
constexpr int kMaxFrames = 4;

/** 地址表容量。2^16 项 × 24 B ≈ 1.5 MB（mmap 出来，不用 malloc —— 见 initTable） */
constexpr uint32_t kAddrSlots = 1u << 16;
/** 站点表容量。满了之后新增站点会并入 "OTHER" 桶（而不是丢弃，见 accountSite） */
constexpr uint32_t kSiteSlots = 512;
/** 站点的帧哈希桶（用于把"同一处分配"合并成一个站点） */
constexpr uint32_t kSiteHashMask = kSiteSlots - 1;

/** 拿不到锁时最多自旋次数（见文件头 §三③） */
constexpr int kLockSpins = 200;

constexpr uint32_t kMaxSitesReported = 40;

// ─────────────────────────────────────────────────────────────────────────────
// 数据结构（全部放在 mmap 出来的固定区域里 —— 因为 hook 一旦装上，
// 我们自己的任何 malloc 都会被自己 hook 到，会造成递归/污染）
// ─────────────────────────────────────────────────────────────────────────────

struct AddrEntry {
  uintptr_t addr;   // 0 = 空槽
  uint32_t size;
  uint32_t site;    // 站点索引
};

struct Site {
  uint64_t hash;
  uint64_t bytes;      // 当前存活字节（**订阅值**，free 会减）
  uint64_t count;      // 当前存活笔数
  uint64_t peakBytes;  // 历史峰值（订阅值回落但峰值不降 —— 用来发现"曾经很大"）
  uint64_t totalBytes; // 累计分配字节（只增）
  // ⚠️ 必须是 uintptr_t（完整机器字），**不能用 uint32_t**：
  //    实测踩过 —— 存成 32 位后 arm64 的地址高 32 位丢失，
  //    resolveFrame 永远匹配不到任何模块，报告里只剩 `0x2ef58268` 这类裸地址，
  //    "归因到代码"直接失效（而且不报错，只是看起来像"符号解析没开"）。
  //    这个 bug 只有真机/模拟器跑一次才能发现，编译期完全看不出来。
  uintptr_t frames[kMaxFrames];
  uint32_t frameCount;
};

AddrEntry* g_addrTable = nullptr;   // 开放寻址哈希表
Site* g_sites = nullptr;

uint64_t g_siteCount = 0;           // 已用站点数
uint32_t g_otherSite = 0;           // 站点表满时的兜底桶

pthread_spinlock_t g_lock;
bool g_lockReady = false;

// ── 统计（用原子，保证在"没拿到锁"的路径上也能安全累加）──
volatile uint64_t g_seenAllocs = 0;      // hook 到的 malloc/calloc/realloc 次数
volatile uint64_t g_trackedAllocs = 0;   // 实际记账的次数
volatile uint64_t g_trackedBytes = 0;    // 实际记账的字节（累计）
volatile uint64_t g_skippedContended = 0;// 因拿不到锁而放弃
volatile uint64_t g_addrOverflow = 0;    // 地址表满，该笔分配没记下
volatile uint64_t g_unknownFree = 0;     // free 了一个表里没有的地址（正常：常见于 hook 前的分配）
volatile uint64_t g_freeSkipped = 0;     // free 时拿不到锁（会让字节数偏高 —— 见报告里的说明）

volatile bool g_installed = false;
volatile bool g_paused = false;          // 出报告时暂停记账（避免自己的 IO 污染数字）
uint32_t g_smallSampleCounter = 0;

// ── 重入防护：**每线程**，因为 unwind 自己也可能 malloc ──
__thread int g_inHook = 0;

// ── 原始函数：**不需要自己保存** ──
// bytehook 的自动模式下，`BYTEHOOK_CALL_PREV(my_malloc, ...)` 会通过它自己的
// trampoline 找到前驱函数地址（内部用了 `bytehook_get_prev_func`）。
// 手写 GOT hook（如本仓库 thread_hook.cpp）必须自己存 `g_original_pthread_create`，
// 这里若也存一份，反而在"同一个符号被多个库各自 hook"时可能拿到错的前驱。

// ─────────────────────────────────────────────────────────────────────────────
// 表初始化：**绝不能 malloc**（此时 hook 可能已经装上了）
// ─────────────────────────────────────────────────────────────────────────────

bool initTables() {
  if (g_addrTable != nullptr) return true;
  const size_t addrBytes = sizeof(AddrEntry) * kAddrSlots;
  const size_t siteBytes = sizeof(Site) * (kSiteSlots + 1); // +1 给 OTHER 桶

  void* a = mmap(nullptr, addrBytes, PROT_READ | PROT_WRITE,
                 MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  void* s = mmap(nullptr, siteBytes, PROT_READ | PROT_WRITE,
                 MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (a == MAP_FAILED || s == MAP_FAILED) {
    if (a != MAP_FAILED) munmap(a, addrBytes);
    if (s != MAP_FAILED) munmap(s, siteBytes);
    ALOGW("initTables 失败：mmap 不可用（已降级为不跟踪）");
    return false;
  }
  g_addrTable = static_cast<AddrEntry*>(a);
  g_sites = static_cast<Site*>(s);
  memset(g_addrTable, 0, addrBytes);
  memset(g_sites, 0, siteBytes);
  g_otherSite = kSiteSlots;
  if (!g_lockReady) {
    pthread_spin_init(&g_lock, PTHREAD_PROCESS_PRIVATE);
    g_lockReady = true;
  }
  ALOGI("记账表已就绪：addr=%u 槽(%zu KB) site=%u 槽(%zu KB)",
        kAddrSlots, addrBytes / 1024, kSiteSlots, siteBytes / 1024);
  return true;
}

inline uint32_t hashAddr(uintptr_t a) {
  // 指针低 3 位恒为 0（对齐），先右移再乘黄金比例再取高位，减少聚簇
  return static_cast<uint32_t>(((a >> 4) * 11400714819323198485ull) >> 40) & (kAddrSlots - 1);
}

// ─────────────────────────────────────────────────────────────────────────────
// 栈捕获
// ─────────────────────────────────────────────────────────────────────────────

struct UnwindState {
  uintptr_t frames[kMaxFrames + 6]; // 多抓几帧，再把 hook 自己的帧裁掉
  int count;
};

_Unwind_Reason_Code unwindCb(struct _Unwind_Context* ctx, void* arg) {
  UnwindState* st = static_cast<UnwindState*>(arg);
  if (st->count >= kMaxFrames + 6) return _URC_END_OF_STACK;
  uintptr_t ip = _Unwind_GetIP(ctx);
  if (ip != 0) st->frames[st->count++] = ip;
  return _URC_NO_REASON;
}

/**
 * 捕获调用栈，然后**裁掉我们自己的帧**。
 *
 * ⚠️ 这里裁掉的帧数写死为 2（`recordAlloc` + `captureStack`），
 *    它们来自本文件的同一个编译单元，栈形状是稳定的 —— 这是刻意的：
 *    动态判断"哪一帧属于本模块"需要 dladdr 或 maps 查询，那在热路径上太贵。
 *    （bytehook 自动模式下的 BYTEHOOK_STACK_SCOPE() 负责把它的 trampoline 帧
 *     也一并抹掉，所以这里只需管我们的两帧。）
 */
int captureStack(uintptr_t* out, int maxOut) {
  UnwindState st{};
  st.count = 0;
  _Unwind_Backtrace(unwindCb, &st);
  int skip = 2;
  int n = 0;
  for (int i = skip; i < st.count && n < maxOut; i++) {
    out[n++] = st.frames[i];
  }
  return n;
}

// ─────────────────────────────────────────────────────────────────────────────
// 站点记账
// ─────────────────────────────────────────────────────────────────────────────

/** 站点哈希：4 帧 IP 的混合。用哈希而不是逐帧比较 —— 热路径上省一次比较循环。 */
inline uint64_t hashFrames(const uintptr_t* f, int n) {
  uint64_t h = 1469598103934665603ull;
  for (int i = 0; i < n; i++) {
    h ^= static_cast<uint64_t>(f[i]);
    h *= 1099511628211ull;
  }
  return h;
}

/**
 * 找到（或新建）一个站点。**调用方必须已持锁。**
 *
 * 站点表满时**不丢弃**，而是并入 OTHER 桶：丢弃会让"总账对不上"
 * （地址表里有记录、站点表里没归属），让报告出现无法解释的缺口；
 * 并入 OTHER 至少能说明"还有 N MB 来自未展开的站点"。
 */
uint32_t accountSite(const uintptr_t* frames, int n) {
  const uint64_t h = hashFrames(frames, n);
  uint32_t idx = static_cast<uint32_t>(h & kSiteHashMask);
  for (uint32_t probe = 0; probe < kSiteSlots; probe++) {
    Site& s = g_sites[idx];
    if (s.hash == 0) {
      // 空槽 → 新建站点（栈捕获成本**只在这里**付一次，之后同址分配直接命中）
      s.hash = h == 0 ? 1 : h;
      s.frameCount = static_cast<uint32_t>(n);
      for (int i = 0; i < n; i++) {
        s.frames[i] = frames[i];
      }
      g_siteCount++;
      return idx;
    }
    if (s.hash == h) return idx;
    idx = (idx + 1) & kSiteHashMask;
  }
  // 表满：并入 OTHER
  Site& other = g_sites[g_otherSite];
  other.hash = 0xFFFFFFFFFFFFFFFFull;
  other.frameCount = 0;
  return g_otherSite;
}

void recordAllocLocked(void* p, size_t size) {
  if (p == nullptr) return;
  uintptr_t frames[kMaxFrames];
  int n = captureStack(frames, kMaxFrames);
  const uint32_t site = accountSite(frames, n);

  // 地址表插入
  uint32_t idx = hashAddr(reinterpret_cast<uintptr_t>(p));
  for (uint32_t probe = 0; probe < kAddrSlots; probe++) {
    AddrEntry& e = g_addrTable[idx];
    if (e.addr == 0) {
      e.addr = reinterpret_cast<uintptr_t>(p);
      e.size = static_cast<uint32_t>(size);
      e.site = site;
      Site& s = g_sites[site];
      s.bytes += size;
      s.count++;
      s.totalBytes += size;
      if (s.bytes > s.peakBytes) s.peakBytes = s.bytes;
      g_trackedAllocs++;
      g_trackedBytes += size;
      return;
    }
    idx = (idx + 1) & (kAddrSlots - 1);
  }
  // 地址表满：这笔分配没有归属 → 它的 free 也会变成 unknownFree。
  // 这是"数字偏低"的来源之一，必须计数暴露出来而不是静默。
  g_addrOverflow++;
}

void forgetAllocLocked(void* p) {
  if (p == nullptr) return;
  uint32_t idx = hashAddr(reinterpret_cast<uintptr_t>(p));
  for (uint32_t probe = 0; probe < kAddrSlots; probe++) {
    AddrEntry& e = g_addrTable[idx];
    if (e.addr == 0) { // 走到空槽 = 没找到
      g_unknownFree++;
      return;
    }
    if (e.addr == reinterpret_cast<uintptr_t>(p)) {
      Site& s = g_sites[e.site];
      const uint64_t sz = e.size;
      s.bytes = (s.bytes >= sz) ? (s.bytes - sz) : 0;
      s.count = (s.count > 0) ? (s.count - 1) : 0;
      // 清槽。⚠️ 开放寻址里"清空"会切断探测链 —— 这里用一个墓碑技巧的简化版：
      // 直接把该槽置为"已释放"标记（addr = 1），查找时跳过。
      // 代价是表会被墓碑占满（长时间运行后插入变慢），但**不会出错**；
      // 对本模块的使用形态（诊断时开、看几分钟）完全够用，且避免了 rehash。
      e.addr = 1;
      e.size = 0;
      e.site = 0;
      return;
    }
    idx = (idx + 1) & (kAddrSlots - 1);
  }
  g_unknownFree++;
}

// ─────────────────────────────────────────────────────────────────────────────
// Hook 入口
// ─────────────────────────────────────────────────────────────────────────────

inline bool shouldTrack(size_t size) {
  if (size >= kAlwaysTrackSize) return true;
  if (size < kIgnoreBelowSize) return false;
  // 小分配抽样。用无锁自增计数器 —— 抽样精度不重要，成本才重要。
  return ((__atomic_add_fetch(&g_smallSampleCounter, 1, __ATOMIC_RELAXED) &
           kSmallSampleMask) == 0);
}

/** 尝试拿锁：有限自旋，拿不到就返回 false（见文件头 §三③）。 */
inline bool tryLock() {
  for (int i = 0; i < kLockSpins; i++) {
    if (pthread_spin_trylock(&g_lock) == 0) return true;
  }
  return false;
}

void* my_malloc(size_t size) {
  BYTEHOOK_STACK_SCOPE();
  void* p = BYTEHOOK_CALL_PREV(my_malloc, size);
  if (g_inHook || g_paused || !g_installed || p == nullptr) return p;
  __atomic_add_fetch(&g_seenAllocs, 1, __ATOMIC_RELAXED);
  if (!shouldTrack(size)) return p;
  g_inHook = 1;
  if (tryLock()) {
    recordAllocLocked(p, size);
    pthread_spin_unlock(&g_lock);
  } else {
    __atomic_add_fetch(&g_skippedContended, 1, __ATOMIC_RELAXED);
  }
  g_inHook = 0;
  return p;
}

void my_free(void* p) {
  BYTEHOOK_STACK_SCOPE();
  if (!g_inHook && !g_paused && g_installed && p != nullptr) {
    g_inHook = 1;
    if (tryLock()) {
      forgetAllocLocked(p);
      pthread_spin_unlock(&g_lock);
    } else {
      __atomic_add_fetch(&g_freeSkipped, 1, __ATOMIC_RELAXED);
    }
    g_inHook = 0;
  }
  BYTEHOOK_CALL_PREV(my_free, p);
}

void* my_calloc(size_t n, size_t size) {
  BYTEHOOK_STACK_SCOPE();
  const size_t total = n * size;
  void* p = BYTEHOOK_CALL_PREV(my_calloc, n, size);
  if (g_inHook || g_paused || !g_installed || p == nullptr) return p;
  __atomic_add_fetch(&g_seenAllocs, 1, __ATOMIC_RELAXED);
  if (!shouldTrack(total)) return p;
  g_inHook = 1;
  if (tryLock()) {
    recordAllocLocked(p, total);
    pthread_spin_unlock(&g_lock);
  } else {
    __atomic_add_fetch(&g_skippedContended, 1, __ATOMIC_RELAXED);
  }
  g_inHook = 0;
  return p;
}

void* my_realloc(void* old, size_t size) {
  BYTEHOOK_STACK_SCOPE();
  void* p = BYTEHOOK_CALL_PREV(my_realloc, old, size);
  if (g_inHook || g_paused || !g_installed) return p;
  __atomic_add_fetch(&g_seenAllocs, 1, __ATOMIC_RELAXED);
  g_inHook = 1;
  const bool track = shouldTrack(size);
  if (tryLock()) {
    // 语义顺序：先销掉旧账，再记新账。反过来会让新旧两块同时"存活"，
    // 在紧循环 realloc（如 std::vector 扩容）时会看到假峰值。
    if (old != nullptr) forgetAllocLocked(old);
    if (p != nullptr && track) recordAllocLocked(p, size);
    pthread_spin_unlock(&g_lock);
  } else {
    __atomic_add_fetch(&g_skippedContended, 1, __ATOMIC_RELAXED);
  }
  g_inHook = 0;
  return p;
}

void onHooked(bytehook_stub_t, int status, const char* caller, const char* sym,
              void*, void*, void*) {
  ALOGI("hook 完成：sym=%s caller=%s status=%d", sym, caller ? caller : "(all)", status);
}

} // namespace

// ═════════════════════════════════════════════════════════════════════════════
// 模块解析（只在出报告时做，不在热路径上）
// ═════════════════════════════════════════════════════════════════════════════

namespace {

constexpr int kMaxModules = 512;

struct Module {
  uintptr_t start;
  uintptr_t end;
  uintptr_t base;          // 第一个 PT_LOAD 的起始地址（算偏移要用它，不是 start）
  char name[128];
};

Module* g_modules = nullptr;
int g_moduleCount = 0;

int phdrCb(struct dl_phdr_info* info, size_t, void*) {
  if (g_moduleCount >= kMaxModules) return 1;

  const uintptr_t loadBias = static_cast<uintptr_t>(info->dlpi_addr);
  bool seenFirstLoad = false;
  Module m{};
  m.end = loadBias;

  for (int i = 0; i < info->dlpi_phnum; i++) {
    const ElfW(Phdr)& ph = info->dlpi_phdr[i];
    if (ph.p_type != PT_LOAD) continue;
    const uintptr_t lo = loadBias + ph.p_vaddr;
    const uintptr_t hi = lo + ph.p_memsz;
    if (!seenFirstLoad) {
      // ⚠️⚠️ 两个坑都在这一处，且都**不会报错**，只会让报告里出现
      //     `libart.so+0x754873f504` 这种"偏移跟地址一样长"的鬼东西：
      //
      //   ① 不能写 `info->dlpi_phdr[0]` 取第一个 LOAD 段 ——
      //      **phdr[0] 是 PT_PHDR 不是 PT_LOAD**（arm64/Android 实测），
      //      于是 `i == 0` 这个条件在 `continue` 之后**永远不成立**，
      //      `m.base` 保持默认 0（mmap 出来的内存是清零的），
      //      偏移算成 `ip - 0` = **绝对地址**。
      //   ② 偏移基准是 `loadBias + 第一个 PT_LOAD 的 p_vaddr`，
      //      不是单独的 `loadBias`：vaddr 可能是 0（常见）却**不保证**，
      //      用错会让所有符号整体偏一个常量。
      m.start = lo;
      m.base = lo;
      seenFirstLoad = true;
    }
    if (hi > m.end) m.end = hi;
  }
  if (!seenFirstLoad) return 0;   // 没有 LOAD 段（异常）→ 不登记

  const char* n = info->dlpi_name;
  snprintf(m.name, sizeof(m.name), "%s", (n && *n) ? n : "[main]");
  g_modules[g_moduleCount] = m;
  g_moduleCount++;
  return 0;
}

void refreshModules() {
  if (g_modules == nullptr) {
    // 出报告时可以有 malloc（g_paused 已开），这里直接用 malloc 也行，
    // 但为了与"表全部走 mmap"的纪律一致，继续用 mmap。
    g_modules = static_cast<Module*>(
        mmap(nullptr, sizeof(Module) * kMaxModules, PROT_READ | PROT_WRITE,
             MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    if (g_modules == MAP_FAILED) { g_modules = nullptr; return; }
  }
  g_moduleCount = 0;
  dl_iterate_phdr(phdrCb, nullptr);
}

/** 把一个地址解析成 `模块名+0x偏移`；解析不到就给裸地址。 */
void resolveFrame(uintptr_t ip, char* out, size_t outSize) {
  for (int i = 0; i < g_moduleCount; i++) {
    const Module& m = g_modules[i];
    if (ip >= m.start && ip < m.end) {
      const char* slash = strrchr(m.name, '/');
      const char* shortName = slash ? slash + 1 : m.name;
      snprintf(out, outSize, "%s+0x%zx", shortName, static_cast<size_t>(ip - m.base));
      return;
    }
  }
  snprintf(out, outSize, "0x%zx", static_cast<size_t>(ip));
}

} // namespace

// ═════════════════════════════════════════════════════════════════════════════
// mallinfo2 / malloc_info：**分配器视角**的补充证据（总量 + 尺寸档）
// ═════════════════════════════════════════════════════════════════════════════
//
// ⚠️ 实测（模拟器 API 36，allocator = scudo，见 INTERVIEW 文档 M-x 记录）：
//    · `malloc_info(0, fp)` 只输出 **primary（尺寸档）分配器**的存活统计，
//      所以**大块分配（走 secondary / mmap）不出现在里面**。
//      实测：8 笔 100000 B 的存活分配 → 输出仅 157 字节（只有头尾标签）。
//      这不是 bug，是 scudo 的实现边界 —— 所以本模块**不能**依赖它算总量，
//      只能拿它看**小对象碎片**。
//    · `mallinfo2().uordblks` 反而是准的（实测 8846640 B ≈ 预期 8.6 MB）。
//
// 这两条决定了：尺寸档表在报告里标注"只含小对象"，总量一律用 mallinfo2。
namespace {

/**
 * 从 malloc_info 的 XML 里解析 `<alloc size="N" count="M"/>` 尺寸档，按占用字节降序。
 *
 * 手写解析而不是引 XML 库：格式极简（只有 `<malloc>` / `<alloc>` / `<total>` 三类标签），
 * 引入解析库会给一个诊断用的 .so 增加真实的体积与依赖风险。
 *
 * ⚠️ 排序按 `size × count`（**占用字节**）而不是 count：数量多但都很小的档位
 *    与数量少但都很大的档位，后者才是内存账上的主角。
 */
struct SizeClass { long size; long count; };

int parseSizeClasses(const char* xml, SizeClass* out, int maxOut) {
  int n = 0;
  const char* p = xml;
  while ((p = strstr(p, "<alloc ")) != nullptr && n < maxOut) {
    const char* s = strstr(p, "size=\"");
    const char* c = strstr(p, "count=\"");
    if (!s || !c) break;
    out[n].size = strtol(s + 6, nullptr, 10);
    out[n].count = strtol(c + 7, nullptr, 10);
    n++;
    p = c;
  }
  for (int i = 1; i < n; i++) {
    SizeClass k = out[i];
    int j = i - 1;
    while (j >= 0 && out[j].size * out[j].count < k.size * k.count) {
      out[j + 1] = out[j];
      j--;
    }
    out[j + 1] = k;
  }
  return n;
}

/** 用 fmemopen 把 malloc_info 收进内存缓冲（避免临时文件 IO）。 */
size_t dumpMallocInfo(char* out, size_t cap) {
  FILE* f = fmemopen(out, cap, "w");
  if (f == nullptr) return 0;
  malloc_info(0, f);
  fflush(f);
  const long n = ftell(f);
  fclose(f);
  return n > 0 ? static_cast<size_t>(n) : 0;
}

} // namespace

// ═════════════════════════════════════════════════════════════════════════════
// JNI 导出（类：com.interview.memtrace.MemTraceNative）
// ═════════════════════════════════════════════════════════════════════════════

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_interview_memtrace_MemTraceNative_isInstalledNative(JNIEnv*, jclass) {
  return g_installed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_interview_memtrace_MemTraceNative_installNative(JNIEnv*, jclass) {
  if (g_installed) return JNI_TRUE;
  if (!initTables()) return JNI_FALSE;

  const int rc = bytehook_init(BYTEHOOK_MODE_AUTOMATIC, false);
  if (rc != BYTEHOOK_STATUS_CODE_OK) {
    ALOGW("bytehook_init 失败：status=%d", rc);
    return JNI_FALSE;
  }

  g_installed = true; // 装之前先置位，装的过程中漏采一小段可接受（比漏报好）

  const char* syms[4] = {"malloc", "free", "calloc", "realloc"};
  void* news[4] = {(void*)my_malloc, (void*)my_free, (void*)my_calloc, (void*)my_realloc};
  int okCount = 0;
  for (int i = 0; i < 4; i++) {
    bytehook_stub_t stub = bytehook_hook_all(nullptr, syms[i], news[i], onHooked, nullptr);
    if (stub != nullptr) okCount++;
    else ALOGW("hook %s 失败", syms[i]);
  }
  ALOGI("memtrace 安装完成：%d/4 个符号已 hook", okCount);
  return okCount > 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_interview_memtrace_MemTraceNative_uninstallNative(JNIEnv*, jclass) {
  // ⚠️ bytehook 支持按 stub 反安装，但本模块刻意**不提供运行时卸载**：
  //    hook 期间的地址表里有大量存活记录，卸载后这些内存的 free 不再被我们看见，
  //    界面上的"存活字节"会永远停在那里 —— 那比"不卸载"更容易被误读成泄漏。
  //    需要停止观察时请用 pause()/reset()，而不是卸载。
  ALOGW("uninstallNative 被调用：本模块不提供运行时卸载（见代码注释）");
  return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_interview_memtrace_MemTraceNative_pauseNative(JNIEnv*, jclass, jboolean paused) {
  g_paused = (paused == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_interview_memtrace_MemTraceNative_resetNative(JNIEnv*, jclass) {
  if (!g_lockReady) return;
  if (tryLock()) {
    if (g_addrTable) memset(g_addrTable, 0, sizeof(AddrEntry) * kAddrSlots);
    if (g_sites) memset(g_sites, 0, sizeof(Site) * (kSiteSlots + 1));
    g_siteCount = 0;
    pthread_spin_unlock(&g_lock);
  }
  __atomic_store_n(&g_seenAllocs, 0ull, __ATOMIC_RELAXED);
  __atomic_store_n(&g_trackedAllocs, 0ull, __ATOMIC_RELAXED);
  __atomic_store_n(&g_trackedBytes, 0ull, __ATOMIC_RELAXED);
  __atomic_store_n(&g_skippedContended, 0ull, __ATOMIC_RELAXED);
  __atomic_store_n(&g_addrOverflow, 0ull, __ATOMIC_RELAXED);
  __atomic_store_n(&g_unknownFree, 0ull, __ATOMIC_RELAXED);
  __atomic_store_n(&g_freeSkipped, 0ull, __ATOMIC_RELAXED);
}

/** 归因报告文本。格式稳定（便于 adb 抓取 + 离线符号化），不是 JSON 以免过度设计。 */
JNIEXPORT jstring JNICALL
Java_com_interview_memtrace_MemTraceNative_reportNative(JNIEnv* env, jclass, jint maxSites) {
  g_paused = true; // 报告期间的临时分配不进账（见文件头 §三的"污染"问题）
  refreshModules();

  const uint32_t limit = (maxSites > 0)
      ? (static_cast<uint32_t>(maxSites) < kMaxSitesReported ? static_cast<uint32_t>(maxSites)
                                                            : kMaxSitesReported)
      : 8;

  // 找出存活字节最多的 limit 个站点：n 很小（≤ 512+1），直接选择排序。
  uint32_t order[kSiteSlots + 1];
  uint32_t used = 0;
  // ⚠️ 上界包含 g_otherSite（索引 == kSiteSlots）：站点表满时的兜底桶
  //    如果不扫它，报告里的 liveBytes 合计会小于 mallinfo2 的在用量，
  //    出现"账对不上"却找不到那一块——正是我们要避免的不可解释缺口。
  for (uint32_t i = 0; i <= kSiteSlots; i++) {
    if (g_sites[i].hash != 0 && g_sites[i].bytes > 0) order[used++] = i;
  }
  for (uint32_t i = 0; i < used && i < limit; i++) {
    uint32_t best = i;
    for (uint32_t j = i + 1; j < used; j++) {
      if (g_sites[order[j]].bytes > g_sites[order[best]].bytes) best = j;
    }
    uint32_t t = order[i]; order[i] = order[best]; order[best] = t;
  }

  struct mallinfo2 mi{};
  mi = mallinfo2();

  char* xml = static_cast<char*>(malloc(1 << 16));
  size_t xmlLen = 0;
  if (xml != nullptr) {
    xmlLen = dumpMallocInfo(xml, 1 << 16);
    if (xmlLen == 0) xml[0] = '\0';
  }

  // 汇总存活字节（用于自证：站点表里存活之和 vs mallinfo2 的在用量）
  uint64_t liveBytes = 0, liveCount = 0, totalBytes = 0;
  for (uint32_t i = 0; i <= kSiteSlots; i++) {
    liveBytes += g_sites[i].bytes;
    liveCount += g_sites[i].count;
    totalBytes += g_sites[i].totalBytes;
  }

  size_t cap = 1 << 18;
  char* out = static_cast<char*>(malloc(cap));
  if (out == nullptr) {
    free(xml);
    g_paused = false;
    return env->NewStringUTF("报告内存不足（malloc 失败）");
  }
  size_t off = 0;
#define APPEND(...)                                                       \
  do {                                                                    \
    const int r = snprintf(out + off, cap - off, __VA_ARGS__);             \
    if (r > 0 && static_cast<size_t>(r) < cap - off) off += static_cast<size_t>(r); \
    else off = cap - 1;                                                    \
  } while (0)

  APPEND("== memtrace 归因报告 ==\n");
  APPEND("installed=%d paused=%d seenAllocs=%llu trackedAllocs=%llu\n",
         g_installed ? 1 : 0, g_paused ? 1 : 0,
         (unsigned long long)g_seenAllocs, (unsigned long long)g_trackedAllocs);
  APPEND("config: 全量跟踪>=%zuB；[%zu,%zu)B 抽样 1/256；栈深=%d；地址槽=%u 站点槽=%u\n",
         kAlwaysTrackSize, kIgnoreBelowSize, kAlwaysTrackSize, kMaxFrames, kAddrSlots, kSiteSlots);
  APPEND("lossy: skippedContended=%llu freeSkipped=%llu addrOverflow=%llu unknownFree=%llu\n",
         (unsigned long long)g_skippedContended, (unsigned long long)g_freeSkipped,
         (unsigned long long)g_addrOverflow, (unsigned long long)g_unknownFree);
  APPEND("sites: used=%llu liveBytes=%llu(%.2f MB) liveCount=%llu cumulativeBytes=%llu(%.2f MB)\n",
         (unsigned long long)g_siteCount, (unsigned long long)liveBytes,
         liveBytes / 1048576.0, (unsigned long long)liveCount,
         (unsigned long long)totalBytes, totalBytes / 1048576.0);
  APPEND("mallinfo2: uordblks=%zu(%.2f MB) fordblks=%zu(%.2f MB) hblkhd=%zu(%.2f MB) "
         "arena=%zu ordblks=%zu\n",
         (size_t)mi.uordblks, mi.uordblks / 1048576.0,
         (size_t)mi.fordblks, mi.fordblks / 1048576.0,
         (size_t)mi.hblkhd, mi.hblkhd / 1048576.0,
         (size_t)mi.arena, (size_t)mi.ordblks);
  APPEND("malloc_info: version=%s bytes=%zu（⚠ 只含 primary/小对象档，大块走 secondary 不在此列）\n",
         strstr(xml, "version=\"") ? "scudo" : "(unknown)", xmlLen);

  // 尺寸档 Top（从 XML 里直接搬，按占用字节排序 —— 见 parseSizeClasses 注释）
  {
    SizeClass items[32];
    const int n = parseSizeClasses(xml, items, 32);
    const int top = n < 8 ? n : 8;
    for (int i = 0; i < top; i++) {
      APPEND("sizeclass: %8ld B x %ld = %.2f MB\n", items[i].size, items[i].count,
             items[i].size * items[i].count / 1048576.0);
    }
    if (n == 0) APPEND("sizeclass: （本机 malloc_info 未列出任何尺寸档）\n");
  }

  APPEND("\n-- 存活字节 Top%u 站点（bytes=当前存活, peak=历史峰值, total=累计）--\n", limit);
  for (uint32_t i = 0; i < used && i < limit; i++) {
    Site& s = g_sites[order[i]];
    const bool isOther = (order[i] == g_otherSite);
    APPEND("site#%u bytes=%llu(%.2f MB) count=%llu peak=%.2f MB total=%.2f MB frames=%u%s\n",
           i, (unsigned long long)s.bytes, s.bytes / 1048576.0,
           (unsigned long long)s.count, s.peakBytes / 1048576.0,
           s.totalBytes / 1048576.0, s.frameCount, isOther ? " [OTHER:站点表已满]" : "");
    for (uint32_t f = 0; f < s.frameCount; f++) {
      char line[192];
      resolveFrame(s.frames[f], line, sizeof(line));
      APPEND("  frame[%u] %s\n", f, line);
    }
  }
  if (used == 0) {
    APPEND("（本次窗口内没有记录到任何存活站点：可能未安装 / 已 reset / 分配都小于阈值）\n");
  }

  APPEND("\n⚠ 已知偏差（必须与数字一起读）：\n");
  APPEND("  · 全量跟踪 >=8192B + 小对象 1/256 抽样 ⇒ 小对象泄漏会漏\n");
  APPEND("  · free 时拿不到锁会放弃记账（freeSkipped）⇒ 存活字节**偏高**\n");
  APPEND("  · 地址表满的新分配无归属（addrOverflow）⇒ 存活字节偏低\n");
  APPEND("  · 符号化需离线做：拿构建目录带符号的 .so 跑 tools/native-mem-symbolize.sh\n");
#undef APPEND

  jstring result = env->NewStringUTF(out);
  free(out);
  free(xml);
  g_paused = false;
  return result;
}

/** 安装状态（给 Java 侧做降级判断与页面展示）。 */
JNIEXPORT jstring JNICALL
Java_com_interview_memtrace_MemTraceNative_statusNative(JNIEnv* env, jclass) {
  char buf[512];
  snprintf(buf, sizeof(buf),
           "installed=%d paused=%d lock=%d version=bytehook-%s sites=%llu/%u "
           "addrSlots=%u seenAllocs=%llu tracked=%llu",
           g_installed ? 1 : 0, g_paused ? 1 : 0, g_lockReady ? 1 : 0, BYTEHOOK_VERSION,
           (unsigned long long)g_siteCount, kSiteSlots, kAddrSlots,
           (unsigned long long)g_seenAllocs, (unsigned long long)g_trackedAllocs);
  return env->NewStringUTF(buf);
}

/**
 * **分配器视角**的轻量读数（给指标层每次采样调用，必须便宜）。
 *
 * 为什么单独一个入口、而不并入 reportNative：
 *   · reportNative 会遍历 512 个站点 + dl_iterate_phdr + 组装几 KB 文本，
 *     它是"按需出报告"的（秒级），**不能**放在 2 秒一次的采样路径上；
 *   · 本函数只做 `mallinfo2()` + 一次 `malloc_info`（实测输出仅百字节量级），
 *     实测成本在微秒级。
 *
 * 返回格式（单行、固定字段序，便于 Java 侧按位置解析，不用引入 JSON）：
 * ```
 *   used=<B> free=<B> mmap=<B> arena=<B> ordblks=<N>|sc:<size>:<count>,<size>:<count>,...
 * ```
 * `|sc:` 之后是尺寸档（**已按占用字节降序**），最多 8 个。
 */
JNIEXPORT jstring JNICALL
Java_com_interview_memtrace_MemTraceNative_allocatorStatsNative(JNIEnv* env, jclass) {
  // ⚠️ 本函数自己会 malloc 一个 64KB 缓冲，而它**每 2 秒被采一次**。
  //    不暂停记账的话，站点表里会常驻一个"来自 allocatorStatsNative 的 64KB 站点"，
  //    把真正的业务站点挤出 Top 榜 —— 这是"监控把自己记进被测对象"的现场，
  //    必须在代码里堵住，而不是靠读报告时人工忽略。
  const bool prevPaused = g_paused;
  g_paused = true;

  struct mallinfo2 mi{};
  mi = mallinfo2();

  char* xml = static_cast<char*>(malloc(1 << 16));
  size_t xmlLen = 0;
  if (xml != nullptr) {
    xmlLen = dumpMallocInfo(xml, 1 << 16);
    if (xmlLen == 0) xml[0] = '\0';
  }

  char buf[768];
  int off = snprintf(buf, sizeof(buf),
                     "used=%zu free=%zu mmap=%zu arena=%zu ordblks=%zu",
                     (size_t)mi.uordblks, (size_t)mi.fordblks, (size_t)mi.hblkhd,
                     (size_t)mi.arena, (size_t)mi.ordblks);
  if (off < 0) off = 0;
  if (static_cast<size_t>(off) < sizeof(buf)) {
    off += snprintf(buf + off, sizeof(buf) - off, "|sc:");
  }
  if (xml != nullptr) {
    SizeClass items[8];
    const int n = parseSizeClasses(xml, items, 8);
    for (int i = 0; i < n && static_cast<size_t>(off) < sizeof(buf) - 32; i++) {
      off += snprintf(buf + off, sizeof(buf) - off, "%s%ld:%ld",
                      i == 0 ? "" : ",", items[i].size, items[i].count);
    }
    free(xml);
  }
  if (static_cast<size_t>(off) >= sizeof(buf)) buf[sizeof(buf) - 1] = '\0';

  g_paused = prevPaused;
  return env->NewStringUTF(buf);
}

/**
 * **演示用**的 native 分配：分配 `count` 块 `size` 字节，返回指针数组。
 *
 * 为什么要专门加这一对 JNI 函数，而不是让 Java 侧用
 * `ByteBuffer.allocateDirect()`：
 *   · `allocateDirect` 的调用栈落在 libart/libjavacore 里，归因报告里看到的是
 *     ART 内部函数 —— 能证明"工具有效"，但**证明不了它能定位到我们自己的代码**；
 *   · 本函数在 memtrace.cpp 里，符号可见（`libmemtrace.so+0x...`），
 *     所以报告里会出现**我们自己的函数**这一帧 —— 这才是"归因到代码行"
 *     的完整证明（离线符号化后就能看到函数名）。
 *
 * ⚠️ 本函数**不暂停记账**（与 report/allocatorStats 相反）：它的分配正是我们要记的。
 */
JNIEXPORT jlongArray JNICALL
Java_com_interview_memtrace_MemTraceNative_allocBlocksNative(JNIEnv* env, jclass,
                                                            jint count, jint size) {
  if (count <= 0 || size <= 0 || count > 512) return nullptr;
  // 用栈上数组收指针：**不要在记账函数里再 malloc**（那会额外污染站点表）
  void* ptrs[512];
  int made = 0;
  for (int i = 0; i < count; i++) {
    void* p = malloc(static_cast<size_t>(size));
    if (p == nullptr) break;
    // 真正写一遍内存：不写的话 scudo 可能只记账不提交页，
    // PSS/RSS 上的变化就看不到 —— 而"净账涨了但 RSS 没涨"这个现象
    // 恰恰是要在演示里**区分**的两种情形之一（见 INTERFACE 文档）。
    memset(p, 0xA5, static_cast<size_t>(size));
    ptrs[made++] = p;
  }
  jlongArray arr = env->NewLongArray(made);
  if (arr != nullptr && made > 0) {
    jlong buf[512];
    for (int i = 0; i < made; i++) buf[i] = (jlong)(intptr_t)ptrs[i];
    env->SetLongArrayRegion(arr, 0, made, buf);
  }
  return arr;
}

/** 释放 [allocBlocksNative] 分配的块。 */
JNIEXPORT void JNICALL
Java_com_interview_memtrace_MemTraceNative_freeBlocksNative(JNIEnv* env, jclass, jlongArray arr) {
  if (arr == nullptr) return;
  const jsize n = env->GetArrayLength(arr);
  if (n <= 0) return;
  jlong* buf = env->GetLongArrayElements(arr, nullptr);
  if (buf == nullptr) return;
  for (jsize i = 0; i < n; i++) {
    if (buf[i] != 0) free((void*)(intptr_t)buf[i]);
  }
  env->ReleaseLongArrayElements(arr, buf, JNI_ABORT);
}

} // extern "C"
