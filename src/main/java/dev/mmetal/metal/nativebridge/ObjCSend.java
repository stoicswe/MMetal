package dev.mmetal.metal.nativebridge;

import java.lang.invoke.MethodHandle;

/** Tiny helper for invoking cached {@code objc_msgSend} handles. */
public final class ObjCSend {
	private ObjCSend() {
	}

	public static Object invoke(MethodHandle handle, Object... args) {
		try {
			return handle.invokeWithArguments(args);
		} catch (Throwable t) {
			throw new IllegalStateException("ObjC call failed", t);
		}
	}

	public static Object invokeQuiet(MethodHandle handle, Object... args) {
		try {
			return handle.invokeWithArguments(args);
		} catch (Throwable t) {
			return null;
		}
	}

	/** Reads a pointer result as a {@link MemorySegment}-compatible object, or NULL. */
	public static java.lang.foreign.MemorySegment asPointer(Object result) {
		if (result instanceof java.lang.foreign.MemorySegment seg) {
			return seg;
		}
		return java.lang.foreign.MemorySegment.NULL;
	}
}