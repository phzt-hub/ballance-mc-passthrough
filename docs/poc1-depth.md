# PoC-1：把深度从渲染器里拿出来

日期：2026-11-01 · 环境：本机 192.168.3.59（Linux x64，Xvfb :99）
上游：Ballanced `ececf94`（sdl 分支）、SDL3 **3.4.18**、Renderer = SDL3 GPU（Vulkan）

## 一、结论

| 问题 | 判定 | 证据 |
|---|---|---|
| SDL3 GPU 能不能下载**深度**纹理 | ✅ 能 | `probe_depth.cpp`：D24S8 与 D32F 都成功，clear 值逐位对上 |
| Ballanced 能不能在本机编出来 | ✅ 能 | 1354 个目标，`linux-x64-runtime-stage-release` 出全套 stage |
| 无窗口环境能不能跑 | ✅ 能 | Xvfb :99 + Vulkan（llvmpipe 软件光栅），约 44 fps @640×480 |
| 深度是不是**真实几何**（不是清屏值） | ✅ 是 | 一帧 7.4 万个不同深度值、31% 像素在 1.0 以内、与颜色图边缘相关 +0.43 |
| 深度图的行序/朝向 | ✅ 已知，无需翻转 | 正相关 +0.43 vs 翻转 +0.02 |
| 进到**关卡**里抓球道深度 | ⏳ 未完成 | 见第四节；需要往窗口注入输入 |

## 二、两个只有实测才知道的细节

### 1. D24S8 的位序：深度在**低**三字节

`probe_depth.cpp` 把一个已知值清进深度纹理再下载（不画任何东西，不需要着色器）：

| 清屏值 | 下载回来的 4 字节 | 解释 |
|---|---|---|
| 1.00 | `ff ff ff 00` | u32 = 0x00FFFFFF → 低 24 位 = 0xFFFFFF |
| 0.75 | `ff ff bf 00` | 低 24 位 = 0xBFFFFF = 0.75 × 0xFFFFFF ✅ |
| 0.25 | `00 00 40 00` | 低 24 位 = 0x400000 ✅ |

即 **depth = u32 & 0xFFFFFF，除以 16777215**，第 4 字节未用。最初按 `u32 >> 8` 解码，结果整幅图只有 0.002～0.0039 —— 因为真实值被挤掉了 8 位。这正是把「原始字节」当唯一真相、解码放在 Python 里的理由：改一行脚本重跑，不用重新编译 20 分钟。

### 2. 实际下载到的是 **native target**，不是 scene target

日志里没有 `scene-depth`，只有 `native-depth`：本机 `RenderScale=1`、无 FXAA/锐化时，resolve 是恒等操作，渲染器直接把场景画进单采样的 native target（`m_FrameState.SceneUsesNative`）。所以：

- 单采样 ⇒ 深度能直接下载（MSAA 纹理在 Vulkan 里根本不允许 copy，这是可行性文档里标红的那条坑）；
- 一旦开 MSAA / RenderScale≠1，深度就在**多采样**的 scene target 里，必须先 resolve 到单采样再下载 —— 这条**还没验**。

## 三、实测数据

`FRAMES=400,900,1500 ./run-poc1.sh`，640×480：

| 帧 | 内容 | 深度 distinct | depth<1 覆盖 | 与颜色边缘相关 |
|---|---|---|---|---|
| 600 | 启动中（几乎空白） | 2 | 0.12 | — |
| 1200 | 菜单/开场 3D 场景 | 72,468 | 0.32 | +0.43 |
| 1500 | 同上（静态机位） | 74,174 | 0.31 | +0.43 |

`inspect_dump.py` 的 ASCII 预览里，深度图是一个占据画面中下部、由近到远连续过渡的几何体，边缘与颜色图的物体轮廓对得上 —— 这就是「近距离亮、远处暗」的球道深度雏形。

## 四、还没打通：进关卡

`SkipOpening=1` 后游戏稳定渲染同一个静态 3D 场面（无报错），但截帧里 **OCR 认不出任何文字**（tesseract eng+chi_sim 都跑过），所以还没确认它是主菜单还是开场帧。要进关卡得往窗口注入输入：

- 本机**没有 xdotool**，但有 `tesseract`、有 `sudo` ⇒ `apt install xdotool` 就能点；
- 或者改走「读 Database.tdb 直接进关」的老路子。

这是下一步唯一卡点，不是渲染侧的卡点。

## 五、怎么复现

    tools/poc1/build-ballanced.sh          # SDL3 3.4.18 + Ballanced（首次约 6 分钟）
    tools/poc1/apply-depth-hook.py         # 把 dump 钩子打进 RenderEngine 子模块
    FRAMES=600,1200 ./tools/poc1/run-poc1.sh
    python3 tools/poc1/inspect_dump.py /tmp/poc1/out 1200

钩子默认关闭，只有设了 `BALLANCE_POC1_DEPTH_FRAMES` 才动作；输出是 `<label>-<frame>.bin` + `.json`（原始行、无 padding），解码在 `decode_depth.py` / `inspect_dump.py`。

## 六、环境要求（踩过的坑）

1. **SDL3 必须 ≥ 3.4.8**。Debian 只有 3.2.10，`find_package(SDL3 3.4.8)` 直接拒；脚本自己下 3.4.18 编进 `~/.local/opt`。
2. 本机缺 `libxtst` 头 ⇒ SDL3 配置时 `-DSDL_X11_XTEST=OFF`。
3. 游戏资产从原版安装借（`3D Entities` / `Textures` / `Sounds` / `Text` / `help` / `base.cmo` / `Database.tdb` 软链），**绝不能**用原版的 32 位 DLL 目录 —— stage 自己的 `.so` 才是对的。
4. 钩子改的是 **RenderEngine 子模块**（未提交），`git submodule update` 会被冲掉，重跑 `apply-depth-hook.py`。
