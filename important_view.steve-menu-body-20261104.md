# Steve 菜单释放 + 身体朝向 —— 本轮结论（2026-11-04 21:35，全部验收通过）

三件事都做完了，每件都有可复现脚本和数字。

| 项 | 落点 | 验收 |
|---|---|---|
| 菜单开着就把指针还给玩家 | MinecraftSteve.cpp 的 UpdateMenuState() + KeyTappedThisFrame() | tools/steve/menu_shots.sh → 8 项 PASS |
| 身体朝向跟滚动方向 | MinecraftSteve.cpp 的 UpdateBodyYaw() | tools/steve/body_shots.sh → 6 项 PASS |
| 文档 | docs/steve-in-ballance.md 新增「鼠标视角」「菜单开着就把鼠标还回去」「身体朝向」三节 | — |
| 补丁重新导出 | tools/ballanced-patches/Player.patch（2270 行 / 7 文件 / base e5073295） | 在 base 的干净 worktree 上 apply 后与原工作区 diff -r 完全一致 |

## 菜单闸门

    menuUp = m_MenuKeyUp（Esc 边沿切换）
          || !context->IsPlaying()
          || (m_CursorArmed && GetCursorVisibility())

- m_CursorArmed：只有先见过 cursor=FALSE 才相信那个标志，否则「没人动过」会被读成「菜单开着」，整局都不抓鼠标。
- 释放后再按 空格/四方向 任一 → 立刻重新捕获（覆盖「点 Resume 退出暂停菜单、没再按 Esc」这条路）。
- 切回捕获后丢 1 帧位移（m_SkipLookFrames）：SDL 进相对模式会把指针 warp 到窗口中心，那一跳不是玩家甩的。

三个候选信号的实测结论（顺序就是可信度）：

| 信号 | 实测 | 能不能用 |
|---|---|---|
| SdlInputManager::GetCursorVisibility() | 被端口钉死 TRUE | 不能（KeepSystemCursorVisible()，游戏自绘光标没移植；BALLANCE_SPRITE_CURSOR=1 可恢复） |
| CKContext::IsPlaying() | 一直 TRUE，只在切全屏时 Pause | 不能 |
| Esc 键 | 同窗口截图前后差 31978 px，再按一次复原 | 用它 |

### 头号坑：同一帧内的按下+抬起

xdotool key X（真人快按也一样）会把 KEY_DOWN 和 KEY_UP 塞进同一帧：KS_PRESSED 与 KS_RELEASED 同时置位，PostProcess 随即清掉，逐帧 IsKeyDown() 从头到尾看不见这次按键。

修法放在引擎侧而不是让测试慢下来：KeyTappedThisFrame() 扫本帧的按键缓冲（GetNumberOfKeyInBuffer() / GetKeyFromBuffer(i, key)）。两者都是只读、不 pop，所以不会把游戏自己（EditString、KeyWaiter 读同一份缓冲）要的键吃掉。

## 验收 menu_shots.sh（8 项 PASS，两次跑数字一致）

    escape released the pointer                    keys [(escape, released), (movement, captured)]
    a movement key captured it again               2
    pointer released then recaptured               on -> off -> on
    one release and one recapture, no flicker      2 transition(s)
    burst 1 turns the head by -28.6 deg            -28.6
    burst 2 while released turns it by 0.0 deg     0.0
    burst 3 turns the head again by -28.6 deg      -28.7
    the released burst really reached the window   200.0
    PASS

同一份 200 px 输入打三次，中间真按 Esc 和 Up。两条对照缺一不可：

- burst 3：机制是活过来了，不是死了（不然 0° 也可能是指针彻底失效）。
- pointer.txt：菜单开着时那颗 OS 指针实打实走了 200 px 出了窗口 —— 说明这 200 px 送到了窗口，是闸门故意不理它，不是输入没到。

顺手纠正一个上手时的错判：上一轮写的「deltas still arrived while released」这条判据本身错了 —— 相对模式关掉以后根本没有相对位移可送（实测那段时间 look 行全是 d(+0, +0)），拿它当 PASS 条件永远过不了。

## 身体朝向

- want = atan2(dx, dz)：球每秒在 XZ 平面上实际挪的方向。
- 转向限速 720°/s（BALLANCE_MC_STEVE_TURN 可调），掉下台面被拉回原位时是转过去而不是瞬间翻转。
- 速度 < 0.4 单位/秒 就不改朝向：站着不动没有「前进方向」。
- 固定机位（BALLANCE_MC_STEVE_POS）或 BODY=0 → 退回「身体 = 头的 yaw」，所有旧截图脚本跑的就是这条路径。

### 验收 body_shots.sh（6 项 PASS，两次跑数字一致）

    the ball really rolled                          42 moving sample(s), max speed 31.6
    its heading really changed                      spread 180 deg
    body yaw is the heading it was asked for        worst 0.0 deg over 42 sample(s)
    want matches the path the ball actually took    median 8.1 deg over 42 chord(s)
    body ignores the turned head                    18 of 21 sample(s) >= 20 deg away
    following off keeps the body on the head's yaw  22 chord(s), heading spread 180 deg
    PASS

- 第 4 项是独立复算：want 不照抄引擎字段，而是拿每秒日志里的位置算弦方向再比。一秒的弦会切掉弯道，所以判据是「中位数远低于随机配对的 90°」（实测 8.1°），不是精确相等。
- 第 5 项是身体与头分家的证据：鼠标把头顶到 -28.6° 后就不再动，球还在滚，身体仍指着路。
- 第 6 项守回退路径：BODY=0 时身体每帧等于头的 yaw（头是 0），球照样在走（22 条弦、方向跨度 180°）。

符号一致性已核对：相机/头的 forward = (sin(yaw), 0, cos(yaw))（look_shots 里 yaw -28.6° 实测 (-0.479, +0.000, +0.878)），身体 SetOrientation 用同一式子，而 want = atan2(dx, dz) 恰好满足 (sin(want), cos(want)) = (dx, dz)/|(dx, dz)| —— 身体正面朝运动方向，不是反的。（网格正面朝 +Z 这点沿用上一轮近景验收，没重测。）

## 回归（都跑了，都过）

- tools/steve/look_shots.sh：7 项 PASS，pose 与相机向量一字不差；像素差 151065 / 167509（上轮 151066 / 167514，差 1 px 与 5 px，即 0.0007% 与 0.003%；同批次「同样输入 0 px」仍成立，判为动画帧相位抖动，不是回归）。
- tools/steve/render-steve-close.sh：PASS（重复帧 0 px、近景 9849 px / bbox 93x190、palette 与 upright 不变、parked sunk 0 px、fp 158261 px）。

## 补丁

    cd ~/src/Ballanced
    git -C Source/Player add -N . && git -C Source/Player diff > /tmp/Player.patch && git -C Source/Player reset -q

导出后做了真验证：在 base e5073295 的干净 worktree 上 git apply --check 通过、应用后 git status --porcelain 与活工作区相同、diff -r 完全一致（IDENTICAL），然后删掉临时 worktree。README 里的清单数字同步改成 7 files / 2270 lines。

## 还没做（下一轮）

- 提交：Ballanced 工作区仍脏（4 个子模块改动）、passthrough 有 9 个新文件未提交。
- 真人手感：Xvfb 里只能验数值，灵敏度/转向速度/Esc 手感还得真玩一遍。
