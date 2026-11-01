# Level 解析路线核查（2026-11-01）

## 结论先说

- **CK2 单独能用，但只看得到容器层**：`CKFile::Load` 读 Ballance 的 .nmo 完全正常（Virtools "Nemo Fi" 容器、对象表、类 ID 都对），
  但 **CK2 里没有 3D 类实现**（`Source/CK2/src` 无 CK3dObject/CKMesh/CK3dEntity），所以 `GetMeshCount()` 永远是 0。
- 3D 类层（`CK2_3D` 模块）在 **CKRenderEngine**（`/home/phzt/src/CKRenderEngine`）里，需要连着它一起建。
- 因此「离线拿 Level_01 三角」有两条路：**建 Ballanced 全量**（顺带把 A″ 跑起来）或 **改用 libcmo21** 这类纯离线读写库。

## 实测记录

| 步骤 | 结果 |
|---|---|
| 解包 `【基础游戏安装】 Ballance整合包-2026.6版.exe`（NSIS，sha256 `1a549a55…`） | 390 文件 / 164 MB → `/tmp/balgame` |
| `base.cmo` / `Database.tdb` / `Sounds`(62) / `Bin/Player.exe` | 四样齐；`physics_RT.dll` 哈希仍与 BML+ 的零售版记录一致 |
| pip 装 cmake 4.4.4 + ninja 1.13.2，apt 装 `libsdl3-dev` 3.2.10 | OK |
| `git submodule update --init --recursive`（miniz / simde / stb） | OK |
| `cmake -S Source/CK2 -B /tmp/ck2build -G Ninja -DCMAKE_BUILD_TYPE=Release` | 配置通过 |
| `ninja -C /tmp/ck2build` | 94/94 通过 → `/tmp/ck2build/lib/libCK2.so` |
| `tools/nmo_dump` 读 `Level_01.NMO` | rc=0，29 个对象，**全为 CKCID_GROUP(23)**，0 个 3D 实体 |
| 读 `PH/P_Modul_01.nmo` | 206 个对象（参数/行为 IO），0 个 3D 实体 |
| 读 `Balls.nmo` | 2131 个对象，cid 直方图 `2,3,4,6,8,9,23,45,52` —— **没有 33(3DENTITY)/41(3DOBJECT)/32(MESH)** |

cid 缺失 = 这些类的对象在加载时根本没被创建，不是解析错位。

## 用法

```bash
# 1) 建 CK2（一次性）
cmake -S "$HOME/src/Ballanced/Source/CK2" -B /tmp/ck2build -G Ninja -DCMAKE_BUILD_TYPE=Release
ninja -C /tmp/ck2build

# 2) 建工具 + 出清单
tools/build-nmo-dump.sh /tmp/nmo_dump
/tmp/nmo_dump "/tmp/balgame/3D Entities/Level/Level_01.NMO" --summary
/tmp/nmo_dump "…/Level_01.NMO" --json /tmp/l01.json
```

## 下一手的两个候选

1. **建 Ballanced 全量**（Player + RenderEngine/CK2_3D + IVP）：一次解决「谁提供 3D 类」和「A″ 运行时」两件事，代价是编译量大。
2. **libcmo21**（yyc12345）：专为离线读 Virtools 文件写的库，带 CKMesh / 几何 / StateChunk，不需要渲染层，代价是另立一套代码、和 Ballanced 的类定义要各自维护。
