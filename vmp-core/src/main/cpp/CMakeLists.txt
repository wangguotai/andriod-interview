cmake_minimum_required(VERSION 3.22.1)
project("vmpcore")

# 复用仓库共享的「CMake 驱动 cargo」封装（见 tools/cmake/build_rust_android.cmake）。
# CMAKE_CURRENT_SOURCE_DIR = vmp-core/src/main/cpp → 上溯 4 级到仓库根。
# （本文件原在 vmp-lab，层级数一致，搬过来无需修改。）
set(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../..")
include("${REPO_ROOT}/tools/cmake/build_rust_android.cmake")

# ─────────────────────────────────────────────────────────────────────────────
# 只注册一个 crate：`vmp_android`。
#
# 它会把 `vmp`（虚拟机 + 加密程序）与 `imagepipeline`（原生对照路径）一起编进来 ——
# 两者都是它的 path 依赖，cargo 会自动处理，不需要在这里各注册一行。
#
# 产物 libvmp_android.so 与 app 的 libimagepipeline.so **互不干扰**：
# 这是本 Lab 能「同时装、同时比」的前提，也是把它做成独立 android library
# （:vmp-core）的前提 —— :app 与 :vmp-lab 各自链接同一份构建产物。
# ─────────────────────────────────────────────────────────────────────────────
ipclab_build_rust_android(
  CRATE_NAME    vmp_android
  CRATE_DIR     "${REPO_ROOT}/rust/vmp-android"
  WORKSPACE_DIR "${REPO_ROOT}/rust"
  LIB_NAME      vmp_android)
