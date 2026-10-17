// ══════════════════════════════════════════════════════════════════════════════
// oom_snapshot.cpp —— JVM OOM 现场取证：**fork 出子进程，由子进程 dump 父进程的堆快照**
// ══════════════════════════════════════════════════════════════════════════════
//
// 命题（用户原话的意思）：OOM 时不要在本进程里 dump —— 本进程此刻已经分配不出内存，
// 而 `Debug.dumpHprofData` 需要 stop-the-world + 几十 MB 缓冲，十有八九在 dump 里再炸一次。
// 改为 **fork 一个子进程**：子进程只复制页表（COW），它看到的堆就是 fork 那一瞬间的原样，
// 由它去慢慢写文件；父进程可以照常活着或照常死。
//
// ─────────────────────────────────────────────────────────────────────────────
// 本文件的所有"为什么"都来自**本机实测**（Redmi K40 / Android 12 / arm64 / user 版），
// 不是从源码读出来的推测。实测过程与原始数据见
//   app/src/main/java/com/interview/内存/INTERVIEW-OOM快照方案.md
// 这里只写**结论 + 踩坑的现场**，因为下面每一行都对应一个"不这么写就必然失败"。
// ─────────────────────────────────────────────────────────────────────────────
//
// 【0】为什么不能用 Debug.dumpHprofData，也不能 dlopen libart
//
//   Debug.dumpHprofData → Debug.dumpHprofDataDdms → art::Dbg::DumpHprofData
//   → 最终落到 art::hprof::DumpHeap。我们想要的就是这个函数，但要在**子进程**里调。
//
//   拿到它的地址有两条路，实测**只活了一条**：
//     ✗ dlopen("/apex/com.android.art/lib64/libart.so")
//       实测报错：library "...libart.so" needed or dlopened by "/data/local/tmp/libx.so"
//                 is not accessible for the namespace "classloader-namespace"
//       —— 应用进程在 classloader-namespace，ld.config 的 permitted.paths 不含 ART APEX。
//     ✗ 在 .so 里留一个未定义引用，让 linker 在加载期解析（NEEDED/undefined）
//       实测：UnsatisfiedLinkError: cannot locate symbol "_ZN3art5hprof8DumpHeapEPKcib"
//       —— 与 dlopen 同源，加载期的符号查找也受 namespace 约束。
//     ✓ **手工解析已经映射在本进程里的 libart.so 的 .dynsym**
//       我们本来就是 ART 跑起来的，libart.so 早就在内存里；用公开 API
//       dl_iterate_phdr 拿到它的 load bias，再自己走 DT_SYMTAB/DT_STRTAB 查符号。
//       这条路只用公开 API + 公开 ELF 结构，不依赖任何私有权限。
//
// 【1】为什么 fork 之后不能直接调 DumpHeap（实测死锁，且卡死在两种地方）
//
//   直接调，子进程**永久卡死**（futex 不返回，只能 SIGKILL）。用 SIGALRM + 帧指针回溯
//   抓到子进程 pc，再减去 libart load bias 换成文件偏移，与本地 libart.so 的 .symtab 对账：
//
//     死锁点 A：DumpHeap → ScopedGCCriticalSection ctor → Heap::StartGC
//                        → Heap::WaitForGcToCompleteLocked     ← 卡在这
//     死锁点 B：DumpHeap → InternTable::VisitRoots → Mutex::ExclusiveLock  ← 卡在这
//
//   根因是同一个：**这两处都在"等其他线程"**（等 GC 结束、等锁的持有者释放），
//   而 fork 之后子进程里只剩一个线程 —— 那些线程在子进程里只是页表里的副本，
//   它们的调用栈冻结在 fork 那一刻，永远不会来 unlock、永远不会来挂起。
//
// 【2】解法：三个动作**缺一不可**（用 mode 位逐项做过对照实验，见下面 flag 说明）
//
//   ① fork 之前，在**父进程**里 ThreadList::SuspendAll
//      ⇒ 让所有其它线程停在挂起点再 fork。这样"锁的持有者"只可能是本线程，
//        而本线程没持锁 ⇒ 子进程里不存在"持有者不在场"的锁（治死锁点 B）。
//      ⚠️ 必须在 fork 之后**立刻** ResumeAll，否则 App 整体卡住（ResumeAll 本身也在等它们）。
//   ② 子进程里把 ThreadList::SuspendAll / ResumeAll 变成空操作
//      ⇒ 因为 DumpHeap 自己会 SuspendAll，而父进程已经挂起过一次；
//        子进程里再挂一次会去等"其它线程到达挂起点"，它们只是副本 ⇒ 死锁。
//   ③ 子进程里把 Heap::StartGC / Heap::FinishGC 变成空操作
//      ⇒ 治死锁点 A。子进程里不需要也不能 GC：我们要的就是**冻结的那一刻**。
//
//   实测四种组合（同一台机器，64MB 堆，超时 12s）：
//     mode=4（只做③）                    → 卡在 HostSuspendAll 路径，TIMEOUT
//     mode=5（做②③，不做①）             → 卡在 InternTable::VisitRoots，TIMEOUT
//     mode=6（做①③，不做②）             → 卡在 ThreadList::SuspendAll，TIMEOUT
//     mode=7（①②③）                     → **成功，808ms，85.8MB 合法 hprof**
//
// 【3】为什么"在子进程里改代码段"是安全的 —— 这正是本方案能成立的原因
//
//   fork 之后所有页都是 COW 私有的。子进程 mprotect + 写 libart.so 的代码页，
//   只会触发那几页的 COW 复制，**父进程的 libart 映像完全不受影响**。
//   所以：child-patch 只作用于本次取证的子进程，父进程里 Debug.dumpHprofData、
//   其它 ART 行为全部照旧 —— 这是本实现相对"启动时就把 ART 打补丁"的做法
//   （那样父进程的 dump 能力也一起被改掉）的一个明确优势。
//
// 【4】子进程要能**活过父进程**（OOM 时这条特别重要）
//
//   OOM 现场的真实剧本是：dump 还没写完，父进程就被系统杀掉/自己崩了。
//   所以这里**刻意不做**两件事：
//     · 不设 prctl(PR_SET_PDEATHSIG) —— 设了就是"父死子必死"，快照必然残缺；
//     · 不依赖父进程收尸 —— 子进程 setpgid(0,0) 自成进程组，父进程被 killed 后
//       （Android 的 LMK 是按进程杀，不牵连进程组）子进程继续把 hprof 写完、fsync、再退出。
//   父进程侧的 waitpid 只是"能等就等、超时就杀"，不是子进程存活的前提。
//
// 【5】快照确实是"fork 那一刻的原样"—— 有一条独立实验证明
//
//   在父进程分配 1MB byte[] 填 0xAB → 起一个父进程线程在 200ms 后（子进程正在 dump）
//   把它整块改成 0xCD → fork 一个子进程去 dump。事后在产出的 hprof 里逐字节扫描：
//     最长 0xAB 连续段 = 1048576 字节（完整命中）
//     0xCD 连续段     = 0（**一次都没有**）
//   ⇒ dump 记录的是 fork 瞬间的内容，父进程后续的修改没有渗进去。
//
// 【6】真 OOM 时刻也验证过
//
//   把堆压到 255MB/256MB（ART 抛 OutOfMemoryError），在 catch 里立刻 fork+dump：
//   产出 285MB hprof、耗时 1160ms、exit(0)。并用 **shark 2.14**（与自研解析器无共用代码）
//   独立读取：bytesRead == fileLen == 285046277，2822 个类，其中 byte[] 270MB。
//   ⚠️ 但这只是"这一次能成"的数据点，不是"永远能成"的保证：
//      fork 要复制页表、子进程还要写缓冲，堆彻底耗尽时 fork 可能直接 ENOMEM。
//      所以业务层另有"水位阈值提前触发"的通道（见 OomSnapshotMonitor），
//      不能只押"真 OOM 那一刻还能 fork 成功"。
//
// ⚠️ 已知边界（诚实标注，别把这里当"全版本通用"）
//   · 只在 arm64 / x86_64 上实现了"改首指令为 ret"；其余 ABI 直接返回不支持。
//   · 符号名与 ART 版本绑定。本机（Android 12 / SDK 31）实测存在的是
//     DumpHeap(const char*, int, bool)。Android 13+ 该函数签名可能变成
//     std::string 版本 —— 本文件按候选表逐个尝试，**找不到就明确报"符号未命中"**，
//     绝不假装成功。新增 ROM/版本后应当先跑 `probe()` 看符号能否命中。
//   · SuspendAll 是**同步**的：若某线程长期停在不可中断的 native 调用里，
//     父进程挂起会拖长（甚至卡住）。本实现把"挂起→fork→恢复"窗口压到最小，
//     并打印实际耗时；这是本方案区别于"只读内存、不碰 ART"方案的代价所在。
// ══════════════════════════════════════════════════════════════════════════════

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define TAG "OomSnapshot"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
// mode 位（与 Kotlin 侧 Flags 常量逐位对应；对照实验用）
// ─────────────────────────────────────────────────────────────────────────────
#define FLAG_PARENT_SUSPEND 0x01   // ① fork 前在父进程 SuspendAll
#define FLAG_CHILD_PATCH_SUSPEND 0x02   // ② 子进程：SuspendAll/ResumeAll → ret
#define FLAG_CHILD_PATCH_GC 0x04   // ③ 子进程：Heap::StartGC/FinishGC → ret
#define FLAGS_DEFAULT (FLAG_PARENT_SUSPEND | FLAG_CHILD_PATCH_SUSPEND | FLAG_CHILD_PATCH_GC)

// ─────────────────────────────────────────────────────────────────────────────
// 候选符号表
//
// DumpHeap 的签名随 ART 版本变过，所以按候选逐个试，命中哪个就报哪个。
// ⚠️ 本机实测命中的只有第一条；后两条是**留档的候选**，未在真机验证过 ——
//    命中不了会走"符号未命中"分支并打日志，不会静默失败。
// ─────────────────────────────────────────────────────────────────────────────
struct SymCandidate {
    const char *mangled;
    const char *note;   // 版本线索，仅用于日志
};

static const SymCandidate kDumpHeapCandidates[] = {
        {"_ZN3art5hprof8DumpHeapEPKcib", "DumpHeap(const char*, int, bool) —— 本机 Android 12 实测命中"},
        {"_ZN3art5hprof8DumpHeapENSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEib",
         "DumpHeap(std::string, int, bool) —— Android 13+ 候选，未在真机验证"},
        {"_ZN3art5hprof8DumpHeapEPKcb", "DumpHeap(const char*, bool) —— 旧版本候选，未在真机验证"},
};

struct Sym {
    const char *mangled;
    void *addr;
    bool required;   // 必需（缺了就不能用）还是"有就用"
};

// ─────────────────────────────────────────────────────────────────────────────
// 手工 ELF 动态符号解析（dlopen 路线已被 linker namespace 封死，见文件头【0】）
// ─────────────────────────────────────────────────────────────────────────────
static uintptr_t g_libart_base = 0;
static int g_libart_found = 0;
static size_t g_libart_dynsym_count = 0;

struct FindCtx {
    const char *name;
    void *result;
};

// 从 GNU_HASH 推出动态符号总数（libart.so 是 BIND_NOW + GNU_HASH，没有 DT_HASH）。
// 只看 hash 桶的最大值，再沿 chain 走到链尾 —— 我们只是要一个可线性扫描的上界。
//
// ⚠️【踩过的坑，务必保留这段】GNU_HASH 的表头不是 `[nbuckets][symoffset][buckets...]`，
//    中间还夹着 **bloom filter（Elf64_Xword[bloom_size]）**。第一版漏掉了 bloom，
//    把 bloom 的字当成 bucket 读，最大 bucket 被算成 **4026643504** —— 一个 4 亿的
//    假上界。后果分两级，第二级是致命的：
//      ① 好符号**仍然能命中**（命中即 return，不会走到表尾）—— 所以 probe() 看起来一切正常；
//      ② 一旦某个名字**没找到**，扫描就会越过 .dynsym 的真实末尾去解引用假地址，
//         在 phdr_cb 里的 strcmp 上直接 SIGSEGV，**整个父进程崩掉**。
//    实测就是这样崩的：probe 全绿，forkDump 里一个拼错的符号名把 App 带走。
//    教训：**"找不到就走查表末尾"必须被硬约束住**，见下方 DT_STRSZ 校验。
static size_t symcount_from_gnu_hash(const uint32_t *gnu) {
    if (gnu == nullptr) return 0;
    // ⚠️ bloom filter 的元素宽度取决于 ELF class：64 位是 Elf64_Xword（8 字节），
    //    32 位是 Elf32_Word（4 字节）。这里用 64 位公式，所以**必须在 32 位上早退**，
    //    否则 buckets 的偏移会多跳 bloom_size*4 字节，读到的"bucket"全是垃圾，
    //    进而推出一个失控的 symcount（下面有截断兜底，但早退更干净）。
    //    本库的 patch 本来就只支持 arm64/x86_64（见 patch_entry_to_ret），
    //    所以 32 位返回 0（"符号未命中"）与整体能力声明是一致的，不是新缺陷。
#if !defined(__LP64__)
    (void) gnu;
    return 0;
#else
    const uint32_t nbuckets = gnu[0];
    const uint32_t symoffset = gnu[1];
    const uint32_t bloom_size = gnu[2];
    if (nbuckets == 0 || symoffset == 0 || nbuckets > (1u << 24)) return 0;
    if (bloom_size > (1u << 24)) return 0;
    // bloom 是 Elf64_Xword 数组 → nbuckets 的偏移是 16 + 8*bloom_size
    const uint32_t *buckets = gnu + 4 + 2 * bloom_size;
    uint32_t bmax = 0;
    for (uint32_t k = 0; k < nbuckets; k++) {
        if (buckets[k] > bmax) bmax = buckets[k];
    }
    if (bmax < symoffset) return 0;
    const uint32_t *chain = buckets + nbuckets;
    uint32_t idx = bmax;
    // chain 的最后一项最低位置 1；给一个硬上界防止畸形表把这里跑飞。
    // ⚠️ 撞上上界时必须返回 0（= 解析失败 → 干净降级），
    //    而不是把上界当结果返回 —— 那等于把一个"不知道"包装成一个假的确定值。
    while (idx <= 400000u) {
        const uint32_t v = chain[idx - symoffset];
        idx++;
        if ((v & 1u) != 0) return idx;
    }
    return 0;
#endif
}

static int phdr_cb(struct dl_phdr_info *info, size_t, void *data) {
    auto *ctx = static_cast<FindCtx *>(data);
    if (ctx->result != nullptr) return 1;
    if (info->dlpi_name == nullptr) return 0;

    // 只认 libart.so 本身：libartbase.so / libartpalette.so 是同前缀的兄弟库，
    // 用 strstr 会误命中（它们里面没有 hprof）。
    const char *slash = strrchr(info->dlpi_name, '/');
    const char *leaf = slash ? slash + 1 : info->dlpi_name;
    if (strcmp(leaf, "libart.so") != 0) return 0;

    const uintptr_t bias = static_cast<uintptr_t>(info->dlpi_addr);
    g_libart_base = bias;
    g_libart_found = 1;

    // 文件头只在 offset==0 的那个 PT_LOAD 里（libart.so 满足）
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD || ph->p_offset != 0) continue;

        auto *eh = reinterpret_cast<ElfW(Ehdr) *>(bias + ph->p_vaddr);
        if (memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0) return 0;

        const ElfW(Dyn) *dyn = nullptr;
        for (int j = 0; j < info->dlpi_phnum; j++) {
            if (info->dlpi_phdr[j].p_type == PT_DYNAMIC) {
                dyn = reinterpret_cast<const ElfW(Dyn) *>(bias + info->dlpi_phdr[j].p_vaddr);
                break;
            }
        }
        if (dyn == nullptr) return 0;

        ElfW(Sym) *symtab = nullptr;
        const char *strtab = nullptr;
        size_t symcount = 0;
        size_t strsz = 0;
        const uint32_t *gnu = nullptr;
        // DT_SYMTAB 落在哪个 PT_LOAD 里 —— 用来算"这张表**物理上**装得下多少项"。
        // 这才是真正的上界；symcount 只是"hash 表声称有多少项"。
        size_t symtab_max = 0;
        for (const ElfW(Dyn) *d = dyn; d->d_tag != DT_NULL; d++) {
            switch (d->d_tag) {
                case DT_SYMTAB:
                    symtab = reinterpret_cast<ElfW(Sym) *>(bias + d->d_un.d_ptr);
                    break;
                case DT_STRTAB:
                    strtab = reinterpret_cast<const char *>(bias + d->d_un.d_ptr);
                    break;
                case DT_GNU_HASH:
                    gnu = reinterpret_cast<const uint32_t *>(bias + d->d_un.d_ptr);
                    break;
                case DT_HASH:
                    // 有 DT_HASH 就用它（第二项就是符号总数），否则退回 GNU_HASH 推导
                    symcount = reinterpret_cast<const uint32_t *>(bias + d->d_un.d_ptr)[1];
                    break;
                case DT_STRSZ:
                    // strtab 的**真实字节数** —— 用来把每个符号名夹在合法范围内
                    strsz = d->d_un.d_val;
                    break;
                default:
                    break;
            }
        }
        if (symtab == nullptr || strtab == nullptr) return 0;
        if (symcount == 0) symcount = symcount_from_gnu_hash(gnu);
        if (symcount == 0) return 0;

        // ── 真正的安全闸门：算出 .dynsym **物理上**能放多少项 ───────────────
        //
        // ⚠️ 这一段是第二次被实测教育后补上的，值得留档：
        //    symcount 来自 hash 表（声称有多少项），一旦解析出错或 ROM 表畸形，
        //    它会变成一个巨大的假上界。第一版的兜底是"用 DT_STRSZ 夹住符号名的偏移"，
        //    那个闸门**只保证偏移合法**，但 `symtab[k]` 这次解引用本身仍无约束 ——
        //    也就是说循环可能已经在读 .dynsym 之外的内存了。本机之所以没崩，
        //    纯属运气：.dynsym 后面还铺着 1.2MB 只读段（实测闸门在 k=6018 触发，
        //    而段尾相当于 k≈87600）。换个 .so 布局、或 32 位 ABI 下就是崩溃。
        //
        //    所以真正的上界必须来自**段大小**：k * sizeof(Sym) 不能超出
        //    .dynsym 所在 PT_LOAD 的文件大小。这是在读任何一项**之前**就夹死的。
        {
            const uintptr_t sym_addr = reinterpret_cast<uintptr_t>(symtab);
            for (int j = 0; j < info->dlpi_phnum; j++) {
                const ElfW(Phdr) *p = &info->dlpi_phdr[j];
                if (p->p_type != PT_LOAD) continue;
                const uintptr_t seg_begin = bias + p->p_vaddr;
                const uintptr_t seg_end = seg_begin + p->p_filesz;
                if (sym_addr >= seg_begin && sym_addr < seg_end) {
                    symtab_max = (seg_end - sym_addr) / sizeof(ElfW(Sym));
                    break;
                }
            }
        }
        if (symtab_max > 0 && symcount > symtab_max) {
            // 只在"夹掉了东西"时才吼：正常情况两个数是相等的
            LOGW("⚠️ .dynsym 声称 %zu 项，但所在段只装得下 %zu 项 —— 按段大小截断",
                 symcount, symtab_max);
            symcount = symtab_max;
        }
        if (symcount == 0) return 0;
        g_libart_dynsym_count = symcount;

        for (size_t k = 0; k < symcount; k++) {
            const size_t off = symtab[k].st_name;
            // 第二道闸门：符号名偏移必须在 .dynstr 范围内。
            // ⚠️ 这道闸门**不能单独用**（见上）—— 它在读完 symtab[k] 之后才生效。
            //    保留它是因为它挡的是另一类问题：st_name 合法但指向越界的字符串。
            if (off >= strsz) break;
            const char *n = strtab + off;
            if (strcmp(n, ctx->name) == 0) {
                ctx->result = reinterpret_cast<void *>(bias + symtab[k].st_value);
                return 0;
            }
        }
        return 0;   // 找到了 libart.so 就够了，不必继续遍历其它库
    }
    return 0;
}

static void *resolve_in_libart(const char *mangled) {
    FindCtx ctx{mangled, nullptr};
    dl_iterate_phdr(phdr_cb, &ctx);
    return ctx.result;
}

// 按候选表解析 DumpHeap；命中时把 note 写出来（"到底是哪个签名"必须可见）
static const char *g_dump_heap_hit = nullptr;
static void *resolve_dump_heap(const char **hit_note) {
    for (const auto &c : kDumpHeapCandidates) {
        void *p = resolve_in_libart(c.mangled);
        if (p != nullptr) {
            g_dump_heap_hit = c.mangled;
            if (hit_note) *hit_note = c.note;
            return p;
        }
    }
    if (hit_note) *hit_note = nullptr;
    return nullptr;
}

// ─────────────────────────────────────────────────────────────────────────────
// 子进程侧日志（定义在后面；这里先声明，因为 patch 的日志也要用它）
// 它们**只在子进程里被调用**，实现里没有 snprintf/liblog —— 见后方定义处的说明。
// ─────────────────────────────────────────────────────────────────────────────
static void child_log(const char *msg);
static void child_log_patch(const char *what, void *fn, unsigned long long before_bits, int hexdigits);
static char *append_str(char *p, char *end, const char *s);
static char *append_int(char *p, char *end, long long v);

// ─────────────────────────────────────────────────────────────────────────────
// 把函数入口改成 `ret`（子进程专用）
// ─────────────────────────────────────────────────────────────────────────────
// ⚠️ 这个函数只在**子进程**里调用（父进程的 libart 绝不能被改）。
//    所以它内部的日志走 child_log_*（无锁、无 locale、只有 write），
//    不能用 LOGW/LOGI —— 那些要拿 liblog 的锁。见文件头【3】。
static bool patch_entry_to_ret(void *fn, const char *what, bool in_child) {
    if (fn == nullptr) {
        // 不是所有 ROM/版本都有这些符号；缺了就**只跳过这一项**并说清楚，
        // 不要因为一个非必需符号缺失就整体失败。
        if (in_child) {
            child_log("skip patch: symbol missing");
        } else {
            LOGW("patch %s：符号不存在，跳过", what);
        }
        return false;
    }

#if defined(__aarch64__)
    const uint32_t ret_insn = 0xD65F03C0u;   // AArch64: ret
    const size_t insn_size = 4;
#elif defined(__x86_64__)
    const uint8_t ret_insn = 0xC3;           // x86-64: ret
    const size_t insn_size = 1;
#else
    if (in_child) {
        child_log("skip patch: unsupported ABI");
    } else {
        LOGW("patch %s：本 ABI 未实现（只有 arm64 / x86_64）", what);
    }
    return false;
#endif

    const long ps = sysconf(_SC_PAGESIZE);
    if (ps <= 0) return false;
    const uintptr_t page = reinterpret_cast<uintptr_t>(fn) & ~static_cast<uintptr_t>(ps - 1);

    // fork 之后代码页是 COW 私有副本，改这里不会影响父进程（见文件头【3】）
    if (mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(ps),
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        if (in_child) {
            // strerror 可能查表/碰 locale，子进程里不用它，只报 errno 数值
            char tmp[128];
            char *end = tmp + sizeof(tmp) - 2;
            char *p = append_str(tmp, end, "patch mprotect failed errno=");
            p = append_int(p, end, errno);
            if (p < end) *p = '\0';
            child_log(tmp);
        } else {
            LOGE("patch %s：mprotect 失败 errno=%d(%s)", what, errno, strerror(errno));
        }
        return false;
    }

    uint8_t before[8] = {0};
    memcpy(before, fn, insn_size);
    memcpy(fn, &ret_insn, insn_size);
    __builtin___clear_cache(reinterpret_cast<char *>(fn),
                            reinterpret_cast<char *>(fn) + insn_size);

    // ── 回读校验：**改完必须再读一遍确认**（这是本文件的一条纪律）──────
    // 为什么要回读：补丁没打上的后果是**子进程死锁**，而父进程只会看到
    // `timeout_sigkilled` —— 和一个"ART 变了导致本来就卡"的现场**长得一模一样**，
    // 排查成本极高。回读能把"补丁失败"变成一个明确的、当场可见的原因。
    // （实测过类似的坑：符号名贴错一个字符 = 静默少打一个补丁 = 子进程卡死。）
    uint8_t after[8] = {0};
    memcpy(after, fn, insn_size);
    if (memcmp(after, &ret_insn, insn_size) != 0) {
        if (in_child) {
            child_log("patch readback FAILED");
        } else {
            LOGE("patch %s @%p 回读校验失败 —— 期望 ret，实际不符（该页可能不可写/被保护）",
                 what, fn);
        }
        return false;
    }

    // 记录"改之前是什么"：万一将来 ART 把这些函数的入口改成 ABI 垫片
    // （例如多一条 bti/paciasp 之外的指令），日志里能立刻看出来。
    unsigned long long before_bits = 0;
    for (size_t i = 0; i < insn_size && i < 8; i++) {
        before_bits |= static_cast<unsigned long long>(before[i]) << (8 * i);
    }
    if (in_child) {
        child_log_patch(what, fn, before_bits, static_cast<int>(insn_size * 2));
    } else {
        char hex[24] = {0};
        for (size_t i = 0; i < insn_size; i++) {
            snprintf(hex + i * 2, 3, "%02x", before[i]);
        }
        LOGI("patch %s @%p 首指令 %s → ret", what, fn, hex);
    }
    return true;
}

// ─────────────────────────────────────────────────────────────────────────────
// 小工具
// ─────────────────────────────────────────────────────────────────────────────
static long long now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<long long>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

// ─────────────────────────────────────────────────────────────────────────────
// 子进程侧的日志：**严格 async-signal-safe**
//
// POSIX 只保证 fork 后的子进程可以安全调用 async-signal-safe 那一小撮函数。
// snprintf / __android_log_print **都不在**那个名单里（前者可能碰 locale，
// 后者要拿 liblog 的锁）。所以这里自己拼字符串，最后只用 write()。
//
// 注：默认路径下（先 SuspendAll 再 fork）其实不会再有别的线程持锁，
// 用 snprintf 大概率也没事 —— 但 flags 允许关掉父进程挂起（对照实验用），
// 那时这条保证就没了。子进程的纪律按**最坏情况**写，不留"大概率没事"的侥幸。
// ─────────────────────────────────────────────────────────────────────────────
static char *append_str(char *p, char *end, const char *s) {
    while (*s != '\0' && p < end) *p++ = *s++;
    return p;
}

// 十进制（十进制不需要 locale，手写即可）
static char *append_int(char *p, char *end, long long v) {
    if (v < 0) {
        if (p < end) *p++ = '-';
        v = -v;
    }
    char tmp[24];
    int n = 0;
    do {
        tmp[n++] = static_cast<char>('0' + (v % 10));
        v /= 10;
    } while (v != 0 && n < 23);
    while (n > 0 && p < end) *p++ = tmp[--n];
    return p;
}

static char *append_hex16(char *p, char *end, unsigned long long v) {
    static const char *digits = "0123456789abcdef";
    for (int shift = 60; shift >= 0 && p < end; shift -= 4) {
        *p++ = digits[(v >> shift) & 0xF];
    }
    return p;
}

static void child_log(const char *msg) {
    char buf[256];
    char *end = buf + sizeof(buf) - 2;
    char *p = append_str(buf, end, "[OomSnapshot child] ");
    p = append_str(p, end, msg);
    if (p < end) *p++ = '\n';
    if (p > buf) {
        ssize_t ignored = write(STDERR_FILENO, buf, static_cast<size_t>(p - buf));
        (void) ignored;
    }
}

// "patch <name> @0x<addr> <before> -> ret" 的免锁版本
static void child_log_patch(const char *what, void *fn, unsigned long long before_bits, int hexdigits) {
    char buf[256];
    char *end = buf + sizeof(buf) - 2;
    char *p = append_str(buf, end, "[OomSnapshot child] patch ");
    p = append_str(p, end, what);
    p = append_str(p, end, " @0x");
    p = append_hex16(p, end, reinterpret_cast<unsigned long long>(fn));
    p = append_str(p, end, " before=0x");
    // 只保留低 hexdigits 位十六进制
    unsigned long long mask = (hexdigits >= 16) ? ~0ULL : ((1ULL << (hexdigits * 4)) - 1);
    unsigned long long v = before_bits & mask;
    for (int shift = 60; shift >= 0; shift -= 4) {
        unsigned long long nib = (v >> shift) & 0xF;
        if (shift >= hexdigits * 4 && nib == 0) continue;   // 前导零不打印
        static const char *digits = "0123456789abcdef";
        if (p < end) *p++ = digits[nib];
    }
    p = append_str(p, end, " -> ret");
    if (p < end) *p++ = '\n';
    if (p > buf) {
        ssize_t ignored = write(STDERR_FILENO, buf, static_cast<size_t>(p - buf));
        (void) ignored;
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// probe：能力探测
//
// 页面/PR 的第一件事应该是看这个 —— 它回答"这台设备上本方案能不能用、用的哪个符号"。
// 绝不要把"符号没找到"包装成"dump 成功"。
// ═════════════════════════════════════════════════════════════════════════════
extern "C" JNIEXPORT jstring JNICALL
Java_com_interview_oomdump_OomSnapshotNative_probeNative(JNIEnv *env, jclass) {
    const char *note = nullptr;
    void *dump = resolve_dump_heap(&note);

    // 顺手探测三个"必需"符号是否存在（缺任何一个，默认 flags 就不可用）
    void *p_parent_suspend = resolve_in_libart("_ZN3art16ScopedSuspendAllC2EPKcb");
    void *p_resume = resolve_in_libart("_ZN3art16ScopedSuspendAllD2Ev");
    void *p_child_suspend = resolve_in_libart("_ZN3art10ThreadList10SuspendAllEPKcb");
    void *p_child_resume = resolve_in_libart("_ZN3art10ThreadList9ResumeAllEv");
    void *p_gc_start = resolve_in_libart("_ZN3art2gc4Heap7StartGCEPNS_6ThreadENS0_7GcCauseENS0_13CollectorTypeE");
    void *p_gc_finish = resolve_in_libart("_ZN3art2gc4Heap8FinishGCEPNS_6ThreadENS0_9collector6GcTypeE");

    char buf[1024];
    snprintf(buf, sizeof(buf),
             "usable=%d libart=0x%lx libartFound=%d dynsym=%zu "
             "dumpHeap=0x%lx dumpSym=%s "
             "scopedSuspendAll=0x%lx scopedSuspendAllDtor=0x%lx "
             "threadListSuspendAll=0x%lx threadListResumeAll=0x%lx "
             "heapStartGc=0x%lx heapFinishGc=0x%lx",
             (dump != nullptr && p_parent_suspend != nullptr && p_resume != nullptr &&
              p_child_suspend != nullptr && p_child_resume != nullptr &&
              p_gc_start != nullptr && p_gc_finish != nullptr) ? 1 : 0,
             static_cast<unsigned long>(g_libart_base), g_libart_found, g_libart_dynsym_count,
             static_cast<unsigned long>(reinterpret_cast<uintptr_t>(dump)),
             g_dump_heap_hit ? g_dump_heap_hit : "-",
             static_cast<unsigned long>(reinterpret_cast<uintptr_t>(p_parent_suspend)),
             static_cast<unsigned long>(reinterpret_cast<uintptr_t>(p_resume)),
             static_cast<unsigned long>(reinterpret_cast<uintptr_t>(p_child_suspend)),
             static_cast<unsigned long>(reinterpret_cast<uintptr_t>(p_child_resume)),
             static_cast<unsigned long>(reinterpret_cast<uintptr_t>(p_gc_start)),
             static_cast<unsigned long>(reinterpret_cast<uintptr_t>(p_gc_finish)));

    // note 单独一行：它含空格/中文，塞进上面那个以空格分词的串里不好解析
    LOGI("probe: %s", buf);
    LOGI("probe: 命中签名 = %s", note ? note : "（DumpHeap 未命中！）");

    // 返回 "键=值 ...\n# note"：Kotlin 侧按空格分词解析，第二行原样展示给人看
    char out[1400];
    snprintf(out, sizeof(out), "%s\n#%s", buf, note ? note : "DumpHeap 符号未命中");
    return env->NewStringUTF(out);
}

// ═════════════════════════════════════════════════════════════════════════════
// forkDump：主入口
//
// 约定：
//   · **必须在后台线程调用**（会阻塞到子进程写完或超时）；
//   · 返回值是一行 `键=值` 诊断串（Kotlin 侧解析成结构体展示），不抛异常；
//   · 失败一律把原因写进返回值与 logcat，绝不返回"成功"。
// ═════════════════════════════════════════════════════════════════════════════
extern "C" JNIEXPORT jstring JNICALL
Java_com_interview_oomdump_OomSnapshotNative_forkDumpNative(
        JNIEnv *env, jclass, jstring jpath, jlong timeout_ms, jint flags) {

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) {
        return env->NewStringUTF("ok=0 err=GetStringUTFChars_null");
    }

    // ── 结果累加器（最后一次性组装成一行返回）──────────────────────────────
    char result[1024];
    auto fail = [&](const char *reason) -> jstring {
        snprintf(result, sizeof(result), "ok=0 err=%s", reason);
        LOGE("forkDump 失败：%s", reason);
        env->ReleaseStringUTFChars(jpath, path);
        return env->NewStringUTF(result);
    };

    // ── 解析符号（父进程里做，子进程里绝不再做任何"可能拿锁"的事）──────────
    // ⚠️ timeout<=0 会把 deadline 设成"现在就超时" → 循环一次都不跑 → 直接 SIGKILL
    //    + unlink，把一个本来正常的 dump 静默杀掉。输入非法就该当场拒绝。
    if (timeout_ms <= 0) {
        return fail("bad_timeout_ms");
    }

    const char *sym_note = nullptr;
    void *p_dump = resolve_dump_heap(&sym_note);
    void *p_scoped_suspend = resolve_in_libart("_ZN3art16ScopedSuspendAllC2EPKcb");
    void *p_scoped_suspend_dtor = resolve_in_libart("_ZN3art16ScopedSuspendAllD2Ev");
    void *p_suspend_all = resolve_in_libart("_ZN3art10ThreadList10SuspendAllEPKcb");
    void *p_resume_all = resolve_in_libart("_ZN3art10ThreadList9ResumeAllEv");
    // ⚠️ mangled 名逐字对齐：贴错一个字符就是"静默地少打一个补丁"，
    //    后果是子进程死锁在 GC 等待上（实测见过）。所以每个符号命中与否
    //    都进 probe() 的返回值，而不是只躺在日志里。
    void *p_gc_start = resolve_in_libart(
            "_ZN3art2gc4Heap7StartGCEPNS_6ThreadENS0_7GcCauseENS0_13CollectorTypeE");
    void *p_gc_finish = resolve_in_libart(
            "_ZN3art2gc4Heap8FinishGCEPNS_6ThreadENS0_9collector6GcTypeE");

    if (p_dump == nullptr) {
        return fail("dumpHeap_symbol_not_found");
    }
    if ((flags & FLAG_PARENT_SUSPEND) && (p_scoped_suspend == nullptr || p_scoped_suspend_dtor == nullptr)) {
        return fail("scopedSuspendAll_symbol_not_found");
    }
    // ⚠️ 子进程补丁符号也必须在**父进程**这里查完再判空。
    //    若放进子进程里判，一旦为 null 就会去改地址 0 —— 子进程崩了、父进程只看到超时，
    //    排查成本极高。宁可在这里干净地失败，返回一个能看懂的原因。
    if ((flags & FLAG_CHILD_PATCH_SUSPEND) && (p_suspend_all == nullptr || p_resume_all == nullptr)) {
        return fail("threadListSuspend_symbol_not_found");
    }
    if ((flags & FLAG_CHILD_PATCH_GC) && (p_gc_start == nullptr || p_gc_finish == nullptr)) {
        return fail("heapGc_symbol_not_found");
    }

    LOGI("forkDump 开始 path=%s timeout=%lldms flags=0x%x 签名=%s", path,
         static_cast<long long>(timeout_ms), flags, sym_note ? sym_note : "?");

    // ── 输出文件在**父进程**先打开 ────────────────────────────────────────
    // 理由：打开文件要解析路径、可能要拿 libc 的锁；这些都不该在 fork 之后的
    // 子进程里做。父进程打开好，子进程直接继承 fd 用（fork 会继承，exec 才不继承）。
    int out_fd = open(path, O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC, 0600);
    if (out_fd < 0) {
        char reason[128];
        snprintf(reason, sizeof(reason), "open_failed_errno_%d", errno);
        return fail(reason);
    }

    // ── ① fork 前挂起父进程其它线程 ───────────────────────────────────────
    // ScopedSuspendAll 的 ctor/dtor 内部自己解析 Runtime::instance_ → thread_list_，
    // 调用方**不需要**传 Runtime，也不需要构造合法对象（实测反汇编确认：
    // ctor 只做"取 thread_list → 尾调 SuspendAll"；dtor 只做"取 thread_list → 尾调 ResumeAll"）。
    // ⚠️ 不要直接调 ThreadList::SuspendAll —— 它是成员函数，少传 this 会把 x0 当
    //    ThreadList 用，实测直接 SIGSEGV。
    // 只作占位：真正的状态在 ART 的 ThreadList 里，这个对象的内容没人读。
    char suspend_obj[64] = {0};
    bool parent_suspended = false;
    long long suspend_ms = 0;
    if ((flags & FLAG_PARENT_SUSPEND) != 0) {
        using ScopedSuspendCtor = void (*)(void *, const char *, bool);
        const long long t_suspend = now_ms();
        reinterpret_cast<ScopedSuspendCtor>(p_scoped_suspend)(suspend_obj, "oom-snapshot", false);
        suspend_ms = now_ms() - t_suspend;
        parent_suspended = true;
        // 挂起耗时是"本方案对 App 的直接影响"，必须可见：正常是毫秒级，
        // 若到秒级说明有线程卡在不可中断的 native 调用里，这条日志就是线索。
        if (suspend_ms >= 100) {
            LOGW("⚠️ 挂起全部线程耗时 %lldms（偏长，请查是否有线程停在长 native 调用）",
                 static_cast<long long>(suspend_ms));
        }
    }

    const long long t_fork_begin = now_ms();
    const pid_t pid = fork();
    if (pid < 0) {
        const int e = errno;
        // 失败也要把线程恢复回去，否则 App 直接死在我们手里
        if (parent_suspended) {
            using ScopedSuspendDtor = void (*)(void *);
            reinterpret_cast<ScopedSuspendDtor>(p_scoped_suspend_dtor)(suspend_obj);
        }
        close(out_fd);
        char reason[128];
        snprintf(reason, sizeof(reason), "fork_failed_errno_%d_%s", e, strerror(e));
        return fail(reason);
    }

    if (pid == 0) {
        // ══════════════════════════════════════════════════════════════════
        // 子进程：页表是 fork 那一瞬间的副本 —— 它看到的堆就是 OOM 现场的原样
        // ══════════════════════════════════════════════════════════════════

        // 自成进程组：父进程被系统杀掉时不牵连我们（OOM 时这条很关键）
        setpgid(0, 0);
        prctl(PR_SET_NAME, "oom-snapshot", 0, 0, 0);
        // ⚠️ 刻意**不**设 PR_SET_PDEATHSIG —— 设了就是"父死子必死"，见文件头【4】

        // 留一个自己的 fd 用于收尾时 fsync：DumpHeap 内部可能把传进去的 fd 关掉，
        // 所以 dup 一份自己握着（都是 syscall，async-signal-safe）。
        const int keep_fd = dup(out_fd);

        // ② 子进程里 SuspendAll/ResumeAll 变空操作
        // ⚠️ 补丁的返回值必须检查，且失败要**立刻退出并用专用 exit code 上报**。
        //    第一版忽略返回值、最后恒 _exit(0)：补丁失败会表现为子进程死锁 →
        //    父进程只看到 timeout_sigkilled，和"ART 变了导致本来就卡"完全混淆 ——
        //    而这两种情况的处置方式截然不同。回读校验 + 专用 exit code 才分得开。
        bool patch_ok = true;
        if ((flags & FLAG_CHILD_PATCH_SUSPEND) != 0) {
            patch_ok &= patch_entry_to_ret(p_suspend_all, "ThreadList::SuspendAll", true);
            patch_ok &= patch_entry_to_ret(p_resume_all, "ThreadList::ResumeAll", true);
        }
        // ③ 子进程里禁止 GC：我们要的是冻结的那一刻，不是"GC 之后"
        if ((flags & FLAG_CHILD_PATCH_GC) != 0) {
            patch_ok &= patch_entry_to_ret(p_gc_start, "Heap::StartGC", true);
            patch_ok &= patch_entry_to_ret(p_gc_finish, "Heap::FinishGC", true);
        }
        if (!patch_ok) {
            // 带着没打上的补丁去 dump 只会卡死，等超时纯属浪费时间：
            // 直接退出，让父进程得到 patch_failed 这个明确结论。
            child_log("ABORT: patch failed, exiting 10");
            _exit(10);
        }

        child_log("start writing hprof from forked snapshot");

        // ⚠️ 注意：本机（Android 12）实测 `art::hprof::DumpHeap` 返回 **void**
        //    （反汇编确认：函数末尾的 ret 路径不写 w0），所以**没法**检查它的返回值。
        //    这条留档是为了防止后人照抄别处的"if (!DumpHeap(...))"写法 —— 在这里
        //    读 w0 只会读到一个垃圾值。判定成功与否只能靠**文件字节数**。
        using DumpFn = void (*)(const char *, int, bool);
        reinterpret_cast<DumpFn>(p_dump)(path, out_fd, false);

        // 立刻落盘：父进程可能马上就被杀了，数据必须已经在文件里
        if (keep_fd >= 0) {
            fsync(keep_fd);
            close(keep_fd);
        }
        child_log("hprof written and fsynced");

        // ⚠️ 必须 _exit 而不是 exit：exit 会跑 atexit handler、flush 从父进程继承来的
        //    stdio 缓冲 —— 那些都可能拿锁或写坏父进程的数据（fork 后子进程的纪律）。
        _exit(0);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 父进程
    // ══════════════════════════════════════════════════════════════════════

    // ② 立刻恢复：fork 一返回就把线程放回去，挂起窗口只覆盖 fork 本身（实测 ~15ms）。
    //    晚了 App 就一直卡着。
    long long fork_ms = now_ms() - t_fork_begin;
    bool resumed = false;
    if (parent_suspended) {
        using ScopedSuspendDtor = void (*)(void *);
        const long long t_resume = now_ms();
        reinterpret_cast<ScopedSuspendDtor>(p_scoped_suspend_dtor)(suspend_obj);
        resumed = true;
        fork_ms = now_ms() - t_fork_begin;
        (void) t_resume;
    }

    // ── 有界等待子进程（超时就 SIGKILL，绝不留一个卡死的子进程吃内存）──────
    const long long deadline = now_ms() + static_cast<long long>(timeout_ms);
    int status = -1;
    bool finished = false;
    while (now_ms() < deadline) {
        const pid_t r = waitpid(pid, &status, WNOHANG);
        if (r == pid) {
            finished = true;
            break;
        }
        if (r < 0) break;   // ECHILD 等：不再重试
        struct timespec req = {0, 50L * 1000 * 1000};   // 50ms 轮询
        nanosleep(&req, nullptr);
    }

    bool killed_by_us = false;
    bool unreapable = false;
    if (!finished) {
        killed_by_us = true;
        kill(pid, SIGKILL);
        // 回收，避免留下僵尸进程。
        // ⚠️ 但**不能无限等**：SIGKILL 只是"不可被捕获"，进程仍可能停在不可中断的
        //    内核态（D 状态，例如卡在 I/O / fsync）而不退出。那样阻塞的 waitpid 会让
        //    调用线程**永久挂住**，这条通道就再也发不出第二次取证了。
        //    所以给回收也加一个上限；超时就放弃并如实报告 child_unreapable ——
        //    "有一个收不掉的僵尸"是必须暴露的事实，不是可以静默忽略的细节。
        const long long reap_deadline = now_ms() + 5000;
        bool reaped = false;
        while (now_ms() < reap_deadline) {
            const pid_t r2 = waitpid(pid, &status, WNOHANG);
            if (r2 == pid) {
                reaped = true;
                break;
            }
            if (r2 < 0) {
                reaped = true;   // ECHILD：已被回收（或本就不是我们的子进程）
                break;
            }
            struct timespec req = {0, 20L * 1000 * 1000};
            nanosleep(&req, nullptr);
        }
        if (!reaped) {
            unreapable = true;
            LOGW("⚠️ 子进程 %d 已 SIGKILL 但仍未退出（可能停在不可中断的 I/O）—— 放弃回收", pid);
        }
    }
    const long long total_ms = now_ms() - t_fork_begin + suspend_ms;

    if (out_fd >= 0) close(out_fd);

    // 子进程退出码 → 可读原因。10 是"补丁没打上"（见子进程里的 _exit(10)）——
    // 它必须和 timeout 区分开：超时是"子进程卡住了"，补丁失败是"我们根本没准备好就去 dump 了"。
    const char *how;
    bool child_patch_failed = false;
    if (unreapable) {
        // ⚠️ 这个分支必须放在最前面：没回收成功时 status 是**未定义**的，
        //    再去 WIFEXITED/WIFSIGNALED 判断等于读垃圾值（可能报出一个假结论）。
        how = "child_unreapable";
        unlink(path);
    } else if (killed_by_us) {
        how = "timeout_sigkilled";
        // 超时大概率意味着子进程卡在 ART 的某个锁上（对照实验里就见过），
        // 半个文件没有分析价值，直接删掉，别让它污染磁盘与后续解析。
        unlink(path);
    } else if (WIFEXITED(status)) {
        const int code = WEXITSTATUS(status);
        if (code == 0) {
            how = "exited_ok";
        } else if (code == 10) {
            how = "child_patch_failed";
            child_patch_failed = true;
            unlink(path);
        } else {
            how = "exited_nonzero";
        }
    } else if (WIFSIGNALED(status)) {
        how = "signaled";
    } else {
        how = "unknown";
    }

    struct stat st{};
    const long long size = (stat(path, &st) == 0) ? static_cast<long long>(st.st_size) : -1;

    const bool ok = (!killed_by_us) && (!unreapable) && WIFEXITED(status) &&
                    WEXITSTATUS(status) == 0 && size > 0;

    snprintf(result, sizeof(result),
             "ok=%d how=%s exit=%d size=%lld suspendMs=%lld forkMs=%lld totalMs=%lld "
             "flags=0x%x parentSuspended=%d resumed=%d childPid=%d",
             ok ? 1 : 0, how, (!unreapable && WIFEXITED(status)) ? WEXITSTATUS(status) : -1, size,
             suspend_ms, fork_ms, total_ms, flags, parent_suspended ? 1 : 0, resumed ? 1 : 0,
             static_cast<int>(pid));

    if (ok) {
        LOGI("forkDump 成功：%s", result);
    } else {
        // ⚠️ 失败必须打日志且说清"哪一步"：这类方案最容易出现"看起来成功了、
        //    其实文件是空的"，所以 size 与 how 都进返回值。
        LOGE("forkDump 失败：%s", result);
        if (child_patch_failed) {
            LOGE("  原因明确：**子进程补丁未生效**（回读校验失败）—— 不是超时、不是 ART 卡住。");
            LOGE("  含义：该 ROM 的这个函数入口无法被改成 ret（可能被保护/是 ABI 垫片）。");
            LOGE("  处置：本机应视为**不可用**，不要当成'偶发超时'重试。");
        }
    }

    env->ReleaseStringUTFChars(jpath, path);
    return env->NewStringUTF(result);
}
