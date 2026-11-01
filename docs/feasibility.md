# PoC-0：可行性验证报告

日期：2026-11-02 · 环境：Linux x64（本机 192.168.3.59）
上游版本：Ballanced `ececf94`（sdl, 2026-10-29）、CKRenderEngine `049c78b`（sdl, 2026-10-29）

## 一、结论

| # | 生死问题 | 判定 | 依据 |
|---|---|---|---|
| 1 | 宿主渲染后端的**深度**能不能拿到 | ✅ 能 | `CKSdlGpuPresentTarget` 里颜色与深度是两个独立纹理，且 present stage 自带 readback 通道 |
| 2 | 能不能给 MC 提供**脚下地面高度** | ✅ 能 | `CKCollisionManager::RayIntersection` / `CK3dEntity::RayIntersection` |
| 3 | MC 的**方块能不能变成 Ballance 的碰撞体** | ⚠️ API 齐全，待运行验证 | `CKCollisionManager::AddObstacle(ent, moving, CKCOLLISION_BOX)` + `RemoveObstacle` / `RemoveAllObstacles` |
| 4 | 能不能在 **Linux** 上开发和调试 | ✅ 能 | `CMakePresets.json` 含 `linux-x64-runtime` |
| 5 | 相机能不能喂给 MC | ✅ 能 | `CKCamera : CK3dEntity`，有 `SetFov()` |

**判定：项目成立，可以进入 PoC-1。** 最大的未知从"深度拿不到"变成了"动态 obstacle 的性能与上限"。

## 二、关键证据（全部是读代码得出的，不是看 README）

### 1. 渲染后端是 SDL3 GPU，不是 D3D9

`Source/RenderEngine`（= CKRenderEngine）的光栅器目录：

```
src/CKRasterizer/CKFFPLib              D3D9 固定管线 → 着色器的翻译层
src/CKRasterizer/CKSdlGpuRasterizer    SDL3 GPU 后端（sdl 分支新增）
src/CKRasterizer/CKShaderJit
```

`CKFFPLib` 负责把 Virtools 的固定功能管线翻译成现代着色器，`CKSdlGpuRasterizer` 才是真正的后端。
**这就是"这次比 GTA 那次干净"的根本原因**：不需要注入、不需要 ReShade、不需要挂钩 D3D9。

### 2. 深度是独立纹理，且已有下载通道

`src/CKRasterizer/CKSdlGpuRasterizer/Rasterizer/CKSdlGpuPresentStage.h:14`

```cpp
struct CKSdlGpuPresentTarget {
    CKDWORD ColorTexture;
    CKDWORD DepthTexture;     // ← 深度就在这儿
    CKDWORD FrameBuffer;
    CKDWORD Width, Height, Samples;   // Samples: 0 = 单采样
    CK_DEPTH_FORMAT DepthFormat;
};
```

- 深度格式：`CKRST_DEPTHFMT_D24S8` / `D24` / `D16`（`CKSdlGpuRasterizerTargets.cpp:77`、`CKSdlGpuPresentStage.cpp:177`）
- 下载通道已存在：`SDL_GPUTransferBufferCreateInfo{SDL_GPU_TRANSFERBUFFERUSAGE_DOWNLOAD}` + `SDL_DownloadFromGPUTexture`（`Native/CKSdlGpuCommands.cpp:1323`）
- present stage 本身就提供 readback 纹理：`AcquireReadbackTexture()` / `GetReadbackTexture()`（`CKSdlGpuPresentStage.h:87`），注释写明 *"Readbacks blit the native target into the readback texture"*

⚠️ **唯一的技术坑**：`Samples` 非 0 时（MSAA）深度不能直接 blit 下载，需要先 resolve 到单采样目标。**PoC-1 先关 MSAA 做**，跑通再处理。

### 3. 地面检测

`Source/CK2/include/CKCollisionManager.h` 明确列出三组射线函数：

```
RayIntersection()         对全部障碍物
FixedRayIntersection()    只对静态障碍物
MovingRayIntersection()   只对移动障碍物
```

蓝本每帧打 160 条射线（40 格半径网格）是为了给 MC 的 barrier 铺地形；Ballance 里降到 9~25 条足够，
因为球道窄、而且 MC 侧本来就有自己的方块碰撞。

### 4. 方块 → 碰撞体（最关键的 API）

`CKCollisionManager.h:135`

```cpp
virtual void AddObstacle(CK3dEntity *ent, CKBOOL moving = FALSE,
                        CK_GEOMETRICPRECISION precision = CKCOLLISION_BOX,
                        CKBOOL hiera = FALSE) = 0;
virtual void RemoveObstacle(CK3dEntity *ent) = 0;
virtual void RemoveAllObstacles() = 0;
```

- `CKCOLLISION_BOX` 走包围盒，**不需要真实网格** → MC 的一个方块 = 一个 bbox，正好 1:1
- 现成的 BB 参考实现：`Source/BuildingBlocks/Collision/Behaviors/AddObstacle.cpp`
- 蓝本在 GTA 里用的是 `SET_ENTITY_COLLISION` + 上限 400 个；Ballance 侧的**上限和每帧开销未知**，这是 PoC-2 要量的

### 5. 构建与挂点

- 预设：`windows-{x86,x64,arm64}`、`linux-{x64,arm64}`、`macos-{x64,arm64}` × `{base,runtime,static,tests}`
- 主循环/窗口：`Source/Player/src/GamePlayer.cpp:555` → `SDL_CreateWindow("Ballance", ...)`
- `Source/` 下 9 个子模块：`Player`(45 文件) `CK2`(287) `RenderEngine`(820) `VxMath`(388) `BuildingBlocks`(911) `Plugins`(399) `Managers/{SdlInputManager,SdlSoundManager,ParameterOperations}`

## 三、还没验证的（PoC-1 / PoC-2 的任务）

| # | 未验证项 | 风险 | 怎么验 |
|---|---|---|---|
| 1 | MSAA 深度的 resolve 路径 | 中 | 关闭 MSAA 先跑通 |
| 2 | 动态 obstacle 的数量上限与每帧成本 | **高** | 加 100/400/1000 个盒子，测球的碰撞与帧时间 |
| 3 | "无网格纯 bbox 实体"能否被 `AddObstacle` 接受 | 中 | 造一个只有 bbox 的 `CK3dEntity` |
| 4 | 相机脚本（Hardness）会不会抢走相机控制权 | 中 | 自由视角要在哪一层插入 |
| 5 | 输入转发：MC 是独立窗口，SDL 事件怎么送给它 | 中 | 平台层合成事件 vs 直接喂 MC 的 window handle |
| 6 | 真实资产（`Level Assets`、`3D Entities`）的加载路径 | 低 | 按 `GameConfig` 配好目录 |

## 四、下一步

1. **PoC-1**：`linux-x64-runtime` 构出来跑通主菜单 → 在第 N 帧把 `DepthTexture` 下载成 PNG 落盘。
   验收：能看见球道深度图（近处亮、远处暗），证明深度通道可用。
2. **PoC-2**：起一个后台线程每帧 `AddObstacle` 一个盒子在球道正前方 + `RemoveObstacle`。
   验收：球被盒子挡住、帧时间掉多少。
这两步过了，M0 就是纯工程活。
