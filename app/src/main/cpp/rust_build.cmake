# ─────────────────────────────────────────────────────────────────────────────
# Rust 库接入（CMake 驱动 cargo）
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
#   ImagePipelineNative，捕获 UnsatisfiedLinkError）。
# ─────────────────────────────────────────────────────────────────────────────

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
  return()
endif()

# API level：AGP 会传 ANDROID_PLATFORM（形如 android-24）。
# 取不到就回落到 minSdk 的 24 —— 与 app/build.gradle.kts 的 minSdk 一致。
set(RUST_API_LEVEL "24")
if(ANDROID_PLATFORM MATCHES "android-([0-9]+)")
  set(RUST_API_LEVEL "${CMAKE_MATCH_1}")
endif()

# NDK 工具链 bin 目录：从 CMake 自己的编译器路径反推，避免再硬编码 NDK 版本
get_filename_component(NDK_BIN_DIR "${CMAKE_C_COMPILER}" DIRECTORY)
set(RUST_LINKER "${NDK_BIN_DIR}/${RUST_LINKER_STEM}${RUST_API_LEVEL}-clang")

if(NOT EXISTS "${RUST_LINKER}")
  message(WARNING "[rust] 找不到 NDK 链接器 ${RUST_LINKER}，跳过 Rust 库构建")
  return()
endif()

# 该 target 是否已 rustup target add？没有就跳过（提示怎么补），不阻塞构建。
string(REPLACE "-" "_" RUST_TARGET_UNDERSCORE "${RUST_TARGET}")
string(TOUPPER "${RUST_TARGET_UNDERSCORE}" RUST_TARGET_UPPER)

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

if(NOT RUST_CARGO_RESULT EQUAL 0)
  message(WARNING
    "[rust] 无法执行 cargo（${RUST_CARGO}）。\n"
    "       请确认 Rust 已安装，或显式传入 -DRUSTUP_HOME_VALUE=... -DCARGO_HOME_VALUE=...\n"
    "       本机为 /Volumes/ext/Rust/{rustup,cargo}。跳过 Rust 库构建。")
  return()
endif()

execute_process(
  COMMAND "${CMAKE_COMMAND}" -E env
          "RUSTUP_HOME=${RUSTUP_HOME_VALUE}"
          "CARGO_HOME=${CARGO_HOME_VALUE}"
          "${RUST_CARGO}" "+stable" --list
  OUTPUT_VARIABLE RUST_INSTALLED_PROBE
  ERROR_QUIET
  RESULT_VARIABLE RUST_PROBE_RESULT
)

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
    return()
  endif()
endif()

message(STATUS "[rust] ABI=${ANDROID_ABI} → target=${RUST_TARGET}, linker=${RUST_LINKER}")
message(STATUS "[rust] ${RUST_CARGO_VERSION}")

# 库名与产物的对应关系：crate 名 imagepipeline_android → libimagepipeline_android.so
set(RUST_LIB_NAME "imagepipeline_android")
# CMAKE_CURRENT_SOURCE_DIR = app/src/main/cpp → 上溯 4 级到仓库根。
# （踩过：写成 3 级会静默指到不存在的路径，cargo 报 "manifest path does not exist"，
#   而 CMake 配置阶段因为只是拼字符串、不校验存在性，完全不会提前报错。）
set(RUST_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../..")
set(RUST_CRATE_DIR "${RUST_REPO_ROOT}/rust/android")
set(RUST_MANIFEST "${RUST_CRATE_DIR}/Cargo.toml")

if(NOT EXISTS "${RUST_MANIFEST}")
  message(WARNING "[rust] 找不到 ${RUST_MANIFEST}（仓库目录层级判断有误），跳过 Rust 库构建")
  return()
endif()

# workspace 布局下，CARGO_TARGET_DIR 默认落在 workspace 根的 target/，
# 而不是成员 crate 的 target/。写死成员目录会「编译成功却找不到产物」。
set(RUST_SO_SRC "${RUST_REPO_ROOT}/rust/target/${RUST_TARGET}/release/lib${RUST_LIB_NAME}.so")

# 增量依赖：crate 源码 + 两个 Cargo.toml + lock。
# 用 GLOB_RECURSE + CONFIGURE_DEPENDS 而非手写列表，避免新增文件后忘更新导致「改了不重编」。
file(GLOB_RECURSE RUST_SOURCES CONFIGURE_DEPENDS
  "${RUST_CRATE_DIR}/src/*.rs"
  "${RUST_CRATE_DIR}/Cargo.toml"
  "${RUST_REPO_ROOT}/rust/imagepipeline/src/*.rs"
  "${RUST_REPO_ROOT}/rust/imagepipeline/Cargo.toml"
  "${RUST_REPO_ROOT}/rust/Cargo.toml"
  "${RUST_REPO_ROOT}/rust/Cargo.lock"
)

# ─────────────────────────────────────────────────────────────────────────────
# 为什么必须建成一个**真实的 SHARED 目标**，而不是 add_custom_target
#
# AGP 只从 CMake codemodel 里读「库类型」目标（SHARED_LIBRARY 等）来生成
# android_gradle_build_mini.json 的 `output` 字段；纯 custom target 是 UTILITY，
# AGP 只记名字、不给 output，mergeDebugNativeLibs 就不会把它打进 APK。
# 而 IMPORTED 目标干脆不进 codemodel（默认 EXPORT_PROPERTIES 不含 IMPORTED）。
# 两条路都试过，结论一致：**只能是本工程内建的 SHARED 目标**。
#
# 但 cargo 才是真正的生产者，CMake 不能直接「产出」它的结果。于是：
#   1. 用一份 CMake 生成的占位源 kernel_stub.c 建 SHARED 目标（产物名 libimagepipeline_android.so）
#   2. 让占位源依赖 cargo 的产物 → 任一 crate 源码变动都会重新生成占位源 → 重新链接
#   3. POST_BUILD 用 get_filename_component 解析出的真实 .so 覆盖 CMake 链接出的那份
#
# 备选方案（用占位源参与链接再覆盖）唯一的瑕疵：若某次 cargo 构建失败，
# POST_BUILD 不执行，APK 里会静静躺着 CMake 链接出来的空库。所以下面的
# POST_BUILD 里显式校验 cargo 产物存在，不存在就 FATAL_ERROR，把假绿掐掉。
# ─────────────────────────────────────────────────────────────────────────────

set(RUST_STUB_SRC "${CMAKE_CURRENT_BINARY_DIR}/rust_kernel_stub.c")
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
              --manifest-path "${RUST_MANIFEST}"
              --target "${RUST_TARGET}"
              --release
              --locked
  COMMAND "${CMAKE_COMMAND}" -E touch "${RUST_STUB_SRC}"
  DEPENDS ${RUST_SOURCES}
  COMMENT "[rust] cargo build --target ${RUST_TARGET} --release（${RUST_LIB_NAME}）"
  VERBATIM
)

add_library(imagepipeline_android SHARED "${RUST_STUB_SRC}")
set_target_properties(imagepipeline_android PROPERTIES
  # 输出名与 Rust crate 的产物一致，System.loadLibrary("imagepipeline_android") 才能找到
  OUTPUT_NAME "${RUST_LIB_NAME}"
  PREFIX "lib"
)

add_custom_command(TARGET imagepipeline_android POST_BUILD
  # 注意：这里**不要**给 -D 的值再加一层引号。在 VERBATIM 下 ninja 会整体加引号，
  # 内层再加会变成字面量 `\"...\"`，路径校验必然失败（踩过一次）。
  COMMAND "${CMAKE_COMMAND}"
          "-DRUST_SO_SRC=${RUST_SO_SRC}"
          "-DRUST_SO_DST=$<TARGET_FILE:imagepipeline_android>"
          -P "${CMAKE_CURRENT_LIST_DIR}/copy_rust_so.cmake"
  BYPRODUCTS "${RUST_SO_SRC}"
  COMMENT "[rust] 用 cargo 产物替换占位库：${RUST_LIB_NAME}.so"
  VERBATIM
)
