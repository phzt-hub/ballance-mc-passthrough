# M1c：在世界里放方块

目标一句话：**Ballance 的鼠标能在 Minecraft 的世界里放和挖方块**，
空手上没有可放的平面时，方块直接生成在准星指着的那个点上。

这一版两头都动了：Ballance 只改了一个比例常数（`UnitsPerBlock`），
其余全在 MC 侧（Fabric mod）。

## 一、现在的行为

| 输入 | 屏幕关着时做什么 |
|---|---|
| 左键 | 原版攻击：`startAttack()` → `startDestroyBlock`，创造模式一下碎 |
| 右键 | 四种结果（射线打空 / 目标格空着 / 目标格是 barrier / 其余），见「1.1」 |
| 中键 | 原版取方块（`pickBlockOrEntity`） |
| 滚轮 | 原版换快捷栏（`MouseHandler.onScroll` 的无屏分支） |
| E / Esc | 和 M1b 一样，开关创造物品栏 |

生成那一块之后，射线就会命中它，**后面每一次放置都走原版**：
贴边、叠高、对准面，全是 MC 自己的规则，没有第二套代码。

### 1.1 11-13：右键的四种结果

11-08 把「某处一直放不上方块，换个地方就行」归到了「服务端的眼睛落后客户端」。
11-13 那局把这个假设证伪了：**14 次右键里，服务端与客户端的眼睛差 0.00–0.11 格，
`clientSays` 与 `serverSays` 14/14 完全相同**。两侧从来不是不同步的。

真正的原因在同一份日志里躺着：14 次右键有 **3 次**的目的格本身就是 `barrier` ——

```
world right press aim=(-1,100,-2)/west   target=(-2,100,-2)
    clientSays=barrier  serverSays=barrier
```

关卡的世界是烘出来的**不可见 barrier**（Ballance 地表的碰撞代理）。射线打中一面墙，
要放方块的那一格往往**也是** barrier，原版就没有格子可放，服务端照规矩拒绝，
而客户端这一侧一行日志都不打 —— 于是「点了没反应」。

现在四种情况各有归宿，每一行都写清楚：

| 情况 | 做什么 | 日志 |
|---|---|---|
| 射线打空 | 在 `eye + look * 4.5` 生成一块 | `aim=MISS ... placed at the end of the reach` |
| 目标格可替换（`canBeReplaced()`，与服务端同一条判据） | 原版放置 | `target=open` |
| 目标格被 `Blocks.BARRIER` 占着 | 把**那一格本身**换成手里的方块 | `target=barrier, replaced the surface itself` |
| 其余 | 不派发，写明被什么挡住 | `blocked_by=<那一格实际的方块> -> refused` |

第三种是这次唯一的新行为：你瞄的那面不可见墙就是关卡地表，方块落在它上面
（也就是十字准星底下），而不是往里三格。护栏是 `isCollisionShapeFullBlock` ——
只接受碰撞形状整格的方块：barrier 是球滚的那层，换成火把、铁轨、半砖等于把地板抽走。

### 1.2 落地校验：`placement ok` / `placement refused`

按下去放没放上，按下这一刻是不知道的：方块要等服务端收了包、再把更新发回客户端，
一两个 tick 之后才出现。所以每次右键都记下「该出现在哪格、是什么方块」，
四个 tick 内读一次客户端世界：

```
placement ok (open): minecraft:stone at (-2,100,-2) after 1 ticks
placement refused (open): minecraft:stone at (-2,100,-2) is still minecraft:air
```

`open` 是走原版的那次，`surface` 是换掉 barrier 的那次。以后再看「为什么没放上」，
不用再从位置读数里反推。

## 二、两道墙

### 1. 屏幕一关，世界里的点击全被吞掉

`PassthroughInput.onClientTick` 原来是这么分的：

```java
if (openNow) {            // 创造物品栏在屏幕上
    forwardPointer(...);
} else {
    seenButtons = pose.buttons();   // ← 直接同步成现状，等于丢弃
}
```

`seenButtons` 是「客户端已经处理过的按钮位图」。屏幕关着时把它同步成当前值，
下一次按下就没有「变化」可言，什么都不会转发。M1b 只做物品栏，所以这条分支是
对的；要做世界，它必须换成真正转发。

### 2. 虚空里没有可放的平面

世界是超平坦 `the_void`（一层空气），脚下什么都没有。原版
`Minecraft.startUseItem()` 先看 `hitResult`：

* 命中方块 → `gameMode.useItemOn(...)`，正常放置；
* 没命中（`Type.MISS`）→ `gameMode.useItem(...)`，也就是物品自己的 use 方法。
  普通 `BlockItem` 的 use 在空气上返回 PASS —— **什么都不发生，也不报错**。

所以「转发右键」只解决一半：第一块永远放不出来，得有个兜底。

## 三、实现

| 件 | 在哪 | 作用 |
|---|---|---|
| 世界输入转发 | `PassthroughInput.forwardWorldInput` | 左右中键、滚轮，按 `pose.buttons()` 的位变化 |
| 按键注入 | `PassthroughInput.mouseKey` | `KeyMapping.set(key, down)` + 按下时 `KeyMapping.click(key)` |
| 兜底放置 | `PassthroughInput.placeAtAim` | 主手是 `BlockItem` 时，在 `eye + look * 4.5` 的格子 `setBlockAndUpdate` |
| 地表替换 | `PassthroughInput.placeSurface` | 目标格被关卡自己的 barrier 占着时，用碰撞整格的方块换掉那一格 |
| 落地校验 | `PassthroughInput.watchPlacement` / `checkPlacement` | 四个 tick 内回读那一格，日志给 `placement ok` / `placement refused` |
| 比例 | `MinecraftCameraSync.cpp` | `UnitsPerBlock = 5.0`（球直径 4 u = 0.8 格） |

### 为什么不走 `MouseHandler.onButton`

那是真鼠标点击的入口，但它干的第一件事是：

```java
if (screen == null && !mouseGrabbed && down) grabMouse();   // 26.3 字节码
```

指针在 Ballance 窗口手里，MC 窗口没有焦点，**没有什么可以 grab 的**。
而 `onButton` 在屏幕为空时末尾只做三件事：

```java
if (button == 1) isLeftPressed = down;
else if (button == 3) isRightPressed = down;
KeyMapping.set(mouseKey(button), down);
if (down) KeyMapping.click(mouseKey(button));
```

后两行照抄即可，前面那堆 grab/screen 逻辑全部绕开。
按下之后 `Minecraft.handleKeybinds()` 下一个 tick 就会
`while (keyUse.consumeClick()) startUseItem();` —— 和真鼠标一模一样。

### 为什么在服务端 setBlock

`ClientLevel.setBlock()` 只改客户端的内存，服务端不知道，下一次区块更新就被抹了。
`client.getSingleplayerServer().getLevel(dimension).setBlockAndUpdate(pos, state)`
走服务端，包会自己回到客户端。

## 四、六个坑

### 1. `Type.MISS` 也是 `BlockHitResult`

`describe()` 一开始写的是 `if (hit instanceof BlockHitResult) return block.getBlockPos()`，
于是「什么都没打中」被打印成 `aim=BlockPos{x=3, y=98, z=0}/west` —— 看着像命中了一块
根本不存在的方块。原版的 miss 是 `BlockHitResult.miss(pos, dir, blockPos)`，**带着位置**。
判据必须是 `hit.getType()`。

### 2. 快捷栏选中格是玩家数据，两边各存一份

`inventory.setSelectedSlot(0)` 只改调用它的那一侧。`dressScene` 里的是
`ServerPlayer`，改完**不发包**，客户端还记着上次会话滚到的格子 ——
手是空的，右键什么都没发生，日志里 `held=0 minecraft:air`。
客户端那份要在 `McWorldBootstrap.onInWorld` 里自己再设一次。

### 3. 菜单里的点击也会被当成世界点击

相机通道从第一帧就在发布，菜单态也在发 `buttons`。进关卡的
CREDITS / START / LEVEL 8 那些点击，每一发都会变成 MC 的一次左键攻击
（虚空里无害，但状态脏）。门禁是 `pose.hasBall()`：**有球才说明在关卡里**。

### 4. 比例：1 方块 = 4 u，屏幕上也正好一个球宽

`docs/scale-calibration.md` 标定球直径 **4.00 u**（`Ball_Wood` 网格 4.00×4.00×4.00），
所以「一个方块等价于一个球宽」就是 **`UnitsPerBlock = 4.0`**。这一版就是 4.0，
而且屏幕上也终于是这个数——中间那一版 5.0 是补错了地方。

**方块偏小从来不是世界比例的问题。** 4.0 和 5.0 改的是世界换算，可是被摆下的方块
离相机几格是**固定的**（射线长度 4.5 格），它在屏上的宽度根本不随 `UnitsPerBlock` 变；
真正错的是两半的**投影**：

| | Ballance 那半 | Minecraft 那半（改前） |
|---|---|---|
| FOV 口径 | 58° **水平**（`VxMatrix::Perspective` 的 `m00 = cot(fov/2)` 是 x 方向） | 58 被当成**垂直**用（`Camera.calculateFov` 是垂直的） |
| 帧尺寸 | 640×480 | 854×480 |
| 焦距 | 577 px（两个轴） | 433 px（原始帧），合成时横向再乘 640/854 |
| 合成后等效焦距 | 577 / 577 | **325 / 433** |
| 相对 Ballance | 1.00 | **0.56 横 / 0.75 竖** |

所以一个方块在屏幕上只有球的 0.56 宽、0.75 高，又被横向压扁——这就是「两个方块才
顶一个球」的来源。改法是两头一起对齐：

1. `CameraMixin` 把通道里的**水平** FOV 换成垂直的再交给 MC：
   `2·atan(tan(fovH/2) / aspect)`，58° → **45.15°**（`CameraSync.verticalFov`）；
   准星的像素偏移同一处口径，也跟着改了。
2. MC 的帧尺寸必须和 Ballance 窗口**同宽高比**：两个都是方形像素的投影，
   宽高比不同就没有任何单一 FOV 能让两个轴同时对上，合成时只能把一边压扁。
   现在 `run/options.txt` 里 `overrideWidth:640 / overrideHeight:480`，
   帧就是 640×480，合成 1:1 不再缩放。`CameraSync` 发现两者差超过 2% 会在日志里
   报警（`frame aspect ... differs from the Ballance window's`），不再靠眼睛发现。

改完的实测（`tools/passthrough/fit-projection.py`：拿**已知坐标**的方块，
用 Ballance 的投影公式算出它该占的框，再和目标帧里量到的框比）：

```
frame          640x480
Ballance f     577.3 px  (horizontal fov 58.00 deg)
block          [3, 98, 0]            <- 虚空里右键 MISS，日志给了确切坐标
predicted box  x 318.9..364.1  y 116.6..177.9   (45.2 x 61.3 px)
measured box   x 315..362  y 116..179   (47 x 63 px)
implied fx     600.4 px   fy 593.1 px   (Ballance: 577.3)
```

两个轴都落回 577 px 附近（量的框每边多 1 px 抗锯齿，正好解释 47 对 45.2）。
球那半也对得上：4.00 u 的球离相机 41.35 u，577 × 4 / 41.35 = **55.8 px**，
合成帧里量到 **56–58 px**。

镜头、球位、放置距离的换算都跟着这一个数走，改一处两边都得跟着：

```
Source/Player/src/MinecraftCameraSync.cpp: header.UnitsPerBlock = 4.0f;
mc-mod/.../McCameraMapper.java:            unitsPerBlock = 4.0f (fallback)
```

`McCameraMapper` 以前**第一帧就把比例锁死**（`if (ready) return;`），C++ 后来改的值
一个都没生效——22:20 那轮整轮跑的都是旧比例，这也是当时「改成 5.0 却看不出变化」的
原因。现在只锁原点（那是两套世界的对应关系，不能变），比例每帧跟着通道走。

`inspect-camera.py` 能独立读出这个值，不用信两端的日志：

```
ball      (35.911, 34.425, 0.038)  u
ball->eye (-22.048, 35.003, 0.083)  u = 10.34 blocks
units/blk 4.000
```

> 剩下的误差：2026-11-07 之前准星方块中心还差约 20 px（当时准星只按竖直分量偏移，
> 而且球一左右漂就不跟）。现在准星画在「射线 4.5 格外那个点」的**完整投影**上，
> 两轴都对：静止帧量到准星 (319.1, 135.8) 对算出 (320.4, 135.0)，差 1.5 px。
> 见 [docs/m1b-input.md](../../docs/m1b-input.md) 第五节。

### 5. 指针位置只在屏幕里用

世界里不需要 `MouseX/MouseY`：放置射线从玩家眼睛沿**旋转**出发，而旋转是
`CameraSync` 每 tick 从球位和 `aimLift` 算出来的。所以世界分支不碰
`MouseHandler` 的指针位置，只送按键。

### 6. 关卡地表是**不可见**的，于是「没反应」看着像 bug

球滚的那层是 `Blocks.BARRIER`，渲染上完全透明，选中框画上去也看不见。射线打中它的时候，
屏幕上没有任何标记 —— 用户看到的是「我对着空地右键，什么都没发生」。
能看出来的只有日志：`blocked_by=` 说明目标格被什么挡住，`target=barrier` 说明换格那条路走了。

## 五、验收（`tools/passthrough/test-m1c.sh`）

一局里连做四件事，日志是判定依据（截图只能看个大概）：

```
world right press  aim=MISS                    held=3 minecraft:spruce_log
placed Block{minecraft:spruce_log} at BlockPos{x=3, y=98, z=-1}
                eye=(-0.04,101.62,-0.07) target=(3.51,98.86,-0.07)
world right release aim=MutableBlockPos{x=3, y=98, z=-1}/west
world right press   aim=MutableBlockPos{x=3, y=98, z=-1}/west     ← 走原版
world right release aim=MutableBlockPos{x=3, y=98, z=0}/west      ← 叠上去了一块
world left  press   aim=MutableBlockPos{x=2, y=99, z=-1}/up
world left  release aim=MISS                                      ← 挖掉了
```

| 判定 | 证据 |
|---|---|
| 右键在虚空里放得出来 | `placed ... at BlockPos{x=3, y=98, z=-1}` |
| 第二块走原版 | 按下的 `aim` 已经是 `Type.BLOCK`，不是 MISS |
| 左键能挖 | 松手后 `aim` 回到 MISS |
| 准星对得上 | 截图里十字压在方块正中 |
| 比例 | `inspect-camera.py` 的 `units/blk 5.000` |

截图在 `docs/m1c-shots/`：物品栏取方块、关屏、放置、叠放、挖掉各一张（640x480 合成画面），
外加两张 MC 原始帧（854x480，`inspect-mailbox.py --png`），方块在那里没有被 Ballance
的场景盖住。

## 六、还没做的

* **碰撞**：Ballance 的球不认识 MC 的方块，会直接穿过去。下一步（M1d）是把方块
  同步成 `CKCollisionManager` 的 `CKCOLLISION_BOX`；`docs/poc2-obstacles.md`
  已经量过成本（69 ns/个/帧，16000 个没到上限），1 个方块 = 1 个 bbox，尺度按 5 u。
* **按住不放连续放**：现在一次按下放一块。原版按住是每 4 tick 一次。
* **拖动**（`mouseDragged`）在物品栏里还没验收。
