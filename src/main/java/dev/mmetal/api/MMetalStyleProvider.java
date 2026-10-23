package dev.mmetal.api;

import java.util.List;

/**
 * Optional code-based style plugin entrypoint. Declare it in your mod's
 * {@code fabric.mod.json}:
 *
 * <pre>{@code
 * "entrypoints": { "mmetal:styles": [ "com.example.MyStyleProvider" ] }
 * }</pre>
 *
 * <p>For shader-only style jars you do not need any Java code: ship a
 * {@code mmetal/styles.json} manifest plus your metallib (see docs/STYLES.md).</p>
 */
public interface MMetalStyleProvider {
	List<MMetalStyle> styles();
}
