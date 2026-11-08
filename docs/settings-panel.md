# 设置面板（F1）

Steve 的几个开关以前只能靠环境变量，改一次就是一次重启。这一步把它们放进
一个游戏内的面板：F1 呼出，点一下就地生效，同时写进自己的 ini。

代码在 `Source/Player/src/`：`MinecraftSettings.*`（配置的唯一真相）、
`MinecraftOverlay.*`（面板）、`MinecraftFontAtlas.h`（字形图集，生成物）。

## 九行

| ini 键 | 环境变量 | 面板标签 | 默认 | 步进 |
|---|---|---|---|---|
| `Steve` | `BALLANCE_MC_STEVE` | Steve in Ballance | 0 | 布尔 |
| `FollowBall` | `BALLANCE_MC_STEVE_BALL` | Follow the ball | 1 | 布尔 |
| `HideSteve` | `BALLANCE_MC_STEVE_HIDE` | Hide Steve | 0 | 布尔 |
| `HideBall` | `BALLANCE_MC_STEVE_HIDEBALL` | Hide the ball | 0 | 布尔 |
| `Scale` | `BALLANCE_MC_STEVE_SCALE` | Model scale | 0.25 | ±0.05，限 0.05–2.0 |
| `Sensitivity` | `BALLANCE_MC_STEVE_SENS` | Mouse sensitivity | 0.0025 | ±0.0005，限 0.0005–0.02 |
| `AimLift` | `BALLANCE_MC_AIM_LIFT` | Aim lift (degrees) | 20 | ±1，限 0–30 |
| `Block` | `BALLANCE_MC_BLOCK` | Block size (units) | 5.48 | ±0.05，限 2.0–8.0 |
| `Debug` | `BALLANCE_MC_STEVE_DEBUG` | Debug log | 0 | 布尔 |

`Block` 是一粒方块画多大（Ballance 单位），改的就是相机通道里的 `UnitsPerBlock`：

- **5.48 = 变球器底板的宽度**（`P_Trafo_*` 是 5.48 x 5.48 x 0.50 u），于是摆在变球器旁的
  方块和它一样大。这是现在的默认。
- `4.0` = 标定表里的「一个球直径一格」，也是这个值原先的硬编码。

两个数都说得通，取哪个是观感问题，所以做成了面板行而不是常量——`AimLift` 当初也是
这样从常量搬进面板的。调这个等于整体缩放 Minecraft 世界相对 Ballance 的比例：调大，
方块变大、球相对变小。

`MinecraftSettings.cpp` 里是一张 `kRows[]`，ini 键 / 环境变量 / 标签 / 步进 /
上下界 / ini 注释都在同一行，行数用 `static_assert` 和 `enum McSettingRow` 对齐。
加一个开关只需要动这一处，再加一个 enum 值——另外三处（写 ini、读环境、画面板）
都是按表遍历的。

## 文件与优先级

    <游戏目录>/Bin/Minecraft.ini      <- 面板写这里
    <游戏目录>/Bin/Player.ini         <- 引擎自己的，面板永远不碰

优先级 **环境变量 > ini**。这样所有既有截图脚本（都用环境变量钉值）行为一字不变；
被环境变量压住的行，面板上用橙色 `[env]` 标出来——否则点一下没反应会像坏按钮。
ini 缺失不是错误：用默认值跑起来，并写一份带注释的默认文件（这样「面板记不住」
和「文件没生成」一眼能分开）。

`tools/run/make-run.sh` 和 `tools/poc1/run-poc1.sh` 的 rsync 都加了
`--exclude=Bin/Minecraft.ini`：stage 里没有这个文件，`--delete` 会在每次启动时
把它删掉，玩家设置看起来就像从未保存过。

## 面板怎么操作

- **F1** 随时开合（任何场景，菜单里也行）；**Esc** 也关。
- 布尔行：整行就是靶子，点哪儿都翻。
- 数值行：右侧 `-` / `+` 两个方框，点一次一步。
- 底部 **Restore defaults**：回出厂值，再把环境变量叠回来（钉住的行保持钉住）。
- 每次改动立刻落盘，日志有 `saved <path>`。

打开的瞬间把世界冻住，关掉恢复。冻法见下一节。

## 一个坑：CKContext::Pause() 会把输入一起弄死

第一版面板打开时调 `context->Pause()`，引擎自己的暂停语义。结果：面板能画，
**点不动，F1 也关不掉**。

原因在 `CKContext::Process()`：

    CKERROR CKContext::Process() {
        if (m_Playing) {            // <- 暂停之后整段被跳过
            ExecuteManagersPreProcess();
            ...

SDL 输入管理器（`SdlInputManager`）是一个 PreProcess 管理器，事件是在 `PreProcess()`
里才从队列搬进 `m_Mouse` / `m_KeyboardState` 的。上下文一暂停，PreProcess 不再执行，
**任何一个鼠标和按键事件都进不来**——包括本该关面板的那一下 F1。

改成把时间缩放设为 0：

    CKTimeManager *timeManager = (CKTimeManager *)context->GetManagerByGuid(TIME_MANAGER_GUID);
    m_SavedTimeScale = timeManager->GetTimeScaleFactor();
    timeManager->SetTimeScaleFactor(0.0f);      // 每帧再压一次，防止关卡脚本改回去

上下文继续 playing（输入照常进），而 `GetLastDeltaTime()` 变成 0，物理和脚本原地不动。

测量：换之前一次真 `xdotool` 点击，日志里连 `click at` 都没有（指针坐标一直是启动时
那一个值）；换之后同一脚本七次点击七次命中，见下节。

## 另一个坑：不钉环境变量时，模型按 4 倍身高摆

`ReadEnvironment()` 第一版把缩放的读取放在开关判断之后：

    if (!m_Enabled) return false;        // Steve 总开关关着就整段跳过
    ...
    m_Height = 32.0f * SCALE;            // <- 不设 BALLANCE_MC_STEVE 就走不到这里

不设 `BALLANCE_MC_STEVE` 时（也就是「只用 ini」的正常玩法），函数在这里提前返回，
`m_Height` 停在初始的 32，而顶点已经按 `m_Scale` 缩过了。模型本身画对了，
**摆放却按 4 倍身高算**：脚底沉进地板、头飘在空中，第一人称的视点也跟着偏。
截图脚本一律显式写 `BALLANCE_MC_STEVE=1`，所以这个 bug 只在「ini 驱动」这条路上露头。

修法：把缩放的计算提到提前返回之前——缩放是模型尺寸，跟总开关无关。

## 测试钩子

| 变量 | 作用 |
|---|---|
| `BALLANCE_MC_PANEL_TEST=1` | 启动即开面板（截图/点击脚本抓帧时没法先按 F1） |
| `BALLANCE_MC_PANEL_LAYOUT=1` | 打开后打一次版面：窗口尺寸、面板矩形、每行矩形、数值行的 `-`/`+` 矩形 |
| `BALLANCE_MC_PANEL_POINTER=1` | 每 0.4 秒打一次指针（窗口坐标 / 绝对坐标 / 左键状态） |

面板每次按下都记一行 `click at x,y row N button M`，**打空也记**（`row -1`）。
「点了没反应」因此能分成三种：没收到按下 / 收到了但打空 / 打中了但没生效。

`tools/panel/panel-input-test.sh` 把这件事跑成一条命令：起游戏 → 等版面日志 →
用 `xdotool` 瞄准**日志里给出的那些矩形中心**去点（这样顺带验证了鼠标坐标系和
绘图坐标系是同一个，而不是两边各自「应该没问题」）→ 每一步回读日志与 ini。

一次通过的输出（`run/panel-input/panel.log`）：

    -- click row 0 (Steve) -> off: window 320,101
       click at 320.0,101.0 row 0 button 0
       Steve = 0
       saved .../Bin/Minecraft.ini
       steve off                        <- Steve 当帧就不画了
    -- click row 5 (Scale) plus: window 570,283
       click at 570.0,283.0 row 5 button 12
       Scale = 0.3000
    -- click panel edge, expected miss: window 53,137
       click at 53.0,137.0 row -1 button 0
    -- click Restore defaults: window 320,395
       click at 320.0,395.0 row -1 button 0 reset
       restored defaults
    -- key F1 (close)      panel closed
       presses while closed: 0 (expected 0)     <- 关掉之后同一处点击不再进面板
       ini unchanged: True
    -- key F1 (reopen)     panel opened
    -- key Escape (close)  panel closed

鼠标必须**按住几十毫秒**再松开：SDL 会把按下和抬起在同一帧里送达，而命中判定读的是
`IsMouseButtonDown()` 的帧末状态。真按键（F1/Esc）不用管，面板是从帧内键盘缓冲
（`GetKeyFromBuffer`）取按下的，一帧内的按下+抬起也认。

## 验收：ini 驱动的 A/B（一个 `BALLANCE_MC_STEVE*` 都不设）

`tools/steve/ini_ab.sh` 跑两遍完全相同的流程（等菜单 → Play → 进 Level_01 → 停 6 秒 → 抓帧），
两遍的唯一差别是 `run/game/Bin/Minecraft.ini` 里的 `Steve=` 那一行；环境里没有
`BALLANCE_MC_STEVE`，也没有 `..._SCALE`。

    Steve=1 -> differing pixels: 6104 / 307200
               bbox x[188..449] y[113..347]  262x235
               shirt 614 px (mean row 235) / skin 1434 (224) / pants 181 (250)
    Steve=0 -> 同一帧里没有 Steve；日志里连 [mc-steve] steve on 都没有

对照早前用环境变量钉值的 A/B（6261 px、bbox 263x238、shirt 614 / skin 1467 / pants 181）：
同一块区域、同一套配色、皮肤行仍在衬衫行之上、裤子之下——画出来的还是那个模型，
这次开关只来自 ini。

一次白跑的记录，免得下次再撞：第一版把抓帧定在 `FRAMES=420`（沿用 look_shots 的帧号），
结果 0 px 差异。不是没画，而是那时还在菜单里（`level state: menu`），而菜单里 Steve 是
刻意 `CKHIDE` 的（第一人称在菜单里会抓指针，按钮就点不动）。A/B 必须真的点进关卡再抓帧。

## 已知口子

- 面板只有 ASCII 字形（`tools/panel/make-font-atlas.py` 用 DejaVu Sans Mono 烘的
  8x12 图集）。要中文得另烘一份 CJK 图集；布局按 8x12 单元算，换字号重跑脚本即可。
- Esc 同时是游戏自己的菜单键：关面板会顺带把游戏暂停菜单叫出来（面板盖在上面）。
- 被环境变量锁住的行点得动、也会写进 ini，但当次运行仍以环境变量为准。
- 面板开着时鼠标不进相对模式（`WantsRelativeMouse()` 排除面板打开状态），视角不会跟着
  鼠标乱转。
- 面板是自绘覆盖层（渲染上下文 post-sprite + CK 裁剪空间图元），不生成 CK 2D 实体。

## 复现

    tools/run/make-run.sh                 # 组装 game 目录（保留 Minecraft.ini 位置：--exclude）
    bash run/play-mc.sh                   # 正常玩：F1 开面板
    tools/panel/panel-input-test.sh       # 点击 + 按键全流程，结果在 run/panel-input/
    tools/steve/ini_ab.sh                  # ini 驱动的 A/B（不设任何环境变量），结果在 run/ini-ab/
    tools/panel/make-font-atlas.py        # 重新烘字形图集（改字号/字体）
