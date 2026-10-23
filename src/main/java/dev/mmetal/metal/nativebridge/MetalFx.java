package dev.mmetal.metal.nativebridge;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import static dev.mmetal.metal.nativebridge.Layouts.BOOL;
import static dev.mmetal.metal.nativebridge.Layouts.FLOAT;
import static dev.mmetal.metal.nativebridge.Layouts.NSUINTEGER;
import static dev.mmetal.metal.nativebridge.Layouts.POINTER;

/**
 * Bindings for the MetalFX framework: spatial upscaling (macOS 13+), temporal upscaling
 * (macOS 13+, experimental in MMetal) and frame interpolation (macOS 26+, the Metal 4 era
 * {@code MTLFXFrameInterpolator}).
 *
 * <p>See {@code MTLFXSpatialScaler.h}, {@code MTLFXTemporalScaler.h} and
 * {@code MTLFXFrameInterpolator.h} in the macOS SDK - selectors mirror those headers.</p>
 */
public final class MetalFx {
	public static final String METALFX_FRAMEWORK = "/System/Library/Frameworks/MetalFX.framework/MetalFX";

	// MTLFXSpatialScalerColorProcessingMode
	public static final long COLOR_PROCESSING_PERCEPTUAL = 0;
	public static final long COLOR_PROCESSING_LINEAR = 1;
	public static final long COLOR_PROCESSING_HDR = 2;

	private static final MemorySegment SPATIAL_DESCRIPTOR_CLASS;
	private static final MemorySegment TEMPORAL_DESCRIPTOR_CLASS;
	private static final MemorySegment FRAME_INTERPOLATOR_DESCRIPTOR_CLASS;

	// Availability probes (class methods: receiver is the class object).
	private static final MethodHandle MH_SUPPORTS_DEVICE = classBool("supportsDevice:");
	private static final MethodHandle MH_SPATIAL_CREATE = methodObj("newSpatialScalerWithDevice:");
	private static final MethodHandle MH_TEMPORAL_CREATE = methodObj("newTemporalScalerWithDevice:");
	private static final MethodHandle MH_INTERPOLATOR_CREATE = methodObj("newFrameInterpolatorWithDevice:");

	// Descriptor setters (shared across the three descriptor classes).
	private static final MethodHandle MH_SET_COLOR_FORMAT = setterLong("setColorTextureFormat:");
	private static final MethodHandle MH_SET_OUTPUT_FORMAT = setterLong("setOutputTextureFormat:");
	private static final MethodHandle MH_SET_DEPTH_FORMAT = setterLong("setDepthTextureFormat:");
	private static final MethodHandle MH_SET_MOTION_FORMAT = setterLong("setMotionTextureFormat:");
	private static final MethodHandle MH_SET_INPUT_W = setterLong("setInputWidth:");
	private static final MethodHandle MH_SET_INPUT_H = setterLong("setInputHeight:");
	private static final MethodHandle MH_SET_OUTPUT_W = setterLong("setOutputWidth:");
	private static final MethodHandle MH_SET_OUTPUT_H = setterLong("setOutputHeight:");
	private static final MethodHandle MH_SET_COLOR_MODE = setterLong("setColorProcessingMode:");
	private static final MethodHandle MH_SET_AUTO_EXPOSURE = setterBool("setAutoExposureEnabled:");

	// Spatial / temporal scaler instance methods.
	private static final MethodHandle MH_SC_SET_COLOR = setterObj("setColorTexture:");
	private static final MethodHandle MH_SC_SET_OUTPUT = setterObj("setOutputTexture:");
	private static final MethodHandle MH_SC_SET_DEPTH = setterObj("setDepthTexture:");
	private static final MethodHandle MH_SC_SET_MOTION = setterObj("setMotionTexture:");
	private static final MethodHandle MH_SC_SET_CONTENT_W = setterLong("setInputContentWidth:");
	private static final MethodHandle MH_SC_SET_CONTENT_H = setterLong("setInputContentHeight:");
	private static final MethodHandle MH_SC_SET_JITTER_X = setterFloat("setJitterOffsetX:");
	private static final MethodHandle MH_SC_SET_JITTER_Y = setterFloat("setJitterOffsetY:");
	private static final MethodHandle MH_SC_SET_MV_SCALE_X = setterFloat("setMotionVectorScaleX:");
	private static final MethodHandle MH_SC_SET_MV_SCALE_Y = setterFloat("setMotionVectorScaleY:");
	private static final MethodHandle MH_SC_SET_RESET = setterBool("setReset:");
	private static final MethodHandle MH_SC_ENCODE = setterObj("encodeToCommandBuffer:");

	// Frame interpolator instance methods.
	private static final MethodHandle MH_FI_SET_PREV_COLOR = setterObj("setPrevColorTexture:");
	private static final MethodHandle MH_FI_SET_DELTA_TIME = setterFloat("setDeltaTime:");
	private static final MethodHandle MH_FI_SET_NEAR = setterFloat("setNearPlane:");
	private static final MethodHandle MH_FI_SET_FAR = setterFloat("setFarPlane:");
	private static final MethodHandle MH_FI_SET_FOV = setterFloat("setFieldOfView:");
	private static final MethodHandle MH_FI_SET_ASPECT = setterFloat("setAspectRatio:");
	private static final MethodHandle MH_FI_SET_RESET_HISTORY = setterBool("setShouldResetHistory:");
	private static final MethodHandle MH_FI_SET_DEPTH_REVERSED = setterBool("setDepthReversed:");

	private MetalFx() {
	}

	static {
		// Loading the framework's symbols first also loads it into the process, which
		// makes its Objective-C classes visible to objc_getClass.
		ObjC.framework(METALFX_FRAMEWORK, "MetalFX");
		SPATIAL_DESCRIPTOR_CLASS = ObjC.cls("MTLFXSpatialScalerDescriptor");
		TEMPORAL_DESCRIPTOR_CLASS = ObjC.cls("MTLFXTemporalScalerDescriptor");
		FRAME_INTERPOLATOR_DESCRIPTOR_CLASS = ObjC.cls("MTLFXFrameInterpolatorDescriptor");
	}

	// ------------------------------------------------------------------
	// Availability
	// ------------------------------------------------------------------

	public static boolean supportsSpatial(MemorySegment device) {
		if (!SystemInfo.supportsMetalFxUpscaling()) {
			return false;
		}
		return classBool(SPATIAL_DESCRIPTOR_CLASS, device);
	}

	public static boolean supportsTemporal(MemorySegment device) {
		if (!SystemInfo.supportsMetalFxUpscaling()) {
			return false;
		}
		return classBool(TEMPORAL_DESCRIPTOR_CLASS, device);
	}

	public static boolean supportsFrameInterpolation(MemorySegment device) {
		if (!SystemInfo.supportsFrameInterpolation()) {
			return false;
		}
		return classBool(FRAME_INTERPOLATOR_DESCRIPTOR_CLASS, device);
	}

	// ------------------------------------------------------------------
	// Spatial scaler
	// ------------------------------------------------------------------

	public static MemorySegment createSpatial(MemorySegment device, long colorFormat, long outputFormat,
	                                          int inputWidth, int inputHeight,
	                                          int outputWidth, int outputHeight,
	                                          long colorProcessingMode) {
		MemorySegment descriptor = MetalNative.allocInit(SPATIAL_DESCRIPTOR_CLASS);
		invoke(MH_SET_COLOR_FORMAT, descriptor, colorFormat);
		invoke(MH_SET_OUTPUT_FORMAT, descriptor, outputFormat);
		invoke(MH_SET_INPUT_W, descriptor, (long) inputWidth);
		invoke(MH_SET_INPUT_H, descriptor, (long) inputHeight);
		invoke(MH_SET_OUTPUT_W, descriptor, (long) outputWidth);
		invoke(MH_SET_OUTPUT_H, descriptor, (long) outputHeight);
		invoke(MH_SET_COLOR_MODE, descriptor, colorProcessingMode);
		MemorySegment scaler = (MemorySegment) invoke(MH_SPATIAL_CREATE, descriptor, device);
		require(scaler, "MTLFXSpatialScalerDescriptor.newSpatialScalerWithDevice:");
		return scaler;
	}

	/** BOOL arguments are C chars on arm64 - convert Java booleans up front. */
	private static byte bool(boolean value) {
		return (byte) (value ? 1 : 0);
	}

	// ------------------------------------------------------------------
	// Temporal scaler (experimental)
	// ------------------------------------------------------------------

	public static MemorySegment createTemporal(MemorySegment device, long colorFormat, long depthFormat,
	                                           long motionFormat, long outputFormat,
	                                           int inputWidth, int inputHeight,
	                                           int outputWidth, int outputHeight,
	                                           boolean autoExposure) {
		MemorySegment descriptor = MetalNative.allocInit(TEMPORAL_DESCRIPTOR_CLASS);
		invoke(MH_SET_COLOR_FORMAT, descriptor, colorFormat);
		invoke(MH_SET_DEPTH_FORMAT, descriptor, depthFormat);
		invoke(MH_SET_MOTION_FORMAT, descriptor, motionFormat);
		invoke(MH_SET_OUTPUT_FORMAT, descriptor, outputFormat);
		invoke(MH_SET_INPUT_W, descriptor, (long) inputWidth);
		invoke(MH_SET_INPUT_H, descriptor, (long) inputHeight);
		invoke(MH_SET_OUTPUT_W, descriptor, (long) outputWidth);
		invoke(MH_SET_OUTPUT_H, descriptor, (long) outputHeight);
		invoke(MH_SET_AUTO_EXPOSURE, descriptor, bool(autoExposure));
		MemorySegment scaler = (MemorySegment) invoke(MH_TEMPORAL_CREATE, descriptor, device);
		require(scaler, "MTLFXTemporalScalerDescriptor.newTemporalScalerWithDevice:");
		return scaler;
	}

	// ------------------------------------------------------------------
	// Frame interpolator
	// ------------------------------------------------------------------

	public static MemorySegment createFrameInterpolator(MemorySegment device, long colorFormat, long outputFormat,
	                                                    long depthFormat, long motionFormat,
	                                                    int inputWidth, int inputHeight,
	                                                    int outputWidth, int outputHeight) {
		MemorySegment descriptor = MetalNative.allocInit(FRAME_INTERPOLATOR_DESCRIPTOR_CLASS);
		invoke(MH_SET_COLOR_FORMAT, descriptor, colorFormat);
		invoke(MH_SET_OUTPUT_FORMAT, descriptor, outputFormat);
		invoke(MH_SET_DEPTH_FORMAT, descriptor, depthFormat);
		invoke(MH_SET_MOTION_FORMAT, descriptor, motionFormat);
		invoke(MH_SET_INPUT_W, descriptor, (long) inputWidth);
		invoke(MH_SET_INPUT_H, descriptor, (long) inputHeight);
		invoke(MH_SET_OUTPUT_W, descriptor, (long) outputWidth);
		invoke(MH_SET_OUTPUT_H, descriptor, (long) outputHeight);
		MemorySegment interpolator = (MemorySegment) invoke(MH_INTERPOLATOR_CREATE, descriptor, device);
		require(interpolator, "MTLFXFrameInterpolatorDescriptor.newFrameInterpolatorWithDevice:");
		return interpolator;
	}

	// ------------------------------------------------------------------
	// Scaler instance configuration + encode
	// ------------------------------------------------------------------

	public static void configureScaler(MemorySegment scaler, MemorySegment color, MemorySegment output,
	                                   int contentWidth, int contentHeight) {
		invoke(MH_SC_SET_COLOR, scaler, color);
		invoke(MH_SC_SET_OUTPUT, scaler, output);
		invoke(MH_SC_SET_CONTENT_W, scaler, (long) contentWidth);
		invoke(MH_SC_SET_CONTENT_H, scaler, (long) contentHeight);
	}

	public static void setTemporalState(MemorySegment scaler, MemorySegment depth, MemorySegment motion,
	                                    float jitterX, float jitterY,
	                                    float mvScaleX, float mvScaleY, boolean reset) {
		invoke(MH_SC_SET_DEPTH, scaler, depth);
		invoke(MH_SC_SET_MOTION, scaler, motion);
		invoke(MH_SC_SET_JITTER_X, scaler, jitterX);
		invoke(MH_SC_SET_JITTER_Y, scaler, jitterY);
		invoke(MH_SC_SET_MV_SCALE_X, scaler, mvScaleX);
		invoke(MH_SC_SET_MV_SCALE_Y, scaler, mvScaleY);
		invoke(MH_SC_SET_RESET, scaler, bool(reset));
	}

	public static void encodeScaler(MemorySegment scaler, MemorySegment commandBuffer) {
		invoke(MH_SC_ENCODE, scaler, commandBuffer);
	}

	public static void configureInterpolator(MemorySegment interpolator, MemorySegment color,
	                                         MemorySegment prevColor, MemorySegment depth, MemorySegment motion,
	                                         MemorySegment output,
	                                         float deltaTime, float nearPlane, float farPlane,
	                                         float fieldOfView, float aspectRatio,
	                                         boolean resetHistory) {
		invoke(MH_SC_SET_COLOR, interpolator, color);
		invoke(MH_FI_SET_PREV_COLOR, interpolator, prevColor);
		invoke(MH_SC_SET_DEPTH, interpolator, depth);
		invoke(MH_SC_SET_MOTION, interpolator, motion);
		invoke(MH_SC_SET_OUTPUT, interpolator, output);
		invoke(MH_FI_SET_DELTA_TIME, interpolator, deltaTime);
		invoke(MH_FI_SET_NEAR, interpolator, nearPlane);
		invoke(MH_FI_SET_FAR, interpolator, farPlane);
		invoke(MH_FI_SET_FOV, interpolator, fieldOfView);
		invoke(MH_FI_SET_ASPECT, interpolator, aspectRatio);
		invoke(MH_FI_SET_RESET_HISTORY, interpolator, bool(resetHistory));
		invoke(MH_FI_SET_DEPTH_REVERSED, interpolator, (byte) 1);
	}

	public static void encodeInterpolator(MemorySegment interpolator, MemorySegment commandBuffer) {
		invoke(MH_SC_ENCODE, interpolator, commandBuffer);
	}

	// ------------------------------------------------------------------
	// Plumbing
	// ------------------------------------------------------------------

	private static boolean classBool(MemorySegment cls, MemorySegment device) {
		try {
			Object result = MH_SUPPORTS_DEVICE.invoke(cls, device);
			return result instanceof Byte b && b != 0;
		} catch (Throwable t) {
			throw new IllegalStateException("supportsDevice: failed", t);
		}
	}

	/** Class method probe: handle shaped (classObject, device) with the selector baked in.
	 *  {@code supportsDevice:} returns ObjC BOOL (a byte on arm64). */
	private static MethodHandle classBool(String selector) {
		try {
			return MethodHandles.insertArguments(
				ObjC.send(FunctionDescriptor.of(BOOL, POINTER, POINTER, POINTER)),
				1, ObjC.sel(selector));
		} catch (Throwable t) {
			throw new IllegalStateException("Failed to bind selector " + selector, t);
		}
	}

	private static MethodHandle methodObj(String selector) {
		try {
			return MethodHandles.insertArguments(
				ObjC.send(FunctionDescriptor.of(POINTER, POINTER, POINTER, POINTER)),
				1, ObjC.sel(selector));
		} catch (Throwable t) {
			throw new IllegalStateException("Failed to bind selector " + selector, t);
		}
	}

	private static MethodHandle setterLong(String selector) {
		try {
			return MethodHandles.insertArguments(
				ObjC.send(FunctionDescriptor.ofVoid(POINTER, POINTER, NSUINTEGER)),
				1, ObjC.sel(selector));
		} catch (Throwable t) {
			throw new IllegalStateException("Failed to bind selector " + selector, t);
		}
	}

	private static MethodHandle setterFloat(String selector) {
		try {
			return MethodHandles.insertArguments(
				ObjC.send(FunctionDescriptor.ofVoid(POINTER, POINTER, FLOAT)),
				1, ObjC.sel(selector));
		} catch (Throwable t) {
			throw new IllegalStateException("Failed to bind selector " + selector, t);
		}
	}

	private static MethodHandle setterBool(String selector) {
		try {
			return MethodHandles.insertArguments(
				ObjC.send(FunctionDescriptor.ofVoid(POINTER, POINTER, BOOL)),
				1, ObjC.sel(selector));
		} catch (Throwable t) {
			throw new IllegalStateException("Failed to bind selector " + selector, t);
		}
	}

	private static MethodHandle setterObj(String selector) {
		try {
			return MethodHandles.insertArguments(
				ObjC.send(FunctionDescriptor.ofVoid(POINTER, POINTER, POINTER)),
				1, ObjC.sel(selector));
		} catch (Throwable t) {
			throw new IllegalStateException("Failed to bind selector " + selector, t);
		}
	}

	private static Object invoke(MethodHandle handle, Object... args) {
		try {
			return handle.invokeWithArguments(args);
		} catch (Throwable t) {
			throw new IllegalStateException("MetalFX call failed", t);
		}
	}

	private static void require(MemorySegment segment, String what) {
		if (segment == null || segment.address() == 0) {
			throw new IllegalStateException(what + " returned nil");
		}
	}
}
