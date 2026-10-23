package dev.mmetal.style;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mmetal.api.ManifestStyle;
import dev.mmetal.api.MMetalStyle;
import dev.mmetal.api.MMetalStyleProvider;
import dev.mmetal.api.MMetalStyles;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Finds style plugins:
 * <ul>
 *   <li><b>Shader-only jars</b>: any loaded mod containing {@code mmetal/styles.json}
 *       plus its metallib(s). No Java code required.</li>
 *   <li><b>Code plugins</b>: a {@code mmetal:styles} entrypoint implementing
 *       {@link MMetalStyleProvider}.</li>
 * </ul>
 * Every failure is isolated - a broken plugin is logged and skipped.
 */
public final class StyleDiscovery {
	private static final Logger LOGGER = LoggerFactory.getLogger("MMetal");
	private static boolean discovered;

	private StyleDiscovery() {
	}

	public static void discover() {
		if (discovered) {
			return;
		}
		discovered = true;
		discoverEntrypoints();
		discoverManifests();
	}

	private static void discoverEntrypoints() {
		try {
			for (MMetalStyleProvider provider : FabricLoader.getInstance()
				.getEntrypoints("mmetal:styles", MMetalStyleProvider.class)) {
				try {
					for (MMetalStyle style : provider.styles()) {
						MMetalStyles.register(style);
						LOGGER.info("MMetal style registered via entrypoint: {}", style.id());
					}
				} catch (Throwable t) {
					LOGGER.warn("MMetal style provider failed - skipping it", t);
				}
			}
		} catch (Throwable t) {
			LOGGER.warn("MMetal style entrypoint discovery failed", t);
		}
	}

	private static void discoverManifests() {
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			try {
				Optional<Path> manifestPath = mod.findPath("mmetal/styles.json");
				if (manifestPath.isEmpty()) {
					continue;
				}
				JsonObject root = JsonParser.parseString(Files.readString(manifestPath.get())).getAsJsonObject();
				if (!root.has("styles")) {
					continue;
				}
				for (JsonElement element : root.getAsJsonArray("styles")) {
					try {
						JsonObject entry = element.getAsJsonObject();
						String id = entry.get("id").getAsString();
						String metallib = entry.get("metallib").getAsString();
						String kernel = entry.get("kernel").getAsString();
						String name = entry.has("name") ? entry.get("name").getAsString() : id;
						String description = entry.has("description")
							? entry.get("description").getAsString() : "";
						double strength = entry.has("strength") ? entry.get("strength").getAsDouble() : 1.0;
						int customFloats = entry.has("customFloats") ? entry.get("customFloats").getAsInt() : 0;
						Optional<Path> lib = mod.findPath(metallib);
						if (lib.isEmpty()) {
							LOGGER.warn("MMetal style '{}': metallib '{}' not found in {}", id, metallib,
								mod.getMetadata().getId());
							continue;
						}
						MMetalStyles.register(new ManifestStyle(id, name, description, strength,
							lib.get(), kernel, customFloats));
						LOGGER.info("MMetal style registered from {}: {} (kernel {})",
							mod.getMetadata().getId(), id, kernel);
					} catch (Throwable t) {
						LOGGER.warn("MMetal: bad style entry in {}", mod.getMetadata().getId(), t);
					}
				}
			} catch (Throwable t) {
				LOGGER.warn("MMetal: failed to read styles from {}", mod.getMetadata().getId(), t);
			}
		}
	}
}
