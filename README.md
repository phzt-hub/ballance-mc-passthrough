# ballance-mc-passthrough

把 **Minecraft 的画面按深度合成进 Ballance**，并让两个未经修改的游戏互相驱动。
目标玩法：在 Ballance 的球道上**摆方块、放 TNT、拿苦力怕炸那颗球**，球能撞上你搭的墙、能站在你搭的桥上。

技术蓝本是 [rehan-remade/universal-modder](https://github.com/rehan-remade/universal-modder) 里的
`minecraft-gta5-passthrough`（GTA5 ↔ Minecraft 的开源实现）。蓝本的**内容层（MC 侧的方块/怪/TNT/交互）可以整个搬过来**，
要做的是换掉"宿主适配层"：GTA 的 native 调用 → Ballance 的 Virtools CK2。

## 当前状态：PoC-0（可行性验证）✅ 通过 · 路线已改推 A′

三个"生死问题"都有明确答案，证据见 **[docs/feasibility.md](docs/feasibility.md)**。
**2026-11-01 追加核查**：BML+ 把渲染上下文 / 碰撞 / 物理 / 输入全部公开给 mod，深度就在公开结构体 `VxDirectXData.DDZBuffer` 里，
而 BML+ 只支持**同样开源的** BallancePlayer —— 所以"源码"和"mod 生态"不必二选一。见 **[docs/bmlplus-hooks.md](docs/bmlplus-hooks.md)**。

| 生死问题 | 结论 |
|---|---|
| 宿主深度拿不拿得到 | ✅ 能。SDL3 GPU 后端，深度是独立的 `DepthTexture`，且已有 readback 通道 |
| 地面高度怎么给 MC | ✅ 有 `CKCollisionManager::RayIntersection` |
| MC 方块能不能变成 Ballance 的碰撞体 | ⚠️ API 齐全（`AddObstacle(…, CKCOLLISION_BOX)`），**待运行验证** |
| 能不能在 Linux 上开发 | ✅ `linux-x64-runtime` 预设存在，本机可直接编 |

## 里程碑

| 阶段 | 内容 | 验收 |
|---|---|---|
| **PoC-0** ✅ | 可行性：深度 / raycast / 动态碰撞体 / Linux 构建 | 本文档 |
| PoC-1 | 构出 runtime，在深度上挂一个"读取并落盘一帧"的探针 | 拿到真实关卡的深度 PNG |
| PoC-2 | 动态 obstacle 加/删 + 球能撞上 | 球被自己加的盒子挡住 |
| M0 | 共享内存链路 + 全屏贴图 | Ballance 窗口里看到 MC 画面 |
| M1 | 相机 + 球位 = Steve 位 | 球滚，MC 世界跟着走不漂 |
| M2 | 深度合成（重投影） | 柱子能挡住 MC 的墙 |
| M3a | 输入转发 | 摆方块、点 TNT、点苦力怕 |
| M3b | 地面 raycast | Steve 站在球道上不穿模 |
| M3c | 方块 → 碰撞体 | **球能撞上你搭的墙** |

**离线侧已完成（2026-11-01）**：13 关 472,534 个三角全部可离线导出（`tools/nmo_geom`），
尺度已标定（1 MC 方块 = 4.0 Ballance 单位 = 1 个球直径），见 [docs/scale-calibration.md](docs/scale-calibration.md)。

## 目录

```
docs/feasibility.md        PoC-0 的证据与结论（Ballanced / SDL3 路线）
docs/bmlplus-hooks.md      BML+ 钩子能力核查（路线 A′：BallancePlayer + BML+）
docs/level-parse.md        离线读关卡几何：已跑通，含三个坑与矩阵约定
docs/scale-calibration.md  尺度标定表（球 / 物理件 / 13 关走廊宽 / 方块比例）
tools/nmo_geom.cpp         读 .nmo 导出世界空间 OBJ + JSON（libcmo21）
tools/build-nmo-geom.sh    建它（钉死编译参数）
tools/export-levels.sh     13 关 + PH + 资产全量导出
tools/calibrate.py         由导出产物生成 docs/scale-calibration.md
tools/nmo_dump.cpp         早期的容器层探针（看不到 3D 类，仅存档）
tools/passthrough/         画面穿透 M0b：协议、两半实现、验收与取材脚本（见该目录 README）
external/fetch-upstream.sh 拉上游 Ballanced / CKRenderEngine（不随本仓库分发）
```

## 上游

```sh
./external/fetch-upstream.sh          # 默认拉到 ../Ballanced 与 ../CKRenderEngine
```

- [doyaGu/Ballanced](https://github.com/doyaGu/Ballanced)（branch `sdl`，MIT）—— 源码级复刻的 Ballance 运行时，SDL3
- [doyaGu/CKRenderEngine](https://github.com/doyaGu/CKRenderEngine)（branch `sdl`，Apache-2.0）—— Virtools CK2 渲染引擎，带 SDL3 GPU 光栅器
- 上游是 Ballance 的反编译/复刻代码，**需要自备正版 Ballance 资产**，本仓库不含任何游戏资源。

本仓库只放自己的代码与文档，上游一律按脚本拉取（不 vendored、不改别人 LICENSE）。
