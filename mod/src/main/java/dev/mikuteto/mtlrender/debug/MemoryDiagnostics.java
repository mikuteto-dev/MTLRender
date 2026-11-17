package dev.mikuteto.mtlrender.debug;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import dev.mikuteto.mtlrender.config.Config;

public final class MemoryDiagnostics {
	private static final int MAX_SOURCES = 64;
	private static final long DEFAULT_INTERVAL_NANOS = 1_000_000_000L;
	private static final MemoryDiagnostics GLOBAL = new MemoryDiagnostics(
		MemoryAccount.global(),
		() -> Config.get().debugMemory,
		Long.getLong("mtlrender.debug.memory.intervalNanos", DEFAULT_INTERVAL_NANOS)
	);

	private final MemoryAccount account;
	private final BooleanSupplier enabled;
	private final long intervalNanos;
	private final Object lock = new Object();
	private final Map<String, Registered> sources = new TreeMap<>();
	private final Map<String, LongSupplier> pools = new TreeMap<>();
	private long lastSnapshotNanos = Long.MIN_VALUE;
	private MemoryReport lastReport;

	public MemoryDiagnostics() {
		this(MemoryAccount.global(), () -> Config.get().debugMemory, DEFAULT_INTERVAL_NANOS);
	}

	public MemoryDiagnostics(MemoryAccount account, BooleanSupplier enabled, long intervalNanos) {
		this.account = Objects.requireNonNull(account, "account");
		this.enabled = Objects.requireNonNull(enabled, "enabled");
		if (intervalNanos < 0) {
			throw new IllegalArgumentException("interval must be non-negative");
		}
		this.intervalNanos = intervalNanos;
	}

	public static MemoryDiagnostics global() {
		return GLOBAL;
	}

	public boolean enabled() {
		return enabled.getAsBoolean();
	}

	public Registration register(String id, MemorySource source) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(source, "source");
		if (id.isBlank()) {
			throw new IllegalArgumentException("source id must not be blank");
		}
		synchronized (lock) {
			if (!sources.containsKey(id) && sources.size() >= MAX_SOURCES) {
				throw new IllegalStateException("too many memory sources");
			}
			Registered registered = new Registered(id, source);
			sources.put(id, registered);
			invalidate();
			return new Registration(this, registered);
		}
	}

	public void registerPool(String id, LongSupplier bytes) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(bytes, "bytes");
		if (id.isBlank()) {
			throw new IllegalArgumentException("pool id must not be blank");
		}
		synchronized (lock) {
			pools.put(id, bytes);
			invalidate();
		}
	}

	public MemoryReport snapshot() {
		if (!enabled()) {
			return null;
		}
		long now = System.nanoTime();
		synchronized (lock) {
			if (lastReport != null && lastSnapshotNanos != Long.MIN_VALUE && now >= lastSnapshotNanos && now - lastSnapshotNanos < intervalNanos) {
				return lastReport;
			}
			MemoryAccount.Snapshot tracked = account.snapshot();
			List<MemoryReport.Source> results = new ArrayList<>(sources.size());
			for (Registered registered : sources.values()) {
				MemorySource.Values values;
				boolean failed = false;
				try {
					values = registered.source.sample();
					if (values == null) {
						values = MemorySource.Values.unavailable();
						failed = true;
					}
				} catch (Throwable failure) {
					values = MemorySource.Values.unavailable();
					failed = true;
				}
				results.add(new MemoryReport.Source(registered.id, values(values), failed));
			}
			MemoryReport report = new MemoryReport(
				jvmHeap(),
				new MemoryReport.TrackedJava(tracked.bytes(), tracked.count(), tracked.entries()),
				sum(results, source -> source.values().nativeAllocationBytes()),
				first(results, source -> source.values().currentAllocatedSize()),
				first(results, source -> source.values().recommendedMaxWorkingSetSize()),
				first(results, source -> source.values().physFootprint()),
				results
			);
			lastSnapshotNanos = now;
			lastReport = report;
			return report;
		}
	}

	public List<String> diagnosticLines() {
		if (!enabled()) {
			return List.of();
		}
		MemoryReport report = snapshot();
		return report == null ? List.of() : lines(report);
	}

	public String text() {
		if (!enabled()) {
			return "";
		}
		MemoryReport report = snapshot();
		return report == null ? "" : String.join("\n", lines(report)) + "\n";
	}

	private static MemoryReport.JvmHeap jvmHeap() {
		try {
			return MemoryReport.JvmHeap.from(ManagementFactory.getMemoryMXBean());
		} catch (RuntimeException failure) {
			return new MemoryReport.JvmHeap(OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty());
		}
	}

	private static MemoryReport.Values values(MemorySource.Values values) {
		return new MemoryReport.Values(
			values.nativeAllocationBytes(),
			values.currentAllocatedSize(),
			values.recommendedMaxWorkingSetSize(),
			values.physFootprint()
		);
	}

	private static OptionalLong sum(List<MemoryReport.Source> sources, java.util.function.Function<MemoryReport.Source, OptionalLong> value) {
		long total = 0;
		boolean available = false;
		for (MemoryReport.Source source : sources) {
			OptionalLong current = value.apply(source);
			if (current.isPresent()) {
				if (Long.MAX_VALUE - total < current.getAsLong()) {
					return OptionalLong.empty();
				}
				total += current.getAsLong();
				available = true;
			}
		}
		return available ? OptionalLong.of(total) : OptionalLong.empty();
	}

	private static OptionalLong first(List<MemoryReport.Source> sources, java.util.function.Function<MemoryReport.Source, OptionalLong> value) {
		for (MemoryReport.Source source : sources) {
			OptionalLong current = value.apply(source);
			if (current.isPresent()) {
				return current;
			}
		}
		return OptionalLong.empty();
	}

	private List<String> lines(MemoryReport report) {
		List<String> lines = new ArrayList<>(12 + report.trackedJava().subsystems().size() + report.sources().size() + 2);
		MemoryReport.JvmHeap heap = report.jvmHeap();
		lines.add("jvm_heap.init_bytes: " + value(heap.initBytes()));
		lines.add("jvm_heap.used_bytes: " + value(heap.usedBytes()));
		lines.add("jvm_heap.committed_bytes: " + value(heap.committedBytes()));
		lines.add("jvm_heap.max_bytes: " + value(heap.maxBytes()));
		lines.add("tracked_java.bytes: " + report.trackedJava().bytes());
		lines.add("tracked_java.count: " + report.trackedJava().count());
		for (MemoryAccount.Entry entry : report.trackedJava().subsystems()) {
			lines.add("tracked_java." + entry.subsystem().id() + ".bytes: " + entry.bytes());
			lines.add("tracked_java." + entry.subsystem().id() + ".count: " + entry.count());
		}
		lines.add("native.native_allocation_bytes: " + value(report.nativeAllocationBytes()));
		lines.add("native.currentAllocatedSize: " + value(report.currentAllocatedSize()));
		lines.add("native.recommendedMaxWorkingSetSize: " + value(report.recommendedMaxWorkingSetSize()));
		lines.add("native.phys_footprint: " + value(report.physFootprint()));
		for (MemoryReport.Source source : report.sources()) {
			if (source.failed()) {
				lines.add("memory_source." + source.id() + ": unavailable");
			}
			Registered registered = registered(source.id());
			if (registered != null) {
				lines.addAll(registered.source.diagnosticLines());
			}
		}
		synchronized (lock) {
			for (Map.Entry<String, LongSupplier> pool : pools.entrySet()) {
				long bytes;
				try {
					bytes = Math.max(0, pool.getValue().getAsLong());
				} catch (RuntimeException failure) {
					bytes = 0;
				}
				lines.add("gpu_pool." + pool.getKey() + ".bytes: " + bytes);
			}
		}
		AssetMemory.Snapshot assets = AssetMemory.global().snapshot();
		lines.add("assets.atlas_gpu_bytes: " + assets.atlasGpuBytes());
		lines.add("assets.cpu_image_bytes: " + assets.cpuImageBytes());
		lines.add("assets.cpu_mipmap_bytes: " + assets.cpuMipmapBytes());
		lines.add("assets.sprite_count: " + assets.spriteCount());
		lines.add("assets.animated_sprite_count: " + assets.animatedSpriteCount());
		lines.add("assets.baked_item_map_entries: " + assets.bakedItemMapEntries());
		lines.add("assets.baked_block_map_entries: " + assets.bakedBlockMapEntries());
		lines.add("assets.baked_fluid_map_entries: " + assets.bakedFluidMapEntries());
		lines.add("assets.entity_model_layers: " + assets.entityModelLayers());
		lines.add("assets.rust_model_table_bytes: " + assets.rustModelTableBytes());
		AudioMemory.Snapshot audio = AudioMemory.global().snapshot();
		lines.add("java.audio.cached_sound_count: " + audio.cachedSoundCount());
		lines.add("java.audio.decoded_direct_bytes: " + audio.decodedDirectBytes());
		lines.add("java.audio.al_buffer_count: " + audio.alBufferCount());
		lines.add("java.audio.al_buffer_bytes: " + audio.alBufferBytes());
		return List.copyOf(lines);
	}

	private static String value(OptionalLong value) {
		return value.isPresent() ? Long.toString(value.getAsLong()) : "unavailable";
	}

	private Registered registered(String id) {
		synchronized (lock) {
			Registered value = sources.get(id);
			return value;
		}
	}

	private void invalidate() {
		lastSnapshotNanos = Long.MIN_VALUE;
		lastReport = null;
	}

	public static final class Registration implements AutoCloseable {
		private final MemoryDiagnostics owner;
		private final Registered registered;
		private boolean closed;

		private Registration(MemoryDiagnostics owner, Registered registered) {
			this.owner = owner;
			this.registered = registered;
		}

		@Override
		public void close() {
			synchronized (owner.lock) {
				if (closed) {
					return;
				}
				closed = true;
				if (owner.sources.get(registered.id) == registered) {
					owner.sources.remove(registered.id);
					owner.invalidate();
				}
			}
		}
	}

	private record Registered(String id, MemorySource source) {
	}
}
