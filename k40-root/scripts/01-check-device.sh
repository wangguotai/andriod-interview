#!/usr/bin/env bash
# 设备状态体检：确认 K40 当前处于哪个阶段
# 用法: ./scripts/01-check-device.sh
set -uo pipefail

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[ -x "$ADB" ] || ADB=$(command -v adb)
FB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/fastboot"
[ -x "$FB" ] || FB=$(command -v fastboot)

g() { "$ADB" shell getprop "$1" 2>/dev/null | tr -d '\r'; }

echo "=========== ADB 设备 ==========="
"$ADB" devices -l

if ! "$ADB" get-state >/dev/null 2>&1; then
  echo
  echo "!! adb 未检测到设备。请确认："
  echo "   - USB 已连接且手机上已授权本机调试"
  echo "   - 或手机已进入 fastboot 模式（见下方 fastboot 设备）"
  echo
  echo "=========== FASTBOOT 设备 ==========="
  "$FB" devices
  exit 0
fi

echo
echo "=========== 系统版本 ==========="
printf 'Android      : %s (SDK %s)\n' "$(g ro.build.version.release)" "$(g ro.build.version.sdk)"
printf 'MIUI         : %s\n' "$(g ro.miui.ui.version.name)"
printf '增量版本     : %s\n' "$(g ro.build.version.incremental)"
printf '机型/代号    : %s / %s\n' "$(g ro.product.model)" "$(g ro.product.name)"
printf '区域         : %s\n' "$(g ro.miui.region)"

echo
echo "=========== Bootloader / AVB ==========="
LOCK="$(g ro.secureboot.lockstate)"
VBS="$(g ro.boot.verifiedbootstate)"
printf 'BL 锁状态    : %s\n' "${LOCK:-unknown}"
printf 'verified boot: %s\n' "${VBS:-unknown}"
printf 'veritymode   : %s\n' "$(g ro.boot.veritymode)"
printf 'flash.locked : %s\n' "$(g ro.boot.flash.locked)"

echo
echo "=========== Root 检测 ==========="
if "$ADB" shell 'command -v su >/dev/null 2>&1 || ls /data/adb/magisk >/dev/null 2>&1'; then
  echo "检测到 root 痕迹："
  "$ADB" shell 'su -c id 2>/dev/null; ls -d /data/adb/magisk 2>/dev/null; ls -d /data/adb/modules 2>/dev/null'
  "$ADB" shell 'magisk -V 2>/dev/null; magisk -v 2>/dev/null'
else
  echo "未检测到 root（无 su，无 /data/adb/magisk）"
fi

echo
echo "=========== 解锁判定 ==========="
if [ "$LOCK" = "locked" ] || [ "$VBS" = "green" ]; then
  echo "→ 【BL 仍锁定】无法刷入补丁 boot。请先完成小米解锁流程。"
  echo "  下一步：设置 → 更多设置 → 开发者选项 → 设备解锁状态 → 绑定账号和设备"
else
  echo "→ 【BL 已解锁】可以进入 fastboot 刷入流程。"
  echo "  注意：刷 boot 前请先解包固件提取 boot.img 并完成 Magisk 打补丁。"
fi
