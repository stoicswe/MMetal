package dev.mmetal.metal.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.mmetal.metal.render.HeldLight;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the real camera-space transform of each first-person hand at the moment
 * the item pose is applied (bobbing, swing and arm transforms all included), so the
 * held-light emitter sits exactly on the rendered item.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsAndItemsRendererMixin {

	@Inject(method = "applyItemArmTransform", at = @At("TAIL"))
	private void mmetal$captureHandTransform(PoseStack poseStack, HumanoidArm arm, float swing,
	                                         CallbackInfo ci) {
		Matrix4f pose = new Matrix4f(poseStack.last().pose());
		HeldLight.captureHand(arm, pose);
	}
}