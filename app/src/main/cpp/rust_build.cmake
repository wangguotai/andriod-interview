# ─────────────────────────────────────────────────────────────────────────────
# Rust 库接入（CMake 驱动 cargo）—— 通用版：支持多个 crate
#
# 为什么不用 corrosion / cargo-ndk：
#   corrosion 把 crate 暴露成 SHARED IMPORTED 目标，而 AGP 的 externalNativeBuild
#   只打包「本 CMake 构建树里产出的」共享库，IMPORTED 目标不进 APK。
#   cargo-ndk 直出 jniLibs 则绕过了本仓库既有的 externalNativeBuild 结构。
#   这里选择最直白的一条：add_custom_command 调 cargo，把 .so 产到
#   ${CMAKE_CURRENT_BINARY_DIR}（即 AGP 的 <abi> 输出目录，和 libthreadhook.so 同级）。
#
# 约定：找不到 Rust 工具链时「警告 + 跳过」，而不是让整个 native 构建失败。
#   这样其它 ABI / 其它 native 库不受影响；Kotlin 侧对缺失的库做降级（见
#   ImagePipelineBridge / NetLabBridge，捕获 UnsatisfiedLinkError）。
#
# ─── 本文件的两次演进（保留历史，避免后人重蹈）───
#   1) 最初只为 imagepipeline 单库写死；
#   2) 接入 netlab 时抽出「工具链探测（一次）」+「库注册（每 crate 一次）」，
#      新增第三个 crate 时只需在 CMakeLists 里加一行，不必复制整份逻辑。
# ─────────────────────────────────────────────────────────────────────────────

# ─────────────────────────────────────────────
# 工具链探测（include 时执行一次）
# ─────────────────────────────────────────────

# Rust 工具链位置：优先取 CMake 变量，其次取环境变量，最后回落到 ~/.cargo。
set(RUSTUP_HOME_VALUE "$ENV{RUSTUP_HOME}" CACHE PATH "rustup home（含 toolchains/）")
set(CARGO_HOME_VALUE "$ENV{CARGO_HOME}" CACHE PATH "cargo home（含 bin/cargo）")

if(NOT RUSTUP_HOME_VALUE)
  set(RUSTUP_HOME_VALUE "$ENV{HOME}/.rustup")
endif()
if(NOT CARGO_HOME_VALUE)
  set(CARGO_HOME_VALUE "$ENV{HOME}/.cargo")
endif()

set(RUST_CARGO "${CARGO_HOME_VALUE}/bin/cargo")
if(NOT EXISTS "${RUST_CARGO}")
  set(RUST_CARGO "$ENV{HOME}/.cargo/bin/cargo")
endif()

# 当前 ABI → Rust target triple / NDK 链接器名
if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(RUST_TARGET "aarch64-linux-android")
  set(RUST_LINKER_STEM "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
  set(RUST_TARGET "armv7-linux-androideabi")
  set(RUST_LINKER_STEM "armv7a-linux-androideabi")
elseif(ANDROID_ABI STREQUAL "x86")
  set(RUST_TARGET "i686-linux-android")
  set(RUST_LINKER_STEM "i686-linux-android")
elseif(ANDROID_ABI STREQUAL "x86_64")
  set(RUST_TARGET "x86_64-linux-android")
  set(RUST_LINKER_STEM "x86_64-linux-android")
else()
  message(WARNING "[rust] 未知 ANDROID_ABI=${ANDROID_ABI}，跳过 Rust 库构建")
  set(RUST_TARGET "")
endif()

# API level：AGP 会传 ANDROID_PLATFORM（形如 android-24）。
# 取不到就回落到 minSdk 的 24 —— 与 app/build.gradle.kts 的 minSdk 一致。
set(RUST_API_LEVEL "24")
if(ANDROID_PLATFORM MATCHES "android-([0-9]+)")
  set(RUST_API_LEVEL "${CMAKE_MATCH_1}")
endif()

# NDK 工具链 bin 目录：从 CMake 自己的编译器路径反推，避免再硬编码 NDK 版本
set(RUST_TOOLCHAIN_OK OFF)
if(RUST_TARGET)
  get_filename_component(NDK_BIN_DIR "${CMAKE_C_COMPILER}" DIRECTORY)
  set(RUST_LINKER "${NDK_BIN_DIR}/${RUST_LINKER_STEM}${RUST_API_LEVEL}-clang")
  if(NOT EXISTS "${RUST_LINKER}")
    message(WARNING "[rust] 找不到 NDK 链接器 ${RUST_LINKER}，跳过 Rust 库构建")
    set(RUST_TARGET "")
  endif()
endif()

# cargo 是否可执行？
if(RUST_TARGET)
  execute_process(
    COMMAND "${CMAKE_COMMAND}" -E env
            "RUSTUP_HOME=${RUSTUP_HOME_VALUE}"
            "CARGO_HOME=${CARGO_HOME_VALUE}"
            "${RUST_CARGO}" --version
    OUTPUT_VARIABLE RUST_CARGO_VERSION
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET
    RESULT_VARIABLE RUST_CARGO_RESULT
  )
  if(RUST_CARGO_RESULT EQUAL 0)
    set(RUST_TOOLCHAIN_OK ON)
  else()
    message(WARNING
      "[rust] 无法执行 cargo（${RUST_CARGO}）。\n"
      "       请确认 Rust 已安装，或显式传入 -DRUSTUP_HOME_VALUE=... -DCARGO_HOME_VALUE=...\n"
      "       本机为 /Volumes/ext/Rust/{rustup,cargo}。跳过 Rust 库构建。")
  endif()
endif()

# 该 target 是否已 rustup target add？没有就跳过（提示怎么补），不阻塞构建。
if(RUST_TOOLCHAIN_OK)
  execute_process(
    COMMAND "${CMAKE_COMMAND}" -E env
            "RUSTUP_HOME=${RUSTUP_HOME_VALUE}"
            "CARGO_HOME=${CARGO_HOME_VALUE}"
            "${CARGO_HOME_VALUE}/bin/rustup" target list --installed
    OUTPUT_VARIABLE RUST_INSTALLED_TARGETS
    ERROR_QUIET
    RESULT_VARIABLE RUST_TARGETS_RESULT
  )
  if(RUST_TARGETS_RESULT EQUAL 0)
    string(FIND "${RUST_INSTALLED_TARGETS}" "${RUST_TARGET}" RUST_TARGET_POS)
    if(RUST_TARGET_POS EQUAL -1)
      message(WARNING
        "[rust] 未安装 target ${RUST_TARGET}（ABI ${ANDROID_ABI}），跳过 Rust 库构建。\n"
        "       补齐：rustup target add ${RUST_TARGET}\n"
        "       国内网络可加 RUSTUP_DIST_SERVER=https://mirrors.ustc.edu.cn/rust-static")
      set(RUST_TOOLCHAIN_OK OFF)
    endif()
  endif()
endif()

if(RUST_TOOLCHAIN_OK)
  string(REPLACE "-" "_" RUST_TARGET_UNDERSCORE "${RUST_TARGET}")
  string(TOUPPER "${RUST_TARGET_UNDERSCORE}" RUST_TARGET_UPPER)
  message(STATUS "[rust] ABI=${ANDROID_ABI} → target=${RUST_TARGET}, linker=${RUST_LINKER}")
  message(STATUS "[rust] ${RUST_CARGO_VERSION}")
endif()

# 本 include 处 CMAKE_CURRENT_SOURCE_DIR = app/src/main/cpp → 上溯 4 级到仓库根。
# （踩过：写成 3 级会静默指到不存在的路径，cargo 报 "manifest path does not exist"，
#   而 CMake 配置阶段因为只是拼字符串、不校验存在性，完全不会提前报错。）
set(RUST_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../..")

# ─────────────────────────────────────────────
# add_rust_android_library(<cmake_target> <crate_dir> <lib_name> [extra_crate_dir...])
#
#   <cmake_target>  CMake 目标名（任意）
#   <crate_dir>     相对仓库根的 crate 目录，如 rust/android
#   <lib_name>      Rust 产物名，决定 lib<lib_name>.so 与 System.loadLibrary 的参数
#   [extra...]      额外的增量依赖 crate 目录（如 netlab-android 依赖 netlab）
# ─────────────────────────────────────────────

function(add_rust_android_library CMAKE_TARGET CRATE_DIR LIB_NAME)
  if(NOT RUST_TOOLCHAIN_OK)
    message(STATUS "[rust] 跳过 ${LIB_NAME}（工具链不可用）")
    return()
  endif()

  set(MANIFEST "${RUST_REPO_ROOT}/${CRATE_DIR}/Cargo.toml")
  if(NOT EXISTS "${MANIFEST}")
    message(WARNING "[rust] 找不到 ${MANIFEST}，跳过 ${LIB_NAME}")
    return()
  endif()

  # workspace 布局下，CARGO_TARGET_DIR 默认落在 workspace 根的 target/，
  # 而不是成员 crate 的 target/。写死成员目录会「编译成功却找不到产物」。
  set(SO_SRC "${RUST_REPO_ROOT}/rust/target/${RUST_TARGET}/release/lib${LIB_NAME}.so")

  # 增量依赖：本 crate 源码 + Cargo.toml + workspace 的 Cargo.toml/lock，
  # 外加调用方声明的额外依赖 crate。
  # 用 GLOB_RECURSE + CONFIGURE_DEPENDS 而非手写列表，避免新增文件后忘更新。
  file(GLOB_RECURSE SOURCES CONFIGURE_DEPENDS
    "${RUST_REPO_ROOT}/${CRATE_DIR}/src/*.rs"
    "${RUST_REPO_ROOT}/${CRATE_DIR}/Cargo.toml"
  )
  foreach(EXTRA IN LISTS ARGN)
    file(GLOB_RECURSE EXTRA_SOURCES CONFIGURE_DEPENDS
      "${RUST_REPO_ROOT}/${EXTRA}/src/*.rs"
      "${RUST_REPO_ROOT}/${EXTRA}/Cargo.toml"
    )
    list(APPEND SOURCES ${EXTRA_SOURCES})
  endforeach()
  list(APPEND SOURCES
    "${RUST_REPO_ROOT}/rust/Cargo.toml"
    "${RUST_REPO_ROOT}/rust/Cargo.lock"
  )

  # ─────────────────────────────────────────────────────────────────────────
  # 为什么必须建成一个**真实的 SHARED 目标**，而不是 add_custom_target
  #
  # AGP 只从 CMake codemodel 里读「库类型」目标（SHARED_LIBRARY 等）来生成
  # android_gradle_build_mini.json 的 `output` 字段；纯 custom target 是 UTILITY，
  # AGP 只记名字、不给 output，mergeDebugNativeLibs 就不会把它打进 APK。
  # 而 IMPORTED 目标干脆不进 codemodel（默认 EXPORT_PROPERTIES 不含 IMPORTED）。
  # 两条路都试过，结论一致：**只能是本工程内建的 SHARED 目标**。
  #
  # 但 cargo 才是真正的生产者，CMake 不能直接「产出」它的结果。于是：
  #   1. 用一份 CMake 生成的占位源建 SHARED 目标（产物名 lib<lib_name>.so）
  #   2. 让占位源依赖 cargo 的产物 → 任一 crate 源码变动都会重新生成占位源 → 重新链接
  #   3. POST_BUILD 用解析出的真实 .so 覆盖 CMake 链接出的那份
  #
  # 唯一瑕疵：若某次 cargo 构建失败，POST_BUILD 不执行，APK 里会静静躺着
  # CMake 链接出来的空库。所以 copy_rust_so.cmake 里显式校验 cargo 产物存在，
  # 不存在就 FATAL_ERROR，把假绿掐掉。
  # ─────────────────────────────────────────────────────────────────────────
  set(STUB_SRC "${CMAKE_CURRENT_BINARY_DIR}/${LIB_NAME}_stub.c")
  add_custom_command(
    OUTPUT "${STUB_SRC}"
    COMMAND "${CMAKE_COMMAND}" -E env
            "RUSTUP_HOME=${RUSTUP_HOME_VALUE}"
            "CARGO_HOME=${CARGO_HOME_VALUE}"
            "PATH=${NDK_BIN_DIR}:$ENV{PATH}"
            "CARGO_TARGET_${RUST_TARGET_UPPER}_LINKER=${RUST_LINKER}"
            "CC_${RUST_TARGET}=${RUST_LINKER}"
            "CC_${RUST_TARGET_UNDERSCORE}=${RUST_LINKER}"
            "AR_${RUST_TARGET}=${NDK_BIN_DIR}/llvm-ar"
            "AR_${RUST_TARGET_UNDERSCORE}=${NDK_BIN_DIR}/llvm-ar"
            "${RUST_CARGO}" build
                --manifest-path "${MANIFEST}"
                --target "${RUST_TARGET}"
                --release
                --locked
    COMMAND "${CMAKE_COMMAND}" -E touch "${STUB_SRC}"
    DEPENDS ${SOURCES}
    COMMENT "[rust] cargo build --target ${RUST_TARGET} --release（${LIB_NAME}）"
    VERBATIM
  )

  add_library(${CMAKE_TARGET} SHARED "${STUB_SRC}")
  set_target_properties(${CMAKE_TARGET} PROPERTIES
    OUTPUT_NAME "${LIB_NAME}"
    PREFIX "lib"
  )

  add_custom_command(TARGET ${CMAKE_TARGET} POST_BUILD
    # 注意：这里**不要**给 -D 的值再加一层引号。在 VERBATIM 下 ninja 会整体加引号，
    # 内层再加会变成字面量 `\"...\"`，路径校验必然失败（踩过一次）。
    COMMAND "${CMAKE_COMMAND}"
            "-DRUST_SO_SRC=${SO_SRC}"
            "-DRUST_SO_DST=$<TARGET_FILE:${CMAKE_TARGET}>"
            -P "${CMAKE_CURRENT_LIST_DIR}/copy_rust_so.cmake"
    BYPRODUCTS "${SO_SRC}"
    COMMENT "[rust] 用 cargo 产物替换占位库：${LIB_NAME}.so"
    VERBATIM
  )
endfunction()
