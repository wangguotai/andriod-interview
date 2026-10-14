#!/usr/bin/env bash
#
# 一键准备 Android 原生多 bundle demo 所需的产物：
#
#   1. 在 host-app 产出 base bundle + mapping（用的是 host-app 自己的 ReactNativeHost）
#   2. 在 business-app 按 mapping 剥离 base 内容，产出只含业务代码的业务包
#   3. 把两个 bundle 摆进 host-app 的 assets，随 APK 一起打包
#
# 之后 assembleDebug 即可（debug 变体默认不跑 JS bundling，不会覆盖这两个文件）。
#
# 用法:
#   bash scripts/prepare-android-demo.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="$ROOT/packages/rn-bundle-split/bin/rn-bundle-split.js"

BASE_OUT="$ROOT/host-app/output"
BIZ_OUT="$ROOT/business-app/output"
BASE_BUNDLE="$BASE_OUT/base.android.release.bundle"
MAPPING="$BASE_OUT/base.android.release.mapping.json"
BIZ_BUNDLE="$BIZ_OUT/business.android.release.bundle"
ASSETS="$ROOT/host-app/android/app/src/main/assets"

echo "==> 1/3 产出 base bundle + mapping"
node "$CLI" base --project "$ROOT/host-app" --out "$BASE_OUT"

echo
echo "==> 2/3 产出业务 bundle（按 mapping 剥离 base 内容）"
node "$CLI" split \
  --project "$ROOT/business-app" \
  --mapping "$MAPPING" \
  --out "$BIZ_OUT"

echo
echo "==> 3/3 校验 + 摆进 assets"
node "$CLI" verify \
  --project "$ROOT/business-app" \
  --mapping "$MAPPING" \
  --business "$BIZ_BUNDLE" \
  --base "$BASE_BUNDLE"

mkdir -p "$ASSETS"
# base 必须在业务包之前执行，且自带 prelude；文件名与 MultiBundleLoader.BASE_ASSET_URL 对应
cp "$BASE_BUNDLE" "$ASSETS/index.android.bundle"
# MultiBundleLoader.BUSINESS_ASSET_NAME 必须是这个文件名
cp "$BIZ_BUNDLE" "$ASSETS/business.android.bundle"

echo
echo "assets 已就绪:"
ls -lh "$ASSETS" | tail -n +2

cat <<'EOF'

下一步:
  cd host-app/android && ./gradlew :app:assembleDebug
  然后安装 APK，桌面会出现两个图标:
    - RnBundleSplit      -> MainActivity，渲染 base bundle 里的组件
    - BusinessApp (业务包) -> BusinessActivity，渲染业务 bundle 里的组件
EOF
