# Windows 构建（真机试玩用）

这台 Linux 机器是开发机：所有验收（xdotool 点击、抓帧统计、ini A/B）都在这里无头跑。
**Windows 那台（`Administrator@10.0.0.4`）只用来"人自己玩"** —— 双击 `D:\bmc-play\Bin\Player.exe`，
用真键鼠试 F1 面板和 Steve。两条线共用同一份源码。

## 环境（2026-11-05 重建过一次，全部可复现）

| 组件 | 版本 | 位置 |
|---|---|---|
| MSVC | VS2019 BuildTools 14.29.30133（x64） | `C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools` |
| CMake | 3.31.6 windows-x86_64 | `D:\bmc-build\tools\cmake-3.31.6-windows-x86_64\bin` |
| Ninja | 1.12.1 win | `D:\bmc-build\tools\ninja\ninja.exe` |
| SDL3 | 3.4.20 devel-VC | `D:\bmc-build\sdl3\SDL3-3.4.20` |
| 源码 | 本仓库 `~/src/Ballanced` 的 tar 快照 | `D:\bmc-build\Ballanced` |
| 安装前缀 | —— | `D:\bmc-build\stage` |
| 可玩目录 | —— | `D:\bmc-play` |

cmake / ninja / SDL3 三个 zip 来自 GitHub Releases，**在本机下载后 scp 过去**（不在 Windows 上下载）。

## 三步

```bat
:: D:\bmc-build\configure.bat
call "C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
set "PATH=D:\bmc-build\tools\ninja;D:\bmc-build\tools\cmake-3.31.6-windows-x86_64\bin;%PATH%"
cmake -S D:\bmc-build\Ballanced -B D:\bmc-build\Ballanced\build\ninja-msvc -G Ninja ^
  -DCMAKE_BUILD_TYPE=Release -DBALLANCE_BUILD_STATIC=OFF ^
  -DSDL3_DIR=D:/bmc-build/sdl3/SDL3-3.4.20/cmake ^
  -DCMAKE_INSTALL_PREFIX=D:/bmc-build/stage
```

```bat
:: D:\bmc-build\build.bat —— 编 + 装
cmake --build D:\bmc-build\Ballanced\build\ninja-msvc -- -j 10
cmake --install D:\bmc-build\Ballanced\build\ninja-msvc
```

产物：`stage\{Bin,BuildingBlocks,Managers,Plugins,RenderEngines}`，其中 `Bin\Player.exe`、
`CK2.dll`、`VxMath.dll`、`SDL3.dll`、`Bin\data\fonts`。

**铺可玩目录** = stage 的五个目录 + 零售资产。资产从 `D:\Ballance` 复制这七项：
`3D Entities`、`Textures`、`Sounds`、`Text`、`help`、`base.cmo`、`Database.tdb`
（**不要**复制零售的 `Bin`：里面是原版 DLL，我们的构建会顶掉它们）。
最后把 `Bin\Player.ini` 的 `SkipOpening` 改成 1，否则开场要等一分钟。

## 三个坑（都踩过）

### 1. 从 ssh 会话里起构建 → 会话一关，构建就被杀

`ssh win 'cmd /c build.bat'` 或 `Start-Process` 都挂在本次 ssh 会话的作业对象下，
调用返回时**整棵进程树被终止**（实测日志停在 `[60/1398]` 就没动静了，cl.exe 全消失）。
改成计划任务跑，脱离会话：

```bat
schtasks /create /tn bmc-build /tr "D:\bmc-build\run.bat" /sc once /st 23:59 /ru SYSTEM /rl HIGHEST /f
schtasks /run /tn bmc-build
```

（`/ru SYSTEM` 免密码；先把 `build.bat` 的输出重定向写进 `run.bat`。）

### 2. 穿透主机侧是 POSIX 专用，MSVC 编不过

`CKSdlGpuRasterizerPassthrough.cpp` 用的是 `shm_open` / `mmap` / `__atomic_load_n`，
MSVC 下直接 `fatal error C1083: 无法打开包括文件: "sys/mman.h"`。
处理：在这个文件里加 `#if defined(_WIN32)` 分支，把整个主体换成 **返回 FALSE 的空实现**
（符号必须留着，渲染路径会调 `CKSdlGpuPassthroughTick`，删了会链接失败）。
生成源同步改在 `tools/passthrough/sdlgpu-passthrough.cpp`，两份逐字节相同。

**后果说清楚**：Windows 上 `BALLANCE_MC_PASSTHROUGH` 是空转 —— "MC 画面贴进 Ballance" 那条
（M0b）在 Windows 不生效。Steve、F1 面板、碰撞体这些 Ballance 侧的东西完全不受影响。

### 3. Windows 上判不了"能不能玩"

ssh 上去起的进程落在非交互会话，没有桌面：`Player.exe` 能起来（`Player.log` 里有
`Selected rasterizer: sdlgpu (driver 0)`），但窗口建不出来，`GamePlayer::Init()` 走不到
`MinecraftSettings::Load()`，所以**`Bin\Minecraft.ini` 不会生成**。
这不是构建坏了，是验证方式的边界：**能不能玩只能靠双击**。
自动化能做到的只有二值核对：`Player.exe` 里能搜到 `[mc-panel]`/`Restore defaults`/`HideBall` 等字符串
（面板确实编进去了），以及 exe + DLL 能解析（缺 DLL 会弹框而不是写日志）。


## 刷新可玩目录（每次换源码后）

`install` 只更新 `D:\bmc-build\stage`，玩家双击的 `D:\bmc-play` 要单独铺：

```bat
for %D in (Bin BuildingBlocks Managers Plugins RenderEngines) do robocopy D:\bmc-build\stage\%D D:\bmc-play\%D /E /NFL /NDL /NJH /NJS /NP >nul
```

两件必做的收尾：

* `stage\Bin\Player.ini` 里 `SkipOpening = 0`，会覆盖掉可玩目录里的 1 —— 铺完再改回 `SkipOpening = 1`，
  否则每次启动都要等一分钟开场。
* **删掉 `D:\bmc-play\Bin\Minecraft.ini`**。它是上次试玩留下的存档，会压过新默认值
  （现在的默认是 `Hud=1 / Steve=0 / FirstPerson=1`）；删掉才会走默认，面板里再改会重新写出来。

### 加了新的源文件，一定要重跑 configure

`build.bat` 只跑 `cmake --build`。上一轮加了 `MinecraftHud.cpp` 却只编了 6 个老目标，
链接时 4 个 `CMinecraftHud` 符号未解析（`LNK2019`）——`build.ninja` 还是旧的，源文件列表里没有它。
`run2.bat`（先 `configure.bat` 再 `build.bat`，两者各自带日志）就是为这个准备的：

```bat
:: D:\bmc-build\run2.bat
call D:\bmc-build\configure.bat > D:\bmc-build\configure.log 2>&1
call D:\bmc-build\build.bat > D:\bmc-build\build.log 2>&1
```

### 一个 MSVC 才有的坑：POSIX 字符串函数

`strncasecmp` 在 MSVC 里只有 `_strnicmp`。`MinecraftHud.cpp` 里用
`#if defined(_MSC_VER)` 切到 `_strnicmp`，Linux 侧仍是 `strncasecmp`（名字统一走 `BALLANCE_STRNCASECMP`）。
同类函数（`strcasestr`、`strdup`、`mmap`）在这台机器上已经踩过两次，写 Player 侧代码时先想一下。

## 一次完整重建用了多久

从零（D:\bmc 被清空后）：下载 63 MB + scp + 解包 ~2 分钟，configure 22 秒，
1338 个 ninja 目标在 16 核上约 **6 分钟**（`-j 10`），install 数秒，铺目录 ~20 秒。
`run-game.tar.gz`（Linux 侧的 run/game 快照）和 `ballanced-src.tgz`（07:54 的旧源码快照）
**都不能直接当 Windows 输入**：前者是 `.so`，后者不含 `MinecraftOverlay/Settings` 与字体图集。
源码一律以本机 `~/src/Ballanced` 现取。
