package dev.mmetal.metal.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Per-frame camera state captured on the render thread at present time: view rotation,
 * projection, eye position and far plane, with previous-frame copies kept for motion
 * vector generation. Source of truth is {@link CameraRenderState}, which 26.3 vanilla
 * populates every frame with public fields.
 */
final class FrameState {
	final Matrix4f curViewRotation = new Matrix4f();
	final Matrix4f curProjection = new Matrix4f();
	final Vector3f curEye = new Vector3f();
	float curDepthFar = 1024.0f;
	float fovDegrees = 70.0f;
	float curXRot;
	float curYRot;

	final Matrix4f prevViewRotation = new Matrix4f();
	final Matrix4f prevProjection = new Matrix4f();
	final Vector3f prevEye = new Vector3f();
	float prevXRot;
	float prevYRot;
	boolean hasPrevious = false;

	/** Captures the current frame's camera state. Returns false when unavailable (menus etc.). */
	boolean capture(Minecraft mc) {
		try {
			CameraRenderState crs = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
			if (crs != null && crs.initialized && crs.viewRotationMatrix != null && crs.projectionMatrix != null) {
				curViewRotation.set(crs.viewRotationMatrix);
				curProjection.set(crs.projectionMatrix);
				Vec3 pos = crs.pos;
				curEye.set((float) pos.x, (float) pos.y, (float) pos.z);
				curDepthFar = crs.depthFar > 0.0f ? crs.depthFar : 1024.0f;
				curXRot = crs.xRot;
				curYRot = crs.yRot;
			} else {
				return false;
			}
			Integer fov = mc.options.fov().get();
			fovDegrees = fov != null ? fov.floatValue() : 70.0f;
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	void advance() {
		prevViewRotation.set(curViewRotation);
		prevProjection.set(curProjection);
		prevEye.set(curEye);
		prevXRot = curXRot;
		prevYRot = curYRot;
		hasPrevious = true;
	}

	/** view-rot-projection matrix (world -> clip without translation): P * R. */
	Matrix4f viewRotProjection(Matrix4f dest, boolean previous) {
		Matrix4f p = previous ? prevProjection : curProjection;
		Matrix4f r = previous ? prevViewRotation : curViewRotation;
		dest.set(p);
		dest.mul(r);
		return dest;
	}

	Matrix4f viewRotation(boolean previous) {
		return previous ? prevViewRotation : curViewRotation;
	}

	Vector3f eye(boolean previous) {
		return previous ? prevEye : curEye;
	}
}