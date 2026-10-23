package dev.mmetal.metal.render;

import dev.mmetal.metal.nativebridge.Layouts;
import dev.mmetal.metal.nativebridge.MetalNative;
import dev.mmetal.metal.nativebridge.ObjC;
import dev.mmetal.metal.nativebridge.ObjCSend;
import org.slf4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import static dev.mmetal.metal.nativebridge.Layouts.BOOL;
import static dev.mmetal.metal.nativebridge.Layouts.FLOAT;
import static dev.mmetal.metal.nativebridge.Layouts.NSUINTEGER;
import static dev.mmetal.metal.nativebridge.Layouts.POINTER;

/**
 * True HDR (EDR) presentation for macOS.
 *
 * <p>Creates a {@code CAMetalLayer} overlay on the game window configured for extended
 * dynamic range (RGBA16Float + {@code kCGColorSpaceExtendedLinearSRGB}). The Metal
 * pipeline's tonemap stage encodes scene-linear output directly into the drawable's
 * texture - values above 1.0 are brighter than SDR white, and macOS tonemaps the
 * composite to the panel's real headroom. The same presentation model the P.T. PC
 * port uses for its Metal renderer.</p>
 *
 * <p>When the overlay is active, the GL swap is skipped entirely: the Metal layer
 * covers the window.</p>
 */
public final class HdrPresenter {
	public static final String QUARTZCORE_FRAMEWORK = "/System/Library/Frameworks/QuartzCore.framework/QuartzCore";
	public static final String APPKIT_FRAMEWORK = "/System/Library/Frameworks/AppKit.framework/AppKit";
	/** SDL3 property carrying the NSWindow* of an SDL window. */
	public static final String SDL_PROP_WINDOW_COCOA_WINDOW = "SDL.window.cocoa.window";
	/** MTLPixelFormatRGBA16Float. */
	public static final long PIXEL_FORMAT_RGBA16_FLOAT = 115;

	private static final Arena ARENA = Arena.global();
	private static final StructLayout CG_SIZE = MemoryLayout.structLayout(
		ValueLayout.JAVA_DOUBLE.withName("width"), ValueLayout.JAVA_DOUBLE.withName("height"));
	private static final StructLayout CG_RECT = MemoryLayout.structLayout(
		ValueLayout.JAVA_DOUBLE.withName("x"), ValueLayout.JAVA_DOUBLE.withName("y"),
		ValueLayout.JAVA_DOUBLE.withName("w"), ValueLayout.JAVA_DOUBLE.withName("h"));

	private final Logger logger;
	private MemorySegment metalLayer;
	private MemorySegment contentLayer;
	private long lastWidth = -1;
	private long lastHeight = -1;
	private float headroom = 1.0f;
	private boolean ready;

	public HdrPresenter(Logger logger) {
		this.logger = logger;
	}

	public boolean isReady() {
		return ready;
	}

	public float headroom() {
		return headroom;
	}

	/**
	 * Attaches an EDR CAMetalLayer above the SDL window's content view.
	 *
	 * @return true when the overlay was created.
	 */
	public boolean initialize(long windowHandle) {
		if (ready) {
			return true;
		}
		try {
			var sdl = java.lang.foreign.Linker.nativeLinker().defaultLookup();
			var linker = java.lang.foreign.Linker.nativeLinker();
			MethodHandle getWindowProperties = Layouts.fn(linker,
				sdl.find("SDL_GetWindowProperties").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
			MethodHandle getPointerProperty = Layouts.fn(linker,
				sdl.find("SDL_GetPointerProperty").orElseThrow(),
				FunctionDescriptor.of(POINTER, ValueLayout.JAVA_INT, POINTER, POINTER));
			int props = (int) getWindowProperties.invoke(windowHandle);
			MemorySegment nsWindow = (MemorySegment) getPointerProperty.invoke(
				props, ObjC.cstr(SDL_PROP_WINDOW_COCOA_WINDOW), MemorySegment.NULL);
			if (nsWindow == null || nsWindow.address() == 0) {
				logger.warn("MMetal HDR: no Cocoa window handle from SDL");
				return false;
			}

			// Ensure the Objective-C classes are registered, then walk
			// NSWindow -> contentView -> backing layer (AppKit + QuartzCore).
			ObjC.framework(APPKIT_FRAMEWORK, "AppKit");
			ObjC.framework(QUARTZCORE_FRAMEWORK, "QuartzCore");
			MemorySegment contentView = objSend(POINTER, nsWindow, "contentView");
			require(contentView, "contentView");
			objSendVoid(contentView, "setWantsLayer:", (byte) 1);
			MemorySegment contentLayer = objSend(POINTER, contentView, "layer");
			require(contentLayer, "contentView.layer");

			MemorySegment layer = objSend(POINTER, ObjC.cls("CAMetalLayer"), "layer");
			require(layer, "CAMetalLayer layer");
			objSendVoid(layer, "setDevice:", MetalNative.device());
			objSendVoidLong(layer, "setPixelFormat:", PIXEL_FORMAT_RGBA16_FLOAT);

			MemorySegment name = MetalNative.stringToNsString("kCGColorSpaceExtendedLinearSRGB");
			var coreGraphics = ObjC.framework(
				"/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", "CoreGraphics");
			MethodHandle csCreate = Layouts.fn(linker,
				coreGraphics.find("CGColorSpaceCreateWithName").orElseThrow(),
				FunctionDescriptor.of(POINTER, POINTER));
			MemorySegment colorSpace = (MemorySegment) csCreate.invoke(name);
			require(colorSpace, "CGColorSpaceCreateWithName");
			objSendVoid(layer, "setColorSpace:", colorSpace);
			objSendVoid(layer, "setWantsExtendedDynamicRangeContent:", (byte) 1);
			objSendVoid(layer, "setFramebufferOnly:", (byte) 1);
			objSendVoid(contentLayer, "addSublayer:", layer);

			metalLayer = layer;
			this.contentLayer = contentLayer;
			ready = true;
			logger.info("MMetal HDR: EDR CAMetalLayer overlay installed (RGBA16Float, extended linear sRGB)");
			return true;
		} catch (Throwable t) {
			logger.warn("MMetal HDR: EDR presentation unavailable - staying on the GL path", t);
			ready = false;
			return false;
		}
	}

	/**
	 * Acquires the next CAMetalLayer drawable and updates the layer geometry.
	 *
	 * @return the drawable's MTLTexture to render into, or NULL when unavailable.
	 */
	public MemorySegment acquireDrawable(int width, int height) {
		if (!ready) {
			return MemorySegment.NULL;
		}
		try {
			if (width != lastWidth || height != lastHeight) {
				MemorySegment size = ARENA.allocate(CG_SIZE);
				size.set(ValueLayout.JAVA_DOUBLE, 0, (double) width);
				size.set(ValueLayout.JAVA_DOUBLE, 8, (double) height);
				invoke(metalLayer, "setDrawableSize:", size, CG_SIZE);
				MemorySegment frame = ARENA.allocate(CG_RECT);
				frame.set(ValueLayout.JAVA_DOUBLE, 0, 0.0);
				frame.set(ValueLayout.JAVA_DOUBLE, 8, 0.0);
				frame.set(ValueLayout.JAVA_DOUBLE, 16, (double) width);
				frame.set(ValueLayout.JAVA_DOUBLE, 24, (double) height);
				invoke(metalLayer, "setFrame:", frame, CG_RECT);
				lastWidth = width;
				lastHeight = height;
			}

			// Keep the overlay the TOPMOST sublayer. AppKit/SDL can append or reorder
			// the GL content's layer at any view update; if the GL layer ends up above
			// ours, the window shows the GL front buffer - which never swaps while the
			// overlay is active - i.e. a permanent black window.
			if (contentLayer != null && contentLayer.address() != 0) {
				objSendVoid(metalLayer, "removeFromSuperlayer");
				objSendVoid(contentLayer, "addSublayer:", metalLayer);
			}

			headroom = objSendFloat(metalLayer, "maximumExtendedDynamicRangeColorComponentValue");
			if (headroom < 1.0f) {
				headroom = 1.0f;
			}

			MemorySegment drawable = objSend(POINTER, metalLayer, "nextDrawable");
			if (drawable == null || drawable.address() == 0) {
				lastDrawable = null;
				return MemorySegment.NULL; // swapchain backpressured - skip this frame
			}
			MemorySegment texture = objSend(POINTER, drawable, "texture");
			if (texture == null || texture.address() == 0) {
				lastDrawable = null;
				return MemorySegment.NULL;
			}
			lastDrawable = drawable;
			return texture;
		} catch (Throwable t) {
			logger.warn("MMetal HDR acquire failed", t);
			return MemorySegment.NULL;
		}
	}

	private MemorySegment lastDrawable;

	/** Stores the drawable to present (called by acquireDrawable). */
	public void setDrawable(MemorySegment drawable) {
		this.lastDrawable = drawable;
	}

	/** Presents the previously acquired drawable to the display (CoreAnimation vsync). */
	public void presentDrawable() {
		if (!ready || lastDrawable == null || lastDrawable.address() == 0) {
			return;
		}
		try {
			objSendVoid(lastDrawable, "present");
		} catch (Throwable t) {
			logger.warn("MMetal HDR present failed", t);
		}
	}

	// ---- objc helpers -------------------------------------------------------

	private static MemorySegment objSend(MemoryLayout ret, MemorySegment recv, String selector) {
		return (MemorySegment) ObjCSend.invoke(
			ObjC.send(FunctionDescriptor.of(ret, POINTER, POINTER)), recv, ObjC.sel(selector));
	}

	private static MemorySegment objSend(MemorySegment recv, String selector) {
		return objSend(POINTER, recv, selector);
	}

	private static void objSendVoid(MemorySegment recv, String selector) {
		ObjCSend.invoke(ObjC.send(FunctionDescriptor.ofVoid(POINTER, POINTER)),
			recv, ObjC.sel(selector));
	}

	private static void objSendVoid(MemorySegment recv, String selector, MemorySegment arg) {
		ObjCSend.invoke(ObjC.send(FunctionDescriptor.ofVoid(POINTER, POINTER, POINTER)),
			recv, ObjC.sel(selector), arg);
	}

	private static void objSendVoid(MemorySegment recv, String selector, byte arg) {
		ObjCSend.invoke(ObjC.send(FunctionDescriptor.ofVoid(POINTER, POINTER, BOOL)),
			recv, ObjC.sel(selector), arg);
	}

	private static void objSendVoidLong(MemorySegment recv, String selector, long arg) {
		ObjCSend.invoke(ObjC.send(FunctionDescriptor.ofVoid(POINTER, POINTER, NSUINTEGER)),
			recv, ObjC.sel(selector), arg);
	}

	private static float objSendFloat(MemorySegment recv, String selector) {
		Object r = ObjCSend.invoke(ObjC.send(FunctionDescriptor.of(FLOAT, POINTER, POINTER)),
			recv, ObjC.sel(selector));
		return r instanceof Float f ? f : 1.0f;
	}

	private static void invoke(MemorySegment recv, String selector, MemorySegment arg, MemoryLayout argLayout) {
		ObjCSend.invoke(ObjC.send(FunctionDescriptor.ofVoid(POINTER, POINTER, argLayout)),
			recv, ObjC.sel(selector), arg);
	}

	private static void require(MemorySegment segment, String what) {
		if (segment == null || segment.address() == 0) {
			throw new IllegalStateException(what + " returned nil");
		}
	}
}