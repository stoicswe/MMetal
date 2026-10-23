package dev.mmetal.metal.nativebridge;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static dev.mmetal.metal.nativebridge.Layouts.INT;
import static dev.mmetal.metal.nativebridge.Layouts.NSUINTEGER;
import static dev.mmetal.metal.nativebridge.Layouts.POINTER;

/**
 * IOSurface creation and the OpenGL side of the GL &harr; Metal zero-copy bridge.
 *
 * <p>The classic macOS sharing recipe is used: an IOSurface is created through
 * {@code IOSurfaceCreate}, attached to a GL rectangle texture with
 * {@code CGLTexImageIOSurface2D}, and opened by Metal with
 * {@code -[MTLDevice newTextureWithDescriptor:iosurface:plane:]}. With the canonical
 * format tuple ({@code GL_RGBA8}/{@code GL_BGRA}/{@code GL_UNSIGNED_INT_8_8_8_8_REV})
 * both APIs see identical pixels.</p>
 */
public final class IosurfaceBridge {
	public static final String IOSURFACE_FRAMEWORK = "/System/Library/Frameworks/IOSurface.framework/IOSurface";
	public static final String CORE_FOUNDATION_FRAMEWORK =
		"/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";
	public static final String OPENGL_FRAMEWORK = "/System/Library/Frameworks/OpenGL.framework/OpenGL";

	/** GL enums passed through to CGLTexImageIOSurface2D. */
	public static final int GL_TEXTURE_RECTANGLE = 0x84F5;
	public static final int GL_RGBA8 = 0x8058;
	public static final int GL_RGBA32F = 0x8814;
	public static final int GL_BGRA = 0x80E1;
	public static final int GL_RGBA = 0x1906;
	public static final int GL_UNSIGNED_INT_8_8_8_8_REV = 0x8367;
	public static final int GL_FLOAT = 0x1406;

	private static final int CF_NUMBER_INT_TYPE = 9;

	// CoreFoundation / IOSurface / CGL entry points are plain C functions - direct downcalls,
	// no objc_msgSend involved.
	private static final java.lang.invoke.MethodHandle MH_CF_DICT_CREATE_MUTABLE = cFunction(
		FunctionDescriptor.of(POINTER, POINTER, NSUINTEGER, POINTER, POINTER),
		CORE_FOUNDATION_FRAMEWORK, "CoreFoundation", "CFDictionaryCreateMutable");
	private static final java.lang.invoke.MethodHandle MH_CF_DICT_ADD_VALUE = cFunction(
		FunctionDescriptor.ofVoid(POINTER, POINTER, POINTER),
		CORE_FOUNDATION_FRAMEWORK, "CoreFoundation", "CFDictionaryAddValue");
	private static final java.lang.invoke.MethodHandle MH_CF_NUMBER_CREATE = cFunction(
		FunctionDescriptor.of(POINTER, POINTER, INT, POINTER),
		CORE_FOUNDATION_FRAMEWORK, "CoreFoundation", "CFNumberCreate");
	private static final java.lang.invoke.MethodHandle MH_IOSURFACE_CREATE = cFunction(
		FunctionDescriptor.of(POINTER, POINTER),
		IOSURFACE_FRAMEWORK, "IOSurface", "IOSurfaceCreate");
	private static final java.lang.invoke.MethodHandle MH_IOSURFACE_GET_WIDTH = cFunction(
		FunctionDescriptor.of(NSUINTEGER, POINTER),
		IOSURFACE_FRAMEWORK, "IOSurface", "IOSurfaceGetWidth");
	private static final java.lang.invoke.MethodHandle MH_IOSURFACE_GET_HEIGHT = cFunction(
		FunctionDescriptor.of(NSUINTEGER, POINTER),
		IOSURFACE_FRAMEWORK, "IOSurface", "IOSurfaceGetHeight");
	private static final java.lang.invoke.MethodHandle MH_CGL_GET_CURRENT_CONTEXT = cFunction(
		FunctionDescriptor.of(POINTER),
		OPENGL_FRAMEWORK, "OpenGL", "CGLGetCurrentContext");
	private static final java.lang.invoke.MethodHandle MH_CGL_TEX_IMAGE_IOSURFACE = cFunction(
		FunctionDescriptor.of(
			INT, // CGLError
			POINTER, INT, INT, INT, INT, INT, INT, POINTER, INT),
		OPENGL_FRAMEWORK, "OpenGL", "CGLTexImageIOSurface2D");

	private static java.lang.invoke.MethodHandle cFunction(FunctionDescriptor descriptor,
	                                                       String framework, String leaf, String symbol) {
		return Layouts.fn(java.lang.foreign.Linker.nativeLinker(),
			ObjC.symbol(framework, leaf, symbol), descriptor);
	}

	private static final java.lang.invoke.MethodHandle MH_CF_RELEASE = cFunction(
		FunctionDescriptor.ofVoid(POINTER),
		CORE_FOUNDATION_FRAMEWORK, "CoreFoundation", "CFRelease");

	/** Releases a CoreFoundation object we own (Create-rule). */
	public static void cfRelease(MemorySegment object) {
		if (object == null || object.address() == 0) {
			return;
		}
		invoke(MH_CF_RELEASE, object);
	}

	private static final MemorySegment KEY_WIDTH;
	private static final MemorySegment KEY_HEIGHT;
	private static final MemorySegment KEY_BYTES_PER_ELEMENT;
	private static final MemorySegment DICT_KEY_CALLBACKS;
	private static final MemorySegment DICT_VALUE_CALLBACKS;

	private static final MemorySegment NUMBER_SCRATCH = ObjC.sharedArena().allocate(INT);

	static {
		// kIOSurfaceWidth etc. are extern CFStringRef VARIABLES - dlsym returns the address
		// of the variable, so dereference to obtain the actual CFStringRef object.
		KEY_WIDTH = deref(ObjC.symbol(IOSURFACE_FRAMEWORK, "IOSurface", "kIOSurfaceWidth"));
		KEY_HEIGHT = deref(ObjC.symbol(IOSURFACE_FRAMEWORK, "IOSurface", "kIOSurfaceHeight"));
		KEY_BYTES_PER_ELEMENT = deref(ObjC.symbol(IOSURFACE_FRAMEWORK, "IOSurface", "kIOSurfaceBytesPerElement"));
		// The dictionary callbacks are const structs; their address is what CF expects.
		DICT_KEY_CALLBACKS = ObjC.symbol(CORE_FOUNDATION_FRAMEWORK, "CoreFoundation",
			"kCFTypeDictionaryKeyCallBacks");
		DICT_VALUE_CALLBACKS = ObjC.symbol(CORE_FOUNDATION_FRAMEWORK, "CoreFoundation",
			"kCFTypeDictionaryValueCallBacks");
	}

	private static MemorySegment deref(MemorySegment global) {
		// dlsym segments have byteSize 0; these globals are real pointer slots, so
		// reinterpret to 8 bytes before reading.
		return global.reinterpret(8).get(POINTER, 0);
	}

	private IosurfaceBridge() {
	}

	/** Creates an IOSurface of the given size with 4 bytes per element. */
	public static MemorySegment create(int width, int height) {
		MemorySegment dict = createMutableDictionary();
		addIntEntry(dict, KEY_WIDTH, width);
		addIntEntry(dict, KEY_HEIGHT, height);
		addIntEntry(dict, KEY_BYTES_PER_ELEMENT, 4);
		return (MemorySegment) invoke(MH_IOSURFACE_CREATE, dict);
	}

	public static long getWidth(MemorySurface surface) {
		return getWidth(surface.handle());
	}

	public static long getHeight(MemorySurface surface) {
		return getHeight(surface.handle());
	}

	public static long getWidth(MemorySegment surface) {
		return (long) invoke(MH_IOSURFACE_GET_WIDTH, surface);
	}

	public static long getHeight(MemorySegment surface) {
		return (long) invoke(MH_IOSURFACE_GET_HEIGHT, surface);
	}

	/** Returns the current CGL context handle (null when no GL context is current). */
	public static MemorySegment currentContextHandle() {
		return (MemorySegment) invoke(MH_CGL_GET_CURRENT_CONTEXT);
	}

	/**
	 * Attaches an IOSurface to the currently bound GL texture. Must be called with the
	 * target texture bound on the active texture unit. Returns the CGL error code (0 = ok).
	 */
	public static int bindSurfaceToCurrentGlTexture(MemorySegment surface, int glTarget, int glInternalFormat,
	                                                int width, int height, int glFormat, int glType) {
		MemorySegment context = currentContextHandle();
		if (context == null || context.address() == 0) {
			throw new IllegalStateException("No current CGL context - IOSurface sharing requires the GL thread");
		}
		try {
			return (int) MH_CGL_TEX_IMAGE_IOSURFACE.invoke(context, glTarget, glInternalFormat,
				width, height, glFormat, glType, surface, 0);
		} catch (Throwable t) {
			throw new IllegalStateException("CGLTexImageIOSurface2D failed", t);
		}
	}

	private static MemorySegment createMutableDictionary() {
		return (MemorySegment) invoke(MH_CF_DICT_CREATE_MUTABLE,
			MemorySegment.NULL, 0L, DICT_KEY_CALLBACKS, DICT_VALUE_CALLBACKS);
	}

	private static void addIntEntry(MemorySegment dict, MemorySegment key, int value) {
		NUMBER_SCRATCH.set(INT, 0, value);
		MemorySegment number = (MemorySegment) invoke(MH_CF_NUMBER_CREATE,
			MemorySegment.NULL, CF_NUMBER_INT_TYPE, NUMBER_SCRATCH);
		invoke(MH_CF_DICT_ADD_VALUE, dict, key, number);
	}

	private static Object invoke(MethodHandle handle, Object... args) {
		try {
			return handle.invokeWithArguments(args);
		} catch (Throwable t) {
			throw new IllegalStateException("IOSurface call failed", t);
		}
	}

	/** Small value holder pairing an IOSurface with its dimensions. */
	public record MemorySurface(MemorySegment handle, int width, int height) {
	}
}
