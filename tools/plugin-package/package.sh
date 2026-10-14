#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
#  tools/plugin-package/package.sh
#
#  把 :plugin-demo 编出来的 classes / 资源 / assets **组装成一个可独立分发的插件 APK**。
#
#  ─── 为什么不用 `:plugin-demo:assembleDebug` 的默认产物 ───
#
#  library 模块的默认产物是 **AAR**（`plugin-demo-debug.aar`）：里面是 classes.jar，
#  不是 dex，也没有 resources.arsc。而"独立 dex + 独立资源表"恰好是本演示要证的东西，
#  所以 AAR 只能当**原料**，不能当产物。
#
#  于是本脚本只借 AGP 做「编译」这一半（拿 classes.jar / res 资源 / assets / manifest），
#  组装那一半自己做。多写几十行 shell，换来三件 AGP 不给的事：
#
#    1. **产出真正的 classes.dex**（d8），从而"宿主 apk 里没有这些类"可以用 dexdump 核对；
#    2. **产出 resources.arsc**，并顺手导出 `plugin-res-ids.txt`（资源名→ID，
#       让宿主能在**不依赖插件 R 类**的前提下对同一个 ID 做「插件解析得到 / 宿主解析必失败」
#       的对照 —— 这是"资源隔离"唯一可执行的证据形态；
#    3. **产出 plugin-package.xml**（sha256 + 入口类名 + 版本），即"从网络接口下发插件"
#       这条链路的契约文件。
#
#  ─── 用法 ───
#
#    tools/plugin-package/package.sh                    # 打包 + 落到 app/src/main/assets/
#    tools/plugin-package/package.sh --no-embed         # 只打包，不塞进宿主 assets
#    tools/plugin-package/package.sh --out /tmp/p.apk   # 指定输出路径
#
#  产物：
#    build/plugin-package/plugin-demo-debug.apk      ← 可独立分发的插件
#    build/plugin-package/plugin-package.xml         ← 元数据（含 sha256）
#    build/plugin-package/classes.dex                ← 便于 dexdump 核对的副本
#    app/src/main/assets/plugin-demo-debug.apk       ← 离线兜底的内嵌副本（默认）
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

VARIANT="${VARIANT:-debug}"
VARIANT_CAP="$(tr '[:lower:]' '[:upper:]' <<< "${VARIANT:0:1}")${VARIANT:1}"
MODULE="plugin-demo"
WORK="build/plugin-package"
STAGE="$WORK/stage"
OUT_APK="$WORK/${MODULE}-${VARIANT}.apk"
EMBED_TARGET="app/src/main/assets/${MODULE}-${VARIANT}.apk"
PLUGIN_XML="plugin-package.xml"
ENTRY_CLASS="com.interview.plugindemo.PluginEntry"
ACTIVITY_CLASS="com.interview.plugindemo.ui.PluginPageActivity"
PLUGIN_ID="com.interview.plugindemo"
PLUGIN_VERSION="1.0.0"

EMBED=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-embed) EMBED=0; shift ;;
    --out) OUT_APK="$2"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

# ── 工具定位 ──
# 从 local.properties 的 sdk.dir 找 build-tools，**不硬编码版本**。
# 为什么不用 $ANDROID_HOME：本仓库的 local.properties 才是权威（AGP 也只读它），
# 环境变量在很多 shell 里是空的 —— 那会让脚本以"找不到 d8"这种误导性错误挂掉。
SDK_DIR="$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)"
[[ -d "$SDK_DIR" ]] || { echo "❌ local.properties 里的 sdk.dir 无效：'$SDK_DIR'" >&2; exit 1; }
BT="$(ls -d "$SDK_DIR"/build-tools/* 2>/dev/null | sort -V | tail -1)"
D8="$BT/d8"; AAPT2="$BT/aapt2"; DEXDUMP="$BT/dexdump"
ANDROID_JAR="$SDK_DIR/platforms/android-34/android.jar"
for f in "$D8" "$AAPT2" "$ANDROID_JAR"; do
  [[ -e "$f" ]] || { echo "❌ 缺少：$f" >&2; exit 1; }
done
echo "▸ build-tools : $BT"

# ── 0. 编译（AGP 只负责编译，不负责打包）──
echo "▸ [0/6] 编译 :$MODULE"
./gradlew -q ":$MODULE:assemble$VARIANT_CAP" >/dev/null

AAR="$MODULE/build/outputs/aar/${MODULE}-${VARIANT}.aar"
[[ -f "$AAR" ]] || { echo "❌ 找不到 AAR：$AAR" >&2; exit 1; }
MERGED_MANIFEST="$MODULE/build/intermediates/merged_manifest/${VARIANT}/process${VARIANT_CAP}Manifest/AndroidManifest.xml"
[[ -f "$MERGED_MANIFEST" ]] || MERGED_MANIFEST="$MODULE/src/main/AndroidManifest.xml"

rm -rf "$STAGE"; mkdir -p "$STAGE/aar"

# ── 1. 解开 AAR 当原料 ──
echo "▸ [1/6] 解开 AAR（classes.jar / res / assets）"
unzip -q -o "$AAR" -d "$STAGE/aar"

# ── 2. classes.jar → classes.dex ──
echo "▸ [2/6] d8: classes.jar → classes.dex"
mkdir -p "$STAGE/dex-src"
cp "$STAGE/aar/classes.jar" "$STAGE/dex-src/"
# --min-api 24 必须与 plugin-demo 的 minSdk 一致：编成更高 api 形态的 dex 会在
# 低版本设备上以 VerifyError/NoSuchMethodError 的形式炸，而且**是在类加载时才炸**。
"$D8" --lib "$ANDROID_JAR" --min-api 24 --output "$STAGE" "$STAGE/dex-src/classes.jar" >/dev/null
mv "$STAGE/classes.dex" "$WORK/classes.dex"
cp "$WORK/classes.dex" "$STAGE/classes.dex"

# ── 3. res/ → resources.arsc（aapt2 link）──
# AAR 里的 res/ 是**未编译**的 xml/png（source 形态），不能直接进 apk ——
# 必须 aapt2 compile→link 才算"插件自己的资源表"。
# 这一步也是"资源隔离"的技术前提：宿主读插件资源，读的就是这个 arsc。
echo "▸ [3/6] aapt2 compile + link → resources.arsc"
# ⚠️ aapt2 compile 的两种入口**互斥**：`--dir <目录>` 与「把文件列在后面」不能同时给
#    （会直接报 "files given but --dir specified"）。这里用 --dir，让 aapt2 自己扫，
#    也比 shell 拼文件列表更能正确处理子目录与 .9.png。
"$AAPT2" compile -o "$STAGE/compiled.zip" --dir "$STAGE/aar/res" >/dev/null
"$AAPT2" link -o "$STAGE/res-linked.apk" -I "$ANDROID_JAR" \
  --manifest "$MERGED_MANIFEST" --min-sdk-version 24 --target-sdk-version 34 \
  "$STAGE/compiled.zip" >/dev/null
# ⚠️ 连 **AndroidManifest.xml 一起**从 link 产物里取出来 —— 这里曾经踩过一个真坑：
#    link 会把它编译成 **二进制 XML**（RES_XML_TYPE，文件头 03 00 08 00），
#    而 MERGED_MANIFEST 是**文本** XML。早期脚本在组装阶段 `cp "$MERGED_MANIFEST"`，
#    等于把二进制 manifest 又换回了文本 —— 于是成品 apk 里躺着一份纯文本 manifest。
#    后果不是"加载不了"（我们的加载器不解析 manifest），而是**成品不再是一个合法 apk**：
#    连 `aapt2 dump resources <成品>.apk` 都会报
#    "failed to parse binary AndroidManifest.xml: failed to initialize ResXMLTree"，
#    等于把"这到底是不是一个正常 apk"的验证手段自己弄坏了。
#    对 Activity 形态更要紧：一旦将来真要让系统去读这个 manifest（而不是宿主占坑），
#    文本 manifest 会直接被 PMS 拒。
unzip -q -o "$STAGE/res-linked.apk" 'resources.arsc' 'res/*' 'AndroidManifest.xml' -d "$STAGE"
# 校验确实是二进制 XML（RES_XML_TYPE 以 03 00 开头），而不是文本。
# ⚠️ 这里刻意不用 `grep -q $'\x03\x00'`：bash 字符串**不能包含 NUL**，
#    那种写法会把参数截断成一个诡异的残句（本脚本曾经就被这么弄坏过）。
FIRST2="$(head -c 2 "$STAGE/AndroidManifest.xml" | od -An -tx1 | tr -d ' \n')"
if [[ "$FIRST2" != "0300" ]]; then
  echo "  ⚠️ 成品里的 AndroidManifest.xml 不是二进制 XML（首字节=$FIRST2）——" >&2
  echo "     宿主不解析它，但这样打出来的 apk 不是合法 apk，aapt2/PMS 都会拒。" >&2
fi
# ── 3b. 导出「资源名 → ID」对照表，塞进插件包 ──
# 为什么要有这个文件：宿主**不能**引用插件的 R（那会把插件编回宿主）。
# 但"同一个 ID 在插件里解析得到、在宿主里必失败"这条对照必须有个 ID 来源，
# 于是让插件**自己携带**这张表 —— 对照组的两边就都能拿到同一个数字。
# ⚠️ 这张表放在 **assets/** 下面，不是 apk 包根 —— 原因是一条实测出来的事实：
#    `AssetManager.open(name)` 走 nativeOpenAsset，**只查 assets 命名空间**
#    （android-34 的 AssetManager.java 里 open() → nativeOpenAsset，
#     openNonAsset() → nativeOpenAsset，两者是两个不同的 native 入口）。
#    换句话说：assets 前缀是**隐式**的（open("a.txt") 读的是 apk 的 assets/a.txt），
#    而 apk **包根**的文件用 open() 是**读不到**的，只有 openNonAsset(隐藏) /
#    openNonAssetFd(公开但要求未压缩) 能读。
#    所以这张宿主必须读到的小表，放进 assets/ 走公开 API；包根那条留给
#    plugin-root-probe.txt 当**实验**（见下）。
echo "▸ [3b/6] 导出 plugin-res-ids.txt（资源名 → ID）"
RES_DUMP="$WORK/res-dump.txt"
"$AAPT2" dump resources "$STAGE/res-linked.apk" > "$RES_DUMP" 2>/dev/null || true
mkdir -p "$STAGE/assets"
: > "$STAGE/assets/plugin-res-ids.txt"
for name in plugin_page plugin_title plugin_subtitle plugin_from_layout plugin_activity_label plugin_panel; do
  line="$(grep -E "resource 0x[0-9a-f]+ (layout|string|color)/$name\$" "$RES_DUMP" | head -1 || true)"
  if [[ -n "$line" ]]; then
    id="$(awk '{print $2}' <<< "$line")"
    type="$(awk '{print $3}' <<< "$line" | cut -d/ -f1)"
    echo "$type $name $id" >> "$STAGE/assets/plugin-res-ids.txt"
  fi
done
if ! grep -q "plugin_page" "$STAGE/assets/plugin-res-ids.txt"; then
  echo "  ⚠️ 没从 aapt2 dump 里解析到资源 ID —— 宿主页的"资源隔离对照"会退化为"
  echo "     "按名字列 assets，不再逐 ID 对照"。请检查 res-dump.txt 的格式。" >&2
fi
sed 's/^/   /' "$STAGE/assets/plugin-res-ids.txt"

# ── 4. assets / 包根文件 / manifest ──
echo "▸ [4/6] 装配 assets、包根文件与 manifest"
if [[ -d "$STAGE/aar/assets" ]]; then
  mkdir -p "$STAGE/assets"; cp -R "$STAGE/aar/assets"/. "$STAGE/assets/"
fi
# plugin-root-probe.txt：**故意**留在 apk 包根（不是 assets/），用来验证一条
# 容易搞错的边界（已对照 android-34 的 AssetManager.java 确认）：
#   · `open(name)` → nativeOpenAsset：**只查 assets 命名空间**，读包根必然失败
#   · `openNonAssetFd(name)` → 公开 API，能读整个 apk 包（含包根），
#     但**要求该条目未被压缩**，所以下面组装 zip 时用 `zip -0` 单独存它。
# 这一条是本 Lab 里"命名空间边界"的实验物证，不是可有可无的装饰文件。
if [[ -f "$STAGE/assets/plugin-root-probe.txt" ]]; then
  mv "$STAGE/assets/plugin-root-probe.txt" "$STAGE/plugin-root-probe.txt"
fi
# ⚠️ 这里**不再** `cp "$MERGED_MANIFEST"` —— 二进制 manifest 已经在 [3/6]
#    随 res-linked.apk 一起解出来了。若在此处用文本 manifest 覆盖它，
#    成品 apk 里就会是一份**纯文本** manifest：宿主加载不受影响（我们不解析它），
#    但 `aapt2 dump resources <成品>.apk` 会直接报
#    "failed to parse binary AndroidManifest.xml"，等于把"这是不是一个正常 apk"
#    的自检手段自己弄坏。保持二进制形态，成品才是一个**合法 apk**。

# ── 5. 组装 APK ──
echo "▸ [5/6] 组装 apk"
mkdir -p "$(dirname "$OUT_APK")"
rm -f "$OUT_APK"
ABS_OUT="$(cd "$(dirname "$OUT_APK")" && pwd)/$(basename "$OUT_APK")"
( cd "$STAGE" && zip -q -r -X "$ABS_OUT" . -x 'aar/*' -x 'dex-src/*' -x 'compiled.zip' -x 'res-linked.apk' -x '.*' -x 'plugin-root-probe.txt' )
# 包根探针必须单独以 STORED（不压缩）写入：`openNonAssetFd()` 走 mmap，
# 条目被 deflate 过就会抛 "This file can not be opened as a file descriptor;
# it is probably compressed"。所以先从上一条 bulk zip 里排除它，再在这里补写。
if [[ -f "$STAGE/plugin-root-probe.txt" ]]; then
  ( cd "$STAGE" && zip -q -0 "$ABS_OUT" plugin-root-probe.txt )
fi
rm -rf "$STAGE/aar" "$STAGE/dex-src"

# ── 6. 签名 + 元数据 ──
echo "▸ [6/6] 签名与元数据"
KEYSTORE="$HOME/.android/debug.keystore"
if [[ -f "$KEYSTORE" ]]; then
  # ⚠️ 签名对本演示的**两条加载链路都不起作用**：
  #    · 类  ：DexClassLoader 不校验签名，dex 能打开就加载；
  #    · 资源：AssetManager.addAssetPath 同样不校验。
  #    真正需要签名的是 **Activity 形态**：它最终要经由系统的 ActivityManager
  #    与 uid 校验路径（导出检查、权限继承），未签名/签名不匹配会以 SecurityException 出现。
  #    这里用 debug keystore 签名，就是为了让"签名到底影响哪一层"能被对照出来。
  jarsigner -keystore "$KEYSTORE" -storepass android -keypass android \
    -sigalg SHA256withRSA -digestalg SHA-256 "$OUT_APK" androiddebugkey >/dev/null 2>&1 \
    || echo "  ⚠️ jarsigner 失败（不影响 dex/资源两条链路，只影响 Activity 形态）"
else
  echo "  ⚠️ 没有 $KEYSTORE，跳过签名"
fi

SHA="$(shasum -a 256 "$OUT_APK" | awk '{print $1}')"
SIZE="$(stat -f%z "$OUT_APK" 2>/dev/null || stat -c%s "$OUT_APK")"

cat > "$WORK/$PLUGIN_XML" <<XML
<?xml version="1.0" encoding="utf-8"?>
<!--
  插件元数据 —— 「从网络接口下发插件」这条链路的契约文件。

  宿主下载 plugin APK 后，先按 sha256 核对字节（防止下到半截/被替换），
  再按 entryClass / activityClass 反射加载。真实插件化里这个文件通常由服务端
  连同下载地址一起下发（见 app 侧 PluginManifest 的注释）。
-->
<plugin
    id="$PLUGIN_ID"
    version="$PLUGIN_VERSION"
    sha256="$SHA"
    size="$SIZE"
    entryClass="$ENTRY_CLASS"
    activityClass="$ACTIVITY_CLASS"
    minSdk="24"
    builtAt="$(date -u +%Y-%m-%dT%H:%M:%SZ)" />
XML

if [[ "$EMBED" == "1" ]]; then
  mkdir -p app/src/main/assets
  cp "$OUT_APK" "$EMBED_TARGET"
  cp "$WORK/$PLUGIN_XML" "app/src/main/assets/$PLUGIN_XML"
  echo "▸ 已内嵌进宿主 assets: $EMBED_TARGET"
fi

# ── 报告：把"插件里到底装了什么"打出来，不让人靠猜 ──
echo
echo "════════════════════════════════════════════════════════════════"
echo " 插件 APK : $OUT_APK"
echo " 大小     : $SIZE bytes"
echo " sha256   : $SHA"
echo "────────────────────────────────────────────────────────────────"
echo " 内容清单："
unzip -l "$OUT_APK" | awk 'NR>3 && $4!="" {printf "   %8s  %s\n", $1, $4}'
echo "────────────────────────────────────────────────────────────────"
echo " 插件独有的类（宿主 dex 里**不该**出现这些名字，可用 dexdump 反查）："
"$DEXDUMP" -f "$WORK/classes.dex" 2>/dev/null \
  | grep -oE "Lcom/interview/plugindemo/[A-Za-z0-9/$]*;" | sort -u | sed 's/^/   /' | head -20
echo "────────────────────────────────────────────────────────────────"
# ⚠️ 这里必须区分「定义」与「引用」—— 早期版本把两者混为一谈，写成
#    "kotlin-stdlib 由 kotlin 插件自动带上"，是错的（实测定义数为 0）。
#    真实情况：dex 里**一个 kotlin/* 类都没被定义**，只有极少数被引用
#    （主要是 kotlin.jvm.internal.DefaultConstructorMarker 这类编译期产物）。
#    换句话说，插件对 kotlin-stdlib 的依赖是**运行时借宿主的**，不是打包带来的。
#    为什么能这么稳：dex 的类解析走的是"先 propio ClassLoader、再 parent"这条路，
#    而我们给插件的 DexClassLoader 的 parent 就是宿主 ClassLoader ——
#    kotlin/* 在宿主 dex 里已加载过，于是引用能解析成功。
#    反过来说，这也是一条**真实约束**：插件与宿主必须用兼容版本的 kotlin-stdlib，
#    否则会在运行期报 NoSuchMethodError 而不是编译失败。
#    这两行统计是判断该约束是否被违背的最快手段。
DEFINED_KOTLIN="$("$DEXDUMP" -f "$WORK/classes.dex" 2>/dev/null \
  | grep -oE "^ *[0-9]+ *: *Lkotlin/[A-Za-z0-9/$]*;" | sort -u | wc -l | tr -d ' ')"
REFERENCED_KOTLIN="$("$DEXDUMP" -f "$WORK/classes.dex" 2>/dev/null \
  | grep -oE "Lkotlin/[A-Za-z0-9/$]*;" | sort -u | wc -l | tr -d ' ')"
echo " dex 里 kotlin/* 类：定义 $DEFINED_KOTLIN 个 / 被引用 $REFERENCED_KOTLIN 个"
if [ "$DEFINED_KOTLIN" != "0" ]; then
  echo "   ⚠️ 定义数不为 0 —— 说明 stdlib 被真打进了插件包（通常是被 kotlin-stdlib 的"
  echo "      之类的东西带进来的）。这会与宿主的 stdlib 形成两份实现，务必确认是有意为之。"
else
  echo "   ↑ 定义数为 0 ⇒ 插件**没有**自带 stdlib，运行期靠宿主 ClassLoader 提供。"
fi
"$DEXDUMP" -f "$WORK/classes.dex" 2>/dev/null \
  | grep -oE "Lkotlin/[A-Za-z0-9/$]*;" | sort -u | sed 's/^/     引用: /' | head -5
echo "════════════════════════════════════════════════════════════════"
echo "下一步："
echo "  · 离线：# 已完成（宿主 assets 里已有内嵌副本）"
echo "  · 推送：tools/plugin-package/deploy.sh   → 送到宿主私有目录，可在页面上"换包""
echo "  · 网络：把 $OUT_APK 与 $PLUGIN_XML 放到任意 HTTPS 静态服务，"
echo "          在宿主页填入 URL 即可走"从网络接口下载插件""
