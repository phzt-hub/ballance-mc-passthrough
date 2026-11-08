# ballance-mc-passthrough

把 **Minecraft 的画面按 alpha 合成进 Ballance**，并让两个**未经修改**的游戏互相驱动：
Ballance 的镜头和球位送给 MC 当相机，Ballance 的鼠标键盘送给 MC 当输入。

目标玩法：在 Ballance 的球道上**摆方块、放 TNT、拿苦力怕炸那颗球** —— 球能撞上你搭的墙、
能站在你搭的桥上。

技术蓝本是 [rehan-remade/universal-modder](https://github.com/rehan-remade/universal-modder) 里的
`minecraft-gta5-passthrough`（GTA5 ↔ Minecraft）。蓝本的**内容层**（MC 侧的方块/怪/TNT/交互）
整个可以搬过来，要换掉的是"宿主适配层"：GTA 的 native 调用 → Ballance 的 Virtools CK2。

> 本文 2026-11-07 重写。旧版有两个问题：里程碑表停在"进行中"，而 M1 / M1c / M2b 早已验收；
> 而且全文没有一段**怎么把两个游戏跑起来** —— 11-07 那次「没有任何 MC HUD、视角跑到原点」
> 就是缺这一段造成的（人只开了 Ballance 那一半）。所以第 0 节就是它。

## 0. 开跑（三分钟）

**两个进程，顺序不能反。** 只有 MC 在跑，Ballance 才有东西可贴；MC 不在，画面里一格 MC 都没有。

### 0.1 一键

```
J:\bmc-project\run-verify.bat
```

它做的就是我们下面那两步，中间等 15 秒。

### 0.2 手动

```bat
J:\bmc-project\run-mc.bat          :: 1) MC 26.3（自带 JDK），等它自己进虚空世界，20–40 秒
J:\bmc-project\run-ballance.bat    :: 2) 带 BALLANCE_MC_PASSTHROUGH=1 起 Ballance，点 Start → 第 1 关
```

### 0.3 三条容易踩的

1. **直接双击 `runtime\Bin\Player.exe` = 原版 Ballance。** 双击带不上环境变量，
   `BALLANCE_MC_PASSTHROUGH` 没设，穿透就整个关着。这是故意的：邮箱坏了也不能让游戏起不来。
2. **顺序反了会一直等。** 先开 Ballance 再开 MC，日志是
   `waiting for ...ballance_mc_frame.dat (producer not running yet, error 2)`，等一整轮也不会自己好。
3. **MC 窗口可以最小化，但不能关。** 贴进 Ballance 的是 MC 每帧渲染出来的图，关掉就断了源头。

### 0.4 怎么确认真的通了

| 看哪 | 应该看到 |
|---|---|
| `runtime\Bin\mc-passthrough.log`（每 2 秒一行） | `mapped … (4923136 bytes)` → `baseline frame N (published before this run, not drawn)` → `frames=… draws=… (15 fps consumed)` |
| `runtime\mc\logs\latest.log` | `published 75 frames in 5s (~15 fps), skipped 75, failures 0, transparent 87.6%` |
| Ballance 窗口 | 准星在球上方（AimLift 抬起来的那一点）、九格快捷栏贴底、右下角一只手臂 —— **这三样都是 MC 自己画的**，不是 Ballance 补的 |

`waiting for … error 2` 反复出现 = 源头没起来，不是穿透坏了。

## 1. 现在能做什么

| 能力 | 状态 | 证据 |
|---|---|---|
| MC 画面贴进 Ballance，透明的天空透出球道 | ✅ M2b | [docs/mc-void-world.md](docs/mc-void-world.md) |
| 准星 / 快捷栏 / 心 / 手臂跟着那一帧过来 | ✅ | MC 自己画，11-07 从邮箱里解出来的就是它 |
| 相机：MC 的世界跟着 Ballance 的视点走 | ✅ M1 | [docs/m1-camera.md](docs/m1-camera.md) |
| 第三人称只画球、第一人称只画手臂 | ✅ | [docs/steve-in-ballance.md](docs/steve-in-ballance.md) |
| 反向输入：E 开创造物品栏、滚轮换格、点物品 | ✅ M1b | [docs/m1b-input.md](docs/m1b-input.md) |
| 摆方块 / 挖方块，射线打空时在准星前 4.5 格生成 | ✅ M1c | [docs/m1c-place.md](docs/m1c-place.md) |
| 准星跟着球漂，钉在「这一下会放方块的地方」 | ✅ 11-07 | [docs/m1b-input.md](docs/m1b-input.md) 第五节 |
| 每个关卡一个新的世界；旧世界按时间戳留档，不再删掉 | ✅ 11-07 / 11-08 | [docs/level-worlds.md](docs/level-worlds.md) |
| F1 设置面板，9 行开关就地生效 | ✅ | [docs/settings-panel.md](docs/settings-panel.md) |
| 自由视角：按 F 绕球转镜头，球的行进方向一点不变 | ✅ 11-08 | [docs/free-view.md](docs/free-view.md) |
| 关卡地形导入：球道/墙/柱子变成**看不见的** barrier，红石、铁轨、火把有地方放 | ✅ 11-08 | [docs/voxel-import.md](docs/voxel-import.md) |
| 13 关预烘成存档模板，进关卡拷一份；每次进关新实例，旧的按时间戳留档 | ✅ 11-08 | [docs/voxel-import.md](docs/voxel-import.md) |

尺度：**1 方块 = 5.48 Ballance 单位**（默认值，F1 面板第 8 行可改）。4.0 是标定表里「一个球径」的数，
但和变球器 `P_Trafo_*` 的 5.48 对不上，取后者，理由见 [docs/scale-calibration.md](docs/scale-calibration.md)；
13 关 472,534 个三角全部可离线导出。

## 2. 2026-11-07 真机实测

在 Windows 试玩机（192.168.3.128，Win11 + 真显卡）上跑 `run-verify.bat`，12:31:14 开始：

| 时刻 | 事件 |
|---|---|
| 12:31:19 | 起 MC |
| 12:31:34 | 邮箱 `.dat` 出现（**15 秒**，比估计的 20–40 秒快） |
| 12:31:59 | 起 Ballance |
| 12:33:04 | 抓完最后一帧诊断 |

| 指标 | 值 |
|---|---|
| MC 发布 | **15 fps**，每 5 秒 75 帧，skipped 75，failures 0 |
| 透明占比 | **87.6%**（alpha=0）—— 天空确实被去掉了 |
| Ballance 消费 | 14–24 fps，`frames=1240 draws=3832 misses=1 rejected=0` |
| 陈旧帧防护 | `baseline frame 628 (published before this run, not drawn)` —— 首次映射时把"上一轮留下的帧"当基线跳过，不把旧画面糊上去 |

![11-07 从邮箱里解出来的 MC 帧：准星、九格快捷栏（第一格选中）、右下角手臂、右上角教学提示](docs/frame-shots/mc-frame-2026-11-07.png)

上面这张**不是截图**，是直接从帧邮箱里把 MC 发布的那一槽解出来、按 alpha 合成到黑底上的。
这么做是因为当天的桌面截图取证失败了，见 2.2。

### 2.1 踩到并修掉的：陈旧帧

邮箱在 Windows 上就是磁盘上的一个 `.dat`，**进程退出它不会消失**。所以新一轮启动时
消费者第一次映射就能读到上一轮留下的帧，然后一直把它糊在画面上 —— 11-07 的原始报告
「视角跑到坐标原点」有一半就是这个：屏幕上是一张旧的 MC 画面。

修法是首次映射时把当前的 `FrameId` 当作基线（`baseline frame N`），
只画这之后发布的帧。改动在 `tools/ballanced-patches/RenderEngine.patch`，
已经编进 11-07 09:44 的 `Player.exe`（日志里那行 baseline 就是它）。

### 2.2 踩到但没修的：锁屏就截不到桌面

自动化取证用的是 `Graphics.CopyFromScreen`。**工作站一锁屏，session 1 的进程就再也拿不到画面**：
要么返回一整屏纯黑（6 张截图逐字节相同），要么直接抛 `Win32Exception: 句柄无效`。

这不是穿透的毛病，是取证方式的边界，所以 2 节的结论全部走**邮箱解码**这条独立路径：

```python
# header 4096 字节，3 个槽，布局见 tools/passthrough/mpframe.h
magic, ver, w, h, stride = struct.unpack_from("<5I", data, 0)
frameid, slot = struct.unpack_from("<2I", data, 32)
offset = struct.unpack_from("<3Q", data, 56)[slot]
```

要「人眼看合成结果」只有两条路：**人在机器前解锁**，或者给 Ballance 侧加一个帧回读落盘
（还没有）。

## 3. 还没做到

| 阶段 | 内容 | 缺什么 |
|---|---|---|
| M2c | 深度合成（重投影） | 柱子还挡不住 MC 的墙 —— 现在是整帧 alpha 混合，没有逐像素深度 |
| M3a | 输入转发 | 摆方块只在"屏幕关着"时生效；开着物品栏时球仍然冻结 |
| M3b | 地面 raycast | Steve 还不会站在球道上，脚下穿模 |
| M3c | 方块 → 碰撞体 | **球还撞不上你搭的墙** —— 这条是目标玩法的主干 |

## 4. 两个通道

| | 帧通道（M0b） | 相机通道（M1） |
|---|---|---|
| 方向 | MC → Ballance | Ballance → MC |
| 内容 | BGRA8 像素，3 槽轮转，854×480 | 约 100 字节标量 |
| 写者 | Fabric mod | `Source/Player/src/MinecraftCameraSync.cpp` |
| 读者 | `CKSdlGpuPassthroughTick` | `McCameraChannel` → `CameraMixin` |
| Linux | `/dev/shm/ballance_mc_frame` | `/dev/shm/ballance_mc_camera` |
| Windows | `%LOCALAPPDATA%\BallanceMcPassthrough\ballance_mc_frame.dat` | 同目录 `ballance_mc_camera.dat` |
| 顺序 | `FrameId` 发布，acquire/release | **seqlock**（Seq 前后各读一次） |

邮箱布局是 `tools/passthrough/mpframe.h`，两端各有一份镜像实现，
`tools/passthrough/inspect-mailbox.py` / `inspect-camera.py` 是独立第三方读者 ——
**它们能读通，才说明布局没走样**。

| 环境变量 | 谁读 | 作用 |
|---|---|---|
| `BALLANCE_MC_PASSTHROUGH=1` | Ballance | 总开关，默认关 |
| `BALLANCE_MC_SHM` | 两端 | 邮箱名，默认 `ballance_mc_frame` |
| `BALLANCE_MC_MAILBOX` | Ballance | 直接给绝对路径 |
| `BALLANCE_MC_FPS` | MC | 发布限速，默认 30 |
| `BALLANCE_MC_EXPORT=0` | MC | 完全关闭导出 |

## 5. 里程碑

| 阶段 | 内容 | 状态 |
|---|---|---|
| PoC-0 | 可行性：深度 / raycast / 动态碰撞体 / Linux 构建 | ✅ [docs/feasibility.md](docs/feasibility.md) |
| PoC-1 | 在深度上挂探针，落盘一帧 | ✅ [docs/poc1-depth.md](docs/poc1-depth.md) |
| PoC-2 | 动态 obstacle 加/删，球能撞上 | ✅ [docs/poc2-obstacles.md](docs/poc2-obstacles.md) |
| M0 | 共享内存链路 + 全屏贴图 | ✅ |
| M1 | 相机 + 球位 = Steve 位 | ✅ 11-05 |
| M1b | 反向输入：E / 鼠标 / 滚轮驱动 MC 物品栏 | ✅ [docs/m1b-input.md](docs/m1b-input.md) |
| M1c | 在世界里放 / 挖方块 | ✅ 11-07 [docs/m1c-place.md](docs/m1c-place.md) |
| M2a | 虚空世界 + 去天空 | ✅ [docs/mc-void-world.md](docs/mc-void-world.md) |
| M2b | Ballance 侧 alpha 混合 | ✅ 11-06 |
| M2c | 深度合成（重投影） | ⬜ |
| M3a/b/c | 输入转发 / 地面 raycast / 方块变碰撞体 | ⬜ |

## 6. 目录

```
docs/feasibility.md        PoC-0 的证据与结论
docs/bmlplus-hooks.md      BML+ 钩子能力核查（另一条路：BallancePlayer + BML+）
docs/bmlplus-hud-mod.md    ".bmodp 版 HUD" 的可行性（当前未采用）
docs/m1-camera.md          M1 相机通道，含三个坑
docs/m1b-input.md          M1b 反向输入
docs/m1c-place.md          M1c 放/挖方块，含 FOV 与画幅口径
docs/mc-void-world.md      M2a/M2b 虚空世界 + alpha 合成，五个坑
docs/steve-in-ballance.md  Steve 的身体怎么画
docs/free-camera.md        自由相机（调试用）
docs/settings-panel.md     F1 面板的九行与优先级
docs/level-parse.md        离线读关卡几何
docs/scale-calibration.md  尺度标定表
docs/windows-build.md      Windows 真机包怎么编（工具链 / 坑 / 铺目录）
docs/*-shots/              验收截图
mc-mod/                    MC 侧 Fabric mod（CC0）
tools/passthrough/         帧/相机通道的协议、两半实现、验收脚本
tools/ballanced-patches/   对上游 Ballanced 的补丁（Player.patch / RenderEngine.patch）
tools/nmo_geom.cpp         读 .nmo 导出世界空间 OBJ + JSON（libcmo21）
tools/calibrate.py         由导出产物生成尺度表
external/fetch-upstream.sh 拉上游 Ballanced / CKRenderEngine（不随本仓库分发）
```

## 7. 上游与许可

```sh
./external/fetch-upstream.sh          # 默认拉到 ../Ballanced 与 ../CKRenderEngine
```

- [doyaGu/Ballanced](https://github.com/doyaGu/Ballanced)（branch `sdl`，MIT）—— 源码级复刻的 Ballance 运行时，SDL3
- [doyaGu/CKRenderEngine](https://github.com/doyaGu/CKRenderEngine)（branch `sdl`，Apache-2.0）—— Virtools CK2 渲染引擎，带 SDL3 GPU 光栅器

上游是 Ballance 的反编译/复刻代码，**需要正版 Ballance 资产**，本仓库不含任何游戏资源。
本仓库只放自己的代码与文档，上游一律按脚本拉取（不 vendored、不改别人的 LICENSE）。
