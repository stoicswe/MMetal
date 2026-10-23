package dev.mmetal.metal.shaderpack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read-only manifest of an Iris/OptiFine shader pack.
 *
 * <p>Rung 1 of the Metal translation ladder: enumerate the pack's programs, its
 * {@code shaders.properties}, and the GLSL feature set. That is enough to decide
 * whether Apple's GL 4.1-on-Metal backend can run the pack as-is, and it is the
 * input model for translating the packs that need Metal (GLSL -> SPIR-V -> MSL).</p>
 *
 * <p>Pure Java (zip/dir + text scanning), so it is safe to call from a command and
 * from tooling without touching the GPU or Minecraft classes.</p>
 */
public final class IrisPack {
	private static final Pattern VERSION = Pattern.compile(
		"^\\s*#version\\s+(\\d+)(?:\\s+(\\w+))?", Pattern.MULTILINE);
	private static final Pattern EXTENSION = Pattern.compile(
		"^\\s*#extension\\s+(\\w+)", Pattern.MULTILINE);
	private static final Set<String> PROGRAM_EXTENSIONS =
		Set.of("vsh", "fsh", "gsh", "csh", "tcs", "tes");

	/** A matched vertex/fragment program pair (either side may be absent). */
	public record Program(String name, String vertex, String fragment) {
		public boolean complete() {
			return vertex != null && fragment != null;
		}
	}

	private final Path source;
	private final String rootPrefix;
	private final Map<String, String> properties;
	private final Map<String, String> sources;
	private final List<Program> programs;
	private final Set<String> extensions;
	private final int maxVersion;
	private final boolean ssbo;
	private final boolean imageLoadStore;
	private final boolean compute;
	private final int computePrograms;
	private final boolean textureGather;

	private IrisPack(Path source, String rootPrefix, Map<String, String> properties,
	                 Map<String, String> sources, List<Program> programs,
	                 Set<String> extensions, int maxVersion, boolean ssbo,
	                 boolean imageLoadStore, boolean compute, int computePrograms,
	                 boolean textureGather) {
		this.source = source;
		this.rootPrefix = rootPrefix;
		this.properties = properties;
		this.sources = sources;
		this.programs = programs;
		this.extensions = extensions;
		this.maxVersion = maxVersion;
		this.ssbo = ssbo;
		this.imageLoadStore = imageLoadStore;
		this.compute = compute;
		this.computePrograms = computePrograms;
		this.textureGather = textureGather;
	}

	// ------------------------------------------------------------------

	/** Loads a pack from a .zip file or a directory. */
	public static IrisPack load(Path path) throws IOException {
		Map<String, String> files = Files.isDirectory(path)
			? readDirectory(path)
			: readZip(path);
		String prefix = findShadersRoot(files);
		String shaders = prefix + "shaders/";

		Map<String, String> properties = new LinkedHashMap<>();
		String propsText = files.get(shaders + "shaders.properties");
		if (propsText == null) {
			propsText = files.get(prefix + "shaders.properties"); // flattened archive root
		}
		if (propsText != null) {
			parseProperties(propsText, properties);
		}

		Map<String, String> sources = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : files.entrySet()) {
			String relative = entry.getKey();
			if (!relative.startsWith(shaders)) {
				continue;
			}
			String under = relative.substring(shaders.length());
			int dot = under.lastIndexOf('.');
			if (dot < 0) {
				continue;
			}
			String extension = under.substring(dot + 1).toLowerCase(Locale.ROOT);
			if (PROGRAM_EXTENSIONS.contains(extension)) {
				sources.put(under, entry.getValue());
			}
		}

		Map<String, String[]> pairs = new TreeMap<>();
		for (Map.Entry<String, String> entry : sources.entrySet()) {
			String under = entry.getKey();
			int dot = under.lastIndexOf('.');
			String name = under.substring(0, dot);
			String extension = under.substring(dot + 1).toLowerCase(Locale.ROOT);
			String[] pair = pairs.computeIfAbsent(name, ignored -> new String[2]);
			if (extension.equals("vsh")) {
				pair[0] = entry.getValue();
			} else if (extension.equals("fsh")) {
				pair[1] = entry.getValue();
			}
		}
		List<Program> programs = new ArrayList<>();
		for (Map.Entry<String, String[]> entry : pairs.entrySet()) {
			programs.add(new Program(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
		}

		Set<String> extensions = new java.util.TreeSet<>();
		int maxVersion = 0;
		boolean ssbo = false;
		boolean images = false;
		boolean compute = false;
		int computePrograms = 0;
		boolean gather = false;
		for (Map.Entry<String, String> entry : sources.entrySet()) {
			String text = entry.getValue();
			// .csh files are Iris compute passes. Apple GL 4.1 has no compute support,
			// so Iris skips those passes on macOS - they are not a hard blocker, but
			// they are exactly what the Metal translation ladder can unlock.
			boolean computeSource = entry.getKey().toLowerCase(Locale.ROOT).endsWith(".csh");
			if (computeSource) {
				computePrograms++;
			}
			Matcher version = VERSION.matcher(text);
			if (version.find() && !computeSource) {
				try {
					maxVersion = Math.max(maxVersion, Integer.parseInt(version.group(1)));
				} catch (NumberFormatException ignored) {
					// malformed #version - treat as unknown
				}
			}
			Matcher extension = EXTENSION.matcher(text);
			while (extension.find()) {
				extensions.add(extension.group(1));
			}
			if (text.contains("std430")) {
				ssbo = true;
			}
			if (text.contains("imageLoad") || text.contains("imageStore")) {
				images = true;
			}
			if (text.contains("local_size")) {
				compute = true;
			}
			if (text.contains("textureGather")) {
				gather = true;
			}
		}

		return new IrisPack(path, prefix, properties, sources, programs, extensions,
			maxVersion, ssbo, images, compute || computePrograms > 0, computePrograms, gather);
	}

	private static Map<String, String> readZip(Path path) throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		try (ZipFile zip = new ZipFile(path.toFile())) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory()) {
					continue;
				}
				try (InputStream in = zip.getInputStream(entry)) {
					files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
				}
			}
		}
		return files;
	}

	private static Map<String, String> readDirectory(Path path) throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		try (var stream = Files.walk(path)) {
			stream.filter(Files::isRegularFile).forEach(file -> {
				try {
					files.put(path.relativize(file).toString().replace('\\', '/'),
						Files.readString(file, StandardCharsets.UTF_8));
				} catch (IOException ignored) {
					// unreadable entry - skip
				}
			});
		}
		return files;
	}

	/**
	 * Returns the pack prefix before the {@code shaders/} directory (usually "").
	 * Packs store {@code <prefix>/shaders/shaders.properties}; some tooling flattens
	 * that to {@code shaders.properties} at the archive root.
	 */
	private static String findShadersRoot(Map<String, String> files) {
		for (String name : files.keySet()) {
			if (name.equals("shaders.properties") || name.endsWith("/shaders.properties")) {
				String withoutFile = name.substring(0, name.length() - "shaders.properties".length());
				if (withoutFile.endsWith("/")) {
					withoutFile = withoutFile.substring(0, withoutFile.length() - 1);
				}
				if (withoutFile.endsWith("shaders")) {
					withoutFile = withoutFile.substring(0, withoutFile.length() - "shaders".length());
				}
				if (withoutFile.endsWith("/")) {
					withoutFile = withoutFile.substring(0, withoutFile.length() - 1);
				}
				return withoutFile.isEmpty() ? "" : withoutFile + "/";
			}
		}
		throw new IllegalArgumentException("not a shader pack: shaders.properties not found");
	}

	private static void parseProperties(String text, Map<String, String> out) {
		for (String line : text.split("\\R")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			int equals = trimmed.indexOf('=');
			if (equals > 0) {
				out.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1).trim());
			}
		}
	}

	// ------------------------------------------------------------------

	public Path source() {
		return source;
	}

	/** Pack name for display: file name without the .zip suffix. */
	public String name() {
		String file = source.getFileName().toString();
		return file.toLowerCase(Locale.ROOT).endsWith(".zip")
			? file.substring(0, file.length() - 4) : file;
	}

	public List<Program> programs() {
		return List.copyOf(programs);
	}

	public Map<String, String> properties() {
		return Map.copyOf(properties);
	}

	public int maxGlslVersion() {
		return maxVersion;
	}

	public boolean usesSsbo() {
		return ssbo;
	}

	public boolean usesImageLoadStore() {
		return imageLoadStore;
	}

	public boolean usesCompute() {
		return compute;
	}

	public boolean usesTextureGather() {
		return textureGather;
	}

	/** True when Apple's GL 4.1-on-Metal backend can compile this pack as-is. */
	public boolean gl41Compatible() {
		return !ssbo && !imageLoadStore && maxVersion <= 410;
	}

	/** Compute passes (Iris .csh programs) - skipped on GL 4.1, unlocked by Metal. */
	public int computePrograms() {
		return computePrograms;
	}

	/** Packs that need the Metal translation ladder: anything GL 4.1 cannot run. */
	public boolean needsMetalTranslation() {
		return !gl41Compatible();
	}

	public int gbufferPrograms() {
		return countPrograms("gbuffers_");
	}

	public int shadowPrograms() {
		return countPrograms("shadow");
	}

	public int deferredPrograms() {
		return countPrograms("deferred");
	}

	public int compositePrograms() {
		return countPrograms("composite");
	}

	private int countPrograms(String prefix) {
		int count = 0;
		for (Program program : programs) {
			String simple = program.name().substring(program.name().lastIndexOf('/') + 1);
			if (simple.startsWith(prefix)) {
				count++;
			}
		}
		return count;
	}

	/** Human-readable manifest for the /mmetal command and logs. */
	public List<String> summary() {
		List<String> lines = new ArrayList<>();
		lines.add("Pack: " + name() + (rootPrefix.isEmpty() ? "" : " (under " + rootPrefix + ")"));
		lines.add("Programs: " + programs.size()
			+ " (gbuffers " + gbufferPrograms()
			+ ", shadow " + shadowPrograms()
			+ ", deferred " + deferredPrograms()
			+ ", composite " + compositePrograms() + ")");
		lines.add("Sources: " + sources.size()
			+ ", shaders.properties keys: " + properties.size());
		lines.add("GLSL max version: " + (maxVersion == 0 ? "unknown" : Integer.toString(maxVersion))
			+ (extensions.isEmpty() ? "" : ", extensions: " + String.join(", ", extensions)));
		lines.add("Features: SSBO=" + yesNo(ssbo) + " images=" + yesNo(imageLoadStore)
			+ " compute=" + yesNo(compute) + " textureGather=" + yesNo(textureGather)
			+ (computePrograms > 0 ? " (" + computePrograms + " compute programme)" : ""));
		if (ssbo || imageLoadStore) {
			lines.add("Apple GL 4.1: cannot run as-is -> needs the Metal translation ladder");
		} else if (computePrograms > 0) {
			lines.add("Apple GL 4.1: runs, but " + computePrograms
				+ " compute pass(es) are skipped -> Metal unlocks them");
		} else {
			lines.add("Apple GL 4.1: runs as-is (Metal upscale tier can enhance it)");
		}
		return lines;
	}

	private static String yesNo(boolean value) {
		return value ? "yes" : "no";
	}
}
