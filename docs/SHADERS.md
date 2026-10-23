# MMetal Shader Strategy

Goal: run Iris shader packs well on Apple Silicon. Today most packs fail or crawl
on macOS because Minecraft ships an **OpenGL backend translated over Metal**
(Apple GL is capped at 4.1 — no compute, no SSBOs), and Iris packs execute inside
that translator. This doc records the verified baseline and the staged plan to
move from "stands down gracefully" to "shaders run natively on Metal".

## 0. Verified baseline (2026-10-23, MC 26.3 / Sodium 0.9.2 / Iris 1.11.7)

- MMetal coexists with Sodium + Iris at load: no mixin conflicts, boot reports
  `Sodium=present, Iris=present`, and the present hook runs on the title screen.
- `Compat` probes `IrisApi.v0.isShaderPackInUse()` reflectively; it works.
- With a pack active, `MetalPipeline.present()` **fully stands down** (passthrough).
  The pack renders through Apple's GL translation at native resolution — shader
  packs work, but MMetal adds nothing, and packs needing GL 4.3+ still cannot run.

## 1. Phase 1 — Tiered Iris enhancement (implemented 2026-10-23)

`config.irisMode` selects what MMetal does while an Iris shader pack is actively
rendering (default `METALFX`):

| Mode | Behaviour under an active pack |
| --- | --- |
| `STAND_DOWN` | Vanilla presents the pack's frame untouched. The render scale is released (`currentScaleFactor()`), so the game renders native and the 1:1 blit fills the window. |
| `METALFX` | The pack owns the look; MMetal keeps the render scale, captures the pack's output and adds MetalFX upscale + frame interpolation. No MMetal lighting on top (no double grading). |
| `FULL` | The whole pipeline (MMetal lighting effects included) runs under the pack too — for packs without their own lighting. |

Surfaces: `config/mmetal.json` (`irisMode`), the settings screen Render tab
("With Shaders:"), and `/mmetal iris <stand_down|metalfx|full>`.

Fixed along the way: with a pack active the render scale used to stay applied
while MMetal stood down, so the world rendered into the scaled target and
vanilla's 1:1 blit left stale borders around it (seen with Complementary).

Open questions being verified in-game:
- Depth capture under packs: Iris passes consume/rebind depth; frame
  interpolation needs motion vectors derived from depth. If interpolation
  ghosts under a pack, the depth capture path needs an Iris-aware source.
- Iris has its own render-target scale; when it is < 1 MMetal must not rescale
  again (read `IrisApi` render target dims, or clamp scale to 1.0 under Iris).

## 2. Phase 2 — Metal RenderPearl backend (foundation)

`com.mojang.renderpearl.*` is a device abstraction (`GpuDevice`, `CommandEncoder`,
`RenderPass`, `GpuSurface`) with an OpenGL backend (macOS default) and a Vulkan
backend (needs MoltenVK). A native Metal backend:

- removes the GL 4.1 ceiling for the whole game, not just post-processing;
- gives Iris (and Sodium) a real Metal pipeline to target, without GL emulation;
- lets MMetal replace the IOSurface/GL blit capture with direct Metal texture
  sharing (no GL round-trip).

Scope honestly: this is the same order of effort as the Vulkan backend Mojang
ships — phase it behind a `RenderPearl` provider SPI map, keep GL default.

## 3. Phase 3 — Iris → Metal translation (on top of Phase 2)

Shader packs are GLSL + Iris extensions (custom uniforms, shadow passes, deferred
gbuffers). The realistic translation route is the MoltenVK stack:

```
pack GLSL → glslang (SPIR-V) → SPIRV-Cross → MSL → Metal pipelines
```

plus a Metal re-implementation of Iris's pass orchestration. Months of work;
do not start before Phase 2 exists, or every pass will be written twice.

## 4. Native bridge constraints (learned the hard way)

All FFM/objc_msgSend bindings must respect:

- **Struct-returning selectors**: the Linker prepends a `SegmentAllocator`
  parameter, shifting the receiver to index 1. `MetalNative.baked()` derives the
  selector slot from the handle's parameter count — never hardcode index 1
  (a selector pointer as `objc_msgSend` receiver = SIGSEGV, see RtShadowBvh).
- **`MTLResourceOptions` vs `MTLStorageMode`**: buffer allocators take resource
  options (CPU cache mode in bits 0-1, storage in bits 4-5); passing a raw
  `MTLStorageMode` puts 2 in the cache-mode field and Metal aborts
  (`Invalid cacheMode 2`). Use `MetalNative.RESOURCE_STORAGE_*`.
- Texture descriptor `storageMode` *does* take `MTLStorageMode` — the constants
  are only interchangeable when both encodings are 0 (shared).

## 5. Display palettes

`config.palette` selects the display transform (`/mmetal palette <mode>`, the
settings Render → Display tab, or `mmetal.json`):

| Palette | SDR | HDR |
| --- | --- | --- |
| `FILMIC` (default) | linear-light filmic knee | EDR (tonemap mode 2) |
| `EXTENDED` | the EDR look without EDR (fixed highlight lift) | EDR (tonemap mode 2) |
| `VIBRANT` | vanilla-like saturation + micro-contrast, no filmic compression | same grade, then EDR headroom |
| `WARM` | golden/amber tint (luma-preserving) | same tint, then EDR headroom |
| `COOL` | blue/cyan tint (luma-preserving) | same tint, then EDR headroom |
| `NATURAL` | raw passthrough | no headroom lift - HDR keeps the SDR look |
| `NEUTRAL` | raw passthrough | EDR (tonemap mode 2) |

The graded palettes (`VIBRANT`, `WARM`, `COOL`) apply their grade in every display
path (luma-preserving chroma work, `palette@148` in `EffectParams`), which keeps
the chosen look when HDR output is on. `NATURAL` is the minimal-processing choice:
no grade, and the HDR branch suppresses the highlight lift (`headroom = 0`), so
turning HDR on has as little visual impact as possible.

## 6. Ladder rung 1 — pack inspection (implemented)

`/mmetal shaderpack [name]` parses `shaderpacks/*` (zip or folder) through
`dev.mmetal.metal.shaderpack.IrisPack`: program pairs, `shaders.properties`,
GLSL versions/extensions, SSBO/image/compute usage, and a GL-4.1 verdict:

- **runs as-is** — the Metal upscale tier already enhances it;
- **runs with compute passes skipped** — Iris drops `.csh` passes on GL 4.1
  (Complementary: 3 shadowcomp passes) — the Metal ladder unlocks them;
- **needs translation** — SSBOs / image load-store / GLSL above 4.1.

The manifest is the input model for rung 2 (GLSL → SPIR-V → MSL translation).
