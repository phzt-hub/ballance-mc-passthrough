# 方向纠正：Steve 站进 Ballance 世界（M3-S0）— 2026-11-04 19:3x

用户指出上一轮做反了：「应该是 Steve 在 Ballance 的世界里，现在整反了，世界是 MC 的」。
M0/M0b 把 MC 整帧画面盖在 Ballance 窗口上，屏幕上的世界是 Minecraft 的。方向要倒过来。

## 一、用户拍板的六件事（ask_user_question，六问全选推荐项）

| 问题 | 选择 |
|---|---|
| 世界由谁画 | (1) Ballance 照常画自己的世界，往里面放一个 Steve 角色 |
| 镜头 | (2) 第一人称 Steve 视角 |
| 球与 Steve | (1) Steve 就是球：模型换成 Steve，滚动/跳跃物理照旧 |
| 画面内容 | (1) 只有 Steve 一个角色（皮肤贴图 + 方块风格） |
| 本轮交付 | (1) 一张能看的截图：Steve 站在球道上、被柱子正确遮挡 |
| 旧穿透管道 | (1) 保留，先挪到一边 |

另：MC 的 mod 也收回本仓库（不再放 xpconvert）。

## 二、本轮已落地的东西

### 1. mc-mod/ 已并入本仓库
从 ~/mcmod（xpconvert）rsync 进来（不含 build/run/.gradle/.git，212 KB），
gradle 工程可直接在 mc-mod/ 里 ./gradlew25 build。原仓库仍在，未删。

### 2. Ballance 侧：Steve 用 CK2 场景图，不用合成
新文件（Ballanced Source/Player/src/）：MinecraftSteve.h、MinecraftSteve.cpp，
已加进 Player/src/CMakeLists.txt，在 CGamePlayer::Render() 里每帧调 m_Steve.Frame(...)，
Shutdown() 里调 m_Steve.Shutdown(...)。

做法：程序化生成 64x32 经典皮肤（内存里画，LockSurfacePtr 写像素，不用图片编解码）、
建 6 个长方体（144 顶点 / 72 三角，每面独立 UV + 法线）、建 CKMaterial、
挂到 CK3dEntity 并用 CKScene::AddObject 放进当前场景。
所以遮挡、光照、雾全部由游戏自己的 3D pass 完成——没有共享内存、没有重投影、没有光栅器钩子。

自造皮肤而不是用 Mojang 的 steve.png：仓库里不放别人资产。

### 3. 环境变量
| 变量 | 作用 |
|---|---|
| BALLANCE_MC_STEVE=1 | 开（默认关） |
| BALLANCE_MC_STEVE_POS=x,y,z | 固定站位（给 POS 时不再跟球） |
| BALLANCE_MC_STEVE_BALL=0 | 不跟球 |
| BALLANCE_MC_STEVE_YAW=deg | 固定朝向 |
| BALLANCE_MC_STEVE_SCALE=f | 默认 0.25（见下） |
| BALLANCE_MC_STEVE_FP=1 | 第一人称：镜头停在 Steve 眼睛高度，模型自身隐藏 |
| BALLANCE_MC_STEVE_DEBUG=1 | 打印球候选、全场景 3D 实体名+坐标、每秒位置 |

尺度不是拍脑袋：docs/scale-calibration.md 已定 1 MC 方块 = 4.0 Ballance 单位 = 1 个球直径，
所以 1/16 方块 = 0.25 单位，Steve 高 8 单位（1.8 方块）。这一条是本轮踩到的坑：
先用 0.375 渲染出来偏大，按标定表改回 0.25。

### 4. 关卡里的实证（Level_01）
- 球实体候选是 P_Ball_Wood_01 / P_Ball_Stone_* / P_Ball_Paper_* / PE_Balloon_01（关卡里的球道物件），
  玩家球不在这个名单里，下一轮要另找玩家球的实体名。
- 站位取 A01_Floor_04（原点 (-160.2, 16.5, -366.3)），纸球停在 y=19.4、球半径 2，台面约 17.4。
- 机位 -150,26,-318 到 -160,19.5,-366（PoC 自由相机）。

### 5. 验收脚本
tools/steve/render-steve-shots.sh 加 steve_shots.py：同机位渲四帧（off / hero / sunk / fp），
输出 PNG 并做三项判定：
- hero 相对 off 的差异像素数 > 3000（Steve 真的画出来了）
- sunk（把 Steve 沉到台面下 60 单位）差异像素数 = 0（台面自己的深度缓冲把他挡住）
- fp 差异 > 10000（第一人称确实是另一台相机）

## 三、当前实测数值

| 尺度 | hero 差异像素 | bbox | 调色板命中（shirt/skin/pants） | sunk | fp | 判定 |
|---|---|---|---|---|---|---|
| 0.375（先试） | 4531 | 59x122 @ x[292..350] y[132..253] | 1247 / 1044 / 136 | 0 | 197547 | PASS |
| 0.25（按标定表） | 1662 | 39x56 @ x[301..339] y[180..235] | 790 / 370 / 0 | 0 | 213500 | FAIL（阈值 3000、pants=0） |

结论层面已经成立的两件事（两种尺度下都成立）：
1. Steve 是用游戏自己的场景图画的：沉到台面下差异像素恒为 0，遮挡是真的。
2. 皮肤贴图确实上去了：轮廓内 shirt/skin 的色相命中数百像素。

尚未达标的一点：按 0.25 缩到 8 单位后，机位离得远（约 48 单位），人物只占 39x56 像素，
低于脚本阈值；且 pants=0 说明腿被台面/前景挡住了（脚站在 y=17.4，台面厚度未核实）。
这是取材问题，不是渲染问题——需要把机位拉近、把脚抬到台面之上再定稿。

## 四、下一轮从这里接

1. 机位拉近到约 20 单位、确认脚底 y 值（用 DEBUG 的实体坐标 + 台面 bbox），重出四帧直到 PASS。
2. 找玩家球的实体名（现在只匹配到球道物件），把「Steve 就是球」接上：位置跟球、朝向跟速度。
3. 第一人称加鼠标：Ballance 侧接管相机后，按 FreeFirstPersonCamera 的 360Mode=0 默认值（俯仰 ±85 度、世界 Y 轴转向）。
4. 皮肤/MC 侧联动放到后面：目前 Steve 是 Ballance 侧自造网格，与 MC 客户端无关；
   mc-mod/ 里的帧导出先闲置（用户要求保留管道）。

## 五、边界

- Ballanced 仍是脏工作区（levelboot + passthrough + 本轮 Steve），未提交；补丁要重新导出到 tools/ballanced-patches/。
- 未动 128、未动新作17、未动 dsh-backup.sh。
- 图片在 /tmp/steve-shots（未进仓库）；进仓库前先等 PASS。
