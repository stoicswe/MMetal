package dev.mmetal.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry for MMetal post-process styles.
 *
 * <p>Built-ins ({@code none}, {@code legends}) are always present. Mods may add their
 * own via {@link #register(MMetalStyle)}; they appear in the Style tab and are saved by
 * id in the config. Unregistering a style while it is selected falls back to {@code none}.</p>
 */
public final class MMetalStyles {

	private static final Map<String, MMetalStyle> STYLES = new LinkedHashMap<>();

	static {
		register(new BuiltIn("none", "None", "No look override."));
	}

	private MMetalStyles() {
	}

	/** Registers (or replaces) a style. Ids must be non-empty; whitespace is stripped. */
	public static synchronized void register(MMetalStyle style) {
		if (style == null) {
			throw new IllegalArgumentException("style is null");
		}
		String id = style.id() == null ? "" : style.id().trim().toLowerCase(Locale.ROOT);
		if (id.isEmpty()) {
			throw new IllegalArgumentException("style id is empty");
		}
		if (!id.equals(style.id())) {
			// Keep the registry key canonical; the style keeps its own id for display.
			STYLES.put(id, style);
			return;
		}
		STYLES.put(id, style);
	}

	/** Removes a contributed style. Built-ins cannot be removed. */
	public static synchronized void unregister(String id) {
		if (id == null) {
			return;
		}
		MMetalStyle style = STYLES.get(id.trim().toLowerCase(Locale.ROOT));
		if (style != null && !style.builtIn()) {
			STYLES.remove(style.id().trim().toLowerCase(Locale.ROOT));
		}
	}

	/** All styles in registration order (built-ins first). */
	public static synchronized List<MMetalStyle> all() {
		return new ArrayList<>(STYLES.values());
	}

	/** Looks up a style by id, falling back to {@code none}. */
	public static synchronized MMetalStyle get(String id) {
		MMetalStyle style = id == null ? null : STYLES.get(id.trim().toLowerCase(Locale.ROOT));
		return style != null ? style : STYLES.get("none");
	}

	/** True when the id resolves to a real look (not {@code none}). */
	public static boolean isActive(String id) {
		return !"none".equals(get(id).id().trim().toLowerCase(Locale.ROOT));
	}

	private record BuiltIn(String id, String displayName, String description)
		implements MMetalStyle {
		@Override
		public boolean builtIn() {
			return true;
		}
	}
}
