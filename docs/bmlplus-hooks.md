# BML+ 钩子能力核查（2026-11-01）

> 目的：量出"路线 A = BallancePlayer + BML+"的真实成本。
> 结论：**比我原先估计的便宜得多 —— 深度不需要任何逆向，碰撞和相机都是官方 API。**
> 证据出处：`doyaGu/BallanceModLoaderPlus` @ `a630008`（2026-11-01）、`doyaGu/Virtools-SDK-2.1`、`doyaGu/BallancePlayer` v0.3.9。

## 一、一句话结论

BML+ 不是"只能装 bmod 的壳"。它把**渲染上下文、碰撞管理器、物理创建、输入、相机实体**都公开给了 mod。
我上一轮列为"最大未知"的三件事里，**两件是官方 API，一件是官方结构体里的一个字段**。

## 二、对 mod 公开的接口

出处：`include/BML/IBML.h`、`include/BML/IMod.h`。

| 我们要的 | BML+ 给的 | 位置 |
|---|---|---|
| 渲染上下文 | `IBML::GetRenderContext()` | IBML.h:38 |
| 每帧逻辑 + 可画 ImGui/Bui | `IMod::OnProcess()` | IMod.h:241 |
| **场景画完、Present 前的回调** | `IMod::OnRender(CK_RENDER_FLAGS)` | IMod.h:245 |
| 碰撞管理器 | `IBML::GetCollisionManager()` | IBML.h:49 |
| 相机 / 实体 / 渲染管理器 | `GetCameraByName / Get3dEntityByName / GetRenderManager` | IBML.h:58, 128-141 |
| 输入 | `IBML::GetInputManager()` → `InputHook` | IBML.h:54, include/BML/InputHook.h |
| 物理创建钩子 | `IMod::OnPhysicalize(target, fixed, …, collGroup, collSurface, 凸包/球/凹面…)` | IMod.h:262 |
| 跨 mod 通信 | `DataShare` | include/BML/DataShare.hpp |

### 帧内时序（`src/Loader/ModManager.cpp:185-225`）

```
CKRenderContext::Render
  → 场景 + 精灵渲染
  → ModManager::OnPostRender      → 所有 mod 的 OnRender(flags)        ← 合成的插入点
  → ModManager::OnPostSpriteRender → Overlay::ImGuiOnRender()          (BML+ 自己的 UI)
  → Present
```

我们的合成 pass 天然落在"场景画完、BML+ UI 还没画、Present 还没发生"那一格。

## 三、深度：不用逆向，也不用自己挂 minhook

Virtools SDK 的公开虚函数 `CKRenderContext::GetDirectXInfo()`（`Virtools-SDK-2.1/Include/CKRenderContext.h:1169`）返回：

```c
typedef struct VxDirectXData {
    void *DDBackBuffer;  void *DDPrimaryBuffer;  void *DDZBuffer;   // ← 深度面在这里
    void *DirectDraw;    void *Direct3D;         void *DDClipper;
    void *D3DDevice;     void *D3DViewport;      XULONG DxVersion;
} VxDirectXData;                    // Include/VxDefines.h:295
```

BML+ **自己就在用这条路径抓帧**：`tests/player/PlayerFrameCapture.cpp:117-190`

```
GetDirectXInfo() → DxVersion == 0x0900 → (IDirect3DDevice9*)D3DDevice
  → GetBackBuffer → CreateOffscreenPlainSurface(SYSTEMMEM) → GetRenderTargetData → LockRect
(DX7 分支走 DDBackBuffer)
```

我们要的深度是同一张表上的下一格：

```
device->GetDepthStencilSurface()
  → device->CreateTexture(…, D3DFMT_INTZ, …)
  → StretchRect(depth, color=NULL, tex, …)
  → ps_3_0 里 tex2D 读 z
```

`D3DFMT_INTZ` 不被支持时退到 `D3DFMT_R32F`（先 `CheckDeviceFormat` 探一次）。

**所以 A 路线的深度成本 ≈ 0 逆向。** 上一轮担心的"要摸 CK2 内部偏移"不成立 —— `DDZBuffer` 就在公开结构里。

## 四、碰撞：三个层次都有官方入口

1. `CKCollisionManager::AddObstacle(ent, moving, precision, hiera)`（SDK `CKCollisionManager.h:135`），`CKCOLLISION_BOX` 就是盒子 —— **MC 方块 → 静态障碍物，一句调用**。
2. `RayIntersection / FixedRayIntersection`（同上 :82-86）—— 地面高度直接给 MC。
3. `IMod::OnPhysicalize` 给出物理创建的全部参数（凸包/球/凹面/collGroup/collSurface）；仓库内另有 `mods/IVP`（118 文件 / 46,502 行），把零售版 `physics_RT.dll` 里的 IVP 引擎封成 provider `bml.ivp`，需要真物理体也能走引擎本身。

## 五、相机与输入：自由视角不用自己写

`mods/CameraUtilities`（157 行，仓库自带）展示了原版相机的真实结构：
`OnLoadObject("3D Entities\\Camera.nmo")` 里取 `Cam_Pos / Cam_Orient / Cam_OrientRef / Cam_Target` 四个实体，然后直接 `Rotate / Translate / SetQuaternion`。
这就是 `docs/free-camera.md` 里那个 mod 干的事，**而且这份有源码**。
输入走 `InputHook`（`IsKeyPressed / IsKeyDown`），同样有现成示例。

## 六、代价（这一条没变）

| 项 | 要求 |
|---|---|
| 平台 | **Windows x86（Win32）**，没有 Linux 运行时 |
| 工具链 | MSVC（VS2022）+ **Virtools SDK 2.1** |
| 图形 | D3D9 / SM3.0（BallancePlayer 另支持 DX7 老路，我们只走 DX9） |
| 生态 | BML+ **只支持 BallancePlayer，不支持原版 `Player.exe`** |

## 七、这推翻了我上一轮的一个判断

上一轮我说："Ballanced 是唯一能拿到源码的路，代价是丢掉整个 bmod 生态。"
**错在把 BallancePlayer 当成了二进制黑盒** —— 它同样是反编译复刻、源码公开（v0.3.9，`Player.dsp` + CMake，Win32）。
两条路都"有源码"，真正的差别只剩平台与生态：

| | **A′：BallancePlayer + BML+** | **B：Ballanced（SDL3）** |
|---|---|---|
| 源码 | ✅ 反编译复刻 | ✅ 反编译 + 重写 |
| 深度 / 相机 / 碰撞 | ✅ 官方 API（本文档） | ✅ 源码直改 |
| **mod 生态** | ✅ BML+ / bmod / 自由视角 / 自定义地图 | ❌ 没有 |
| 平台 | ❌ Windows only | ✅ Linux 可开发 |
| 上游成熟度 | v0.3.9，社区主推方向 | `sdl` 分支，★49 |

## 八、结论与建议

**改推 A′：BallancePlayer + BML+。**

1. 三个生死问题里两个降级成"调 API"，第三个（深度）降级成"公开结构体里的一个字段"；
2. 白拿自由视角、mod 菜单、自定义地图、IVP 物理；
3. 用户手上有 Windows 机器，Linux 开发不是刚需。

保留 B 作为**无头验证台**：MC 侧 mod、共享内存协议、重投影算法都是宿主无关的，可以在 Linux 上用 `Ballanced` + 假宿主先把数学跑通。

## 九、下一步该验的（PoC-1 改写）

1. 装 BallancePlayer v0.3.9 + BML+，编一个空 bmod，确认能加载、`OnRender` 每帧被调；
2. 同一 mod 里给 `GetDirectXInfo()` 打日志：`DxVersion`、`D3DDevice` 非空、`DDZBuffer` 是否非空；
3. `GetDepthStencilSurface` + INTZ `StretchRect` 一次，落盘一张深度 PNG —— **这一步过了，M2 就成立**；
4. `AddObstacle` 加一个盒子，让球撞上去 —— **这一步过了，M3c 就成立**。

> 上游均需自备正版 Ballance 资产；本仓库不含任何游戏资源。
