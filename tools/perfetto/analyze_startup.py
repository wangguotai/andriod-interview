#!/usr/bin/env python3
"""命令行分析 Android 冷启动 trace：相位耗时 + 静态代码块(<clinit>)耗时。

配合 tools/perfetto/record-startup.sh 使用。

    tools/perfetto/analyze_startup.py out/xxx-startup.perfetto out/xxx-startup.art.trace
    tools/perfetto/analyze_startup.py <相位 trace> [ART trace] [--pkg com.example.myapplication]

两条 trace 的分工（这是本工具存在的理由）：
  · 相位 trace（Perfetto ftrace）：进程创建 → bindApplication → activityStart
    → performCreate → 首帧。回答"启动时间花在哪个阶段"。
  · ART 方法 trace（am start --start-profiler）：每个方法（含 <clinit>）的
    真实墙钟。回答"静态代码块到底是谁、多少毫秒"。
    ⚠️ PERFETTO 的 dalvik 分类不产出 Java <clinit> 切片，所以静态块只能靠这条。

依赖：
  · trace_processor_shell（Perfetto 官方二进制）。查找顺序：
      --tp 参数 → $PERFETTO_TP → tools/perfetto/bin/trace_processor_shell
      → PATH → 自动下载（storage.googleapis.com 可达时）到 tools/perfetto/bin/
  · Python 包 `perfetto`（走 RPC，结果不做列宽截断）。
    未安装时用仓库约定的 `uv run --with perfetto` 自动重入。
"""
import argparse
import os
import platform
import shutil
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
BIN_DIR = os.path.join(HERE, "bin")

TP_VERSION = "v49.0"
TP_URLS = {
    ("Darwin", "arm64"): f"https://storage.googleapis.com/perfetto-luci-artifacts/{TP_VERSION}/mac-arm64/trace_processor_shell",
    ("Darwin", "x86_64"): f"https://storage.googleapis.com/perfetto-luci-artifacts/{TP_VERSION}/mac-amd64/trace_processor_shell",
    ("Linux", "x86_64"): f"https://storage.googleapis.com/perfetto-luci-artifacts/{TP_VERSION}/linux-amd64/trace_processor_shell",
    ("Linux", "aarch64"): f"https://storage.googleapis.com/perfetto-luci-artifacts/{TP_VERSION}/linux-arm64/trace_processor_shell",
}


def ensure_perfetto_pkg():
    try:
        import perfetto  # noqa: F401
        return
    except ImportError:
        pass
    if os.environ.get("_ANALYZE_REEXEC") == "1":
        sys.exit("缺少 Python 包 perfetto，且重入后仍不可用。请手动：uv run --with perfetto python "
                 + os.path.abspath(__file__))
    if shutil.which("uv") is None:
        sys.exit("缺少 Python 包 perfetto，且未找到 uv。请先 `uv run --with perfetto python "
                 + os.path.abspath(__file__) + "`")
    os.environ["_ANALYZE_REEXEC"] = "1"
    os.execvp("uv", ["uv", "run", "--with", "perfetto", "python",
                     os.path.abspath(__file__), *sys.argv[1:]])


def find_tp(explicit):
    if explicit:
        return explicit
    if os.environ.get("PERFETTO_TP"):
        return os.environ["PERFETTO_TP"]
    local = os.path.join(BIN_DIR, "trace_processor_shell")
    if os.path.exists(local):
        return local
    found = shutil.which("trace_processor_shell")
    if found:
        return found
    key = (platform.system(), platform.machine())
    url = TP_URLS.get(key)
    if not url:
        sys.exit(f"找不到 trace_processor_shell，且不支持当前平台 {key}。请用 --tp 指定。")
    os.makedirs(BIN_DIR, exist_ok=True)
    print(f"· 首次运行：下载 trace_processor_shell（{TP_VERSION}）…", file=sys.stderr)
    try:
        urllib.request.urlretrieve(url, local)
    except Exception as e:  # noqa: BLE001
        sys.exit(f"下载失败：{e}\n  可手动下载后放到 {local}，或用 --tp 指定。")
    os.chmod(local, 0o755)
    return local


def rows(tp, sql):
    try:
        it = tp.query(sql)
        return [tuple(getattr(r, c) for c in it.column_names) for r in it]
    except Exception:  # noqa: BLE001
        return None


def table(header, data, max_rows=25):
    if not data:
        print("  （无数据）")
        return
    widths = [max(len(str(h)), *(len(str(r[i])) for r in data[:max_rows])) for i, h in enumerate(header)]
    print("  " + "  ".join(str(h).ljust(widths[i]) for i, h in enumerate(header)))
    print("  " + "  ".join("-" * widths[i] for i in range(len(header))))
    for r in data[:max_rows]:
        print("  " + "  ".join(str(r[i]).ljust(widths[i]) for i in range(len(header))))
    if len(data) > max_rows:
        print(f"  …（共 {len(data)} 行）")


def analyze_phase(tp, pkg):
    print("=" * 74)
    print("[A] 启动相位（Perfetto ftrace）")
    print("=" * 74)

    # 主线程定位：优先 process.name + is_main_thread，退回线程名兜底
    utid = None
    r = rows(tp, f"select t.utid, t.tid, t.name from thread t join process p using(upid) "
                 f"where p.name = '{pkg}' and t.is_main_thread = 1 limit 1")
    if r:
        utid, tid, tname = r[0]
        print(f"app 主线程：utid={utid} tid={tid} name={tname}")
    else:
        tail = pkg.split(".")[-1]
        r = rows(tp, f"select utid, tid, name from thread where is_main_thread=1 and name like '%{tail}%' limit 1")
        if r:
            utid, tid, tname = r[0]
            print(f"app 主线程：utid={utid} tid={tid} name={tname}（由线程名兜底；process.name 可能为空）")
    if utid is None:
        print("⚠ 无法定位主线程：确认抓取顺序（先起 perfetto 再拉进程）与 process_stats 配置。")
        return

    # 关键相位
    print("\n关键相位（主线程，>1ms）")
    phase = rows(tp, f"""
        select name, round(sum(dur)/1e6,2) total_ms, round(max(dur)/1e6,2) max_ms
        from slice where track_id in (select id from thread_track where utid={utid})
        and dur is not null and dur > 1000000
        and (name glob 'bindApplication' or name glob 'app.onCreate' or name glob 'activityStart'
             or name glob 'performCreate*' or name glob 'performResume*'
             or name glob 'Choreographer#doFrame*' or name glob 'traversal'
             or name glob 'inflate' or name glob 'layout' or name glob 'measure'
             or name glob 'RV *' or name glob 'Startup' or name glob 'setSystemFontMap'
             or name glob '*ResourcesManager*')
        group by name order by sum(dur) desc limit 25""")
    table(["name", "total_ms", "max_ms"], phase or [])

    # 首帧
    r = rows(tp, f"select round(max(dur)/1e6,2) from slice where track_id in "
                 f"(select id from thread_track where utid={utid}) and name glob 'Choreographer#doFrame*'")
    if r and r[0][0]:
        print(f"\n最长一帧 (Choreographer#doFrame)：{r[0][0]} ms   ← 冷启动首帧通常最贵")
    print("\n提示：把相位 trace 拖进 https://ui.perfetto.dev 可交互看时间轴。")


def analyze_art(tp, pkg, top_n):
    print()
    print("=" * 74)
    print("[B] 静态代码块 / 类初始化（ART 方法 trace —— Perfetto 抓不到，故单独一条）")
    print("=" * 74)

    n = rows(tp, "select count(*) from slice")
    if not n or not n[0][0]:
        print("⚠ ART trace 为空或不是方法 trace。确认应用是 debuggable、且用 --start-profiler 抓的。")
        return
    print(f"方法记录总数：{n[0][0]}")

    # 全部 clinit
    tot = rows(tp, "select count(*) n, round(sum(dur)/1e6,2) ms from slice where name glob '*<clinit>*'")
    if tot and tot[0][0]:
        print(f"<clinit> 个数：{tot[0][0]}   累计墙钟：{tot[0][1]} ms")

    print("\n【Top <clinit>（按墙钟，全部包）】")
    allc = rows(tp, "select name, round(dur/1e6,2) ms from slice where name glob '*<clinit>*' "
                    f"order by dur desc limit {top_n}")
    table(["class.<clinit>", "ms"], allc or [])

    # 自有代码
    appc = rows(tp, "select name, round(dur/1e6,2) ms from slice where name glob '*<clinit>*' "
                    "and (name glob '*com.interview*' or name glob '*com.example*' or name glob '*com.wgt*') "
                    f"order by dur desc limit {top_n}")
    if appc:
        s = rows(tp, "select round(sum(dur)/1e6,2) from slice where name glob '*<clinit>*' "
                     "and (name glob '*com.interview*' or name glob '*com.example*' or name glob '*com.wgt*')")
        print(f"\n【自有代码 <clinit>（合计 {s[0][0] if s else '?'} ms）】")
        table(["class.<clinit>", "ms"], appc)
    print("\n判读：单个 <clinit> 超过 ~5ms 就值得拆。最常见的元凶是")
    print("  · SimpleDateFormat（要加载时区库）—— 改成线程局部或复用")
    print("  · 大常量表 / 一次性 IO / 反射 —— 移到后台或懒加载")

    # 顺带给出 Application.onCreate 与首帧 Activity 的 children
    print("\n【Application.onCreate / Activity.onCreate 内部（前 15 条子调用）】")
    r = rows(tp, "select name from slice where name glob '*MyApplication.onCreate*' limit 1")
    if r:
        ts = rows(tp, f"select ts, dur from slice where name='{r[0][0]}' limit 1")[0]
        ch = rows(tp, f"select s.name, round(s.dur/1e6,2) ms from slice s "
                      f"where s.ts >= {ts[0]} and s.ts < {ts[0]}+{ts[1]} "
                      f"and s.depth = (select min(depth) from slice where ts={ts[0]} and name like '%onCreate%')+1 "
                      f"order by s.dur desc limit 15")
        table(["onCreate 直接子调用", "ms"], ch or [])


def main():
    ap = argparse.ArgumentParser(description="分析 Android 冷启动 trace（相位 + 静态块）")
    ap.add_argument("phase_trace", help="相位 trace（record-startup.sh 产出的 *.perfetto）")
    ap.add_argument("art_trace", nargs="?", default=None,
                    help="ART 方法 trace（*.art.trace）；缺省则跳过静态块分析")
    ap.add_argument("--pkg", default="com.example.myapplication", help="应用包名")
    ap.add_argument("--top", type=int, default=25, help="静态块排行条数")
    ap.add_argument("--tp", default=None, help="trace_processor_shell 路径")
    args = ap.parse_args()

    for p in filter(None, [args.phase_trace, args.art_trace]):
        if not os.path.exists(p):
            sys.exit(f"trace 不存在：{p}")

    ensure_perfetto_pkg()
    from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig

    tp_path = find_tp(args.tp)
    pkg = args.pkg.replace("'", "''")

    tp = TraceProcessor(trace=args.phase_trace, config=TraceProcessorConfig(bin_path=tp_path))
    analyze_phase(tp, pkg)

    if args.art_trace:
        tp2 = TraceProcessor(trace=args.art_trace, config=TraceProcessorConfig(bin_path=tp_path))
        analyze_art(tp2, pkg, args.top)
    else:
        print("\n（未提供 ART 方法 trace，跳过静态块分析。用 record-startup.sh 一并抓取即可。）")


if __name__ == "__main__":
    main()
