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
 * device and wraps its three compute kernels.
 */
public final class MetalLibrary {
	private static final MethodHandle MH_NEW_LIBRARY_URL = ObjC.send(
		FunctionDescriptor.of(POINTER, POINTER, POINTER, POINTER, POINTER));

	private final MemorySegment library;
	private final MemorySegment depthPipeline;
	private final MemorySegment ssrPipeline;
	private final MemorySegment compositePipeline;
	private final MemorySegment motionPipeline;
	private final MemorySegment volumetricPipeline;
	private final MemorySegment motionBlurPipeline;
	private final MemorySegment tonemapPipeline;
	private final MemorySegment stylePipeline;
	private final MemorySegment reflectionsPipeline;
	private final MemorySegment giPipeline;
	private final MemorySegment bloomExtractPipeline;
	private final MemorySegment bloomBlurPipeline;
	private final MemorySegment rtShadowPipeline;
	private final MemorySegment worldLightPipeline;

	private MetalLibrary(MemorySegment library, MemorySegment depthPipeline, MemorySegment ssrPipeline,
	                     MemorySegment compositePipeline, MemorySegment motionPipeline,
	                     MemorySegment volumetricPipeline, MemorySegment motionBlurPipeline,
	                     MemorySegment tonemapPipeline, MemorySegment stylePipeline,
	                     MemorySegment reflectionsPipeline,
	                     MemorySegment giPipeline, MemorySegment bloomExtractPipeline,
	                     MemorySegment bloomBlurPipeline, MemorySegment rtShadowPipeline,
	                     MemorySegment worldLightPipeline) {
		this.library = library;
		this.depthPipeline = depthPipeline;
		this.ssrPipeline = ssrPipeline;
		this.compositePipeline = compositePipeline;
		this.motionPipeline = motionPipeline;
		this.volumetricPipeline = volumetricPipeline;
		this.motionBlurPipeline = motionBlurPipeline;
		this.tonemapPipeline = tonemapPipeline;
		this.stylePipeline = stylePipeline;
		this.reflectionsPipeline = reflectionsPipeline;
		this.giPipeline = giPipeline;
		this.bloomExtractPipeline = bloomExtractPipeline;
		this.bloomBlurPipeline = bloomBlurPipeline;
		this.rtShadowPipeline = rtShadowPipeline;
		this.worldLightPipeline = worldLightPipeline;
	}

	public static MetalLibrary load() {
		try (InputStream in = MetalLibrary.class.getResourceAsStream("/metal/MMetalRaytrace.metallib")) {
			if (in == null) {
				throw new IllegalStateException("MMetalRaytrace.metallib missing from jar");
			}
			Path temp = Files.createTempFile("mmetal-raytrace", ".metallib");
			Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			MemorySegment pathStr = MetalNative.stringToNsString(temp.toAbsolutePath().toString());
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
			return new MetalLibrary(
				library,
				MetalNative.computePipeline(library, "mm_depth"),
				MetalNative.computePipeline(library, "mm_ssr_shadows"),
				MetalNative.computePipeline(library, "mm_composite"),
				MetalNative.computePipeline(library, "mm_motion"),
				MetalNative.computePipeline(library, "mm_volumetric"),
				MetalNative.computePipeline(library, "mm_motionblur"),
				MetalNative.computePipeline(library, "mm_tonemap"),
				MetalNative.computePipeline(library, "mm_style"),
				MetalNative.computePipeline(library, "mm_reflections"),
				MetalNative.computePipeline(library, "mm_rtgi"),
				MetalNative.computePipeline(library, "mm_bloom_extract"),
				MetalNative.computePipeline(library, "mm_bloom_blur"),
				MetalNative.computePipeline(library, "mm_rt_shadows"),
				MetalNative.computePipeline(library, "mm_world_light"));
		} catch (Exception e) {
			throw new IllegalStateException("Failed to load MMetal Metal library", e);
		}
	}

	public MemorySegment depthPipeline() {
		return depthPipeline;
	}

	public MemorySegment volumetricPipeline() {
		return volumetricPipeline;
	}

	public MemorySegment motionBlurPipeline() {
		return motionBlurPipeline;
	}

	public MemorySegment tonemapPipeline() {
		return tonemapPipeline;
	}

	public MemorySegment stylePipeline() {
		return stylePipeline;
	}

	public MemorySegment reflectionsPipeline() {
		return reflectionsPipeline;
	}

	public MemorySegment giPipeline() {
		return giPipeline;
	}

	public MemorySegment bloomExtractPipeline() {
		return bloomExtractPipeline;
	}

	public MemorySegment bloomBlurPipeline() {
		return bloomBlurPipeline;
	}

	public MemorySegment rtShadowPipeline() {
		return rtShadowPipeline;
	}

	public MemorySegment worldLightPipeline() {
		return worldLightPipeline;
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

	public MemorySegment library() {
		return library;
	}
}