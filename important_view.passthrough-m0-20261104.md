# 线二 Ballance↔MC 穿透 · M0（宿主侧）— 2026-11-04 19:0x

用户上一轮看到"一直在修宿主"后问「我不是要做 ballance 和 mc 的交互吗？现在是在干嘛？」，
我摊开清单后用户选 **1 = M0 链路（先把 MC 的画面整屏贴进 Ballance 窗口）**。
本轮只做**宿主侧半条链路**，用**合成生产者**替 MC，先把协议、消费端、验收全打通。
**未改 MC 任何代码；MC 客户端 mod 还没写。**

## 一句话
共享内存帧邮箱 + SDL_GPU overlay 贴片**跑通了**：合成生产者 60fps 发布，Ballance 每帧消费并
全屏贴出（1400 帧 1400 次 draw，0 拒绝），画面 96–98% 像素与"未开启"不同，帧率**无可见损失**
（54.5 → 54.5 fps），动图两帧差 82.9%。

## 实测（Xvfb :99，base.cmo 菜单，640x480）
| 项 | 数值 |
|---|---|
| 生产者→消费者 | frames=1400 / draws=1400 / misses=0 / rejected=0 |
| 贴片覆盖 | 与关闭时相比 **96.3% / 97.8%** 像素改变 |
| 图案吻合（静态帧，全画面） | 63.9%（四角/边缘 78.5%，中心 20% —— 菜单 UI 盖在上面） |
| 四角标记 | 左上白、右上蓝、左下红 = 与生产者逐字节一致，**方向正确无翻转** |
| 动图活性 | 两帧（间隔 1s）**82.9%** 像素变化 |
| 帧率 | off 54.5 fps / static 52.8 / anim 54.5 → **无可见损失**（320x240 源） |

## 协议（tools/passthrough/mpframe.h）
```
[MpFrameHeader 4096B][slot0][slot1][slot2]   每槽 BGRA8, Stride*Height
Magic='MFP0' Version=1; 生产者写槽 → 发布 PublishedSlot → __atomic 发布 FrameId
消费者 acquire 读 FrameId → 校验 → LoadTexture 整帧上传 → SubmitCopy 全屏贴
```
三槽是为了让"慢三帧内"的消费者不被撕裂；更深缓冲 = 协议版本号升级。

## 宿主侧实现（Ballanced 源码，补丁）
- 新文件（引擎内）：`Source/RenderEngine/src/CKRasterizer/CKSdlGpuRasterizer/Rasterizer/`
  `mpframe.h`、`CKSdlGpuPassthrough.h`、`CKSdlGpuRasterizerPassthrough.cpp`
- 挂钩点：`CKSdlGpuRasterizerContext::BeginOverlayPhase()` 末尾（pass 已开、场景已 resolve、HUD 之前）
  → 贴片在场景之上、游戏 2D 界面之下
- `BALLANCE_MC_PASSTHROUGH=1` 开，`BALLANCE_MC_SHM` 换邮箱名（默认 /ballance_mc_frame）
- 一条脚本装/卸：`tools/passthrough/apply-passthrough-hook.py [--revert]`（幂等，改 4 处 + 拷 3 文件）

## 落盘文件
| 文件 | 作用 |
|---|---|
| tools/passthrough/mpframe.h | 帧协议（C/C++ 共用 POD） |
| tools/passthrough/shmfeed.c (+ 已编译 shmfeed) | 合成生产者，`--static` 供逐像素验收 |
| tools/passthrough/sdlgpu-passthrough.{h,cpp} | 宿主侧消费者（拷进引擎） |
| tools/passthrough/apply-passthrough-hook.py | 安装/卸载补丁 |
| tools/passthrough/test-m0.sh | 三档验收（off/static/anim），出图 /tmp/m0/<tag>/shot*.png |
| tools/passthrough/verify-m0.py | 复算生产者图案并逐像素比对 |

## 已知小问题
- stats 行的 fps 数值算错（span 用了累计值），显示 694/159 这类假数；帧数/绘制数是准的。下轮修。
- 校验只在 320x240 源上做过；640x480 与 1280x720 的每帧上传成本（每帧整帧 memcpy 进 transfer buffer）还没量。

## 下一轮从这里接
1. （快）修 stats 算术；跑 640x480 与 1280x720 源的帧率对比，得带宽上限。
2. **M0b：MC 客户端导出 mod** —— 用 ~/mcmod 那套 Fabric 26.3 脚手架（JDK25、`./gradlew25`），
   客户端 mixin `Window#swapBuffers` 前 `glReadPixels(GL_BGRA)` 写同一协议；出 jar 后用 Xvfb 跑。
   注意：`~/mcmod` 现状是**服务端** mod（xpconvert，零 mixin），改成客户端工程要加 client 源集。
3. 之后才轮到 M1 相机同步 / M2 深度重投影。

## 边界
- Ballanced 仍是脏工作区（levelboot 补丁 + 本轮 passthrough 补丁），未提交 git。
- 未动 128、未动新作17、未动 dsh-backup.sh。ask_user_question 在本环境挂死，不要用。

## 2026-11-05 补：闪烁已修 + 两仓库已推 gitee

- **闪烁修复**：消费者无新帧时复用上次上传的纹理重画（`redraws` 计数），不再只在
  FrameId 变化时 SubmitCopy。实测 6 张样本两两差 **0.03（>8 像素 0.05%）→ 不闪**。
- **保真**：合成图案档与生产者写入字节在左右各 64px 边带 **mean 0.00 / max 0**；
  真 MC 档边带 mean 0.41（>8 0.90%）。整帧差 mean 16.4/36.8% 差的是 Ballance 自己的 2D 菜单（符合设计）。
- **对照**：on vs off 96.3% 像素不同 → 贴片确实来自邮箱而非游戏自身场景。
- 新增 `verify-m0b.py`（闪烁/保真/对照三项判定）、`tools/passthrough/README.md`。
- 修 `inspect-mailbox.py` 的 `stats(*slot_view(...))` 少一个参数（非 --watch 时崩）。
- **已推送**：
  - ballance-mc-passthrough → gitee `1191fd8`（两个提交：levelboot 探针、passthrough M0/M0b）
  - xpconvert(mcmod) → gitee `74f092a`（客户端帧导出）
  - 注意：passthrough 的 remote 由 HTTPS 改成 SSH（HTTPS 无凭据），已 rebase 到远端 LICENSE 提交之上。
- 仍未动：Ballanced 脏工作区、128、新作17、dsh-backup.sh。

