# XP Convert

MC 26.3 + Fabric Loader 0.19.5 的纯服务端 Mod：手持玻璃瓶右键附魔台，把经验等级灌成附魔之瓶。

## 玩法

- 手持**玻璃瓶**右键**附魔台** → 消耗 1 玻璃瓶 + 背包里 1 青金石 + 1 级经验 → 得到 1 附魔之瓶，带附魔台音效
- 拿其他东西 / 空手点附魔台：照常打开附魔界面，不受影响
- 没青金石或不够 1 级：在 actionbar 提示，不吞操作

## 技术

- 零 mixin，只用 Fabric API 的 `UseBlockCallback`
- `fabric.mod.json` 标 `environment: server`，纯服务端，客户端不用装
- 产物 `build/libs/xpconvert-1.0.0.jar`（~7K），已在 Fabric Server 26.3（Loader 0.19.5）上 74 个 Mod 同服验证加载

## 客户端半边：帧导出（Ballance 画面穿透 M0b）

本仓库同时是 **Ballance ⇄ Minecraft 画面穿透**的生产端。装了本 mod 的客户端会把每帧画面
写进一块 POSIX 共享内存邮箱，另一个仓库（`ballance-mc-passthrough`）里的 Ballance 从邮箱取帧、
贴在自己的窗口上。游戏逻辑不变，只有 `Minecraft#renderFrame` 末尾加了一个 mixin。

- `src/client/java/.../McFrameExport.java` — 邮箱生产者：把主渲染目标交给 MC 自己的截图回读路径，
  转成 BGRA8 后发布；三槽轮转 + `FrameId` 原子递增，布局见对端仓库的 `tools/passthrough/mpframe.h`
- `src/client/java/.../mixin/MinecraftFrameMixin.java` — `renderFrame(Z)V` 的 `@At("TAIL")`
- `environment` 因此从 `server` 改成 `*`：服务端玩法照旧，客户端多了这个入口点

| 环境变量 | 作用 |
|---|---|
| `BALLANCE_MC_SHM` | 邮箱名，默认 `ballance_mc_frame`（即 `/dev/shm/ballance_mc_frame`） |
| `BALLANCE_MC_FPS` | 发布限速，默认 30，`0` = 每帧都发 |
| `BALLANCE_MC_EXPORT=0` | 完全关闭导出 |

```sh
./gradlew25 build
DISPLAY=:99 ./gradlew25 runClient      # Xvfb 上软渲染约 26 fps
```

## 构建（本机）

```sh
./gradlew25 build      # 不要直接用 ./gradlew，原因见 gradle.properties 注释
./gradlew25 runServer  # 联调，端口 25567（run/server.properties），eula 已同意
```

环境要求：JDK 25 跑 Gradle daemon（`gradlew25` 会强制指定），源码 `release = 25`。
全局 `~/.gradle/gradle.properties` 里 pin 的 java-21 是给 Android 工程的，和本工程无关。

## License

CC0-1.0（沿用 Fabric 模板）。
