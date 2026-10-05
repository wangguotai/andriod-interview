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
