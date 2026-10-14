cmake_minimum_required(VERSION 3.16)

project(
    cpp_learn
    VERSION 1.0.0
    DESCRIPTION "A standard C++17 project template for learning"
    LANGUAGES CXX
)

# ---------------------------------------------------------------------------
# Global settings
# ---------------------------------------------------------------------------
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)          # use -std=c++17, not -std=gnu++17

set(CMAKE_EXPORT_COMPILE_COMMANDS ON)  # emits build/compile_commands.json
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Debug CACHE STRING "Build type" FORCE)
    set_property(CACHE CMAKE_BUILD_TYPE PROPERTY STRINGS Debug Release RelWithDebInfo MinSizeRel)
endif()

# Common warnings, applied only to targets that link `cpplearn_warnings`.
add_library(cpplearn_warnings INTERFACE)
if(MSVC)
    target_compile_options(cpplearn_warnings INTERFACE /W4 /permissive-)
else()
    target_compile_options(cpplearn_warnings INTERFACE -Wall -Wextra -Wpedantic -Wshadow -Wnon-virtual-dtor)
endif()

# ---------------------------------------------------------------------------
# Library
# ---------------------------------------------------------------------------
add_library(cpplearn
    src/math_utils.cpp
    src/string_utils.cpp
    src/string_utils_impl.h
)
add_library(cpplearn::cpplearn ALIAS cpplearn)

target_include_directories(cpplearn
    PUBLIC  "${CMAKE_CURRENT_SOURCE_DIR}/include"
    PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src"
)
target_compile_features(cpplearn PUBLIC cxx_std_17)
target_link_libraries(cpplearn PRIVATE cpplearn_warnings)

# ---------------------------------------------------------------------------
# Executable
# ---------------------------------------------------------------------------
add_executable(cpp_learn app/main.cpp)
target_link_libraries(cpp_learn PRIVATE cpplearn)
target_link_libraries(cpp_learn PRIVATE cpplearn_warnings)

# ---------------------------------------------------------------------------
# Tests & tooling (opt-in)
# ---------------------------------------------------------------------------
option(CPPLEARN_BUILD_TESTS "Build unit tests" ON)
option(CPPLEARN_ENABLE_CLANG_TIDY "Run clang-tidy while compiling" OFF)
option(CPPLEARN_ENABLE_SANITIZERS "Enable Address/UB sanitizers (Debug only)" OFF)

if(CPPLEARN_ENABLE_CLANG_TIDY)
    find_program(CLANG_TIDY_EXE NAMES clang-tidy)
    if(CLANG_TIDY_EXE)
        set(CMAKE_CXX_CLANG_TIDY "${CLANG_TIDY_EXE}")
    else()
        message(WARNING "clang-tidy not found; CPPLEARN_ENABLE_CLANG_TIDY ignored")
    endif()
endif()

if(CPPLEARN_ENABLE_SANITIZERS AND NOT MSVC)
    add_compile_options(-fsanitize=address,undefined -fno-omit-frame-pointer)
    add_link_options(-fsanitize=address,undefined)
endif()

if(CPPLEARN_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
message(STATUS "cpp_learn ${PROJECT_VERSION}")
message(STATUS "  C++ standard : ${CMAKE_CXX_STANDARD}")
message(STATUS "  Build type   : ${CMAKE_BUILD_TYPE}")
message(STATUS "  Build tests  : ${CPPLEARN_BUILD_TESTS}")
