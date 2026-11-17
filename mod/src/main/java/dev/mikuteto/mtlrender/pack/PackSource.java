package dev.mikuteto.mtlrender.pack;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class PackSource implements AutoCloseable {
	private final FileSystem zipFileSystem;
	private final Path root;

	private PackSource(FileSystem zipFileSystem, Path root) {
		this.zipFileSystem = zipFileSystem;
		this.root = root;
	}

	public static PackSource open(Path packPath) throws IOException {
		if (Files.isDirectory(packPath)) {
			Path shaders = packPath.resolve("shaders");
			Path root = Files.isDirectory(shaders) ? shaders : packPath;
			return new PackSource(null, root);
		}

		FileSystem fileSystem = FileSystems.newFileSystem(URI.create("jar:" + packPath.toUri()), Map.of());
		Path shaders = fileSystem.getPath("/shaders");
		Path root = Files.isDirectory(shaders) ? shaders : fileSystem.getPath("/");
		return new PackSource(fileSystem, root);
	}

	public static String normalize(String path) {
		String value = path.replace('\\', '/');
		if (!value.startsWith("/")) {
			value = "/" + value;
		}

		List<String> segments = new ArrayList<>();
		for (String segment : value.split("/", -1)) {
			if (segment.isEmpty() || segment.equals(".")) {
				continue;
			}
			if (segment.equals("..")) {
				if (!segments.isEmpty()) {
					segments.remove(segments.size() - 1);
				}
				continue;
			}
			segments.add(segment);
		}

		if (segments.isEmpty()) {
			return "/";
		}

		StringBuilder builder = new StringBuilder();
		for (String segment : segments) {
			builder.append('/').append(segment);
		}
		return builder.toString();
	}

	public Optional<String> read(String path) {
		return read(path, StandardCharsets.UTF_8);
	}

	public Optional<String> read(String path, Charset charset) {
		Path resolved = resolve(path);
		if (!Files.isRegularFile(resolved)) {
			return Optional.empty();
		}
		try {
			return Optional.of(Files.readString(resolved, charset));
		} catch (IOException exception) {
			return Optional.empty();
		}
	}

	public Optional<byte[]> readBytes(String path) {
		Path resolved = resolve(path);
		if (!Files.isRegularFile(resolved)) {
			return Optional.empty();
		}
		try {
			return Optional.of(Files.readAllBytes(resolved));
		} catch (IOException exception) {
			return Optional.empty();
		}
	}

	public boolean exists(String path) {
		return Files.exists(resolve(path));
	}

	public boolean isDirectory(String path) {
		return Files.isDirectory(resolve(path));
	}

	public List<String> list(String directory) {
		Path resolved = resolve(directory);
		if (!Files.isDirectory(resolved)) {
			return List.of();
		}
		try (Stream<Path> stream = Files.list(resolved)) {
			return stream.map(this::absolutePath).sorted().toList();
		} catch (IOException exception) {
			return List.of();
		}
	}

	public List<String> walk(String directory) {
		Path resolved = resolve(directory);
		if (!Files.isDirectory(resolved)) {
			return List.of();
		}
		try (Stream<Path> stream = Files.walk(resolved)) {
			return stream.filter(Files::isRegularFile).map(this::absolutePath).sorted().toList();
		} catch (IOException exception) {
			return List.of();
		}
	}

	public Optional<String> parent(String path) {
		String normalized = normalize(path);
		int slash = normalized.lastIndexOf('/');
		if (slash <= 0) {
			return Optional.empty();
		}
		return Optional.of(normalized.substring(0, slash));
	}

	private String absolutePath(Path path) {
		String relative = root.relativize(path).toString().replace('\\', '/');
		return normalize("/" + relative);
	}

	private Path resolve(String path) {
		String normalized = normalize(path);
		if (normalized.equals("/")) {
			return root;
		}
		return root.resolve(normalized.substring(1));
	}

	@Override
	public void close() throws IOException {
		if (zipFileSystem != null) {
			zipFileSystem.close();
		}
	}
}
