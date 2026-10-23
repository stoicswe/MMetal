# MMetal Architecture

How MMetal works internally, what is real today, and what is planned. Written for
anyone hacking on the mod. Quality targets and the presentation model follow
[pt-pc](https://github.com/LoreanXavier/pt-pc): render scale → hardware upscaler →
post effects → display transform, with per-preset quality tiers.

## 1. Minecraft 26.3, in one page

- **Unobfuscated** — Mojang ships real class names; Yarn stops at 1.21.11 and the
  Fabric template for 26.x declares no mappings. Mixins target real names.
- **RenderPearl** — all rendering goes through `com.mojang.renderpearl.*`
  (`GpuDevice`, `CommandEncoder`, `RenderPass`, `GpuSurface`) with an OpenGL backend
  (macOS default) and a Vulkan backend (only if forced, needs MoltenVK).
- **SDL windowing** — `com.mojang.blaze3d.platform.Window` wraps an SDL window;
  `SDL_GL_SwapWindow` presents.
- **Java 25 runtime** — the mod can use the finalized Foreign Function & Memory API
  (JEP 454). No bundled native code.
- **Reversed-Z depth** — the main target's depth is cleared to 0.0 (far), 1.0 = near.

Frame loop (verified by disassembling the shipped jar):

```
Minecraft.renderFrame(boolean)
  ├─ configure GpuSurface from Window.queryFramebufferSize()   (real window size)
  ├─ acquireNextTexture()
  ├─ GameRenderer.render()
  │    ├─ extractWindow() → WindowRenderState {width,height,guiScale}  (stays native)
  │    ├─ resize mainRenderTarget (reads scaled by GameRendererMixin)
  │    ├─ renderLevel()            → world into mainRenderTarget (RGBA8 + D32)
  │    └─ GuiRenderer.render()     → GUI into the SAME mainRenderTarget
  ├─ windowSurface.blitFromTexture(encoder, colorView)  → GL blit to the default FB
  └─ windowSurface.present()       → GlSurface.present() → SDL_GL_SwapWindow
```

## 2. Mixins

| Mixin | Target | Purpose |
| --- | --- | --- |
| `GameRendererMixin` | `GameRenderer.render()` field redirects | Returns scaled values for the two `WindowRenderState.width/height` reads (size comparison + `resize(w,h)`). **The only place the render scale is applied.** The state itself stays native. |
| `GuiRendererMixin` | `GuiRenderer.enableScissor()` | Scales the native-pixel scissor rect into the reduced render target. |
| `GlSurfaceMixin` | `GlSurface.present()` HEAD (cancellable) | Takes over presentation: capture → Metal → draw → swap. |
| `OptionsScreenMixin` | `OptionsScreen.init()` TAIL | Adds the "MMetal Settings" button. |
| `ScreenInvoker` | `Screen` | Exposes protected widget registration. |
| `MinecraftInvoker` | `Minecraft` | Exposes `resizeGui()` for re-layout after preset changes. |

### Coordinate-space invariants (these caused real bugs — keep them)

- `Window.getWidth()/getHeight()` stay **native**. The mouse handler maps
  `pixel * guiScaledWidth / getWidth()`, so scaling the framebuffer size shifts every
  click.
- `getGuiScaledWidth/Height()` stay **native**. The GUI ortho projection is built as
  `WindowRenderState.width / guiScale` inside `GuiRenderer.draw()`, so scaling either
  of them alone (without the other) shifts and rescales the whole HUD.
- `WindowRenderState.width/height` stay **native** for every GUI consumer; only the
  render-target resize reads are scaled (see `GameRendererMixin`). Scaling the state
  globally makes the GUI projection disagree with the layout.
- The GUI scissor is computed in native pixels (`coord * guiScale`, clamped against
  the native state) and scaled to the target by `GuiRendererMixin`. Do not scale it
  earlier: the y-flip and clamps inside `enableScissor` assume native space.
- The whole frame (world + GUI) is rendered at the reduced resolution and upscaled as
  one image. A separate full-resolution GUI target was prototyped and removed: the
  vanilla GUI bakes fullscreen layer rects from the framebuffer size, so a
  differently-sized GUI target displaces its background layers.
- The swapchain comes from `queryFramebufferSize()` and must be the **real** window
  size: `GlSurface.blitFromTexture` presents with it, so scaling it draws the game
  into a sub-region of the window.
- `present()` must always cancel vanilla's present exactly once — two
  `SDL_GL_SwapWindow` calls per frame alternate the double-buffered window (flicker,
  black frames).

## 3. GL ⇄ Metal bridge

- `IOSurfaceCreate` → GL rectangle texture via `CGLTexImageIOSurface2D` → Metal
  `-newTextureWithDescriptor:iosurface:plane:`.
- Only **BGRA8** surfaces are used (`GL_RGBA8`/`GL_BGRA`/`GL_UNSIGNED_INT_8_8_8_8_REV`
  ↔ `MTLPixelFormatBGRA8Unorm`). Apple's GL rejects `GL_RGBA32F` IOSurface combos, so
  depth travels as **packed 24-bit reversed depth in BGRA8** and `mm_depth` unpacks it
  into a private `R32Float` texture that MetalFX and the kernels consume.
- Synchronisation (IOSurface does **not** auto-synchronise across APIs):
  - GL → Metal: `glFinish()` before encoding (a `glFlush` leaves a race that shows as
    flicker/black).
  - Metal → GL: `waitUntilCompleted` on the command buffer, then a plain
    `glBindTexture` on the reading context (per `CGLIOSurface.h`).
- Metal objects are ARC-managed/autoreleased; there is **no `-close`/`-release`** to
  send from Java (doing so aborts with "unrecognized selector"). IOSurfaces follow the
  CF Create-rule and are `CFRelease`d.

## 4. Native layer (`nativebridge`)

`java.lang.foreign` only:

- `ObjC` — caches `objc_msgSend` handles per `FunctionDescriptor` with
  `Linker.Option.critical(false)` (`isTrivial` was removed in Java 26). Every call has
  a fixed descriptor; on arm64 that matches clang's ObjC call sites.
- `MetalNative`/`MetalFx` — device, queue, textures, IOSurface-backed textures,
  command buffers/encoders, `MTLFXSpatialScaler`, `MTLFXTemporalScaler`,
  `MTLFXFrameInterpolator` (descriptors and instances).
- `IosurfaceBridge` — IOSurface + CoreFoundation dictionary plumbing +
  `CGLTexImageIOSurface2D` + `CFRelease`.
- `HdrPresenter` — EDR `CAMetalLayer` overlay (QuartzCore/AppKit) and drawable
  acquisition.

Selector gotchas learned the hard way: it is `newLibraryWithURL:error:` (not
`…ContentsOfURL:`); `newFunctionWithName:` takes an `NSString*`, not a C string;
`supportsDevice:` returns `BOOL` (a byte).

## 5. Per-frame Metal pipeline

```
GL capture            : blit main.color → inColor (BGRA8 IOSurface, scaled W×H)
                        depth shader  → inDepth (packed reversed depth, scaled W×H)
Metal command buffer  :
  1. mm_depth          inDepth → depthR32                     (R32Float)
  2. MetalFX scaler    inColor → privateFull                  (full W×H)
  3. mm_ssr_shadows    depthR32+privateFull → mask            (if Ray Traced Lighting)
     mm_volumetric     privateFull+depthR32 → volumetric      (if Volumetric Light)
     mm_composite      privateFull+mask+volumetric → postFx
  4. mm_motionblur     postFx+prevColor → blurred             (if Motion Blur)
  5. motion + FI       postFx+prevColor+depthR32+motion → interpPrivate (if FI)
  6. mm_tonemap        [interp|postFx] → tonemapped           (SDR, filmic knee)
     blit              → outColor (IOSurface)  |  HDR: tonemap into EDR drawable
  7. prevColor ← postFx (pre-tonemap; consistent encoding for FI/blur)
GL present            : draw outColor → FB0 → SDL swap
                        (FI: interpolated frame first, then the real frame)
```

### Display transform

The framebuffer is **display-referred** (sRGB-encoded). ACES applied directly lifts
every midtone ~50% (washed-out frames). MMetal instead decodes to linear, applies a
soft-knee highlight compressor (identity below 0.8, smooth rolloff above), and
re-encodes: midtones are pixel-exact, only the sun/highlights roll off.

HDR mode (EDR): decode to scene-linear, boost bright content into the display's
headroom (`layer.maximumExtendedDynamicRangeColorComponentValue`) and write
`RGBA16Float` into a `CAMetalLayer` with `kCGColorSpaceExtendedLinearSRGB`; the OS
tonemaps to the panel. The GL swap is skipped while the layer is active.

### Temporal hygiene

Temporal upscaling and frame interpolation blend history — never across a UI state
change. When `Minecraft.gui.screen()` appears/disappears, MMetal sets the scaler's
reset flag, resets the interpolator history, and pauses FI while a screen is open.

## 6. Failure policy

Every entry point is wrapped: a fatal error (missing framework, unsupported GPU,
shader compile failure, GL surprise) disables the pipeline for the session, logs once,
and lets vanilla present run untouched. Injections use `require = 0` where a future
Mojang refactor could remove a target, so the mod degrades instead of crashing.

## 7. Roadmap

1. **Native-resolution UI pass** — render the GUI into a full-resolution target after
   the upscale so text stays crisp at Performance/Ultra Performance.
2. **Shared-event fences** — replace the `glFinish`/`waitUntilCompleted` round trip
   with `MTLSharedEvent` + GL fence sync to remove the per-frame stall.
3. **Denoised RT** — route the shadow/AO mask through `MTLFXTemporalDenoisedScaler`
   (the MetalFX denoiser designed for ray-traced content) instead of raw jitter.
4. **Hardware RT** — export chunk geometry into `MTL4AccelerationStructure` BVHs for
   real ray-traced shadows/reflections/gi.
