#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
#  tools/plugin-package/deploy.sh
#
#  把打好的插件 APK 送到**宿主的私有目录**，让宿主页不依赖网络也能加载到。
#
#  ─── 为什么不 push 到 /sdcard 根目录 ───
#
#  Android 10+ 的分区存储下，`/sdcard` 是 shell 与 app 各看一半的地方，
#  路径与权限都会随机型/版本变形（外部存储权限、MANAGE_EXTERNAL_STORAGE、
#  shell 的 sdcard_rw 组……）。而 `/sdcard/Android/data/<pkg>/files/` 是
#  **shell 有写权限、app 有读写权限**的交集 —— 且它恰好就是
#  `context.getExternalFilesDir(null)`，宿主代码不用为"调试路径"开特例。
#
#  ⚠️ 这也解释了为什么"从资源文件加载"要分两条：
#    · assets 内嵌副本：编译期就在 apk 里，**零外部依赖**，是最可靠的兜底；
#    · 私有目录副本：可替换、可对照（换一个插件包不用重装宿主）。
#  两者都在，是为了能把"同一份宿主 + 不同插件"这类实验做出来。
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG="${PKG:-com.example.myapplication}"
APK="${1:-$REPO_ROOT/build/plugin-package/plugin-demo-debug.apk}"
XML="${2:-$REPO_ROOT/build/plugin-package/plugin-package.xml}"

[[ -f "$APK" ]] || { echo "❌ 找不到插件 APK：$APK（先跑 package.sh）" >&2; exit 1; }

DEST="/sdcard/Android/data/$PKG/files"
echo "▸ 推送到 $DEST"
adb shell mkdir -p "$DEST"
adb push "$APK" "$DEST/" | tail -1
if [[ -f "$XML" ]]; then
  adb push "$XML" "$DEST/plugin-package.xml" | tail -1
fi

echo "▸ 校验（宿主侧看到的路径与大小）："
adb shell "ls -l $DEST"

echo
echo "现在可以在宿主 App 里打开「插件化 Lab」→ 选「私有目录插件」加载。"
