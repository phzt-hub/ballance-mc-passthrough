# M1：把 Ballance 的镜头交给 Minecraft

目标（README 的里程碑表）：**相机 + 球位 = Steve 位**，球滚的时候 MC 世界跟着走，不漂。

这一版做的是**反向通道**：M0b 把 MC 的像素送进 Ballance，M1 把 Ballance 的视角送回 MC。
两个方向是两块独立的共享内存，互不影响。

## 一、通道

| | 帧通道（M0b，已有） | 相机通道（M1，本轮） |
|---|---|---|
| 方向 | MC → Ballance | Ballance → MC |
| 内容 | BGRA8 像素，三槽轮转 | 约 100 字节标量，无槽 |
| 写者 | Fabric mod | Source/Player/src/MinecraftCameraSync.cpp |
| 读者 | CKSdlGpuPassthroughTick | McCameraChannel → CameraMixin |
| Linux | /dev/shm/ballance_mc_frame | /dev/shm/ballance_mc_camera |
| Windows | %LOCALAPPDATA%\BallanceMcPassthrough\ballance_mc_frame.dat | 同目录 ballance_mc_camera.dat |
| 顺序 | FrameId 发布，acquire/release | **seqlock**（Seq 前后各读一次，不相等就重试） |

布局在 MpCamera.h（Player 侧）与 tools/passthrough/mpcamera.h（工具侧，镜像）里，Java 端
McCampaChannel 按偏移读，tools/passthrough/inspect-camera.py 是独立第三方读者。

字段：Eye/Forward/Up（Ballance 单位）、FovY（弧度）、Aspect/Near/Far、Ball（球心，单位）、
UnitsPerBlock、Frame、TimeNs，外加 Flags（VALID/BALL/PAUSED）。

## 二、三个必须写下来的坑

### 1. --level 拿不到游戏镜头

BALLANCE_POC_ISOLATE=1 + --level 跑的是「关卡在菜单后面」，引擎每帧都被菜单重新挂
Cam_MenuLevel，那是一台绕着关卡中心飞的展示相机（实测 eye 恒定 y=40、半径约 100u）。
关卡脚本确实跑了（球会滚），但**游戏相机 InGameCam 在场却不被挂上**。

真实入口（菜单里点 Start → 点第 1 关）才会挂 InGameCam。两者实测对比：

| 入口 | 挂上的相机 | fov | 球 |
|---|---|---|---|
| --level + ISOLATE + START_LEVEL | Cam_MenuLevel | 54.4° | I_Ball_Stone |
| 菜单 Start → 关卡 1 | InGameCam | 58.0° | Ball_Wood |

所以验收脚本要走菜单：tools/passthrough/enter-level.sh。

### 2. 球的名字取决于入口

- 隔离入口给的是交互球 I_Ball_Stone；
- 真实入口给的是渲染模型 Ball_Wood（另有 Ball_Stone / Ball_Paper 停在原点），
  旁边还有 Ball_LightningSphere、Ball_Paper_piece* 这些同样「像球」的对象。

按名字硬编一个必然错一半，所以改成**打分**：I_Ball* 优先，其次精确的
Ball_Wood/Stone/Paper，再看包围盒是不是 4×4×4，最后看它离视线射线多近
（停在原点的两个假球就是这样被排掉的）。

### 3. 本机的无头 MC 进不了世界

这不是 M1 的代码问题，是环境：

- --args 传不进 runClient：loom 把程序参数写进 argfile，Gradle 的 --args 被丢掉，
  游戏日志里是 Completely ignored arguments: [...]。已在 build.gradle 里加了
  BALLANCE_MC_QUICKPLAY 钩子（仍走 loom 的 argfile）。
- 没有窗口管理器时窗口拿不到焦点，合成的点击进不了游戏（xdotool windowfocus 直接
  BadWindow）。用 BALLANCE_MC_WORLD=<存档> 走 WorldOpenFlows 也不成：客户端停在标题
  界面、15 fps、Camera.update 不跑，mixin 自然一次都没触发。

**所以「MC 客户端里的应用」这一段这轮没有实拍证据**，只有编译通过 + 逻辑走查。
Ballance 侧与 Java 读端是实测过的（见下）。

顺带记一条：xdotool windowmove 之后那个 MC 客户端的渲染线程会卡死（日志停住、进程仍
400% CPU），移动窗口这条别再试。

## 三、坐标映射

- 比例：**1 MC 方块 = 4.0 Ballance 单位**（docs/scale-calibration.md，球直径正好一格）。
- 轴：两边都是 Y 向上；Virtools 左手（+Z 向前），MC 右手，所以 **Z 取反**，
  这样「向前」在两边都还是向前。
- 朝向：不假设 yaw 约定，由向量反算 —— yaw = atan2(-dx, dz)、pitch = -asin(dy)
  （MC 的 pitch 正数是低头）。
- 原点：两套世界没有公共坐标，第一次采样时把**球心**定到锚点
  BALLANCE_MC_ANCHOR（默认 0,100,0），之后一切按相对位置走。不做这一步，第 1 关的
  z = -153 会把世界甩到几百格之外。

## 四、开关

| 变量 | 作用 |
|---|---|
| BALLANCE_MC_CAMERA=1 | Ballance 侧开始发布（默认关） |
| BALLANCE_MC_CAMERA_SHM=名字 | 共享内存名，默认 ballance_mc_camera |
| BALLANCE_MC_CAMERA_PATH=绝对路径 | 整条路径，排查用 |
| BALLANCE_MC_CAMERA_LIST=1 | 每 2 秒把场上所有相机和「像球」的对象打到 stderr |
| BALLANCE_MC_CAMERA=0（MC 侧） | 关掉客户端应用 |
| BALLANCE_MC_ANCHOR=x,y,z | 球在 MC 世界里落在哪，默认 0,100,0 |
| BALLANCE_MC_WORLD=<存档> | 无人值守时自动开世界（见坑 3） |

## 五、实测（2026-11-05，Xvfb :99，菜单入口，关卡 01）

Ballance 侧日志：

    [mc-camera] camera=InGameCam eye=(32.1 52.7 -153.1) dir=(+0.53 -0.85 -0.00) fov=58.0 deg 640x480 ball=(54.1 17.7 -153.1)

Java 读端（CamProbe，直接用 McCampaChannel + McCampaMapper，不经过 MC）：

    seq=2432 valid=true ball=true eyeBlocks=(-5.50, 108.75, -0.00) ballBlocks=(0.00, 100.00, 0.00)
              yaw=-90.00 pitch=57.85 fovDeg=58.00 aspect=1.333 unitsPerBlock=4.00

对得上：(32.1-54.1, 52.7-17.7)/4 = (-5.5, +8.75)，锚点 (0,100,0) 加偏移即
(-5.5, 108.75, 0)；atan2(-0.53, 0) = -90°，asin(0.8466) = 57.85°。

## 六、还没做的

1. **MC 里真的看到**（本轮缺）：需要一台能正常进世界的客户端。
2. 标定世界（超平坦 + 已知坐标彩色柱）+ 投影比对：把「准不准」变成数字，而不是「看起来对」。
3. 用真实游戏镜头跑一遍「球滚、世界跟」：现在镜头在起步点是静止的（没有输入，球不动）。
4. 画幅：Ballance 窗口 640x480、MC 854x480，两边 aspect 不同，纵向 FOV 一致但横向会差；
   要么把两边调成同尺寸，要么在 MC 侧按 Ballance 的 aspect 重建投影。
