# Steve 在 Ballance 世界里（M3-S0）

M0/M0b 的方向是反的：那套把 Minecraft 整帧贴到 Ballance 窗口上的管道，画面上
**世界** 是 Minecraft 的。这条路留着不动（`tools/passthrough/`），但真正的目标
是反过来：**Ballance 是那个世界，Steve 站进去。**

## 做法

`Source/Player/src/MinecraftSteve.cpp`（补丁见 `tools/ballanced-patches/Player.patch`）：

1. 用内存里画的 64x32 皮肤图集（`CKTexture::CreateImage` + `LockSurfacePtr`，不落盘、不解码）；
2. 按 6 个长方体拼出经典 Steve（144 顶点 / 72 三角面），一个 `CKMaterial`；
3. 塞进一个 `CK3dEntity`，`CKScene::AddObject` 进当前场景。

所以遮挡、光照、雾全部由游戏自己的 3D pass 完成——没有共享内存、没有重投影、没有光栅化钩子。

尺度按 `docs/scale-calibration.md`：1 个 MC 方块 = 4.0 单位 = 1 个球直径，
1 texel = 0.25 单位，Steve 高 8 单位（约 2 个球直径，和 MC 里 1.8 方块的身高对得上）。

## 「Steve 就是球」

关卡里的 `P_Ball_*` 是不会动的拾取物；真正的玩家球是**关卡脚本运行时创建**的
`I_Ball_*`（`I_Ball_Stone` 实测包围盒 4.08 x 4.15 x 4.07，正是 1 个球直径）。
`FindBall()` 每帧找一次、优先选 `I_Ball*`，把 Steve 的脚放在「球心减半径」，也就是台面上。

`BALLANCE_MC_STEVE_HIDEBALL=1` 把球网格隐藏，于是滚动的那摊东西就是 Steve 本人。

## 环境变量

| 变量 | 作用 |
|---|---|
| `BALLANCE_MC_STEVE=1` | 打开 |
| `BALLANCE_MC_STEVE_POS=x,y,z` | 固定脚底位置（不跟球） |
| `BALLANCE_MC_STEVE_BALL=0` | 不跟球 |
| `BALLANCE_MC_STEVE_YAW=deg` | 固定朝向 |
| `BALLANCE_MC_STEVE_SCALE=f` | 单位/模型单位，默认 0.25 |
| `BALLANCE_MC_STEVE_FP=1` | 相机放在 Steve 眼睛上（第一人称） |
| `BALLANCE_MC_STEVE_CAMOFF=dx,dy,dz` | 相机随 Steve 平移，拍第三人称跟拍 |
| `BALLANCE_MC_STEVE_HIDE=1` | 只建不画（对照组） |
| `BALLANCE_MC_STEVE_HIDEBALL=1` | 隐藏球网格 |
| `BALLANCE_MC_STEVE_DEBUG=1` | 每秒日志：位置、球候选、谁在动 |
| `BALLANCE_MC_STEVE_LOOK=0` | 关掉鼠标视角（相机不再吃鼠标位移） |
| `BALLANCE_MC_STEVE_MOUSE=0` | 不进相对鼠标模式（指针仍可见，当对照） |
| `BALLANCE_MC_STEVE_MENU=0` | 关掉「菜单开着就释放指针」的闸门 |
| `BALLANCE_MC_STEVE_MENU_TEST=<file>` | 测试钩子：文件里写 0/1，等价于游戏自己调 `ShowCursor(FALSE/TRUE)` |
| `BALLANCE_MC_STEVE_BODY=0` | 身体不跟滚动方向，退回「身体 = 头的 yaw」 |
| `BALLANCE_MC_STEVE_TURN=deg` | 身体最大转向速度，度/秒，默认 720 |
| `BALLANCE_MC_STEVE_LEVEL=0/1` | 强制「菜单」/「关卡中」；默认按当前相机名判断（带 InGame 算关卡、带 Menu 算菜单）。截图脚本跑的是合并关卡、没有游戏相机，所以都显式写 1 |

## 复现

```bash
tools/poc1/build-ballanced.sh            # 需要 cmake/ninja 在 PATH 上（~/.local/bin）
tools/steve/render-steve-shots.sh        # 四帧：off / hero / sunk / fp  -> /tmp/steve-shots
tools/steve/render-ball-shots.sh         # 两帧：球上第三人称 / 第一人称    -> /tmp/ball-shots
tools/steve/look_shots.sh                # 鼠标视角：五遍同机位，逐像素比
tools/steve/menu_shots.sh                # 菜单闸门：Esc 释放、方向键取回
tools/steve/body_shots.sh                # 身体朝向：真按键让球滚，另有 BODY=0 回退
tools/panel/panel-input-test.sh          # 设置面板：真指针点七下 + F1/Esc 开合，逐条回读日志与 ini
```

Xvfb 需要在 `:99` 上跑（`Xvfb :99 -screen 0 1600x1000x24`）。

## 验收（全部实测通过）

`render-steve-shots.sh`（parked 相机；脚底 y=16.19 是从 `nmo_geom` 导出的三角形里对该点插值出来的，不是猜的）：

```
hero: 14136 像素与对照不同，bbox x[263..380] y[58..300]
调色板像素：shirt=3809, skin=3034, pants=1075
各部分平均行：shirt=153.9, skin=106.5, pants=262.1（头在上、裤在下，没倒立）
sunk: 0 像素与对照不同（沉到台面下 60 单位，台面自己的深度缓冲把他藏起来了）
first person: 216084 像素变化（相机确实进了他脑子里）
upright: yes, 轮廓高宽比 2.06（模型本身是 2.0）
PASS
```

`render-ball-shots.sh`（关卡真跑起来、球在滚）：

```
third person: 4965 shirt-teal 像素（没有 Steve 的帧实测为 0），轮廓 x[272..352] y[261..412]
  裁切区内 shirt=4965 / skin=4397 / pants=3799（头、身、腿都在）
first person: 两帧相隔 300 次渲染调用，17452 像素不同（眼睛跟着球跑）
  Steve 像素 0（相机在他头里，看不到自己）
PASS
```

为什么用「衬衫青」当标记：皮肤棕在本关里到处都是（没有 Steve 的帧就有 62519 像素落在那个色相上），
青绿是干净的（没有 Steve 的帧 = 0）。

## 截图

`docs/steve-shots/`：

- `00-control.png` Steve 关掉的对照
- `01-steve-on-track.png` Steve 站在关卡台面上
- `02-steve-zoom.png` 4 倍放大
- `03-steve-occluded.png` 沉到台面下（与对照逐像素相同）
- `04-first-person.png` 第一人称
- `10-steve-is-the-ball.png` 关卡在跑，Steve 就是那颗球
- `11-first-person-from-the-ball-0.png` / `-1.png` 从球上看到的第一人称，两帧
- `12-steve-zoom.png` 跟拍放大

## 鼠标视角

三步：`GetMouseRelativePosition()` 拿本帧位移 → `m_Yaw -= dx * 0.0025`、`m_Pitch -= dy * 0.0025` → `SDL_SetWindowRelativeMouseMode(window, true)`（`GamePlayer::ApplyRelativeMouseMode()`，每帧只在「想要的模式」变化时才真调 SDL，免得刷日志）。

pitch 夹 ±85°（翻不过头顶），yaw 绕世界 Y 折到 ±180°（免得跑一小时浮点漂）。
灵敏度 0.0025 rad/px = 0.143°/px：200 px 横扫 = 28.6°，60 px 竖推 = 8.6°。

用相对模式而不是 `SetWindowMouseGrab()`：SDL3 一次调用同时给出「相对位移」和「指针不出窗」；grab 只挡住指针出去，位移还得自己 warp 前后相减。

验收 `tools/steve/look_shots.sh`（同一机位跑五遍，关卡静止所以可以逐像素比）：

```
pose in yawonly   (-28.6, 0.0)   camera dir (-0.479, +0.000, +0.878)  与角度算出来的一致
pose in pitchonly (0.0, 8.6)     camera dir (+0.000, +0.149, +0.989)
pose in turned    (-28.6, 8.6)   camera dir (-0.474, +0.149, +0.868)
yaw alone changes the frame      151065 px
pitch alone changes the frame    167509 px
same input, same frame           0 px
```

最后一行是关键：同样的输入再跑一遍逐像素相同，所以上面那些像素差是「角度变了」，不是每帧噪声。

## 菜单开着就把鼠标还回去

游戏自己的暂停菜单一开，指针必须回到玩家手里，否则 Resume 点不动。三个候选信号实测（`BALLANCE_MC_STEVE_DEBUG=1` 每秒打在 `state:` 行上）：

| 信号 | 实测 |
|---|---|
| `CKContext::IsPlaying()` | 一直 TRUE，只在切全屏时 Pause——菜单跟它无关 |
| `CKInputManager::GetCursorVisibility()` | 被端口钉死 TRUE，永远不动 |
| Esc | 同窗口截图前后差 31978 像素，再按一次复原——真的开菜单 |

`SdlInputManager::ShowCursor()` 在本端口被 `KeepSystemCursorVisible()` 强行改回 TRUE：游戏自绘的光标精灵没移植，OS 指针得一直在，菜单才点得动（`BALLANCE_SPRITE_CURSOR=1` 恢复原行为）。所以「游戏想显示光标」这个最正确的信号在这里拿不到，闸门改由 Esc 驱动：

- Esc 按一下释放，再按一下取回；
- 释放期间按 空格/上下左右 任一 → 立刻取回（覆盖「点 Resume 退出暂停菜单」这条路）；
- 取回后那一帧丢掉位移（`m_SkipLookFrames`）：进相对模式会把指针 warp 到窗口中心，那一跳不是玩家甩的；
- `cursor` 标志只在**见过一次 FALSE** 之后才算数（`m_CursorArmed`），否则「没人动过」会被读成「菜单开着」，整局都不抓鼠标。

**坑：`xdotool key X` 的按下和抬起常常落在同一帧。** 逐帧 `IsKeyDown()` 看不见这次按键——SDL 把两个事件都塞进同一帧，`KS_PRESSED` 和 `KS_RELEASED` 同时置位，`PostProcess` 随即清掉；真人快按也是同一个现象。修法不是让测试慢下来，而是引擎侧读本帧的**按键缓冲**：`KeyTappedThisFrame()` 走 `GetNumberOfKeyInBuffer()` / `GetKeyFromBuffer()`，两者都是只读、不会把游戏自己要读的键（EditString、KeyWaiter 读同一份缓冲）吃掉。

验收 `tools/steve/menu_shots.sh`（同一份 200 px 输入打三次，中间真按 Esc 和 Up）：

```
escape released the pointer                    keys [(escape, released), (movement, captured)]
a movement key captured it again               2
pointer released then recaptured               on -> off -> on
one release and one recapture, no flicker      2 transition(s)
burst 1 turns the head by -28.6 deg            -28.6
burst 2 while released turns it by 0.0 deg     0.0
burst 3 turns the head again by -28.6 deg      -28.7
the released burst really reached the window   200.0
PASS
```

burst 3 是「机制还活着」的对照（不然 0° 也可能是指针彻底失效）。第 8 项防的是「0° 是因为输入根本没送到窗口」：菜单开着时 `pointer.txt` 里那颗 OS 指针实打实走了 200 px、出了窗口，说明这 200 px 送到了、是闸门故意不理它。

## 身体朝向

头跟鼠标走，身体跟滚动方向走：`want = atan2(dx, dz)`（球这一秒在 XZ 平面上往哪挪），身体每秒最多转 `BALLANCE_MC_STEVE_TURN`（默认 720°/s——掉下台面被拉回原位时是转过去而不是瞬间翻转），速度低于 0.4 单位/秒就不改朝向：站着不动没有「前进方向」。

固定机位（`BALLANCE_MC_STEVE_POS`）或 `BALLANCE_MC_STEVE_BODY=0` 时退回「身体 = 头的 yaw」，所有旧截图脚本跑的就是这条路径。

验收 `tools/steve/body_shots.sh`（不设 POS，真按方向键让球滚，两段：跟球 / 关掉）：

```
the ball really rolled                          42 moving sample(s), max speed 31.6
its heading really changed                      spread 180 deg
body yaw is the heading it was asked for        worst 0.0 deg over 42 sample(s)
want matches the path the ball actually took    median 8.1 deg over 42 chord(s)
body ignores the turned head                    18 of 21 sample(s) >= 20 deg away
following off keeps the body on the head's yaw  22 chord(s), heading spread 180 deg
PASS
```

- 第 4 项是**独立复算**：want 不是照抄引擎的字段，而是拿每秒日志里的位置算弦方向再比。一秒的弦会切掉弯道的角，所以标准是「中位数远低于随机配对的 90°」，不是精确相等（实测 8.1°）。
- 第 5 项是身体与头分家的证据：鼠标把头顶到 -28.6° 之后就不再动，球还在滚，身体仍然指着路——21 个采样里 18 个离头 ≥ 20°。
- 第 6 项守回退路径：`BODY=0` 时身体每帧都等于头的 yaw（头是 0），球照样在走（22 条弦，方向跨度 180°）。

符号在同一套约定里：相机/头的 forward 是 `(sin(yaw), 0, cos(yaw))`（`look_shots` 里 yaw -28.6° 实测 (-0.479, +0.000, +0.878) 正是这个式子），身体 `SetOrientation` 用同一个式子，而 `want = atan2(dx, dz)` 恰好满足 `(sin(want), cos(want)) = (dx, dz) / |(dx, dz)|`——所以身体正面朝运动方向，不是反的。

## 从菜单进关卡 / 换关卡（2026-11-05 修）

在这之前：菜单里点 Start、点第 1 关，**当场段错误**，关卡永远是 `Cam_MenuLevel`（用户报的「进 level1 卡关进不去」）。原因不是菜单点击——`pick2d` 一路记到 `M_Main_But_1` / `M_Start_But_01` / `M_Start_But_03`，点击一直有效——而是**关卡一载入就把 Steve 追的东西全删了**：

- 菜单里跟着的「球」是 `I_Ball_Stone`，它是那张地图的一部分，不是玩家；
- `SteveAvatar` 加在**菜单那一幕的 scene** 里，关卡载入后整个 scene 连同物体一起没了；
- Steve 手里还攥着这两根指针，下一帧 `GetPosition()` 就是读已释放内存。

修法是一个每帧的 `Validate()`（`MinecraftSteve.cpp`）：

| 检查 | 处理 |
|---|---|
| `context->GetObject(m_BallId) != m_Ball` | 清空、重找（`m_HasLastBallPosition = false`） |
| 同上，`m_Entity` | `Shutdown()` 后下一帧重建（mesh/material/texture 是 Steve 自己的，不随关卡走） |
| 同上，`m_Camera` | 清空 |
| 当前 scene 不等于建它时的 scene | `AddObject()` 重新挂进新关卡那一幕 |

**只比指针不安全**：对象释放后指针还指着那块内存，先解引用就已经是崩溃本身。所以每个指针旁边存了 `CK_ID`，判据是 `context->GetObject(id) == ptr`——拿 id 去问上下文，永远不碰旧指针。

### 顺手拆出来的两件事

- **菜单里不再画 Steve**。`UpdateLevelState()` 按**当前相机名**判断：名字带 `InGame` 算关卡中，带 `Menu` 算菜单；没见过的名字算「关卡中」，免得别的关卡相机换了名字 Steve 就消失。菜单里隐藏模型、也不抓鼠标（第一人称最要紧：菜单抓了指针就点不动按钮）。
- **`BALLANCE_MC_STEVE_LEVEL=0/1`** 覆盖这个判断。截图/验收脚本跑的是「合并关卡 + 无游戏相机」，必须写 1，否则一律被当成菜单。

顺带把每秒刷屏的 `ball ... is the player` 改成只在换球时打一次。

### 验收

关卡内 A/B（同一个球位、Steve 开/关各截一帧后相减）：

    differing pixels: 6261
    bbox x[194..456] y[111..348]  size 263x238
    shirt  614 px  mean row 235
    skin   1467 px  mean row 227
    pants   181 px  mean row 250

皮肤在衬衫之上、裤子之下，数量级也对得上——画出来的确实是那个模型，不是别的东西。崩溃计数 0，日志里 `level state: menu` 与 `level state: in a level` 各一次，恰好对应两次点击。

三套旧回归全过：`look_shots.sh` 10 项、`menu_shots.sh` 8 项、`body_shots.sh` 6 项。

## 设置面板（F1，2026-11-05）

八个开关不再只能靠环境变量：F1 呼出面板，点一下就地生效并写进 `Bin/Minecraft.ini`
（与 `Player.ini` 并排，面板永远不碰引擎自己的 ini）。环境变量仍然优先于 ini，
被压住的行在面板上标橙色 `[env]`——截图脚本因此行为不变。

面板打开时把世界冻住的方式是 **时间缩放置 0**，不是 `CKContext::Pause()`：后者会让
`CKContext::Process()` 整段跳过 `ExecuteManagersPreProcess()`，而 SDL 输入管理器正是
一个 PreProcess 管理器——暂停的上下文连鼠标按键都进不来，面板能画但点不动、F1 也关不掉。
细节、测试钩子和实测输出见 `docs/settings-panel.md`。
