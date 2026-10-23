package dev.mmetal.metal.render;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import dev.mmetal.metal.config.MMetalConfig;

/**
 * Detects a light-emitting item held in either hand and drives:
 * <ul>
 *   <li>world-space illumination ({@code mm_world_light}) at the hand's real position,</li>
 *   <li>firelight flicker (layered noise on intensity),</li>
 *   <li>subtle flame particles at the emitter.</li>
 * </ul>
 */
public final class HeldLight {
	public static volatile double strength;
	public static volatile float red = 1.0f;
	public static volatile float green = 0.72f;
	public static volatile float blue = 0.35f;
	public static volatile float posX = 0.80f;
	public static volatile float posY = 0.14f;

	/** World position of the held flame and its flicker factor for this frame. */
	public static volatile double lightX;
	public static volatile double lightY;
	public static volatile double lightZ;
	public static volatile float flicker = 1.0f;
	public static volatile boolean soulFire;

	/** Camera-space hand transforms captured during the first-person item draw. */
	private static final org.joml.Matrix4f mainHandMatrix = new org.joml.Matrix4f();
	private static final org.joml.Matrix4f offHandMatrix = new org.joml.Matrix4f();
	private static boolean mainHandCaptured;
	private static boolean offHandCaptured;

	/** Called by the first-person renderer mixin after each hand's pose is applied. */
	public static void captureHand(net.minecraft.world.entity.HumanoidArm arm, org.joml.Matrix4f pose) {
		if (arm == net.minecraft.world.entity.HumanoidArm.RIGHT) {
			mainHandMatrix.set(pose);
			mainHandCaptured = true;
		} else {
			offHandMatrix.set(pose);
			offHandCaptured = true;
		}
	}

	private static String lastReported;
	private static int particleTick;
	private static double seed = Double.NaN;

	private HeldLight() {
	}

	public static void update(Minecraft mc) {
		strength = 0.0;
		if (mc == null || mc.player == null || mc.level == null) {
			return;
		}
		double tip = tipHeight();
		if (inspect(mc, mc.player.getMainHandItem(), net.minecraft.world.entity.HumanoidArm.RIGHT, tip)) {
			return;
		}
		inspect(mc, mc.player.getOffhandItem(), net.minecraft.world.entity.HumanoidArm.LEFT, tip);
	}

	private static double tipHeight() {
		MMetalConfig config = MetalPipeline.config();
		return config != null && config.effects.flameTipHeight > 0.0
			? config.effects.flameTipHeight : 0.35;
	}

	private static boolean inspect(Minecraft mc, ItemStack stack,
	                               net.minecraft.world.entity.HumanoidArm arm, double tipHeight) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		int emission = 0;
		if (stack.getItem() instanceof BlockItem blockItem) {
			BlockState state = blockItem.getBlock().defaultBlockState();
			try {
				emission = state.getLightEmission();
			} catch (Throwable ignored) {
				emission = 0;
			}
		}
		if (emission <= 0) {
			return false;
		}

		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		strength = emission / 15.0;
		soulFire = path.contains("soul");

		if (soulFire) {
			red = 0.45f;
			green = 0.85f;
			blue = 1.0f;
		} else if (path.contains("glowstone") || path.contains("shroomlight") || path.contains("froglight")) {
			red = 1.0f;
			green = 0.92f;
			blue = 0.62f;
		} else if (path.contains("sea_lantern") || path.contains("end_rod")) {
			red = 0.85f;
			green = 1.0f;
			blue = 0.95f;
		} else {
			red = 1.0f;
			green = 0.72f;
			blue = 0.35f;
		}

		// --- world position of the flame tip ---
		// The REAL camera-space hand pose captured during the item draw (bobbing,
		// swing and arm transforms included), with the flame lifted above the item
		// origin. Falls back to vanilla's static hand offset before the first draw.
		Vec3 eye = mc.player.getEyePosition();
		org.joml.Matrix4f viewRot = mc.gameRenderer.mainCamera()
			.getViewRotationMatrix(new org.joml.Matrix4f());
		org.joml.Matrix4f eyeToWorld = new org.joml.Matrix4f(viewRot).transpose();

		org.joml.Matrix4f handPose;
		if (arm == net.minecraft.world.entity.HumanoidArm.RIGHT && mainHandCaptured) {
			handPose = mainHandMatrix;
		} else if (arm == net.minecraft.world.entity.HumanoidArm.LEFT && offHandCaptured) {
			handPose = offHandMatrix;
		} else {
			float sideSign = arm == net.minecraft.world.entity.HumanoidArm.RIGHT ? 1.0f : -1.0f;
			handPose = new org.joml.Matrix4f().translation(0.56f * sideSign, -0.52f, -0.72f);
		}
		org.joml.Vector3f tipCam = handPose.transformPosition(
			new org.joml.Vector3f(0.0f, (float) tipHeight, 0.0f));
		org.joml.Vector3f world = eyeToWorld.transformPosition(tipCam);
		lightX = eye.x + world.x;
		lightY = eye.y + world.y;
		lightZ = eye.z + world.z;

		// Project the emitter back to screen for the halo anchor.
		var cameraState = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		if (cameraState != null && cameraState.projectionMatrix != null) {
			org.joml.Vector4f clip = new org.joml.Vector4f(
				(float) (lightX - eye.x), (float) (lightY - eye.y), (float) (lightZ - eye.z), 1.0f);
			viewRot.transform(clip);
			cameraState.projectionMatrix.transform(clip);
			if (clip.w > 0.0001f) {
				posX = clip.x / clip.w * 0.5f + 0.5f;
				posY = 0.5f - clip.y / clip.w * 0.5f;
			}
		}

		// --- firelight flicker: layered noise, deterministic per session ---
		if (Double.isNaN(seed)) {
			seed = (System.nanoTime() % 100_000L) / 1000.0;
		}
		double t = System.nanoTime() / 1.0e9 + seed;
		double n = 0.09 * Math.sin(t * 11.3)
			+ 0.05 * Math.sin(t * 26.7 + 1.3)
			+ 0.03 * Math.sin(t * 53.1 + 2.1)
			+ 0.02 * Math.sin(t * 97.7 + 0.7);
		flicker = (float) Math.max(0.62, Math.min(1.06, 0.84 + n));

		// --- subtle flame particles at the emitter (~20/s) ---
		MMetalConfig pipelineConfig = MetalPipeline.config();
		boolean particlesEnabled = pipelineConfig == null || pipelineConfig.effects.heldLightParticles;
		if (particlesEnabled && ++particleTick % 3 == 0) {
			ParticleOptions type = soulFire ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME;
			double jx = (Math.random() - 0.5) * 0.10;
			double jz = (Math.random() - 0.5) * 0.10;
			mc.level.addParticle(type,
				lightX + jx, lightY, lightZ + jz,
				0.0, 0.012, 0.0);
		}

		if (!path.equals(lastReported)) {
			lastReported = path;
			MetalPipeline.LOGGER.info("MMetal held light: {} (emission {}, at {})", path, emission,
				String.format(java.util.Locale.ROOT, "%.1f, %.1f, %.1f", lightX, lightY, lightZ));
		}
		return true;
	}

}
