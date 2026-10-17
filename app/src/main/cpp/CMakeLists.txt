cmake_minimum_required(VERSION 3.22.1)
project("threadhook")

# 先跑 Rust 库的构建规则（找不到工具链会自我降级为警告，不影响下面的 C++ 库）。
# 该文件提供 add_rust_android_library()，每个 Rust crate 调一次。
include(${CMAKE_CURRENT_SOURCE_DIR}/rust_build.cmake)

add_library(threadhook SHARED thread_hook.cpp)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(threadhook ${log-lib} ${android-lib} dl)

# ─────────────────────────────────────────────────────────────────────────────
# anrsignal：通道 4 —— 拦截 SIGQUIT（ANR 信号）
#
# 与 threadhook 分开成独立 ABI 是有意的：
#   · threadhook 是「线程治理」的 hook，装错会改变线程创建行为；
#   · anrsignal 是「ANR 取证」的 hook，装错会拖慢系统 dump trace（最长 20s）。
#   两者失败后果不同，共用一个 .so 会让 Java 侧的优雅降级变得模糊
#   （loadLibrary 成功 ≠ 两者都能用）。分开后任一侧失败都能独立降级。
# ─────────────────────────────────────────────────────────────────────────────
add_library(anrsignal SHARED anr_signal_hook.cpp)
target_link_libraries(anrsignal ${log-lib})

# ─────────────────────────────────────────────────────────────────────────────
# memtrace：Native 内存分配归因（hook malloc/free/calloc/realloc + 栈回溯）
#
# 与上两个库分开成独立 ABI 的理由同样是"失败后果不同"：
#   · threadhook 装错会改变线程创建行为；
#   · anrsignal 装错会拖慢系统 dump trace（最长 20s）；
#   · memtrace 装错会挂在**全局内存分配热路径**上 —— 三者里后果最严重的一个
#     （所有线程的所有分配都要过我们），所以它必须能被独立关掉/独立降级。
#
# 依赖 bytehook（字节跳动的 PLT/GOT hook 框架）：它是**预编译 .so**，
# 由 prefab 从 AAR 里解出来，故用 find_package + bytehook::bytehook 目标名。
#
# ⚠️ 版本卡点（实测，不是随手挑）：bytehook 1.1.2 的 AAR metadata 写了
#    minCompileSdk=37，本仓库 compileSdk=34 ⇒ 依赖解析直接失败，
#    且报的错与内存毫无关系（"requires compileSdk 37"）。实测 1.0.10 的
#    minCompileSdk=1，可正常使用，且已带 prefab + 四个 ABI。
# ─────────────────────────────────────────────────────────────────────────────
find_package(bytehook REQUIRED CONFIG)

add_library(memtrace SHARED memtrace.cpp)
target_link_libraries(memtrace ${log-lib} dl bytehook::bytehook)

# ─────────────────────────────────────────────────────────────────────────────
# oomsnapshot：JVM OOM 现场取证 —— fork 出子进程，由子进程 dump 父进程的堆快照
#
# 单独成一个 .so 的理由和其它第三方库注册处同一个逻辑 —— **失败后果不同**：
#   · threadhook   装错会改变线程创建行为；
#   · anrsignal    装错会拖慢系统 dump trace；
#   · memtrace     装错会挂在全局内存分配热路径上；
#   · oomsnapshot  装错会（a）在子进程里改到 ART 的代码段、
#                        （b）把父进程其它线程挂在 SuspendAll 上不回来
#                   —— 后者会让**整个 App 冻结**：
#                   它动的是 ART 的运行时同步原语，而不是只读数据。
#
# 因此它必须能独立 loadLibrary 失败、独立降级，且安装/触发都要能在 logcat 单独观察。
#
# ⚠️ 它**不需要**额外依赖：符号表是自己走 ELF 结构查的（dl_iterate_phdr + DT_SYMTAB），
#    库本身只用 liblog + libc + libdl。这也正是它能工作的前提 ——
#    应用 namespace 里 dlopen 不到 libart.so（见 oom_snapshot.cpp 文件头【0】）。
# ─────────────────────────────────────────────────────────────────────────────
add_library(oomsnapshot SHARED oom_snapshot.cpp)
target_link_libraries(oomsnapshot ${log-lib} dl)

# ─────────────────────────────────────────────────────────────────────────────
# Rust 库注册
#
#   1) imagepipeline：图像流水线（纯算法 kernel + JNI 绑定层）
#   2) netlab：传输实验（纯逻辑 kernel + JNI 绑定层）
#
# netlab-android 额外声明 netlab 为增量依赖：改 netlab 的 src 也要触发重编，
# 否则会出现「改了 kernel 但 .so 没更新」的假绿 —— 这正是 GLOB 依赖存在的意义。
# 注意：netlab-android 的 cargo 构建会**一起**把 netlab 编好（它是 path 依赖），
# 所以不需要单独为 netlab 这个 rlib 注册一个 CMake 目标。
# ─────────────────────────────────────────────────────────────────────────────
add_rust_android_library(imagepipeline_android rust/android imagepipeline_android rust/imagepipeline)
add_rust_android_library(netlab_android rust/netlab-android netlab_android rust/netlab)
