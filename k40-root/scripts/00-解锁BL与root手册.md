# Redmi K40 (alioth) Root 完整作战手册

> 结论先行：**你这台机器现在不能 root，卡在 bootloader 锁定。**
> 且额外发现：**当前系统版本 `V13.0.7.1.SKHCNXM` 在公开固件库不存在**，
> 所以必须先刷一个官方零售稳定版，才能拿到可打补丁的 boot 镜像。
> 好在解锁本来就会清空数据，两件事正好合并做。

---

## 一、设备实测档案

| 项目 | 值 |
|---|---|
| 型号 | M2012K11AC |
| 代号 | **alioth** |
| SoC | 骁龙 870 (kona / qcom) |
| 系统 | MIUI **V13.0.7.1.SKHCNXM**，Android 12 (SDK 31) |
| 构建类型 | user / **release-keys**（零售正式版，非内测） |
| 构建日期 | 2022-09-02 |
| 分区结构 | A/B + dynamic partitions (super) |
| 当前槽 | **B** (`ro.boot.slot_suffix=_b`) |
| Bootloader | **locked** (`ro.secureboot.lockstate=locked`) |
| AVB | verifiedbootstate=**green**（严格校验）, veritymode=enforcing |
| vbmeta | avb_version 1.0, sha256, invalidate_on_error=yes |
| 当前 root | 无（无 su / 无 /data/adb/magisk） |
| adb | 已开启 |
| 已用存储 | 51 GB / 225 GB |

### 为什么锁定时不能 root

`boot` 分区受 AVB 签名保护。锁定状态下 `fastboot flash boot` 会被拒绝；
强刷未签名镜像 → verified boot 失败 → 无法开机。
所以顺序固定：**解锁 → 取 boot 镜像 → Magisk 打补丁 → fastboot 刷入**。

---

## 二、【重要】版本号问题

`adb shell getprop ro.build.version.incremental` 实报 `V13.0.7.1.SKHCNXM`，
指纹也自洽（`Redmi/alioth/alioth:12/.../V13.0.7.1.SKHCNXM:user/release-keys`），
说明这版本**真实存在过**。

但它**不在任何公开固件库**：
- alioth 国行 SKHCNXM 分支公开版本序列为
  `V13.0.2.0 → 13.0.3.0 → 13.0.5.0 → 13.0.6.0 → 13.0.7.0 → 13.0.9.0 → 13.0.10.0`
  **没有 7.1**
- `xmfirmwareupdater.com` 的 `V13.0.7.1.SKHCNXM` 页面 → 404
- 全网搜索 `"13.0.7.1.SKHCNXM"` → 零结果

推测：这是个**小范围灰度/召回修正版**，小米没有公开发布。

### 影响与对策

Magisk 补丁要求 boot 镜像与系统**内核 + ramdisk 精确对应**。
拿 `13.0.7.0` 的 boot.img 刷到 `13.0.7.1` 上，典型后果是卡 logo / bootloop。

→ **对策：解锁后直接刷一个官方零售稳定版，把系统版本变成"有公开 boot.img 的版本"。**
反正是要清数据的，一步到位。

### 可选目标版本

| 版本 | Android | 说明 |
|---|---|---|
| **OS1.0.6.0.TKHCNXM** | 13 (HyperOS) | **推荐**，最新稳定，2024-06，安全性最好 |
| V13.0.7.0.SKHCNXM | 12 | 离你当前最近，但已 2022 年，太老 |

> 关于 `init_boot`：Android 13 起部分机型引入 `init_boot` 分区，Magisk 需补丁它而非 `boot`。
> alioth 是**动态分区 + A/B 两段式**，**大概率没有独立 init_boot**。
> 不用猜 —— `03-extract-boot.sh` 会自动检测包内是否存在 `init_boot.img` 并决定补丁目标。

---

## 三、执行流程

### 阶段 0 · 解锁 Bootloader（先做，等待期最长）

**小米解锁限制按「账号 + 设备绑定」走，与机型新旧无关，无法绕过：**
- 账号需注册 ≥ 30 天
- 绑定后强制等待 **168 小时（7 天）**，评分低/超额可能 15~30 天
- 每账号 30 天内一般只能解 1 台；每年约 2~3 台
- 解锁会**清空全部数据**、影响 Google Pay / 银行类 App

#### 0.1 手机上操作（★ 等待期从这步开始计时）

1. 插入可用 SIM 卡，登录小米账号，开启「查找设备」
2. 设置 → 我的设备 → 全部参数 → 连点「MIUI 版本」7 次
3. 开发者选项 → 打开「OEM 解锁」
4. 开发者选项 → 「设备解锁状态」 → 点「绑定账号和设备」
5. **等待期内不要**退出账号 / 关查找设备 / 恢复出厂 / 换卡

#### 0.2 Mac 上执行解锁（官方工具是 Windows 独占）

已为你装好跨平台工具 **MiUnlockTool**（Apache-2.0，1.5k★，2026-09 仍在维护）：

```bash
cd /Volumes/ext/workspace/projects/android/interview/android-interview/k40-root
./.venv-miunlock/bin/miunlock
```

按提示选择登录方式（**必须用手机上绑定的同一个小米账号**），完成验证。
等 168 小时到期后，重新运行该命令即可执行解锁。

> 备选方案：虚拟机跑 Windows + 官方 MiFlashUnlock；或借一台 Windows PC。
> 官方下载：https://en.miui.com/unlock/download_en.html

想要脚本化进度确认，任何时候可运行：
```bash
bash scripts/01-check-device.sh
```

---

### 阶段 1 · 刷入官方零售稳定版

> 前提：BL 已解锁。

**⚠️ 重要实测：** 小米 CDN 对**单连接限速极狠**（实测 13 KB/s，全程要 114 小时）。
必须用**并行分块下载**（32 并发实测约 1.6 MB/s，全程约 1 小时）。

```bash
# 推荐：并行分块下载（支持断点续传，中断后重跑即可续传）
bash scripts/05-parallel-download.sh
# 可自定义: bash scripts/05-parallel-download.sh <URL> <输出文件> <并发数>

# 备选：单线程（慢，不推荐）
bash scripts/02-download-firmware.sh /Volumes/data/k40-rom
```

- 文件大小：**5,854,141,992 字节**（5.45 GiB）
- 期望 MD5：`9c952f8190ae6d841f10a2c1f74958d2`
- **不要**放在 `/Volumes/ext`（仅剩 11 GB），用 `/Volumes/data`（1.7 TB 空闲）

刷入方式二选一：
- **推荐**：解包后用包内官方脚本 `flash_all.sh`（在 Mac 上执行）
- 或手动 `fastboot flash` 各分区（参考 LineageOS alioth 固件更新页的分区列表）

> ⚠️ 刷完整包会再次清空数据。

---

### 阶段 2 · 提取 boot 镜像

```bash
bash scripts/03-extract-boot.sh /Volumes/data/k40-rom
```

输出：
- `boot/stock-boot.img`（**原厂备份，务必留存，救砖用**）
- `boot/to-patch-boot.img`（待补丁）
- 并自动判定该补 `boot` 还是 `init_boot`

推送待补丁镜像到手机：
```bash
adb push /Volumes/data/k40-rom/boot/to-patch-boot.img /sdcard/Download/
```

---

### 阶段 3 · Magisk 打补丁

1. 安装 `magisk/Magisk-v30.7.apk`（已下好，稳定版；v31.0 还是 prerelease 先别用）
   ```bash
   adb install magisk/Magisk-v30.7.apk
   ```
2. 打开 Magisk App → `安装` → `选择并修补一个文件`
3. 选 `/sdcard/Download/to-patch-boot.img`
4. 产物在 `/sdcard/Download/magisk_patched-xxxxx.img`

取回电脑：
```bash
adb pull /sdcard/Download/magisk_patched-xxxxx.img
```

---

### 阶段 4 · 刷入并验证

```bash
# 手机关机 → 按住【音量下 + 电源】进 FASTBOOT → 插 USB
bash scripts/04-flash-magisk.sh magisk_patched-xxxxx.img boot
```

脚本会：确认设备在线 → **校验 BL 已解锁** → 二次确认 → 同时写 `boot_a` / `boot_b`
→ 重启 → 等待开机 → 自动跑 `su -c id` 验证。

成功标志：输出含 `uid=0(root)`。

---

## 四、救砖

刷错 boot 导致卡 logo / bootloop 时：
```bash
fastboot flash boot_a /Volumes/data/k40-rom/boot/stock-boot.img
fastboot flash boot_b /Volumes/data/k40-rom/boot/stock-boot.img
fastboot reboot
```
所以 **`stock-boot.img` 必须保留到确认 root 成功之后**。

无法进 fastboot 时可用小米官方 `fastboot` 完整线刷脚本恢复（`flash_all.sh`）。

---

## 五、脚本清单

| 脚本 | 作用 |
|---|---|
| `scripts/01-check-device.sh` | 设备体检：版本/BL 状态/root 判定 |
| `scripts/02-download-firmware.sh` | 下载固件 + MD5 校验（单线程，慢） |
| **`scripts/05-parallel-download.sh`** | **并行分块下载（推荐）**，断点续传 |
| `scripts/03-extract-boot.sh` | 解包 + 提取 boot + 判定 boot/init_boot + 备份原厂 |
| `scripts/04-flash-magisk.sh` | 刷入补丁镜像 + 重启 + root 验证 |

工具（均已就绪）：
- `magisk/Magisk-v30.7.apk` — Magisk 稳定版
  SHA256 `e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48fcb6b5bee0afae9ebd5`（与 GitHub 官方 digest 一致 ✅）
- `.venv-miunlock/bin/miunlock` — Mac 上的小米解锁工具（v1.6.3）

---

## 七、本机实测补充结论

1. **无 `init_boot` 分区**（`/dev/block/by-name/` 实测）→ 补丁目标是 **`boot.img`**。
2. **无 `boot`（无后缀）节点**，只有 `boot_a`(sde12) / `boot_b`(sde37)
   → 刷写必须用 `boot_a` / `boot_b`，不能写裸 `boot`。
3. **无法直接从手机 dump boot**：shell 读 `/dev/block/*` 被 SELinux 拒绝
   （`dd: Permission denied`）→ 只能从固件包提取。
4. **本机是 A/B 机型**，当前运行在 **B 槽**（`ro.boot.slot_suffix=_b`）。
5. 小米解锁官方工具仅 Windows；Mac 用 `miunlock`（`pip install miunlock`）。

---

## 六、待确认事项

- [ ] 手机上完成账号绑定，确认等待期长度（运行 miunlock 会告知）
- [ ] 决定目标版本：OS1.0.6.0.TKHCNXM（Android 13）还是 V13.0.7.0（Android 12）
- [ ] 确认 `/Volumes/data` 有 ≥ 14 GB 空间（已确认 1.7 TB，充足）
