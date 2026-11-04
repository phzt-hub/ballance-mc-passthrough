# Steve 接鼠标转视角 —— 本轮结论（2026-11-04，session b8592f3e）

## 做了什么（3 → 1 → 2 全部完成）

| 步 | 内容 | 落点 |
|---|---|---|
| 3 | 先在 Xvfb 下用 xdotool 注入鼠标、证明读取路径 | `tools/steve/probe-look.sh` + `look_check.py` |
| 1 | `m_Pitch` + 每帧读 `GetMouseRelativePosition()` | `Source/Player/src/MinecraftSteve.{h,cpp}` |
| 2 | 真正的相对鼠标模式（指针被捕获） | `GamePlayer.cpp` 的 `ApplyRelativeMouseMode()` |

## 验收（全部 PASS，数字可复现）

```
PASS pose in yawonly   (-28.6, 0.0)    yaw -28.6 pitch 0.0
PASS camera follows yawonly            (-0.479, +0.000, +0.878)
PASS camera follows pitchonly          (+0.000, +0.149, +0.989)
PASS camera follows turned             (-0.474, +0.149, +0.868)
PASS yaw alone changes the frame        151066 px
PASS pitch alone changes the frame      167514 px
PASS same input, same frame                   0 px
```
灵敏度 0.0025 rad/px：200 px 横 = **-28.6°** yaw，60 px 竖 = **+8.6°** pitch，俯仰夹在 **±85°**（社区 360Mode=0 的默认值）。

## 三个实测发现的坑（都写进代码注释了）

1. **m_InputWindow 恒为 0**：Player 从不调 `SetInputViewport`，输入管理器没有窗口句柄，所以 `SDL_SetWindowRelativeMouseMode` 只能放在 `CGamePlayer`（它拥有 `m_SdlWindow`）。`BALLANCE_INPUT_DEBUG=1` 会打印 `win=2 input=0`。
2. **未捕获指针会先吐一个"追上来的"假位移**：SDL 用陈旧指针位置算出第一个 xrel，表现为**反向**的一次性转角（实测 -200/+60）。捕获指针后这条消失——这也是「必须做第 2 步」的直接证据，不只是「能转多大范围」的问题。
3. **xdotool 坐标别猜**：游戏窗口是 **640x480 @+480+260**，`mousemove --window W 800 500` 落在窗口外，SDL 一个 motion 事件都不给，看起来就像「鼠标没被读到」。必须先 `getwindowgeometry --shell` 量出来再动。
4. **28.6° 转角不能当成 261 px 平移来验**：实测最佳平移匹配是 0 px——眼点贴在台面上，大角度 yaw 是近景的透视扭曲，不是平移。所以 yaw 改用**相机自身 forward 向量**验（见上表），平移判据只留给小角度的 pitch（-68 px vs 预测 -74 px）。

## 回归

**2026-11-04 21:35 复跑（菜单闸门 + 身体朝向那一轮之后）**：7 项仍全 PASS，pose 与相机向量一字不差；像素差 151065 / 167509，上轮 151066 / 167514 —— 差 1 px 与 5 px（0.0007%、0.003%），同批次「同样输入 0 px」仍成立，判为动画帧相位抖动，不是回归。


`tools/steve/render-steve-close.sh` 重跑：重复帧 0 像素差、近景 9849 px / bbox 93x190、palette 与 upright 不变、parked sunk 0 像素差、fp 158261 px（对上轮 158262，差 1 px ≈ 0.0006%，非确定性检查仍是 0，属跨构建浮点差）。

## 新增环境变量

| 变量 | 作用 |
|---|---|
| `BALLANCE_MC_STEVE_LOOK=0` | 不读鼠标（默认开） |
| `BALLANCE_MC_STEVE_SENS=f` | rad/px，默认 0.0025 |
| `BALLANCE_MC_STEVE_MOUSE=0` | 不捕获指针（仍读位移） |
| `BALLANCE_INPUT_DEBUG=1` | 逐条打印指针事件（在窗口/暂停过滤之前，区分"没到"与"被丢"） |

## 还没做

- 菜单/暂停时释放指针 已经完成（Esc 闸门 + 同帧按键缓冲），见 `important_view.steve-menu-body-20261104.md`。
- Steve 身体朝向跟速度 已经完成（body yaw 跟滚动方向，限速 720°/s），同上。
- 补丁已重新导出（Player.patch，2270 行，含干净 worktree 的 apply 验证）。
- Ballanced 工作区仍是脏的（未提交）；passthrough 侧的新脚本也未提交。
