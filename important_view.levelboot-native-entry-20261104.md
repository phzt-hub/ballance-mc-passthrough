# 线一 levelboot：原生进关卡已打通（菜单点击 → Level_01 → InGameCam → 球能动）

时间：2026-11-04 18:2x｜用户本轮选「1」（BALLANCE_POC_START_LEVEL=all），并提示「Ballance 的菜单就是一个不断旋转视角的地图」。
本轮改了 Ballanced 源码（补丁）+ 重建 + 实测，**未提交 git**。

## 一句话（推翻前几轮方向）
**进关卡根本不用碰脚本。** 前几轮的「激活 Start 输入」「并入 Gameplay.nmo」「钉相机」都是绕路。
真正的门是**菜单的鼠标点击**；点击失效的根因是 **SDL 端口把光标可见性关掉了**：
TT PushButton2 的判定是 `if (target == picked && im->GetCursorVisibility()) state |= 1;`，
Ballanced 的 SdlInputManager 在 MouseCursor 行为进入 ON 时执行 `ShowCursor(FALSE)`（想自己画精灵光标，
但那个精灵在 SDL 后端没画出来），于是 **GetCursorVisibility()==0 → 按钮永不响应**。
补 `ShowCursor(TRUE)` 后：点 Start → 关卡选择 → 点第 1 关 → **Level_01 原生载入，相机切 InGameCam，球能开**。

## 决定性实测

### 1. BALLANCE_POC_START_LEVEL=all 无效（用户选的 1）
63/63 脚本全部 fired，但 3d 258→303（chain 是 569），相机始终 Cam_MenuLevel，eye 继续沿菜单路径飞。
→ 「全激活」不是钥匙；手工激活 Loading_Manager 也不会把关卡带起来。

### 2. 输入诊断（新探针 Poc1InputLog）
BALLANCE_POC_INPUTLOG=15 每 15 次 Render 打印 rel/abs/left/clicked/vis/pick2d：
- xdotool 移动后 rel 从 (306,96) 变 (320,94) → 指针确实进游戏；
- pick2d=M_Main_But_1 → Pick2D 命中按钮；
- 点击那帧 left=1 → 按钮事件确实收到；
- 但 vis=0 → GetCursorVisibility()==0，正是 PushButton2 的否决条件。

### 3. 光标补丁后整条原生链路通了
BALLANCE_POC_CURSOR=1（每帧 im->ShowCursor(TRUE)）→
- 点 (320,94)（M_Main_But_1）→ 关卡选择界面出现：M_Start_But_01..13 + Back 变 visible，pick2d 变 M_Start_But_03；
- 点 (320,38)（M_Start_But_01）→ **Level_01 载入**：3d 130→349/350（visible 146），HUD Interface_Life_*／Tutorial_Interface 出现；
- **相机 = InGameCam**，eye=(32.10 52.66 -153.07)（关卡起点）；
- 日志零 error；
- 之后 xdotool 连按 Up，第 2250 帧 eye 开始动（32.07 52.18 -152.91 → 32.12 51.67 -153.19）
  → **物理 + 跟随相机 + 键盘输入全部活着**。

## 写死的结论
1. --level 那条「手工合并 NMO」的路不再需要：原版加载链自己会载 Levelinit/Gameplay/Camera/关卡。
2. 关卡不带 scene、CK2 没有字节码 VM——都不重要，行为图原生跑得动。
3. 「相机沿菜单路径飞」= 菜单本身（用户提示），不是 bug。
4. 唯一 bug 是 SDL 端口的光标可见性：ShowCursor(FALSE) 之后没有地方再打开。

## 落盘文件
| 文件 | 作用 |
|---|---|
| tools/levelboot/poc2d.cpp | 新：dump 全部 2D 实体（名字/可见/像素矩形/文本），定位菜单按钮 |
| tools/levelboot/pocframelog.cpp | 改：新增 Poc1InputLog()（鼠标/光标/Pick2D）、Poc1ForceCursor()（BALLANCE_POC_CURSOR） |
| tools/levelboot/apply-levelboot-hook.py | 改：拼接 poc2d.cpp；Render() 加 Poc1ForceCursor / Poc1InputLog / PocDump2DAuto |
| /tmp/one-click.sh 等 | xdotool 点击/开球脚本 |
| /tmp/cur1.log /tmp/play1.log /tmp/drive1.log | 三轮实测日志；/tmp/poc1/<tag>/ 有颜色与深度帧 |

## 新开关
| 开关 | 作用 |
|---|---|
| BALLANCE_POC_CURSOR=1 | 每帧强制 im->ShowCursor(TRUE)（本轮钥匙） |
| BALLANCE_POC_INPUTLOG=n | 每 n 帧打印 rel/abs/left/clicked/vis/pick2d |
| BALLANCE_POC_DUMP2D=1 + _FRAME=200,500,800 | 按帧号列表 dump 2D 实体（默认只 dump 可见，_ALL=1 含隐藏） |

## 复现
1) 复原 + 打补丁：cd ~/src/Ballanced/Source/Player && git checkout -- src/GamePlayer.cpp src/GamePlayer.h src/Player.cpp src/GameConfig.h && python3 ~/src/ballance-mc-passthrough/tools/levelboot/apply-levelboot-hook.py
2) 重建：export PATH="$HOME/.local/bin:$PATH"; cd ~/src/Ballanced && cmake --build --preset linux-x64-runtime-release --target stage
3) 开跑（不需要 --level）：bash /tmp/play-level.sh play1 45  （内含两次 xdotool 点击 (320,94) → (320,38)）

菜单按钮像素坐标（640x480）：主菜单 M_Main_But_1..5 = x 224..416，y 72/144/216/288/360，高 45；
关卡选择 M_Start_But_01..13 = x 258..382，y 从 25.7 起每格 28.8（01=25.7, 02=54.5, …）。

## 下一步（按性价比）
1. **修到源头**：让 SdlInputManager / MouseCursor 在隐藏系统光标时仍报告可见（或让精灵光标真的画出来），去掉 BALLANCE_POC_CURSOR 也能玩；顺带查 MouseCursorRender 的 AddPostSpriteRenderCallBack 是否没实现。
2. 查 scene=(none)：全程 GetCurrentScene()==NULL，可能影响依赖 CurrentScene 的行为图（切换/暂停）。
3. 球/物理验收：球是否真在轨道上滚、碰撞是否正常（本轮只证明相机跟着动）。
4. --level / START_LEVEL / ISOLATE / CAM 保留为调试工具，不再是主路径。

## 边界
- 未提交任何 git；Ballanced 子模块仍是脏工作区（补丁状态）。
- 未动 128、未动新作17、未动 dsh-backup.sh。
- ask_user_question 在本环境挂死，不要用。
