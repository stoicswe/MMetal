package dev.mmetal.api;

/**
 * A custom post-process look ("style") contributed by a mod.
 *
 * <p>MMetal applies the look to every presented frame after its display transform
 * (tonemap), in the same slot as its built-in style. A style can either use one of
 * MMetal's built-in kernels, or ship its own Metal compute kernel in a {@code .metallib}
 * bundled inside the mod jar.</p>
 *
 * <h2>Kernel contract</h2>
 * <p>Your kernel is dispatched once per frame with one thread per output pixel:</p>
 * <pre>{@code
 * kernel void my_style(
 *     texture2d<float, access::read>   inColor  [[texture(0)]],  // LDR frame, full output size
 *     texture2d<float, access::sample> depth    [[texture(1)]],  // R32Float, reversed depth (1 = near)
 *     texture2d<float, access::write>  outColor [[texture(2)]],  // write your result here
 *     texture2d<float, access::sample> bloom    [[texture(3)]],  // emissive bloom (full size)
 *     constant EffectParams           &params   [[buffer(0)]],  // MMetal standard params
 *     constant float                  *custom   [[buffer(1)]],  // your customFloatCount() floats
 *     uint2 gid                                 [[thread_position_in_grid]])
 * {
 *     if (gid.x >= (uint)params.viewport.x || gid.y >= (uint)params.viewport.y) { return; }
 *     ...
 *     outColor.write(float4(result, 1.0), gid);
 * }
 * }</pre>
 *
 * <p>{@code EffectParams} (144 bytes, all float unless noted):</p>
 * <pre>
 * viewport@0 (float2)   near@8   far@12   sunStrength@16  aoStrength@20
 * steps@24              rayLength@28     sunDirEye@32 (float4)
 * time@48               tanHalfFovY@52   aspect@56
 * sunScreen@64 (float4) motionBlurAmount@80 (style: 1 = depth valid, 0 = menu frame)
 * exposure@84           tonemapMode@88   headroom@92 (style: applied strength 0..1)
 * reflectionStrength@96 giStrength@100   denoise@104     bloomStrength@108
 * handGlow@112 (float4) handGlowPos@128 (float2)         rtShadowMix@136
 * styleId@140
 * </pre>
 *
 * <h2>Registration</h2>
 * <pre>{@code
 * MMetalStyles.register(new MMetalStyle() {
 *     public String id()               { return "mymod:film"; }
 *     public String displayName()      { return "Film"; }
 *     public String description()      { return "Warm 35mm look."; }
 *     public String metallibResource() { return "/assets/mymod/metal/film.metallib"; }
 *     public String kernelName()       { return "my_film"; }
 * });
 * }</pre>
 *
 * <p>Register during mod initialisation on the client. Unknown or failing styles are
 * skipped with a log message and never crash the game.</p>
 */
public interface MMetalStyle {

	/** Stable id, lowercase, e.g. {@code "mymod:film"}. */
	String id();

	/** Name shown in the Style settings tab. */
	String displayName();

	/** One-line description shown in the Style settings tab. */
	String description();

	/** Strength applied when the user first selects this style (0..1). */
	default double defaultStrength() {
		return 1.0;
	}

	/** True for MMetal's own styles; their kernel lives in MMetal's metallib. */
	default boolean builtIn() {
		return false;
	}

	/** Classpath resource of your compiled metallib, e.g. {@code "/assets/mymod/metal/style.metallib"}. */
	default String metallibResource() {
		return null;
	}

	/**
	 * Path to your metallib inside your mod jar. Preferred over
	 * {@link #metallibResource()} for data-driven style jars
	 * (populated automatically from {@code mmetal/styles.json}).
	 */
	default java.nio.file.Path metallibPath() {
		return null;
	}

	/** Compute kernel name inside {@link #metallibResource()}. */
	default String kernelName() {
		return null;
	}

	/** Number of floats passed to buffer(1), max 64. */
	default int customFloatCount() {
		return 0;
	}

	/** Fill the custom float buffer (buffer(1)). Called once per frame on the render thread. */
	default void writeCustomParams(float[] params) {
	}
}
