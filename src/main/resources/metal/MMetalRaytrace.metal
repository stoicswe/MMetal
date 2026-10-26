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
    float  pad0;
    float  motionBlurAmount;// 0..1 blend factor toward the previous frame
    float  reflectionStrength; // 0..1 ray traced reflections
    float  giStrength;         // 0..1 ray traced global illumination
    float  denoise;            // >0.5 = denoise the effect mask
    float  rtShadowMix;        // 0..1 blend in the hardware ray-traced shadow texture
    float  pad1;
    float  pad2;
    float  pad3;
};
// Java-side offsets: viewport@0, near@8, far@12, sunStrength@16, aoStrength@20,
// steps@24, rayLength@28, sunDirEye@32, time@48, tanHalfFovY@52, aspect@56,
// motionBlurAmount@64, reflectionStrength@68, giStrength@72, denoise@76,
// rtShadowMix@80, size 96.

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

// The GL side packs 24-bit depth as three bytes, each divided by 255 (see DEPTH_FRAG), so
// the channels read here are byte/255 - the bytes have to be restored before weighting
// them. Without the 255 every depth came out 255x too small (everything "hundreds of
// metres away": no parallax, no depth for any effect).
static inline float mmetalUnpackDepth(float3 packedRgb) {
    return (packedRgb.r * 65536.0 + packedRgb.g * 256.0 + packedRgb.b) * 255.0 / 16777215.0;
}

kernel void mm_depth(
    texture2d<float, access::read>  packedDepth [[texture(0)]],
    texture2d<float, access::write> outDepth    [[texture(1)]],
    uint2 gid                                                  [[thread_position_in_grid]])
{
    float4 p = packedDepth.read(gid);
    // p.r = most significant byte, p.g = middle, p.b = least significant (see GLSL writer).
	    float d = mmetalUnpackDepth(p.rgb);
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
// entity motion approximated) - feeds the MetalFX temporal upscaler.
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
    texture2d<float>            reflections [[texture(2)]],
    texture2d<float>                   giOut [[texture(3)]],
    texture2d<float>               rtShadow [[texture(4)]],
    texture2d<float, access::write> outColor [[texture(5)]],
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
    lit += reflections.sample(smpV, uv).rgb;
    lit += giOut.sample(smpV, uv).rgb;
    // Hardware ray-traced shadows (1.0 = fully lit when disabled).
    lit *= mix(1.0, rtShadow.sample(smpV, uv).r, params.rtShadowMix);
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
