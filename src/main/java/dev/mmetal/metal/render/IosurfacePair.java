package dev.mmetal.metal.render;

import dev.mmetal.metal.nativebridge.IosurfaceBridge;
import dev.mmetal.metal.nativebridge.MetalNative;

import java.lang.foreign.MemorySegment;

import static org.lwjgl.opengl.GL41C.*;

/**
 * A single zero-copy image shared between Minecraft's OpenGL context and Metal:
 * an IOSurface, a GL rectangle texture bound to it via CGLTexImageIOSurface2D, and
 * a Metal texture opened on the same surface. Writes by one API are visible to the
 * other (IOSurface performs implicit cross-API synchronization).
 */
final class IosurfacePair implements AutoCloseable {
	enum Kind {
		// GL: internalFormat GL_RGBA8, format GL_BGRA, type GL_UNSIGNED_INT_8_8_8_8_REV
		COLOR(IosurfaceBridge.GL_RGBA8, IosurfaceBridge.GL_BGRA,
			IosurfaceBridge.GL_UNSIGNED_INT_8_8_8_8_REV, MetalNative.PIXEL_FORMAT_BGRA8_UNORM),
		// GL: internalFormat GL_RGBA32F, format GL_RGBA, type GL_FLOAT
		FLOAT32(IosurfaceBridge.GL_RGBA32F, IosurfaceBridge.GL_RGBA,
			IosurfaceBridge.GL_FLOAT, MetalNative.PIXEL_FORMAT_RGBA32_FLOAT);

		final int glInternalFormat;
		final int glFormat;
		final int glType;
		final long metalPixelFormat;

		Kind(int glInternalFormat, int glFormat, int glType, long metalPixelFormat) {
			this.glInternalFormat = glInternalFormat;
			this.glFormat = glFormat;
			this.glType = glType;
			this.metalPixelFormat = metalPixelFormat;
		}
	}

	final int width;
	final int height;
	final Kind kind;
	final MemorySegment surface;
	final MemorySegment metalTexture;
	final int glTexture;

	private IosurfacePair(int width, int height, Kind kind, MemorySegment surface,
	                      MemorySegment metalTexture, int glTexture) {
		this.width = width;
		this.height = height;
		this.kind = kind;
		this.surface = surface;
		this.metalTexture = metalTexture;
		this.glTexture = glTexture;
	}

	static IosurfacePair create(int width, int height, Kind kind, long metalUsage) {
		MemorySegment surface = IosurfaceBridge.create(width, height);
		if (surface == null || surface.address() == 0) {
			throw new IllegalStateException("IOSurfaceCreate failed for " + width + "x" + height);
		}
		int glTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_RECTANGLE, glTexture);
		glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		int error = IosurfaceBridge.bindSurfaceToCurrentGlTexture(surface,
			IosurfaceBridge.GL_TEXTURE_RECTANGLE, kind.glInternalFormat,
			width, height, kind.glFormat, kind.glType);
		glBindTexture(GL_TEXTURE_RECTANGLE, 0);
		if (error != 0) {
			glDeleteTextures(glTexture);
			throw new IllegalStateException("CGLTexImageIOSurface2D failed with CGL error " + error);
		}
		MemorySegment metalTexture = MetalNative.newTextureFromIosurface(surface, width, height,
			kind.metalPixelFormat, metalUsage);
		return new IosurfacePair(width, height, kind, surface, metalTexture, glTexture);
	}

	/** Re-binds the IOSurface-backed texture so GL picks up Metal's latest writes
	 *  (Apple's docs: a glBindTexture on the reading context is sufficient after the
	 *  writer has completed; re-calling CGLTexImageIOSurface2D is only for setup). */
	void rebindGl() {
		glBindTexture(GL_TEXTURE_RECTANGLE, glTexture);
	}

	@Override
	public void close() {
		// IOSurface follows the CF "Create" rule: we own it and must CFRelease it.
		// Metal textures are ARC-managed/autoreleased objects - there is no -close
		// method, and sending one aborts the process (AGXG15GFamilyTexture close).
		try {
			if (surface != null && surface.address() != 0) {
				IosurfaceBridge.cfRelease(surface);
			}
		} catch (Throwable ignored) {
		}
		glDeleteTextures(glTexture);
	}
}