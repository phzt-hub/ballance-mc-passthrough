# 把 HUD 打包成 `.bmodp` 装进 BML+ —— 可行性（2026-11-05）

> **2026-11-06 作废**：手搓 HUD 已从 Ballanced 侧删除（用户决定不要，MC 帧自带的那套才是真的）。
> 这篇留作 BML+ 钩子能力的记录，**不要再按它开工**。

结论先说：**能做，但不是给现在这套东西打包，是另写一个同功能的 mod。**

现在这套是**改 Player 源码**：`GamePlayer::Render()` 里注册 post-sprite 回调、SDL 侧的相对鼠标、自建 CK 3D 实体。
BML+ 的 `.bmodp` 是**插件**，它不能改 player，只能走 SDK 给的钩子。同一个功能要落地，得照 SDK 重写一份。

## 硬事实（都从 `doyaGu/BallanceModLoaderPlus` 源码与文档核过，v0.3.14）

| 项 | 结论 |
|---|---|
| `.bmodp` 是什么 | 一个 **Win32 x86 DLL**，导出 `BMLEntry` / `BMLExit`（`BML_MOD_ENTRY` 固定 C 调用约定），扩展名改成 `.bmodp`；拷进 `<Ballance>/ModLoader/Mods/` 即安装 |
| 宿主 | **只支持 BallancePlayer**（doyaGu 的 Win32 复刻 player）。README 原文："BML+ supports BallancePlayer only. It does not support the original Ballance player." 我们的 Ballanced（SDL3、x64、Linux）**不是**它的宿主 |
| 构建 | Windows + VS2019+（C++20）、Virtools SDK 2.1、BML+ SDK（release 里有 `BMLPlus-SDK-v0.3.14-Release.zip`）、**Win32 目标平台**；`bml_add_mod` / `bml_install_mod`，`bml pack` 出 `dist/*.bmodp` |
| 现有代码能直接搬吗 | 不能照抄：改源码那套在 mod 形态里没有对应位置，只能换成 SDK 钩子 |

## 需求与 API 的对应（几乎一一对上）

| 我们要的 | BML+ 给什么 |
|---|---|
| Lives / Points | **`BML::Gameplay::ReadEnergy`** → `BML_GameplayEnergyState{Points, Lives, StartPoints, StartLives, TimeFactor, LifeBonus}`，正好是 HUD 要的两个数，不用自己找 `CKDataArray`（也就绕开了 `Lifes` 拼写与 `StartLifes` 回落那一堆事） |
| 画 HUD | `IBML::GetRenderContext()` → 自己 `AddPostSpriteRenderCallBack`；或直接用 BML+ 自带的 ImGui（`Bui` / `ModMenu`，带 CK2 后端） |
| 每帧逻辑 / 相机 | `IMod::OnProcess()`（渲染前）+ `OnRender(flags)`（`ModManager::OnPostRender` 调，在渲染之后）。相机必须在 `OnProcess` 里改 |
| 键鼠 | `IBML::GetInputManager()` 的 `InputHook`（键、鼠标、手柄，含输入块配对），比现在 SDL 那套干净 |
| 面板与配置 | `IConfig`（带类型的属性）+ Mods 菜单页；不用再手绘 F1 面板、不用自己写 ini |
| 找对象/脚本 | `IBML::Get3dEntityByName` / `GetScriptByName` / `Behavior` / `ScriptHelper` / `ExecuteBB` |

## 风险与代价

1. **两套产物**：Ballanced 版（SDL3、x64、Linux，能本机调试 + 截图像素验收）和 bmodp 版（Win32，只能 Windows 编译 + 人肉试）。
   功能一样，代码两份。
2. **post-sprite 顺序**：BML+ 自己在 `OnPostSpriteRender` 里画 ImGui 覆盖层。我们的 HUD 回调若注册更晚就会盖住 Mods 菜单，
   需要联动（检测菜单开着时不自绘）。
3. **一人称相机**：得在 `OnProcess` 里每帧改 attached camera 的矩阵；BML+ 没有"相机跟球"的封装，
   逻辑可以从 Ballanced 版搬（`ApplyFirstPersonCamera`）。
4. **编译机**：必须 Windows + VS2019 + Win32。办公室那台 `10.0.0.4` 有 VS2019 BuildTools（见 `docs/windows-build.md`），可复用；
   Linux 交叉编译不可行（SDK 只发 MSVC 目标）。
5. **Steve 模型不需要了**：用户已定"不画身体、只画手臂 + 一人称机位"，所以 mod 版只剩 **HUD + 手臂 + 相机**，
   估 **800–1200 行**，比 Ballanced 版（HUD 780 行 + Steve 1300 行）小一半。

## 建议路线

1. 先在本机把 HUD 做对做稳（已完成，见 `docs/mc-hud.md`）——这条路能截图像素验收。
2. 再单独开一个 `mc-hud-mod` 工程：`bml.py new native` 起模板 → 数据用 `ReadEnergy` →
   HUD 用 post-sprite 自绘（和本机这套几乎逐行对应，只换数据源与输入层）→ `bml build/run/pack`。

## 不要做的两件事

* 不要在 Ballanced 里硬套 mod 形态（两套渲染/输入模型会互相打架）。
* 不要把 mod 立成"能装进原版 Ballance"——原版 player 不被 BML+ 支持。
