#!/usr/bin/env python3
"""泳道并发标定数据可视化

数据来源：tools/data/lane-calibration.csv
  由 LaneCalibration.kt 在 Redmi K40 (M2012K11AC/alioth) / Android 12 / 8核 / UFS 上采集
  复现：
    adb shell cat /sdcard/Android/data/com.example.myapplication/files/LaneCalibration/calibration.csv \
      > tools/data/lane-calibration.csv
    uv run --with matplotlib python tools/plot_lane_calibration.py

判据：core 上界 = min(吞吐平台起点, P99 陡升点)
  吞吐平台起点：相邻档位吞吐增益 < 15%
  P99 陡升点  ：P99 超过 n=1 的 1.5 倍
"""
import csv
import os
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager

for cand in ["/System/Library/Fonts/Hiragino Sans GB.ttc",
             "/System/Library/Fonts/STHeiti Medium.ttc",
             "/System/Library/Fonts/Supplemental/Songti.ttc"]:
    try:
        font_manager.fontManager.addfont(cand)
    except Exception:
        pass
plt.rcParams["font.family"] = ["Hiragino Sans GB", "STHeiti", "Songti SC", "DejaVu Sans"]
plt.rcParams["axes.unicode_minus"] = False

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CSV_PATH = os.path.join(HERE, "data", "lane-calibration.csv")

# ── 展示元数据（单位/配色），数值全部来自 CSV ──
META = {
    "DISK_RANDOM_4K_SYNC": dict(
        title="disk 随机4K 落盘 (O_SYNC)", unit="IO/s", color="#c0392b", marker="o"),
    "DISK_RANDOM_4K": dict(
        title="disk 随机4K 缓存态", unit="IO/s", color="#e67e22", marker="s"),
    "DISK_SEQ_READ": dict(
        title="disk 顺序读 (带预读)", unit="1MB块/s", color="#8e44ad", marker="D"),
    "NET_RTT": dict(
        title="net RTT受限 (sleep 替身)", unit="请求/s", color="#27ae60", marker="^"),
    "CPU_PARSE": dict(
        title="cpu 计算 (模拟解析)", unit="批次/s", color="#2980b9", marker="v"),
}
ORDER = list(META.keys())


def load():
    """读 CSV，按 workload 聚合"""
    by_wl = {}
    with open(CSV_PATH, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            ops, wall = int(row["total_ops"]), int(row["wall_ms"])
            by_wl.setdefault(row["workload"], {})[int(row["n"])] = dict(
                # 用未舍入的 ops/wall 重算，避免 CSV 里 1 位小数的舍入误差
                tps=ops * 1000.0 / wall,
                p50=int(row["p50_us"]), p99=int(row["p99_us"]),
                wall=wall, ops=ops,
            )

    out = {}
    for key in ORDER:
        if key not in by_wl:
            raise SystemExit(f"CSV 缺少 workload: {key}")
        rows = by_wl[key]
        ns = sorted(rows)
        d = dict(META[key], key=key, ns=ns,
                 tps=[rows[n]["tps"] for n in ns],
                 p50=[rows[n]["p50"] for n in ns],
                 p99=[rows[n]["p99"] for n in ns])
        # P99 首次超过 n=1 的 1.5 倍
        d["p99_break"] = next(
            (n for n, v in zip(ns, d["p99"]) if v / d["p99"][0] > 1.5), None)
        out[key] = d
    return out


def suggest_core(d):
    """core 上界 = min(吞吐平台起点, P99 陡升点)"""
    ns, tps = d["ns"], d["tps"]
    knee = next((ns[i] for i in range(1, len(ns))
                 if tps[i - 1] > 0 and tps[i] / tps[i - 1] - 1.0 < 0.15), None)
    cands = [c for c in (knee, d["p99_break"]) if c is not None]
    return min(cands) if cands else None


data = load()
for d in data.values():
    d["bound"] = suggest_core(d)

N = sorted({n for d in data.values() for n in d["ns"]})
syn, net = data["DISK_RANDOM_4K_SYNC"], data["NET_RTT"]
syn_g, syn_p = syn["tps"][-1] / syn["tps"][0], syn["p99"][-1] / syn["p99"][0]
net_g = net["tps"][-1] / net["tps"][0]

fig = plt.figure(figsize=(15.5, 12))
fig.suptitle("泳道并发标定实测 · Redmi K40 (UFS / 8 核)\n"
             "闭循环扫描：固定 n 个 worker 抢任务，无队列、无拒绝、无丢样本",
             fontsize=15.5, fontweight="bold", y=0.985)
gs = fig.add_gridspec(3, 2, hspace=0.50, wspace=0.22,
                      top=0.895, bottom=0.075, left=0.07, right=0.97)

# ── ① 吞吐-并发曲线 ──
ax = fig.add_subplot(gs[0, :])
for d in data.values():
    ax.plot(N, [v / d["tps"][0] for v in d["tps"]], marker=d["marker"], ls="-",
            label=f'{d["title"]}   [{d["unit"]}]', color=d["color"], lw=2.2, ms=7)
ax.plot(N, N, "k--", alpha=0.4, lw=1.5, label="理想线性（等比例增益）")
ax.set_xscale("log", base=2); ax.set_yscale("log", base=2)
ax.set_xticks(N); ax.set_xticklabels([str(v) for v in N])
ax.set_yticks([1, 2, 4, 8, 16]); ax.set_yticklabels(["1x", "2x", "4x", "8x", "16x"])
ax.set_ylim(0.85, 30)
ax.set_xlabel("并发 n", fontsize=11); ax.set_ylabel("吞吐（相对 n=1）", fontsize=11)
ax.set_title("① 吞吐-并发曲线（归一化）", fontsize=12.5, fontweight="bold")
ax.grid(alpha=0.3, which="both")
ax.legend(fontsize=9, loc="lower right", framealpha=0.95)
ax.annotate("RTT 受限型全程贴合理想线 → 并发是净收益",
            xy=(net["ns"][-1], net_g), xytext=(1.45, 19), fontsize=9.5,
            color="#1e8449", arrowprops=dict(arrowstyle="->", color="#1e8449", lw=1.5))
ax.annotate(f'落盘档到 n={syn["ns"][-1]} 仅 {syn_g:.2f}x，已离开理想线',
            xy=(syn["ns"][-1], syn_g), xytext=(1.45, 7.2), fontsize=9.5,
            color="#a93226", arrowprops=dict(arrowstyle="->", color="#a93226", lw=1.5))

# ── ② P99 劣化倍数 ──
ax = fig.add_subplot(gs[1, 0])
for d in data.values():
    ax.plot(N, [v / d["p99"][0] for v in d["p99"]], marker=d["marker"],
            color=d["color"], lw=2.2, ms=6, label=d["title"])
ax.axhline(1.5, color="red", ls="--", alpha=0.65, lw=1.6)
ax.text(4.6, 1.57, "判据：劣化 1.5x → 该并发即 core 上界",
        color="red", fontsize=8.5, va="bottom")
ax.axhline(1.0, color="gray", ls=":", alpha=0.5)
ax.set_xscale("log", base=2); ax.set_xticks(N); ax.set_xticklabels([str(v) for v in N])
ax.set_ylim(0.6, 11)
ax.set_xlabel("并发 n", fontsize=11)
ax.set_ylabel("P99（相对 n=1 的倍数）", fontsize=11)
ax.set_title("② 尾延迟劣化 —— 核心判据", fontsize=12.5, fontweight="bold")
ax.grid(alpha=0.3)
ax.legend(fontsize=8.2, loc="upper left", framealpha=0.95)

# ── ③ 权衡散点 ──
ax = fig.add_subplot(gs[1, 1])
lim = 20
ax.plot([1, lim], [1, lim], "k--", alpha=0.45, lw=1.5)
ax.fill_between([1, lim], [1, lim], lim, color="#e74c3c", alpha=0.055)
ax.fill_between([1, lim], 0.5, [1, lim], color="#27ae60", alpha=0.055)
ax.text(7.5, 15.5, "亏：P99 涨得比吞吐快", color="#a93226", fontsize=10,
        ha="center", fontweight="bold")
ax.text(2.4, 0.72, "赚：吞吐涨得更快", color="#1e8449", fontsize=10,
        ha="center", fontweight="bold")
for d in data.values():
    xs = [v / d["tps"][0] for v in d["tps"]]
    ys = [v / d["p99"][0] for v in d["p99"]]
    ax.plot(xs, ys, "-", color=d["color"], lw=1.6, alpha=0.75)
    ax.scatter(xs, ys, s=[18, 32, 48, 70, 105], color=d["color"], zorder=5,
               edgecolor="white", linewidth=1.1)
    is_net = d["key"] == "NET_RTT"
    ax.annotate(d["title"].split(" (")[0], xy=(xs[-1], ys[-1]),
                xytext=(-10, 11) if is_net else (7, 7),
                textcoords="offset points", fontsize=8.6, color=d["color"],
                fontweight="bold", ha="right" if is_net else "left")
ax.set_xscale("log", base=2); ax.set_yscale("log", base=2)
ax.set_xlim(0.9, 22); ax.set_ylim(0.6, 22)
ax.set_xlabel("吞吐增益（相对 n=1）", fontsize=11)
ax.set_ylabel("P99 劣化（相对 n=1）", fontsize=11)
ax.set_title("③ 权衡面：点越大 = 并发越高", fontsize=12.5, fontweight="bold")
ax.grid(alpha=0.3, which="both")
ax.annotate(f'落盘档：吞吐只涨 {syn_g:.2f}x\nP99 却涨 {syn_p:.0f}x → 亏',
            xy=(syn_g, syn_p), xytext=(1.35, 12.5), fontsize=9, color="#a93226",
            arrowprops=dict(arrowstyle="->", color="#a93226", lw=1.4))
ax.annotate(f'net：吞吐 {net_g:.2f}x\nP99 不动 → 赚',
            xy=(net_g, net["p99"][-1] / net["p99"][0]), xytext=(3.6, 0.655),
            fontsize=9, color="#1e8449",
            arrowprops=dict(arrowstyle="->", color="#1e8449", lw=1.4,
                            connectionstyle="arc3,rad=-0.15"))

# ── ④ 结论汇总表 ──
ax = fig.add_subplot(gs[2, :]); ax.axis("off")
rows = []
for d in data.values():
    g, p = d["tps"][-1] / d["tps"][0], d["p99"][-1] / d["p99"][0]
    rows.append([
        d["title"], d["unit"],
        f'{d["tps"][0]:,.1f} → {d["tps"][-1]:,.1f}',
        f"{g:.2f}x", f"{p:.2f}x",
        f'n={d["p99_break"]}' if d["p99_break"] else "未劣化",
        "亏（尾延迟代价 > 吞吐收益）" if p > g else "赚",
        f'core ≤ {d["bound"]}' if d["bound"] else "未测到上界",
    ])

headers = ["负载", "单位", f"吞吐 n={N[0]} → n={N[-1]}", "吞吐增益", "P99 劣化",
           "P99 破 1.5x", "权衡", "建议 core"]
table = ax.table(cellText=rows, colLabels=headers, cellLoc="center", loc="center",
                 colWidths=[0.235, 0.075, 0.175, 0.09, 0.085, 0.095, 0.165, 0.10])
table.auto_set_font_size(False); table.set_fontsize(9.5); table.scale(1, 2.3)
for (r, c), cell in table.get_celld().items():
    cell.set_edgecolor("#cccccc")
    if r == 0:
        cell.set_facecolor("#34495e")
        cell.set_text_props(color="white", fontweight="bold")
        continue
    if c == 0:
        cell.set_facecolor(data[ORDER[r - 1]]["color"] + "22")
        cell.set_text_props(ha="left"); cell._loc = "left"
    else:
        cell.set_facecolor("#fafafa")
    if c == 7:
        cell.set_text_props(fontweight="bold", color="#c0392b")
    if c == 6:
        cell.set_text_props(color="#a93226" if "亏" in rows[r - 1][6] else "#1e8449")

ax.set_title("④ 结论汇总：core 上界 = min（吞吐平台起点, P99 陡升点）",
             fontsize=12.5, fontweight="bold", pad=16)
fig.text(0.5, 0.022,
         "⚠️ 边界：本机为 UFS，恰是「能吃高队列深度」的一类 —— 结论不能跨设备搬运，eMMC 低端机必须重测；"
         "net 档为 sleep 替身，不含带宽竞争与服务端限流。\n"
         "缓存态一档的 P99 仅 45~72µs，处于调度/JIT 噪声量级，其「n=4 破 1.5x」不宜过度解读。",
         ha="center", fontsize=9.5, color="#7f8c8d", style="italic")

out = os.path.join(ROOT, "images", "lane-calibration.png")
fig.savefig(out, dpi=150, bbox_inches="tight", facecolor="white")
print(f"saved: {out}")
for d in data.values():
    print(f'  {d["key"]:<20} 吞吐 {d["tps"][-1] / d["tps"][0]:5.2f}x  '
          f'P99 {d["p99"][-1] / d["p99"][0]:5.2f}x  → core ≤ {d["bound"]}')
