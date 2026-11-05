# Ballance <-> Minecraft 画面穿透 · M0 / M0b

把 Minecraft 客户端渲染的画面，实时贴进 Ballance（Ballanced 移植版）的窗口里。
这一层只解决**画面传输**：Ballance 的场景照常渲染，MC 的帧作为一块全屏贴片绘制在场景之上、
游戏自己的 2D 界面（菜单按钮、HUD）之下。相机同步（M1）和深度重投影（M2）还没做。

一句话链路：

```
Minecraft 26.3 (Fabric mod)  --glReadPixels-->  邮箱  -->  Ballanced (SDL_GPU) 全屏四边形
```

邮箱就是同一块内存，两个平台各用各的拿法：

| | Linux | Windows |
|---|---|---|
| 位置 | `/dev/shm/ballance_mc_frame` | `%LOCALAPPDATA%\BallanceMcPassthrough\ballance_mc_frame.dat` |
| 生产者 | `FileChannel.map` 写 `/dev/shm/...` | 同一个 mod，`FileChannel.map` 写那个 `.dat` |
| 消费者 | `shm_open` + `mmap` | `CreateFileA` + `CreateFileMapping` + `MapViewOfFile` |
| 顺序语义 | `__atomic_load_n(acquire)` | `_InterlockedCompareExchange(p,0,0)` |

Windows 上没有 shm 命名空间，所以那边是"内存映射文件"；布局（`mpframe.h`）两边逐字节相同。

## 协议（`mpframe.h`，两端字节兼容）

```
[ MpFrameHeader 4096 B ][ slot0 ][ slot1 ][ slot2 ]     每槽 = Stride * Height 字节 BGRA8
```

* `Magic = 'MFP0'`，`Version = 1`；`FrameId` 用 `__atomic` 发布/读取（序号即"有新帧"的信号）。
* 三槽轮转：生产者写 `PublishedSlot` 之外的槽再更新序号；消费者落后三帧以上才会被撕。
* 头里还有 `Width/Height/Stride/Format/SlotOffset[3]/ProducerPid` 等字段，`inspect-mailbox.py`
  可以完全独立于两端读出全部内容。

## 两半

| 一半 | 位置 | 说明 |
|---|---|---|
| 生产者 | `~/mcmod`（Fabric 客户端 mod，独立仓库） | `Minecraft#renderFrame` 末尾复用 MC 自己的截图回读，把每帧转 BGRA8 写进邮箱 |
| 消费者 | 本目录 `sdlgpu-passthrough.{h,cpp}` | 在 `CKSdlGpuRasterizerContext::BeginOverlayPhase()` 末尾取最新帧、上传纹理、全屏绘制 |

消费者由 `apply-passthrough-hook.py` 幂等装进 Ballanced 源码树（`--revert` 撤销）。
装完重建：`cmake --build --preset linux-x64-runtime-release --target stage`
（**必须带 `--target stage`**，否则 `stage/Bin/Player` 还是旧的）。

开关：

| 变量 | 作用 |
|---|---|
| `BALLANCE_MC_PASSTHROUGH=1` | 打开消费者（默认关） |
| `BALLANCE_MC_SHM=名字` | 邮箱名，默认 `ballance_mc_frame`（POSIX 侧带不带 `/dev/shm/` 前缀都认） |
| `BALLANCE_MC_MAILBOX=绝对路径` | 整个路径，两端都认；一般是排查用 |
| `BALLANCE_MC_EXPORT=0` | （MC 侧）关闭导出 |
| `BALLANCE_MC_FPS=30` | （MC 侧）导出限速 |

诊断：Linux 侧打在 stderr（`[mc-passthrough] ...`，跟着 `Player.log` 一起）；Windows 没有控制台，
同一个前缀同时追加进 `Bin\mc-passthrough.log`。

## 关键设计：无新帧时复用上一次上传的纹理

生产者是**软件渲染的 Minecraft**，只有 14~26 fps；Ballance 这边 54 fps。
如果只在"有新帧"的那一帧绘制，画面会**闪**（1/4 的帧有 MC、其余帧是 Ballance 自己的场景）。
所以消费者缓存最后上传成功的纹理，**每个 tick 都重画一遍**，只在新帧到达时才重新上传。
统计行里的 `redraws` 就是这个复用次数。

## 验收

```sh
# 1) MC 客户端（另一个终端，Xvfb :99）
cd ~/mcmod && ./gradlew25 build && DISPLAY=:99 ./gradlew25 runClient

# 2) 独立读邮箱：字段 + 槽 PNG + 速率
./tools/passthrough/inspect-mailbox.py --watch 4 --png /tmp/f.png

# 3) 两档截图：on（贴 MC 画面）/ off（对照）
./tools/passthrough/test-m0b.sh on  26 on
./tools/passthrough/test-m0b.sh off 26 off

# 4) 判定
./tools/passthrough/verify-m0b.py /tmp/m0b/on --control /tmp/m0b/off
```

`test-m0b.sh` 会先给窗口摆位置（Xvfb 没有窗口管理器，窗口会互相遮挡：SDL 会把 Ballance
窗口居中到 480,260，所以要等它落位后再挪到 0,0 并**用 xwininfo 复核**），然后隔 0.7 s 连拍 6 张。

### 实测（2026-11-04，Xvfb :99，llvmpipe）

| 项 | 数值 |
|---|---|
| 贴片稳定度 | 6 张样本两两差 **0.03（>8 的像素 0.05%）→ 不闪** |
| 与生产的帧逐像素 | 左右各 64 px 边带 **mean 0.00 / max 0**（合成图案那档完全一致） |
| 整帧差 | mean 16.4 / 36.8% —— 差的是 Ballance 自己画在上层的 2D 菜单，符合设计 |
| on vs off 对照 | mean 70.1 / **96.3% 像素改变** |
| 通道/方向 | BGRA 正确、无上下翻转、alpha=255 |
| 消费速率 | 14~15 fps（跟着 MC 走），misses 0，rejected 0 |

图案那档（`shmfeed --static --name ballance_test`）证明的是**传输路径本身逐像素精确**：
左右边带与生产者写入的字节完全一致，中间差的是游戏 UI。

## Windows 真机（2026-11-05 起可用）

源码永远从 Linux 这边取，Windows 只当编译/试玩机：

```sh
./tools/windows-sync.sh --build --jar --deploy
```

它会打包 `~/src/Ballanced`（不含 `build/`、`.git`）推过去解包、用计划任务重编、把
`mc-mod` 的 jar 推进 `D:\bmc-play\mc\mods\`、再把 stage 铺成可玩目录。换一台 Windows 只需
`BMC_HOST=Administrator@<ip>`。细节和坑见 `docs/windows-build.md`。

试玩顺序：先开 Minecraft（`D:\bmc-play\mc\launch.bat`，进世界或停在菜单都行），再双击
`D:\bmc-play\run-ballance-mc.bat`（开穿透）或 `run-ballance-plain.bat`（关，对照）。

## 已知限制

* 源分辨率任意（实测 640x480、854x480），每帧整帧上传，1280x720 以上的成本还没量。
* 只做铺满全屏的拉伸（854x480 → 640x480 会横向压扁），还没有等比/裁切选项。
* M1（相机同步）、M2（深度重投影）未做。
* 传的是 MC 的**整帧**，含它自己的 HUD；Ballance 侧 `MinecraftHud` 手搓的准星/快捷栏/心与之重复，
  两个都开会叠出两套。已知未修（决策 C），收法见 `docs/mc-hud.md` 「归属」一节。
