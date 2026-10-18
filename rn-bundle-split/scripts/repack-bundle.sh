#!/usr/bin/env bash
#
# 快速替换 APK 里的 JS bundle 并重装 —— 用于复现/验证白屏与加载故障。
#
# 为什么需要它：改一行 JS 走完整 Gradle 构建要 ~3 分钟（还要吃磁盘）。
# 直接替换 assets 里的 bundle 再签名重装，约 3 秒。
#
# 用法:
#   bash scripts/repack-bundle.sh <assets里的文件名> <替换用的本地文件> [APK路径]
#
# 例（用改坏的业务包替换）:
#   bash scripts/repack-bundle.sh business.android.bundle /tmp/broken.bundle
#
# 例（还原成正常产物）:
#   bash scripts/repack-bundle.sh business.android.bundle \
#        business-app/output/business.android.release.bundle
#
# 之后:
#   adb logcat -c && adb shell am force-stop <pkg> && adb shell am start -n <pkg>/.BusinessActivity
#
set -euo pipefail

ENTRY="${1:?用法: repack-bundle.sh <assets内的文件名> <替换文件> [APK]}"
SRC="${2:?缺少替换文件}"
DEFAULT_APK="host-app/android/app/build/outputs/apk/debug/app-debug.apk"
APK="${3:-$DEFAULT_APK}"
PKG="com.rnbundlesplit"

# 工具链位置（按需改）
SDK="${ANDROID_HOME:-/Users/wgt/Library/Android/sdk}"
BT="$SDK/build-tools/34.0.0"
ADB="$SDK/platform-tools/adb"
KS="host-app/android/app/debug.keystore"

[ -f "$APK" ] || { echo "✗ APK 不存在: $APK（先跑一次 assembleDebug）"; exit 1; }
[ -f "$SRC" ] || { echo "✗ 替换文件不存在: $SRC"; exit 1; }
[ -x "$BT/apksigner" ] || { echo "✗ 找不到 apksigner: $BT"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "▸ 1/4 复制 APK 到临时目录（避免污染构建产物）"
cp "$APK" "$WORK/base.apk"

echo "▸ 2/4 替换 assets/$ENTRY"
mkdir -p "$WORK/stage/assets"
cp "$SRC" "$WORK/stage/assets/$ENTRY"
(cd "$WORK/stage" && zip -q -X "$WORK/base.apk" "assets/$ENTRY")

echo "▸ 3/4 zipalign + 签名"
"$BT/zipalign" -f -p 4 "$WORK/base.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "$WORK/signed.apk" "$WORK/aligned.apk"

echo "▸ 4/4 安装"
"$ADB" install -r -d "$WORK/signed.apk" | tail -1

echo
echo "完成。下一步:"
echo "  $ADB logcat -c"
echo "  $ADB shell am force-stop $PKG"
echo "  $ADB shell am start -n $PKG/.BusinessActivity"
echo
echo "还原成正常产物:"
echo "  bash scripts/repack-bundle.sh $ENTRY business-app/output/business.android.release.bundle"
