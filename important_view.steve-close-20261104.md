# 机位拉近 + 脚底 y + 玩家球实体名（M3-S1）— 2026-11-04 20:1x

接着 `important_view.steve-in-ballance-20261104.md` 的「下一轮从这里接」第 1、2 条做。
本轮**全部实测**，四条判据全过：`tools/steve/render-steve-close.sh` → **PASS**。

## 一、结论先列

| 问题 | 答案 | 怎么验的 |
|---|---|---|
| 玩家球实体名 | **`I_Ball_Stone`** | DEBUG 每秒比对全场景 3D 实体的位移差分，它是唯一在动的非相机实体 |
| 球尺寸 | bbox 4.08 × 4.15 × 4.07 → 半径 2.07 | 与标定表「1 球径 = 4 单位」吻合 |
| 脚底 y | **16.19**（A01_Floor_04 主朝上平面） | 从导出的 OBJ 反算：朝上面积 950 单位²，y=16.19；另 17.41 是台沿 |
| 机位 | feet + (0, 6, 20) = **20.9 单位** | 人物从 39×56 px 变成 **93×190 px**，aspect 2.04（皮肤贴图 16×32 = 2.0） |
| 真的被台面遮挡 | `sunk` 与对照 **0 像素差** | 停泊机位、只把 Steve 沉 60 单位 |

## 二、本轮五条判据（`steve_close_check.py` 的输出）

```
repeat run:           0 pixels differ (0 = frame is deterministic)
close-up:          9849 pixels, bbox 93x190 at x[274..366] y[199..388], aspect 2.04 (skin is 2.0)
palette:         shirt=2866, skin=937, pants=839
mean row:        shirt=275.9, skin=241.6, pants=347.9
upright:         yes (skin above shirt above pants)
parked hero:      14259 pixels differ from the same view without Steve
parked sunk:          0 pixels differ (0 = the slab's depth buffer hides him)
first person:    158264 pixels differ from the attached camera
PASS
```

对照上一轮的数字：`pants=0`（腿被裁掉/太小认不出）→ 现在 839；差异像素 1662 → 9849；
bbox 39×56 → 93×190。**上一轮 FAIL 的原因就是取材（机位远、脚底取错），不是渲染。**

## 三、两个坑（这轮踩到，写进脚本注释了）

1. **不给 `--level` 就等于什么都没干。** level 文件不会自己合进来，帧日志会显示
   `camera=Cam_MenuLevel scene=(none)` —— 渲染的是**主菜单**，Steve 压根不在画面里。
   任何"像素差"这时候量到的都是菜单动画，不是模型。
   （本机实测：不合并关卡时同一机位连跑两次，帧不稳定，因为菜单相机是时间驱动的。）
2. **停泊机位落在台面内部就什么都看不见。** A01_Floor_04 的 bbox 在 y 上跨 12.85–20.19，
   机位 y=20 在板子里 → 从板子内部往 Steve 看，只看到板子背面。y=21 以上才在外面。

## 四、本轮的确定性（重要，之前没有）

合并关卡 + 固定 POS + 相机挂在 Steve 身上时，**重复跑两次的帧逐字节相同**
（`repeat run: 0 pixels differ`）。所以 `sunk=0` 这类断言才有意义：
它证明的是"台面自己的深度缓冲挡住 Steve"，不是"两次跑巧了都一样"。

对比：菜单相机（时间驱动）下同样两次跑差 18k 像素 —— 那种环境里做像素比对是假证据。

## 五、代码/脚本

| 文件 | 作用 |
|---|---|
| `tools/steve/render-steve-close.sh` | 新：固定站位近景五帧（hide/hero/hero2/fp + 停泊 hide/hero/sunk），一份 PASS |
| `tools/steve/steve_close_check.py` | 新：上述五条判据 |
| `tools/steve/render-steve-ride.sh` | 新：跟球版（Steve 跟着 `I_Ball_Stone` 走，相机挂他身上）——**用于看运动，不能用于像素验收**（球是物理驱动的，两次跑会跑偏） |
| `tools/run/make-run.sh` | 新：只装运行目录不启动；模块来自 Ballanced stage，资产软链到零售安装 |
| `tools/poc1/run-poc1.sh` | 改：`GAME`/`OUT` 默认值从 `/tmp/poc1` 挪到 `<repo>/run/` |
| `docs/steve-shots/20..25-*.png` | 本轮交付图（20 对照 / 21 近景 / 22 三倍放大 / 23 第一人称 / 24-25 停泊机位对照） |

运行目录现在在 `~/src/ballance-mc-passthrough/run/`（`.gitignore` 里 `/run/`），
`/tmp` 是 tmpfs，重启不再丢；`/tmp/poc1` 可以不要了。

## 六、下一轮从这里接

1. **鼠标接管第一人称**：现在 `BALLANCE_MC_STEVE_FP=1` 的朝向是固定 yaw（`m_Yaw` 只在构造时读环境），
   要接 SDL 相对鼠标（`SDL_SetWindowRelativeMouseMode`）才有得玩；参考 `docs/free-camera.md` 里
   FreeFirstPersonCamera 的默认值（俯仰 ±85°、世界 Y 轴转向）。
2. **朝向跟速度**：`UpdatePlacement()` 里 yaw 只有 `m_YawGiven` 和常量 `m_Yaw` 两个来源，
   给球加"按位移方向算 yaw"这一步，"Steve 就是球"才算完整。
3. 跑动中的截图仍要做，但**验收**要走同一个套路：先确认帧确定（重复跑 0 像素差）再比像素。

## 七、边界

- Ballanced 仍是脏工作区（levelboot + passthrough + Steve），未提交；补丁未重新导出。
- 未动 128、未动新作17、未动 dsh-backup.sh。
- `docs/steve-shots/00–12` 是上一轮的图，保留但**已被 20–25 取代**（那批的 sunk=0 在菜单/近距取材下不可信）。
