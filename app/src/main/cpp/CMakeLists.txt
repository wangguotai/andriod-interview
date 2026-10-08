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
