# MMetal style plugins

MMetal's "Style" tab runs a post-process look over every presented frame (after the
display transform). Looks are provided by a small registry, and **third-party mods can
add their own** - either as a shader-only jar you drop into `mods/`, or as a code
plugin.

Built-ins: `none`, `legends`.

---

## 1. Shader-only jar (no Java code)

A jar that contains a manifest and a compiled Metal library is picked up automatically
at startup.

```
mystyle.jar
├── fabric.mod.json                     (any valid Fabric mod metadata)
├── mmetal/
│   └── styles.json
└── assets/mystyle/metal/film.metallib
```

`mmetal/styles.json`:

```json
{
  "styles": [
    {
      "id": "mystyle:film",
      "name": "Film",
      "description": "Warm 35mm look with soft highlights.",
      "metallib": "assets/mystyle/metal/film.metallib",
      "kernel": "film_style",
      "strength": 1.0
    }
  ]
}
```

| field         | meaning                                                        |
|---------------|----------------------------------------------------------------|
| `id`          | stable id, lowercase (`namespace:name`); saved in the config   |
| `name`        | label shown in the Style tab                                   |
| `description` | one-line description                                           |
| `metallib`    | path inside your jar                                           |
| `kernel`      | compute function name inside the metallib                      |
| `strength`    | initial strength (0..1) when selected                          |

You can ship several entries (and several metallibs) per jar. Invalid entries are
logged and skipped - a broken plugin never crashes the game.

---

## 2. Code plugin (Fabric entrypoint)

For styles that need runtime parameters:

```json
// fabric.mod.json
"entrypoints": {
  "mmetal:styles": [ "com.example.MyStyleProvider" ]
}
```

```java
public final class MyStyleProvider implements dev.mmetal.api.MMetalStyleProvider {
    @Override
    public List<MMetalStyle> styles() {
        return List.of(new MyFilmStyle());
    }
}
```

The same `MMetalStyles.register(...)` API is available for programmatic registration.

---

## 3. Kernel contract

The kernel runs once per output pixel, at the **full output resolution**, after MMetal's
tonemap. It receives MMetal's standard textures and parameters:

```metal
#include <metal_stdlib>
using namespace metal;

struct EffectParams {          // 144 bytes; identical to MMetal's layout
    float2 viewport;           // @0   output size in pixels
    float  near;               // @8   camera near plane
    float  far;                // @12  camera far plane
    float  sunStrength;        // @16
    float  aoStrength;         // @20
    float  steps;              // @24
    float  rayLength;          // @28
    float4 sunDirEye;          // @32
    float  time;               // @48
    float  tanHalfFovY;        // @52
    float  aspect;             // @56
    float4 sunScreen;          // @64
    float  motionBlurAmount;   // @80  1 = depth valid (no menu), 0 = menu frame
    float  exposure;           // @84
    float  tonemapMode;        // @88
    float  headroom;           // @92  applied style strength (0..1)
    float  reflectionStrength; // @96
    float  giStrength;         // @100
    float  denoise;            // @104
    float  bloomStrength;      // @108
    float4 handGlow;           // @112
    float2 handGlowPos;        // @128
    float  rtShadowMix;        // @136
    float  styleId;            // @140
};

kernel void film_style(
    texture2d<float, access::read>   inColor  [[texture(0)]],  // LDR frame
    texture2d<float, access::sample> depth    [[texture(1)]],  // R32Float, reversed (1 = near)
    texture2d<float, access::write>  outColor [[texture(2)]],  // write result here
    texture2d<float, access::sample> bloom    [[texture(3)]],  // emissive bloom
    constant EffectParams           &params   [[buffer(0)]],
    constant float                  *custom   [[buffer(1)]],  // customFloatCount() floats
    uint2 gid                                 [[thread_position_in_grid]])
{
    if (gid.x >= (uint)params.viewport.x || gid.y >= (uint)params.viewport.y) { return; }
    float3 c = inColor.read(gid).rgb;
    // ... your look ...
    outColor.write(float4(c, 1.0), gid);
}
```

Notes:
- `depth` is only meaningful when `params.motionBlurAmount > 0.5` (menu frames clear it).
- `params.headroom` carries the Strength slider; use it to blend your look.
- `params.aspect` is width/height; `params.viewport` matches the output texture size.
- Write alpha `1.0`; the frame is presented straight to the window.

### Building the metallib

```bash
xcrun metal -c film.metal -o film.air
xcrun metallib film.air -o film.metallib
```

For a code plugin without a jar manifest, return the metallib as a classpath resource
from `metallibResource()` (e.g. `"/assets/mymod/metal/film.metallib"`) and set
`kernelName()` plus, optionally, `customFloatCount()` / `writeCustomParams(float[])`.

### Testing

1. Build your jar and drop it into `.minecraft/mods`.
2. Start the game with MMetal installed; the log shows
   `MMetal style registered from <mod>: <id> (kernel <name>)`.
3. Open **MMetal Settings → Style**, cycle to your style, tune Strength.

If a style fails to load, MMetal logs the kernel/library error and keeps the previous
image (it never crashes).
