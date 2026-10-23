package dev.mmetal.metal.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * GLSL -> SPIR-V -> MSL translation prototype (Metal translation ladder, rung 2).
 *
 * <p>Uses the MoltenVK toolchain that ships with the Vulkan ecosystem:
 * {@code glslangValidator} turns GLSL into Vulkan-flavoured SPIR-V (auto-mapping
 * attribute locations and resource bindings, because shader packs declare neither),
 * then {@code spirv-cross} emits Metal Shading Language. The resulting MSL is
 * compiled at runtime through {@code MetalNative.compileLibrary}.</p>
 *
 * <p>This is deliberately a thin wrapper: Iris-specific preprocessing (its uniform
 * set, {@code shaders.properties} defines, and the {@code lib/} include tree) is the
 * next layer, not this class's job.</p>
 */
public final class GlslTranslator {
	public enum Stage {
		VERTEX("vert", "vsh"),
		FRAGMENT("frag", "fsh");

		private final String glslangName;
		private final String extension;

		Stage(String glslangName, String extension) {
			this.glslangName = glslangName;
			this.extension = extension;
		}

		public static Stage fromExtension(String extension) {
			return switch (extension.toLowerCase(java.util.Locale.ROOT)) {
				case "vsh" -> VERTEX;
				case "fsh" -> FRAGMENT;
				default -> throw new IllegalArgumentException("unsupported stage: " + extension);
			};
		}
	}

	private GlslTranslator() {
	}

	/** Translates GLSL source to MSL. {@code defines} entries are {@code NAME} or {@code NAME=value}. */
	public static String toMsl(String glsl, Stage stage, List<Path> includeDirs,
	                           List<String> defines) throws IOException, InterruptedException {
		return toMsl(glsl, stage, includeDirs, defines, true);
	}

	/**
	 * Translates GLSL to MSL. {@code normalize} applies the Iris/legacy preprocessor
	 * (see {@link #normalize}): shader packs use GLSL 120/130 and {@code /lib} includes
	 * that neither glslang nor Vulkan SPIR-V accept directly.
	 */
	public static String toMsl(String glsl, Stage stage, List<Path> includeDirs,
	                           List<String> defines, boolean normalize) throws IOException, InterruptedException {
		String glslang = tool("MMETAL_GLSLANG", "glslangValidator");
		String spirvCross = tool("MMETAL_SPIRV_CROSS", "spirv-cross");
		String source = normalize ? normalize(glsl, stage) : glsl;

		Path work = Files.createTempDirectory("mmetal-glsl-");
		try {
			Path file = work.resolve("shader." + stage.extension);
			Path spv = work.resolve("shader.spv");
			Path msl = work.resolve("shader.msl");
			Files.writeString(file, source, StandardCharsets.UTF_8);

			List<String> glslangArgs = new ArrayList<>(List.of(
				glslang, "-V", "--target-env", "vulkan1.1",
				"--aml", "--amb", "-S", stage.glslangName,
				"-o", spv.toString()));
			for (Path include : includeDirs) {
				glslangArgs.add("-I" + include.toAbsolutePath());
			}
			for (String define : defines) {
				glslangArgs.add("-D" + define);
			}
			glslangArgs.add(file.toString());
			run(glslangArgs, "glslangValidator");

			run(List.of(spirvCross, "--msl", "--msl-version", "20100",
				"--output", msl.toString(), spv.toString()), "spirv-cross");
			return Files.readString(msl, StandardCharsets.UTF_8);
		} finally {
			deleteTree(work);
		}
	}

	private static final Pattern VERSION_LINE = Pattern.compile(
		"(?m)^[ \\t]*#version[ \\t]+\\d+[^\\n]*?$");
	private static final Pattern INCLUDE = Pattern.compile(
		"(?m)^([ \\t]*#include[ \\t]+\")/([^\"]*\")");

	/**
	 * Prepares a shader-pack source for the Vulkan/Metal toolchain:
	 * <ul>
	 *   <li>{@code #version 120/130} -> {@code #version 450 compatibility} (packs rely on
	 *       legacy builtins such as {@code texture2D} and {@code gl_FragColor}, which the
	 *       compatibility profile keeps),</li>
	 *   <li>legacy {@code attribute}/{@code varying} -> {@code in}/{@code out},</li>
	 *   <li>{@code #include "/lib/..."} -> pack-root-relative, plus the include-directive
	 *       extension glslang requires.</li>
	 * </ul>
	 */
	public static String normalize(String glsl, Stage stage) {
		String text = VERSION_LINE.matcher(glsl).replaceFirst("#version 450 compatibility");
		text = INCLUDE.matcher(text).replaceAll("$1$2");
		if (!text.contains("GL_GOOGLE_include_directive")) {
			text = text.replace("#version 450 compatibility",
				"#version 450 compatibility\n#extension GL_GOOGLE_include_directive : require");
		}
		text = text.replaceAll("\\battribute\\b", "in");
		text = text.replaceAll("\\bvarying\\b", stage == Stage.VERTEX ? "out" : "in");
		return text;
	}

	private static void run(List<String> command, String tool) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command)
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		int exit = process.waitFor();
		if (exit != 0) {
			throw new IllegalStateException(tool + " failed (exit " + exit + "):\n" + output);
		}
	}

	/** Resolves a tool from an env override first, then the usual Homebrew paths. */
	private static String tool(String env, String name) {
		String override = System.getenv(env);
		if (override != null && !override.isBlank()) {
			return override;
		}
		String path = System.getenv("PATH");
		String[] dirs = path == null ? new String[0] : path.split(":");
		List<String> candidates = new ArrayList<>(List.of(dirs));
		candidates.add("/opt/homebrew/bin");
		candidates.add("/usr/local/bin");
		for (String dir : candidates) {
			Path candidate = Path.of(dir, name);
			if (Files.isExecutable(candidate)) {
				return candidate.toString();
			}
		}
		throw new IllegalStateException(name + " not found - install glslang and spirv-cross "
			+ "(brew install glslang spirv-cross) or set " + env);
	}

	private static void deleteTree(Path root) {
		try (var stream = Files.walk(root)) {
			stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// best effort temp cleanup
				}
			});
		} catch (IOException ignored) {
			// best effort temp cleanup
		}
	}
}
