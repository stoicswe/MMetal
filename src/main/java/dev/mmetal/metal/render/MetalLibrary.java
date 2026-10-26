package dev.mmetal.metal.render;

import dev.mmetal.metal.nativebridge.MetalNative;
import dev.mmetal.metal.nativebridge.ObjC;
import dev.mmetal.metal.nativebridge.ObjCSend;

import java.io.InputStream;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.mmetal.metal.nativebridge.Layouts.POINTER;

/**
 * Loads the precompiled {@code MMetalRaytrace.metallib} (built from
 * {@code MMetalRaytrace.metal} with {@code xcrun metal} at build time) into the Metal
 * device and wraps its compute kernels.
 */
public final class MetalLibrary {
	private static final MethodHandle MH_NEW_LIBRARY_URL = ObjC.send(
		FunctionDescriptor.of(POINTER, POINTER, POINTER, POINTER, POINTER));

	private final MemorySegment library;
	private final MemorySegment depthPipeline;
	private final MemorySegment ssrPipeline;
	private final MemorySegment compositePipeline;
	private final MemorySegment motionPipeline;
	private final MemorySegment motionBlurPipeline;
	private final MemorySegment reflectionsPipeline;
	private final MemorySegment giPipeline;
	private final MemorySegment rtShadowPipeline;

	private MetalLibrary(MemorySegment library) {
		this.library = library;
		this.depthPipeline = MetalNative.computePipeline(library, "mm_depth");
		this.ssrPipeline = MetalNative.computePipeline(library, "mm_ssr_shadows");
		this.compositePipeline = MetalNative.computePipeline(library, "mm_composite");
		this.motionPipeline = MetalNative.computePipeline(library, "mm_motion");
		this.motionBlurPipeline = MetalNative.computePipeline(library, "mm_motionblur");
		this.reflectionsPipeline = MetalNative.computePipeline(library, "mm_reflections");
		this.giPipeline = MetalNative.computePipeline(library, "mm_rtgi");
		this.rtShadowPipeline = MetalNative.computePipeline(library, "mm_rt_shadows");
	}

	public static MetalLibrary load() {
		try (InputStream in = MetalLibrary.class.getResourceAsStream("/metal/MMetalRaytrace.metallib")) {
			if (in == null) {
				throw new IllegalStateException("MMetalRaytrace.metallib missing from jar");
			}
			Path temp = Files.createTempFile("mmetal-raytrace", ".metallib");
			Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			return new MetalLibrary(loadLibraryFromPath(temp));
		} catch (Exception e) {
			throw new IllegalStateException("Failed to load MMetal Metal library", e);
		}
	}

	/** Loads a metallib file into an MTLLibrary. */
	private static MemorySegment loadLibraryFromPath(Path metallib) throws Exception {
		MemorySegment pathStr = MetalNative.stringToNsString(metallib.toAbsolutePath().toString());
		MethodHandle fileUrl = ObjC.send(FunctionDescriptor.of(POINTER, POINTER, POINTER, POINTER));
		MemorySegment url = (MemorySegment) ObjCSend.invoke(fileUrl,
			ObjC.cls("NSURL"), ObjC.sel("fileURLWithPath:"), pathStr);
		if (url == null || url.address() == 0) {
			throw new IllegalStateException("NSURL fileURLWithPath: failed");
		}
		MemorySegment errorSlot = MetalNative.sharedArena().allocate(POINTER);
		errorSlot.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
		MemorySegment library = (MemorySegment) ObjCSend.invoke(MH_NEW_LIBRARY_URL,
			MetalNative.device(), ObjC.sel("newLibraryWithURL:error:"),
			url, errorSlot);
		if (library == null || library.address() == 0) {
			MemorySegment error = errorSlot.get(ValueLayout.ADDRESS, 0);
			String detail = error.address() != 0 ? MetalNative.nsErrorDescription(error) : "unknown error";
			throw new IllegalStateException("newLibraryWithURL failed: " + detail);
		}
		return library;
	}

	public MemorySegment depthPipeline() {
		return depthPipeline;
	}

	public MemorySegment motionBlurPipeline() {
		return motionBlurPipeline;
	}

	public MemorySegment reflectionsPipeline() {
		return reflectionsPipeline;
	}

	public MemorySegment giPipeline() {
		return giPipeline;
	}

	public MemorySegment rtShadowPipeline() {
		return rtShadowPipeline;
	}

	public MemorySegment ssrPipeline() {
		return ssrPipeline;
	}

	public MemorySegment compositePipeline() {
		return compositePipeline;
	}

	public MemorySegment motionPipeline() {
		return motionPipeline;
	}
}
