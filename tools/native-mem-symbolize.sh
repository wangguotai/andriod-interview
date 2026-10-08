#!/usr/bin/env bash
#
# native-mem-symbolize.sh —— 把 memtrace 归因报告里的 `模块+偏移` 还原成 **函数 + 行号**
#
# ════════════════════════════════════════════════════════════════════════════
# 为什么需要这个脚本（而不是在 App 里用 dladdr 当场解析）
# ════════════════════════════════════════════════════════════════════════════
#
# 设备端**故意不做**符号解析，是设计取舍（见 app/src/main/cpp/memtrace.cpp 文件头 §三）：
#
#   1. dladdr 会重入动态链接器锁（dlopen → malloc → dladdr ⇒ 死锁风险），
#      在 malloc hook 内做等于自杀；
#   2. 线上 App 里**没有符号表**（Release 只带 .dynsym，行号信息在 .debug_* 里，
#      通常随构建产物上传符号服务，不随 APK 下发）。
#
# 所以线上报告只携带 **`module+offset`**（稳定、体积小、不依赖设备环境），
# 由构建侧/事后用本机 NDK 的 llvm-symbolizer 离线还原。
# 这是 Android 平台的标准做法（ndk-stack / perfetto 的 symbolize 同理）。
#
# ════════════════════════════════════════════════════════════════════════════
# 用法
# ════════════════════════════════════════════════════════════════════════════
#
#   # ① 最常用：直接喂一段 logcat 归因报告（脚本自己提取所有 frame）
#   adb logcat -d -s MemoryMonitor:I | tools/native-mem-symbolize.sh
#
#   # ② 喂文件
#   tools/native-mem-symbolize.sh /tmp/attribution.txt
#
#   # ③ 只查一条
#   tools/native-mem-symbolize.sh --so libmemtrace.so --off 0x17268
#
#   # ④ 指定未 strip 的 .so 目录（默认自动找 app/build/intermediates/cmake/debug/obj）
#   tools/native-mem-symbolize.sh --libdir path/to/unstripped/libs < report.txt
#
# ⚠️ **必须用未 strip 的库**。`intermediates/merged_native_libs` 和 APK 里的是
#    strip 过的，只能给出函数名（且是导出符号），**没有行号**。
#    本脚本默认取 `intermediates/cmake/debug/obj/<abi>/`，那里是未 strip 的。
#
# ════════════════════════════════════════════════════════════════════════════
# 实测（模拟器 API 36 / arm64-v8a，2024-10 本机）
# ════════════════════════════════════════════════════════════════════════════
#
#   报告的 site#0 = 32.00 MB / 32 笔，解析结果：
#     libmemtrace.so+0x17268  →  my_malloc                          memtrace.cpp:434
#     libmemtrace.so+0x19750  →  Java_..._allocBlocksNative         memtrace.cpp:923
#   正好对上"点【造 NATIVE 负载】→ 走 native 分配器要 32MB"这条因果链。
#
set -euo pipefail

ABI="${ABI:-arm64-v8a}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ── 找 llvm-symbolizer：优先用工程声明的 NDK，再退回 PATH 里的 ──────────────
find_symbolizer() {
  if [[ -n "${LLVM_SYMBOLIZER:-}" ]]; then echo "$LLVM_SYMBOLIZER"; return; fi
  local ndk_root="$HOME/Library/Android/sdk/ndk"
  # 优先用与 CMakeLists 一致的 25.1.8937393（见 app/build.gradle.kts 的 ndkVersion）
  local cand
  for cand in "$ndk_root/25.1.8937393" "$ndk_root"/*; do
    local p="$cand/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-symbolizer"
    [[ -x "$p" ]] && { echo "$p"; return; }
    p="$cand/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-symbolizer"
    [[ -x "$p" ]] && { echo "$p"; return; }
  done
  command -v llvm-symbolizer || true
}

# ── 找未 strip 的 .so 目录 ────────────────────────────────────────────────
find_libdir() {
  local d
  for d in "$ROOT/app/build/intermediates/cmake/debug/obj/$ABI" \
           "$ROOT/app/build/intermediates/cxx/Debug"/*/"obj/$ABI"; do
    [[ -d "$d" ]] && { echo "$d"; return; }
  done
  return 1
}

MODE=""; ONE_SO=""; ONE_OFF=""; LIBDIR=""; INPUT="-"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --so)      MODE=one; ONE_SO="$2"; shift 2 ;;
    --off)     ONE_OFF="$2"; shift 2 ;;
    --libdir)  LIBDIR="$2"; shift 2 ;;
    --abi)     ABI="$2"; shift 2 ;;
    -h|--help) sed -n '2,45p' "$0"; exit 0 ;;
    -)         INPUT="-"; shift ;;
    *)         INPUT="$1"; shift ;;
  esac
done

SYMBOLIZER="$(find_symbolizer)"
if [[ -z "$SYMBOLIZER" ]]; then
  echo "❌ 找不到 llvm-symbolizer。装 NDK 或设 LLVM_SYMBOLIZER=<path>。" >&2
  exit 1
fi
[[ -z "$LIBDIR" ]] && LIBDIR="$(find_libdir || true)"

echo "llvm-symbolizer: $SYMBOLIZER"
echo "库目录(未 strip): ${LIBDIR:-<未找到>}"
[[ -n "$LIBDIR" ]] && ls -1 "$LIBDIR"/*.so 2>/dev/null | sed 's/^/  /' | head -20
echo

# ── 系统库（libart.so / libhwui.so ...）的帧：**本脚本解析不了，这是事实** ──
#
# 实测本机（模拟器 API 36 / arm64，用户版系统镜像）：
#   adb shell "cat /apex/com.android.art/lib64/libart.so" > /tmp/sysso/libart.so
#   → llvm-symbolizer 输出 `??  ??:0:0`（**符号表是 strip 掉的**）。
# 系统库只带 `.dynsym`（导出符号），不含 `.debug_*`/`.symtab`，而帧偏移往往落在
# 内部静态函数上，因此**必须**用与之精确匹配的 **symbol file**：
#   · 官方镜像：Google 的 per-build 符号包（Android 版本 + build id 必须严格对应）
#   · 自编译镜像：AOSP `out/target/product/<dev>/symbols/`
# 对不上的 symbol file 会给出**错误但看起来合理**的函数名 —— 比解析不出来更危险。
#
# 所以对系统库帧的处理是：**明确标注"需 symbol file"，不猜**。
# 我们要归因的是**自己代码**的分配（这才是可改的部分），系统库帧只提供上下文。
EXTRA_DIRS=("${EXTRA_LIBDIR:-/tmp/sysso}")

lookup() {
  local so="$1" off="$2"
  local dir f
  for dir in "$LIBDIR" "${EXTRA_DIRS[@]}"; do
    [[ -n "$dir" && -f "$dir/$so" ]] || continue
    f="$dir/$so"
    local out
    out="$("$SYMBOLIZER" --obj="$f" --functions=short --inlines "$off" 2>/dev/null | head -4)"
    # ⚠️ llvm-symbolizer 对"没有符号信息"的库返回 `??  ??:0:0` 而**不是报错**。
    #    直接透传会让人以为"解析过了"，实则一个字都没解出来。这里挑明。
    if [[ "$out" == *"??"* ]]; then
      echo "    ⚠️ $so 无符号信息（strip 过）。系统库需要与 build-id 匹配的 symbol file，"
      echo "       不能拿设备上的 .so 直接解 —— 见脚本头部说明。"
      echo "       自己代码的帧请确认用的是未 strip 的库（--libdir）。"
      return 0
    fi
    echo "$out"
    return 0
  done
  echo "    (本机无 $so 的副本)"
}

if [[ "$MODE" == "one" ]]; then
  lookup "$ONE_SO" "$ONE_OFF"
  exit 0
fi

# ── 从报告中提取 `xxx.so+0xNNN` 并逐条解析 ────────────────────────────────
# ⚠️ 去重后按**出现顺序**输出，保留与报告的对应关系（不去重会重复几十遍，
#    同一个 my_malloc 站点会被 32 笔分配共享）。
grep -oE '[A-Za-z0-9_.+-]+\.so\+0x[0-9a-f]+' "$INPUT" 2>/dev/null | awk '!seen[$0]++' | while IFS= read -r tok; do
  so="${tok%%+*}"
  off="${tok#*+}"
  echo "── $so $off"
  lookup "$so" "$off" | sed 's/^/    /'
done
