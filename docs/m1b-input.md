# M1b：用 Ballance 的鼠标键盘开 Minecraft 的物品栏

目标：在 Ballance 窗口里按 **E**，弹出 MC 的创造模式物品栏，能用鼠标点物品、换页签、滚列表，
松手时球停住不动 —— 全部通过相机通道，MC 窗口一直收不到任何输入事件。

## 一、链路

| 段 | 在哪 | 做什么 |
|---|---|---|
| 采集 | `Source/Player/src/MinecraftOverlay.cpp` | 每帧读 CK 的鼠标状态与 E/Esc 计数，写进相机通道 payload |
| 传输 | `ballance_mc_camera` 共享内存 | 与 M1 相机同一个 seqlock 块，只是多用了几个字段 |
| 还原 | `mc-mod/.../PassthroughInput.java` | 每 client tick 读一次，翻译成 MC 自己的输入调用 |
| 回写 | 同一块的 status 区 | MC 告诉 Ballance「物品栏开着」→ Ballance 交出指针、冻结关卡 |

payload 里的输入字段（`MpCamera.h` / `tools/passthrough/mpcamera.h` / `McCameraChannel.java` 三处镜像）：

| 字段 | 含义 |
|---|---|
| `MouseX/MouseY` | 指针在 Ballance 窗口里的位置，0..1，原点左上 |
| `Buttons` | 位掩码，`0x1` 左键 `0x2` 右键 |
| `Wheel` | 滚轮累计刻度，**120 = 一格**（Windows 单位） |
| `KeyE / KeyEsc` | 按下计数（单调递增，重启后归零 → 视为「不是一次按键」） |
| `InventoryOpen`（回写） | MC 侧物品栏是否还开着 |

## 二、MC 侧怎么"假装"这是一次真输入

三条都走 MC 自己的入口，不直接调业务方法：

| 输入 | 调用 | 为什么不能用更直接的办法 |
|---|---|---|
| E | `KeyMapping.click(KEY_E)` | MC 在自己的 tick 里处理，页签、权限、上次打开的页签都由它决定 |
| 左右键 | `MouseHandler.onButton(window.handle(), info, 1/0)` | 屏幕还会拿到 `afterMouseAction()` 与双击判定，松手会走 `mouseReleased` ——**创造物品栏的页签是在松手时切换的**，只调 `mouseClicked` 永远换不了页 |
| 滚轮 | `MouseHandler.onScroll(window.handle(), 0, notches)` | 与上同理：灵敏度、离散步进由它处理 |
| 指针 | 写 `MouseHandler.xpos/ypos`（`MouseHandlerMixin`） | 悬停高亮与 tooltip 读的是窗口指针位置，不是事件参数 |

坐标换算：`gui = (u * screen.width, v * screen.height)`，窗口位置写 `(u * window.getWidth(), v * window.getHeight())`。
实测两套坐标逐位相等：

```
left press gui=(204.82656759023666,96.00000143051147) screen=427x240 win=854x480 scale=2
           rendererMouse=(204.82656759023666,96.00000143051147)
```

## 三、四个坑（都是"看起来成功"的那种）

### 1. 鼠标键编号在 26.3 变成了 SDL 的 1/2/3

`SDLEventHandler.handleMouseButtonEvent` 直接把 `SDL_MouseButtonEvent.button` 塞进
`MouseButtonInfo`，也就是 **1=左 2=中 3=右**（旧版是 GLFW 的 0/1/2）。
`AbstractContainerScreen.mouseClicked` 只把 **1 和 3** 当容器点击：

```java
if (event.button() != 1 && event.button() != 3 && !cloning) {
   this.checkHotbarMouseClicked(event);   // 其它键 → 只查热键交换，什么都不做
} else {
   ... 真正的点槽 ...
}
```

发 `0` 时点击落进第一个分支，**什么都不干，却仍然 `return true`** ——
第一版就是被这个 `handled=true` 骗了半天。`getContainerClickButton` 里 1→0、3→1 也是同一套编号。

### 2. `Minecraft.screen` 在 26.3 不存在

屏幕在 `client.gui.screen()` / `client.gui.setScreen()`。创造物品栏也不再由
`Minecraft.handleKeybinds` 直接 new，而是 `InventoryScreen.init()` 里转发（无创造权限时自动换成普通背包）。

### 3. `pauseOnLostFocus`：第一 tick 就会弹出暂停屏

Ballance 窗口持有焦点 → MC 一直认为"失焦" → 暂停屏盖住一切，E 也被它吃掉。
mod 入口跑在 `Minecraft` 构造器里，那时 `options` 还是 `null`，只能等第一个 tick 改，
而且**只改内存**（不写玩家的 options.txt）。

### 4. `ItemStack` 不是值类型

`carried.equals(lastCarried)` 只在两边都是 `ItemStack.EMPTY` 时成立（同一个单例），
真拿起来以后每 tick 都"变了"。判断变化必须 `ItemStack.matches(a, b)`，
否则日志会每秒刷二十行同样的内容。

## 四、关卡冻结

打开物品栏 → Ballance 收到 `InventoryOpen=true` → 交出指针、把关卡 `timeScale` 归零并显示系统光标；
关闭时恢复。它和 F1 设置面板共用同一个冻结闸门，闸门是**计数式**的，
两个界面先后打开／关闭不会互相把对方的时间倍率搞错。

## 五、准星前移（AimLift）

球画在 Ballance 窗口正中，MC 的准星也在正中，于是两者重合：放方块会直接怼在球上。
`AimLift`（F1 面板 row 8，默认 20°，0..30 可调）抬的是**玩家的俯仰**——放置射线
从玩家的眼睛出发、沿玩家的旋转走，所以抬高它就是把落点抬起来。相机**不抬**：相机按
Ballance 的视点摆，抬它只会把整个画面转过去，球仍在画面正中。

`HudMixin` 把准星画到那条射线 4.5 格外那个点的投影上，于是准星就是落点本身。

**2026-11-07**：这条投影原来只算了**竖直**分量（画在画面正中往上抬），球一左右漂准星
就脱开——实测在 640x480 的关卡 1 里，球投影到屏幕的最左 289.3、最右 321.4（±32 px），
上下 220.3..253.7。现在 x、y 都从相机投影里来，球怎么漂准星怎么跟，落点也仍然在准星下。

**2026-11-08**：上面那条“跟”只对了一半——准星跟着球**平移**，但跟球的**间距不是定值**。
抬角转的是 20°，而 20° 在屏幕上折多少像素取决于相机离球多远；球一快，Ballance 的镜头跟不上，
球自己甩出画面中线最多 26°，这段差额全部加在间距上。把 2026-11-08 那一局的 73 个位姿样本
（关卡 9、320x240 GUI）逐个重算：

| 量 | 实测 |
|---|---|
| 准星到球的距离 | **39.2 .. 108.1 px**，同一个 20° 抬角（中位 65.6） |
| 相机到球的距离 | 6.66 .. 18.71 格（间距随它反变） |
| 球偏出画面中线（镜头滞后） | 最大 26.08°，此时间距被推到 102、108 px |

所以“准星不跟球”其实是**间距在变**，而镜头滞后那几下最明显（球被甩到一边，准星留在原处）。

改法分两步，都不再是“转一个角度”：

1. **准星画在球自己的投影 + 固定像素**（`CameraSync.gapGui`）。`AimLift` 仍是唯一控件、
   仍是 0..30 度，刻度换成 **3.3 px/度**（20° = 66 px，即 2026-11-07 在 7.5 格相机上量到的值），
   于是同一个设置在任何关卡、任何镜头距离下都是同一个屏幕间距。
2. **反求玩家的旋转**（`CameraSync.aimForCrosshair`），使落点正好落在那个像素上。落点必须
   同时满足两条：在相机过该像素的射线上（准星就画在那儿），且离玩家眼睛正好 4.5 格
   （放置射线从那儿出发）。后者是眼睛周围的一个球面，所以交点就是“射线 ∩ 球面”，一元二次方程，
   取**远根**——远根在球的前面，近根会把方块夹在球和相机之间。

离线复算 73 个样本（同一套公式）：间距 **66.00 px**，上下 0.0；落点高于相机轴的仰角 9.0..15.4°。
代价：镜头特别远的帧（关卡刚进、18.7 格）要让准星停在球上方 66 px，玩家的俯仰会比原来抬一点
（原判据 2.96..54.67° 向下，改后 −19.4..+51.1°，即个别帧变成果断上抬），方块因此落在球上方
一格多而不是球心附近——这是“间距恒定”换来的，`AimLift` 调小就回到球上。

另外，日志里的 `applied pose` 行现在带 `at=(x, y)`（球在屏幕上的位置）、
`gap=66.0px`（目标间距）和 `aim=(yaw, pitch)`（实际给玩家的旋转）：
以后再怀疑“不跟”，把这三个数和准星在截图上的位置对一下即可。

![准星画在算出来的落点上：青色圈是球的投影，品红框是算出的落点，实测准星到框心 1.3 px](m1b-shots/crosshair-projected-2026-11-07.png)

独立的小工具 `tools/passthrough/check-crosshair.py`（读相机通道 + 抓窗口 + 按同一套
投影算出球和落点该在哪，把两个点画在截图上）。静止帧实测：准星量在 (319.1, 135.8)，
算出该在 (320.4, 135.0)，差 **1.5 px**。

## 六、验收

```sh
# 先起 MC 客户端（含 mod），再跑：
tools/passthrough/test-m1b.sh <tag> 12
```

脚本自己起 Ballance、走菜单进第 8 关、按 E、点页签、点物品、点快捷栏、滚轮、右键、再按 E 收场，
九张截图在 `/tmp/m1b/<tag>/`。2026-11-06 这一轮（tag `m1b8`）的实际结果：

| 检查 | 证据 |
|---|---|
| E 打开 | MC `opening the creative inventory (E from Ballance)`；Ballance `minecraft inventory open -- level frozen, pointer handed over` |
| 页签切换 | 截图标题从 `Building Blocks` 变成 `Natural Blocks`（点的是第 3 个页签，窗口坐标 274,72） |
| 点物品 | `carried is now 1 minecraft:red_sand` |
| 放进快捷栏 | `hotbar=[1 minecraft:red_sand, -, -, ...]`（点之前是上一次留下的一块云杉原木，于是发生了交换——原版行为） |
| 滚轮 | 截图里格子内容整体换行（`03b-scroll.png`） |
| 右键 | `right press` / `right release` 都到达屏幕，无异常 |
| 坐标 | `gui` 与 `rendererMouse` 两套值逐位相同 |

坐标是怎么算出来的（640x480 窗口 ↔ 427x240 GUI，x 缩放 640/427、y 缩放 2）：

| 控件 | GUI | 窗口像素 |
|---|---|---|
| 页签 2 | (183, 36) | (274, 72) |
| 物品格 第 2 行第 5 列 | (205, 96) | (307, 192) |
| 快捷栏 0 / 1 | (133, 172) / (151, 172) | (199, 344) / (227, 344) |

## 七、截图（`docs/m1b-shots/`）

| 文件 | 内容 |
|---|---|
| `inventory-open.png` | E 打开的创造物品栏，默认页签 Building Blocks |
| `tab-natural-blocks.png` | 点第三个页签之后，标题变成 Natural Blocks |
| `hotbar-placed.png` | 点过物品、再点快捷栏第 1 格：一块红沙已经在格子里，tooltip 跟着指针 |
| `scrolled.png` | 滚轮往下三格之后的同一页 |
| `crosshair.png` | 关掉物品栏回到关卡，准星在球上方 |

## 八、已知边界

- 只在 Linux/Xvfb 上跑过；Windows 真机未验证（事件源同样是 SDL，编号应当一致，但没有实测）。
- 按住左键拖动（分堆/铺满）走 `screen.mouseDragged`，没有做自动化验收。
- 创造物品栏关掉时如果光标上还拿着东西，交给原版逻辑处理（该掉就掉），不额外干预。
