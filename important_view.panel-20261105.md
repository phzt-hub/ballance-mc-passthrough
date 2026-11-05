# 设置面板 + Minecraft.ini（2026-11-05）

## 结论先说

F1 覆盖式设置面板已经跑起来了：进关卡后按 F1 弹面板，面板画得出来，Minecraft.ini 自动生成在
`run/game/Bin/Minecraft.ini`，改一项立刻回写文件，Steve 同一帧就吃新值（下个面板动作前不用重启）。

## 六个已定的设计口径（用户 2026-11-05 点选）

| 问题 | 选择 |
|---|---|
| 怎么呼出 | **F1 随时呼出覆盖层**（Esc 也能关）；不接进游戏自己的 Esc 菜单 |
| 怎么画 | **自绘覆盖层**：走渲染上下文的 post-sprite 阶段，用 CK 裁剪空间图元，不生成任何 CK 2D 实体 |
| 配置存哪 | **单独一个 Minecraft.ini**，和 Player.ini 并排（Bin/） |
| 收哪些项 | **只收稳定项**：Steve 总开关 / 跟随球 / 第一人称 / 隐藏 Steve / 隐藏球 / 模型缩放 / 鼠标灵敏度 / 调试日志（POC_* 那堆不进 ini） |
| 优先级 | **环境变量覆盖 ini**（面板上被环境变量压住的行走橙色 `[env]` 标记） |
| 提交 | 本轮把 Ballanced 脏工作区 + passthrough 新文件一起提交（先跑回归） |

## 代码落点

Ballanced `Source/Player/src/`：

- `MinecraftSettings.h/.cpp`（新）：8 行配置的唯一真相。一张 `kRows[]` 表同时给出
  ini 键名 / 环境变量名 / 面板标签 / 步进与上下界，`enum McSettingRow` 与之 static_assert 对齐。
  `Load()`：读 ini（缺文件不算错，直接写出一份带注释的默认文件）→ 再叠环境变量 → 记下哪几行被环境变量锁。
  `Save()`：逐行注释 + 当前值，幂等。`ResetToDefaults()`：回默认后重新叠环境变量（测试脚本钉住的行不会被面板"重置"掉）。
- `MinecraftOverlay.h/.cpp`（新）：面板本体。`AddPostSpriteRenderCallBack` 挂一次，
  只在打开时画：半透明压暗 → 面板底板 + 四边描边 → 标题/副标题 → 8 行（布尔行整行是靶、数值行右侧 -/+
  两个方框）→ Restore defaults 按钮 → 页脚显示 ini 路径。行矩形在 `ComputeLayout()` 里按窗口
  尺寸算一次，绘制与命中判定共用同一份矩形，不会出现"看着点上了其实没点上"。
- `MinecraftFontAtlas.h`（**生成物**）：95 个 ASCII 字形，8x12 单元格、16x6 网格，8 位覆盖率，base64 12 KB。
- `MinecraftSteve.h/.cpp`：新增 `SetSettings()`/`ApplySettings()`：
  8 个用户项改为**每帧**从 settings 读（面板改完当帧生效），其余仍是启动读一次的环境变量
  （POS/YAW/CAMOFF/LEVEL/TURN/BODY/LOOK/MOUSE/MENU 这些测试钩子）。
  缩放变化会当场 `SetScale` 到实体上。关掉 Steve 总开关会把实体 `Show(CKHIDE)` 掉，而不是只停止移动
  （实体在 scene 里，不隐藏的话游戏的 3D pass 会继续画）。
- `GamePlayer.h/.cpp`：`Init()` 末尾按 Player.ini 的目录定位 Minecraft.ini 并 Load，把
  settings 指针交给面板和 Steve；`Render()` 里顺序改成 **面板 → 告诉 Steve 面板开着没 → Steve → 相对鼠标**，
  面板先跑是因为 F1 和点击归它管；`Shutdown()` 里面板先于 Steve 收。

passthrough `tools/`：

- `tools/panel/make-font-atlas.py`（新）：用系统 DejaVu Sans Mono 烘焙上面那份 atlas，
  重跑即可换字号/字体；输出直接落到 Ballanced 的 Player/src。
- `tools/run/make-run.sh`, `tools/poc1/run-poc1.sh`：rsync 加
  `--exclude=Bin/Minecraft.ini`。原来 `--delete` 会在每次启动时把玩家刚改的设置删掉，
  面板看起来就像"记不住"。

## 为什么画在这里（两条已踩过的坑）

1. 走游戏自己的 2D 实体 = 每个关卡一份面板、随关卡切换重建，还得靠 CK 脚本树去挂；用 post-sprite 回调
   只存在一份，菜单里和关卡里都有。这条路是游戏自己的 Mouse Cursor 行为用的同一条。
2. 面板打开时 `CKContext::Pause()`（注意 API 无参，恢复用 `Play()`）：世界停住，
   点击不会同时打到后面的东西。CK 的 2D 图元接口照抄 `BuildingBlocks/Interface/DrawingPrimitives.cpp`：
   `GetDrawPrimitiveStructure(CKRST_DP_CL_VC / CL_VCT)` + `DrawPrimitive(VX_TRIANGLEFAN)`，
   像素坐标、免变换。

## 验收（已测到的部分）

`run/panel-test/panel.log`（14 秒、Level_01、`BALLANCE_MC_PANEL_TEST=1` 强制开面板）：

    [mc-panel] font atlas: 9216 bytes decoded, 128x128 texture
    [mc-panel] panel opened
    [mc-steve] built: 144 vertices, 72 triangles, 8.0 units tall, scale 0.250, first person 0

- 第 420 帧彩色帧（640x480 BGRA）统计：整帧均值 rgb(20.7, 22.8, 26.3)，贴合面板底板色 (20,22,27)
  的像素 219187 / 307200，bbox x52..636 y16..468 —— 面板确实占了屏幕中央一大块。
- 把该帧降采样成 80x40 的亮度字符画，能读出结构：外框、两行标题、8 行"方框 + 文字"（数值行的右侧
  有 -/+ 两个小方块）、中下方 Restore defaults 按钮、最下方 ini 路径。
- `run/game/Bin/Minecraft.ini` 已按默认值生成（含每行中文/英文注释），说明 Save 路径通。

## 还没做（下一步照这个顺序）

1. **点击命中**：xdotool 真点若干行，验证 `GetMousePosition(rel, FALSE)` 的坐标原点和面板矩形一致
   （若偏，改成 `rel + GetWindowRect().GetTopLeft()`，与 Poc1InputLog 的 Pick2D 口径对齐），
   验证点一行能翻转 + 回写 ini + Steve 当帧响应。
2. **F1 真键开合**：xdotool key F1 两次，日志应有 `panel opened`/`panel closed`，
   且关闭后相对鼠标恢复（`relative mouse mode on`）。
3. **回归**：`menu_shots.sh` 8 项、`look_shots.sh` 10 项、`body_shots.sh` 6 项全过；
   注意这些脚本都用环境变量钉值，路径上必须和以前一致。
4. 文档：新写 `docs/settings-panel.md`（开关表 + F1 + ini 位置 + 环境变量覆盖关系）。
5. 导出 `tools/ballanced-patches/Player.patch`（干净 worktree 上 `apply --check` 必须过）。
6. 提交：Ballanced 子模块（Player 一处）+ passthrough（tools/panel、两个 runner 的 exclude、文档）。
7. 收尾：`run/play-mc.sh` / `play-mc-fp.sh` 里写死的 `BALLANCE_MC_STEVE` 等变量可以退役
   （ini 已是默认源），但要留一个 `--reset` 或说明，避免"我明明在面板里关了怎么又开了"。

## 已知口子（不是 bug，写在前面）

- 面板文字只有 ASCII（atlas 是 8x12 点阵），所以行名是英文；要做中文得另烘一份带 CJK 的 atlas。
- 面板打开时按 Esc：面板关掉，同时游戏自己的暂停菜单也可能在背后打开（Esc 是游戏菜单键）。
- 环境变量锁住的行：点得动、ini 会写，但当次运行仍以环境变量为准，面板上用 `[env]` 标出来。

## 收尾（2026-11-05 11:05）

上面「还没做」七项已全部做完：

1. 点击命中：`tools/panel/panel-input-test.sh` 七次真 `xdotool` 点击全命中（行 0 翻转 Steve、行 4 HideBall、
   行 5 Scale ±0.05、打空记 `row -1`、Restore defaults 回默认并落盘）。
2. F1 / Esc 真键：`panel opened → closed → opened → closed`；关掉后同一处点击不再进面板、ini 不变。
3. 回归：`look_shots` 10/10、`body_shots` 6/6、`menu_shots` 8/8。
4. 文档：`docs/settings-panel.md`（新增），`docs/steve-in-ballance.md`（补一节）。
5. `Player.patch` 重新导出（base e5073295，12 文件 3932 行），干净 worktree 上 `apply --check` 通过、
   应用后与活工作区逐字节相同。
6. 提交见下（Ballanced 子模块照旧不提交，补丁是唯一凭据）。
7. `run/play-mc.sh` 不再写死 `BALLANCE_MC_STEVE*`；`play-mc-fp.sh` 保留钉住的两行并在文档里说明面板上会显示 `[env]`。

新挖出并修掉的两个真问题：面板打开时 `CKContext::Pause()` 让输入管理器停摆（面板画得出、点不动、F1 也关不掉），
改成时间缩放 0；`ReadEnvironment()` 的缩放计算在 `if (!m_Enabled) return false` 之后，不设环境变量时
`m_Height` 停在 32（4 倍身高）。

## ini 驱动的 A/B（本轮最后一项，已过）

`tools/steve/ini_ab.sh`：两遍相同流程，唯一差别是 `Minecraft.ini` 的 `Steve=`；环境里没有任何
`BALLANCE_MC_STEVE*`。结果 6104 / 307200 px 差异，bbox 262x235，shirt 614 / skin 1434 / pants 181，
与早前环境变量钉值那次的 6261 px / 263x238 / 614-1467-181 同源。

白跑记录：先前把抓帧定在 `FRAMES=420` 得 0 px——那时还在菜单（`level state: menu`），菜单里 Steve 故意
`CKHIDE`，必须先点进关卡再抓帧。
