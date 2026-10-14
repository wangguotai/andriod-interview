# cpp_learn

一个标准的 C++17 学习工程骨架：静态库 + 可执行程序 + GoogleTest 单元测试，开箱即用的 CMake 构建。

适合用来练习「真实项目长什么样」——头文件与实现分离、私有头、命名空间、构建选项、测试与 CI。

## 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| CMake | >= 3.16（推荐 3.21+ 以使用 `CMakePresets.json`） | 构建系统 |
| C++ 编译器 | 支持 C++17 | GCC 9+ / Clang 10+ / MSVC 19.20+ |
| Ninja | 可选 | 比 Make 更快，preset 默认使用 |
| GoogleTest | 自动获取 | 默认通过 `FetchContent` 拉取 v1.15.2，无需手动安装 |

## 快速开始

```bash
# 方式一：使用 CMake Presets（推荐）
cmake --preset debug
cmake --build --preset debug
ctest --preset debug --output-on-failure
./build/debug/bin/cpp_learn

# 方式二：手动指定参数
cmake -S . -B build -DCMAKE_BUILD_TYPE=Debug
cmake --build build -j
ctest --test-dir build --output-on-failure
./build/bin/cpp_learn
```

> 首次配置需要联网下载 GoogleTest。纯离线环境请安装 GoogleTest 后加上
> `-DCPPLEARN_USE_SYSTEM_GTEST=ON`。

## 目录结构

```
.
├── CMakeLists.txt              # 顶层构建配置
├── CMakePresets.json           # debug / release / sanitized 预设
├── .clang-format               # 代码风格（Google 基础，4 空格缩进）
├── .github/workflows/ci.yml    # 三平台 CI
├── include/cpplearn/           # 公开头文件（对外 API）
│   ├── math_utils.h
│   └── string_utils.h
├── src/                        # 实现 + 私有头文件
│   ├── math_utils.cpp
│   ├── string_utils.cpp
│   └── string_utils_impl.h     # 仅在库内部使用，不对外暴露
├── app/                        # 可执行程序入口
│   └── main.cpp
└── tests/                      # 单元测试
    ├── CMakeLists.txt
    ├── math_utils_test.cpp
    └── string_utils_test.cpp
```

**头文件摆放约定**

- `include/` 放**公开**头文件，库的使用者只能看到这里。
- `src/` 放实现文件和**私有**头文件（如 `string_utils_impl.h`），改动它们不影响使用者。
- 公开头文件用 `#pragma once` + 相对路径包含，例如 `#include "cpplearn/math_utils.h"`。

## 构建选项

| 选项 | 默认 | 作用 |
| --- | --- | --- |
| `CPPLEARN_BUILD_TESTS` | `ON` | 是否构建单元测试 |
| `CPPLEARN_USE_SYSTEM_GTEST` | `OFF` | 使用系统已安装的 GoogleTest 而非联网拉取 |
| `CPPLEARN_GTEST_REPOSITORY` | Gitee 镜像 | GoogleTest 拉取源，可改回 `https://github.com/google/googletest.git` |
| `CPPLEARN_GTEST_TAG` | `v1.15.2` | GoogleTest 版本标签 |
| `CPPLEARN_ENABLE_SANITIZERS` | `OFF` | 开启 AddressSanitizer / UndefinedBehaviorSanitizer |
| `CPPLEARN_ENABLE_CLANG_TIDY` | `OFF` | 编译时运行 clang-tidy（需已安装） |

> **关于 GoogleTest 拉取源**：默认使用 Gitee 镜像，因为部分网络环境下
> `github.com` 不可达（首次配置会因此失败）。能直连 GitHub 时建议改回官方源：
> `cmake -S . -B build -DCPPLEARN_GTEST_REPOSITORY=https://github.com/google/googletest.git`

> **关于 Sanitizer**：`debug-sanitized` 预设在本仓库已验证可正常**编译**，
> 但部分 macOS 环境（ASan shadow memory 初始化卡住）会导致 sanitizer 程序
> 启动即挂起。如果遇到该现象，请改用普通 `debug` 预设，或确认本机
> `clang++ -fsanitize=address` 能否运行一个最小程序。


## 目标（CMake Targets）

| 目标 | 类型 | 说明 |
| --- | --- | --- |
| `cpplearn`（别名 `cpplearn::cpplearn`） | 静态库 | 核心代码 |
| `cpp_learn` | 可执行文件 | 演示程序，输出到 `build/bin/` |
| `cpplearn_tests` | 可执行文件 | GoogleTest 单元测试 |
| `cpplearn_warnings` | INTERFACE | 统一的编译告警开关 |

## 练习建议

1. 在 `include/cpplearn/` + `src/` 中新增一个模块，并在 `CMakeLists.txt` 的库源文件列表里注册。
2. 为新模块写一个 `tests/xxx_test.cpp`，加入 `tests/CMakeLists.txt` 的源文件列表。
3. 跑一遍 `clang-format -i` 保持风格统一。
4. 用 `debug-sanitized` 预设验证代码没有内存 / 未定义行为问题：

```bash
cmake --preset debug-sanitized
cmake --build --preset debug-sanitized
```

## 学习路线

| 主题 | 建议练习 |
| --- | --- |
| 基础语法与函数 | 在 `math_utils` 中增加 `power`、`clamp` 等工具函数 |
| `std::string` / `string_view` | 给 `string_utils` 加 `to_upper`、`reverse` |
| 容器与算法 | 用 `<algorithm>` 重写 `primes_up_to` 中的循环 |
| 类与 RAII | 新增一个 `Timer` 类，析构时打印耗时 |
| 模板与泛型 | 把 `average` 改造成接受任意数值类型的模板函数 |
| 智能指针 | 用 `std::unique_ptr` 管理一个资源类 |
| 移动语义 | 给自定义类补上移动构造 / 移动赋值 |
| 并发 | 用 `std::thread` + `std::mutex` 并行计算素数 |
| 异常安全 | 为库函数补充异常用例测试 |

## 代码风格

```bash
# 格式化单个文件
clang-format -i include/cpplearn/math_utils.h src/math_utils.cpp

# 格式化全部源文件（需已安装 clang-format）
find include src app tests -name '*.cpp' -o -name '*.h' | xargs clang-format -i
```

主要规则：C++17、100 列宽、4 空格缩进、左置指针（`int* p`）。

头文件包含顺序统一为三段，段间空一行：

```cpp
#include <vector>            // 1. C++ 标准库

#include <gtest/gtest.h>     // 2. 第三方库

#include "cpplearn/math_utils.h"  // 3. 本项目头文件
```
