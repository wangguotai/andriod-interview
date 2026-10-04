#!/usr/bin/env python3
"""命令行分析 Perfetto trace：滚动卡顿到底该算谁头上。

配合 tools/perfetto/record-scroll.sh 使用。

    tools/perfetto/analyze_trace.py tools/perfetto/out/xxx-scroll.perfetto
    tools/perfetto/analyze_trace.py <trace> --pkg com.example.myapplication

依赖：
  · trace_processor_shell（Perfetto 官方二进制）。查找顺序：
      --tp 参数 → $PERFETTO_TP → tools/perfetto/bin/trace_processor_shell
      → PATH → 自动下载（storage.googleapis.com 可达时）到 tools/perfetto/bin/
  · Python 包 `perfetto`（走 RPC 查询，结果不做列宽截断）。
    未安装时脚本会用仓库约定的 `uv run --with perfetto` 自动重入。

它想回答的第一个问题不是「app 慢在哪」，而是「这些 jank 是不是 app 造成的」。
FrameTimeline 的 jank_type 会给出归属，避免把模拟器的 SurfaceFlinger 软渲染
（SurfaceFlinger CPU Deadline Missed / Buffer Stuffing / Prediction Error）
误当成自己的性能问题去优化。
"""
import argparse
import os
import platform
import shutil
import subprocess
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
BIN_DIR = os.path.join(HERE, "bin")

TP_VERSION = "v49.0"  # 与开发机 adb 里的 perfetto 大版本对齐
TP_URLS = {
    ("Darwin", "arm64"): f"https://storage.googleapis.com/perfetto-luci-artifacts/{TP_VERSION}/mac-arm64/trace_processor_shell",
    ("Darwin", "x86_64"): f"https://storage.googleapis.com/perfetto-luci-artifacts/{TP_VERSION}/mac-amd64/trace_processor_shell",
    ("Linux", "x86_64"): f"https://storage.googleapis.com/perfetto-luci-artifacts/{TP_VERSION}/linux-amd64/trace_processor_shell",
    ("Linux", "aarch64"): f"https://storage.googleapis.com/perfetto-luci-artifacts/{TP_VERSION}/linux-arm64/trace_processor_shell",
}


def ensure_perfetto_pkg():
    """确保能 import perfetto；否则用 uv 自动重入（仓库约定 uv run --with）。"""
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
        sys.exit(f"下载失败：{e}\n  可手动下载后放到 {local}，或用 --tp 指定。\n"
                 f"  （commondatastorage 可能不通；storage.googleapis.com 通常可达。）")
    os.chmod(local, 0o755)
    return local


def rows(tp, sql):
    """执行一条 SQL，返回 [(col, ...), ...]；表/列不存在时返回 None。"""
    try:
        it = tp.query(sql)
        return [tuple(getattr(r, c) for c in it.column_names) for r in it]
    except Exception:  # noqa: BLE001
        return None


def table(header, data, max_rows=20):
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


def main():
    ap = argparse.ArgumentParser(description="分析 Perfetto 滚动 trace")
    ap.add_argument("trace", help="trace 文件路径")
    ap.add_argument("--pkg", default="com.example.myapplication", help="应用包名（用于定位主线程）")
    ap.add_argument("--tp", default=None, help="trace_processor_shell 路径")
    args = ap.parse_args()

    if not os.path.exists(args.trace):
        sys.exit(f"trace 不存在：{args.trace}")

    ensure_perfetto_pkg()
    from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig

    tp_path = find_tp(args.tp)
    tp = TraceProcessor(trace=args.trace, config=TraceProcessorConfig(bin_path=tp_path))

    pkg = args.pkg.replace("'", "''")

    print("=" * 72)
    print(f"trace: {os.path.basename(args.trace)}   pkg: {args.pkg}")
    print("=" * 72)

    # ── 0. 基本信息 ──
    meta = rows(tp, "select (select count(*) from expected_frame_timeline_slice) e, "
                    "(select count(*) from actual_frame_timeline_slice) a")
    if meta:
        print(f"帧数：expected={meta[0][0]}  actual={meta[0][1]}")
        if not meta[0][1]:
            print("⚠ 没有 FrameTimeline 数据：确认配置含 android.surfaceflinger.frametimeline，")
            print("  且抓取期间应用确实在前台并产生了窗口绘制。")
    else:
        print("⚠ trace 里没有 FrameTimeline 表。")

    # ── 1. jank 归因（最重要的一步）──
    print("\n[1] jank 归因：这些卡顿该算谁的？")
    jank = rows(tp, "select jank_type, count(*) n from actual_frame_timeline_slice "
                    "where jank_type is not null and jank_type not in ('None','') "
                    "group by jank_type order by n desc")
    table(["jank_type", "n"], jank or [])
    app_like = sum(r[1] for r in (jank or []) if "App Deadline Missed" in str(r[0]))
    sf_like = sum(r[1] for r in (jank or []) if "SurfaceFlinger" in str(r[0]))
    buf_like = sum(r[1] for r in (jank or []) if "Buffer Stuffing" in str(r[0]))
    other_like = sum(r[1] for r in (jank or []) if "Dropped Frame" in str(r[0]))
    print()
    print("  注：jank_type 可能是复合值（多项逗号拼接），下方分类按子串统计，会互相重叠。")
    print(f"  app 侧超时（App Deadline Missed）  ：{app_like}")
    print(f"  SurfaceFlinger 超时                ：{sf_like}   ← 模拟器软渲染常见，通常不是你的代码")
    print(f"  Buffer Stuffing（缓冲积压）        ：{buf_like}   ← 同为合成侧")
    print(f"  其它（Dropped Frame 等）           ：{other_like}")
    if app_like == 0 and (sf_like + buf_like) > 0:
        print("  ⇒ 本 trace 未见 app 侧超时。瓶颈在图形合成/调度，优化 RecyclerView 收益很低。")
    elif app_like > 0:
        print(f"  ⇒ 有 {app_like} 帧是 app 真的超时了，继续看第 [3] 步找主线程瓶颈。")
    else:
        print("  ⇒ 基本没有可归因的 jank。")

    # ── 2. 定位 app 主线程 ──
    # 铁律：主线程不是「slice 最多的那个线程」——app 进程里 RenderThread 的 slice
    # 通常多得多。用 thread.is_main_thread 才准。
    utid = None
    r = rows(tp, f"select t.utid, t.tid, t.name from thread t join process p using(upid) "
                 f"where p.name = '{pkg}' and t.is_main_thread = 1 limit 1")
    if r:
        utid = r[0][0]
        print(f"\n[2] app 主线程：utid={utid} tid={r[0][1]} name={r[0][2]} (进程名 + is_main_thread)")
    else:
        r = rows(tp, "select distinct tt.utid from slice s join thread_track tt on s.track_id = tt.id "
                     "where s.name like 'draw-VRI[%' limit 1")
        if r:
            utid = r[0][0]
            print(f"\n[2] app 主线程：utid={utid}（由 draw-VRI[ 反查；"
                  f"process.name 为空 → 抓取配置缺 linux.process_stats）")
        else:
            print("\n[2] ⚠ 无法定位 app 主线程：trace 里既没有进程名也没有 draw-VRI。")
            print("    多设备时请用 record-scroll.sh -s <serial> 指定设备。")

    if utid is None:
        print("\n无法继续下钻，结束。")
        return

    def scalar(sql):
        rr = rows(tp, sql)
        if not rr or rr[0][0] is None:
            return None
        try:
            return float(rr[0][0])
        except (TypeError, ValueError):
            return None

    # ── 3. app 主线程最耗时的 slice ──
    print(f"\n[3] app 主线程最耗时 slice（utid={utid}）")
    top = rows(tp, f"select name, count(*) n, round(max(dur)/1e6,2) max_ms, round(sum(dur)/1e6,1) total_ms "
                    f"from slice where track_id in (select id from thread_track where utid={utid}) "
                    f"and dur is not null group by name order by sum(dur) desc limit 15")
    table(["name", "n", "max_ms", "total_ms"], top or [])

    w = scalar(f"select sum(dur)/1e6 from slice "
               f"where track_id in (select id from thread_track where utid={utid}) "
               f"and (name like 'postAndWait%' or name like '%dequeueBuffer%')")
    t = scalar(f"select sum(dur)/1e6 from slice "
               f"where track_id in (select id from thread_track where utid={utid}) and name = 'traversal'")
    if w is not None and t:
        print(f"\n  traversal 总计 {t:.1f} ms，其中等在 buffer 上 {w:.1f} ms（{100*w/t:.0f}%）。")
        if w / t > 0.5:
            print("  ⇒ 主线程大部分时间在等 buffer（合成侧受限），不是 bind/measure 慢。")
    elif w is not None:
        print(f"\n  主线程等在 buffer 上 {w:.1f} ms（本 trace 未见 traversal，可能没发生窗口重绘遍历）。")

    # ── 4. RecyclerView 自带埋点 ──
    print("\n[4] RecyclerView 自带埋点（1.3.2 源码里的 TraceCompat 段）")
    rv = rows(tp, "select name, count(*) n, round(sum(dur)/1e6,2) total_ms, round(max(dur)/1e6,2) max_ms "
                  "from slice where name in ('RV OnBindView','RV CreateView','RV OnLayout','RV Scroll',"
                  "'RV FullInvalidate','RV PartialInvalidate','RV Prefetch','RV Nested Prefetch') "
                  "group by name order by sum(dur) desc")
    table(["name", "n", "total_ms", "max_ms"], rv or [])
    full = next((x for x in (rv or []) if x[0] == "RV FullInvalidate"), None)
    if full and full[1]:
        print(f"  ⚠ 出现 RV FullInvalidate × {full[1]}（notifyDataSetChanged 全量重排）。")
        print("     列表更新建议改 DiffUtil / ListAdapter，配合 payload 做局部刷新。")
    else:
        print("  ✓ 未见 RV FullInvalidate，更新方式没有走全量刷新。")

    print("\n提示：交互式分析请把 trace 拖进 https://ui.perfetto.dev。")


if __name__ == "__main__":
    main()
