package dev.mikuteto.mtlrender.backend;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import org.joml.Vector4fc;

final class CommandStream {
	private static final int BEGIN_PASS = 1;
	private static final int END_PASS = 2;
	private static final int SET_PIPELINE = 3;
	private static final int BIND_BUFFER = 4;
	private static final int BIND_TEXTURE = 5;
	private static final int BIND_TEXEL_BUFFER = 6;
	private static final int VERTEX_BUFFER = 7;
	private static final int INDEX_BUFFER = 8;
	private static final int DRAW = 9;
	private static final int DRAW_INDEXED = 10;
	private static final int SCISSOR = 11;
	private static final int COPY_BUFFER = 13;
	private static final int COPY_BUFFER_TO_TEXTURE = 14;
	private static final int COPY_TEXTURE_TO_BUFFER = 15;
	private static final int COPY_TEXTURE_TO_TEXTURE = 16;
	private static final int CLEAR = 17;
	private static final int CLEAR_REGION = 18;
	private static final int BLIT_TO_SURFACE = 19;
	private static final int WRITE_TIMESTAMP = 20;
	private static final int TERRAIN_CULL = 21;
	private static final int DRAW_INDEXED_INDIRECT = 22;
	private static final int TERRAIN_EXPAND = 23;
	private static final int TERRAIN_EXPAND_CHECK = 24;
	private static final int PATH_TRACE = 25;
	private static final int GENERATE_MIPMAPS = 42;
	private static final int METALFX = 26;
	private static final int TERRAIN_SORT = 27;
	private static final int BEGIN_COMPUTE = 28;
	private static final int END_COMPUTE = 29;
	private static final int COMPUTE_PIPELINE = 30;
	private static final int COMPUTE_BUFFER = 31;
	private static final int COMPUTE_TEXTURE = 32;
	private static final int COMPUTE_IMAGE = 33;
	private static final int COMPUTE_BYTES = 34;
	private static final int COMPUTE_DISPATCH = 35;
	private static final int COMPUTE_DISPATCH_INDIRECT = 36;
	private static final int DRAW_MESH = 37;
	private static final int MESH_DISPATCH = 28;
	private static final int MESH_DISPATCH_WORDS = 30;
	private static final int LOD_CULL = 38;
	private static final int FEATURE_CULL = 39;
	private static final int TERRAIN_OIT_RESOLVE = 40;
	private static final int FEATURE_AABB_WORDS = 6;
	private static final int LOD_CULL_VERSION = 1;
	static final int PACK_DEPTH_CONVERT = 41;

	static final int READ = 0;
	static final int WRITE = 1;
	static final int READ_WRITE = 2;

	private static final long INITIAL_WORDS = 1 << 16;

	private MemorySegment memory = Arena.global().allocate(ValueLayout.JAVA_INT, INITIAL_WORDS);
	private MemorySegment words = MemorySegment.ofAddress(memory.address()).reinterpret(memory.byteSize());
	private long size;
	private int draws;
	private int passes;

	MemorySegment words() {
		return words;
	}

	long size() {
		return size;
	}

	int draws() {
		return draws;
	}

	int passes() {
		return passes;
	}

	void reset() {
		size = 0;
		draws = 0;
		passes = 0;
	}

	void beginPass(int id, RenderPassDescriptor descriptor, RenderPass.RenderArea area) {
		List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
		op(BEGIN_PASS);
		passes++;
		u32(id);
		u32(colors.size());
		for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> color : colors) {
			if (color == null) {
				u64(0);
				flag(false);
				rgba(null);
			} else {
				u64(MetalTextureView.handle(color.textureView()));
				flag(color.clearValue().isPresent());
				rgba(color.clearValue().orElse(null));
			}
		}
		RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
		u64(depth == null ? 0 : MetalTextureView.handle(depth.textureView()));
		flag(depth != null && depth.clearValue().isPresent());
		f32(depth == null ? 0 : (float) depth.clearValue().orElse(0));
		area(area.x(), area.y(), area.width(), area.height());
	}

	void endPass() {
		op(END_PASS);
	}

	void setPipeline(long pipeline) {
		op(SET_PIPELINE);
		u64(pipeline);
	}

	void beginCompute() {
		beginCompute(false);
	}

	void beginCompute(boolean allowConcurrent) {
		op(BEGIN_COMPUTE);
		u32(allowConcurrent ? 1 : 0);
	}

	void endCompute() {
		op(END_COMPUTE);
	}

	void packDepthConvert(long source, long destination) {
		op(PACK_DEPTH_CONVERT);
		u64(source);
		u64(destination);
	}

	void computePipeline(long pipeline) {
		op(COMPUTE_PIPELINE);
		u64(pipeline);
	}

	void computeBuffer(int slot, long buffer, long offset, int access) {
		op(COMPUTE_BUFFER);
		u32(slot);
		u64(buffer);
		u64(offset);
		u32(access);
	}

	void computeTexture(int textureSlot, int samplerSlot, long view, long sampler, int access) {
		op(COMPUTE_TEXTURE);
		u32(textureSlot);
		u32(samplerSlot);
		u64(view);
		u64(sampler);
		u32(access);
	}

	void computeImage(int slot, long view, int access) {
		op(COMPUTE_IMAGE);
		u32(slot);
		u64(view);
		u32(access);
	}

	void computeBytes(int slot, byte[] bytes) {
		op(COMPUTE_BYTES);
		u32(slot);
		u32(bytes.length);
		int words = (bytes.length + 7) / 8 * 2;
		for (int word = 0; word < words; word++) {
			int value = 0;
			for (int byteIndex = 0; byteIndex < Integer.BYTES; byteIndex++) {
				int index = word * Integer.BYTES + byteIndex;
				if (index < bytes.length) {
					value |= (bytes[index] & 0xff) << (byteIndex * Byte.SIZE);
				}
			}
			u32(value);
		}
	}

	void computeDispatch(int x, int y, int z) {
		op(COMPUTE_DISPATCH);
		u32(x);
		u32(y);
		u32(z);
	}

	void computeDispatchIndirect(long buffer, long offset) {
		op(COMPUTE_DISPATCH_INDIRECT);
		u64(buffer);
		u64(offset);
	}

	void bindBuffer(int index, long buffer, long offset) {
		op(BIND_BUFFER);
		u32(index);
		u64(buffer);
		u64(offset);
	}

	void bindTexture(int textureIndex, int samplerIndex, long view, long sampler, int stages) {
		op(BIND_TEXTURE);
		u32(textureIndex);
		u32(samplerIndex);
		u64(view);
		u64(sampler);
		u32(stages);
	}

	void bindTexelBuffer(int index, long buffer, int format) {
		op(BIND_TEXEL_BUFFER);
		u32(index);
		u64(buffer);
		u32(format);
	}

	void vertexBuffer(int slot, long buffer, long offset) {
		op(VERTEX_BUFFER);
		u32(slot);
		u64(buffer);
		u64(offset);
	}

	void indexBuffer(long buffer, boolean wide) {
		op(INDEX_BUFFER);
		u64(buffer);
		flag(wide);
	}

	void draw(int count, int instances, int first, int baseInstance) {
		op(DRAW);
		draws++;
		u32(count);
		u32(instances);
		u32(first);
		u32(baseInstance);
	}

	void drawIndexed(int count, int instances, int first, int baseVertex, int baseInstance) {
		op(DRAW_INDEXED);
		draws++;
		u32(count);
		u32(instances);
		u32(first);
		u32(baseVertex);
		u32(baseInstance);
	}

	void drawMesh(int threadCount, int objectThreads, int meshThreads) {
		op(DRAW_MESH);
		draws++;
		u32(threadCount);
		u32(objectThreads);
		u32(meshThreads);
	}

	void scissor(int x, int y, int width, int height) {
		op(SCISSOR);
		area(x, y, width, height);
	}

	void copyBuffer(long source, long sourceOffset, long destination, long destinationOffset, long length) {
		op(COPY_BUFFER);
		u64(source);
		u32(Math.toIntExact(sourceOffset));
		u64(destination);
		u32(Math.toIntExact(destinationOffset));
		u32(Math.toIntExact(length));
	}

	void copyBufferToTexture(long source, long offset, int bytesPerRow, long texture, int mip, int layer, int x, int y, int width, int height) {
		op(COPY_BUFFER_TO_TEXTURE);
		u64(source);
		u32(Math.toIntExact(offset));
		u32(bytesPerRow);
		u64(texture);
		u32(mip);
		u32(layer);
		area(x, y, width, height);
	}

	void copyTextureToBuffer(long texture, int mip, int x, int y, int width, int height, long destination, long offset, int bytesPerRow) {
		op(COPY_TEXTURE_TO_BUFFER);
		u64(texture);
		u32(mip);
		area(x, y, width, height);
		u64(destination);
		u32(Math.toIntExact(offset));
		u32(bytesPerRow);
	}

	void copyTextureToTexture(long source, long destination, int mip, int destX, int destY, int sourceX, int sourceY, int width, int height) {
		op(COPY_TEXTURE_TO_TEXTURE);
		u64(source);
		u64(destination);
		u32(mip);
		u32(destX);
		u32(destY);
		u32(sourceX);
		u32(sourceY);
		u32(width);
		u32(height);
	}

	void generateMipmaps(long view, int baseMip, int mipLevels) {
		op(GENERATE_MIPMAPS);
		u64(view);
		u32(baseMip);
		u32(mipLevels);
	}

	void clear(long texture, Vector4fc color, double depth) {
		op(CLEAR);
		u64(texture);
		rgba(color);
		f32((float) depth);
	}

	void clearRegion(long color, Vector4fc rgba, long depth, double depthValue, int x, int y, int width, int height) {
		op(CLEAR_REGION);
		u64(color);
		rgba(rgba);
		u64(depth);
		f32((float) depthValue);
		area(x, y, width, height);
	}

	void blitToSurface(long view) {
		op(BLIT_TO_SURFACE);
		u64(view);
	}

	void writeTimestamp(long pool, int index) {
		op(WRITE_TIMESTAMP);
		u64(pool);
		u32(index);
	}

	void drawIndexedIndirect(long buffer, long offset) {
		op(DRAW_INDEXED_INDIRECT);
		u64(buffer);
		u64(offset);
	}

	void terrainCull(int phase, int mode, long units, long unitsOffset, int count, int firstSolid, int firstCutout, int firstTranslucent, int indicesPerUnit,
		int sections, long out, long outOffset, long argsOffset, long depthView, float[] clip, float[] previousClip, int cameraX, int cameraY, int cameraZ,
		float offsetX, float offsetY, float offsetZ, boolean fromArgs, long countArgsOffset) {
		if (clip.length != 16) {
			throw new IllegalArgumentException("clip must have 16 floats: " + clip.length);
		}
		if (previousClip.length != 16) {
			throw new IllegalArgumentException("previousClip must have 16 floats: " + previousClip.length);
		}
		op(TERRAIN_CULL);
		u32(phase);
		u32(mode);
		u64(units);
		u64(unitsOffset);
		u32(count);
		u32(firstSolid);
		u32(firstCutout);
		u32(firstTranslucent);
		u32(indicesPerUnit);
		u32(sections);
		u64(out);
		u64(outOffset);
		u64(argsOffset);
		u64(depthView);
		for (float value : clip) {
			f32(value);
		}
		for (float value : previousClip) {
			f32(value);
		}
		u32(cameraX);
		u32(cameraY);
		u32(cameraZ);
		f32(offsetX);
		f32(offsetY);
		f32(offsetZ);
		u32(fromArgs ? 1 : 0);
		u64(countArgsOffset);
	}

	void featureCull(long aabbs, long aabbsOffset, int count, long out, long outOffset, long depth, float[] clip, float[] previousClip) {
		featureCull(aabbs, aabbsOffset, count, FEATURE_AABB_WORDS, out, outOffset, depth, clip, previousClip);
	}

	void featureCull(long aabbs, long aabbsOffset, int count, int strideWords, long out, long outOffset, long depth, float[] clip,
		float[] previousClip) {
		if (count < 0 || strideWords != FEATURE_AABB_WORDS || aabbsOffset < 0 || outOffset < 0
			|| aabbsOffset % Integer.BYTES != 0 || outOffset % Integer.BYTES != 0) {
			throw new IllegalArgumentException("invalid feature cull layout");
		}
		Objects.requireNonNull(clip, "clip");
		Objects.requireNonNull(previousClip, "previousClip");
		if (clip.length != 16 || previousClip.length != 16) {
			throw new IllegalArgumentException("feature cull matrices must contain 16 values");
		}
		for (float value : clip) {
			if (!Float.isFinite(value)) {
				throw new IllegalArgumentException("feature cull clip contains a non-finite value");
			}
		}
		for (float value : previousClip) {
			if (!Float.isFinite(value)) {
				throw new IllegalArgumentException("feature cull previous clip contains a non-finite value");
			}
		}
		op(FEATURE_CULL);
		u64(aabbs);
		u64(aabbsOffset);
		u32(count);
		u32(strideWords);
		u64(out);
		u64(outOffset);
		u64(depth);
		for (float value : clip) {
			f32(value);
		}
		for (float value : previousClip) {
			f32(value);
		}
	}

	void terrainExpand(long updates, long updatesOffset, int updateCount, long visible, long visibleOffset, int visibleCount, int sections,
		int cullMask, int layerMask, int options, int indicesPerUnit, int capacity, int cameraX, int cameraY, int cameraZ, float offsetX,
		float offsetY, float offsetZ, long out, long outOffset, long argsOffset) {
		op(TERRAIN_EXPAND);
		u64(updates);
		u64(updatesOffset);
		u32(updateCount);
		u64(visible);
		u64(visibleOffset);
		u32(visibleCount);
		u32(sections);
		u32(cullMask);
		u32(layerMask);
		u32(options);
		u32(indicesPerUnit);
		u32(capacity);
		u32(cameraX);
		u32(cameraY);
		u32(cameraZ);
		f32(offsetX);
		f32(offsetY);
		f32(offsetZ);
		u64(out);
		u64(outOffset);
		u64(argsOffset);
	}

	void metalFx(int mode, long color, long depth, long output, int inputWidth, int inputHeight, int outputWidth, int outputHeight,
		float jitterX, float jitterY, float[] currentInverse, float[] previousClip, boolean reset) {
		if (currentInverse.length != 16 || previousClip.length != 16) {
			throw new IllegalArgumentException("MetalFX matrices must contain 16 values");
		}
		op(METALFX);
		u32(mode);
		u64(color);
		u64(depth);
		u64(output);
		u32(inputWidth);
		u32(inputHeight);
		u32(outputWidth);
		u32(outputHeight);
		f32(jitterX);
		f32(jitterY);
		for (float value : currentInverse) {
			f32(value);
		}
		for (float value : previousClip) {
			f32(value);
		}
		flag(reset);
	}

	void metalFx(long currentColor, long previousColor, long depth, long motion, long ui, long output,
		int inputWidth, int inputHeight, int outputWidth, int outputHeight, float deltaTime, float motionScaleX,
		float motionScaleY, float nearPlane, float farPlane, float fieldOfView, float aspectRatio, float[] worldToView,
		float[] viewToClip, float jitterX, float jitterY, boolean reset, boolean uiComposited, boolean depthReversed) {
		if (worldToView.length != 16 || viewToClip.length != 16) {
			throw new IllegalArgumentException("MetalFX matrices must contain 16 values");
		}
		op(METALFX);
		u32(3);
		u32(60);
		u64(currentColor);
		u64(previousColor);
		u64(depth);
		u64(motion);
		u64(ui);
		u64(output);
		u32(inputWidth);
		u32(inputHeight);
		u32(outputWidth);
		u32(outputHeight);
		f32(deltaTime);
		f32(motionScaleX);
		f32(motionScaleY);
		f32(nearPlane);
		f32(farPlane);
		f32(fieldOfView);
		f32(aspectRatio);
		for (float value : worldToView) {
			f32(value);
		}
		for (float value : viewToClip) {
			f32(value);
		}
		f32(jitterX);
		f32(jitterY);
		flag(reset);
		flag(uiComposited);
		flag(depthReversed);
	}

	void lodCull(long nodes, long nodesOffset, long nodesLength, long meshes, long meshesOffset, long meshesLength, float[] camera, float[] frustum,
		long output, long outputOffset, long outputLength, int level, int maxLevel, float threshold) {
		if (camera.length != 3) {
			throw new IllegalArgumentException("camera must have 3 floats: " + camera.length);
		}
		if (frustum.length != 16) {
			throw new IllegalArgumentException("frustum must have 16 floats: " + frustum.length);
		}
		op(LOD_CULL);
		u32(LOD_CULL_VERSION);
		u64(nodes);
		u64(nodesOffset);
		u64(nodesLength);
		u64(meshes);
		u64(meshesOffset);
		u64(meshesLength);
		for (float value : camera) {
			f32(value);
		}
		for (float value : frustum) {
			f32(value);
		}
		u64(output);
		u64(outputOffset);
		u64(outputLength);
		u32(level);
		u32(maxLevel);
		f32(threshold);
	}

	void terrainSort(long records, long recordsOffset, long expected, long expectedOffset, int count, int items, int indices, int maxPadded,
		float cameraX, float cameraY, float cameraZ) {
		op(TERRAIN_SORT);
		u64(records);
		u64(recordsOffset);
		u64(expected);
		u64(expectedOffset);
		u32(count);
		u32(items);
		u32(indices);
		u32(maxPadded);
		f32(cameraX);
		f32(cameraY);
		f32(cameraZ);
	}

	void terrainOitResolve(int k) {
		if (k < 4 || k > 8) {
			throw new IllegalArgumentException("OIT k must be between 4 and 8");
		}
		op(TERRAIN_OIT_RESOLVE);
		u32(k);
	}

	void terrainExpandCheck(long cpu, long cpuOffset, int count, int firstSolid, int firstCutout, int firstTranslucent, long gpu, long gpuOffset,
		long gpuBytes, long argsOffset) {
		op(TERRAIN_EXPAND_CHECK);
		u64(cpu);
		u64(cpuOffset);
		u32(count);
		u32(firstSolid);
		u32(firstCutout);
		u32(firstTranslucent);
		u64(gpu);
		u64(gpuOffset);
		u64(gpuBytes);
		u64(argsOffset);
	}

	void pathTrace(long color, long depth, long bricks, long table, int width, int height, float resolutionScale, int bounces, float cameraX,
		float cameraY, float cameraZ, float[] inverseClip, float[] previousClip, int tableOriginX, int tableOriginY, int tableOriginZ, float sunDirectionX,
		float sunDirectionY, float sunDirectionZ, float sunColorR, float sunColorG, float sunColorB, float moonDirectionX, float moonDirectionY,
		float moonDirectionZ, float moonColorR, float moonColorG, float moonColorB) {
		if (inverseClip.length != 16) {
			throw new IllegalArgumentException("inverseClip must have 16 floats: " + inverseClip.length);
		}
		op(PATH_TRACE);
		u64(color);
		u64(depth);
		u64(bricks);
		u64(table);
		u32(width);
		u32(height);
		f32(resolutionScale);
		u32(bounces);
		f32(cameraX);
		f32(cameraY);
		f32(cameraZ);
		for (float value : inverseClip) {
			f32(value);
		}
		if (previousClip.length != 16) {
			throw new IllegalArgumentException("previousClip must have 16 floats: " + previousClip.length);
		}
		for (float value : previousClip) {
			f32(value);
		}
		u32(tableOriginX);
		u32(tableOriginY);
		u32(tableOriginZ);
		f32(sunDirectionX);
		f32(sunDirectionY);
		f32(sunDirectionZ);
		f32(sunColorR);
		f32(sunColorG);
		f32(sunColorB);
		f32(moonDirectionX);
		f32(moonDirectionY);
		f32(moonDirectionZ);
		f32(moonColorR);
		f32(moonColorG);
		f32(moonColorB);
	}

	void meshDispatch(int objectX, int objectY, int objectZ, int meshX, int meshY, int meshZ,
			int payloadBytes, int vertexCount, int primitiveCount, long resourceAddress, long resourceOffset,
			long resourceLength, long payloadOffset, long vertexOffset, long vertexBytes, long primitiveOffset,
			long primitiveBytes, int geometryX, int geometryY, int geometryZ) {
		if (!validMeshValue(objectX) || !validMeshValue(objectY) || !validMeshValue(objectZ)
				|| !validMeshValue(meshX) || !validMeshValue(meshY) || !validMeshValue(meshZ)
				|| !validMeshValue(geometryX) || !validMeshValue(geometryY) || !validMeshValue(geometryZ)
				|| payloadBytes <= 0 || payloadBytes > 16384 || vertexCount <= 0 || vertexCount > 256
				|| primitiveCount <= 0 || primitiveCount > 512 || resourceAddress <= 0 || resourceOffset < 0
				|| resourceLength <= 0 || payloadOffset < 0 || vertexOffset < 0 || primitiveOffset < 0
				|| vertexBytes <= 0 || primitiveBytes <= 0
				|| resourceAddress > Long.MAX_VALUE - resourceOffset
				|| resourceOffset > Long.MAX_VALUE - resourceLength
				|| !validMeshRange(payloadOffset, payloadBytes, resourceLength)
				|| !validMeshRange(vertexOffset, vertexBytes, resourceLength)
				|| !validMeshRange(primitiveOffset, primitiveBytes, resourceLength)) {
			return;
		}
		op((MESH_DISPATCH_WORDS << 16) | MESH_DISPATCH);
		u32(objectX);
		u32(objectY);
		u32(objectZ);
		u32(meshX);
		u32(meshY);
		u32(meshZ);
		u32(payloadBytes);
		u32(vertexCount);
		u32(primitiveCount);
		u64(resourceAddress);
		u64(resourceOffset);
		u64(resourceLength);
		u64(payloadOffset);
		u64(vertexOffset);
		u64(vertexBytes);
		u64(primitiveOffset);
		u64(primitiveBytes);
		u32(geometryX);
		u32(geometryY);
		u32(geometryZ);
		u32(0);
	}

	private static boolean validMeshValue(int value) {
		return value > 0 && value <= 65535;
	}

	private static boolean validMeshRange(long offset, long length, long resourceLength) {
		return offset >= 0 && length > 0 && offset <= resourceLength && length <= resourceLength - offset;
	}

	private void op(int opcode) {
		u32(opcode);
	}

	private void area(int x, int y, int width, int height) {
		u32(x);
		u32(y);
		u32(width);
		u32(height);
	}

	private void rgba(Vector4fc color) {
		f32(color == null ? 0 : color.x());
		f32(color == null ? 0 : color.y());
		f32(color == null ? 0 : color.z());
		f32(color == null ? 0 : color.w());
	}

	private void flag(boolean value) {
		u32(value ? 1 : 0);
	}

	private void f32(float value) {
		u32(Float.floatToRawIntBits(value));
	}

	private void u64(long value) {
		u32((int) value);
		u32((int) (value >>> 32));
	}

	private void u32(int value) {
		if (size == words.byteSize() / Integer.BYTES) {
			grow();
		}
		words.setAtIndex(ValueLayout.JAVA_INT, size++, value);
	}

	private void grow() {
		MemorySegment larger = Arena.global().allocate(ValueLayout.JAVA_INT, size * 2);
		MemorySegment.copy(words, 0, larger, 0, words.byteSize());
		memory = larger;
		words = MemorySegment.ofAddress(larger.address()).reinterpret(larger.byteSize());
	}
}
