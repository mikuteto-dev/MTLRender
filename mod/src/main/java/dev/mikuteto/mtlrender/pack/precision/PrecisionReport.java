package dev.mikuteto.mtlrender.pack.precision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record PrecisionReport(List<PrecisionDecision> decisions) {
	public PrecisionReport {
		List<PrecisionDecision> ordered = new ArrayList<>(decisions);
		ordered.sort(Comparator.comparing(PrecisionDecision::bufferId));
		decisions = List.copyOf(ordered);
	}
}
