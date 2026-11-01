# PoC-2：动态 obstacle 的上限与每帧开销

日期：2026-11-01 · 环境：本机 192.168.3.59（Linux x64，Xvfb :99，llvmpipe 软光栅）
上游：Ballanced `ececf94`（sdl 分支）+ 本仓库 `tools/poc2/` 的物理钩子

## 一、结论

| 问题 | 判定 | 证据 |
|---|---|---|
| ipion 后端能扛多少动态盒碰撞体 | **没摸到硬上限**，16000 个照跑不误 | 每次都在运行中生成成功，物理对象数 16001，无报错/无崩溃 |
| 每帧物理成本怎么长 | **线性**，约 **69 ns/个/帧** | 0 → 16000 的 simulate_ms 中位数见下表 |
| 16000 个占多少帧预算 | 60fps 的 **6.7%**（1.11 / 16.7 ms） | 同上 |
| 一次性生成贵不贵 | **超线性**，16000 个要 540 ms | 4000→26.8ms，8000→131.9ms，16000→540.3ms（每翻倍 ×4~5） |
| 静止之后还花不花 | **不花**，掉到 ~0.008 ms | 4000 个盒子的场景里，全休眠帧 simulate=0.007~0.011ms，被撞醒的帧 0.32~0.34ms |

## 二、实测数据

合成场景，测 12 秒取后半段（`--unlock-framerate`，无垂直同步）：

| N（盒子数） | 生成耗时 | simulate 中位 | p95 | max | 物理对象数 |
|---|---|---|---|---|---|
| 0（只有地板+球） | — | 0.017 ms | 0.026 | 0.070 | 1 |
| 100 | 0.4 ms | 0.028 ms | 0.043 | 0.062 | 101 |
| 400 | 1.6 ms | 0.058 ms | 0.089 | 0.296 | 401 |
| 1000 | 3.7 ms | 0.106 ms | 0.165 | 0.266 | 1001 |
| 2000 | 10.7 ms | 0.202 ms | 0.303 | 0.415 | 2001 |
| 4000 | 26.8 ms | 0.325 ms | 0.476 | 0.646 | 4001 |
| 8000 | 131.9 ms | 0.572 ms | 0.755 | 0.995 | 8001 |
| 16000 | 540.3 ms | 1.110 ms | 1.302 | 2.069 | 16001 |

边际成本 (1.110-0.017)/16000 = **68 ns/盒/帧**；1000 以内还要背 ~0.015ms 的固定开销。

## 三、场景是怎么造的

钩子挂在 `CKIpionManager::PostProcess()`（包住 `Simulate()` 计时），场景在 `Source/BuildingBlocks/physics_RT/Poc2Obstacles.cpp`：

1. `CreateEnvironment()`（菜单态没有物理环境，这方法是 public）；重力 −9.81 由它自己设。
2. 地板：一块 240×20×240 的盒子，`fixed=TRUE`，碰撞面名 `poc2-floor`（`AddConvexSurface` 把 8 个顶点转成凸包 ledge）。
3. 球：`CreatePhysicsBall(mass=1, radius=2.0u)`，从 y=100 落下。**半径 2.0u 沿用 `docs/scale-calibration.md` 的标定（1 MC 方块 = 4.0u）**。
4. N 个 4u 盒子：地面网格，间距 8u，**共用一个 CKMesh + 一个碰撞面名 `poc2-synthbox`** —— 这正是 MC 方块该有的做法（同尺寸共享几何，不 per-block 建面）。
5. 每帧记 `simulate_ms`（`Simulate()` 的墙钟）、`frame_ms`（到上一帧的间隔）、物理对象数。

球是真的在动，不是摆设：日志里 `movable0 core=0.00,7.26,0.00 speed=14.6` → `core=0.00,17.75,0.00 speed=0.12` → 又落回 `13.25`，即**砸在地板/盒子上弹跳**，说明窄相位确实在跑。

## 四、五个坑（都已验证）

1. **`CreatePhysicsBall()` 不登记 entity↔physics 映射。** 直接调它，`m_PhysicsObjects` 表里没有这条，`UpdateObjectWorldMatrix` 拿不到 `client_data`，于是 **CK3dEntity 的位置永远停在创建值**（实测一直 y=100）。补 `GetPhysicsObject(ent, TRUE)` 也没让它跟上——要看位置就读 IVP core（`get_core()->get_position_PSI()`）。走 `CreatePhysicsObjectOnParameters()` 的盒子则正常登记（100 个盒子 → 对象数 101）。
2. **生成成本超线性**（每翻倍 ×4~5）：16000 个要 540 ms，一次性生成会明显卡一帧。MC 侧必须**分帧生成**（每帧几百个）。
3. **静止即休眠**：物体睡着以后每帧几乎零成本，成本是「被撞醒的对象数」而不是「总数」。所以真正要盯的是**球周围被唤醒的盒子数**，不是全图方块数。
4. **`frame_ms` 列不能当渲染开销看**：渲染是 llvmpipe 软光栅，热身的 8~10ms 几乎全是它。要测渲染得先按 `VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/intel_icd.json` 走硬件 Vulkan（本机是 Intel UHD 730），这条**还没验**。
5. **进不了真关卡**：本机跑起来停在主 level（`level=Level`，物理对象 0），`xdotool` 点击打不进游戏，脚本激活 `Menu_Start` 也无效——所以这份数据是**合成场景**，不是 `Level_01` 真球道。真球道的差别是：静态几何是凹面 ledge soup（三角形多得多），球道本身也要参与窄相位。

## 五、复现

```bash
export PATH=$HOME/.local/bin:$PATH      # cmake/ninja 只在 ~/.local/bin
tools/poc2/apply-physics-hook.py        # 把钩子打进 physics_RT（幂等，改完要重新 build）
cd ~/src/Ballanced && cmake --build --preset linux-x64-runtime-stage-release -j8
tools/poc2/run-poc2.sh 0 100 1000 4000  # 逐个 N 跑，CSV 落 /tmp/poc2/physics-sweep<N>.csv
```

环境变量见 `tools/poc2/poc2-obstacles.cpp` 顶部注释；`BALLANCE_POC2_SYNTH=N` 是「合成场景 + N 个盒子」，不设就是纯观测（只记 CSV）。

## 六、还没做的

1. 真球道上的同一组数字（要先解决「进关卡」，见坑 5）。
2. 硬件 Vulkan（Intel ANV）下的渲染开销，把 `frame_ms` 变成有意义的数。
3. **移动的盒子**（MC 里 TNT 炸掉方块就是要动态删/加）：目前只测了静态；`RemoveObstacle` 一侧的成本没量。
4. 与 CK 层脚本的交互成本：4000 个实体在 `CKContext::Process` 里的遍历开销（frame 7.96→10.29ms 那 2.3ms 是什么，没拆开）。
