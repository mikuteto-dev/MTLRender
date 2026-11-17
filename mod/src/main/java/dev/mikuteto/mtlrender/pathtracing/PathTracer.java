package dev.mikuteto.mtlrender.pathtracing;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.config.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public final class PathTracer {
	private record Section(int x, int y, int z, VoxelBrickSet bricks) {
	}

	private static final PathTracer INSTANCE = new PathTracer();
	private static final int BANKS = 3;
	private static final int RADIUS = 4;
	private static final int DIAMETER = RADIUS * 2 + 1;
	private static final int SLOTS = DIAMETER * DIAMETER * DIAMETER;
	private static final int TABLE_BYTES = SLOTS * Integer.BYTES;
	private static final int BRICK_BYTES = SLOTS * VoxelBrick.BYTES;
	private final Config.PathTracing config = Config.get().pathTracing;
	private final Map<Long, Section> sections = new HashMap<>();
	private final GpuBuffer[] bricks = new GpuBuffer[BANKS];
	private final GpuBuffer[] table = new GpuBuffer[BANKS];
	private CelestialLighting celestialLighting = new CelestialLighting();
	private final float[] inverseClip = new float[16];
	private final float[] previousClip = new float[16];
	private final float[] reprojectionClip = new float[16];
	private boolean hasPreviousClip;
	private Vec3 camera = Vec3.ZERO;
	private int cameraSectionX;
	private int cameraSectionY;
	private int cameraSectionZ;
	private boolean hasCameraSection;
	private int tableOriginX;
	private int tableOriginY;
	private int tableOriginZ;
	private int bank;
	private boolean prepared;
	private boolean rendered;

	private PathTracer() {
	}

	public static PathTracer get() {
		return INSTANCE;
	}

	public boolean active() {
		return config.enabled && MetalAccess.active();
	}

	public void updateSection(BlockPos origin, VoxelBrickSet brickSet) {
		if (!active()) {
			return;
		}
		long key = SectionPos.asLong(Math.floorDiv(origin.getX(), 16), Math.floorDiv(origin.getY(), 16), Math.floorDiv(origin.getZ(), 16));
		if (brickSet == null) {
			synchronized (sections) {
				Section previous = sections.remove(key);
				if (previous != null) {
					previous.bricks().release();
				}
			}
			return;
		}
		int x = Math.floorDiv(origin.getX(), 16);
		int y = Math.floorDiv(origin.getY(), 16);
		int z = Math.floorDiv(origin.getZ(), 16);
		if (hasCameraSection && outsideRadius(x, y, z)) {
			return;
		}
		synchronized (sections) {
			Section previous = sections.put(key, new Section(x, y, z, brickSet));
			if (previous != null && previous.bricks() != brickSet) {
				previous.bricks().release();
			}
		}
	}

	public boolean owns(VoxelBrickSet brickSet) {
		synchronized (sections) {
			for (Section section : sections.values()) {
				if (section.bricks() == brickSet) {
					return true;
				}
			}
		}
		return false;
	}

	public void prepare(Vec3 camera, Matrix4fc clip) {
		if (!active()) {
			prepared = false;
			return;
		}
		var levelState = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState;
		this.celestialLighting = CelestialLighting.from(levelState.skyRenderState, levelState.cameraRenderState.viewRotationMatrix);
		this.camera = camera;
		int nextCameraSectionX = Math.floorDiv((int) Math.floor(camera.x), 16);
		int nextCameraSectionY = Math.floorDiv((int) Math.floor(camera.y), 16);
		int nextCameraSectionZ = Math.floorDiv((int) Math.floor(camera.z), 16);
		boolean cameraChanged = !hasCameraSection || nextCameraSectionX != cameraSectionX
			|| nextCameraSectionY != cameraSectionY || nextCameraSectionZ != cameraSectionZ;
		cameraSectionX = nextCameraSectionX;
		cameraSectionY = nextCameraSectionY;
		cameraSectionZ = nextCameraSectionZ;
		hasCameraSection = true;
		if (cameraChanged) {
			pruneSections();
		}
		tableOriginX = cameraSectionX - RADIUS;
		tableOriginY = cameraSectionY - RADIUS;
		tableOriginZ = cameraSectionZ - RADIUS;
		float[] currentClip = clip.get(new float[16]);
		if (!hasPreviousClip) {
			System.arraycopy(currentClip, 0, previousClip, 0, previousClip.length);
			hasPreviousClip = true;
		}
		System.arraycopy(previousClip, 0, reprojectionClip, 0, reprojectionClip.length);
		System.arraycopy(currentClip, 0, previousClip, 0, previousClip.length);
		new Matrix4f(clip).invert().get(inverseClip);
		bank = (int) (MetalAccess.submittedFrames() % BANKS);
		GpuBuffer brickBuffer = ensure(bricks, bank, BRICK_BYTES, "MTLRender path brickmap");
		GpuBuffer tableBuffer = ensure(table, bank, TABLE_BYTES, "MTLRender path section table");
		MemorySegment tableContents = MetalAccess.contents(tableBuffer);
		tableContents.fill((byte) 0);
		synchronized (sections) {
		for (Section section : sections.values()) {
			int localX = section.x() - tableOriginX;
			int localY = section.y() - tableOriginY;
			int localZ = section.z() - tableOriginZ;
			if (localX < 0 || localX >= DIAMETER || localY < 0 || localY >= DIAMETER || localZ < 0 || localZ >= DIAMETER) {
				continue;
			}
			int slot = localX + DIAMETER * (localY + DIAMETER * localZ);
			VoxelBrick merged = section.bricks().combine();
			if (merged != null) {
				tableContents.set(ValueLayout.JAVA_INT, (long) slot * Integer.BYTES, 1);
				MemorySegment.copy(MemorySegment.ofBuffer(merged.data()), 0, MetalAccess.contents(brickBuffer), (long) slot * VoxelBrick.BYTES, VoxelBrick.BYTES);
			}
		}
		}
		prepared = true;
		rendered = false;
	}

	public void render(GpuTextureView color, GpuTextureView depth) {
		if (!prepared || rendered || !active()) {
			return;
		}
		GpuBuffer brickBuffer = bricks[bank];
		GpuBuffer tableBuffer = table[bank];
		if (brickBuffer == null || tableBuffer == null) {
			return;
		}
		MetalAccess.pathTrace(
			color,
			depth,
			brickBuffer,
			tableBuffer,
			color.getWidth(0),
			color.getHeight(0),
			config.resolutionScale,
			Math.max(0, config.bounces),
			(float) camera.x,
			(float) camera.y,
			(float) camera.z,
			inverseClip,
			reprojectionClip,
			tableOriginX,
			tableOriginY,
			tableOriginZ,
			celestialLighting.sunDirectionX(),
			celestialLighting.sunDirectionY(),
			celestialLighting.sunDirectionZ(),
			celestialLighting.sunColorR(),
			celestialLighting.sunColorG(),
			celestialLighting.sunColorB(),
			celestialLighting.moonDirectionX(),
			celestialLighting.moonDirectionY(),
			celestialLighting.moonDirectionZ(),
			celestialLighting.moonColorR(),
			celestialLighting.moonColorG(),
			celestialLighting.moonColorB()
		);
		rendered = true;
	}

	private boolean outsideRadius(int x, int y, int z) {
		return Math.abs(x - cameraSectionX) > RADIUS || Math.abs(y - cameraSectionY) > RADIUS
			|| Math.abs(z - cameraSectionZ) > RADIUS;
	}

	private void pruneSections() {
		synchronized (sections) {
			Iterator<Map.Entry<Long, Section>> iterator = sections.entrySet().iterator();
			while (iterator.hasNext()) {
				Section section = iterator.next().getValue();
				if (outsideRadius(section.x(), section.y(), section.z())) {
					iterator.remove();
					section.bricks().release();
				}
			}
		}
	}

	private static GpuBuffer ensure(GpuBuffer[] buffers, int index, long size, String name) {
		GpuBuffer buffer = buffers[index];
		if (buffer == null || buffer.isClosed()) {
			buffer = RenderSystem.getDevice().createBuffer(() -> name, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, size);
			buffers[index] = buffer;
		}
		return buffer;
	}
}
