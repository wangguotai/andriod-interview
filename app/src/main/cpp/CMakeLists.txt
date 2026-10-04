cmake_minimum_required(VERSION 3.22.1)
project("threadhook")

# 先跑 Rust 库的构建规则（找不到工具链会自我降级为警告，不影响下面的 C++ 库）
include(${CMAKE_CURRENT_SOURCE_DIR}/rust_build.cmake)

add_library(threadhook SHARED thread_hook.cpp)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(threadhook ${log-lib} ${android-lib} dl)
