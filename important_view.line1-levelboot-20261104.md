# 任务：line1 = levelboot 玩法接管（2026-11-04 14:4x）

用户在上一轮「Linux 侧还有哪些代码没干」里选了 **1 = 线一 levelboot 玩法接管**
（不是线二 passthrough M0）。本轮全部是排查，未改任何源码、未提交。

## 本轮结论（重要，推翻了之前一个假设）

**Ballanced 的 CK2 根本没有 Virtools 脚本解释器。** 原版 Ballance 的玩法
（球滚动、物理、相机跟随、计时）全部是 CKScript，而 CK2 里：

- `CKScript` 只是一个 **`CKBehavior` 子类 + `CKBEHAVIOR_SCRIPT` 标志位**，
  没有字节码 VM、没有 opcode 表、没有解释器实现。
- 证据：`Source/CK2/src/` 下没有任何 `CKScript*.cpp`；全仓 grep
  `interpreter|bytecode|opcode|virtualmachine` 只命中 RenderEngine 的 shader JIT
  和测试，与游戏脚本无关。`CKEnums.h:420` 只有 `CKBEHAVIORTYPE_SCRIPT = 1` 标志，
  `CKEnums.h:1385` 只有参数 GUID `CKPGUID_SCRIPT`。
- 结论：**原版玩法脚本在 Ballanced 里不可能跑起来**。所以 levelboot 的「玩法接管」
  不是「找到 base.cmo 里那套脚本接线」——那条路不存在。要「进关卡」只能
  **自己写玩法**（C++ 直接驱动球/物理/相机），或者放弃「玩法」只做「渲染接管」。

## 实测：base.cmo / 关卡文件结构（全部实测，非推测）

用新写的探针 `tools/levelboot/nmo_inv.cpp`（链接 libcmo21，能读任意 cid 的对象表，
对 base.cmo 不段错误；Ballanced 的 `nmo_dump` 会段错误）。
构建方式同 `tools/build-nmo-geom.sh`（c++23 + YYCC defines + libcmo21.a）。

| 文件 | 对象数 | 内容 |
|---|---|---|
| `base.cmo` | 6111 | `CKLevel "Level"`(cid21) + 一组 CKScript 行为 + `Loading_Manager` + `Gameplay_Ingame/Events/Tutorial/Refresh Script` + `CurrentLevel Array` + `IngameParameter Array` + `All_Gameplay Group` + `Ball_Wood/Paper/Stone Entity` + 参数/数组(cid 2/3/45/9) |
| `3D Entities/Gameplay.nmo` | 10713 | 玩法主体：`ActiveBall`、`BallMatrix`、`Ball_Paper/Wood/Stone`、`CollGroup`、`Ball Count`、`ball position`、`ball radius`、`set new Ball`、`Get Nearest In Group` |
| `3D Entities/Levelinit.nmo` | 4063 | 关卡初始化：`LoadingManagerScript`、`CurrentLevel`、`AllLevel row`、`GameSettings Array`、`LoadingParamArray`、`Music_Init script` |
| `3D Entities/Balls.nmo` | 2264 | 球体件：`Init Ballpieces`、`Balls_Init`、`Ball_LightningSphere`、`Ball_Explosion_Wood` |
| `3D Entities/Camera.nmo` | 18 | 相机（18 个对象，很少） |
| `3D Entities/Intro.nmo` | 692 | 片头 |
| `Level/Level_01.NMO` | 356 | 只有 cid 23(group)/41(3dentity)/32(mesh)，**无 cid21**，与检查点一致 |

base.cmo 的 cid 直方图：9(1577) 45(1370) 2(1318) 6(812) 8(611) 3(366) 4(14) 52(13)
27(2) 41(1) 32(1) 21(1)。

## 关卡自带 scene 去哪了

`Level_01.NMO` 只有 356 个对象，**cid 直方图里没有 cid=10 (CKCID_SCENE)**。
所以关卡文件本身**不携带 scene**，scene 是 base.cmo 的 `CKLevel "Level"` 在运行时
创建的。之前检查点里「关卡自带 scene 是否要单独 LaunchScene」这个问题的答案是
**不需要**——没有 scene 可 launch。

## 下一步（从这里接）

1. 既然脚本引擎不存在，玩法接管只剩两条路：
   A. 在 C++ 里自己写一套最小玩法（球沿轨道滚 + 相机跟随 + 碰撞），驱动
      `Balls.nmo` 的球体件与 `CKCollisionManager`。
   B. 只做「渲染接管」——已经通了，承认 levelboot 到此为止（能看不能玩）。
2. 需要先问用户：要 A 还是 B。A 工作量大（物理 + 相机 + 输入），B 几乎零工作量。

## 落盘文件

| 文件 | 作用 |
|---|---|
| `tools/levelboot/nmo_inv.cpp` | 新：全对象清单探针（id/cid/name），链接 libcmo21 |
| `tools/ballanced-patches/*` | 已推 Gitee（上一轮） |
| `important_view.levelboot-20261102.md` | 上一轮检查点 |

## 本轮其它

- 未改 Ballanced 任何源码，未提交任何 git。
- Ballanced 三个子模块仍是脏工作区（补丁状态）。
- 未动 128、未动新作17、未动 dsh-backup.sh。