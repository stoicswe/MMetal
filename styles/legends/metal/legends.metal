// MMetal style plugin example: "Legends" - painterly storybook look.
//
// Kernel contract: see docs/STYLES.md. This file is self-contained so it can be
// compiled and shipped without MMetal's own shader sources.
#include <metal_stdlib>
using namespace metal;

// Must match MMetal's EffectParams layout (144 bytes).
struct EffectParams {
    float2 viewport;
    float  near;
    float  far;
    float  sunStrength;
    float  aoStrength;
    float  steps;
    float  rayLength;
    float4 sunDirEye;
    float  time;
    float  tanHalfFovY;
    float  aspect;
    float4 sunScreen;
    float  motionBlurAmount;
    float  exposure;
    float  tonemapMode;
    float  headroom;
    float  reflectionStrength;
    float  giStrength;
    float  denoise;
    float  bloomStrength;
    float4 handGlow;
    float2 handGlowPos;
    float  rtShadowMix;
    float  styleId;
};

static float linearizeDepth(float depth01, constant EffectParams &p) {
    return p.near * p.far / (max(depth01, 1e-5) * (p.far - p.near) + p.near);
}

kernel void legends_style(
    texture2d<float, access::read>   inColor  [[texture(0)]],
    texture2d<float, access::sample> depth    [[texture(1)]],
    texture2d<float, access::write>  outColor [[texture(2)]],
    texture2d<float, access::sample> bloomTex [[texture(3)]],
    constant EffectParams           &params   [[buffer(0)]],
    constant float                  *custom   [[buffer(1)]],
    uint2 gid                                 [[thread_position_in_grid]])
{
    if (gid.x >= (uint)params.viewport.x || gid.y >= (uint)params.viewport.y) { return; }

    constexpr sampler smp(address::clamp_to_edge, filter::linear);
    float2 uv = (float2(gid) + 0.5) / params.viewport;
    float3 original = inColor.read(gid).rgb;
    float3 c = original;
    float strength = saturate(params.headroom);
    // Example custom parameter (buffer 1). Zero means "default" so the style works
    // without a code plugin filling the buffer.
    float hazeScale = saturate(custom[0] > 0.0 ? custom[0] : 1.0);

    // Depth is only trustworthy while no UI screen is open (menu blur passes leave
    // the depth attachment cleared to "far"); MMetal flags this in motionBlurAmount.
    float depthValid = step(0.5, params.motionBlurAmount);
    float viewDist = linearizeDepth(clamp(depth.sample(smp, uv).r, 0.0, 1.0), params);

    uint2 maxP = uint2((uint)params.viewport.x - 1u, (uint)params.viewport.y - 1u);
    float3 cL = inColor.read(uint2(gid.x > 1u ? gid.x - 2u : 0u, gid.y)).rgb;
    float3 cR = inColor.read(uint2(min(gid.x + 2u, maxP.x), gid.y)).rgb;
    float3 cD = inColor.read(uint2(gid.x, gid.y > 1u ? gid.y - 2u : 0u)).rgb;
    float3 cU = inColor.read(uint2(gid.x, min(gid.y + 2u, maxP.y))).rgb;

    // 1) Aerial perspective (luminance-preserving so it adapts to any biome/time).
    float haze = depthValid * smoothstep(64.0, 300.0, viewDist) * 0.55 * hazeScale;
    float lum0 = dot(c, float3(0.2126, 0.7152, 0.0722));
    float3 hazeCol = mix(float3(lum0), float3(0.72, 0.80, 0.90), 0.40) * 1.05;
    c = mix(c, hazeCol, haze);

    // 2) Painted block edges: soften and round hard voxel silhouettes with a gentle
    //    crease instead of drawing outlines.
    float3 soft = c * 0.42 + (cL + cR + cD + cU) * 0.145;
    float lL = dot(cL, float3(0.2126, 0.7152, 0.0722));
    float lR = dot(cR, float3(0.2126, 0.7152, 0.0722));
    float lD = dot(cD, float3(0.2126, 0.7152, 0.0722));
    float lU = dot(cU, float3(0.2126, 0.7152, 0.0722));
    float edge = smoothstep(0.05, 0.26, length(float2(lR - lL, lU - lD)));
    c = mix(c, soft, 0.16 + 0.40 * edge);
    c *= 1.0 - 0.14 * edge;

    // 3) Painterly grade: S-curve, airy exposure, warm balance, vivid palette,
    //    cool shadows and golden highlights.
    c = mix(c, c * c * (3.0 - 2.0 * c), 0.20);
    c = c * 1.02 + 0.005;
    c *= float3(1.02, 1.00, 0.97);
    float lum = dot(c, float3(0.2126, 0.7152, 0.0722));
    c = mix(float3(lum), c, 1.24);
    c += float3(-0.010, 0.004, 0.020) * (1.0 - lum);
    c += float3(0.050, 0.020, -0.015) * lum;

    // 4) Vignette.
    float2 vd = (uv - 0.5) * float2(params.aspect, 1.0);
    c *= 1.0 - dot(vd, vd) * 0.18;

    outColor.write(float4(saturate(mix(original, c, strength)), 1.0), gid);
}
