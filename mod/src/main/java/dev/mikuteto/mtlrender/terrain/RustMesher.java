package dev.mikuteto.mtlrender.terrain;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

import com.mojang.logging.LogUtils;
import dev.mikuteto.mtlrender.debug.AssetMemory;
import dev.mikuteto.mtlrender.debug.ChunkTiming;
import dev.mikuteto.mtlrender.ffi.Mtlr;
import dev.mikuteto.mtlrender.ffi.MtlrMesherSection;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

public final class RustMesher {
	public static final int CELL_SIDE = 18;
	public static final int CENTER_SIDE = 16;
	public static final int CELL_COUNT = CELL_SIDE * CELL_SIDE * CELL_SIDE;
	public static final int CENTER_COUNT = CENTER_SIDE * CENTER_SIDE * CENTER_SIDE;
	private static final int SNAPSHOT_MAGIC = 0x4d53_4832;
	private static final int SNAPSHOT_VERSION = 1;
	private static final int PACK_SNAPSHOT_VERSION = 2;
	private static final int PACK_OUTPUT_MAGIC = 0x4d50_4832;
	private static final int PACK_OUTPUT_VERSION = 1;
	private static final int PACK_RECORD_STRIDE = 16;
	private static final int TINT_COUNT = 4;
	private static final int INPUT_STRIDE = 32;
	private static final int OUTPUT_STRIDE = 28;
	private static final int MAX_BATCH_SIZE = 8;
	private static final int RESULT_HEADER_BYTES = 12;
	private static final LongAdder BATCH_CALLS = new LongAdder();
	private static final LongAdder SINGLE_CALLS = new LongAdder();
	private static final Logger LOGGER = LogUtils.getLogger();
	private static MemorySegment table = MemorySegment.NULL;
	private static final Set<Integer> exportedStates = new HashSet<>();
	private static boolean disabled;
	private static boolean unavailable;
	private static long tableGeneration;
	private static Object modelTableIdentity;
	private static long modelTableBytes;
	private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
	private static PackTerrainRequirements packRequirements = PackTerrainRequirements.none();
	private static long packGeneration;

	private RustMesher() {
	}

	public static synchronized void setPackTerrainRequirements(PackTerrainRequirements requirements) {
		PackTerrainRequirements next = requirements == null ? PackTerrainRequirements.none() : requirements;
		if (next.fingerprint() != packRequirements.fingerprint()) {
			packGeneration++;
		}
		packRequirements = next;
	}

	public static synchronized PackTerrainRequirements packRequirements() {
		return packRequirements;
	}

	public static synchronized long packGeneration() {
		return packGeneration;
	}

	public static synchronized boolean enabled() {
		return !disabled && !unavailable && dev.mikuteto.mtlrender.config.Config.get().terrain.rustMesher;
	}

	public static synchronized long tableGeneration() {
		return tableGeneration;
	}

	public static synchronized Object modelTableIdentity() {
		return modelTableIdentity;
	}

	public static synchronized void disable() {
		disabled = true;
	}

	public static synchronized boolean supports(int stateId) {
		return enabled() && !table.equals(MemorySegment.NULL) && exportedStates.contains(stateId);
	}

	public static boolean eligible(SectionPos sectionPos, RenderSectionRegion region) {
		if (!enabled()) {
			return false;
		}
		BlockPos origin = sectionPos.origin();
		for (int y = 0; y < CENTER_SIDE; y++) {
			for (int z = 0; z < CENTER_SIDE; z++) {
				for (int x = 0; x < CENTER_SIDE; x++) {
					BlockPos pos = origin.offset(x, y, z);
					if (!eligibleState(region.getBlockState(pos), region, pos)) {
						return false;
					}
				}
			}
		}
		return true;
	}

	public static synchronized void beginExport() {
		tableGeneration++;
		modelTableIdentity = null;
		modelTableBytes = 0L;
		AssetMemory.global().rustModelTable(0L);
		if (!enabled()) {
			return;
		}
		if (!table.equals(MemorySegment.NULL)) {
			Mtlr.mtlr_mesher_table_destroy(table);
		}
		exportedStates.clear();
		try {
			table = Mtlr.mtlr_mesher_table_create();
			modelTableIdentity = table;
		} catch (LinkageError error) {
			unavailable = true;
			table = MemorySegment.NULL;
			modelTableIdentity = null;
		}
	}

	public static synchronized void exportState(int stateId, int flags, ByteBuffer bytes) {
		if (!enabled() || table.equals(MemorySegment.NULL) || !bytes.isDirect() || bytes.position() != 0) {
			return;
		}
		try {
			if (Mtlr.mtlr_mesher_table_add_state(
				table,
				stateId,
				flags,
				MemorySegment.ofBuffer(bytes).address(),
				bytes.limit()
			)) {
				exportedStates.add(stateId);
				modelTableBytes += bytes.limit();
			}
		} catch (LinkageError error) {
			unavailable = true;
		}
	}

	public static synchronized void endExport() {
		AssetMemory.global().rustModelTable(modelTableBytes);
		if (!table.equals(MemorySegment.NULL)) {
			try {
				LOGGER.info(
					"Rust mesher table exported: {} states, {} quads",
					Mtlr.mtlr_mesher_table_state_count(table),
					Mtlr.mtlr_mesher_table_quad_count(table)
				);
			} catch (LinkageError error) {
				unavailable = true;
			}
		}
	}

	public static synchronized ByteBuffer meshSection(
		SectionPos sectionPos,
		RenderSectionRegion region,
		boolean ambientOcclusion,
		boolean cutoutLeaves,
		BlockColors blockColors
	) {
		if (!enabled() || table.equals(MemorySegment.NULL)) {
			if (ChunkTiming.enabled()) {
				ChunkTiming.recordMesherFallback();
				ChunkTiming.recordSectionDuration(ChunkTiming.Stage.RUST_COMPILE, sectionPos.asLong(), 0L);
				ChunkTiming.recordSectionDuration(ChunkTiming.Stage.RUST_MESHER, sectionPos.asLong(), 0L);
			}
			return null;
		}
		ByteBuffer input = snapshot(sectionPos, region, ambientOcclusion, cutoutLeaves, blockColors);
		long capacity = maxOutput();
		if (packRequirements.needsExtraData()) {
			capacity = capacity > Long.MAX_VALUE - (long) CENTER_COUNT * PACK_RECORD_STRIDE
				? Long.MAX_VALUE : capacity + (long) CENTER_COUNT * PACK_RECORD_STRIDE;
		}
		if (capacity < RESULT_HEADER_BYTES || capacity > Integer.MAX_VALUE) {
			if (ChunkTiming.enabled()) {
				ChunkTiming.recordMesherFallback();
				ChunkTiming.recordSectionDuration(ChunkTiming.Stage.RUST_COMPILE, sectionPos.asLong(), 0L);
				ChunkTiming.recordSectionDuration(ChunkTiming.Stage.RUST_MESHER, sectionPos.asLong(), 0L);
			}
			return null;
		}
		ByteBuffer output = ByteBuffer.allocateDirect((int) capacity).order(ByteOrder.LITTLE_ENDIAN);
		long nativeStart = ChunkTiming.enabled() ? System.nanoTime() : 0L;
		long written = 0L;
		try {
			written = meshSingle(input, output);
		} finally {
			if (nativeStart != 0L) {
				long nativeDuration = System.nanoTime() - nativeStart;
				ChunkTiming.recordSectionDuration(ChunkTiming.Stage.RUST_COMPILE, sectionPos.asLong(), nativeDuration);
				ChunkTiming.recordSectionDuration(ChunkTiming.Stage.RUST_MESHER, sectionPos.asLong(), nativeDuration);
			}
		}
		if (!validWritten(written, output.capacity())) {
			if (ChunkTiming.enabled()) {
				ChunkTiming.recordMesherFallback();
				ChunkTiming.recordMesherFailedItem();
			}
			return null;
		}
		output.limit((int) written);
		output.position(0);
		return output;
	}

	public static PreparedRequest prepare(
		SectionPos sectionPos,
		RenderSectionRegion region,
		boolean ambientOcclusion,
		boolean cutoutLeaves,
		BlockColors blockColors
	) {
		if (!enabled() || table.equals(MemorySegment.NULL)) {
			return null;
		}
		long outputSize = maxOutput();
		if (packRequirements.needsExtraData()) {
			outputSize = outputSize > Long.MAX_VALUE - (long) CENTER_COUNT * PACK_RECORD_STRIDE
				? Long.MAX_VALUE : outputSize + (long) CENTER_COUNT * PACK_RECORD_STRIDE;
		}
		if (outputSize < RESULT_HEADER_BYTES || outputSize > Integer.MAX_VALUE) {
			return null;
		}
		Eligibility eligibility = new Eligibility();
		Scratch scratch = SCRATCH.get();
		ByteBuffer ownedInput = scratch.takeInput(16 + 24 + CELL_COUNT * INPUT_STRIDE + CENTER_COUNT * 24);
		snapshot(sectionPos, region, ambientOcclusion, cutoutLeaves, blockColors, eligibility, ownedInput);
		ByteBuffer output = scratch.takeOutput((int) outputSize);
		return new PreparedRequest(
			ownedInput,
			output,
			sectionPos,
			eligibility.value,
			tableGeneration,
			ambientOcclusion,
			cutoutLeaves,
			blockColors,
			modelTableIdentity,
			scratch
		);
	}

	public static synchronized BatchResult[] meshSections(PreparedRequest[] requests) {
		if (requests == null) {
			throw new IllegalArgumentException("batch requests must not be null");
		}
		BatchResult[] results = new BatchResult[requests.length];
		int offset = 0;
		while (offset < requests.length) {
			PreparedRequest first = requests[offset];
			if (first == null || !first.eligible || !first.compatible()) {
				if (ChunkTiming.enabled()) {
					ChunkTiming.recordMesherFailedItem();
				}
				results[offset] = BatchResult.failure();
				offset++;
				continue;
			}
			int count = 1;
			while (offset + count < requests.length && count < SectionBatch.MAX_ITEMS
				&& requests[offset + count] != null && requests[offset + count].eligible
				&& requests[offset + count].compatible(first)) {
				count++;
			}
			ByteBuffer[] inputs = new ByteBuffer[count];
			ByteBuffer[] outputs = new ByteBuffer[count];
			long[] sectionKeys = new long[count];
			long[] written = new long[count];
			for (int index = 0; index < count; index++) {
				PreparedRequest request = requests[offset + index];
				inputs[index] = request.input;
				outputs[index] = request.output;
				sectionKeys[index] = request.sectionPos.asLong();
			}
			meshSectionsInternal(inputs, outputs, written, sectionKeys);
			for (int index = 0; index < count; index++) {
				PreparedRequest request = requests[offset + index];
				long value = validMesh(outputs[index], written[index]) ? written[index] : 0L;
				setLimit(outputs[index], value);
				if (value == 0L && ChunkTiming.enabled()) {
					ChunkTiming.recordMesherFailedItem();
				}
				results[offset + index] = value == 0L ? BatchResult.failure() : BatchResult.success(outputs[index], value);
			}
			offset += count;
		}
		return results;
	}

	public static long batchCalls() {
		return BATCH_CALLS.sum();
	}

	public static long singleCalls() {
		return SINGLE_CALLS.sum();
	}

	public static synchronized int meshSections(ByteBuffer[] inputs, ByteBuffer[] outputs, long[] written) {
		return meshSectionsInternal(inputs, outputs, written, null);
	}

	private static int meshSectionsInternal(ByteBuffer[] inputs, ByteBuffer[] outputs, long[] written, long[] sectionKeys) {
		if (inputs == null || outputs == null || written == null
			|| inputs.length != outputs.length || inputs.length != written.length) {
			throw new IllegalArgumentException("batch arrays must have equal lengths");
		}
		if (!enabled() || table.equals(MemorySegment.NULL)) {
			java.util.Arrays.fill(written, 0L);
			return 0;
		}
		int completed = 0;
		for (int offset = 0; offset < inputs.length; offset += MAX_BATCH_SIZE) {
			int count = Math.min(MAX_BATCH_SIZE, inputs.length - offset);
			long[] keys = sectionKeys == null || !ChunkTiming.enabled()
				? null : java.util.Arrays.copyOfRange(sectionKeys, offset, offset + count);
			if (!meshBatch(inputs, outputs, written, offset, count, keys)) {
				for (int index = offset; index < offset + count; index++) {
					if (ChunkTiming.enabled()) {
						ChunkTiming.recordMesherFallback();
					}
					long key = sectionKeys == null ? 0L : sectionKeys[index];
					written[index] = meshSingleChecked(inputs[index], outputs[index], key);
				}
			}
			completed += count;
		}
		return completed;
	}

	private static long maxOutput() {
		try {
			return Mtlr.mtlr_mesher_table_max_output(table);
		} catch (LinkageError error) {
			unavailable = true;
			return 0L;
		}
	}

	private static boolean meshBatch(
		ByteBuffer[] inputs, ByteBuffer[] outputs, long[] written, int offset, int count, long[] sectionKeys
	) {
		long timingStart = ChunkTiming.enabled() ? System.nanoTime() : 0L;
		long nativeDuration = 0L;
		try (Arena arena = Arena.ofConfined()) {
			long size = MtlrMesherSection.layout().byteSize();
			MemorySegment descriptors = arena.allocate(size * count, MtlrMesherSection.layout().byteAlignment());
			MemorySegment results = arena.allocate(ValueLayout.JAVA_LONG, count);
			for (int item = 0; item < count; item++) {
				ByteBuffer input = inputs[offset + item];
				ByteBuffer output = outputs[offset + item];
				MemorySegment descriptor = descriptors.asSlice(size * item, size);
				MtlrMesherSection.input(descriptor, address(input));
				MtlrMesherSection.input_length(descriptor, length(input));
				MtlrMesherSection.output(descriptor, address(output));
				MtlrMesherSection.output_capacity(descriptor, capacity(output));
			}
			BATCH_CALLS.increment();
			long nativeStart = ChunkTiming.enabled() ? System.nanoTime() : 0L;
			if (!Mtlr.mtlr_mesher_mesh_sections(table, descriptors, count, results)) {
				if (nativeStart != 0L) {
					nativeDuration = System.nanoTime() - nativeStart;
				}
				return false;
			}
			if (nativeStart != 0L) {
				nativeDuration = System.nanoTime() - nativeStart;
			}
			for (int item = 0; item < count; item++) {
				long value = results.getAtIndex(ValueLayout.JAVA_LONG, item);
				written[offset + item] = validWritten(value, capacity(outputs[offset + item])) ? value : 0L;
				setLimit(outputs[offset + item], written[offset + item]);
			}
			return true;
		} catch (LinkageError error) {
			unavailable = true;
			return false;
		} finally {
			if (timingStart != 0L) {
				ChunkTiming.recordMesherBatch(count, System.nanoTime() - timingStart, nativeDuration, sectionKeys);
			}
		}
	}

	private static long meshSingleChecked(ByteBuffer input, ByteBuffer output, long sectionKey) {
		long value = 0L;
		if (enabled() && !table.equals(MemorySegment.NULL) && direct(input) && direct(output)) {
			long nativeStart = ChunkTiming.enabled() ? System.nanoTime() : 0L;
			value = meshSingle(input, output);
			if (nativeStart != 0L) {
				long duration = System.nanoTime() - nativeStart;
				ChunkTiming.recordSectionDuration(ChunkTiming.Stage.RUST_COMPILE, sectionKey, duration);
				ChunkTiming.recordSectionDuration(ChunkTiming.Stage.RUST_MESHER, sectionKey, duration);
			}
			if (!validWritten(value, capacity(output))) {
				value = 0L;
			}
		}
		setLimit(output, value);
		if (value == 0L && ChunkTiming.enabled()) {
			ChunkTiming.recordMesherFailedItem();
		}
		return value;
	}

	private static long meshSingle(ByteBuffer input, ByteBuffer output) {
		try {
			SINGLE_CALLS.increment();
			return Mtlr.mtlr_mesher_mesh_section(
				table, address(input), length(input), address(output), capacity(output)
			);
		} catch (LinkageError error) {
			unavailable = true;
			return 0L;
		}
	}

	private static boolean direct(ByteBuffer buffer) {
		return buffer != null && buffer.isDirect() && buffer.position() == 0;
	}

	private static long address(ByteBuffer buffer) {
		return direct(buffer) ? MemorySegment.ofBuffer(buffer).address() : 0L;
	}

	private static long length(ByteBuffer buffer) {
		return direct(buffer) ? buffer.limit() : 0L;
	}

	private static long capacity(ByteBuffer buffer) {
		return direct(buffer) ? buffer.capacity() : 0L;
	}

	private static boolean validWritten(long value, long capacity) {
		return value >= RESULT_HEADER_BYTES && value <= capacity && value <= Integer.MAX_VALUE;
	}

	private static void setLimit(ByteBuffer buffer, long written) {
		if (buffer != null && buffer.isDirect() && written <= Integer.MAX_VALUE) {
			buffer.limit((int) written);
			buffer.position(0);
		}
	}

	private static ByteBuffer snapshot(
		SectionPos sectionPos,
		RenderSectionRegion region,
		boolean ambientOcclusion,
		boolean cutoutLeaves,
		BlockColors blockColors
	) {
		ByteBuffer data = ByteBuffer.allocateDirect(16 + 24 + CELL_COUNT * INPUT_STRIDE + CENTER_COUNT * 24)
			.order(ByteOrder.LITTLE_ENDIAN);
		snapshot(sectionPos, region, ambientOcclusion, cutoutLeaves, blockColors, null, data);
		return data;
	}

	private static void snapshot(
		SectionPos sectionPos,
		RenderSectionRegion region,
		boolean ambientOcclusion,
		boolean cutoutLeaves,
		BlockColors blockColors,
		Eligibility eligibility,
		ByteBuffer data
	) {
		data.clear().order(ByteOrder.LITTLE_ENDIAN);
		int metadataMask = packRequirements.needsExtraData() ? packRequirements.sidecarMask() : 0;
		data.putInt(SNAPSHOT_MAGIC).putInt(metadataMask == 0 ? SNAPSHOT_VERSION : PACK_SNAPSHOT_VERSION);
		int flags = ambientOcclusion ? 1 : 0;
		if (cutoutLeaves) {
			flags |= 1 << 1;
		}
		data.putInt(flags).putInt(metadataMask);
		CardinalLighting lighting = region.cardinalLighting();
		data.putFloat(lighting.down()).putFloat(lighting.up()).putFloat(lighting.north());
		data.putFloat(lighting.south()).putFloat(lighting.west()).putFloat(lighting.east());
		BlockPos origin = sectionPos.origin();
		for (int y = -1; y <= 16; y++) {
			for (int z = -1; z <= 16; z++) {
				for (int x = -1; x <= 16; x++) {
					BlockPos pos = origin.offset(x, y, z);
					BlockState state = region.getBlockState(pos);
					int stateId = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(state);
					data.putInt(Math.max(stateId, 0));
					data.putInt(net.minecraft.util.LightCoordsUtil.getLightCoords(region, pos));
					data.putFloat(state.getShadeBrightness(region, pos));
					for (int tint = 0; tint < TINT_COUNT; tint++) {
						var source = blockColors.getTintSource(state, tint);
						data.putInt(source == null ? -1 : source.colorInWorld(state, region, pos));
					}
					int skipMask = 0;
					for (Direction direction : Direction.values()) {
						BlockState neighbor = region.getBlockState(pos.relative(direction));
						if (state.skipRendering(neighbor, direction)) {
							skipMask |= 1 << direction.ordinal();
						}
					}
					data.putInt(skipMask);
				}
			}
		}
		for (int y = 0; y < CENTER_SIDE; y++) {
			for (int z = 0; z < CENTER_SIDE; z++) {
				for (int x = 0; x < CENTER_SIDE; x++) {
					BlockPos pos = origin.offset(x, y, z);
					BlockState state = region.getBlockState(pos);
					if (eligibility != null && !eligibleState(state, region, pos)) {
						eligibility.value = false;
					}
					data.putLong(state.getSeed(pos));
					var offset = state.getOffset(pos);
					data.putFloat((float) offset.x).putFloat((float) offset.y).putFloat((float) offset.z);
				}
			}
		}
	}

	private static boolean eligibleState(BlockState state, RenderSectionRegion region, BlockPos pos) {
		return state.getFluidState().isEmpty()
			&& !state.hasBlockEntity()
			&& state.getRenderShape() == RenderShape.MODEL
			&& supports(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(state));
	}

	private static boolean validMesh(ByteBuffer output, long written) {
		if (!validWritten(written, capacity(output))) return false;
		ByteBuffer header = output.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		header.limit((int) written).position(0);
		int first = header.getInt();
		int headerBytes = RESULT_HEADER_BYTES;
		long total = 0L;
		if (first == PACK_OUTPUT_MAGIC) {
			if (written < 36L || header.getInt() != PACK_OUTPUT_VERSION
				|| !PackTerrainLayout.isValidFieldMask(header.getInt())) return false;
			headerBytes = 36;
			for (int index = 0; index < 3; index++) {
				int size = header.getInt(12 + index * 4);
				int metadata = header.getInt(24 + index * 4);
				if (size < 0 || size % OUTPUT_STRIDE != 0 || metadata < 0 || metadata % PACK_RECORD_STRIDE != 0
					|| metadata / PACK_RECORD_STRIDE != size / OUTPUT_STRIDE) return false;
				total += size + (long) metadata;
			}
		} else {
			for (int index = 0; index < 3; index++) {
				int size = index == 0 ? first : header.getInt();
				if (size < 0 || size % OUTPUT_STRIDE != 0) return false;
				total += size;
			}
		}
		return total <= written - headerBytes;
	}

	private static final class Eligibility {
		private boolean value = true;
	}

	public static final class PreparedRequest implements AutoCloseable {
		private ByteBuffer input;
		private ByteBuffer output;
		private final SectionPos sectionPos;
		private final boolean eligible;
		private final long generation;
		private final boolean ambientOcclusion;
		private final boolean cutoutLeaves;
		private final Object colors;
		private final Object modelIdentity;
		private final Scratch scratch;
		private boolean closed;

		private PreparedRequest(ByteBuffer input, ByteBuffer output, SectionPos sectionPos, boolean eligible,
			long generation, boolean ambientOcclusion, boolean cutoutLeaves, Object colors, Object modelIdentity, Scratch scratch) {
			this.input = input;
			this.output = output;
			this.sectionPos = sectionPos;
			this.eligible = eligible;
			this.generation = generation;
			this.ambientOcclusion = ambientOcclusion;
			this.cutoutLeaves = cutoutLeaves;
			this.colors = colors;
			this.modelIdentity = modelIdentity;
			this.scratch = scratch;
		}

		public boolean eligible() { return eligible; }
		public SectionPos sectionPos() { return sectionPos; }
		public ByteBuffer output() { return output; }
		private boolean compatible() { return generation == tableGeneration && modelIdentity == modelTableIdentity; }
		private boolean compatible(PreparedRequest other) {
			return generation == other.generation && ambientOcclusion == other.ambientOcclusion
				&& cutoutLeaves == other.cutoutLeaves && colors == other.colors
				&& modelIdentity == other.modelIdentity && compatible();
		}

		@Override
		public void close() {
			if (!closed) {
				closed = true;
				ByteBuffer input = this.input;
				ByteBuffer output = this.output;
				this.input = null;
				this.output = null;
				if (input != null && output != null) {
					scratch.release(input, output);
				}
			}
		}
	}

	public static final class BatchResult {
		private final ByteBuffer output;
		private final long written;
		private BatchResult(ByteBuffer output, long written) { this.output = output; this.written = written; }
		private static BatchResult success(ByteBuffer output, long written) { return new BatchResult(output, written); }
		private static BatchResult failure() { return new BatchResult(null, 0L); }
		public boolean success() { return output != null; }
		public ByteBuffer output() { return output; }
		public long written() { return written; }
	}

	private static final class Scratch {
		private final java.util.ArrayDeque<ByteBuffer> inputs = new java.util.ArrayDeque<>();
		private final java.util.ArrayDeque<ByteBuffer> outputs = new java.util.ArrayDeque<>();
		private ByteBuffer takeInput(int capacity) { return take(inputs, capacity); }
		private ByteBuffer takeOutput(int capacity) { return take(outputs, capacity); }
		private static ByteBuffer take(java.util.ArrayDeque<ByteBuffer> pool, int capacity) {
			ByteBuffer value = pool.pollFirst();
			if (value == null || value.capacity() < capacity) {
				return ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN);
			}
			value.clear().order(ByteOrder.LITTLE_ENDIAN);
			return value;
		}
		private void release(ByteBuffer input, ByteBuffer output) { inputs.addFirst(input); outputs.addFirst(output); }
	}
}
