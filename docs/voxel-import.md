# 关卡导入：把 13 关的地形铺进 Minecraft（2026-11-08）

## 一、为什么做这个

用户的原话：「直接放红石粉等需要方块支撑的东西放不了」。

穿透世界是超平坦虚空：Ballance 那一帧里看得到球道，但 Minecraft 的世界里**什么都没有**。
原版放置红石粉、铁轨、火把、南瓜、树苗，第一步都是问「底下有没有一个硬面」——虚空里没有，
于是右键什么都不发生，也不报错（M1c 那段已经踩过一次同样的坑，当时给方块加了「准星前 4.5 格凭空生成」
的兜底，但那条兜底只能放**方块**，放不了需要支撑的东西）。

于是把关卡自己的地面读出来，铺成**看不见的** barrier 方块：整方块、有碰撞、不渲染 ——
画面不变，红石能架上去；关卡地形仍然由 Ballance 画，Minecraft 只负责「底下确实有个东西」。

用户确认的口径：**碰撞面 + 大件（墙、柱子、变球器底座），格子 5.48 u，方块看不见**；
后来追加两条：**13 关预先烘成存档**、**每次重进关卡开新实例、旧实例按时间戳留档**。

## 二、一条流水线

~~~
NMO ──nmo_geom──▶ 世界空间 OBJ ──voxelize.py──▶ 方块格(.bin)
                                                 │
                                                 ├─ 打包进 mod: assets/ballance/voxel/
                                                 └─ mod 在虚空世界里铺一遍 ──▶ 存档模板(.zip)
                                                                                  │
进关卡：认出哪一关 ──拷贝模板──▶ saves/<实例> ──打开──▶ 完整的一关
~~~

| 件 | 在哪 | 作用 |
|---|---|---|
| nmo_geom | tools/level-parse.md | 用 libcmo21 读 .NMO，导出世界空间三角 |
| voxelize.py | tools/voxel | 三角 → 方块格，写 MPVX2，顺带出预览图 |
| bake-levels.sh | tools/voxel | 用游戏把一关铺进世界并存档（跑 13 次） |
| package-templates.sh | tools/voxel | 把烘好的世界压成 Level_NN.zip 放进 mod |
| LevelVoxels.java | mc-mod | 识别关卡、铺方块、烘焙、检查地面 |
| McWorldBootstrap.openLevelWorld | mc-mod | 解压模板成新存档并打开；旧存档按时间戳留档 |

## 三、方块化口径

一关的几何分两类，都铺：

| 类 | 判据 | 用途 |
|---|---|---|
| 可走面 | 法线 ny > 0.5（最多约 60° 斜坡）**且**面积 < 500 u² | 球道本身；关卡识别也只认这一类 |
| 大件 | 其余面里、足迹落在可走面 2 格以内的 | 墙、柱子、变球器底座 |

* **面积 500 u² 的上限是必须的**：天空层和那块大 Quader 板是单个巨大三角，不剔掉就会在关卡顶上
  铺出一个「地板」（calibrate.py 早先算走廊宽时用的是同一条规则）。
* **竖直对齐**取「顶面不超过地表」的那一层，即 j = floor(y / cell) - 1，误差 0..cell。
  取最近（round）会把方块顶面推到地表之上最多半格：实测第 1 关起点那颗球正好落在这种方块里
  （球心 17.7 u、最近的格平面 21.9 u），球所在格被自己的支撑块占住，那一格就再也放不下红石。
* **格子大小跟着面板第 7 行 Block 走，默认 5.48 u**（变球器底板）。烘好的模板与 .bin 都把这个数
  写死了：把面板调成别的值，mod 会拒绝铺并打印一行警告，换尺寸要重跑 voxelize.py + bake-levels.sh。

实测（13 关，5.48 u）：

| 关卡 | 方块格 | 其中可走面 | 网格范围 |
|---|---|---|---|
| Level_01 | 7 581 | 2 953 | 237 x 145 x 317 |
| Level_04 | 11 352 | 6 536 | 474 x 265 x 276 |
| Level_12 | 11 669 | 7 523 | 177 x 262 x 282 |
| Level_13 | 13 368 | 5 747 | 556 x 368 x 624 |
| **13 关合计** | **115 461** | 54 279 | |

可走面的「面连通率」97.7%–99.3%（voxelize.py 每次都打印一行）——低的那几关是台阶、窄梁本身断开，
不是采样漏了。

## 四、坐标：绝对坐标，Z 镜像要减一

方块位置就是「Ballance 世界单位 / 格子大小」，不再像以前那样每关把球对到锚点：

~~~
x = floor(units.x / cell)
y = floor(units.y / cell) + offsetY      // 见下
z = -floor(units.z / cell) - 1           // 相机映射把 Z 镜像，格子因此也镜像
~~~

* **Z 为什么是 -k-1**：映射是 blockZ = -(z - origin)/cell，格子 k 覆盖 [k*cell, (k+1)*cell)，
  镜像后落进 (-(k+1), -k]，也就是方块 -(k+1)。一开始写成 -k，整套支撑在 Z 上偏了一格
  （红石照样能放，所以不会报错，只是位置不对）。
* **offsetY**：格子索引以世界原点为单位算，而有的关卡在地面以下（Level_13 最低 y = -997 u）。
  offsetY 把关卡整体抬进世界高度 -62..318，默认 100，放不下的自动改（Level_13 是 120）。
  模板烘的时候用同一个函数算，所以两边一致。
* 旧的「每关一个锚点」（McCamerMapper.reanchor）在绝对模式下变成空操作：否则每关把球对到
  (0,100,0) 会和烘死的方块错开。

## 五、认关卡：靠球自己的位置

Minecraft 那边没有任何东西知道 Ballance 正在哪一关——关卡文件是引擎的 Object Load 行为读的，
要拿到文件名得改 CK2（另一个 submodule）。所以换成量出来的办法：

> 13 关各自占着不同的绝对坐标，球站在自己那关的地面上。把球的位置拿去和 13 份可走面比一遍，
> 最近的那一关就是它。

Level_01 起点 (54.1, 17.7, -153.1) 实测：

| 关卡 | 距离（格） |
|---|---|
| **1** | **0.64** |
| 2 | 2.81 |
| 11 | 2.84 |
| 7 | 7.66 |
| 8 | 12.14 |
| 其余 | 12–146 |

判据是「最近的可走面 ≤ 1.8 格」：0.64 过，2.8 不过，不会认错；真实一关里球离地面通常不到半格。
认不出来时退回旧行为（虚空世界 + 按球设锚点），日志里写明分数表，不会静默铺错。
BALLANCE_MC_LEVEL=<n> 可以强制指定，给测试用。

## 六、存档：模板、实例、留档

* **模板**：bake-levels.sh 启动 13 次客户端，每次 BALLANCE_MC_BAKE=<n> + BALLANCE_MC_VOID_WORLD=bmc-tpl-<n>，
  mod 把该关格子**强制加载所在区块**再铺（平时铺块会跳过没加载的区块，烘模板不能跳，否则远端是空的），
  然后 saveEverything(flush)，日志打 BAKE DONE；烘好的世界压成 Level_NN.zip 放进 mod 资源。
* **进关卡**：LevelSession 认出关卡 → McWorldBootstrap.openLevelWorld 把模板解到 saves/<实例> → openWorld。
  世界一打开就是完整的一关；模板缺失时退回「建虚空世界 + 现铺」。
* **解包怎么走**（2026-11-08 12:00 改）：字节**先从 mod 自己的 jar 文件里读**（ownJar() 拿 CodeSource 位置，
  ZipFile 取条目），类加载器只作开发环境（exploded classes）的退路；解到 <游戏目录>/.bmc-template-work/Level_NN，
  确认 level.dat 真在、再看一眼字节数，才 Files.move 进 saves/<实例>。任何一步不对就抛异常，
  退回虚空世界并打 ERROR —— 不会再留一个没有 level.dat 的空存档让世界列表去打开它。
* **实例与留档**：每次重进关卡都是新实例；被换下来的那份**不删**，改名成 <id>-<yyyyMMdd-HHmmss>
  留在 saves/ 里（例如 passthrough-2-20261108-113012）。日志写 archived … (N instances kept)，
  超过 20 份追加一句提醒。上次会话没来得及留档的那份，启动时也会改名留档，不会删。
  **没有 level.dat 的目录不算实例**：启动时直接删（removed the empty leftover …），
  取 id 时也当它是空的、原地重写 —— 2026-11-08 那次失败留下的正是这种目录。

## 七、实测（2026-11-08，Linux / Xvfb / llvmpipe）

`tools/voxel/test-import.sh 1` 的完整链路：

~~~
level start: level 1 ball at (54.1, 16.0, -153.0) -> mapping anchor=(0.0, 100.0, 0.0) origin=(0.0, 0.0, 0.0)
level start: replacing the world (previous save "passthrough")
template Level_01 read through the class loader (985240 bytes, ...)   <- 开发环境走这条
opening the baked world for Level_01 as "passthrough-2" (30 files)
world ready: "passthrough-2" (previous save "passthrough")
voxels: under the ball at BlockPos{x=9, y=102, z=27} is Block{minecraft:barrier}[waterlogged=false]
PASS: level 1, baked world, barrier under the ball
~~~

| 量 | 值 |
|---|---|
| 一关的世界（未压缩） | 14–15 MB（4 个 region 文件，大部分是空格子） |
| 一关的模板（zip） | 0.9–1.0 MB；13 份共 13 MB，jar 12.6 MB |
| 烘一关 | 约 26 秒（含客户端启动）；13 关约 6 分钟 |
| 进关卡（认关卡 + 拷模板 + 换世界） | 约 1 秒 |
| 现铺（模板缺失时的退路） | 7 581 格 / 2048 格每 tick = 4 tick |
| 红石探针 | `voxels: redstone dust can survive on a barrier` |

真正的判定不是 canSurvive 这个谓词，而是走一遍右键放下的那条代码路径：

~~~
voxels: probe: gamemode=CREATIVE target=BlockPos{x=9, y=104, z=27}=Block{minecraft:air} canSurvive=true
voxels: vanilla right-click test on a barrier -> Success[swingSource=PREDICTED, itemContext=...]
        (redstone placed: true)
~~~

它调的是 `ServerPlayerGameMode.useItemOn(...)`，和鼠标右键在服务端走的是同一个方法；
方块也真的出现了（redstone placed: true）。这一段只在 `BALLANCE_MC_PROBE=1` 时跑，
因为它会真的往世界里放一块红石。

红石探针问的是原版自己的判据（`RedStoneWireBlock.canSurvive`），也就是「右键能不能放下红石粉」
的同一个判断；它比截图更能说明这次改动到底解决没解决用户报的问题。

另外：`enter-level.sh` 只进第 1 关，所以测试只覆盖了第 1 关的模板；其余 12 份的烘焙日志
（`run/bake-logs/`）各自有一行 BAKE DONE 和格数，可以逐个核对。

## 八、坑

1. **镜像差一格**（第四节）。症状：能放，但整片支撑在 Z 上偏一格。
2. **saveEverything 不能在客户端 tick 线程调**。第一版直接在 Minecraft.tick() 里存盘，
   崩在 DistanceManager.runAllUpdates 的 ReferenceArrayList.get（NPE）。烘焙必须 server.execute(...)。
3. **旧 .bin 混进新 jar**：格式从 MPVX1 改到 MPVX2（加了可走面标记）时，mod 资源目录里还是旧的，
   日志一句 is not an MPVX2 file 才发现。两边都校验 magic。
4. **比例是烘死的**：模板里的方块按 5.48 u 生成，面板 Block 调成别的值不会自动跟着变。
5. **未加载的区块**：平时铺块跳过没加载的区块（球跑到哪铺到哪），烘模板必须强制生成。
6. **pkill -f 会杀掉自己**：写在一行 bash 里时，模式串也出现在父 shell 的命令行里。
   脚本里用 [n]et.fabricmc… 这种括号写法。
7. **打包后的 jar 里，类加载器给的模板流是空的**（2026-11-08 首次真机）。开发环境解出 30 个文件，
   128 上同一份代码打的是 (0 files)：不抛异常、循环一次都没进，目录建了、level.dat 没有，
   紧接着 openWorld 撞上 NoSuchFileException: level.dat，客户端停在「加载世界失败 / 尝试恢复」。
   同一台机器、同一个 jar、同一份 JDK 25 用 URLClassLoader + getResourceAsStream 读出来是 30 条，
   所以问题在「谁来读」而不在文件。现在的做法不再依赖那条流：直接按 CodeSource 打开 mod 自己的 jar，
   ZipFile 取条目；并且解包先进暂存目录、验过 level.dat 才搬进 saves/。
   **判据**：日志里出现 template Level_NN read from the mod file … 才算走了新路；
   出现 could not unpack the baked world … 说明退回了虚空世界（能玩，但方块是现铺的）。

## 九、常用命令

~~~bash
tools/export-levels.sh /tmp/nmo_geom ~/src/ballance-game /tmp/nmg   # 1) 导几何
python3 tools/voxel/voxelize.py --out /tmp/vox --preview            # 2) 方块化 + 预览
tools/voxel/bake-levels.sh                                          # 3) 烘模板（13 次客户端）
tools/voxel/package-templates.sh && (cd mc-mod && ./gradlew25 build) # 4) 打包 + 重编
tools/voxel/test-import.sh                                          # 5) 验收
~~~
