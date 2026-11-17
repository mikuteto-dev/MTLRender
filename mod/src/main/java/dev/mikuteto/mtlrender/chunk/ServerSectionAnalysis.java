package dev.mikuteto.mtlrender.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

public final class ServerSectionAnalysis {
	private static final Map<IdentityKey, Result> PUBLISHED = new ConcurrentHashMap<>();
	private static final Map<IdentityKey, Boolean> POI_RESULTS = new ConcurrentHashMap<>();

	private ServerSectionAnalysis() {
	}

	public static void prepare(SerializableChunkData data) {
		List<Callable<Section>> jobs = new ArrayList<>(data.sectionData().size());
		for (SerializableChunkData.SectionData sectionData : data.sectionData()) {
			jobs.add(() -> analyze(sectionData));
		}
		List<Section> sections = ChunkLoadParallelism.shared().submitBatch(jobs).join();
		Result result = new Result(sections);
		PUBLISHED.put(new IdentityKey(data), result);
		for (Section section : sections) {
			if (section.section() != null) {
				POI_RESULTS.put(new IdentityKey(section.section()), section.mayHavePoi());
			}
		}
	}

	public static void require(SerializableChunkData data) {
		if (!PUBLISHED.containsKey(new IdentityKey(data))) {
			throw new IllegalStateException("section analysis is not published");
		}
	}

	public static boolean mayHavePoi(LevelChunkSection section, boolean fallback) {
		Boolean result = POI_RESULTS.get(new IdentityKey(section));
		return result == null ? fallback : result;
	}

	public static void finish(SerializableChunkData data) {
		IdentityKey dataKey = new IdentityKey(data);
		Result result = PUBLISHED.remove(dataKey);
		if (result != null) {
			for (Section section : result.sections()) {
				POI_RESULTS.remove(new IdentityKey(section.section()));
			}
		}
	}

	private static Section analyze(SerializableChunkData.SectionData sectionData) {
		LevelChunkSection section = sectionData.chunkSection();
		if (section == null) {
			return new Section(sectionData.y(), null, false, false, false, false, false, false);
		}
		boolean mayHavePoi = section.maybeHas(PoiTypes::hasPoi);
		return new Section(
			sectionData.y(),
			section,
			mayHavePoi,
			section.hasOnlyAir(),
			section.hasFluid(),
			section.isRandomlyTicking(),
			section.isRandomlyTickingBlocks(),
			section.isRandomlyTickingFluids()
		);
	}

	public record Result(List<Section> sections) {
		public Result {
			sections = List.copyOf(sections);
		}
	}

	public record Section(
		int y,
		LevelChunkSection section,
		boolean mayHavePoi,
		boolean onlyAir,
		boolean hasFluid,
		boolean randomlyTicking,
		boolean randomlyTickingBlocks,
		boolean randomlyTickingFluids
	) {
	}

	private static final class IdentityKey {
		private final Object value;
		private final int hash;

		private IdentityKey(Object value) {
			this.value = value;
			this.hash = System.identityHashCode(value);
		}

		@Override
		public int hashCode() {
			return hash;
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof IdentityKey other && value == other.value;
		}
	}
}
