# 一个关卡一个世界（2026-11-07）

从菜单进一次关卡，Minecraft 那边就**换一个全新的虚空世界**：旧存档删掉，新存档重建。
上一个关卡里搭的桥、堆的墙不会出现在下一个关卡里，也不会出现在同一关第二次进去的时候。

## 一、为什么要这个

在此之前所有关卡共用一个存档 `passthrough`，而且所有关卡共用同一个映射原点
（`McCameraMapper` 只在第一帧锁一次原点），于是：

* 第一关搭的东西，进第二关时**就立在起点**（因为每关的球都落到同一个锚点）；
* 退出去再进同一关，上一轮搭的还在；
* 玩到后面，世界里散落的方块越来越多，分不清是哪一关的。

用户 2026-11-07 的要求：「不要所有的关卡都用一个世界，可以每次重新进入关卡和开启新关卡
都另起一个新的干净的世界吗？」并在两个做法里选了**真的删档重建**（不是就地清空）——
多等两三秒加载，换一个货真价实的新存档。

## 二、怎么知道「刚进了一关」

相机通道里新加了 `MPC_FLAG_LEVEL`（`MpCamera.h`，0x8）：

```
if (camera && cameraName && strcmp(cameraName, "Cam_MenuLevel") != 0)
    header.Flags |= MPC_FLAG_LEVEL;
```

**不能用「有球」来判断。** 原来的门禁是 `hasBall()`，文档里也写着「有球才说明在关卡里」。
2026-11-07 实测推翻了这个前提：菜单的 composition 自己就带球状物体（`Ball_Wood` 停在
原点），菜单里 `flags=0x3`（VALID|BALL）、`ball=(0.0, 0.0, 0.0)`，相机是 `Cam_MenuLevel`
在绕着场景转。于是：

* 菜单里的 START / LEVEL 点击被当成**世界点击**转发给 MC（顺带把世界标记成「用过」）；
* 「球出现」这个上升沿在启动时就已经用掉了，真正进关卡时反而没有沿。

相机名才分得开：菜单 = `Cam_MenuLevel`，关卡 = `InGameCam`。所以标志位从相机推，
MC 侧用它的上升沿（带 6 tick 确认、10 tick 迟滞，见 `LevelSession`）。

## 三、MC 侧的动作（`LevelSession.java`）

```
上升沿（关卡开始，且这个世界被点过）
  → publishBlank()        一个全透明帧，盖掉旧的 MC 画面
  → setSuspended(true)    挂起导出，加载屏不会贴到 Ballance 上
  → 下一 tick client.disconnect(new TitleScreen(), false)
  → client.level 变 null 后 McWorldBootstrap.createFreshWorld()
  → 新世界就绪：setSuspended(false)，删掉上一份存档
```

三个细节：

1. **存档名带序号**：`passthrough`、`passthrough-2`、……（取第一个没被占用的）。
   不原地「删了再建」是因为删除必须等旧服务端线程真的停下来；新建一个不同 id 的世界
   没有这个时序问题，新世界加载完再删旧目录，绝不会被写回。
2. **空白帧**：消费者每 tick 都重画最后上传的那帧，所以推进去一帧全透明的，整个加载
   期间 Ballance 的画面（也就是刚进去的那一关）原样露出来，不闪、不糊加载屏。
   挂起期间的读回也会被丢掉（`publishRows` 里挡一道），否则在途的一帧会把旧世界贴回来。
3. **没被点过的世界不换**：Minecraft 启动时本来就是刚建的新世界，第一次进关卡再换一次
   就是白等两三秒。只有「转发过世界点击」的世界才在下一关开始时被替换。

## 四、实测（Linux / Xvfb :99，640x480）

第一次进关卡（世界是新的，没点过）：

```
23:20:19 level start: ball at (54.1, 17.7, -153.1) -> mapping origin (54.10, 17.66, -153.07)
23:20:19 level start: this world has not been built in, keeping it
```

退出关卡 → 回菜单（`flags 0x3`，没有 LEVEL）→ 再进同一关：

```
23:47:57 level start: replacing the world (previous save "passthrough")
23:47:57 published a blank frame (the world is being replaced)
23:47:58 creating fresh void world "passthrough-2"
23:48:00 in world: ... gameType=CREATIVE passthrough=true save=passthrough-2
23:48:00 world ready: "passthrough-2" (previous save "passthrough")
23:48:00 deleted the previous void save "passthrough"
```

整段 **3 秒**（23:47:57 → 23:48:00），期间画面是 Ballance 自己的关卡。`run/saves` 里始终
只有当前这一份：`passthrough-2`。再走一轮，id 会回收成 `passthrough`（第一个没被占用的），
旧的那份在新世界就绪后删掉——两轮都验过。

## 五、边界

| 情况 | 行为 |
|---|---|
| 从菜单进关 / 换关 / 重进同一关 | 换新世界（前提：这个世界被点过） |
| 球摔死、关卡自己重开 | **不换**（用户选的：这一关搭的东西还在） |
| 在关卡里按 Esc 暂停、开关背包 | **不换**（LEVEL 标志一直亮着） |
| MC 启动时 Ballance 已经在关卡里 | 不换（那是启动时的第一个世界，本来就是新的） |
| `BALLANCE_MC_WORLD=<存档>` 指定的世界 | 不碰（既不会布景，也不会被删） |

开关：

| 环境变量 | 作用 |
|---|---|
| `BALLANCE_MC_NEWWORLD=0` | 关掉每关换新世界，回到共用一个存档 |
| `BALLANCE_MC_REUSE_WORLD=1` | 启动时复用已有存档（调画面省一次加载），换关逻辑仍在 |
| `BALLANCE_MC_VOID_WORLD=<名>` | 存档基名，默认 `passthrough` |

启动时会清掉上一次会话留下的所有 `passthrough*` 存档（只删自己这套命名：基名本身和
「基名-数字」），所以关掉 MC 再开也是干净的。

## 六、坑

1. **菜单里也有球**（上面第二节）。除了换世界，它还让世界输入的门禁从 `hasBall()` 改成
   `inLevel() && hasBall()`——否则菜单里点 START 会被当成世界点击，把世界标脏。
2. **不能边跑边删**：`client.disconnect()` 之后服务端线程是异步停的，立刻删目录会被它
   写回来。带序号的存档名把这个问题整个绕开。
3. **别在渲染线程里 disconnect**：检测到关卡的 tick 只置状态，真正 `disconnect` 放到
   下一个 tick（那时这一 tick 的世界已经跑完）。
4. **空白帧之后还要挡一次**：读回是异步的，挂起前已经提交的那一帧会在空白帧之后落地，
   不挡的话旧世界会被贴回来，然后整个加载期间都停在那张旧图上。
