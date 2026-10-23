package dev.mmetal.metal.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes Minecraft's GUI re-layout to the mod (called when the render scale changes). */
@Mixin(Minecraft.class)
public interface MinecraftInvoker {

	@Invoker("resizeGui")
	void mmetal$resizeGui();
}