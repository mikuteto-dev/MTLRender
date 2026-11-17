package dev.mikuteto.mtlrender.debug;

import java.util.List;
import java.util.OptionalLong;

@FunctionalInterface
public interface MemorySource {
	Values sample();

	default List<String> diagnosticLines() {
		return List.of();
	}

	record Values(
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

		private static OptionalLong value(OptionalLong value) {
			return value == null ? OptionalLong.empty() : value;
		}
	}
}
