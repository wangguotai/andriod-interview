#!/usr/bin/env python3
"""
ncreport-symbolize.py —— 把 NCREPORT（我们的 native 崩溃取证报告）还原成「函数 + 行号」

════════════════════════════════════════════════════════════════════════════
为什么需要它（而不是直接用现成工具）
════════════════════════════════════════════════════════════════════════════

我们的报告是**自定义文本格式**（NCREPORT v1），Perfetto / Android Studio **都读不了** ——
这是设计取舍（见 native_crash_handler.cpp：信号处理器里不能做符号化）。
所以需要一层「格式转换 + 符号化」的桥：

    NCREPORT 文本  ──本脚本──▶  llvm-symbolizer  ──▶  函数 + 文件:行号
         │
         └── 也能导出 --format ndk-stack，喂给官方 ndk-stack / Android Studio

════════════════════════════════════════════════════════════════════════════
⭐ 核心难点：base.apk+0xNNN 的偏移换算
════════════════════════════════════════════════════════════════════════════

报告里的帧长这样：

    #00 0x0000007bea9085c8 base.apk+0x2b95c8      ← 注意模块名是 base.apk，不是 .so！
    #02 0x0000007d0ee67260 libsigchain.so+0x2260

`base.apk+0x…` 是「**后备文件内偏移**」（我们的 resolveFrame 刻意用这个口径，
见 native_crash_handler.cpp:810 的注释）—— 也就是说这个偏移是**整个 base.apk（一个 zip）
里的位置**，不是 ELF 内的偏移。

要符号化，必须换算成 ELF 内偏移：

    ELF_offset = apk_offset − zip_entry_data_start

其中 `zip_entry_data_start` 从 APK 的 zip 目录（central directory）里查，
这点和 tombstone 的做法一致（tombstone 会打印 `base.apk!libxxx.so` 把两者分开）。

⚠️ **实测核对过**（本仓真机样本 report-21691）：
    base.apk+0x2b8fd4  −  data_start 0x2b4000  =  0x4fd4
    → llvm-symbolizer → Java_..._nullDerefNative + 0x18
    → tombstone 同一帧写的是 `(Java_..._nullDerefNative+24)`   ✅ 完全吻合

════════════════════════════════════════════════════════════════════════════
用法
════════════════════════════════════════════════════════════════════════════

  # ① 符号化一份报告（自动找未 strip 的 so 和 APK）
  tools/ncreport-symbolize.py report-21691-*.txt

  # ② 指定 so 目录 / APK
  tools/ncreport-symbolize.py --libdir app/build/intermediates/.../obj/arm64-v8a \\
                              --apk app/build/outputs/apk/debug/app-debug.apk report.txt

  # ③ 导出 ndk-stack 格式，交给官方工具/Android Studio
  tools/ncreport-symbolize.py --format ndk-stack report.txt | \\
      $NDK/prebuilt/darwin-x86_64/bin/ndk-stack -sym <unstripped_dir>

  # ④ 只看崩溃线程
  tools/ncreport-symbolize.py --only-crash report.txt

⚠️ 必须用**未 strip** 的库（有 .symtab/.debug_*）。APK 里的是 strip 过的，只能给导出符号名。
"""

import argparse
import os
import re
import subprocess
import sys
import zipfile

# ── NCREPORT 解析 ─────────────────────────────────────────────────────────

RE_BACKTRACE = re.compile(r"^--- BACKTRACE (?P<name>.*) \(tid=(?P<tid>\d+)\) ---\s*$")
RE_FRAME = re.compile(r"^\s+#(?P<idx>\d+)\s+0x(?P<addr>[0-9a-fA-F]+)\s+(?P<module>\S+)\+0x(?P<off>[0-9a-fA-F]+)\s*$")
RE_FRAME_ANON = re.compile(r"^\s+#(?P<idx>\d+)\s+0x(?P<addr>[0-9a-fA-F]+)\s+\(anonymous\)\s*$")
RE_MAPS = re.compile(
    r"^(?P<start>[0-9a-f]+)-(?P<end>[0-9a-f]+)\s+"
    r"(?P<perm>[-rwxps]+)\s+(?P<fileoff>[0-9a-f]+)\s+\S+\s+\d+\s+(?P<path>/\S+)\s*$"
)


def parse_report(path):
    """解析 NCREPORT → (header dict, [线程], [maps])"""
    header, threads, maps = {}, [], []
    cur = None
    section = None
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for lineno, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if line == "NCREPORT v1":
                continue
            m = RE_BACKTRACE.match(line)
            if m:
                cur = {"name": m.group("name").strip(), "tid": int(m.group("tid")), "frames": []}
                threads.append(cur)
                section = "backtrace"
                continue
            if line.startswith("--- MAPS ---"):
                section = "maps"
                cur = None
                continue
            if line.startswith("--- FD ---"):
                section = "fd"
                continue
            if line.startswith("--- STATUS ---"):
                section = "status"
                continue
            if line.startswith("--- CMDLINE ---") or line == "--- END ---":
                section = None
                continue

            if section == "backtrace" and cur is not None:
                m = RE_FRAME.match(line)
                if m:
                    cur["frames"].append({
                        "idx": int(m.group("idx")),
                        "addr": int(m.group("addr"), 16),
                        "module": m.group("module"),
                        "offset": int(m.group("off"), 16),
                    })
                    continue
                m = RE_FRAME_ANON.match(line)
                if m:
                    cur["frames"].append({
                        "idx": int(m.group("idx")),
                        "addr": int(m.group("addr"), 16),
                        "module": "(anonymous)",
                        "offset": None,
                    })
                    continue
                continue

            if section == "maps":
                m = RE_MAPS.match(line)
                if m:
                    maps.append({
                        "start": int(m.group("start"), 16),
                        "end": int(m.group("end"), 16),
                        "perm": m.group("perm"),
                        "fileoff": int(m.group("fileoff"), 16),
                        "path": m.group("path"),
                    })
                    continue

            # header（第一个 --- 之前）
            if section is None and "=" in line:
                k, _, v = line.partition("=")
                header[k.strip()] = v.strip()
    return header, threads, maps


# ── APK → ELF 偏移换算 ────────────────────────────────────────────────────

class ApkOffsetResolver:
    """把 base.apk 的「backing-file 偏移」换算成「ELF 内偏移」。

    实测必要性：报告里 57/333 帧（17%）是 base.apk 帧，现有 native-mem-symbolize.sh
    的正则 `\\.so\\+0x` **完全匹配不到它们**，会被静默丢弃。
    """

    def __init__(self, apk_path):
        self.apk_path = apk_path
        self.entries = []          # (data_start, data_end, name)
        if apk_path and os.path.isfile(apk_path):
            with zipfile.ZipFile(apk_path) as z:
                for i in z.infolist():
                    if not i.filename.endswith(".so"):
                        continue
                    ds = i.header_offset + 30 + len(i.filename.encode()) + len(i.extra)
                    self.entries.append((ds, ds + i.file_size, i.filename))

    def resolve(self, apk_off):
        """→ (elf_offset, so_name) 或 (None, None)"""
        for ds, de, name in self.entries:
            if ds <= apk_off < de:
                return apk_off - ds, os.path.basename(name)
        return None, None


# ── 符号化 ────────────────────────────────────────────────────────────────

class Symbolizer:
    def __init__(self, libdir, symbolizer, extra_dirs, apk):
        self.libdir = libdir
        self.symbolizer = symbolizer
        self.extra_dirs = extra_dirs
        self.apk = ApkOffsetResolver(apk)
        self.cache = {}

    def _find_so(self, name):
        for d in [self.libdir] + self.extra_dirs:
            if not d:
                continue
            p = os.path.join(d, name)
            if os.path.isfile(p):
                return p
            # base.apk!libxxx.so → 也试去前缀
            base = name.split("!")[-1]
            p = os.path.join(d, base)
            if os.path.isfile(p):
                return p
        return None

    def symbolize(self, module, offset):
        """→ (结果字符串, 是否成功)"""
        if offset is None:
            return "(anonymous) —— 无后备文件，无法符号化", False

        so_name = module
        elf_off = offset

        # ⭐ base.apk 帧：先做 zip → ELF 换算
        if module.endswith(".apk") or module == "base.apk":
            elf_off, inner = self.apk.resolve(offset)
            if elf_off is None:
                return (f"⚠️ 偏移 0x{offset:x} 不在 {module} 的任何 .so 条目内 —— "
                        f"请用 --apk 指定正确的 APK"), False
            so_name = inner

        key = (so_name, elf_off)
        if key in self.cache:
            return self.cache[key]

        so_path = self._find_so(so_name)
        if not so_path:
            r = (f"⚠️ 本机未找到未 strip 的 {so_name}（用 --libdir 指定）"
                 + (f"  [apk内偏移 0x{elf_off:x}]" if module.endswith(".apk") else "")), False
            self.cache[key] = r
            return r

        try:
            out = subprocess.run(
                [self.symbolizer, "--obj=" + so_path, "--functions=short", "--inlines",
                 hex(elf_off)],
                capture_output=True, text=True, timeout=20,
            ).stdout.strip()
        except Exception as e:
            r = f"⚠️ llvm-symbolizer 调用失败：{e}", False
            self.cache[key] = r
            return r

        if not out or "??" in out.splitlines()[0]:
            r = (f"⚠️ {so_name} 无符号信息（strip 过）—— 需要**未 strip** 的库；"
                 f"系统库需 build-id 匹配的 symbol file"), False
        else:
            lines = [l for l in out.splitlines() if l.strip()]
            r = " | ".join(lines[:3]) + (f"  (inlined, {len(lines)-2} frames)" if len(lines) > 3 else "")
            r = r, True
            self.cache[key] = r
            return r

        self.cache[key] = r
        return r


# ── 输出 ─────────────────────────────────────────────────────────────────

def out_ndk_stack(header, threads, maps):
    """导出成 ndk-stack / tombstone 风格的文本，交给官方工具链。"""
    print(f"*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***")
    print(f"pid: {header.get('pid','?')}, tid: {header.get('tid','?')}, "
          f"name: {header.get('thread_name','?')}")
    print(f"signal {header.get('signal','?')} ({header.get('code','?')}), "
          f"fault addr {header.get('fault_addr','?')}")
    for t in threads:
        print(f"    #00 pc {0:08x}  {t['name']} (tid={t['tid']})")
        for f in t["frames"]:
            mod = f["module"]
            off = f["offset"]
            if off is None:
                print(f"        #{f['idx']:02d} pc {f['addr']:016x}  (anonymous)")
            else:
                print(f"        #{f['idx']:02d} pc {off:08x}  {mod}")
    print(f"*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***")


def main():
    ap = argparse.ArgumentParser(description="把 NCREPORT native 崩溃报告符号化")
    ap.add_argument("report", help="NCREPORT 文本文件")
    ap.add_argument("--libdir", default=None, help="未 strip 的 .so 目录")
    ap.add_argument("--extra-libdir", action="append", default=[], help="附加 .so 目录（可多次）")
    ap.add_argument("--apk", default=None, help="APK 路径（解析 base.apk 帧必需）")
    ap.add_argument("--symbolizer", default=None, help="llvm-symbolizer 路径")
    ap.add_argument("--format", choices=["text", "ndk-stack"], default="text")
    ap.add_argument("--only-crash", action="store_true", help="只输出崩溃线程")
    ap.add_argument("--max-frames", type=int, default=0, help="每线程最多输出多少帧（0=全部）")
    args = ap.parse_args()

    if not os.path.isfile(args.report):
        print(f"错误：找不到报告 {args.report}", file=sys.stderr)
        return 2

    header, threads, maps = parse_report(args.report)
    if not threads:
        print("错误：报告里没有解析到 BACKTRACE 段 —— 格式不匹配？", file=sys.stderr)
        return 2

    if args.format == "ndk-stack":
        out_ndk_stack(header, threads, maps)
        return 0

    # 自动找一个 llvm-symbolizer
    sym = args.symbolizer
    if not sym:
        cands = []
        for root in (os.environ.get("ANDROID_NDK_HOME", ""),
                     os.path.expanduser("~/Library/Android/sdk/ndk")):
            if not root or not os.path.isdir(root):
                continue
            for dirpath, _, files in os.walk(root):
                if "llvm-symbolizer" in files:
                    cands.append(os.path.join(dirpath, "llvm-symbolizer"))
        sym = cands[0] if cands else "llvm-symbolizer"

    # 自动找 libdir
    libdir = args.libdir
    if not libdir:
        for root in ("app/build/intermediates/cxx", "app/build/intermediates/cmake"):
            if not os.path.isdir(root):
                continue
            for dirpath, _, files in os.walk(root):
                if "libnativecrash.so" in files:
                    libdir = dirpath
                    break
            if libdir:
                break

    print("═" * 74)
    print(f"NCREPORT 符号化：{os.path.basename(args.report)}")
    print(f"  pid={header.get('pid','?')} tid={header.get('tid','?')} "
          f"signal={header.get('signal','?')} code={header.get('code','?')}")
    print(f"  fault_addr={header.get('fault_addr','?')}  "
          f"thread={header.get('thread_name','?')}")
    print(f"  version={header.get('version_name','?')}({header.get('version_code','?')})  "
          f"android={header.get('android_release','?')} sdk={header.get('sdk','?')}")
    print(f"  abort_message={header.get('abort_message','-')}")
    print(f"  uptime_ms={header.get('uptime_ms','?')}")
    print(f"  symbolizer={sym}")
    print(f"  libdir={libdir or '<未找到>'}")
    print(f"  apk={args.apk or '<未指定，base.apk 帧将无法解析>'}")
    print("═" * 74)

    s = Symbolizer(libdir, sym, args.extra_libdir, args.apk)
    missing_apk = 0
    for t in threads:
        if args.only_crash and t["tid"] != int(header.get("tid", -1)):
            continue
        print(f"\n── {t['name']} (tid={t['tid']}) ──")
        frames = t["frames"]
        if args.max_frames:
            frames = frames[: args.max_frames]
        for f in frames:
            if f["module"].endswith(".apk") and not args.apk:
                missing_apk += 1
            res, ok = s.symbolize(f["module"], f["offset"])
            mod = f["module"] if f["offset"] is None else f"{f['module']}+0x{f['offset']:x}"
            mark = " " if ok else "!"
            print(f"  {mark} #{f['idx']:02d} {mod:<34} {res}")

    if missing_apk:
        print(f"\n⚠️ 有 {missing_apk} 个 base.apk 帧未解析 —— "
              f"用 --apk <你的.apk> 指定 APK 即可（这是 17% 的帧，别漏）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
