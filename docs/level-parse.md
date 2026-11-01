# Level 解析：已跑通（2026-11-01 晚）

## 结论

**13 关 + PH 物理件 + Balls/Camera/Levelinit 全部能在 Linux 上离线读出三角。**
走的是 **libcmo21**，不是 Ballanced 的 CK2——CK2 只有容器层，看不到 3D 类（见文末「走过的弯路」）。

| 产出 | 命令 | 结果 |
|---|---|---|
| `tools/nmo_geom` | `tools/build-nmo-geom.sh` | 读单个 .nmo，导出世界空间 OBJ + JSON 清单 |
| 13 关几何 | `tools/export-levels.sh` | **472,534 三角 / 609,578 顶点**，`dropped_faces=0` |
| 尺度标定 | `tools/calibrate.py` | [docs/scale-calibration.md](scale-calibration.md) |

Level_01：128 个带网格实体、33,743 面，包围盒 1893 x 436 x 1874 u。
最大的 Level_13：85,385 顶点、86,676 面。

## 五个必踩的坑（都已在 `tools/build-nmo-geom.sh` 里钉成参数，别再踩一次）

| 坑 | 现象 | 解法 |
|---|---|---|
| 没调 `CKStartUp()` | `CreateObject` 全返回 null，表面看像「类没实现」 | 先 `CKStartUp()`，再建 `CKContext` |
| 没设编码 | `CKFileReader::DeepLoad` 直接返回 `CKERR_CANCELLED` | `ctx.SetEncoding({u8"GBK", u8"windows-1252"})` |
| 编译参数不齐 | yycc 的 detector 头直接 `#error`；`char8_t` / `u8string` 在 C++20 下编不过 | `-std=c++23` 加 libcmo21 自己 ninja 的那一整串 define（OS / 编译器 / 字节序 / 指针宽度 / charconv 探测） |
| 类型名找不到 | `CKDWORD` / `VxMath::` 报未声明 | 它们在 `LibCmo::` 而不是 `LibCmo::CK2::`，两个 using 都要有 |
| `CKShutdown()` 时机 | 抛 `LibCmo::LogicException: Invalid CK_CLASSID` | `CKShutdown()` 必须在 `CKContext` 析构**之后**，所以主体要单独拆一个函数 |

## 几何引用是已解析的，不用自己补

`CKFileReader::DeepLoad` 是两遍：先给文件里每个对象 `CreateObject`，再逐个 `Load()`。
所以 `CK3dEntity::Load` 里 `chunk->ReadObjectPointer(pendingMesh)` 拿到的是真指针，
`GetCurrentMesh()` 直接可用。之前以为的「引用没解析、要自己读 state chunk」不存在。

## 矩阵约定（已在 2145 个实体上逐一验证）

Virtools 把平移放在**第 4 行**、第 4 列恒为 `(0,0,0,1)`，因此只能按行向量变换：

```
x' = x*M[0][0] + y*M[1][0] + z*M[2][0] + M[3][0]   （y、z 同理）
```

`tools/nmo_geom` 每次运行都会打印 `matrix column 3 == (0,0,0,1): N/N entities`，
不是 N/N 就说明这批文件不能这么读，导出结果不可信。`--col` 参数是反证用的对照
（它把第 4 列当平移，而第 4 列恒为 0，等于把平移全丢掉，几何会堆在原点附近）。

形状自检：Level_01 的可走面俯视足迹是一条**连续蜿蜒的走廊**（不是散落的点云），
说明变换与实例化都对。

## 用法

```bash
# 1) 建 libcmo21（一次性）
cmake -S "$HOME/src/libcmo21" -B /tmp/libcmo21-build -G Ninja \
      -DYYCCommonplace_DIR="$HOME/src/yyc-install/lib/cmake/YYCCommonplace"
ninja -C /tmp/libcmo21-build LibCmo

# 2) 建工具
tools/build-nmo-geom.sh /tmp/nmo_geom

# 3) 导一关看形状（Blender 打开 /tmp/l01.obj 即可验收）
/tmp/nmo_geom "$HOME/src/ballance-game/3D Entities/Level/Level_01.NMO" \
              --obj /tmp/l01.obj --json /tmp/l01.json

# 4) 全量导出 + 标定表
tools/export-levels.sh /tmp/nmo_geom "$HOME/src/ballance-game" /tmp/nmg
tools/calibrate.py --data /tmp/nmg > docs/scale-calibration.md
```

`/tmp` 是 tmpfs：`/tmp/libcmo21-build` 与 `/tmp/nmg` 重启即失，重跑上面 1、2、4 三步即可重建
（libcmo21 源码在 `~/src/libcmo21`，yycc 在 `~/src/yyc-install`）。

## 还没做的

- **贴图与材质槽**：只导了三角与矩阵，`CKMesh::GetMaterialSlots` / UV / 法线都没出。M2 深度合成要采样贴图，得先补 UV。
- **PH 件的物理参数**：只量了包围盒尺寸，`physics_RT.dll` 里的质量 / 摩擦 / 回弹仍要从原厂二进制里取。
- **实体层级**：`CK3dEntity` 的 parent 与 CKPlace 在 libcmo21 里被显式丢弃（源码注释写明），拿不到「谁挂在谁下面」。目前平铺展开几何不需要，做动画时要补。
- **场景图与动画**：`AnimTrafo.nmo` 有 6 个网格实体，keyframe 片段没解析。

## 走过的弯路（存档，别重走）

- **Ballanced 的 CK2 单独建**：`CKFile::Load` 读容器 100% 正常（Virtools "Nemo Fi"、对象表、类 ID 都对），
  但 `Source/CK2/src` 里根本没有 `CK3dObject` / `CKMesh` / `CK3dEntity` 的实现，
  所以 `GetMeshCount()` 恒为 0，cid 直方图里没有 33/41/32。
  `tools/nmo_dump.cpp` 是这个阶段的产物，现在只作容器层探针保留，**不要用它取几何**。
- 3D 类层其实在 `CKRenderEngine` 里（`~/src/CKRenderEngine`），要连它一起建才有几何；
  但那条路比 libcmo21 重得多，而 libcmo21 已经把 13 关的三角全给出来了，所以不走。
