# 源码级跳关：Ballance 关卡在 Linux 上从源码渲染成功（2026-11-02 23:0x）

接上一条「Linux 侧还能干什么」清单的第 1 项**源码级跳关**。结论先说：**关卡数据能加载、能渲染出画面**，
不再是「Failed to retrieve the level!」，也不再是「合并了但看不见」。

## 关键事实（全部实测）

1. **关卡 .NMO 里没有 CKLevel**。`Level_01.NMO` 只有 cid 23/41 的对象，无 cid 21。
   原版 Player.exe 是把关卡**并入** base.cmo 那份合成（level 对象、管理器和玩法脚本都在 base.cmo 里）。
   Ballanced 的 `CGamePlayer::Load()` 会重置上下文再整体加载，所以拿关卡当 `--cmo` 必然失败。
2. 补丁 `CGamePlayer::LoadLevel()` 把关卡并入当前 level：356 个对象、level+scene 双挂、渲染上下文
   `AddObjectWithHierarchy`、最后 `LaunchScene(NULL)` 让 `CKScene::Init` 重新登记。
3. **几何真的被画出来了**。逐帧日志（1200 帧时）：
   `merged 3d=128 visible=124 attached=128 inScene=128 inFrustum=35..55`。
   关卡包围盒 min=(-597 -355 -1482) max=(1296 81 392)，对象名是 `A01_Floor_04 / A01_Rail_08/09 /
   A02_Tower / A02_Rail_01 / A03_Floor_Colums_Bottom / Quader01/02` —— 正是第一关的起始段。
4. **之前「看不见」的真因是相机，不是几何**。合成在每次 LaunchScene 时把自己的
   `Cam_MenuLevel` 挂到视点上，LoadLevel 里那一次性 `AttachViewpointToCamera` 早被覆盖；
   菜单机位在 (31,40,90) 一带，关卡在 z=-138..-915，于是只多出约 7 个百分点的覆盖率。
   改成**每帧**在 `CGamePlayer::Render()` 里重挂相机后，同一相机两个帧差 0.0（完全静止），
   覆盖率从 33%（只有菜单）升到 22%（只画关卡，菜单被隐藏），深度分布出现近处几何。
5. 出图：`~/ballance-mc-frames/{start,ground,over}.png`（640x480，亮度 sd≈46，不是单色）。

## 复现

```bash
# 1) 打补丁（幂等；子模块被 reset 后重跑即可）
python3 ~/src/ballance-mc-passthrough/tools/levelboot/apply-levelboot-hook.py
# 2) 构建（增量 ~40 s，需要 cmake 在 PATH：~/.local/bin）
cd ~/src/Ballanced && cmake --build --preset linux-x64-runtime-stage-release
# 3) 出图
~/src/ballance-mc-passthrough/tools/levelboot/render-level-frames.sh
```

开关：`--level <file>`、`BALLANCE_POC_ISOLATE=1`（隐藏合成对象，只留关卡）、
`BALLANCE_POC_CAM=x,y,z,tx,ty,tz`（每帧钉住机位）、`BALLANCE_POC_FRAMELOG=<n>`（逐帧可观察量）、
`BALLANCE_POC_BBOX=1`（关卡包围盒 + 屏幕投影面积排行）。

## 还没做到的（别夸大）

- **没有玩法**：球、物理、计时、相机跟随都还没接管。合并进来的只是关卡数据与对象，
  base.cmo 的玩法脚本仍停在菜单场景的流程里；`GetCurrentScene()` 此刻是没有名字的场景。
- 关卡自己的场景/脚本有没有跟着合并、要不要单独 LaunchScene，是下一步要确认的事。
- 子模块 `Source/Player` 现在是脏的（补丁），`git submodule update` 会冲掉，重跑脚本恢复。

## 下一轮从这里接

1. 查 `CKContext::Load` 合并时关卡自带 scene 去了哪（`CKScene::MergeScene` 路径），
   如果关卡有自己的 scene，就直接 `level->LaunchScene(levelScene)` 让它的脚本跑起来。
2. 玩法接管真正的入口是 base.cmo 里那套 Gameplay/LevelManager 脚本，它原本负责「读关卡 + 启动」；
   找到它比继续在 C++ 里手工拼更接近原版行为。
