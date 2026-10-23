// MMetal - Screen-space ray marched lighting effects (Metal 4 compute)
//
// Compiled with:
//   xcrun metal -c MMetalRaytrace.metal -o MMetalRaytrace.metallib
//
// Depth convention: Minecraft 26.3 uses a REVERSED depth buffer (cleared to 0.0,
// far plane == 0.0, near plane == 1.0). Linearization:
//     dist = near * far / (depth * (far - near) + near)
// where depth in [0,1], sky pixels are exactly 0.0.
//
// All work happens in eye space (OpenGL convention: camera looks down -Z, so
// points in front of the camera have z < 0 and dist = -z).

#include <metal_stdlib>
#include <metal_raytracing>
using namespace metal;
using namespace metal::raytracing;

// ----------------------------------------------------------------------------
// Uniforms
// ----------------------------------------------------------------------------

struct EffectParams {
    float2 viewport;        // render size in pixels
    float  near;            // projection near plane (0.05 for MC)
    float  far;             // projection far plane
    float  sunStrength;     // 0..1 how strongly shadows are applied
    float  aoStrength;      // 0..1 how strongly AO is applied
    float  steps;           // shadow ray march steps (4..32)
    float  rayLength;       // occlusion ray length in blocks (eye units)
    float4 sunDirEye;       // sun direction in EYE space, xyz, w unused
    float  time;            // seconds, for temporal jitter
    float  tanHalfFovY;     // tan(vertical FOV / 2)
    float  aspect;          // width / height
    float4 sunScreen;       // sun position on screen: xy = uv, z = onScreen flag, w = volumetric strength
    float  motionBlurAmount;// 0..1 blend factor toward the previous frame
    float  exposure;        // pre-tonemap gain
    float  tonemapMode;     // 0 = off, 1 = filmic knee (SDR), 2 = HDR inverse-tonemap
    float  headroom;        // EDR headroom multiplier for HDR mode (1.0 = SDR white)
    float  reflectionStrength; // 0..1 ray traced reflections
    float  giStrength;         // 0..1 ray traced global illumination
    float  denoise;            // >0.5 = denoise the effect mask
    float  bloomStrength;      // 0..1 emissive glow (torches, lava, glowstone...)
    float4 handGlow;           // rgb = held light colour, w = strength
    float2 handGlowPos;        // screen position of the held light (uv)
    float  rtShadowMix;        // 0..1 blend in the hardware ray-traded shadow texture
    float  styleId;            // mm_style: look id (see MMetalConfig.Style ordinal)
    float  hdrDarkness;        // HDR only: 0 = neutral, 1 = deepest shadow rendition
};
// Java-side offsets: viewport@0, near@8, far@12, sunStrength@16, aoStrength@20,
// steps@24, rayLength@28, sunDirEye@32, time@48, tanHalfFovY@52, aspect@56,
// sunScreen@64, motionBlurAmount@80, exposure@84, tonemapMode@88, headroom@92,
// reflectionStrength@96, giStrength@100, denoise@104, bloomStrength@108,
// handGlow@112, handGlowPos@128, rtShadowMix@136, styleId@140, hdrDarkness@144,
// size 148.
// mm_style also reuses headroom@92 as the style strength.

// Per-frame camera parameters for motion vector generation.
struct MotionParams {
    float2   inSize;        // scaled (input) render size in pixels
    float2   outSize;       // full (output) render size in pixels
    float4x4 curRot;        // current view rotation (world -> eye)
    float4x4 prevRot;       // previous view rotation
    float4x4 curVP;         // current projection * viewRotation
    float4x4 prevVP;        // previous projection * viewRotation
    float4   curEye;        // current eye position (world), xyz
    float4   prevEye;       // previous eye position (world), xyz
    float    near;
    float    far;
    float    tanHalfFovY;
    float    aspect;
    float    firstFrame;    // 1.0 on the first frame after (re)activation
};
// Java-side offsets: inSize@0, outSize@8, curRot@16, prevRot@80, curVP@144, prevVP@208,
// curEye@272, prevEye@288, near@304, far@308, tanHalfFovY@312, aspect@316, firstFrame@320, size 336.

// ----------------------------------------------------------------------------
// Helpers
// ----------------------------------------------------------------------------

static float linearizeDepth(float depth01, constant EffectParams &p) {
    return p.near * p.far / (max(depth01, 1e-5) * (p.far - p.near) + p.near);
}

/** Reconstructs the eye-space position of a pixel. */
static float3 eyePos(float2 uv, float depth01, constant EffectParams &p) {
    float dist = linearizeDepth(depth01, p);
    float2 ndc = uv * 2.0 - 1.0;
    return float3(ndc.x * dist * p.tanHalfFovY * p.aspect,
                  ndc.y * dist * p.tanHalfFovY,
                  -dist);
}

/** Projects an eye-space point to screen UV; returns (-1) in .z when behind the camera or off screen. */
static float3 projectEye(float3 point, constant EffectParams &p) {
    if (point.z >= -1e-4) { return float3(0, 0, -1); }
    float dist = -point.z;
    float2 uv = float2(point.x / (dist * p.tanHalfFovY * p.aspect),
                       point.y / (dist * p.tanHalfFovY)) * 0.5 + 0.5;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) { return float3(0, 0, -1); }
    return float3(uv, dist);
}

/** Reconstructs an eye-space normal from depth neighborhood derivatives. */
static float3 normalFromDepth(texture2d<float> depthTex, float2 uv, float2 texel,
                              constant EffectParams &p) {
    constexpr sampler smp(address::clamp_to_edge, filter::nearest);
    float dC = depthTex.sample(smp, uv).r;
    float dR = depthTex.sample(smp, uv + float2(texel.x, 0.0)).r;
    float dU = depthTex.sample(smp, uv + float2(0.0, texel.y)).r;
    float3 pC = eyePos(uv, dC, p);
    float3 pR = eyePos(uv + float2(texel.x, 0.0), dR, p);
    float3 pU = eyePos(uv + float2(0.0, texel.y), dU, p);
    if (dC <= 0.0) { return float3(0, 0, -1); }
    float3 n = normalize(cross(pU - pC, pR - pC));
    return n;
}
static float hashJitter(float2 xy, float t) {
    return fract(sin(dot(xy + t * 61.7, float2(12.9898, 78.233))) * 43758.5453);
}

// ----------------------------------------------------------------------------
// Kernel 0: unpack the GL-side packed depth (BGRA8 IOSurface, 24-bit reversed
// depth in R=bits16-23, G=bits8-15, B=bits0-7) into a native R32Float texture
// that MetalFX and the other kernels can consume directly.
// ----------------------------------------------------------------------------

kernel void mm_depth(
    texture2d<float, access::read>  packedDepth [[texture(0)]],
    texture2d<float, access::write> outDepth    [[texture(1)]],
    uint2 gid                                                  [[thread_position_in_grid]])
{
    float4 p = packedDepth.read(gid);
    // p.r = most significant byte, p.g = middle, p.b = least significant (see GLSL writer).
    float d = (p.r * 65536.0 + p.g * 256.0 + p.b) / 16777215.0;
    outDepth.write(float4(d, 0.0, 0.0, 1.0), gid);
}

// ----------------------------------------------------------------------------
// Kernel 1: ray-marched sun shadows + ambient occlusion -> packed mask
//   mask.r = shadow factor, mask.g = AO factor, mask.b = source luminance
// ----------------------------------------------------------------------------

kernel void mm_ssr_shadows(
    texture2d<float, access::read>  inDepth [[texture(0)]],
    texture2d<float, access::read>  inColor [[texture(1)]],
    texture2d<float>           depthSampler [[texture(2)]],
    texture2d<float, access::write> outMask [[texture(3)]],
    constant EffectParams          &params  [[buffer(0)]],
    uint2 gid [[thread_position_in_grid]])
{
    if (gid.x >= (uint)params.viewport.x || gid.y >= (uint)params.viewport.y) { return; }

    float2 uv = (float2(gid) + 0.5) / params.viewport;
    float2 texel = 1.0 / params.viewport;
    // R32Float depth is not filterable - sample with nearest.
    constexpr sampler smp(address::clamp_to_edge, filter::nearest);

    float depth01 = inDepth.read(gid).r;
    float4 color = inColor.read(gid);

    float shadow = 1.0;
    float ao = 1.0;
    bool isSky = depth01 <= 0.0;

    if (!isSky) {
        float3 p = eyePos(uv, depth01, params);
        float jit = hashJitter(uv * params.viewport, params.time);

        // ---- Sun shadow ray (screen-space march toward the sun) ----
        int steps = (int)clamp(params.steps, 4.0, 32.0);
        float stepLen = params.rayLength / (float)steps;
        float3 rp = p + params.sunDirEye.xyz * (stepLen * (jit + 0.5));
        for (int i = 0; i < steps; i++) {
            float3 projected = projectEye(rp, params);
            if (projected.z < 0.0) { break; }
            float sceneDist = linearizeDepth(depthSampler.sample(smp, projected.xy).r, params);
            float depthDelta = sceneDist - (-rp.z);
            if (depthDelta > 0.05 && depthDelta < stepLen * 4.0) {
                // Occluded: apply a soft penumbra based on how deep the blocker is.
                shadow = min(shadow, saturate(depthDelta / (stepLen * 4.0)) * 0.9 + 0.1);
            }
            rp += params.sunDirEye.xyz * stepLen;
        }

        // ---- Ambient occlusion (hemisphere rays) ----
        float3 n = normalFromDepth(depthSampler, uv, texel, params);
        float occ = 0.0;
        for (int i = 0; i < 8; i++) {
            // Cosine-weighted hemisphere sampling around the normal.
            float a = (float)i / 8.0 * 6.2831853 + jit;
            float h = 0.2 + 0.6 * fract(jit * (float)(i + 3) * 0.137);
            float3 tangent = normalize(cross(n, abs(n.y) < 0.99 ? float3(0, 1, 0) : float3(1, 0, 0)));
            float3 bitangent = cross(n, tangent);
            float3 dir = normalize(tangent * cos(a) * h + bitangent * sin(a) * h + n);
            float3 probe = p + n * 0.08 + dir * params.rayLength * (0.25 + 0.75 * (float)i / 8.0);
            float3 projected = projectEye(probe, params);
            if (projected.z > 0.0) {
                float sceneDist = linearizeDepth(depthSampler.sample(smp, projected.xy).r, params);
                float diff = sceneDist - (-probe.z);
                if (diff > 0.02 && diff < params.rayLength) {
                    occ += saturate(1.0 - diff / params.rayLength);
                }
            }
        }
        ao = 1.0 - saturate(occ / 8.0) * 0.9;
    }

    outMask.write(float4(shadow, ao, dot(color.rgb, float3(0.2126, 0.7152, 0.0722)), 1.0), gid);
}

// ----------------------------------------------------------------------------
// Kernel 6: display transform
//   mode 1: filmic highlight rolloff in LINEAR light - midtones are preserved
//           exactly (decode -> knee -> encode), only highlights above the knee
//           are compressed, so the sun stops clipping flat white without
//           brightening the rest of the frame.
//   mode 2: HDR inverse-tonemap into scene-linear extended range for EDR
//           presentation (values above 1.0 = brighter than SDR white).
// ----------------------------------------------------------------------------

static float3 mmetalSrgbToLinear(float3 c) {
    return pow(c, float3(2.2));
}

static float3 mmetalLinearToSrgb(float3 c) {
    return pow(c, float3(1.0 / 2.2));
}

/** Soft-knee highlight compressor: identity below K, smooth rolloff above. */
static float3 mmetalHighlightKnee(float3 c) {
    const float K = 0.80;
    float3 over = max(c - K, 0.0);
    float3 compressed = K + (1.0 - K) * (1.0 - exp(-over / (1.0 - K)));
    return float3(c.r <= K ? c.r : compressed.r,
                  c.g <= K ? c.g : compressed.g,
                  c.b <= K ? c.b : compressed.b);
}

kernel void mm_tonemap(
    texture2d<float, access::read>  inColor  [[texture(0)]],
    texture2d<float>                   bloom [[texture(1)]],
    texture2d<float, access::write> outColor [[texture(2)]],
    constant EffectParams          &params   [[buffer(0)]],
    uint2 gid                                                [[thread_position_in_grid]])
{
    if (gid.x >= (uint)params.viewport.x || gid.y >= (uint)params.viewport.y) { return; }
    constexpr sampler smpB(address::clamp_to_edge, filter::linear);
    float2 uv = (float2(gid) + 0.5) / params.viewport;
    // Emissive bloom adds light before the display transform.
    float3 bloomAdd = bloom.sample(smpB, uv).rgb * params.bloomStrength;
    float3 base = inColor.read(gid).rgb + bloomAdd;
    // Held light source glow (torch/lantern in hand): soft radial falloff so the
    // emitter visibly lights the area around the hand.
    float2 hd = uv - params.handGlowPos;
    float heldGlow = exp(-dot(hd, hd) / 0.008) * params.handGlow.w;
    base += params.handGlow.rgb * heldGlow;
    float3 c = max(base, float3(0.0)) * params.exposure;

    if (params.tonemapMode > 1.5) {
        // HDR: decode the display-referred frame to scene-linear, then push bright
        // content (sun, sky, glow) into the display's EDR headroom. The OS tonemaps
        // this composite for the actual panel capability.
        float3 lin = mmetalSrgbToLinear(saturate(c));
        float luma = dot(lin, float3(0.2126, 0.7152, 0.0722));
        float boost = 1.0 + params.headroom * pow(saturate((luma - 0.4) / 0.6), 2.0);
        // Darkness slider: pull shadow luminance down without touching midtones or
        // highlights (the black-level half of the AAA HDR calibration pair).
        float shadowW = 1.0 - saturate(luma / 0.25);   // 1 near black, 0 by 25% grey
        lin *= 1.0 - params.hdrDarkness * shadowW * 0.8;
        // The pipeline's working textures are bottom-up (they are sampled by the GL
        // present quad without a v-flip); a CAMetalLayer drawable is top-down, so the
        // EDR write flips Y to present upright.
        outColor.write(float4(lin * boost, 1.0),
                       uint2(gid.x, (uint)params.viewport.y - 1u - gid.y));
    } else if (params.tonemapMode > 0.5) {
        // Linear-light filmic knee: midtones exact, highlights compressed.
        float3 lin = mmetalSrgbToLinear(saturate(c));
        outColor.write(float4(mmetalLinearToSrgb(mmetalHighlightKnee(lin)), 1.0), gid);
    } else {
        outColor.write(float4(c, 1.0), gid);
    }
}

// ----------------------------------------------------------------------------
// Kernel 7: ray traced reflections (screen space)
//   Marches the reflected view ray against the depth buffer and samples the
//   hit point's colour, weighted by a Fresnel-style grazing term.
// ----------------------------------------------------------------------------

kernel void mm_reflections(
    texture2d<float>               colorSampler [[texture(0)]],
    texture2d<float>               depthSampler [[texture(1)]],
    texture2d<float, access::write> outRefl     [[texture(2)]],
    constant EffectParams          &params      [[buffer(0)]],
    uint2 gid                                                 [[thread_position_in_grid]])
{
    if (gid.x >= (uint)params.viewport.x || gid.y >= (uint)params.viewport.y) { return; }

    float2 uv = (float2(gid) + 0.5) / params.viewport;
    float2 texel = 1.0 / params.viewport;
    constexpr sampler csmp(address::clamp_to_edge, filter::linear);
    constexpr sampler dsmp(address::clamp_to_edge, filter::nearest);

    if (params.reflectionStrength <= 0.001) { outRefl.write(float4(0.0), gid); return; }

    float depth01 = depthSampler.sample(dsmp, uv).r;
    if (depth01 <= 0.0) { outRefl.write(float4(0.0), gid); return; } // sky

    float3 p = eyePos(uv, depth01, params);
    float3 n = normalFromDepth(depthSampler, uv, texel, params);
    float3 viewDir = normalize(p);
    float3 rayDir = reflect(viewDir, n);

    // Fresnel-ish: stronger at grazing angles.
    float fresnel = pow(1.0 - saturate(dot(-viewDir, n)), 3.0);

    float jit = hashJitter(uv * params.viewport, params.time);
    int steps = (int)clamp(params.steps, 4.0, 32.0);
    float stepLen = params.rayLength / (float)steps;
    float3 rp = p + n * 0.08 + rayDir * (stepLen * jit);

    for (int i = 0; i < steps; i++) {
        float3 projected = projectEye(rp, params);
        if (projected.z < 0.0) { break; }
        float sceneDist = linearizeDepth(depthSampler.sample(dsmp, projected.xy).r, params);
        float delta = sceneDist - (-rp.z);
        if (delta > 0.02 && delta < stepLen * 2.5) {
            float3 hit = colorSampler.sample(csmp, projected.xy).rgb;
            float fade = 1.0 - (float)i / (float)steps;
            float3 refl = hit * (fresnel * 0.85 + 0.15) * fade * params.reflectionStrength;
            outRefl.write(float4(refl, 1.0), gid);
            return;
        }
        rp += rayDir * stepLen;
    }
    outRefl.write(float4(0.0), gid);
}

// ----------------------------------------------------------------------------
// Kernel 8: ray traced global illumination (screen space)
//   Hemisphere gathers of scene colour, added as indirect light. Expensive -
//   optional, and the denoise toggle matters here.
// ----------------------------------------------------------------------------

kernel void mm_rtgi(
    texture2d<float>               colorSampler [[texture(0)]],
    texture2d<float>               depthSampler [[texture(1)]],
    texture2d<float, access::write> outGi       [[texture(2)]],
    constant EffectParams          &params      [[buffer(0)]],
    uint2 gid                                                 [[thread_position_in_grid]])
{
    if (gid.x >= (uint)params.viewport.x || gid.y >= (uint)params.viewport.y) { return; }
    if (params.giStrength <= 0.001) { outGi.write(float4(0.0), gid); return; }

    float2 uv = (float2(gid) + 0.5) / params.viewport;
    float2 texel = 1.0 / params.viewport;
    constexpr sampler csmp(address::clamp_to_edge, filter::linear);
    constexpr sampler dsmp(address::clamp_to_edge, filter::nearest);

    float depth01 = depthSampler.sample(dsmp, uv).r;
    if (depth01 <= 0.0) { outGi.write(float4(0.0), gid); return; }

    float3 p = eyePos(uv, depth01, params);
    float3 n = normalFromDepth(depthSampler, uv, texel, params);
    float jit = hashJitter(uv * params.viewport, params.time);

    float3 acc = float3(0.0);
    const int RAYS = 6;
    for (int r = 0; r < RAYS; r++) {
        float a = ((float)r / (float)RAYS) * 6.2831853 + jit;
        float hh = 0.25 + 0.6 * fract(jit * (float)(r + 2) * 0.173);
        float3 tangent = normalize(cross(n, abs(n.y) < 0.99 ? float3(0, 1, 0) : float3(1, 0, 0)));
        float3 bitangent = cross(n, tangent);
        float3 dir = normalize(tangent * cos(a) * hh + bitangent * sin(a) * hh + n);
        float3 probe = p + n * 0.12 + dir * params.rayLength * (0.3 + 0.7 * (float)r / (float)RAYS);
        float3 projected = projectEye(probe, params);
        if (projected.z > 0.0) {
            float sceneDist = linearizeDepth(depthSampler.sample(dsmp, projected.xy).r, params);
            float delta = (sceneDist - (-probe.z));
            if (delta > -0.5 && delta < 0.5) {
                acc += colorSampler.sample(csmp, projected.xy).rgb;
            }
        }
    }
    float3 gi = acc / (float)RAYS * params.giStrength;
    outGi.write(float4(gi, 1.0), gid);
}

// ----------------------------------------------------------------------------
// Kernel 9: emissive bloom (glowing torches, lava, glowstone, the sun)
//   A thresholded bright-pass followed by a separable blur; the tonemap stage
//   adds the result so emitters bleed light into their surroundings.
// ----------------------------------------------------------------------------

kernel void mm_bloom_extract(
    texture2d<float, access::read>  inColor [[texture(0)]],
    texture2d<float, access::write> outBloom [[texture(1)]],
    constant EffectParams          &params  [[buffer(0)]],
    uint2 gid                                               [[thread_position_in_grid]])
{
    if (gid.x >= (uint)params.viewport.x || gid.y >= (uint)params.viewport.y) { return; }
    float3 c = inColor.read(gid).rgb;
    float luma = dot(c, float3(0.2126, 0.7152, 0.0722));
    float threshold = 0.75;
    float knee = 0.15;
    float weight = saturate((luma - threshold + knee) / (2.0 * knee + 1e-4));
    weight = max(weight, step(threshold, luma));
    outBloom.write(float4(c * weight, 1.0), gid);
}

kernel void mm_bloom_blur(
    texture2d<float, access::read>  inBloom  [[texture(0)]],
    texture2d<float>             blurSampler [[texture(1)]],
    texture2d<float, access::write> outBloom [[texture(2)]],
    constant EffectParams          &params   [[buffer(0)]],
    constant float2               &direction [[buffer(1)]],
    uint2 gid                                                 [[thread_position_in_grid]])
{
    if (gid.x >= (uint)params.viewport.x || gid.y >= (uint)params.viewport.y) { return; }
    constexpr sampler smp(address::clamp_to_edge, filter::linear);
    float2 uv = (float2(gid) + 0.5) / params.viewport;
    float2 texel = 1.0 / params.viewport;

    // 9-tap gaussian, normalised.
    const float w0 = 0.227027, w1 = 0.194594, w2 = 0.121621, w3 = 0.054054, w4 = 0.016216;
    float3 acc = blurSampler.sample(smp, uv).rgb * w0;
    acc += blurSampler.sample(smp, uv + direction * texel * 1.0).rgb * w1;
    acc += blurSampler.sample(smp, uv - direction * texel * 1.0).rgb * w1;
    acc += blurSampler.sample(smp, uv + direction * texel * 2.0).rgb * w2;
    acc += blurSampler.sample(smp, uv - direction * texel * 2.0).rgb * w2;
    acc += blurSampler.sample(smp, uv + direction * texel * 3.0).rgb * w3;
    acc += blurSampler.sample(smp, uv - direction * texel * 3.0).rgb * w3;
    acc += blurSampler.sample(smp, uv + direction * texel * 4.0).rgb * w4;
    acc += blurSampler.sample(smp, uv - direction * texel * 4.0).rgb * w4;
    outBloom.write(float4(acc, 1.0), gid);
}

// ----------------------------------------------------------------------------
// Kernel 3: volumetric light shafts (god rays) toward the sun
//   Marches from each pixel toward the sun's screen position, accumulating sky
//   brightness; geometry (depth > 0) occludes the shaft.
// ----------------------------------------------------------------------------

kernel void mm_volumetric(
    texture2d<float>           colorSampler [[texture(0)]],
    texture2d<float>           depthSampler [[texture(1)]],
    texture2d<float, access::write> outGlow [[texture(2)]],
    constant EffectParams     &params      [[buffer(0)]],
    uint2 gid                                            [[thread_position_in_grid]])
{
    if (gid.x >= (uint)params.viewport.x || gid.y >= (uint)params.viewport.y) { return; }

    float2 uv = (float2(gid) + 0.5) / params.viewport;
    constexpr sampler smp(address::clamp_to_edge, filter::linear);
    constexpr sampler dsmp(address::clamp_to_edge, filter::nearest);

    if (params.sunScreen.z < 0.5 || params.sunScreen.w <= 0.001) {
        outGlow.write(float4(0.0), gid);
        return;
    }

    const int STEPS = 24;
    float2 sunUV = params.sunScreen.xy;
    float3 acc = float3(0.0);
    float  total = 0.0;

    for (int i = 1; i <= STEPS; i++) {
        float t = (float)i / (float)STEPS;
        float2 suv = mix(uv, sunUV, t);
        float depth01 = depthSampler.sample(dsmp, suv).r;
        if (depth01 <= 0.0) {
            // Sky: shafts pass through and pick up brightness.
            float3 c = colorSampler.sample(smp, suv).rgb;
            float falloff = 1.0 - t * 0.55; // brighter near the sun
            acc += c * falloff;
            total += falloff;
        }
    }
    if (total <= 0.0) {
        outGlow.write(float4(0.0), gid);
        return;
    }
    float3 glow = acc / max(total, 1e-4) * (total / (float)STEPS) * 2.0;
    // Clamp so a bright sun can never blow out the frame; the tonemap stage
    // rolls the rest off filmically.
    glow = min(glow * params.sunScreen.w, float3(1.25));
    outGlow.write(float4(glow, 1.0), gid);
}

// ----------------------------------------------------------------------------
// Kernel 4: motion blur - blend the final image with the previous final image
//   by the camera-motion amount. Gives smooth trailing on fast camera movement.
// ----------------------------------------------------------------------------

kernel void mm_motionblur(
    texture2d<float, access::read>  curColor  [[texture(0)]],
    texture2d<float, access::read>  prevColor [[texture(1)]],
    texture2d<float, access::write> outColor  [[texture(2)]],
    constant EffectParams          &params    [[buffer(0)]],
    uint2 gid                                                  [[thread_position_in_grid]])
{
    if (gid.x >= (uint)params.viewport.x || gid.y >= (uint)params.viewport.y) { return; }
    float amt = saturate(params.motionBlurAmount);
    float4 cur = curColor.read(gid);
    if (amt <= 0.002) {
        outColor.write(cur, gid);
        return;
    }
    float4 prev = prevColor.read(gid);
    outColor.write(mix(cur, prev, amt), gid);
}

// ----------------------------------------------------------------------------
// Kernel 5: motion vectors from depth reprojection (camera-motion exact,
// entity motion approximated) - feeds MetalFX Frame Interpolation.
// ----------------------------------------------------------------------------

kernel void mm_motion(
    texture2d<float, access::read>  inDepth   [[texture(0)]],
    texture2d<float, access::write> outMotion [[texture(1)]],
    constant MotionParams          &m         [[buffer(0)]],
    uint2 gid [[thread_position_in_grid]])
{
    if (gid.x >= (uint)m.inSize.x || gid.y >= (uint)m.inSize.y) { return; }

    float depth01 = inDepth.read(gid).r;
    float2 mv = float2(0.0);

    if (depth01 > 0.0 && m.firstFrame < 0.5) {
        float dist = m.near * m.far / (depth01 * (m.far - m.near) + m.near);
        float2 uv = (float2(gid) + 0.5) / m.inSize;
        float2 ndc = uv * 2.0 - 1.0;
        float3 eye = float3(ndc.x * dist * m.tanHalfFovY * m.aspect,
                            ndc.y * dist * m.tanHalfFovY,
                            -dist);
        // eye = rot * (world - eye)  =>  world = rot^T * eye + eyePos (rot is orthonormal)
        float3 world = (transpose(m.curRot) * float4(eye, 0.0)).xyz + m.curEye.xyz;
        float4 clipPrev = m.prevVP * float4(world - m.prevEye.xyz, 1.0);
        if (clipPrev.w > 0.0) {
            float2 ndcPrev = clipPrev.xy / clipPrev.w;
            float2 uvPrev = ndcPrev * 0.5 + 0.5;
            mv = (uvPrev - uv) * m.outSize;
        }
    }

    outMotion.write(float4(mv.x, mv.y, 0.0, 1.0), gid);
}

// ----------------------------------------------------------------------------
// Kernel 2: composite the (already temporally denoised) mask back onto the frame
// ----------------------------------------------------------------------------

kernel void mm_composite(
    texture2d<float, access::read>  inColor  [[texture(0)]],
    texture2d<float, access::read>  inMask   [[texture(1)]],
    texture2d<float>             volumetric [[texture(2)]],
    texture2d<float>            reflections [[texture(3)]],
    texture2d<float>                   giOut [[texture(4)]],
    texture2d<float>               rtShadow [[texture(5)]],
    texture2d<float>            worldLight [[texture(6)]],
    texture2d<float, access::write> outColor [[texture(7)]],
    constant EffectParams          &params   [[buffer(0)]],
    uint2 gid                                                [[thread_position_in_grid]])
{
    if (gid.x >= (uint)params.viewport.x || gid.y >= (uint)params.viewport.y) { return; }
    constexpr sampler smpV(address::clamp_to_edge, filter::linear);
    float2 uv = (float2(gid) + 0.5) / params.viewport;
    float4 color = inColor.read(gid);
    float4 mask = inMask.read(gid);

    float shadow = 1.0;
    float ao = 1.0;
    if (params.denoise > 0.5) {
        // 5-tap cross denoise of the ray-marched mask (cheap, removes most grain).
        float2 px = 1.0 / params.viewport;
        float4 acc = mask;
        acc += inMask.read((uint2)clamp(float2(gid) + float2(px.x * params.viewport.x, 0.0), 0.0, params.viewport - 1.0));
        acc += inMask.read((uint2)clamp(float2(gid) - float2(px.x * params.viewport.x, 0.0), 0.0, params.viewport - 1.0));
        acc += inMask.read((uint2)clamp(float2(gid) + float2(0.0, px.y * params.viewport.y), 0.0, params.viewport - 1.0));
        acc += inMask.read((uint2)clamp(float2(gid) - float2(0.0, px.y * params.viewport.y), 0.0, params.viewport - 1.0));
        mask = acc / 5.0;
    }

    // Sky pixels leave the shadow/AO passes with 1.0 factors, so they pass through untouched.
    shadow = mix(1.0, mask.r, params.sunStrength);
    ao = mix(1.0, mask.g, params.aoStrength);
    float3 lit = color.rgb * shadow * ao;
    lit += volumetric.sample(smpV, uv).rgb;
    lit += reflections.sample(smpV, uv).rgb;
    lit += giOut.sample(smpV, uv).rgb;
    // Hardware ray-traced shadows (1.0 = fully lit when disabled).
    lit *= mix(1.0, rtShadow.sample(smpV, uv).r, params.rtShadowMix);
    // Held light source illuminating real geometry (depth-reconstructed world space).
    lit += worldLight.sample(smpV, uv).rgb;
    outColor.write(float4(lit, color.a), gid);
}


// ----------------------------------------------------------------------------
// Kernel 10: HARDWARE ray traced shadows
//   Traces shadow rays against a primitive acceleration structure of per-block
//   bounding boxes (Metal hardware BVH traversal). Exact world-space occlusion:
//   occluders outside the view frustum still cast shadows.
// ----------------------------------------------------------------------------

struct RtShadowParams {
    float2   viewport;     // 0
    float    near;         // 8
    float    far;          // 12
    float4x4 viewRotInv;   // 16  (transpose of the world->eye rotation)
    float4   eye;          // 80  (world-space eye position)
    float4   sunDirWorld;  // 96  (xyz direction toward the sun, w = max ray length)
    float  tanHalfFovY;    // 112
    float  aspect;         // 116
    float  jitter;         // 120
    float  rays;           // 124
};                         // size 128

kernel void mm_rt_shadows(
    texture2d<float>               depthSampler [[texture(0)]],
    texture2d<float, access::write> outShadow [[texture(1)]],
    primitive_acceleration_structure accel    [[buffer(0)]],
    constant RtShadowParams        &p         [[buffer(1)]],
    uint2 gid                                                 [[thread_position_in_grid]])
{
    if (gid.x >= (uint)p.viewport.x || gid.y >= (uint)p.viewport.y) { return; }
    constexpr sampler dsmp(address::clamp_to_edge, filter::nearest);

    float2 uv = (float2(gid) + 0.5) / p.viewport;
    float depth01 = depthSampler.sample(dsmp, uv).r;
    if (depth01 <= 0.0) { outShadow.write(float4(1.0), gid); return; } // sky

    float dist = p.near * p.far / (depth01 * (p.far - p.near) + p.near);
    float2 ndc = uv * 2.0 - 1.0;
    float3 eyeP = float3(ndc.x * dist * p.tanHalfFovY * p.aspect,
                         ndc.y * dist * p.tanHalfFovY,
                         -dist);
    float3 world = (p.viewRotInv * float4(eyeP, 0.0)).xyz + p.eye.xyz;

    float3 sunDir = normalize(p.sunDirWorld.xyz);
    int rays = (int)clamp(p.rays, 1.0, 4.0);
    float jit = hashJitter(uv * p.viewport, p.jitter);
    float occluded = 0.0;

    intersector<triangle_data> isect;
    for (int r = 0; r < rays; r++) {
        // Small cone around the sun direction for penumbra softening.
        float2 j2 = float2(fract(jit * (float)(r + 1) * 0.6180339) - 0.5,
                           fract(jit * (float)(r + 1) * 0.7548776) - 0.5) * 0.05;
        float3 dir = normalize(sunDir + float3(j2.x, 0.0, j2.y));
        ray shadowRay(world + dir * 0.05, dir, 0.0, p.sunDirWorld.w);
        auto res = isect.intersect(shadowRay, accel);
        if (res.type != intersection_type::none) {
            occluded += 1.0;
        }
    }
    float shadow = 1.0 - occluded / (float)rays;
    outShadow.write(float4(shadow, 1.0, 1.0, 1.0), gid);
}


// ----------------------------------------------------------------------------
// Kernel 11: held light source (torch/lantern) illuminating WORLD geometry
//   Reconstructs world positions from depth, applies inverse-square falloff with
//   a normal term so nearby walls, floors and entities are genuinely lit - the
//   screen-space equivalent of a dynamic light.
// ----------------------------------------------------------------------------

struct WorldLightParams {
    float2   viewport;    // 0
    float    near;        // 8
    float    far;         // 12
    float4x4 viewRotInv;  // 16  (transpose of world->eye rotation)
    float4   eye;         // 80  (world eye position)
    float4   lightPos;    // 96  (xyz world position, w = radius)
    float4   lightColor;  // 112 (rgb colour, w = intensity)
    float    tanHalfFovY; // 128
    float    aspect;      // 132
    float    pad0;        // 136
    float    pad1;        // 140
};                        // 144

kernel void mm_world_light(
    texture2d<float>               depthSampler [[texture(0)]],
    texture2d<float, access::write> outLight   [[texture(1)]],
    constant WorldLightParams      &p          [[buffer(0)]],
    uint2 gid                                                 [[thread_position_in_grid]])
{
    if (gid.x >= (uint)p.viewport.x || gid.y >= (uint)p.viewport.y) { return; }
    if (p.lightColor.w <= 0.001) { outLight.write(float4(0.0), gid); return; }
    constexpr sampler dsmp(address::clamp_to_edge, filter::nearest);

    float2 uv = (float2(gid) + 0.5) / p.viewport;
    float2 texel = 1.0 / p.viewport;

    // Reconstructs the world position of a screen sample from depth.
    // (Local lambda-free helper logic is inlined for each sample.)
    float depthC = depthSampler.sample(dsmp, uv).r;
    if (depthC <= 0.0) { outLight.write(float4(0.0), gid); return; } // sky: no light

    float distC = p.near * p.far / (depthC * (p.far - p.near) + p.near);
    float2 ndc = uv * 2.0 - 1.0;
    float3 eyeC = float3(ndc.x * distC * p.tanHalfFovY * p.aspect,
                         ndc.y * distC * p.tanHalfFovY,
                         -distC);
    float3 wC = (p.viewRotInv * float4(eyeC, 0.0)).xyz + p.eye.xyz;

    // --- Proper normals: neighbour world positions (not NDC offsets) ---
    float2 uvR = uv + float2(texel.x, 0.0);
    float2 uvU = uv + float2(0.0, texel.y);
    float depthR = depthSampler.sample(dsmp, uvR).r;
    float depthU = depthSampler.sample(dsmp, uvU).r;

    float3 n = float3(0.0, 1.0, 0.0);
    if (depthR > 0.0 && depthU > 0.0) {
        float distR = p.near * p.far / (depthR * (p.far - p.near) + p.near);
        float distU = p.near * p.far / (depthU * (p.far - p.near) + p.near);
        float2 ndcR = uvR * 2.0 - 1.0;
        float2 ndcU = uvU * 2.0 - 1.0;
        float3 eyeR = float3(ndcR.x * distR * p.tanHalfFovY * p.aspect,
                             ndcR.y * distR * p.tanHalfFovY, -distR);
        float3 eyeU = float3(ndcU.x * distU * p.tanHalfFovY * p.aspect,
                             ndcU.y * distU * p.tanHalfFovY, -distU);
        float3 wR = (p.viewRotInv * float4(eyeR, 0.0)).xyz + p.eye.xyz;
        float3 wU = (p.viewRotInv * float4(eyeU, 0.0)).xyz + p.eye.xyz;
        float3 dR = wR - wC;
        float3 dU = wU - wC;
        // Ignore huge depth discontinuities (silhouettes) to avoid wild normals.
        if (length(dR) < 2.0 && length(dU) < 2.0) {
            n = normalize(cross(dU, dR));
            if (!isfinite(n.x) || !isfinite(n.y) || !isfinite(n.z)) { n = float3(0.0, 1.0, 0.0); }
        }
    }
    // Normals must face the camera for the Lambert term to light front faces.
    float3 viewDir = normalize(wC - p.eye.xyz);
    if (dot(n, viewDir) > 0.0) { n = -n; }

    float3 toLight = p.lightPos.xyz - wC;
    float d = length(toLight);
    if (d > p.lightPos.w) { outLight.write(float4(0.0), gid); return; }
    float3 l = toLight / max(d, 1e-4);

    // Smooth inverse-square-ish falloff that reaches zero at the radius.
    float x = d / p.lightPos.w;
    float atten = (1.0 / (1.0 + 6.0 * x * x)) * saturate(1.0 - x * x);
    // Lambert with a gentle ambient floor so surfaces never go fully black.
    float lambert = max(dot(n, l), 0.0) * 0.8 + 0.2;
    float3 contribution = p.lightColor.rgb * p.lightColor.w * atten * lambert;
    outLight.write(float4(contribution, 1.0), gid);
}
