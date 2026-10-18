#!/usr/bin/env python3
"""
analyze-crash.py —— 用 trace_processor 查询一份「崩溃现场 trace」，给出可读结论。

设计前提（实测确认）：
  · Perfetto **没有 crash 数据源** —— 它抓不到崩溃事件本身
    （设备 perfetto --query-raw 的全部 data source 里没有任何 crash/tombstone/signal）
  · 所以本脚本回答的是「**死之前发生了什么**」，而不是「怎么死的」
  · 「怎么死的」在 logcat/tombstone 里 —— 本脚本会提示去看哪个文件的哪一段

判读的三类指纹（真机实测）：
  · Java 崩溃  → 主线程 state 变 `Z`，其他线程仍在（ART 逐个 detach）
  · Native 崩溃 → **所有线程同一刻变 `X`**（集体消亡）
  · OOM        → 堆曲线顶死 + `GC: Wait For Completion Alloc`
"""
import argparse
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TPS = os.path.join(HERE, "bin", "trace_processor_shell")


def q(trace, sql):
    """跑一条 SQL，返回行列表（跳过表头）。trace_processor -Q 输出 CSV。"""
    r = subprocess.run([TPS, trace, "-Q", sql], capture_output=True, text=True, timeout=600)
    import csv, io
    rows = list(csv.reader(io.StringIO(r.stdout)))
    # 第一行是列名；空行/进度行由 csv 解析后过滤
    rows = [row for row in rows if row and row[0] != ""]
    return rows[1:] if rows else []


def table(rows, header):
    if not rows:
        print("    (无数据)")
        return
    ncol = len(header)
    rows = [list(r)[:ncol] + [""] * max(0, ncol - len(r)) for r in rows]
    widths = [len(h) for h in header]
    for r in rows:
        for i, c in enumerate(r):
            widths[i] = max(widths[i], len(str(c)))
    print("    " + "  ".join(h.ljust(widths[i]) for i, h in enumerate(header)))
    print("    " + "  ".join("-" * w for w in widths))
    for r in rows:
        print("    " + "  ".join(str(c).ljust(widths[i]) for i, c in enumerate(r)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("trace")
    ap.add_argument("--pid", type=int, default=0, help="崩溃进程 pid（不给则自动找 example 包）")
    args = ap.parse_args()

    if not os.path.isfile(args.trace):
        print(f"错误：找不到 {args.trace}", file=sys.stderr); return 2
    if not os.path.isfile(TPS):
        print(f"错误：找不到 trace_processor_shell（{TPS}）", file=sys.stderr); return 2

    t = args.trace
    print("═" * 78)
    print(f"崩溃现场分析：{os.path.basename(t)}")
    print("═" * 78)

    # 总览
    dur = q(t, "select round((max(ts)-min(ts))/1e9,2) from slice")
    nslice = q(t, "select count(*) from slice")
    total_s = float(dur[0][0]) if dur else 0.0
    print(f"\n【总览】时长 {total_s}s，slice 数 {nslice[0][0] if nslice else '?'}")

    # 数据源体检
    print("\n【数据源体检】")
    checks = [
        ("GC 切片(dalvik)", "select count(*) from slice where name like '%copying GC%'"),
        ("堆曲线(Heap size)", "select count(*) from counter_track where name='Heap size (KB)'"),
        ("heapprofd 样本", "select count(*) from heap_profile_allocation"),
        ("帧时间线", "select count(*) from actual_frame_timeline_slice"),
        ("blocked_function", "select count(*) from thread_state where blocked_function is not null"),
        ("logcat 进 trace", "select count(*) from android_logs"),
    ]
    for label, sql in checks:
        try:
            v = q(t, sql)[0][0]
        except Exception:
            v = "err"
        mark = "✅" if v not in ("0", "err") else "❌"
        print(f"    {mark} {label:<20} {v}")

    # 定位崩溃进程
    pid = args.pid
    if not pid:
        # ⚠️ 不能只按 pid 大小挑（实测踩过：崩溃后系统会拉起**新进程**，
        #    新 pid 更大，于是选中的是"重启后的进程"而不是崩溃的那个）。
        #    正确做法：挑**有 Z/X 终态**的那个进程 —— 那才是死掉的。
        procs = q(t, f"""
            select p.pid, p.name, count(*) as dead
            from thread_state ts
            join thread t on ts.utid=t.utid
            join process p using(upid)
            where ts.state in ('Z','X') and p.name like '%example%'
            group by p.pid order by dead desc limit 1
        """)
        if not procs:
            procs = q(t, "select pid, name, 0 from process where name like '%example%' order by pid desc limit 1")
        if procs:
            pid = int(procs[0][0])
    if not pid:
        print("\n⚠️ 找不到目标进程，用 --pid 指定", file=sys.stderr)
        return 1
    print(f"\n【目标进程】pid={pid}")

    # 线程终态（崩溃指纹）
    print("\n【① 线程终态 —— 崩溃指纹看这里】")
    rows = q(t, f"""
        select t.tid, t.name,
               round((max(ts.ts)-(select min(ts) from slice))/1e9, 2) as last_s,
               ts.state
        from thread_state ts
        join thread t on ts.utid=t.utid
        join process p using(upid)
        where p.pid={pid}
        group by t.tid
        order by last_s desc
        limit 14
    """)
    table(rows, ["tid", "name", "last_s", "state"])

    # ⚠️ 判读不能锚定"trace 结束"—— Perfetto 在进程死后仍继续录（实测：崩溃
    #    发生在 17.5s，trace 却录到 22.9s）。正确锚点是**该进程自己的死亡聚集时刻**：
    #    看多数线程"最后活动时刻"是否**高度聚集**在一个点。
    deaths = sorted(float(r[2]) for r in rows)
    spread = (deaths[-1] - deaths[0]) if len(deaths) > 1 else 0.0
    n_dead = sum(1 for r in rows if r[3] in ("X", "Z"))
    print(f"\n    ── 判读（死亡线程 {n_dead} 个，最后活动时刻跨度 {spread:.2f}s）──")
    if n_dead >= 3 and spread < 0.6:
        print(f"    ⭐⭐ 多线程在 {deaths[-1]:.2f}s **同时**消亡 ⇒ **Native 崩溃**指纹")
        print("        （全线程集体消亡，跨度 < 0.6s —— 对比 Java 崩溃的逐个 detach）")
    elif any(r[3] == "Z" for r in rows):
        zs = [r for r in rows if r[3] == "Z"]
        print(f"    ⭐ 出现 'Z'（tid={zs[0][0]} @ {zs[0][2]}s）⇒ **主线程终止**，Java/Native 崩溃均可能")
        print("        Java 崩溃：只有主线程 Z、其余还在（ART 逐个 detach）")
        print("        Native 崩溃：所有线程几乎同一刻 X/Z")
    elif n_dead == 0:
        print("    ⚠️ 无线程终止 ⇒ 进程**没死**（OOM 被 catch / 未真正崩溃）")
    else:
        print(f"    ⚠️ 终止线程 {n_dead} 个、跨度 {spread:.2f}s —— 结合日志与堆曲线判断")

    # 主线程最后在干什么
    print("\n【② 主线程终态前的最后动作】")
    rows = q(t, f"""
        select round((s.ts-(select min(ts) from slice))/1e9, 3) as at_s,
               round(s.dur/1e6, 2) as ms, substr(s.name,1,54) as name
        from slice s
        join thread_track tt on s.track_id=tt.id
        join thread t on tt.utid=t.utid
        join process p using(upid)
        where p.pid={pid} and t.is_main_thread=1
        order by s.ts desc limit 10
    """)
    table(rows, ["at_s", "ms", "name"])

    # 状态聚合
    print("\n【③ 主线程状态聚合】")
    rows = q(t, f"""
        select ts.state, round(sum(ts.dur)/1e6,1) as total_ms
        from thread_state ts join thread t on ts.utid=t.utid join process p using(upid)
        where p.pid={pid} and t.is_main_thread=1
        group by ts.state order by total_ms desc
    """)
    table(rows, ["state", "total_ms"])

    # OOM：堆曲线
    heap_rows = q(t, f"""
        select round((c.ts-(select min(ts) from slice))/1e9,1) as at_s,
               round(max(c.value)/1024,1) as heap_mb
        from counter c
        join counter_track ct on c.track_id=ct.id
        join process_counter_track pct on ct.id=pct.id
        join process p using(upid)
        where p.pid={pid} and ct.name='Heap size (KB)'
        group by round((c.ts-(select min(ts) from slice))/1e9,1)
        order by at_s
    """)
    if heap_rows:
        print("\n【④ 堆曲线（OOM 关键证据）】")
        # 稀疏打印：最多 24 行
        step = max(1, len(heap_rows) // 24)
        table(heap_rows[::step], ["at_s", "heap_mb"])
        peak = max(heap_rows, key=lambda r: float(r[1]))
        print(f"    峰值 {peak[1]} MB @ {peak[0]}s")

    # GC
    gc_rows = q(t, f"""
        select round((s.ts-(select min(ts) from slice))/1e9,2) as at_s,
               round(s.dur/1e6,1) as ms, substr(s.name,1,48) as name
        from slice s
        join thread_track tt on s.track_id=tt.id
        join thread t on tt.utid=t.utid
        join process p using(upid)
        where p.pid={pid} and (s.name like '%GC%' or s.name like '%GarbageCollect%')
        order by s.ts limit 20
    """)
    if gc_rows:
        print("\n【⑤ GC 时间线】")
        table(gc_rows, ["at_s", "ms", "name"])
        if any("Wait For Completion" in r[2] for r in gc_rows):
            print("    ⭐⭐ 出现 `GC: Wait For Completion Alloc` ⇒ **OOM 教科书指纹**")
            print("        （分配方被 GC 逼停，分配速度已追上 GC 速度）")

    # heapprofd 归因
    # ⚠️ stack_profile_frame **没有** mapping_name 列（实测踩过），
    #    映射名要 join stack_profile_mapping。
    alloc_rows = q(t, f"""
        select substr(f.name,1,52) as frame,
               coalesce(m.name,'?') as mapping,
               sum(a.size) as bytes,
               sum(a.count) as allocs
        from heap_profile_allocation a
        join stack_profile_callsite c on a.callsite_id=c.id
        join stack_profile_frame f on c.frame_id=f.id
        left join stack_profile_mapping m on f.mapping=m.id
        where a.upid=(select upid from process where pid={pid})
        group by f.name, m.name
        order by bytes desc limit 12
    """)
    if alloc_rows:
        print("\n【⑥ heapprofd native 分配归因（Top 10 调用点）】")
        table(alloc_rows, ["frame", "mapping", "bytes", "allocs"])

    # 崩溃时刻提示
    print("\n【⑦ 交叉验证「怎么死的」】")
    logcat = t + ".logcat.txt"
    if os.path.isfile(logcat):
        hits = []
        with open(logcat, "r", errors="replace") as f:
            for line in f:
                if re.search(r"FATAL EXCEPTION|Fatal signal|signal 11|signal 6|OutOfMemoryError|tombstone", line):
                    hits.append(line.rstrip()[:150])
                if len(hits) >= 6:
                    break
        if hits:
            print(f"    来自 {os.path.basename(logcat)}：")
            for h in hits:
                print("      " + h)
        else:
            print(f"    (logcat 里未见异常关键字，检查 {os.path.basename(logcat)})")
    else:
        print(f"    未找到 {os.path.basename(logcat)}（-Q 请配合 record-crash-heapprofd.sh 使用）")

    print("\n" + "═" * 78)
    return 0


if __name__ == "__main__":
    sys.exit(main())
