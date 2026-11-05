# Steve 从菜单进关卡就崩 ---- 本轮结论（2026-11-05 09:5x，全部验收通过）

用户报的现象：「菜单界面有，但进 level1 卡关进不去」。一句话结论：**不是开关没开，是段错误**。

## 根因（三个事实，都有日志）

1. 菜单点击一直是好的：`BALLANCE_POC_INPUTLOG=5` 记到 `pick2d` 依次是 `M_Main_But_1` / `M_Start_But_01` / `M_Start_But_03`，光标 `vis=1`。
2. 点下第 1 关的瞬间进程**段错误**（对照：Steve 关掉时同样两次点击，3d 131 到 350、相机换 `InGameCam`、球能动）。
3. 也就是说：菜单里 Steve 追的「球」是 `I_Ball_Stone`（地图上的那颗，不是玩家），`SteveAvatar` 又挂在**菜单那一幕的 scene** 里。关卡载入把这两样连同 scene 一起销毁，指针留在手上。

## 修法：每帧 Validate()

对象释放后指针仍指着那块内存，**先解引用就已经是崩溃本身**。所以每个指针旁边存 `CK_ID`，判据是 `context->GetObject(id) == ptr`：

| 对象 | 判据失败时 |
|---|---|
| `m_Ball` | 清空 + `m_HasLastBallPosition = false`，重新找 |
| `m_Entity` | `Shutdown()`（安全版，只销毁还活着的东西）后下一帧重建 |
| `m_Camera` | 清空 |
| scene 变了 | `scene->AddObject(m_Entity)` 挂进新关卡 |

## 顺手做的三件

- `UpdateLevelState()`：按当前相机名判断在不在关卡（带 `InGame` 算关卡、带 `Menu` 算菜单，不认识的名字算关卡）。菜单里隐藏模型、不抓鼠标 —— 第一人称在菜单抓指针就点不动按钮。
- 新开关 `BALLANCE_MC_STEVE_LEVEL=0/1` 覆盖上面的判断。截图脚本跑的是「合并关卡 + 无游戏相机」，已在 8 个 `tools/steve/*.sh` 里显式写 1。
- 每秒刷屏的 `ball ... is the player` 改成只在换球时打一次。

## 验收

- 关卡内 A/B（同一球位，开/关各一帧相减）：6261 px，bbox 263x238，`shirt 614 / skin 1467 / pants 181`，皮肤行号在衬衫之上、裤子之下 -> 画出来的确实是那个模型。
- 崩溃计数 0；日志 `level state: menu` 与 `level state: in a level` 各一次，正对两次点击。
- 三套旧回归全过：`look_shots.sh` 10 项、`menu_shots.sh` 8 项、`body_shots.sh` 6 项。
- 补丁重新导出：`tools/ballanced-patches/Player.patch`（2407 行 / 7 文件 / base e5073295），干净 worktree 上 `git apply --check` 通过、应用后与活工作区一致。

## 落盘

| 文件 | 说明 |
|---|---|
| `Source/Player/src/MinecraftSteve.h / .cpp` | `Validate()` / `UpdateLevelState()` / `ObjectStillThere()` / id 成员 |
| `run/play-mc.sh`、`run/play-mc-fp.sh` | 双击启动器：第三人称 / 第一人称，变量写死在里面 |
| `docs/steve-in-ballance.md` | 新增「从菜单进关卡 / 换关卡」一节 + `BALLANCE_MC_STEVE_LEVEL` |
| `tools/steve/*.sh`（8 个） | 加 `BALLANCE_MC_STEVE_LEVEL=1` |

## 还没做

- `Player.ini` + 游戏内设置面板（用户本轮选的开关形式），目前仍是环境变量。
- 下一个内容方向：用户选 **C 画中画**（真跑一个 MC 客户端，把画面嵌进 Ballance）；视角两套都留、默认第三人称；方块材质允许用官方材质包。
- 未提交 git：Ballanced 四个子模块仍是脏工作区；passthrough 有新增文件未提交。
