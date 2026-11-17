package dev.mikuteto.mtlrender.entity;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.culling.FeatureInstanceStream;
import dev.mikuteto.mtlrender.culling.Visibility;
import dev.mikuteto.mtlrender.debug.AbSwitch;
import dev.mikuteto.mtlrender.debug.FeatureDiagnostics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public final class EntityFeatureRenderer implements FeatureRenderer<ModelFeatureRenderer.Submit<?>> {
	private static final int ATTRIBUTE_STRIDE = 48;
	private static final int POSE_STRIDE = 112;
	private static FeatureInstanceStream current;
	private final ModelFeatureRenderer fallback = new ModelFeatureRenderer();
	private final List<Group> groups = new ArrayList<>();
	private final List<Candidate> candidates = new ArrayList<>();
	private final FeatureInstanceStream stream = new FeatureInstanceStream();
	private boolean frameOpen;
	private long preparedInstances;
	private long executedInstances;
	private long candidateCount;

	public static FeatureInstanceStream currentStream() {
		return current;
	}

	public FeatureInstanceStream stream() {
		return this.stream;
	}

	public long preparedInstances() {
		return this.preparedInstances;
	}

	public long executedInstances() {
		return this.executedInstances;
	}

	public void prepareCandidates(List<SubmitNode> submits) {
		if (this.frameOpen) {
			return;
		}
		this.stream.beginFrame();
		current = this.stream;
		this.frameOpen = true;
		this.preparedInstances = 0L;
		this.executedInstances = 0L;
		this.candidateCount = 0L;
		for (SubmitNode node : submits) {
			if (node instanceof ModelFeatureRenderer.Submit<?> submit) {
				this.candidateCount++;
				this.addCandidate(submit);
			}
		}
		this.stream.activatePendingMask();
	}

	@Override
	public void prepareGroup(FeatureFrameContext context, List<ModelFeatureRenderer.Submit<?>> submits, boolean strictlyOrdered) {
		if (!this.frameOpen) {
			List<SubmitNode> nodes = new ArrayList<>(submits.size());
			nodes.addAll(submits);
			this.prepareCandidates(nodes);
		}
		if (strictlyOrdered || !MetalAccess.active() || !enabled()) {
			this.preparedInstances += submits.size();
			this.fallback.prepareGroup(context, submits, strictlyOrdered);
			this.groups.add(Group.fallback(this.fallbackGroups++, submits));
			return;
		}
		Map<RenderType, Batch> batches = new LinkedHashMap<>();
		List<ModelFeatureRenderer.Submit<?>> unsupported = new ArrayList<>();
		for (ModelFeatureRenderer.Submit<?> submit : submits) {
			Candidate candidate = this.candidate(submit);
			if (candidate == null || !supported(submit) || !EntityPipelines.compiles(EntityPipelines.get(submit.renderType().pipeline()))) {
				unsupported.add(submit);
				continue;
			}
			if (!strictlyOrdered && !this.stream.visible(candidate.slot())) {
				continue;
			}
			Batch batch = batches.computeIfAbsent(submit.renderType(), Batch::new);
			batch.add(submit, candidate.geometry(), candidate.slot());
		}
		int fallbackIndex = -1;
		if (!unsupported.isEmpty()) {
			this.preparedInstances += unsupported.size();
			fallbackIndex = this.fallbackGroups++;
			this.fallback.prepareGroup(context, unsupported, strictlyOrdered);
		}
		this.groups.add(new Group(new ArrayList<>(batches.values()), unsupported, fallbackIndex));
	}

	@Override
	public void executeGroup(FeatureFrameContext context, int groupIndex, List<ModelFeatureRenderer.Submit<?>> submits, boolean strictlyOrdered) {
		Group group = this.groups.get(groupIndex);
		for (Batch batch : group.batches) {
			this.drawBatch(context, batch);
		}
		if (group.fallbackIndex >= 0) {
			this.fallback.executeGroup(context, group.fallbackIndex, group.fallback, strictlyOrdered);
		}
	}

	@Override
	public void finishExecute(FeatureFrameContext context) {
		this.fallback.finishExecute(context);
		FeatureDiagnostics.publish(new FeatureDiagnostics.Snapshot(
			this.candidateCount,
			Math.max(0L, this.candidateCount - this.candidates.size()),
			this.stream.maskRejected(),
			this.stream.forcedVisible(),
			this.preparedInstances,
			this.executedInstances,
			this.stream.maskRequested(),
			this.stream.maskResultReady(),
			this.stream.failOpen()
		));
		this.groups.clear();
		this.candidates.clear();
		this.frameOpen = false;
		if (current == this.stream) {
			current = null;
		}
		this.fallbackGroups = 0;
	}

	@Override
	public void close() {
		this.fallback.close();
		EntityGeometry.closeAll();
		this.stream.invalidate();
		if (current == this.stream) {
			current = null;
		}
	}

	private int fallbackGroups;

	private void drawBatch(FeatureFrameContext context, Batch batch) {
		List<Entry> eligible = new ArrayList<>(batch.submits.size());
		boolean useMask = this.stream.maskMatches(batch.frameId);
		for (Entry entry : batch.submits) {
			if (!useMask || this.stream.visible(entry.slot())) {
				eligible.add(entry);
			}
		}
		if (eligible.isEmpty()) {
			return;
		}
		this.executedInstances += eligible.size();
		PreparedRenderType prepared = batch.renderType.prepare();
		EntityPipelines.compiles(EntityPipelines.get(prepared.pipeline()));
		GpuBufferSlice data = this.writeData(eligible);
		RenderTarget target = batch.renderType.outputTarget().getRenderTarget();
		var color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
		var depth = target.useDepth ? (RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView()) : null;
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
			() -> "MTLRender entity batch", color, Optional.empty(), depth, OptionalDouble.empty())) {
			pass.setPipeline(EntityPipelines.get(prepared.pipeline()));
			if (prepared.scissorState().enabled()) {
				pass.enableScissor(prepared.scissorState().x(), prepared.scissorState().y(), prepared.scissorState().width(), prepared.scissorState().height());
			}
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", prepared.dynamicTransforms());
			pass.setUniform(EntityPipelines.DATA, data);
			for (PreparedRenderType.Texture texture : prepared.textures()) {
				pass.bindTexture(texture.name(), texture.textureView(), texture.sampler());
			}
			pass.draw(batch.maxVertices(eligible), eligible.size(), 0, 0);
		}
	}

	private GpuBufferSlice writeData(List<Entry> submits) {
		int poseOffset = ATTRIBUTE_STRIDE * submits.size();
		int poseBytes = 0;
		for (Entry entry : submits) {
			poseBytes += entry.geometry.partCount() * POSE_STRIDE;
		}
		int size = poseOffset + poseBytes;
		GpuBufferSlice data = RenderSystem.getDevice().createCommandEncoder().transientMemory()
			.allocateGpuMapped(size, 256, GpuBuffer.USAGE_UNIFORM, size, 1).slice();
		MemorySegment memory = MetalAccess.contents(data.buffer()).asSlice(data.offset(), size);
		long poseAddress = MetalAccess.gpuAddress(data.buffer()) + data.offset() + poseOffset;
		int poseCursor = 0;
		for (int i = 0; i < submits.size(); i++) {
			Entry entry = submits.get(i);
			long attribute = (long) i * ATTRIBUTE_STRIDE;
			long poses = poseAddress + poseCursor;
			memory.set(ValueLayout.JAVA_LONG_UNALIGNED, attribute, MetalAccess.gpuAddress(entry.geometry.buffer()));
			memory.set(ValueLayout.JAVA_LONG_UNALIGNED, attribute + 8, poses);
			memory.set(ValueLayout.JAVA_INT_UNALIGNED, attribute + 16, entry.geometry.partCount());
			memory.set(ValueLayout.JAVA_INT_UNALIGNED, attribute + 20, entry.geometry.vertexCount());
			memory.set(ValueLayout.JAVA_INT_UNALIGNED, attribute + 24, entry.submit.lightCoords());
			memory.set(ValueLayout.JAVA_INT_UNALIGNED, attribute + 28, entry.submit.overlayCoords());
			memory.set(ValueLayout.JAVA_INT_UNALIGNED, attribute + 32, entry.submit.tintedColor());
			memory.set(ValueLayout.JAVA_INT_UNALIGNED, attribute + 36, 0);
			memory.set(ValueLayout.JAVA_LONG_UNALIGNED, attribute + 40, 0L);
			writePoses(memory, poseOffset + poseCursor, entry);
			poseCursor += entry.geometry.partCount() * POSE_STRIDE;
		}
		return data;
	}

	private static void writePoses(MemorySegment memory, int offset, Entry entry) {
		Matrix4f outer = new Matrix4f(entry.submit.pose().pose());
		Matrix3f outerNormal = new Matrix3f(entry.submit.pose().normal());
		for (String path : entry.geometry.parts().keySet()) {
			PoseData part = entry.poses.get(path);
			if (part == null) {
				part = new PoseData(new Matrix4f(), new Matrix3f());
			}
			Matrix4f combined = outer.mul(part.pose(), new Matrix4f());
			Matrix3f combinedNormal = outerNormal.mul(part.normal(), new Matrix3f());
			float[] values = new float[16];
			combined.get(values);
			for (int i = 0; i < values.length; i++) {
				memory.set(ValueLayout.JAVA_FLOAT_UNALIGNED, (long) offset + i * 4L, values[i]);
			}
			float[] normals = new float[9];
			combinedNormal.get(normals);
			for (int i = 0; i < normals.length; i++) {
				long normalOffset = (long) offset + 64L + i / 3 * 16L + i % 3 * 4L;
				memory.set(ValueLayout.JAVA_FLOAT_UNALIGNED, normalOffset, normals[i]);
			}
			offset += POSE_STRIDE;
		}
	}

	private static boolean enabled() {
		return "entityBatch".equals(AbSwitch.NAME) ? AbSwitch.b() : Config.get().entities.batching;
	}

	private static boolean supported(ModelFeatureRenderer.Submit<?> submit) {
		return submit.sprite() == null && submit.sheetedDecalPose() == null && submit.model() != null
			&& submit.renderType().canConsolidateConsecutiveGeometry();
	}

	private final class Batch {
		private final RenderType renderType;
		private final List<Entry> submits = new ArrayList<>();
		private final long frameId;
		private int maxVertices;

		private int maxVertices(List<Entry> entries) {
			int result = 0;
			for (Entry entry : entries) {
				result = Math.max(result, entry.geometry.vertexCount());
			}
			return result;
		}

		private Batch(RenderType renderType) {
			this.renderType = renderType;
			this.frameId = EntityFeatureRenderer.this.stream.frameId();
		}

		private void add(ModelFeatureRenderer.Submit<?> submit, EntityGeometry geometry, int slot) {
			EntityFeatureRenderer.this.preparedInstances++;
			((Model) submit.model()).setupAnim(submit.state());
			Map<String, PoseData> poses = new LinkedHashMap<>();
			submit.model().root().visit(new PoseStack(), (pose, path, cubeIndex, cube) -> poses.putIfAbsent(path, new PoseData(new Matrix4f(pose.pose()), new Matrix3f(pose.normal()))));
			this.submits.add(new Entry(submit, geometry, Map.copyOf(poses), slot));
			this.maxVertices = Math.max(this.maxVertices, geometry.vertexCount());
		}
	}

	private void addCandidate(ModelFeatureRenderer.Submit<?> submit) {
		if (!supported(submit)) {
			return;
		}
		try {
			if (!EntityPipelines.compiles(EntityPipelines.get(submit.renderType().pipeline()))) {
				return;
			}
			EntityGeometry geometry = EntityGeometry.get(submit.model());
			if (geometry.vertexCount() != 0) {
				this.candidates.add(new Candidate(submit, geometry, this.registerInstance(submit, geometry)));
			}
		} catch (RuntimeException exception) {
		}
	}

	private Candidate candidate(ModelFeatureRenderer.Submit<?> submit) {
		for (Candidate candidate : this.candidates) {
			if (candidate.submit() == submit) {
				return candidate;
			}
		}
		return null;
	}

	private int registerInstance(ModelFeatureRenderer.Submit<?> submit, EntityGeometry geometry) {
		int flags = 0;
		Object state = submit.state();
		if (state instanceof BlockEntityRenderState) {
			flags |= FeatureInstanceStream.FLAG_BLOCK_ENTITY;
			if (!AbSwitch.is(AbSwitch.FEATURE_HIZ, Config.get().blockEntities.hizOcclusion)) {
				flags |= FeatureInstanceStream.FLAG_FORCE_VISIBLE;
			}
		} else if (!AbSwitch.is(AbSwitch.FEATURE_HIZ, Config.get().entities.hizOcclusion)) {
			flags |= FeatureInstanceStream.FLAG_FORCE_VISIBLE;
		}
		if (state instanceof EntityRenderState entity) {
			Minecraft minecraft = Minecraft.getInstance();
			boolean cameraEntity = false;
			if (minecraft.gameRenderer.mainCamera() != null) {
				var camera = minecraft.gameRenderer.mainCamera();
				cameraEntity = Math.abs(entity.x - camera.position().x) < 0.001
					&& Math.abs(entity.y - camera.position().y) < 0.001
					&& Math.abs(entity.z - camera.position().z) < 0.001;
			}
			boolean spectator = minecraft.player != null && minecraft.player.isSpectator();
			flags |= Visibility.exceptionFlags(entity, cameraEntity, spectator, entity.passengerOffset != null);
		}

		EntityGeometry.Bounds bounds = geometry.bounds();
		Matrix4f pose = new Matrix4f(submit.pose().pose());
		float[] transformed = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
		for (int corner = 0; corner < 8; corner++) {
			float x = (corner & 1) == 0 ? bounds.minX() : bounds.maxX();
			float y = (corner & 2) == 0 ? bounds.minY() : bounds.maxY();
			float z = (corner & 4) == 0 ? bounds.minZ() : bounds.maxZ();
			org.joml.Vector3f point = pose.transformPosition(x, y, z, new org.joml.Vector3f());
			transformed[0] = Math.min(transformed[0], point.x);
			transformed[1] = Math.min(transformed[1], point.y);
			transformed[2] = Math.min(transformed[2], point.z);
			transformed[3] = Math.max(transformed[3], point.x);
			transformed[4] = Math.max(transformed[4], point.y);
			transformed[5] = Math.max(transformed[5], point.z);
		}
		return this.stream.add(transformed[0], transformed[1], transformed[2], transformed[3], transformed[4], transformed[5], flags);
	}

	private record Candidate(ModelFeatureRenderer.Submit<?> submit, EntityGeometry geometry, int slot) {
	}

	private record Entry(ModelFeatureRenderer.Submit<?> submit, EntityGeometry geometry, Map<String, PoseData> poses, int slot) {
	}

	private record PoseData(Matrix4f pose, Matrix3f normal) {
	}

	private record Group(List<Batch> batches, List<ModelFeatureRenderer.Submit<?>> fallback, int fallbackIndex) {
		private static Group fallback(int index, List<ModelFeatureRenderer.Submit<?>> submits) {
			return new Group(List.of(), submits, index);
		}
	}
}
