package dev.mmetal.metal.nativebridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import static dev.mmetal.metal.nativebridge.Layouts.FLOAT;
import static dev.mmetal.metal.nativebridge.Layouts.INT;
import static dev.mmetal.metal.nativebridge.Layouts.MTL_SIZE;
import static dev.mmetal.metal.nativebridge.Layouts.NSUINTEGER;
import static dev.mmetal.metal.nativebridge.Layouts.POINTER;

/**
 * Hand-written bindings for the subset of Metal needed by MMetal: device, command queue,
 * textures (including IOSurface-backed ones), command buffers, compute encoders, blit
 * encoders, runtime shader compilation and compute pipeline state.
 *
 * <p>All handles are cached {@code objc_msgSend} downcalls; the class is effectively
 * allocation-free after initialization.</p>
 */
public final class MetalNative {
	public static final String METAL_FRAMEWORK = "/System/Library/Frameworks/Metal.framework/Metal";

	// ---- Pixel formats (from MTLPixelFormat.h) ----
	public static final long PIXEL_FORMAT_RGBA8_UNORM = 70;
	public static final long PIXEL_FORMAT_BGRA8_UNORM = 80;
	public static final long PIXEL_FORMAT_RG16_FLOAT = 65;
	public static final long PIXEL_FORMAT_R32_FLOAT = 55;
	public static final long PIXEL_FORMAT_RGBA32_FLOAT = 125;

	// ---- Texture usage (from MTLTexture.h) ----
	public static final long USAGE_SHADER_READ = 0x0001;
	public static final long USAGE_SHADER_WRITE = 0x0002;
	public static final long USAGE_RENDER_TARGET = 0x0004;

	// ---- Storage modes (MTLStorageMode, for MTLTextureDescriptor) ----
	public static final long STORAGE_MODE_SHARED = 0;
	public static final long STORAGE_MODE_MANAGED = 1;
	public static final long STORAGE_MODE_PRIVATE = 2;

	// ---- MTLResourceOptions (for MTLBuffer allocation) ----
	// newBufferWithBytes/Length:options: take MTLResourceOptions, not MTLStorageMode:
	// the CPU cache mode occupies bits 0-1 and the storage mode bits 4-5, so a raw
	// MTLStorageMode lands in the cache-mode field and Metal asserts "Invalid cacheMode".
	public static final long RESOURCE_STORAGE_SHARED = 0;
	public static final long RESOURCE_STORAGE_PRIVATE = 2L << 4;

	// ---- MTLTextureType ----
	public static final long TEXTURE_TYPE_2D = 2;

	private static final Arena ARENA = Arena.global();
	private static final MemorySegment DEVICE;
	private static final MemorySegment QUEUE;

	// Cached selectors.
	private static final MemorySegment SEL_ALLOC = ObjC.sel("alloc");
	private static final MemorySegment SEL_INIT = ObjC.sel("init");

	// Method handles. All ObjC handles are "baked": the selector is inserted into the
	// receiver's parameter slot, so handles have the shape (receiver, args...).
	private static final MethodHandle MH_STRING_UTF8 = baked(POINTER, "UTF8String");
	private static final MethodHandle MH_LOCALIZED_DESCRIPTION = baked(POINTER, "localizedDescription");

	// Device methods.
	private static final MethodHandle MH_NEW_QUEUE = baked(POINTER, "newCommandQueue");
	private static final MethodHandle MH_NEW_TEXTURE_DESC = baked(POINTER, "newTextureWithDescriptor:", POINTER);
	private static final MethodHandle MH_NEW_TEXTURE_IOSURFACE = bakedArgs(POINTER,
		"newTextureWithDescriptor:iosurface:plane:", POINTER, POINTER, NSUINTEGER);
	private static final MethodHandle MH_NEW_LIBRARY = bakedArgs(POINTER,
		"newLibraryWithSource:options:error:", POINTER, POINTER, POINTER);
	private static final MethodHandle MH_NEW_COMPUTE_PIPELINE = bakedArgs(POINTER,
		"newComputePipelineStateWithFunction:error:", POINTER, POINTER);
	private static final MethodHandle MH_DEVICE_NAME = baked(POINTER, "name");

	// Texture descriptor methods.
	private static final MethodHandle MH_TD_WIDTH = bakedVoid(NSUINTEGER, "setWidth:");
	private static final MethodHandle MH_TD_HEIGHT = bakedVoid(NSUINTEGER, "setHeight:");
	private static final MethodHandle MH_TD_PIXEL_FORMAT = bakedVoid(NSUINTEGER, "setPixelFormat:");
	private static final MethodHandle MH_TD_TEXTURE_TYPE = bakedVoid(NSUINTEGER, "setTextureType:");
	private static final MethodHandle MH_TD_STORAGE_MODE = bakedVoid(NSUINTEGER, "setStorageMode:");
	private static final MethodHandle MH_TD_USAGE = bakedVoid(NSUINTEGER, "setUsage:");

	// Command queue / buffer.
	private static final MethodHandle MH_COMMAND_BUFFER = baked(POINTER, "commandBuffer");
	private static final MethodHandle MH_COMMIT = bakedVoid("commit");
	private static final MethodHandle MH_WAIT_UNTIL_COMPLETED = bakedVoid("waitUntilCompleted");
	private static final MethodHandle MH_BUFFER_ERROR = baked(POINTER, "error");
	private static final MethodHandle MH_COMPUTE_ENCODER = baked(POINTER, "computeCommandEncoder");
	private static final MethodHandle MH_BLIT_ENCODER = baked(POINTER, "blitCommandEncoder");

	// Compute encoder.
	private static final MethodHandle MH_SET_PIPELINE = bakedVoid(POINTER, "setComputePipelineState:");
	private static final MethodHandle MH_SET_TEXTURE = bakedVoidArgs("setTexture:atIndex:", POINTER, NSUINTEGER);
	private static final MethodHandle MH_SET_BYTES = bakedVoidArgs("setBytes:length:atIndex:",
		POINTER, NSUINTEGER, NSUINTEGER);
	private static final MethodHandle MH_DISPATCH = bakedVoidArgs("dispatchThreadgroups:threadsPerThreadgroup:",
		MTL_SIZE, MTL_SIZE);
	private static final MethodHandle MH_END_ENCODING = bakedVoid("endEncoding");

	// Blit encoder.
	private static final MethodHandle MH_COPY_TEXTURE = bakedVoidArgs(
		"copyFromTexture:sourceSlice:sourceLevel:sourceOrigin:sourceSize:toTexture:destinationSlice:destinationLevel:destinationOrigin:",
		POINTER, NSUINTEGER, NSUINTEGER, MTL_SIZE, MTL_SIZE,
		POINTER, NSUINTEGER, NSUINTEGER, MTL_SIZE);

	// Library / pipeline introspection.
	private static final MethodHandle MH_NEW_FUNCTION = baked(POINTER, "newFunctionWithName:", POINTER);
	private static final MethodHandle MH_MAX_THREADS = baked(NSUINTEGER, "maxTotalThreadsPerThreadgroup");
	private static final MethodHandle MH_THREAD_WIDTH = baked(NSUINTEGER, "threadExecutionWidth");

	/** {@code MTLAccelerationStructureSizes {NSUInteger x3}} (24 bytes). */
	public static final java.lang.foreign.StructLayout MTL_AS_SIZES = java.lang.foreign.MemoryLayout.structLayout(
		NSUINTEGER.withName("accelerationStructureSize"),
		NSUINTEGER.withName("buildScratchBufferSize"),
		NSUINTEGER.withName("refitScratchBufferSize"));

	// Buffers / acceleration structures (hardware ray tracing).
	private static final MethodHandle MH_NEW_BUFFER = bakedArgs(POINTER,
		"newBufferWithBytes:length:options:", POINTER, NSUINTEGER, NSUINTEGER);
	private static final MethodHandle MH_NEW_BUFFER_LEN = bakedArgs(POINTER,
		"newBufferWithLength:options:", NSUINTEGER, NSUINTEGER);
	private static final MethodHandle MH_AS_SIZES = baked(MTL_AS_SIZES,
		"accelerationStructureSizesWithDescriptor:", POINTER);
	private static final MethodHandle MH_NEW_AS = baked(POINTER, "newAccelerationStructureWithSize:", NSUINTEGER);
	private static final MethodHandle MH_AS_ENCODER = baked(POINTER, "accelerationStructureCommandEncoder");
	private static final MethodHandle MH_BUILD_AS = bakedVoidArgs(
		"buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:",
		POINTER, POINTER, POINTER, NSUINTEGER);
	private static final MethodHandle MH_SET_AS = bakedVoidArgs("setAccelerationStructure:atBufferIndex:",
		POINTER, NSUINTEGER);
	private static final MethodHandle MH_SET_BUFFER = bakedVoidArgs("setBuffer:offset:atIndex:",
		POINTER, NSUINTEGER, NSUINTEGER);

	// GPU timing (MTLCommandBuffer, only valid after completion).
	private static final MethodHandle MH_GPU_START = baked(ValueLayout.JAVA_DOUBLE, "GPUStartTime");
	private static final MethodHandle MH_GPU_END = baked(ValueLayout.JAVA_DOUBLE, "GPUEndTime");

	// Reused struct scratch (render thread only).
	private static final MemorySegment SIZE_SCRATCH_A = ARENA.allocate(MTL_SIZE);
	private static final MemorySegment SIZE_SCRATCH_B = ARENA.allocate(MTL_SIZE);
	private static final MemorySegment ORIGIN_SCRATCH = ARENA.allocate(MTL_SIZE);
	private static final MemorySegment ERROR_SCRATCH = ARENA.allocate(POINTER);

	static {
		MemorySegment createDevice = ObjC.symbol(METAL_FRAMEWORK, "Metal", "MTLCreateSystemDefaultDevice");
		MethodHandle mh = Layouts.fn(java.lang.foreign.Linker.nativeLinker(), createDevice,
			FunctionDescriptor.of(POINTER));
		try {
			DEVICE = (MemorySegment) mh.invoke();
			if (DEVICE == null || DEVICE.address() == 0) {
				throw new IllegalStateException("MTLCreateSystemDefaultDevice returned nil");
			}
			QUEUE = (MemorySegment) MH_NEW_QUEUE.invoke(DEVICE);
			if (QUEUE == null || QUEUE.address() == 0) {
				throw new IllegalStateException("newCommandQueue returned nil");
			}
		} catch (Throwable t) {
			throw new ExceptionInInitializerError(t);
		}
	}

	private MetalNative() {
	}

	public static MemorySegment device() {
		return DEVICE;
	}

	public static MemorySegment queue() {
		return QUEUE;
	}

	public static String deviceName() {
		return string((MemorySegment) invoke(MH_DEVICE_NAME, DEVICE));
	}

	/** Creates an NSString from a Java string. */
	public static MemorySegment stringToNsString(String value) {
		try {
			MethodHandle fromUtf8 = ObjC.send(FunctionDescriptor.of(POINTER, POINTER, POINTER, POINTER));
			MemorySegment ns = (MemorySegment) fromUtf8.invoke(ObjC.cls("NSString"),
				ObjC.sel("stringWithUTF8String:"), ObjC.cstr(value));
			require(ns, "NSString stringWithUTF8String:");
			return ns;
		} catch (IllegalStateException e) {
			throw e;
		} catch (Throwable t) {
			throw new IllegalStateException("NSString creation failed", t);
		}
	}

	// ------------------------------------------------------------------
	// Textures
	// ------------------------------------------------------------------

	public static MemorySegment newTexture(int width, int height, long pixelFormat, long usage, long storageMode) {
		MemorySegment descriptor = allocInit(ObjC.cls("MTLTextureDescriptor"));
		invoke(MH_TD_WIDTH, descriptor, (long) width);
		invoke(MH_TD_HEIGHT, descriptor, (long) height);
		invoke(MH_TD_PIXEL_FORMAT, descriptor, pixelFormat);
		invoke(MH_TD_TEXTURE_TYPE, descriptor, TEXTURE_TYPE_2D);
		invoke(MH_TD_STORAGE_MODE, descriptor, storageMode);
		invoke(MH_TD_USAGE, descriptor, usage);
		MemorySegment texture = (MemorySegment) invoke(MH_NEW_TEXTURE_DESC, DEVICE, descriptor);
		require(texture, "newTextureWithDescriptor");
		return texture;
	}

	public static MemorySegment newTextureFromIosurface(MemorySegment ioSurface, int width, int height,
	                                                    long pixelFormat, long usage) {
		MemorySegment descriptor = allocInit(ObjC.cls("MTLTextureDescriptor"));
		invoke(MH_TD_WIDTH, descriptor, (long) width);
		invoke(MH_TD_HEIGHT, descriptor, (long) height);
		invoke(MH_TD_PIXEL_FORMAT, descriptor, pixelFormat);
		invoke(MH_TD_TEXTURE_TYPE, descriptor, TEXTURE_TYPE_2D);
		invoke(MH_TD_STORAGE_MODE, descriptor, STORAGE_MODE_SHARED);
		invoke(MH_TD_USAGE, descriptor, usage);
		MemorySegment texture = (MemorySegment) invoke(MH_NEW_TEXTURE_IOSURFACE, DEVICE, descriptor, ioSurface, 0L);
		require(texture, "newTextureWithDescriptor:iosurface:plane:");
		return texture;
	}

	// ------------------------------------------------------------------
	// Command encoding
	// ------------------------------------------------------------------

	public static MemorySegment newCommandBuffer() {
		return (MemorySegment) invoke(MH_COMMAND_BUFFER, QUEUE);
	}

	public static void commitAndWait(MemorySegment commandBuffer) {
		invoke(MH_COMMIT, commandBuffer);
		invoke(MH_WAIT_UNTIL_COMPLETED, commandBuffer);
		checkCommandBufferError(commandBuffer);
	}

	/** Commits without blocking (used when the caller measures the wait separately). */
	public static void commit(MemorySegment commandBuffer) {
		invoke(MH_COMMIT, commandBuffer);
	}

	/** Blocks until the command buffer completes and validates it. */
	public static void waitCompleted(MemorySegment commandBuffer) {
		invoke(MH_WAIT_UNTIL_COMPLETED, commandBuffer);
		checkCommandBufferError(commandBuffer);
	}

	/** Creates a shared-storage MTLBuffer from a native memory region. */
	public static MemorySegment newBuffer(MemorySegment bytes, long length) {
		MemorySegment buffer = (MemorySegment) invoke(MH_NEW_BUFFER, DEVICE, bytes, length,
			RESOURCE_STORAGE_SHARED);
		require(buffer, "newBufferWithBytes:length:options:");
		return buffer;
	}

	/** Creates an uninitialised MTLBuffer (used for private scratch space). */
	public static MemorySegment newBuffer(long length) {
		MemorySegment buffer = (MemorySegment) invoke(MH_NEW_BUFFER_LEN, DEVICE, length,
			RESOURCE_STORAGE_PRIVATE);
		require(buffer, "newBufferWithLength:options:");
		return buffer;
	}

	/** Builds a primitive acceleration structure from a prebuilt bounding-box geometry descriptor. */
	public static MemorySegment buildAccelerationStructure(MemorySegment descriptor) {
		// accelerationStructureSizesWithDescriptor: returns a 24-byte struct
		// (MTLAccelerationStructureSizes), so the downcall handle has a leading
		// SegmentAllocator parameter that receives the returned struct.
		MemorySegment sizes = (MemorySegment) invoke(MH_AS_SIZES, ARENA, DEVICE, descriptor);
		long asSize = sizes.get(java.lang.foreign.ValueLayout.JAVA_LONG, 0);
		long scratchSize = sizes.get(java.lang.foreign.ValueLayout.JAVA_LONG, 8);
		MemorySegment accel = (MemorySegment) invoke(MH_NEW_AS, DEVICE, asSize);
		require(accel, "newAccelerationStructureWithSize:");
		MemorySegment scratch = (MemorySegment) invoke(MH_NEW_BUFFER_LEN, DEVICE, scratchSize,
			RESOURCE_STORAGE_PRIVATE);
		require(scratch, "scratch buffer");

		MemorySegment cmd = newCommandBuffer();
		MemorySegment encoder = (MemorySegment) invoke(MH_AS_ENCODER, cmd);
		require(encoder, "accelerationStructureCommandEncoder");
		invoke(MH_BUILD_AS, encoder, accel, descriptor, scratch, 0L);
		invoke(MH_END_ENCODING, encoder);
		commitAndWait(cmd);
		// The scratch buffer was created +1 and the build has completed, so it is ours to drop.
		ObjCSend.invoke(ObjC.send(FunctionDescriptor.ofVoid(POINTER, POINTER)), scratch, ObjC.sel("release"));
		return accel;
	}

	/** Binds a primitive acceleration structure to a compute encoder's argument table. */
	public static void setAccelerationStructure(MemorySegment encoder, MemorySegment accel, long index) {
		invoke(MH_SET_AS, encoder, accel, index);
	}

	/** Binds a buffer range to a compute encoder's argument table. */
	public static void setBuffer(MemorySegment encoder, MemorySegment buffer, long offset, long index) {
		invoke(MH_SET_BUFFER, encoder, buffer, offset, index);
	}

	/** GPU execution time of a completed command buffer, in milliseconds. */
	public static double gpuTimeMs(MemorySegment commandBuffer) {
		try {
			double start = (double) MH_GPU_START.invoke(commandBuffer);
			double end = (double) MH_GPU_END.invoke(commandBuffer);
			double delta = (end - start) * 1000.0;
			return delta > 0.0 && delta < 10_000.0 ? delta : 0.0;
		} catch (Throwable t) {
			return 0.0;
		}
	}

	private static void checkCommandBufferError(MemorySegment commandBuffer) {
		MemorySegment error = (MemorySegment) invoke(MH_BUFFER_ERROR, commandBuffer);
		if (error != null && error.address() != 0) {
			String message = string((MemorySegment) invoke(MH_LOCALIZED_DESCRIPTION, error));
			throw new IllegalStateException("Metal command buffer failed: " + message);
		}
	}

	public static MemorySegment computeEncoder(MemorySegment commandBuffer) {
		return (MemorySegment) invoke(MH_COMPUTE_ENCODER, commandBuffer);
	}

	public static MemorySegment blitEncoder(MemorySegment commandBuffer) {
		return (MemorySegment) invoke(MH_BLIT_ENCODER, commandBuffer);
	}

	public static void setComputePipeline(MemorySegment encoder, MemorySegment pipeline) {
		invoke(MH_SET_PIPELINE, encoder, pipeline);
	}

	public static void setTexture(MemorySegment encoder, MemorySegment texture, long index) {
		invoke(MH_SET_TEXTURE, encoder, texture, index);
	}

	public static void setBytes(MemorySegment encoder, MemorySegment bytes, long length, long index) {
		invoke(MH_SET_BYTES, encoder, bytes, length, index);
	}

	public static void dispatch(MemorySegment encoder, long groupsX, long groupsY,
	                            long threadsX, long threadsY) {
		writeSize(SIZE_SCRATCH_A, groupsX, groupsY, 1);
		writeSize(SIZE_SCRATCH_B, threadsX, threadsY, 1);
		invoke(MH_DISPATCH, encoder, SIZE_SCRATCH_A, SIZE_SCRATCH_B);
	}

	public static void endEncoder(MemorySegment encoder) {
		invoke(MH_END_ENCODING, encoder);
	}

	/** Copies the full contents of one texture into another (must share dimensions/format). */
	public static void copyTexture(MemorySegment commandBuffer, MemorySegment from, MemorySegment to,
	                               int width, int height) {
		MemorySegment encoder = blitEncoder(commandBuffer);
		writeSize(ORIGIN_SCRATCH, 0, 0, 0);
		writeSize(SIZE_SCRATCH_A, width, height, 1);
		invoke(MH_COPY_TEXTURE, encoder,
			from, 0L, 0L, ORIGIN_SCRATCH, SIZE_SCRATCH_A,
			to, 0L, 0L, ORIGIN_SCRATCH);
		endEncoder(encoder);
	}

	// ------------------------------------------------------------------
	// Shaders
	// ------------------------------------------------------------------

	/** Compiles MSL source into a library; throws with the compiler message on failure. */
	public static MemorySegment compileLibrary(String mslSource) {
		MemorySegment nsSource = stringToNsString(mslSource);
		ERROR_SCRATCH.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
		MemorySegment library = (MemorySegment) invoke(MH_NEW_LIBRARY,
			DEVICE, nsSource, MemorySegment.NULL, ERROR_SCRATCH);
		MemorySegment error = ERROR_SCRATCH.get(ValueLayout.ADDRESS, 0);
		if (library == null || library.address() == 0) {
			String detail = error.address() != 0
				? string((MemorySegment) invoke(MH_LOCALIZED_DESCRIPTION, error))
				: "unknown error";
			throw new IllegalStateException("Metal shader compilation failed: " + detail);
		}
		return library;
	}

	public static MemorySegment function(MemorySegment library, String name) {
		// newFunctionWithName: takes an NSString*, not a C string.
		MemorySegment fn = (MemorySegment) invoke(MH_NEW_FUNCTION, library, stringToNsString(name));
		require(fn, "newFunctionWithName: " + name);
		return fn;
	}

	public static MemorySegment computePipeline(MemorySegment library, String name) {
		MemorySegment fn = function(library, name);
		ERROR_SCRATCH.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
		MemorySegment pipeline = (MemorySegment) invoke(MH_NEW_COMPUTE_PIPELINE,
			DEVICE, fn, ERROR_SCRATCH);
		MemorySegment error = ERROR_SCRATCH.get(ValueLayout.ADDRESS, 0);
		if (pipeline == null || pipeline.address() == 0) {
			String detail = error.address() != 0
				? string((MemorySegment) invoke(MH_LOCALIZED_DESCRIPTION, error))
				: "unknown error";
			throw new IllegalStateException("Compute pipeline creation failed for " + name + ": " + detail);
		}
		return pipeline;
	}

	public static long maxThreadsPerGroup(MemorySegment pipeline) {
		return (long) invoke(MH_MAX_THREADS, pipeline);
	}

	public static long threadExecutionWidth(MemorySegment pipeline) {
		return (long) invoke(MH_THREAD_WIDTH, pipeline);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	// NOTE: There is deliberately no closeObject() helper. Metal objects
	// (MTLTexture, MTLLibrary, ...) are ARC-managed/autoreleased; sending -close or
	// -release to them from Java aborts the process (unrecognized selector).

	public static MemorySegment allocInit(MemorySegment cls) {
		try {
			// +alloc and -init are (recv, sel) -> id calls.
			MethodHandle alloc = ObjC.send(FunctionDescriptor.of(POINTER, POINTER, POINTER));
			MemorySegment obj = (MemorySegment) alloc.invoke(cls, SEL_ALLOC);
			requireSegment(obj, "alloc");
			MethodHandle init = ObjC.send(FunctionDescriptor.of(POINTER, POINTER, POINTER));
			MemorySegment result = (MemorySegment) init.invoke(obj, SEL_INIT);
			requireSegment(result, "init");
			return result;
		} catch (IllegalStateException e) {
			throw e;
		} catch (Throwable t) {
			throw new IllegalStateException("alloc/init failed", t);
		}
	}

	private static void requireSegment(MemorySegment segment, String what) {
		if (segment == null || segment.address() == 0) {
			throw new IllegalStateException(what + " returned nil");
		}
	}

	public static String string(MemorySegment nsString) {
		if (nsString == null || nsString.address() == 0) {
			return "";
		}
		MemorySegment utf8 = (MemorySegment) invoke(MH_STRING_UTF8, nsString);
		if (utf8 == null || utf8.address() == 0) {
			return "";
		}
		return utf8.reinterpret(Long.MAX_VALUE).getString(0);
	}

	/** Reads an NSError via -localizedDescription (never call UTF8String on an NSError). */
	public static String nsErrorDescription(MemorySegment error) {
		if (error == null || error.address() == 0) {
			return "unknown error";
		}
		try {
			return string((MemorySegment) invoke(MH_LOCALIZED_DESCRIPTION, error));
		} catch (Throwable t) {
			return "unknown error";
		}
	}

	public static void writeSize(MemorySegment size, long w, long h, long d) {
		size.set(ValueLayout.JAVA_LONG, 0, w);
		size.set(ValueLayout.JAVA_LONG, 8, h);
		size.set(ValueLayout.JAVA_LONG, 16, d);
	}

	/**
	 * Bakes an {@code objc_msgSend} downcall into a handle shaped {@code (receiver, args...)}
	 * by inserting the selector into the receiver's parameter slot.
	 *
	 * <p>Struct results handed back through memory make the Linker prepend a
	 * {@code SegmentAllocator} parameter, moving the receiver to index 1 and the selector
	 * slot to index 2. Binding the selector at a fixed index once put a selector pointer
	 * into {@code objc_msgSend}'s receiver register and SIGSEGV'd
	 * {@code accelerationStructureSizesWithDescriptor:} (see RtShadowBvh), so the slot is
	 * derived from the linked handle's parameter count.</p>
	 */
	private static MethodHandle baked(FunctionDescriptor descriptor, String selector) {
		try {
			MethodHandle handle = ObjC.send(descriptor);
			int parameters = descriptor.argumentLayouts().size(); // receiver + selector + args
			int index = handle.type().parameterCount() == parameters ? 1 : 2;
			return MethodHandles.insertArguments(handle, index, ObjC.sel(selector));
		} catch (Throwable t) {
			throw new IllegalStateException("Failed to bind selector " + selector, t);
		}
	}

	private static MethodHandle baked(java.lang.foreign.MemoryLayout ret, String selector,
	                                  java.lang.foreign.MemoryLayout... args) {
		return baked(FunctionDescriptor.of(ret, prepend(POINTER, POINTER, args)), selector);
	}

	private static MethodHandle bakedVoid(String selector) {
		return baked(FunctionDescriptor.ofVoid(POINTER, POINTER), selector);
	}

	private static MethodHandle bakedVoid(java.lang.foreign.MemoryLayout arg, String selector) {
		return baked(FunctionDescriptor.ofVoid(POINTER, POINTER, arg), selector);
	}

	private static MethodHandle bakedVoidArgs(String selector, java.lang.foreign.MemoryLayout... args) {
		return baked(FunctionDescriptor.ofVoid(prepend(POINTER, POINTER, args)), selector);
	}

	private static MethodHandle bakedArgs(java.lang.foreign.MemoryLayout ret, String selector,
	                                      java.lang.foreign.MemoryLayout... args) {
		return baked(FunctionDescriptor.of(ret, prepend(POINTER, POINTER, args)), selector);
	}

	private static java.lang.foreign.MemoryLayout[] prepend(java.lang.foreign.MemoryLayout first,
	                                                        java.lang.foreign.MemoryLayout second,
	                                                        java.lang.foreign.MemoryLayout... rest) {
		java.lang.foreign.MemoryLayout[] all = new java.lang.foreign.MemoryLayout[2 + rest.length];
		all[0] = first;
		all[1] = second;
		System.arraycopy(rest, 0, all, 2, rest.length);
		return all;
	}

	private static Object invoke(MethodHandle handle, Object... args) {
		try {
			return handle.invokeWithArguments(args);
		} catch (Throwable t) {
			throw new IllegalStateException("Native call failed", t);
		}
	}

	private static void require(MemorySegment segment, String what) {
		if (segment == null || segment.address() == 0) {
			throw new IllegalStateException(what + " returned nil");
		}
	}

	/** Runs a block-shaped runnable guarded for callers that must not crash the game. */
	public static boolean available() {
		return DEVICE != null && DEVICE.address() != 0 && QUEUE != null && QUEUE.address() != 0;
	}

	public static Arena sharedArena() {
		return ARENA;
	}

	/** Float constant setter helper for scalar uniforms. */
	public static void setFloat(MemorySegment buffer, long offset, float value) {
		buffer.set(FLOAT, offset, value);
	}

	/** Int constant setter helper. */
	public static void setInt(MemorySegment buffer, long offset, int value) {
		buffer.set(INT, offset, value);
	}
}
