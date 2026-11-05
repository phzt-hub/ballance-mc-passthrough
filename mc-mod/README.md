# Ballance Passthrough（MC 侧）

Minecraft **26.3** + Fabric Loader **0.19.5** 的**纯客户端** mod：把每帧画面写进一块
共享内存邮箱，供 Ballance 侧的穿透渲染逐帧取用。

mod id `ballance_passthrough`，包 `com.example.ballance.passthrough`，产物
`build/libs/ballance_passthrough-1.0.0.jar`。没有任何游戏逻辑改动，只有
`Minecraft#renderFrame` 末尾一个 mixin。

## 代码

| 文件 | 作用 |
|---|---|
| `src/client/java/.../PassthroughClient.java` | 客户端入口，启动时把邮箱建好（读环境变量） |
| `src/client/java/.../McFrameExport.java` | 邮箱生产者：主渲染目标走 MC 自己的截图回读路径，转 BGRA8 后发布；三槽轮转 + `FrameId` 原子递增 |
| `src/client/java/.../mixin/MinecraftFrameMixin.java` | `renderFrame(Z)V` 的 `@At("TAIL")` |
| 对端 | 邮箱布局见本仓库 `tools/passthrough/mpframe.h`，Ballance 侧 `tools/passthrough/sdlgpu-passthrough.cpp` |

## 环境变量

| 变量 | 作用 |
|---|---|
| `BALLANCE_MC_SHM` | 邮箱名，默认 `ballance_mc_frame`（Posix 上即 `/dev/shm/ballance_mc_frame`） |
| `BALLANCE_MC_FPS` | 发布限速，默认 30，`0` = 每帧都发 |
| `BALLANCE_MC_EXPORT=0` | 完全关闭导出 |

## 构建（本机）

```sh
./gradlew25 build      # 不要直接用 ./gradlew，原因见 gradle.properties 注释
```

环境要求：JDK 25 跑 Gradle daemon（`gradlew25` 会强制指定），源码 `release = 25`。
全局 `~/.gradle/gradle.properties` 里 pin 的 java-21 是给 Android 工程的，和本工程无关。

## License

CC0-1.0（沿用 Fabric 模板）。

