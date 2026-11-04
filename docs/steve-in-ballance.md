# Steve 在 Ballance 世界里（M3-S0）

M0/M0b 的方向是反的：那套把 Minecraft 整帧贴到 Ballance 窗口上的管道，画面上
**世界** 是 Minecraft 的。这条路留着不动（`tools/passthrough/`），但真正的目标
是反过来：**Ballance 是那个世界，Steve 站进去。**

## 做法

`Source/Player/src/MinecraftSteve.cpp`（补丁见 `tools/ballanced-patches/Player.patch`）：

1. 用内存里画的 64x32 皮肤图集（`CKTexture::CreateImage` + `LockSurfacePtr`，不落盘、不解码）；
2. 按 6 个长方体拼出经典 Steve（144 顶点 / 72 三角面），一个 `CKMaterial`；
3. 塞进一个 `CK3dEntity`，`CKScene::AddObject` 进当前场景。

所以遮挡、光照、雾全部由游戏自己的 3D pass 完成——没有共享内存、没有重投影、没有光栅化钩子。

尺度按 `docs/scale-calibration.md`：1 个 MC 方块 = 4.0 单位 = 1 个球直径，
1 texel = 0.25 单位，Steve 高 8 单位（约 2 个球直径，和 MC 里 1.8 方块的身高对得上）。

## 「Steve 就是球」

关卡里的 `P_Ball_*` 是不会动的拾取物；真正的玩家球是**关卡脚本运行时创建**的
`I_Ball_*`（`I_Ball_Stone` 实测包围盒 4.08 x 4.15 x 4.07，正是 1 个球直径）。
`FindBall()` 每帧找一次、优先选 `I_Ball*`，把 Steve 的脚放在「球心减半径」，也就是台面上。

`BALLANCE_MC_STEVE_HIDEBALL=1` 把球网格隐藏，于是滚动的那摊东西就是 Steve 本人。

## 环境变量

| 变量 | 作用 |
|---|---|
| `BALLANCE_MC_STEVE=1` | 打开 |
| `BALLANCE_MC_STEVE_POS=x,y,z` | 固定脚底位置（不跟球） |
| `BALLANCE_MC_STEVE_BALL=0` | 不跟球 |
| `BALLANCE_MC_STEVE_YAW=deg` | 固定朝向 |
| `BALLANCE_MC_STEVE_SCALE=f` | 单位/模型单位，默认 0.25 |
| `BALLANCE_MC_STEVE_FP=1` | 相机放在 Steve 眼睛上（第一人称） |
| `BALLANCE_MC_STEVE_CAMOFF=dx,dy,dz` | 相机随 Steve 平移，拍第三人称跟拍 |
| `BALLANCE_MC_STEVE_HIDE=1` | 只建不画（对照组） |
| `BALLANCE_MC_STEVE_HIDEBALL=1` | 隐藏球网格 |
| `BALLANCE_MC_STEVE_DEBUG=1` | 每秒日志：位置、球候选、谁在动 |

## 复现

```bash
tools/poc1/build-ballanced.sh            # 需要 cmake/ninja 在 PATH 上（~/.local/bin）
tools/steve/render-steve-shots.sh        # 四帧：off / hero / sunk / fp  -> /tmp/steve-shots
tools/steve/render-ball-shots.sh         # 两帧：球上第三人称 / 第一人称    -> /tmp/ball-shots
```

Xvfb 需要在 `:99` 上跑（`Xvfb :99 -screen 0 1600x1000x24`）。

## 验收（全部实测通过）

`render-steve-shots.sh`（parked 相机；脚底 y=16.19 是从 `nmo_geom` 导出的三角形里对该点插值出来的，不是猜的）：

```
hero: 14136 像素与对照不同，bbox x[263..380] y[58..300]
调色板像素：shirt=3809, skin=3034, pants=1075
各部分平均行：shirt=153.9, skin=106.5, pants=262.1（头在上、裤在下，没倒立）
sunk: 0 像素与对照不同（沉到台面下 60 单位，台面自己的深度缓冲把他藏起来了）
first person: 216084 像素变化（相机确实进了他脑子里）
upright: yes, 轮廓高宽比 2.06（模型本身是 2.0）
PASS
```

`render-ball-shots.sh`（关卡真跑起来、球在滚）：

```
third person: 4965 shirt-teal 像素（没有 Steve 的帧实测为 0），轮廓 x[272..352] y[261..412]
  裁切区内 shirt=4965 / skin=4397 / pants=3799（头、身、腿都在）
first person: 两帧相隔 300 次渲染调用，17452 像素不同（眼睛跟着球跑）
  Steve 像素 0（相机在他头里，看不到自己）
PASS
```

为什么用「衬衫青」当标记：皮肤棕在本关里到处都是（没有 Steve 的帧就有 62519 像素落在那个色相上），
青绿是干净的（没有 Steve 的帧 = 0）。

## 截图

`docs/steve-shots/`：

- `00-control.png` Steve 关掉的对照
- `01-steve-on-track.png` Steve 站在关卡台面上
- `02-steve-zoom.png` 4 倍放大
- `03-steve-occluded.png` 沉到台面下（与对照逐像素相同）
- `04-first-person.png` 第一人称
- `10-steve-is-the-ball.png` 关卡在跑，Steve 就是那颗球
- `11-first-person-from-the-ball-0.png` / `-1.png` 从球上看到的第一人称，两帧
- `12-steve-zoom.png` 跟拍放大
