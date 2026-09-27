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
 */
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <string>
#include <atomic>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <elf.h>
#include <link.h>

#define LOG_TAG "ThreadHook"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

int (*original_pthread_create)(pthread_t*, const pthread_attr_t*,
                               void* (*)(void*), void*) = nullptr;

std::atomic<bool> g_hooked{false};

JavaVM* g_jvm = nullptr;
jclass g_callback_class = nullptr;
jmethodID g_on_thread_created = nullptr;

/** 只在 Hook 点做极轻量记录，绝不在这里抓全量堆栈 */
void reportToJava(const char* thread_name) {
    if (g_jvm == nullptr || g_callback_class == nullptr || g_on_thread_created == nullptr) {
        return;
    }
    JNIEnv* env = nullptr;
    bool attached = false;

    int status = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    } else if (status != JNI_OK) {
        return;
    }

    if (env != nullptr) {
        jstring jname = env->NewStringUTF(thread_name != nullptr ? thread_name : "unknown");
        if (jname != nullptr) {
            env->CallStaticVoidMethod(g_callback_class, g_on_thread_created, jname);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
            env->DeleteLocalRef(jname);
        }
    }

    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

int hooked_pthread_create(pthread_t* thread, const pthread_attr_t* attr,
                          void* (*start_routine)(void*), void* arg) {
    // 先记录，再透传。Hook 点绝不做重活，否则会拖慢线程创建本身。
    reportToJava("native-thread");

    if (original_pthread_create != nullptr) {
        return original_pthread_create(thread, attr, start_routine, arg);
    }
    return -1;
}

// ───────────────────────────────────────────────
// 模块定位：用 dl_iterate_phdr 找目标 so（libart.so 无法 dlopen）
// ───────────────────────────────────────────────

struct TargetModule {
    uintptr_t base = 0;
    const ElfW(Phdr)* phdr = nullptr;
    size_t phnum = 0;
    const char* full_name = nullptr;
    const char* name_part = nullptr;
    bool found = false;
};

int findModuleCallback(struct dl_phdr_info* info, size_t /*size*/, void* data) {
    auto* target = static_cast<TargetModule*>(data);
    if (info->dlpi_name == nullptr || info->dlpi_name[0] == '\0') {
        return 0; // 主程序自身，跳过
    }
    if (strstr(info->dlpi_name, target->name_part) != nullptr) {
        target->base = info->dlpi_addr;
        target->phdr = info->dlpi_phdr;
        target->phnum = info->dlpi_phnum;
        target->full_name = info->dlpi_name;
        target->found = true;
        return 1; // 找到即停止遍历
    }
    return 0;
}

/**
 * 在目标模块的 GOT 表中查找并替换 pthread_create 的地址。
 *
 * 原理：PLT/GOT 机制下，对外部函数的调用先经 PLT 跳到 GOT 表项取地址。
 * 改写该表项即可重定向调用，无需修改任何指令 → 兼容性最好。
 */
bool hookGotEntry(const TargetModule& mod, const char* symbol_name,
                  void* replacement, void** old_value) {
    const auto base = static_cast<uintptr_t>(mod.base);

    // 1. 定位 PT_DYNAMIC —— 必须用 p_vaddr + base，不是 p_offset！
    //    （在非首个 PT_LOAD 段中 p_offset != p_vaddr，用错会读到垃圾内存并 SIGSEGV）
    const ElfW(Dyn)* dyn = nullptr;
    for (size_t i = 0; i < mod.phnum; ++i) {
        if (mod.phdr[i].p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<const ElfW(Dyn)*>(base + mod.phdr[i].p_vaddr);
            break;
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
    const auto toAbs = [base](ElfW(Addr) v) -> uintptr_t {
        return (v < base) ? (base + v) : static_cast<uintptr_t>(v);
    };

    const char* strtab = nullptr;
    const ElfW(Sym)* symtab = nullptr;
    void* jmprel = nullptr;
    size_t pltrelsz = 0;
    ElfW(Sxword) pltrel_type = DT_RELA;
    int dyn_count = 0;

    for (const ElfW(Dyn)* d = dyn; d->d_tag != DT_NULL; ++d) {
        ++dyn_count;
        if (dyn_count > 4096) {
            ALOGE("[%s] DT_NULL 未在合理范围内出现，疑似 PT_DYNAMIC 解析错误", mod.name_part);
            return false;
        }
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
            case DT_PLTREL: pltrel_type = d->d_un.d_val; break;
            default: break;
        }
    }

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
    if (pltrelsz > (64u * 1024u * 1024u)) {
        ALOGE("[%s] PLTRELSZ 异常偏大 (%zu)，疑似解析错误", mod.name_part, pltrelsz);
        return false;
    }

    // 4. 遍历重定位表，找到 pthread_create 对应的 GOT 表项
    //    注意 REL 与 RELA 结构体大小不同（REL 无 addend），用错会错位
    const bool is_rela = (pltrel_type == DT_RELA);

    for (size_t offset = 0; offset < pltrelsz; ) {
        uintptr_t r_offset = 0;
        uint32_t sym_index = 0;

        if (is_rela) {
            if (offset + sizeof(ElfW(Rela)) > pltrelsz) break;
            auto* r = reinterpret_cast<const ElfW(Rela)*>(
                    reinterpret_cast<uintptr_t>(jmprel) + offset);
            offset += sizeof(ElfW(Rela));
#if defined(__LP64__)
            sym_index = ELF64_R_SYM(r->r_info);
#else
            sym_index = ELF32_R_SYM(r->r_info);
#endif
            r_offset = r->r_offset;
        } else {
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

        const char* name = strtab + symtab[sym_index].st_name;
        if (strcmp(name, symbol_name) != 0) {
            continue;
        }

        auto* slot = reinterpret_cast<void**>(base + r_offset);
        void* current = *slot;

        ALOGD("[%s] 命中 GOT 表项 %s @ %p (当前值=%p)", mod.name_part, symbol_name,
              static_cast<void*>(slot), current);

        *old_value = current;

        // 5. 改写 GOT：该段通常只读且受 RELRO 保护，需先放开写权限
        const size_t page = static_cast<size_t>(getpagesize());
        auto page_start = reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(slot) & ~(page - 1));
        if (mprotect(page_start, page, PROT_READ | PROT_WRITE) != 0) {
            ALOGE("[%s] mprotect 失败（可能受 RELRO/CFI 保护），放弃改写", mod.name_part);
            return false;
        }
        *slot = replacement;
        mprotect(page_start, page, PROT_READ);

        ALOGD("[%s] GOT Hook 成功: %s", mod.name_part, symbol_name);
        return true;
    }

    ALOGW("[%s] GOT 表中未找到符号: %s", mod.name_part, symbol_name);
    return false;
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_interview_thread_NativeThreadHook_installNative(JNIEnv* env, jclass clazz) {
    if (g_hooked.exchange(true)) {
        ALOGD("Hook 已安装，跳过");
        return;
    }

    env->GetJavaVM(&g_jvm);
    jclass local = env->FindClass("com/interview/thread/NativeThreadHook");
    if (local == nullptr) {
        ALOGE("找不到回调类");
        env->ExceptionClear();
        g_hooked.store(false);
        return;
    }
    g_callback_class = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_on_thread_created = env->GetStaticMethodID(
            g_callback_class, "onThreadCreatedFromNative", "(Ljava/lang/String;)V");
    if (g_on_thread_created == nullptr) {
        ALOGE("找不到回调方法");
        env->ExceptionClear();
        g_hooked.store(false);
        return;
    }

    // 优先 hook libart.so（Java 线程创建的真实调用方）
    // 失败则退回 libc.so（至少能覆盖 libc 内部发起的一部分线程创建）
    const char* candidates[] = {"libart.so", "libc.so"};
    for (const char* candidate : candidates) {
        TargetModule mod{};
        mod.name_part = candidate;
        dl_iterate_phdr(findModuleCallback, &mod);

        if (!mod.found) {
            ALOGW("未在已加载模块中找到 %s", candidate);
            continue;
        }
        ALOGD("定位到 %s @ base=%p (%s)", candidate,
              reinterpret_cast<void*>(mod.base), mod.full_name);

        void* old = nullptr;
        if (hookGotEntry(mod, "pthread_create",
                         reinterpret_cast<void*>(hooked_pthread_create), &old)) {
            original_pthread_create =
                    reinterpret_cast<int (*)(pthread_t*, const pthread_attr_t*,
                                             void* (*)(void*), void*)>(old);
            ALOGD("pthread_create Hook 安装完成（模块=%s，原始地址=%p）", candidate, old);
            return;
        }
        ALOGW("在 %s 上 Hook 失败，尝试下一个候选模块", candidate);
    }

    ALOGE("所有候选模块均 Hook 失败——该 ROM 可能开启了 CFI/RELRO/FakeGOT 保护");
    g_hooked.store(false);
}

extern "C" JNIEXPORT void JNICALL
Java_com_interview_thread_NativeThreadHook_uninstallNative(JNIEnv* env, jclass clazz) {
    g_hooked.store(false);
    ALOGD("Hook 标记已清除（GOT 表项未回滚，进程重启后复位）");
}
