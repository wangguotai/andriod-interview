# 用 cargo 产出的 .so 替换 CMake 为占位 SHARED 目标链接出来的那份。
#
# 单独立成 -P 脚本（而不是内联在 add_custom_command 里）的原因：这样能写显式校验
# 与错误信息。若 cargo 产物不存在（cargo 失败、路径层级写错等），必须 FATAL_ERROR
# 直接失败 —— 绝不能留下 CMake 链接出来的占位空库。那是「APK 里有 .so、但没有 JNI
# 符号」的假绿，比构建失败更难查。
#
# 入参：
#   RUST_SO_SRC  cargo 产物路径
#   RUST_SO_DST  CMake 目标的最终产物路径（$<TARGET_FILE:...>）

if(NOT DEFINED RUST_SO_SRC OR NOT DEFINED RUST_SO_DST)
  message(FATAL_ERROR "[rust] copy_rust_so.cmake 缺少 RUST_SO_SRC / RUST_SO_DST")
endif()

if(NOT EXISTS "${RUST_SO_SRC}")
  message(FATAL_ERROR
    "[rust] 找不到 cargo 产物：${RUST_SO_SRC}\n"
    "       Rust 库没有真正编出来。请手动确认：\n"
    "         cargo build --manifest-path <crate/Cargo.toml> \\\n"
    "               --target aarch64-linux-android --release --locked\n"
    "       常见原因：rustup target 未安装 / NDK linker 路径不对 / workspace 层级写错。")
endif()

file(SIZE "${RUST_SO_SRC}" RUST_SO_SIZE)
if(RUST_SO_SIZE LESS 1024)
  message(FATAL_ERROR "[rust] cargo 产物异常小（${RUST_SO_SIZE} 字节），疑似构建失败：${RUST_SO_SRC}")
endif()

get_filename_component(RUST_SO_DIR "${RUST_SO_DST}" DIRECTORY)
file(MAKE_DIRECTORY "${RUST_SO_DIR}")
configure_file("${RUST_SO_SRC}" "${RUST_SO_DST}" COPYONLY)

message(STATUS "[rust] 已替换 ${RUST_SO_DST} ← ${RUST_SO_SRC}（${RUST_SO_SIZE} 字节）")
