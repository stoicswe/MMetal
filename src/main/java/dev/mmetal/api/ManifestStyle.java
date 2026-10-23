package dev.mmetal.api;

import java.nio.file.Path;

/**
 * A style described by a {@code mmetal/styles.json} manifest inside a mod jar.
 * MMetal discovers these automatically, so a shader-only jar can add styles without
 * any Java code.
 */
public record ManifestStyle(String id, String displayName, String description,
                            double defaultStrength, Path metallibPath, String kernelName,
                            int customFloatCount) implements MMetalStyle {

	public ManifestStyle(String id, String displayName, String description,
	                     double defaultStrength, Path metallibPath, String kernelName) {
		this(id, displayName, description, defaultStrength, metallibPath, kernelName, 0);
	}

	@Override
	public String metallibResource() {
		return null;
	}
}
