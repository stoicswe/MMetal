package dev.mmetal.metal.nativebridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal Objective-C runtime bridge built on the finalized Foreign Function &amp; Memory API
 * (JEP 454, available in the Java 25 runtime that Minecraft 26.3 requires).
 *
 * <p>Apple system frameworks are loaded with {@code dlopen} semantics via
 * {@link SymbolLookup#libraryLookup}, and Objective-C objects are driven through
 * {@code objc_msgSend}. Because {@code objc_msgSend} is declared variadic in C while every
 * concrete call has a static signature, all downcalls use
 * {@link Linker.Option#isTrivial()}, which is the correct calling convention on arm64.</p>
 */
public final class ObjC {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final Arena ARENA = Arena.global();

	private static final ConcurrentHashMap<String, SymbolLookup> LIBRARIES = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, MemorySegment> CLASSES = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, MemorySegment> SELECTORS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<FunctionDescriptor, MethodHandle> SENDERS = new ConcurrentHashMap<>();

	private static final MemorySegment OBJC_GET_CLASS;
	private static final MemorySegment SEL_REGISTER;
	private static final MemorySegment MSG_SEND;

	static {
		SymbolLookup objc = loadLibrary("/usr/lib/libobjc.dylib", null);
		OBJC_GET_CLASS = objc.find("objc_getClass")
			.orElseThrow(() -> new IllegalStateException("objc_getClass not found"));
		SEL_REGISTER = objc.find("sel_registerName")
			.orElseThrow(() -> new IllegalStateException("sel_registerName not found"));
		MSG_SEND = objc.find("objc_msgSend")
			.orElseThrow(() -> new IllegalStateException("objc_msgSend not found"));
	}

	private ObjC() {
	}

	/** Loads a framework (absolute path first, leaf name fallback) and caches its symbol lookup. */
	public static SymbolLookup framework(String absolutePath, String leafName) {
		return LIBRARIES.computeIfAbsent(absolutePath, path -> loadLibrary(path, leafName));
	}

	private static SymbolLookup loadLibrary(String path, String leafName) {
		IllegalArgumentException last = null;
		for (String candidate : leafName == null ? new String[] { path } : new String[] { path, leafName }) {
			try {
				return SymbolLookup.libraryLookup(candidate, ARENA);
			} catch (IllegalArgumentException e) {
				last = e;
			}
		}
		throw new IllegalStateException("Unable to load native library: " + path, last);
	}

	/** Looks a symbol up in a framework, throwing if absent. */
	public static MemorySegment symbol(String libPath, String libLeaf, String name) {
		return framework(libPath, libLeaf).find(name)
			.orElseThrow(() -> new IllegalStateException("Symbol not found: " + name + " (" + libPath + ")"));
	}

	/** Interns an Objective-C class by name. */
	public static MemorySegment cls(String name) {
		return CLASSES.computeIfAbsent(name, n -> {
			try {
				MethodHandle getClass = LINKER.downcallHandle(OBJC_GET_CLASS,
					FunctionDescriptor.of(Layouts.POINTER, Layouts.POINTER));
				MemorySegment cls = (MemorySegment) getClass.invoke(ARENA.allocateFrom(n));
				if (cls == null || cls.address() == 0) {
					throw new IllegalStateException("Objective-C class not found: " + n);
				}
				return cls;
			} catch (Throwable t) {
				throw new IllegalStateException("objc_getClass failed for " + n, t);
			}
		});
	}

	/** Interns a selector. */
	public static MemorySegment sel(String name) {
		return SELECTORS.computeIfAbsent(name, n -> {
			try {
				MethodHandle register = LINKER.downcallHandle(SEL_REGISTER,
					FunctionDescriptor.of(Layouts.POINTER, Layouts.POINTER));
				return (MemorySegment) register.invoke(ARENA.allocateFrom(n));
			} catch (Throwable t) {
				throw new IllegalStateException("sel_registerName failed for " + n, t);
			}
		});
	}

	/** Returns a cached {@code objc_msgSend} downcall handle for the given descriptor.
	 *
	 *  <p>{@code objc_msgSend} is declared variadic in C, but every concrete call site has a
	 *  static signature. We therefore declare fixed descriptors; on arm64 the ABI matches
	 *  what clang emits for ObjC call sites. {@code critical(false)} is the plain, pinned
	 *  downcall mode ({@code isTrivial} was removed in Java 26).</p> */
	public static MethodHandle send(FunctionDescriptor descriptor) {
		return SENDERS.computeIfAbsent(descriptor, d ->
			LINKER.downcallHandle(MSG_SEND, d, Linker.Option.critical(false)));
	}

	/** The global arena shared by all native bridges. */
	public static Arena sharedArena() {
		return ARENA;
	}

	/** Allocates a C string in the global arena. */
	public static MemorySegment cstr(String s) {
		return ARENA.allocateFrom(s);
	}
}
