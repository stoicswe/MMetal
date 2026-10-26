# MMetal

**Metal 4 / MetalFX integration for Minecraft: Java Edition on Apple Silicon.**

MMetal puts Apple's modern GPU stack to work on Minecraft 26.3 (the latest full release) through Fabric:

- **MetalFX Upscaling** – render the world at a lower tier (Ultra Performance → Quality) and let Apple's MetalFX spatial or temporal scaler restore full resolution every frame.
- **Ray traced lighting** – ray-marched sun shadows, ambient occlusion, reflections and global illumination, plus optional hardware ray-traced shadows (Metal acceleration structures), executed as real Metal compute kernels while Minecraft's OpenGL renderer keeps working.
- **Motion blur** – camera-motion blur blended on the Metal side.
- **Sodium & Iris friendly** – MMetal hooks *below* both mods at the swapchain present; while an Iris shader pack is active it only upscales and leaves the look to the pack.

> **Requirements:** Apple Silicon Mac; macOS 13+ (MetalFX upscaling); Minecraft **26.3**; **Fabric Loader ≥ 0.20.1**; **Java 25+**.
>
> **Use a HotSpot JDK** (Temurin, Microsoft OpenJDK, Oracle, Homebrew `openjdk`). IBM Semeru / OpenJ9 has a concurrency bug in its foreign-function implementation that MMetal's native calls can trigger (the JVM aborts inside its FFI machinery). Mojang's launcher ships a HotSpot-derived runtime, so normal installs are unaffected.

---

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.3.
2. Drop these jars into `mods`:
   - [Fabric API](https://modrinth.com/mod/fabric-api) (`0.164.0+26.3` or newer)
   - **MMetal** (build it yourself, or grab a jar from GitHub **Actions** artifacts / Releases)
   - Optionally [Sodium](https://modrinth.com/mod/sodium) 0.9.x and [Iris](https://modrinth.com/mod/iris) 1.11.x for 26.3.

## In-game settings

Open **MMetal Settings** with:
- the **O** key (rebindable under Controls → Miscellaneous),
- the **"MMetal Settings"** button on the vanilla **Options** screen (bottom-left),
- or `/mmetal ui`.

| Control | Effect |
| --- | --- |
| **MMetal: ON/OFF** | Master switch; when off, vanilla frames pass through untouched |
| **Quality** | Upscaling tier: Ultra Performance (33%), Performance (50%), Balanced (58%), Quality (67%), Dynamic (auto), None (native) |
| **Dynamic scaling** | The `Dynamic` quality tier adjusts the render scale (default 50-100%, in 5% steps) to hold the display refresh rate / Max FPS. Tune with `dynamicMinScale`, `dynamicMaxScale` and `dynamicTargetFps` (0 = match display; set it to 60 on a 120 Hz panel you cannot saturate) in `config/mmetal.json` |
| **Upscale** | `SPATIAL` (recommended), `TEMPORAL` (experimental), `OFF` |
| **Post Processing** | Master switch for the Metal-compute effects |
| **Ray Traced Lighting** | Screen-space ray-marched sun shadows + AO |
| **RT Shadows / RT Ambient Occlusion** | Individual effect toggles |
| **RT Reflections / RT Global Illumination** | Screen-space reflections and colour bounce |
| **RT Shadows (Hardware)** | Block-BVH shadow rays traced on the GPU |
| **Motion Blur** | Camera-motion blur |

Sliders: Shadow Strength, AO Strength, GI Strength, Reflection Strength, Ray Length, Motion Blur Amount.

### Commands

```
/mmetal ui            open the settings screen
/mmetal status        capability report + current config
/mmetal quality       ULTRA_PERFORMANCE | PERFORMANCE | BALANCED | QUALITY | DYNAMIC | NONE
/mmetal upscale       OFF | SPATIAL | TEMPORAL
/mmetal effects / shadows / ao    true|false
/mmetal sunstrength / aostrength  0..1
/mmetal reload        re-read config/mmetal.json
```

## Configuration — `config/mmetal.json`

Written on first launch; hot-reloaded when saved.

```json
{
  "enabled": true,
  "qualityPreset": "QUALITY",
  "upscaleMode": "SPATIAL",
  "effects": {
    "enabled": true,
    "rayTracing": true,
    "sunShadows": true,
    "ambientOcclusion": true,
    "sunStrength": 0.55,
    "aoStrength": 0.45,
    "marchSteps": 12,
    "rayLength": 6.0,
    "sunElevationDeg": 55.0,
    "sunAzimuthDeg": 135.0,
    "motionBlur": false,
    "motionBlurStrength": 0.3,
    "reflections": false,
    "globalIllumination": false,
    "hardwareRtShadows": false
  }
}
```

Older configs with a free-form `renderScale` are migrated to the nearest quality preset automatically.

## Building

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)   # 25+; Gradle itself needs 25+
./gradlew build
```

The jar lands in `build/libs/mmetal-0.1.0.jar`. On macOS the build re-compiles the Metal shader (`MMetalRaytrace.metal` → `.metallib`) with `xcrun`; the repo also ships a prebuilt metallib so Linux/CI builds produce a working jar.

### Testing locally

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
./gradlew runClient
```

Expected log on a healthy start:

```
MMetal booting: macOS 27.2, Java 27, Sodium=present, Iris=absent
Metal device: Apple M3
MetalFX spatial: yes, temporal: true
MMetal Metal compute library loaded (shadows/AO/motion kernels ready).
MMetal render target: 3024x1898 -> 2016x1265 (scale 0.6666667)
MMetal resources ready: 2016x1265 -> 3024x1898 (upscale=true effects=true fi=false)
```

### Suggested test matrix

1. **Baseline** – Quality: None, Post Processing off → should look identical to vanilla.
2. **Upscaling** – Quality: Balanced / Performance; check sharpness and full-window coverage in *windowed and fullscreen*, resize the window, toggle fullscreen.
3. **Clicks** – buttons on the title screen, options screens and MMetal settings must respond where they are drawn (mouse mapping).
4. **Live preset switching** – change quality with a screen open; the screen must re-layout and stay clickable.
5. **Effects** – Ray Traced Lighting on/off, reflections, GI, motion blur.
6. **Sodium + Iris** – repeat 2; with an active shader pack MMetal only upscales.

## Architecture (short version)

Minecraft 26.3 renders through Mojang's **RenderPearl** abstraction with the **OpenGL** backend on macOS. MMetal intercepts the last step of the frame — the swapchain present — and:

```
Game (GL, RenderPearl) ──render──▶ main render target (reduced resolution)
        │                          ▲ resize reads scaled in GameRenderer.render()
        ▼ GlSurface.present() mixin
IOSurface capture (GL blit + packed depth)      ← zero-copy GL↔Metal
        ▼
Metal: MetalFX upscale ─▶ RT shadows/AO/reflections/GI ─▶ motion blur
        ▼
IOSurface → GL quad → SDL swap
```

Coordinate-space invariants (important — these caused real bugs):
- The whole frame (world **and** GUI/HUD) renders at the reduced resolution into one
  target and is upscaled as a single image - the engine's intended model.
- `Window.getWidth()/getHeight()` and `getGuiScaledWidth/Height()` stay **native**:
  widget layout, the GUI ortho projection (`WindowRenderState.width / guiScale`), scissor
  math and mouse mapping all live in vanilla coordinates.
- The render scale is applied **only** to the `WindowRenderState.width/height` reads in
  `GameRenderer.render()` (the size comparison and the `resize(w,h)` call). The state
  itself stays native so the GUI projection is untouched.
- GUI scissor rects are computed in native pixels and scaled to the target
  (`GuiRendererMixin`) - RenderPearl rejects out-of-bounds scissors.
- The swapchain is always the **real window size** (`GlSurface.blitFromTexture` presents with it).

Full details — FFI layer, IOSurface sync rules, kernels, failure policy — in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Known limitations (v0.1.0)

- **GUI renders at the scaled resolution** together with the world (soft text at aggressive tiers). A native-resolution UI pass was prototyped and removed: the vanilla GUI bakes fullscreen layer rectangles from the framebuffer size, so rendering it into a differently-sized target shifts its background layers.
- **Temporal upscaling is experimental** — history resets on UI state changes; some ghosting on fast entity motion. `SPATIAL` is the recommended default.
- **Screen-space ray marching** drives AO, reflections and GI; hardware RT shadows use a
  per-frame block BVH (Metal 4 acceleration structures) and are best-effort (quality
  tiers, incremental rebuild is future work).
- The frame is CPU/GPU-synchronised at present (`glFinish` + `waitUntilCompleted`); a shared-event fence path is planned to remove the small stall.
- If anything fails, MMetal disables itself for the session with a clear log message — it never takes the game down.

## GitHub Actions

Every push/PR builds the jar on `ubuntu-latest` with Temurin 25 (`.github/workflows/build.yml`); artifacts are uploaded and attached to GitHub Releases on tag builds.

**Nightly pre-releases** (`.github/workflows/nightly.yml`) run once a day at 06:00 UTC
(and on demand) — but only when `src/`, the build files, the workflows
changed since the newest `nightly_*` tag. Each run publishes a new pre-release
(`nightly_<version>.<date>_<sha>`) containing the mod jar; snapshots are never overwritten. Run it manually any time with
**Actions → nightly → Run workflow**.

## Contributing

Contributions are welcome - bug reports and fixes alike. Please read
[`CONTRIBUTING.md`](CONTRIBUTING.md) for the workflow and coding style, and
[`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) for community expectations.

## Credits

Architecture and quality targets were informed by [LoreanXavier/pt-pc](https://github.com/LoreanXavier/pt-pc) (native P.T. port): render-scale → hardware upscaler → post effects → display transform, with separate UI presentation and per-preset quality tiers.

## License

MIT — see [LICENSE](LICENSE).