# Minecraft HUD（M3-S1）

Minecraft 的抬头显示，叠在 Ballance 关卡上：**准星、9 格快捷栏、10 颗心、经验条**，
第一人称时再加一条手臂。Steve 的模型本身默认不画（见文末）。

> **⚠️ 这套 HUD 是临时件。** M0b 起穿透传的是 MC 的**整帧**，MC 自己的准星/快捷栏/心
> 已经在里面了；这里画的是重复的。打通 M1/M2 后一次性收掉（见「归属」一节）。
> 当前 `Hud=1` 与穿透同时开 = 两套叠在一起，这是已知问题，**故意未修**。

## 口径（开工前定的，不再改）

| 问题 | 选择 |
|---|---|
| Steve 模型隐藏到什么程度 | 不画身体，但画 MC 第一人称手臂/手持物 |
| HUD 第一版画什么 | 准星 + 9 格快捷栏 + 选中格高亮 |
| HUD 数值接不接真实状态 | 接 Ballance：生命数 → 血量，分数 → 经验条 |
| HUD 开关存哪 | F1 面板加 `Hud` / `HudScale` 两行，写进 `Minecraft.ini` |
| Steve 代码怎么处理 | 代码保留、默认关闭，面板行保留 |
| 验收方式 | 本机 Linux 跑关卡截图，HUD 开/关两帧对比出数字 |

合并后 HUD 实际包含：准星、9 格快捷栏（选中格高亮）、10 颗心（= 生命数）、
经验条 + 等级数字（= 分数）。心与经验条是"接真实状态"的载体——不画它们，
那两个数字就没有地方显示。

## 代码

`Source/Player/src/MinecraftHud.{h,cpp}`（补丁见 `tools/ballanced-patches/Player.patch`）。

走**渲染上下文的 post-sprite 回调**，和 F1 面板同一条路（`AddPostSpriteRenderCallBack`）：
不建任何 CK 2D 实体，不碰 3D pass，所以遮挡/雾/光照与它无关。
`CGamePlayer::Render()` 里注册顺序是 **HUD → 面板 → Steve → 相对鼠标**：
HUD 先注册，面板的压暗底板才会盖在 HUD 之上。

| 元素 | 画法 |
|---|---|
| 准星 | 屏幕中心十字，臂长 9 单位，1 像素粗，白色 alpha 235，下面垫一层黑色 alpha 90 的描边（Ballance 的浅色天空会把纯白准星吃掉） |
| 快捷栏 | 9 × 20 单位格，格底 `rgba(24,24,24,235)`，四边 1 像素亮边；选中格抬高 2 像素 + 白框 |
| 心 | 8×7 像素位图心形，10 颗，**一次 draw call**（TRIANGLELIST 批量，560 px/帧；逐像素画会吃帧时间）。活心 `#DC2020`，空心 `#461414` |
| 经验条 | 9 格宽、5 像素高，深底 + `#80FF20` 填充；`level = points / 100`，`fraction = (points % 100) / 100`，等级数字用面板那套字体图集画在条上方 |
| 手臂 | 屏幕右下两条平行四边形（袖子 `#00AFAF` + 描边 `#008484`，手 `#C68D6B` + `#A0694E`，手里一块土），sin/cos 呼吸摆动；**只在一人称时画**（`m_Steve->FirstPersonActive()`），第三人称视角画手臂是假的 |

单位：`min(w,h) / 480 * HudScale`，即 640x480 下 1 单位 = 1 像素。

## 三个踩过的坑

### 1. 半透明填充画在不透明边框上 → 中灰，不是黑

槽底 `rgba(24,24,24,170)` 叠在 `rgba(139,139,139,150)` 的框上，
实测出来是 `(54,53,51)`——"九格深色槽"看着像九条灰带。改成 alpha 235 才落到 `(31,30,29)`。
同理槽与槽之间没有分界时（填充铺满整条）会连成一条，所以每格填充四边内缩 1 像素，
让框色露出来当分隔线——像素验收里的"九个深色连通段"就靠这个。

### 2. 判"Steve 在不在画面里"不能用调色板匹配整张图

关卡石头恰好等于**半亮的皮肤色**（`(198,141,107)` × 0.5 ≈ 岩石的 `(104,61,50)`），
宽松的"任何明度的调色板色"匹配在空场景里也能数出五万个"Steve 像素"。
只有**衬衫的青绿**（g == b、红接近 0）在这些关卡里没有替身：测下来
`none` / `hud` / `slot` 三帧各 0 个，`body` 帧 3866 个，一人称帧 6106 个。
验收脚本因此用青色当 Steve 的身份指纹。

### 3. HUD 抓帧不能用 depth dump

`BALLANCE_POC1_DEPTH_FRAMES` 的 native-color 是**提交阶段**的帧，post-sprite（HUD/面板）还没画上去。
抓 HUD 只能用 X 层面的 `ffmpeg -f x11grab`。

## 数据源：关卡的 Energy 数组

`CKDataArray` 名 `Energy`，第一行就是当前局的状态。实测关卡 1 的列：

```
Points, Lifes, StartPoints, StartLifes, Timefactor, LifeBonus = [0, 0, 1000, 3, 1140457472(float 496.0), 200]
```

两个坑：

* **列名拼错**：生命列叫 `Lifes`，不是 `Lives`；`StartLifes` 以 "start" 开头，
  `LifeBonus` 也以 "life" 开头。匹配规则：前缀 `point` 取最短者当分数列，
  前缀 `startlife` 单独认，其余前缀 `life` 取最短者当生命列（`Lifes`(5) 胜过 `LifeBonus`(9)）。
* **`--level` 直接进关卡时 `Lifes` 是 0**：新游戏那段初始化脚本没跑，
  数组还停在出厂值（`StartLifes = 3`）。所以读到的生命为 0 时回落到 `StartLifes`；
  大于 0 就用读到的那份。理由：真打法里"0 条命"意味着关卡已经结束，玩家看不到那一帧。
  分数不做这个回落（0 分本来就是新开局的正常值）。

## 开关：F1 面板两行 + ini + 环境变量

`MinecraftSettings` 的行表里第 8、9 行（从 0 数）：

| 行 | ini 键 | 环境变量 | 默认 | 范围 |
|---|---|---|---|---|
| 8 | `Hud` | `BALLANCE_MC_HUD` | 1 | 开/关 |
| 9 | `HudScale` | `BALLANCE_MC_HUD_SCALE` | 1 | 0.5–3，步进 0.25 |

优先级：环境变量 > `Bin/Minecraft.ini`（面板写）> 默认值。
另外两个只给截图脚本用的钩子：`BALLANCE_MC_HUD_SLOT=n`（钉住选中格）、
`BALLANCE_MC_HUD_LEVEL=0/1`（强制"关卡中"/"菜单"，默认按相机名判断）。

面板实测（`tools/panel/panel-input-test.sh`，真指针点击 + 回读 ini）：

```
-- click row 8 (Hud) -> off     click at 320.0,393.0 row 8 button 0
   hud off
   ini now: Hud=0
-- click row 8 (Hud) -> on      ...  hud on
-- click row 9 (HudScale) plus  ...  ini now: HudScale=1.2500
-- click row 9 (HudScale) minus ...  (回到 1.0000)
```

## Steve 身体：默认不画

`MinecraftSteve` 里把**身体**和**驾驶舱**拆开了：`m_DrawBody` 只由 `Steve=1` 决定，
模块本身的启用条件是 `steve || firstPerson`。所以默认（Steve=0、FirstPerson=1）时：
相机照样挂在球上、相对鼠标照样工作、HUD 的手臂照样画，**画面里没有任何方块人**。
面板的 `Steve` 行还在，想看模型随时打开。

## 验收

`tools/hud/render-hud-shots.sh` 起 6 遍关卡、每遍抓 3 帧，`tools/hud/hud_check.py` 出 12 条断言。

| run | 机位 | 变量 | 用来证明 |
|---|---|---|---|
| none | 钉住 | HUD=0 Steve=0 FP=0 | 干净世界（对照组） |
| body | 钉住 | Steve=1 | 模型还能画 |
| hud | 钉住 | HUD=1 | 只有 HUD、没有 Steve |
| slot | 钉住 | HUD=1 + SLOT=4 | 选中格跟着动 |
| fp | 一人称 | HUD=1 FP=1 | 手臂 |
| scale | 钉住 | HUD=1 + HudScale=2 | HudScale 行真的生效 |

实测结果（2026-11-05，关卡 1）：

```
PASS  steve model off by default  0 / 0 shirt pixels
PASS  steve model still drawable  3866 shirt pixels, 14259 px changed against the empty frame
PASS  hud draws in the bottom band  6112 px below y=422
PASS  hud leaves the world alone  66 px above the band vs 6112 inside it
PASS  crosshair is a white plus at the centre  vertical 18/19 px, horizontal 18/19 px white
PASS  hotbar is nine dark slots  9 slots at y=457, 4421 px dark vs 249 without the HUD
PASS  HudScale doubles the HUD  band spans 184 px at scale 1 and 368 px at scale 2 (x2.00)
PASS  ten hearts, one per life  10 hearts, 3 lit, lives=3 from the level
PASS  selection follows the number key  changed columns grouped at [239, 319], slots 1 and 5 sit at [240, 320]
PASS  experience bar above the hotbar  7 dark rows above y=457, tallest 185 px
PASS  first person arm  sleeve 4391 px in fp, 0 px in the parked-hud frame
PASS  hud is steady  0 px changed between two hud frames
12/12 PASS
```

截图：`docs/hud-shots/`（`00-none` … `05-scale2`，各取第 2 帧）。

复现：

```bash
cmake --build --preset linux-x64-runtime-release --target stage -j 16   # ~/.local/bin 要有 cmake/ninja
tools/hud/render-hud-shots.sh            # 六组帧 -> run/hud-shots，末尾自动跑断言
tools/panel/panel-input-test.sh          # 面板 row 8/9 点击 + ini 回读
```

## 归属：MC 帧已经带着 HUD，这套是临时件（2026-11-05）

一句话：**M0b 之后，屏幕上该出现的 MC 界面由 MC 自己画**；
本文件画的四个元素是“贴片还不可用”那段时间的替代品，等 M1/M2 打通就收掉。

事实链（都能在代码里对上）：

1. 生产者抓的是 MC 的**整帧**：`McFrameExport.java:258` 取
   `gameRenderer.mainRenderTarget()`，`Screenshot.takeScreenshot(...)` 在 GUI 之后抓，
   所以 MC 自己的准星、快捷栏、心、经验条**已经在帧里**。
2. 消费者把这块帧贴在场景之上、Ballance 自己的 2D 之前：
   `CKSdlGpuRasterizerFrame.cpp:361`（`BeginOverlayPhase` 里那句注释就是
   “over the scene, under the game's 2D interface”）。
3. 本文件的 HUD 走 post-sprite，在**所有** 2D 之后。

于是最终叠放顺序是：

```
MC 整帧（含 MC 的 HUD）
  → Ballance 的 2D（菜单、游戏内 UI）
    → 手搓 HUD + F1 面板
```

`Hud=1` 且穿透有帧时，屏幕上就有**两套**准星/快捷栏/心。这是已知问题，
2026-11-05 决定**先不动**（选 C），所以现在是**故意未修**，不是漏了。

### 当时为什么还要手搓

M0b 只传颜色，相机没同步（M1）、深度没有（M2）：打开穿透看到的是
“MC 世界全屏盖住球道”，不是能玩的画面。而 MC 帧里没有
**Ballance 的生命和分数**，这两个数必须有地方显示。于是画了一套 MC 外观的 HUD——
顺手把准星/快捷栏/手臂也画了，而这三样 MC 帧里本来就有，是重复劳动。

### 收的时候怎么收（M2 之后）

| 元素 | 归属 |
|---|---|
| 准星、快捷栏、手臂、心 | MC 帧（删掉本文件的画法） |
| 生命、分数 | MC 没有 → ① 注入 MC 状态让 MC 自己画（心 = 命数、XP = 分数），或 ② 保留一个两行数字的极简读数 |
| 遮挡 | Ballance 几何挡 MC 世界 = M2 深度重投影 |

GTA5 蓝本（`minecraft-gta5-passthrough`）之所以看起来“直接用 MC 的 UI”，是因为它除了颜色还传了
深度和**单独一层的 HUD/手**，HUD 层不参与深度测试，永远压在最上面。差别不在“HUD 谁画”，
在**深度**。M1 + M2 没到位之前，手工画得再像也不会对。

### 验收缺口

`tools/hud/render-hud-shots.sh` 的六组帧**全是 passthrough 关着跑的**，没有
“passthrough 开 + Hud=1”这一帧 —— 上一轮 12/12 PASS 就是这么漏掉双 HUD 的。
C 决定下暂不补；M2 落地、HUD 收掉之后，这一帧改由 passthrough 侧的验收覆盖。

## 还没做

* 准星/快捷栏/心与 MC 帧自带的 HUD 重复（决策 C，见上「归属」）。
* 快捷栏里没有物品：那一格现在是空的，选中格只跟数字键走。要放物品得先有“手里拿什么”这个概念。
* 生命/分数只在**关卡初始化过数组**后才是真值；`--level` 直启靠 `StartLifes` 兜底（见上）。
* Windows 包还没重打（Linux 侧验收完成，Windows 只能人肉试）。
* 打包成 BML+ 的 `.bmodp` 是另一件事，结论见 `docs/bmlplus-hud-mod.md`。
