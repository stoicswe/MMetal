package dev.mmetal.metal.nativebridge;

/** Runtime system capabilities: OS, version, architecture. */
public final class SystemInfo {
	private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");
	private static final int MAJOR;
	private static final int MINOR;
	private static final boolean ARM64;

	static {
		int major = 0;
		int minor = 0;
		String version = System.getProperty("os.version", "0.0");
		try {
			String[] parts = version.split("\\.");
			major = Integer.parseInt(parts[0]);
			if (parts.length > 1) {
				minor = Integer.parseInt(parts[1]);
			}
		} catch (NumberFormatException ignored) {
		}
		MAJOR = major;
		MINOR = minor;
		ARM64 = "aarch64".equals(System.getProperty("os.arch"));
	}

	private SystemInfo() {
	}

	public static boolean isMac() {
		return MAC;
	}

	public static boolean isArm64() {
		return ARM64;
	}

	public static int macOSMajor() {
		return MAJOR;
	}

	public static int macOSMinor() {
		return MINOR;
	}

	/** Spatial/Temporal MetalFX upscalers require macOS 13 (Ventura) or newer. */
	public static boolean supportsMetalFxUpscaling() {
		return MAC && (MAJOR > 13 || (MAJOR == 13 && MINOR >= 0)) && MAJOR >= 13;
	}

	/** MetalFX frame interpolation requires macOS 26 (Tahoe) or newer. */
	public static boolean supportsFrameInterpolation() {
		return MAC && MAJOR >= 26;
	}

	/** Metal 4 FX (Metal-4-command-buffer compatible effects) require macOS 26+. */
	public static boolean supportsMetal4Fx() {
		return MAC && MAJOR >= 26;
	}
}
