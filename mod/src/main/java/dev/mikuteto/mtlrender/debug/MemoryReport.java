package dev.mikuteto.mtlrender.debug;

import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.OptionalLong;
import java.util.Objects;

public record MemoryReport(
	JvmHeap jvmHeap,
	TrackedJava trackedJava,
	OptionalLong nativeAllocationBytes,
	OptionalLong currentAllocatedSize,
	OptionalLong recommendedMaxWorkingSetSize,
	OptionalLong physFootprint,
	List<Source> sources
) {
	public MemoryReport {
		jvmHeap = Objects.requireNonNull(jvmHeap, "jvmHeap");
		trackedJava = Objects.requireNonNull(trackedJava, "trackedJava");
		nativeAllocationBytes = value(nativeAllocationBytes);
		currentAllocatedSize = value(currentAllocatedSize);
		recommendedMaxWorkingSetSize = value(recommendedMaxWorkingSetSize);
		physFootprint = value(physFootprint);
		sources = List.copyOf(sources);
	}

	public OptionalLong phys_footprint() {
		return physFootprint;
	}

	public record JvmHeap(OptionalLong initBytes, OptionalLong usedBytes, OptionalLong committedBytes, OptionalLong maxBytes) {
		public JvmHeap {
			initBytes = value(initBytes);
			usedBytes = value(usedBytes);
			committedBytes = value(committedBytes);
			maxBytes = value(maxBytes);
		}

		public static JvmHeap from(MemoryMXBean bean) {
			MemoryUsage usage = bean.getHeapMemoryUsage();
			return new JvmHeap(
				memoryValue(usage.getInit()),
				memoryValue(usage.getUsed()),
				memoryValue(usage.getCommitted()),
				memoryValue(usage.getMax())
			);
		}
	}

	public record TrackedJava(long bytes, long count, List<MemoryAccount.Entry> subsystems) {
		public TrackedJava {
			if (bytes < 0 || count < 0) {
				throw new IllegalArgumentException("memory values must be non-negative");
			}
			subsystems = List.copyOf(subsystems);
		}
	}

	public record Source(String id, Values values, boolean failed) {
		public Source {
			id = Objects.requireNonNull(id, "id");
			values = Objects.requireNonNull(values, "values");
		}
	}

	public record Values(
		OptionalLong nativeAllocationBytes,
		OptionalLong currentAllocatedSize,
		OptionalLong recommendedMaxWorkingSetSize,
		OptionalLong physFootprint
	) {
		public Values {
			nativeAllocationBytes = value(nativeAllocationBytes);
			currentAllocatedSize = value(currentAllocatedSize);
			recommendedMaxWorkingSetSize = value(recommendedMaxWorkingSetSize);
			physFootprint = value(physFootprint);
		}

		public static Values unavailable() {
			return new Values(OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty());
		}
	}

	private static OptionalLong value(OptionalLong value) {
		return value == null ? OptionalLong.empty() : value;
	}

	private static OptionalLong memoryValue(long value) {
		return value < 0 ? OptionalLong.empty() : OptionalLong.of(value);
	}
}
