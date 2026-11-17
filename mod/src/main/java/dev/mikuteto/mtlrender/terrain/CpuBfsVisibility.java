package dev.mikuteto.mtlrender.terrain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.ToIntFunction;

public final class CpuBfsVisibility {
	private CpuBfsVisibility() {
	}

	public static <T> List<T> snapshot(List<T> vanillaVisibleSections) {
		if (vanillaVisibleSections.isEmpty()) {
			return List.of();
		}
		return Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(vanillaVisibleSections)));
	}

	public static <T> T at(List<T> sections, int position, boolean reverse) {
		int index = reverse ? sections.size() - 1 - position : position;
		return sections.get(index);
	}

	public static <T> int sectionCount(List<T> sections, ToIntFunction<T> indexer) {
		int highest = -1;
		for (T section : sections) {
			int index = indexer.applyAsInt(section);
			if (index > highest) {
				highest = index;
			}
		}
		return highest + 1;
	}
}
