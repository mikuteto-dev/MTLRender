package dev.mikuteto.mtlrender.pack;

import java.util.List;

public final class ExpandedSource {
	private final List<String> lines;
	private final List<SourceLocation> lineMap;

	public ExpandedSource(List<String> lines, List<SourceLocation> lineMap) {
		if (lines.size() != lineMap.size()) {
			throw new IllegalArgumentException("line map does not match source lines");
		}
		this.lines = List.copyOf(lines);
		this.lineMap = List.copyOf(lineMap);
	}

	public List<String> lines() {
		return lines;
	}

	public List<SourceLocation> lineMap() {
		return lineMap;
	}

	public SourceLocation locationOf(int lineIndex) {
		return lineMap.get(lineIndex);
	}

	public String text() {
		if (lines.isEmpty()) {
			return "";
		}
		return String.join("\n", lines) + "\n";
	}

	public record SourceLocation(String file, int line) {
		@Override
		public String toString() {
			return file + ":" + line;
		}
	}
}
