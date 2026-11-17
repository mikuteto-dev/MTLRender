package dev.mikuteto.mtlrender.pack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class Includes {
	private final Map<String, FileNode> nodes;

	private Includes(Map<String, FileNode> nodes) {
		this.nodes = Map.copyOf(nodes);
	}

	public static Includes build(PackSource source, Collection<String> roots) throws IncludeException {
		Map<String, FileNode> nodes = new LinkedHashMap<>();
		for (String root : roots) {
			String path = PackSource.normalize(root);
			load(source, path, nodes, new LinkedHashSet<>(), null, 0);
		}
		return new Includes(nodes);
	}

	private static void load(PackSource source, String path, Map<String, FileNode> nodes,
							 LinkedHashSet<String> stack, String from, int fromLine) throws IncludeException {
		if (stack.contains(path)) {
			throw new IncludeException("include cycle: " + String.join(" -> ", stack) + " -> " + path, from, fromLine);
		}
		if (nodes.containsKey(path)) {
			return;
		}

		Optional<String> contents = source.read(path);
		if (contents.isEmpty()) {
			throw new IncludeException("missing file " + path, from, fromLine);
		}

		List<String> lines = splitLines(contents.get());
		Map<Integer, String> includes = parseIncludes(path, lines);
		FileNode node = new FileNode(path, lines, includes);
		nodes.put(path, node);

		stack.add(path);
		for (Map.Entry<Integer, String> include : includes.entrySet()) {
			load(source, include.getValue(), nodes, stack, path, include.getKey() + 1);
		}
		stack.remove(path);
	}

	private static List<String> splitLines(String text) {
		String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
		if (normalized.isEmpty()) {
			return List.of();
		}
		String[] split = normalized.split("\n", -1);
		List<String> lines = new ArrayList<>(split.length);
		for (String line : split) {
			lines.add(line);
		}
		if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
			lines.remove(lines.size() - 1);
		}
		return List.copyOf(lines);
	}

	private static Map<Integer, String> parseIncludes(String path, List<String> lines) {
		Map<Integer, String> includes = new LinkedHashMap<>();
		String directory = parentDirectory(path);
		for (int index = 0; index < lines.size(); index++) {
			String trimmed = lines.get(index).trim();
			if (!trimmed.startsWith("#include")) {
				continue;
			}
			String target = trimmed.substring("#include".length()).trim();
			if (target.startsWith("\"")) {
				target = target.substring(1);
			}
			if (target.endsWith("\"")) {
				target = target.substring(0, target.length() - 1);
			}
			includes.put(index, resolveInclude(directory, target));
		}
		return includes;
	}

	private static String resolveInclude(String directory, String target) {
		String value = target.replace('\\', '/');
		if (value.startsWith("/")) {
			return PackSource.normalize(value);
		}
		String base = directory.equals("/") ? "/" : directory + "/";
		return PackSource.normalize(base + value);
	}

	private static String parentDirectory(String path) {
		int slash = path.lastIndexOf('/');
		if (slash <= 0) {
			return "/";
		}
		return path.substring(0, slash);
	}

	public Map<String, FileNode> nodes() {
		return nodes;
	}

	public Optional<FileNode> node(String path) {
		return Optional.ofNullable(nodes.get(PackSource.normalize(path)));
	}

	public Includes withLines(Map<String, List<String>> replacement) {
		Map<String, FileNode> mapped = new HashMap<>();
		for (Map.Entry<String, FileNode> entry : nodes.entrySet()) {
			FileNode node = entry.getValue();
			List<String> lines = replacement.getOrDefault(entry.getKey(), node.lines());
			mapped.put(entry.getKey(), new FileNode(node.path(), lines, node.includes()));
		}
		return new Includes(mapped);
	}

	public ExpandedSource expand(String path) throws IncludeException {
		return expand(path, new HashMap<>(), new LinkedHashSet<>());
	}

	private ExpandedSource expand(String path, Map<String, ExpandedSource> cache,
								  Set<String> stack) throws IncludeException {
		String normalized = PackSource.normalize(path);
		ExpandedSource cached = cache.get(normalized);
		if (cached != null) {
			return cached;
		}
		if (stack.contains(normalized)) {
			throw new IncludeException("include cycle at " + normalized, normalized, 0);
		}

		FileNode node = nodes.get(normalized);
		if (node == null) {
			throw new IncludeException("missing file " + normalized, normalized, 0);
		}

		stack.add(normalized);
		List<String> lines = new ArrayList<>();
		List<ExpandedSource.SourceLocation> map = new ArrayList<>();

		for (int index = 0; index < node.lines().size(); index++) {
			String include = node.includes().get(index);
			if (include == null) {
				lines.add(node.lines().get(index));
				map.add(new ExpandedSource.SourceLocation(node.path(), index + 1));
			} else {
				ExpandedSource included = expand(include, cache, stack);
				lines.addAll(included.lines());
				map.addAll(included.lineMap());
			}
		}

		stack.remove(normalized);
		ExpandedSource expanded = new ExpandedSource(lines, map);
		cache.put(normalized, expanded);
		return expanded;
	}

	public List<String> componentOf(String path) {
		String start = PackSource.normalize(path);
		if (!nodes.containsKey(start)) {
			return List.of();
		}
		Map<String, Set<String>> adjacency = new HashMap<>();
		for (FileNode node : nodes.values()) {
			adjacency.computeIfAbsent(node.path(), ignored -> new HashSet<>());
			for (String include : node.includes().values()) {
				if (nodes.containsKey(include)) {
					adjacency.computeIfAbsent(node.path(), ignored -> new HashSet<>()).add(include);
					adjacency.computeIfAbsent(include, ignored -> new HashSet<>()).add(node.path());
				}
			}
		}

		List<String> component = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		ArrayList<String> queue = new ArrayList<>();
		queue.add(start);
		seen.add(start);
		while (!queue.isEmpty()) {
			String current = queue.remove(queue.size() - 1);
			component.add(current);
			for (String next : adjacency.getOrDefault(current, Set.of())) {
				if (seen.add(next)) {
					queue.add(next);
				}
			}
		}
		return component;
	}

	public List<List<String>> components() {
		List<List<String>> components = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String path : nodes.keySet()) {
			if (seen.contains(path)) {
				continue;
			}
			List<String> component = componentOf(path);
			seen.addAll(component);
			components.add(component);
		}
		return components;
	}

	public record FileNode(String path, List<String> lines, Map<Integer, String> includes) {
		public FileNode {
			lines = List.copyOf(lines);
			includes = Map.copyOf(includes);
		}
	}
}
