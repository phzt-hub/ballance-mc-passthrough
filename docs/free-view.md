# 自由视角（F）：相机绕着球转

2026-11-08。社区那个 `FreeFirstPersonCamera`（[free-camera.md](free-camera.md)）是 BML 的 bmod，
挂在原版 `Player.exe` 上，Ballanced 不认这个包。所以这一次不是把它装进来，而是把它**做出来**：
同一个口径（`360Mode = 0` 的世界 Y 轴偏航、±85° 俯仰），换成**第三人称绕球**。

一句话：**按 F，镜头绕着球转，鼠标左右上下；球该怎么走还怎么走。**

| | 关卡镜头 | 自由视角 |
|---|---|---|
| 位置 | 关卡脚本安排的机位（第 8 关离球 41.6 u） | 同样距离，绕着球转 |
| 朝向 | 脚本 | 鼠标，世界 Y 偏航 + 俯仰 |
| 球的行进 | 方向键 | **一模一样**（实测同一段路 0.0° 差） |
| 镜头归属 | `InGameCam` | `MpFreeViewCamera`（临时建的 CKCamera） |

## 一、怎么用

1. 进关卡，按 **F** —— 画面**不动**（起始姿态直接取自当前关卡镜头）。
2. 鼠标左右 = 绕球转，上下 = 抬高/放低。
3. 再按 **F**，镜头交还关卡。

鼠标什么时候不属于自由视角：**F1 面板开着**、**MC 物品栏开着**、**按了 Esc（把它当菜单）**。
这三种情况指针要还给别人，视角也不再跟着走；按任意方向键（或空格）把指针拿回来。

灵敏度就是面板第 6 行 `Sensitivity`（默认 0.0025 弧度/像素，628 像素 = 90°），改面板即生效。

## 二、验收：9 项全过

```sh
./tools/freeview/freeview-shots.sh          # 两次运行，约 2 分半
```

跑第 8 关两遍：头一遍只按住 Up；第二遍按 F、右转 90°、上抬 17°、再按 F 交还，然后按住 Up 同样久。
每一帧的姿态都是从**共享内存里的相机通道**读的（也就是 MC 那边真正收到的那组数），不是日志。

```
PASS  toggle logged                      1 on / 1 off
PASS  F does not move the picture        eye moved 0.233 u, yaw 0.033 deg over the first second
PASS  yaw turns 90 deg                   90.1 -> 179.0
PASS  pitch stays put on a yaw turn      -58.0 -> -57.9
PASS  orbit radius holds                 41.61 -> 41.51 u
PASS  ball does not move while the view turns 0.120 u
PASS  pitch turns alone                  pitch -57.9 -> -40.8, yaw 179.0 -> 179.0
PASS  F off restores the level camera    yaw 89.9 (level 90.1)
PASS  ball rolls the same way            control (42.5, 0.0) vs orbit (42.2, -0.0), heading diff 0.0 deg
```

最后一行是这件事的意义：**转视角不等于改玩法**。两次运行里球都是往 +X 滚 42 u，
视角在第二次运行里已经转了 90°，球的路一点没变。

![按 F 之前：关卡镜头](freeview-shots/01-level.png)

![转 90° 之后：同一颗球，世界转过来了](freeview-shots/03-yaw90.png)

![再上抬 17°：从更高处俯看](freeview-shots/04-pitch.png)

## 三、实现

都在 `Source/Player/src/MinecraftCameraSync.cpp`（相机通道本来就独占"这一帧从哪看"，自由视角也归它）：

| 位置 | 做什么 |
|---|---|
| `Frame()` 开头 | 先 `FindBall()`、再 `UpdateFreeView()`，**然后**才读附着的相机发布姿态 |
| `UpdateFreeView()` | F 开关、Esc/方向键的指针闸门、鼠标转向、球没了就等 |
| `EnterFreeView()` | 建 `MpFreeViewCamera`，把**当前关卡镜头相对球**的姿态换算成绕球角度 → 所以按 F 不跳 |
| `ApplyFreeViewCamera()` | 每帧 `eye = ball - forward * distance`、`LookAt(ball)`，FOV/近远面**抄关卡镜头** |
| `LeaveFreeView()` | 把关卡镜头重新 attach，并把 view/projection 矩阵一起写回去 |
| `WantsRelativeMouse()` | 指针要被捕获（相对模式），否则它撞到窗口边就不再产生增量 |

`GamePlayer::Render()` 里只加了两处：把面板开着告诉相机通道（`SetPanelOpen`），
以及指针捕获改成"Steve 或自由视角谁要谁说了算"。

为什么是**第三人称**、而且只动相机：镜头不是玩法状态。关卡脚本不认识这颗相机，
所以它不会触发相机区、不会开菜单，也**不会把球的施力方向改掉**——最后这条是量出来的，见上表最后一行。

## 四、四个坑

1. **光标标志在这里恒为真。** `SdlInputManager::ShowCursor()` 为了菜单可点，把光标标志钉成 TRUE，
   所以它区分不了"菜单"和"在玩"（`MinecraftSteve.cpp` 早就记着这条）。菜单信号只能用
   **Esc 按下的边沿**：Esc 当"菜单开着 → 交出指针"，任意方向键当"回到游戏 → 收回指针"。
   MC 物品栏开着时的那个 Esc 不算——它是用来关物品栏的，算进去就会在关掉物品栏后没有指针。
2. **两个 Player 抢同一块共享内存。** `run-poc1.sh` 把游戏放在 `timeout(1)` 底下跑，
   杀掉脚本不会杀掉游戏；`pkill -f Bin/Player` 也打不中（进程命令行是 `./Player`）。
   两个进程同时发布时，采样会在两组姿态之间来回跳——排查时看到"球在 36 和 86 之间闪"就是这个。
   harness 里改成 `pkill -x Player`。
3. **掉球会把球对象删掉重建。** 立刻退出自由视角的话，每次掉球都得重新按一次 F。
   现在宽限 2 秒（`kBallMissingGrace`）：重生接得上，关卡真没了才交还镜头。
4. **菜单镜头要排除。** 菜单里那颗"球"是摆在原点的装饰件，绕着它转会把关卡选择菜单的镜头锁死，
   所以看到 `Cam_MenuLevel` 就立刻交还。

## 五、没做的

- **第一人称**。社区的 mod 是第一人称；这次按用户 2026-11-08 的选择做第三人称绕球。
- 距离、俯仰范围都不开放：距离取按下 F 那一刻关卡镜头的距离（第 8 关 41.6 u，第 1 关 41.3 u），俯仰 ±85°。
- 键位写死 F。**F 目前没有冲突**：透传只转发 E 和 Esc（[m1b-input.md](m1b-input.md)），F 到不了 MC，
  所以 [free-camera.md](free-camera.md) 里"F 已被 MC 占用"那条对当前这版不成立。
