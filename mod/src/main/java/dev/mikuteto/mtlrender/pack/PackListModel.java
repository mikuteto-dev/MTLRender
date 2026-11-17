package dev.mikuteto.mtlrender.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class PackListModel {
	private static final String OFF = "";
	private final PackManager manager;
	private final Path shaderpacksDirectory;
	private List<Entry> entries = List.of();
	private String stagedSelection;
	private PackManager.Result lastResult;
	private LoadError loadError;

	public PackListModel(PackManager manager) {
		this(manager, manager.shaderpacksDirectory());
	}

	PackListModel(Path shaderpacksDirectory) {
		this(null, shaderpacksDirectory);
	}

	private PackListModel(PackManager manager, Path shaderpacksDirectory) {
		this.manager = manager;
		this.shaderpacksDirectory = shaderpacksDirectory;
		this.stagedSelection = manager == null ? OFF : manager.selectedName();
		refresh();
	}

	public List<Entry> entries() {
		return entries;
	}

	public Path openFolder() {
		return shaderpacksDirectory;
	}

	public String activeSelection() {
		return manager == null ? OFF : manager.activeName();
	}

	public String stagedSelection() {
		return stagedSelection;
	}

	public Optional<PackManager.Result> lastResult() {
		return Optional.ofNullable(lastResult);
	}

	public Optional<LoadError> loadError() {
		return Optional.ofNullable(loadError);
	}

	public void refresh() {
		List<Entry> discovered = new ArrayList<>();
		discovered.add(Entry.disabled());
		try {
			if (Files.isDirectory(shaderpacksDirectory)) {
				try (var paths = Files.list(shaderpacksDirectory)) {
					paths.filter(PackListModel::isCandidate)
						.map(Entry::pack)
						.sorted(Entry.ORDER)
						.forEach(discovered::add);
				}
			}
			entries = List.copyOf(discovered);
			if (manager != null) {
				manager.lastError().ifPresent(exception -> loadError = LoadError.from(manager.selectedName(), exception));
			}
		} catch (Exception exception) {
			entries = List.of(Entry.disabled());
			loadError = LoadError.from(stagedSelection, exception);
		}
	}

	public boolean select(String selection) {
		String requested = selection == null ? OFF : selection;
		if (entries.stream().anyMatch(entry -> entry.id().equals(requested))) {
			stagedSelection = requested;
			return true;
		}
		loadError = new LoadError(requested, "Shader pack is not available");
		return false;
	}

	public PackManager.Result applySelection() {
		if (manager == null) {
			Exception exception = new IOException("Pack manager is unavailable");
			loadError = LoadError.from(stagedSelection, exception);
			lastResult = new PackManager.Result(false, false, stagedSelection, Optional.of(exception));
			return lastResult;
		}
		try {
			PackManager.Result result = stagedSelection.isEmpty()
				? manager.disable()
				: manager.apply(stagedSelection);
			lastResult = result;
			if (result.success()) {
				loadError = null;
			} else {
				result.error().ifPresent(exception -> loadError = LoadError.from(stagedSelection, exception));
			}
			return result;
		} catch (Exception exception) {
			loadError = LoadError.from(stagedSelection, exception);
			lastResult = new PackManager.Result(false, false, stagedSelection, Optional.of(exception));
			return lastResult;
		}
	}

	private static boolean isCandidate(Path path) {
		return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".zip")
			|| Files.isDirectory(path) && Files.isDirectory(path.resolve("shaders"));
	}

	public record Entry(String id, String displayName, Path path, boolean off) {
		private static final Comparator<Entry> ORDER = Comparator
			.comparing(Entry::displayName, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(Entry::displayName)
			.thenComparing(entry -> entry.path().toString());

		private static Entry disabled() {
			return new Entry(OFF, "Off", null, true);
		}

		private static Entry pack(Path path) {
			String id = path.getFileName().toString();
			String displayName = id.endsWith(".zip") ? id.substring(0, id.length() - 4) : id;
			return new Entry(id, displayName, path, false);
		}
	}

	public record LoadError(String selection, String message) {
		private static LoadError from(String selection, Exception exception) {
			String message = exception.getMessage();
			return new LoadError(selection == null ? OFF : selection,
				message == null || message.isBlank() ? exception.getClass().getSimpleName() : message);
		}
	}
}
