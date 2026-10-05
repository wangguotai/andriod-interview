# ─────────────────────────────────────────────────────────────────────────────
# 通用「CMake 驱动 cargo 交叉编译 Rust → Android .so」封装
#
# 由来：`app/src/main/cpp/rust_build.cmake` 把整套逻辑写死给了 imagepipeline。本文件
# 把同样的思路抽成可复用函数，供其它 module（当前是 :ipc-lab 的 ipclab crate）调用，
# 避免每个用到 Rust 的 module 各抄一份、各自演化出不同的坑。
#
# ─── 为什么不用 corrosion / cargo-ndk ───
# （与 app 侧同一结论，原样保留理由）
#   - corrosion 把 crate 暴露成 SHARED IMPORTED 目标，AGP 的 externalNativeBuild
#     只打包「本 CMake 构建树里产出的」共享库，IMPORTED 目标不进 APK。
#   - cargo-ndk 直出 jniLibs 则绕过了 externalNativeBuild 结构。
#   这里选最直白的一条：add_custom_command 调 cargo，把 .so 产到
#   ${CMAKE_CURRENT_BINARY_DIR}（即 AGP 的 <abi> 输出目录），与 C++ 库同级。
#
# ─── 为什么必须建成真实的 SHARED 目标（而不是 add_custom_target）───
#   AGP 只从 CMake codemodel 里读「库类型」目标来生成 android_gradle_build_mini.json
#   的 output 字段；纯 UTILITY 目标没有 output，mergeNativeLibs 不会打包。
#   而 IMPORTED 目标默认不进 codemodel。所以：用占位 C 源建 SHARED 目标（名字对齐
#   lib<name>.so），让占位源依赖 cargo 产物，POST_BUILD 再用真实 .so 覆盖它。
#   POST_BUILD 里显式校验 cargo 产物存在 —— 否则 cargo 失败时会静静留下一个
#   「有 .so 但没有 JNI 符号」的假绿，比构建失败更难查。
#
# ─── 约定 ───
#   找不到 Rust 工具链 / 未装对应 target 时：**警告 + 跳过**，不让其它 native 库崩。
#   Kotlin 侧对缺失的库做降级（见 IpcNative 捕获 UnsatisfiedLinkError）。
#
# ─── 用法 ───
#   ipclab_build_rust_android(
#     CRATE_NAME  ipclab                      # crate 名（= 产物 lib<name>.so）
#     CRATE_DIR   <crate 绝对路径>             # 含 Cargo.toml 的目录
#     WORKSPACE_DIR <cargo workspace 根>       # 其 target/ 与 Cargo.lock 所在
#     LIB_NAME    ipclab)                      # 可选，默认 = CRATE_NAME
# ─────────────────────────────────────────────────────────────────────────────

# Rust 工具链位置：优先取 CMake 变量（由 Gradle 从 local.properties 传入），
# 其次环境变量，最后回落 ~/.rustup ~/.cargo。Gradle 调起的 CMake 是独立进程，
# 不继承我们 shell 里导出的 RUSTUP_HOME/CARGO_HOME，所以必须显式传。
set(RUSTUP_HOME_VALUE "$ENV{RUSTUP_HOME}" CACHE PATH "rustup home（含 toolchains/）")
set(CARGO_HOME_VALUE "$ENV{CARGO_HOME}" CACHE PATH "cargo home（含 bin/cargo）")

if(NOT RUSTUP_HOME_VALUE)
  set(RUSTUP_HOME_VALUE "$ENV{HOME}/.rustup")
endif()
if(NOT CARGO_HOME_VALUE)
  set(CARGO_HOME_VALUE "$ENV{HOME}/.cargo")
endif()

function(ipclab_build_rust_android)
  cmake_parse_arguments(ARG "" "CRATE_NAME;CRATE_DIR;WORKSPACE_DIR;LIB_NAME" "" ${ARGN})

  if(NOT ARG_CRATE_NAME OR NOT ARG_CRATE_DIR OR NOT ARG_WORKSPACE_DIR)
    message(FATAL_ERROR "[rust] ipclab_build_rust_android 需要 CRATE_NAME / CRATE_DIR / WORKSPACE_DIR")
  endif()
  set(LIB_NAME "${ARG_LIB_NAME}")
  if(NOT LIB_NAME)
    set(LIB_NAME "${ARG_CRATE_NAME}")
  endif()

  # ── ABI → Rust target triple / NDK 链接器名 ──
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
    message(WARNING "[rust] 未知 ANDROID_ABI=${ANDROID_ABI}，跳过 ${ARG_CRATE_NAME}")
    return()
  endif()

  # ── API level：AGP 会传 ANDROID_PLATFORM（形如 android-24）──
  set(RUST_API_LEVEL "24")
  if(ANDROID_PLATFORM MATCHES "android-([0-9]+)")
    set(RUST_API_LEVEL "${CMAKE_MATCH_1}")
  endif()

  # ── NDK 工具链 bin 目录：从 CMake 自己的编译器路径反推，避免硬编码 NDK 版本 ──
  get_filename_component(NDK_BIN_DIR "${CMAKE_C_COMPILER}" DIRECTORY)
  set(RUST_LINKER "${NDK_BIN_DIR}/${RUST_LINKER_STEM}${RUST_API_LEVEL}-clang")
  if(NOT EXISTS "${RUST_LINKER}")
    message(WARNING "[rust] 找不到 NDK 链接器 ${RUST_LINKER}，跳过 ${ARG_CRATE_NAME}")
    return()
  endif()

  set(RUST_CARGO "${CARGO_HOME_VALUE}/bin/cargo")
  if(NOT EXISTS "${RUST_CARGO}")
    set(RUST_CARGO "$ENV{HOME}/.cargo/bin/cargo")
  endif()

  execute_process(
    COMMAND "${CMAKE_COMMAND}" -E env
            "RUSTUP_HOME=${RUSTUP_HOME_VALUE}" "CARGO_HOME=${CARGO_HOME_VALUE}"
            "${RUST_CARGO}" --version
    OUTPUT_VARIABLE RUST_CARGO_VERSION OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET RESULT_VARIABLE RUST_CARGO_RESULT)
  if(NOT RUST_CARGO_RESULT EQUAL 0)
    message(WARNING
      "[rust] 无法执行 cargo（${RUST_CARGO}），跳过 ${ARG_CRATE_NAME}。\n"
      "       请确认 Rust 已安装，或显式传入 -DRUSTUP_HOME_VALUE=... -DCARGO_HOME_VALUE=...")
    return()
  endif()

  # ── 该 target 是否已 rustup target add？没有就跳过（提示怎么补），不阻塞构建 ──
  execute_process(
    COMMAND "${CMAKE_COMMAND}" -E env
            "RUSTUP_HOME=${RUSTUP_HOME_VALUE}" "CARGO_HOME=${CARGO_HOME_VALUE}"
            "${CARGO_HOME_VALUE}/bin/rustup" target list --installed
    OUTPUT_VARIABLE RUST_INSTALLED_TARGETS ERROR_QUIET RESULT_VARIABLE RUST_TARGETS_RESULT)
  if(RUST_TARGETS_RESULT EQUAL 0)
    string(FIND "${RUST_INSTALLED_TARGETS}" "${RUST_TARGET}" RUST_TARGET_POS)
    if(RUST_TARGET_POS EQUAL -1)
      message(WARNING
        "[rust] 未安装 target ${RUST_TARGET}（ABI ${ANDROID_ABI}），跳过 ${ARG_CRATE_NAME}。\n"
        "       补齐：rustup target add ${RUST_TARGET}")
      return()
    endif()
  endif()

  message(STATUS "[rust] ${ARG_CRATE_NAME}: ABI=${ANDROID_ABI} → ${RUST_TARGET}, linker=${RUST_LINKER}")

  # ── 增量依赖：crate 全部源码 + build.rs + 各层 Cargo.toml/lock ──
  # 用 GLOB_RECURSE + CONFIGURE_DEPENDS，避免新增文件后忘更新导致「改了不重编」。
  file(GLOB_RECURSE RUST_SOURCES CONFIGURE_DEPENDS
    "${ARG_CRATE_DIR}/src/*.rs"
    "${ARG_CRATE_DIR}/build.rs"
  )
  list(APPEND RUST_SOURCES
    "${ARG_CRATE_DIR}/Cargo.toml"
    "${ARG_WORKSPACE_DIR}/Cargo.toml"
    "${ARG_WORKSPACE_DIR}/Cargo.lock"
  )

  # ── 占位 SHARED 目标：名字对齐 lib<LIB_NAME>.so，让它进 APK，再由 cargo 产物覆盖 ──
  string(REPLACE "-" "_" RUST_TARGET_UNDERSCORE "${RUST_TARGET}")
  string(TOUPPER "${RUST_TARGET_UNDERSCORE}" RUST_TARGET_UPPER)
  set(RUST_SO_SRC "${ARG_WORKSPACE_DIR}/target/${RUST_TARGET}/release/lib${LIB_NAME}.so")

  set(RUST_STUB_SRC "${CMAKE_CURRENT_BINARY_DIR}/${LIB_NAME}_rust_stub.c")
  add_custom_command(
    OUTPUT "${RUST_STUB_SRC}"
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
                --manifest-path "${ARG_CRATE_DIR}/Cargo.toml"
                --target "${RUST_TARGET}"
                --release
                --locked
    COMMAND "${CMAKE_COMMAND}" -E touch "${RUST_STUB_SRC}"
    DEPENDS ${RUST_SOURCES}
    COMMENT "[rust] cargo build --target ${RUST_TARGET} --release（${ARG_CRATE_NAME}）"
    VERBATIM)

  add_library(${ARG_CRATE_NAME} SHARED "${RUST_STUB_SRC}")
  set_target_properties(${ARG_CRATE_NAME} PROPERTIES
    OUTPUT_NAME "${LIB_NAME}"
    PREFIX "lib")

  add_custom_command(TARGET ${ARG_CRATE_NAME} POST_BUILD
    # 注意：-D 的值不要再套一层引号（VERBATIM 下 ninja 会整体加引号，内层会变字面量）。
    COMMAND "${CMAKE_COMMAND}"
            "-DRUST_SO_SRC=${RUST_SO_SRC}"
            "-DRUST_SO_DST=$<TARGET_FILE:${ARG_CRATE_NAME}>"
            -P "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/copy_rust_so.cmake"
    BYPRODUCTS "${RUST_SO_SRC}"
    COMMENT "[rust] 用 cargo 产物替换占位库：lib${LIB_NAME}.so"
    VERBATIM)
endfunction()
