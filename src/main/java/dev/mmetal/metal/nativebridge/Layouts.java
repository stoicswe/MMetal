package dev.mmetal.metal.nativebridge;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;

/**
 * Native layout constants shared by the Metal / MetalFX / IOSurface bridges.
 * All sizes assume the arm64 (Apple Silicon) LP64 ABI that Minecraft 26.3 ships with.
 */
public final class Layouts {
	/** id / pointers / CFTypeRef / IOSurfaceRef. */
	public static final AddressLayout POINTER = ValueLayout.ADDRESS;
	/** C {@code size_t} / {@code NSUInteger} / {@code MTLPixelFormat} / {@code NSInteger}. */
	public static final ValueLayout.OfLong NSUINTEGER = ValueLayout.JAVA_LONG;
	/** C {@code int} / {@code GLenum} / {@code GLsizei} / {@code GLuint}. */
	public static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
	/** C {@code unsigned int} used by CGL error codes. */
	public static final ValueLayout.OfInt CGLError = ValueLayout.JAVA_INT;
	/** C {@code float}. */
	public static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT;
	/** Objective-C {@code BOOL} (a single byte on arm64). */
	public static final ValueLayout.OfByte BOOL = ValueLayout.JAVA_BYTE;

	/** {@code MTLSize { NSUInteger width, height, depth }} - passed by value. */
	public static final StructLayout MTL_SIZE = MemoryLayout.structLayout(
		NSUINTEGER.withName("width"),
		NSUINTEGER.withName("height"),
		NSUINTEGER.withName("depth"));

	/** {@code simd_float4x4} (64 bytes, column major). */
	public static final StructLayout FLOAT4X4 = MemoryLayout.structLayout(
		MemoryLayout.sequenceLayout(4, MemoryLayout.structLayout(
			FLOAT.withName("x"), FLOAT.withName("y"), FLOAT.withName("z"), FLOAT.withName("w"))));

	private Layouts() {
	}

	/** Links a native function (non-ObjC) with the exact descriptor. */
	public static java.lang.invoke.MethodHandle fn(Linker linker, MemorySegment symbol, FunctionDescriptor d) {
		return linker.downcallHandle(symbol, d);
	}
}
