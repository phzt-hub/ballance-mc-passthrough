# Ballanced submodule patches

Ballanced (`~/src/Ballanced`) builds on three **git submodules** pinned to upstream
commits:

| Submodule | Upstream | Patch here |
|---|---|---|
| `Source/Player` | `github.com/doyaGu/Player` | `Player.patch` |
| `Source/BuildingBlocks` | `github.com/doyaGu/BuildingBlocks` | `BuildingBlocks.patch` |
| `Source/RenderEngine` | `github.com/doyaGu/RenderEngine` | `RenderEngine.patch` |
| `Source/Managers/SdlInputManager` | `github.com/doyaGu/SdlInputManager` | `SdlInputManager.patch` |

Anything we change inside those submodules is therefore **not committed anywhere** by
the Ballanced repository itself: `git submodule update` restores the pinned commit
and silently deletes the work. These patches are the durable record, and
`apply.sh` puts it back.

## Apply

```sh
# after any `git submodule update`
tools/ballanced-patches/apply.sh
```

The script checks each submodule is still on the commit the patch was generated from
before applying, and uses `git apply --check` first, so it is safe to run twice:
an already-applied patch is reported as `already ...` and skipped, and a submodule
on an unexpected commit is skipped rather than patched onto the wrong base.

## What each patch contains

### Player.patch
`--level <file>` plus `CGamePlayer::LoadLevel()`. A Ballance level `.NMO` carries no
`CKLevel` of its own -- the level object, managers and gameplay scripts live in
`base.cmo`, and the original player **merges** a level into that composition.
Ballanced's `CGamePlayer::Load()` resets the context instead, so loading a level
through it always ended in `Failed to retrieve the level!`. The hook merges the
level into the current context, registers the merged render objects by hand, and
pins the view/projection matrices per frame (`Render()`) because the composition
re-attaches its own camera on every `LaunchScene`.
Also carries the PoC frame-log scaffolding: `BALLANCE_POC_FRAMELOG`,
`BALLANCE_POC_BBOX`, `BALLANCE_POC_ISOLATE`, `BALLANCE_POC_CAM`.

Also carries the in-game settings panel: `MinecraftSettings.*` (the nine rows, the
`Bin/Minecraft.ini` they are written to, and the environment override that wins over it),
`MinecraftOverlay.*` (a self-drawn F1 overlay using the render context's post-sprite
callback and the same 2D primitives as `BuildingBlocks/Interface/DrawingPrimitives.cpp`),
and `MinecraftFontAtlas.h` (a baked ASCII atlas; `tools/panel/make-font-atlas.py` regenerates it).
See `docs/settings-panel.md`, including why the panel freezes the world with a zero time
scale rather than `CKContext::Pause()`.

### BuildingBlocks.patch
`Poc2Obstacles.cpp/.h` -- the physics obstacle harness hooked into
`CKIpionManager::PostProcess()` via `Poc2BeforeFrame()` / `Poc2AfterFrame()`, plus the
`CMakeLists.txt` entry.

### RenderEngine.patch
`CKSdlGpuRasterizerDepthDump.cpp` -- dumps the depth buffer of a submitted frame
when `BALLANCE_POC1_DEPTH_FRAMES` is set, hooked into
`CKSdlGpuRasterizerContext::Submit()`.

`CKFFPresentDraw::Prepare` / `CKSdlGpuPresentStage::SubmitTexture` -- an `AlphaBlend`
flag on the fullscreen present draw. `SubmitCopy` (the passthrough frame) sets it, so the
quad composites source-alpha over the destination instead of replacing it; without it the
Minecraft frame painted over the whole Ballance scene with one flat colour. Resolve and blit
pass `FALSE` and stay opaque.

### SdlInputManager.patch
Two port fixes. `Cursor.cpp`: `KeepSystemCursorVisible()` pins
`ShowCursor()` to TRUE (`BALLANCE_SPRITE_CURSOR=1` restores the original
behaviour) -- this port cannot draw the game's own sprite cursor, and the push-button
building block refuses to arm while the cursor reports invisible, so the whole Ballance
menu becomes unclickable. `SdlInputManager.cpp`: `BALLANCE_INPUT_DEBUG=1` prints
one line per pointer/window event *before* the window and pause filters, so "the event
never arrived" and "it arrived and was dropped" stop looking the same from outside.

## Regenerating a patch
Edit the submodule, then re-export so the patch matches the new working tree:

```sh
cd ~/src/Ballanced
git -C Source/Player add -N . && git -C Source/Player diff > /tmp/Player.patch && git -C Source/Player reset -q
```

and update `base_commit:` in `MANIFEST.txt` to `git -C Source/Player rev-parse HEAD`
*before* applying anything else. `apply.sh` refuses to touch a submodule whose HEAD
does not match the manifest.

## Verification
Each patch was generated from the working tree and re-applied to a clean worktree of
its own `base_commit`; the resulting tree is byte-identical to the dirty tree
(`git status --porcelain` matches for all tracked and untracked files).
`Source/BuildingBlocks/3DTrans/qhull` is a gitlink submodule whose vendored files are
untracked and were excluded from that comparison.

```
Player.patch          base e5073295 15 files  5073 lines
BuildingBlocks.patch  base 5b833b92  4 files  790 lines
RenderEngine.patch    base 049c78b3 15 files  1091 lines
SdlInputManager.patch base 368d4143  2 files   74 lines
```