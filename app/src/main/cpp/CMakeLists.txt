cmake_minimum_required(VERSION 3.22.1)
project("threadhook")

add_library(threadhook SHARED thread_hook.cpp)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(threadhook ${log-lib} ${android-lib} dl)
