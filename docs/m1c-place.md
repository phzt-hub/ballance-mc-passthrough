# M1c：在世界里放方块

目标一句话：**Ballance 的鼠标能在 Minecraft 的世界里放和挖方块**，
空手上没有可放的平面时，方块直接生成在准星指着的那个点上。

这一版两头都动了：Ballance 只改了一个比例常数（`UnitsPerBlock`），
其余全在 MC 侧（Fabric mod）。

## 一、现在的行为

| 输入 | 屏幕关着时做什么 |
|---|---|
| 左键 | 原版攻击：`startAttack()` → `startDestroyBlock`，创造模式一下碎 |
| 右键 | 射线**命中**方块 → 原版放置/交互；**没命中**且手上是方块 → 在准星前 4.5 格凭空生成一块 |
| 中键 | 原版取方块（`pickBlockOrEntity`） |
| 滚轮 | 原版换快捷栏（`MouseHandler.onScroll` 的无屏分支） |
| E / Esc | 和 M1b 一样，开关创造物品栏 |

生成那一块之后，射线就会命中它，**后面每一次放置都走原版**：
贴边、叠高、对准面，全是 MC 自己的规则，没有第二套代码。

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

## 四、五个坑

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

### 4. 比例：1 方块 = 5 u

`docs/scale-calibration.md` 标定球直径 4.0 u。原来按 `UnitsPerBlock = 4.0`，
球正好一格；实机看下来方块偏小，改成 **5.0** —— 球 0.8 格，方块比球大约 25%。
这个数同时决定相机、球位、放置距离在 MC 坐标里的换算，改一处两边都得跟着：

```
/usr/share/.../MinecraftCameraSync.cpp: header.UnitsPerBlock = 5.0f;
mc-mod/.../McCameraMapper.java:      unitsPerBlock = 5.0f (fallback)
```

`inspect-camera.py` 能独立读出这个值，不用信两端的日志：

```
ball      (35.907, 34.425, 0.046)  u
ball->eye (-22.043, 35.002, 0.073)  u = 8.27 blocks
units/blk 5.000
```

### 5. 指针位置只在屏幕里用

世界里不需要 `MouseX/MouseY`：放置射线从玩家眼睛沿**旋转**出发，而旋转是
`CameraSync` 每 tick 从球位和 `aimLift` 算出来的。所以世界分支不碰
`MouseHandler` 的指针位置，只送按键。

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
