package dev.mmetal.metal.render;

import dev.mmetal.metal.nativebridge.MetalNative;
import dev.mmetal.metal.nativebridge.ObjC;
import dev.mmetal.metal.nativebridge.ObjCSend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.lang.invoke.MethodHandle;

import static dev.mmetal.metal.nativebridge.Layouts.NSUINTEGER;
import static dev.mmetal.metal.nativebridge.Layouts.POINTER;

/**
 * Hardware ray-traced shadows: a Metal primitive acceleration structure built from
 * per-block bounding boxes ({@code MTLAxisAlignedBoundingBox}) around the camera.
 *
 * <p>Shadow rays in {@code mm_rt_shadows} then traverse real BVH geometry on the GPU,
 * giving exact world-space occlusion (unlike screen-space marching, which loses
 * occluders outside the view). The structure is rebuilt when the camera moves far
 * enough or after a fixed interval, keeping the cost amortised.</p>
 */
final class RtShadowBvh {
	private static final int FLOATS_PER_BOX = 6; // MTLAxisAlignedBoundingBox = min.xyz, max.xyz
	private static final long BOX_STRIDE = 24;

	private final Logger logger;
	private final Arena arena = Arena.global();

	private float[] boxes = new float[0];
	private MemorySegment descriptor;
	private MemorySegment accelerationStructure;
	private double lastX = Double.NaN;
	private double lastY;
	private double lastZ;
	private int primitiveCount;
	private int framesSinceBuild = Integer.MAX_VALUE;
	private boolean available = true;
	private boolean loggedFailure;

	RtShadowBvh(Logger logger) {
		this.logger = logger;
	}

	boolean isReady() {
		return available && accelerationStructure != null && accelerationStructure.address() != 0;
	}

	MemorySegment accelerationStructure() {
		return accelerationStructure;
	}

	int primitiveCount() {
		return primitiveCount;
	}

	/** Rebuilds when the camera moved or the world had time to change. */
	void update(Minecraft mc, int radius) {
		if (!available) {
			return;
		}
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			return;
		}
		framesSinceBuild++;
		Vec3 cam = mc.player.position();
		boolean moved = Double.isNaN(lastX)
			|| (cam.x - lastX) * (cam.x - lastX) + (cam.y - lastY) * (cam.y - lastY)
			+ (cam.z - lastZ) * (cam.z - lastZ) > 64.0; // 8 blocks
		if (!moved && framesSinceBuild < 60) {
			return;
		}
		try {
			collect(level, cam, radius);
			build();
			lastX = cam.x;
			lastY = cam.y;
			lastZ = cam.z;
			framesSinceBuild = 0;
		} catch (Throwable t) {
			available = false;
			if (!loggedFailure) {
				loggedFailure = true;
				logger.warn("MMetal hardware ray-traced shadows unavailable - falling back", t);
			}
		}
	}

	private void collect(ClientLevel level, Vec3 center, int radius) {
		int cx = (int) Math.floor(center.x);
		int cy = (int) Math.floor(center.y);
		int cz = (int) Math.floor(center.z);
		int needed = (radius * 2 + 1) * (radius * 2 + 1) * (radius * 2 + 1);
		if (boxes.length < needed * FLOATS_PER_BOX) {
			boxes = new float[needed * FLOATS_PER_BOX];
		}
		int n = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = cx - radius; x <= cx + radius; x++) {
			for (int y = cy - radius; y <= cy + radius; y++) {
				for (int z = cz - radius; z <= cz + radius; z++) {
					pos.set(x, y, z);
					BlockState state = level.getBlockState(pos);
					if (!state.canOcclude()) {
						continue;
					}
					int i = n * FLOATS_PER_BOX;
					boxes[i] = x;
					boxes[i + 1] = y;
					boxes[i + 2] = z;
					boxes[i + 3] = x + 1;
					boxes[i + 4] = y + 1;
					boxes[i + 5] = z + 1;
					n++;
				}
			}
		}
		primitiveCount = n;
	}

	private void build() {
		if (primitiveCount <= 0) {
			return;
		}
		// Geometry descriptor: bounding boxes, stride 24 bytes.
		MemorySegment geometry = MetalNative.allocInit(
			ObjC.cls("MTLAccelerationStructureBoundingBoxGeometryDescriptor"));
		MemorySegment buffer = MetalNative.newBuffer(
			arena.allocateFrom(ValueLayout.JAVA_FLOAT, Arrays.copyOf(boxes, primitiveCount * FLOATS_PER_BOX)),
			(long) primitiveCount * BOX_STRIDE);
		setObject(geometry, "setBoundingBoxBuffer:", buffer);
		setLong(geometry, "setBoundingBoxCount:", primitiveCount);
		setLong(geometry, "setBoundingBoxStride:", BOX_STRIDE);

		// Wrap it in an NSMutableArray for the primitive descriptor.
		MemorySegment array = (MemorySegment) ObjCSend.invoke(
			ObjC.send(FunctionDescriptor.of(POINTER, POINTER, POINTER)),
			ObjC.cls("NSMutableArray"), ObjC.sel("array"));
		ObjCSend.invoke(ObjC.send(FunctionDescriptor.ofVoid(POINTER, POINTER, POINTER)),
			array, ObjC.sel("addObject:"), geometry);

		descriptor = MetalNative.allocInit(ObjC.cls("MTLPrimitiveAccelerationStructureDescriptor"));
		setObject(descriptor, "setGeometryDescriptors:", array);

		accelerationStructure = MetalNative.buildAccelerationStructure(descriptor);
	}

	private static void setObject(MemorySegment target, String selector, MemorySegment value) {
		ObjCSend.invoke(ObjC.send(FunctionDescriptor.ofVoid(POINTER, POINTER, POINTER)),
			target, ObjC.sel(selector), value);
	}

	private static void setLong(MemorySegment target, String selector, long value) {
		ObjCSend.invoke(ObjC.send(FunctionDescriptor.ofVoid(POINTER, POINTER, NSUINTEGER)),
			target, ObjC.sel(selector), value);
	}
}
