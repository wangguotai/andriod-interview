cmake_minimum_required(VERSION 3.22.1)
project("ipclab")

# 复用仓库共享的「CMake 驱动 cargo」封装（见 tools/cmake/build_rust_android.cmake）。
# CMAKE_CURRENT_SOURCE_DIR = ipc-lab/src/main/cpp → 上溯 4 级到仓库根。
set(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../..")
include("${REPO_ROOT}/tools/cmake/build_rust_android.cmake")

ipclab_build_rust_android(
  CRATE_NAME    ipclab
  CRATE_DIR     "${REPO_ROOT}/rust/ipclab"
  WORKSPACE_DIR "${REPO_ROOT}/rust"
  LIB_NAME      ipclab)
