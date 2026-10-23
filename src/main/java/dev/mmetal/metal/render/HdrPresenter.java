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
	private MemorySegment window;
	private long lastWidth = -1;
	private long lastHeight = -1;
	private float lastScale = 1.0f;
	private float headroom = 1.0f;
	private boolean ready;
	private boolean failed;

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
		if (failed) {
			return false;
		}
		try {
			var linker = java.lang.foreign.Linker.nativeLinker();
			// LWJGL loads SDL with RTLD_LOCAL, so its symbols are not reachable through
			// the FFM default lookup. Ask the LWJGL SDL binding for the loaded library,
			// then take the two SDL3 entry points from it.
			var sdl = org.lwjgl.sdl.SDL.getLibrary();
			MethodHandle getWindowProperties = Layouts.fn(linker,
				MemorySegment.ofAddress(sdl.getFunctionAddress("SDL_GetWindowProperties")),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
			MethodHandle getPointerProperty = Layouts.fn(linker,
				MemorySegment.ofAddress(sdl.getFunctionAddress("SDL_GetPointerProperty")),
				FunctionDescriptor.of(POINTER, ValueLayout.JAVA_INT, POINTER, POINTER));
			int props = (int) getWindowProperties.invoke(windowHandle);
			MemorySegment nsWindow = (MemorySegment) getPointerProperty.invoke(
				props, ObjC.cstr(SDL_PROP_WINDOW_COCOA_WINDOW), MemorySegment.NULL);
			if (nsWindow == null || nsWindow.address() == 0) {
				logger.warn("MMetal HDR: no Cocoa window handle from SDL");
				failed = true;
				return false;
			}

			// Ensure the Objective-C classes are registered, then walk
			// NSWindow -> contentView -> backing layer (AppKit + QuartzCore).
			ObjC.framework(APPKIT_FRAMEWORK, "AppKit");
			ObjC.framework(QUARTZCORE_FRAMEWORK, "QuartzCore");
			// Keep the NSWindow so the display's EDR headroom can be queried from its
			// NSScreen (that property lives on NSScreen, not on the layer, and returns
			// CGFloat = double on arm64).
			this.window = nsWindow;
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
			// CAMetalLayer's property is `colorspace` (lowercase s). The EDR knobs also
			// moved across macOS releases, so check before sending: an Objective-C
			// "unrecognized selector" exception cannot be caught from Java and aborts
			// the process. Anything missing degrades to the GL path instead.
			if (!responds(layer, "setColorspace:")) {
				logger.warn("MMetal HDR: CAMetalLayer has no setColorspace: - staying on the GL path");
				failed = true;
				return false;
			}
			objSendVoid(layer, "setColorspace:", colorSpace);
			if (responds(layer, "setWantsExtendedDynamicRangeContent:")) {
				objSendVoid(layer, "setWantsExtendedDynamicRangeContent:", (byte) 1);
			}
			if (responds(layer, "setFramebufferOnly:")) {
				objSendVoid(layer, "setFramebufferOnly:", (byte) 1);
			}
			objSendVoid(contentLayer, "addSublayer:", layer);

			metalLayer = layer;
			this.contentLayer = contentLayer;
			ready = true;
			logger.info("MMetal HDR: EDR CAMetalLayer overlay installed (RGBA16Float, extended linear sRGB)");
			return true;
		} catch (Throwable t) {
			logger.warn("MMetal HDR: EDR presentation unavailable - staying on the GL path", t);
			ready = false;
			failed = true; // do not retry every frame; teardown() re-arms it
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
				// The layer's frame lives in the parent view's POINTS space, while the
				// drawable size is PIXELS: on a 2x Retina panel a pixel-sized frame is
				// twice the view and only a quadrant is visible.
				float scale = 1.0f;
				try {
					scale = (float) objSendCGFloat(window, "backingScaleFactor");
				} catch (Throwable ignored) {
					scale = 1.0f;
				}
				if (scale <= 0.0f) {
					scale = 1.0f;
				}
				MemorySegment size = ARENA.allocate(CG_SIZE);
				size.set(ValueLayout.JAVA_DOUBLE, 0, (double) width);
				size.set(ValueLayout.JAVA_DOUBLE, 8, (double) height);
				invoke(metalLayer, "setDrawableSize:", size, CG_SIZE);
				objSendVoidCGFloat(metalLayer, "setContentsScale:", scale);
				MemorySegment frame = ARENA.allocate(CG_RECT);
				frame.set(ValueLayout.JAVA_DOUBLE, 0, 0.0);
				frame.set(ValueLayout.JAVA_DOUBLE, 8, 0.0);
				frame.set(ValueLayout.JAVA_DOUBLE, 16, width / scale);
				frame.set(ValueLayout.JAVA_DOUBLE, 24, height / scale);
				invoke(metalLayer, "setFrame:", frame, CG_RECT);
				lastWidth = width;
				lastHeight = height;
				lastScale = scale;
			}

			// Keep the overlay the TOPMOST sublayer. AppKit/SDL can append or reorder
			// the GL content's layer at any view update; if the GL layer ends up above
			// ours, the window shows the GL front buffer - which never swaps while the
			// overlay is active - i.e. a permanent black window.
			if (contentLayer != null && contentLayer.address() != 0) {
				objSendVoid(metalLayer, "removeFromSuperlayer");
				objSendVoid(contentLayer, "addSublayer:", metalLayer);
			}

			headroom = 1.0f;
			try {
				MemorySegment screen = (window == null || window.address() == 0)
					? MemorySegment.NULL : objSend(POINTER, window, "screen");
				if (screen != null && screen.address() != 0
						&& responds(screen, "maximumExtendedDynamicRangeColorComponentValue")) {
					headroom = (float) objSendCGFloat(
						screen, "maximumExtendedDynamicRangeColorComponentValue");
				}
			} catch (Throwable ignored) {
				headroom = 1.0f;
			}
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

	/**
	 * Removes the EDR overlay so the GL content underneath presents again (HDR toggled
	 * off mid-session). Idempotent; a later re-enable runs {@link #initialize} fresh.
	 */
	public void teardown() {
		if (!ready || metalLayer == null || metalLayer.address() == 0) {
			return;
		}
		try {
			objSendVoid(metalLayer, "removeFromSuperlayer");
			ready = false;
			failed = false; // a later re-enable may retry initialization
			lastDrawable = null;
			// Force the next acquire to re-apply geometry: the layer is recreated on
			// re-enable and must not inherit the previous layer's cached size state.
			lastWidth = -1;
			lastHeight = -1;
			lastScale = 1.0f;
			logger.info("MMetal HDR: EDR overlay removed");
		} catch (Throwable t) {
			logger.warn("MMetal HDR teardown failed", t);
		}
	}

	// ---- objc helpers -------------------------------------------------------

	private static MemorySegment objSend(MemoryLayout ret, MemorySegment recv, String selector) {
		return (MemorySegment) ObjCSend.invoke(
			ObjC.send(FunctionDescriptor.of(ret, POINTER, POINTER)), recv, ObjC.sel(selector));
	}

	/** Safe feature probe: false when the receiver does not implement the selector. */
	private static boolean responds(MemorySegment recv, String selector) {
		try {
			Object r = ObjCSend.invoke(
				ObjC.send(FunctionDescriptor.of(BOOL, POINTER, POINTER, POINTER)),
				recv, ObjC.sel("respondsToSelector:"), ObjC.sel(selector));
			return r instanceof Byte b && b != 0;
		} catch (Throwable t) {
			return false;
		}
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

	/** Sets a {@code CGFloat} (double on arm64) property - e.g. contentsScale. */
	private static void objSendVoidCGFloat(MemorySegment recv, String selector, double arg) {
		ObjCSend.invoke(ObjC.send(FunctionDescriptor.ofVoid(POINTER, POINTER, ValueLayout.JAVA_DOUBLE)),
			recv, ObjC.sel(selector), arg);
	}

	private static float objSendFloat(MemorySegment recv, String selector) {
		Object r = ObjCSend.invoke(ObjC.send(FunctionDescriptor.of(FLOAT, POINTER, POINTER)),
			recv, ObjC.sel(selector));
		return r instanceof Float f ? f : 1.0f;
	}

	/** Reads a {@code CGFloat} (double on arm64) - NSScreen's EDR headroom uses this. */
	private static double objSendCGFloat(MemorySegment recv, String selector) {
		Object r = ObjCSend.invoke(ObjC.send(FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, POINTER, POINTER)),
			recv, ObjC.sel(selector));
		return r instanceof Double d ? d : 1.0;
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