package dev.mikuteto.mtlrender.pack.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class PackTranslationDiskCache {
	private static final int FILE_VERSION = 1;
	private static final int MAX_FIELD = 64 * 1024 * 1024;
	private static final int MAX_ITEMS = 1_000_000;
	private static final byte[] MAGIC = "MTLRSPC1".getBytes(StandardCharsets.US_ASCII);
	private final Path root;

	public PackTranslationDiskCache() {
		this(defaultRoot());
	}

	public PackTranslationDiskCache(Path root) {
		this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
	}

	public Path root() {
		return root;
	}

	public Optional<PackTranslationArtifact> read(PackCacheKey key) {
		Path path = path(key);
		if (!Files.isRegularFile(path)) {
			return Optional.empty();
		}
		try {
			byte[] bytes = Files.readAllBytes(path);
			return Optional.of(decode(key, bytes));
		} catch (IOException | RuntimeException exception) {
			return Optional.empty();
		}
	}

	public Optional<PackTranslationArtifact> load(PackCacheKey key) {
		return read(key);
	}

	public void write(PackCacheKey key, PackTranslationArtifact artifact) throws IOException {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(artifact, "artifact");
		Path destination = path(key);
		Files.createDirectories(root);
		Path temporary = root.resolve(destination.getFileName() + "." + UUID.randomUUID() + ".tmp");
		try {
			Files.write(temporary, encode(key, artifact));
			try {
				Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	public void store(PackCacheKey key, PackTranslationArtifact artifact) throws IOException {
		write(key, artifact);
	}

	public Path path(PackCacheKey key) {
		Objects.requireNonNull(key, "key");
		String name = HexFormat.of().formatHex(digest(key.canonicalBytes())) + ".bin";
		Path path = root.resolve(name).normalize();
		if (!path.getParent().equals(root) || !path.startsWith(root)) {
			throw new IllegalStateException("cache path escaped root");
		}
		return path;
	}

	public static byte[] packDigest(Path pack) throws IOException {
		Objects.requireNonNull(pack, "pack");
		TreeMap<String, byte[]> files = Files.isDirectory(pack) ? directoryFiles(pack) : zipFiles(pack);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (Map.Entry<String, byte[]> entry : files.entrySet()) {
				writeDigestPart(digest, entry.getKey().getBytes(StandardCharsets.UTF_8));
				writeDigestPart(digest, entry.getValue());
			}
			return digest.digest();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static TreeMap<String, byte[]> directoryFiles(Path pack) throws IOException {
		Path base = Files.isDirectory(pack.resolve("shaders")) ? pack.resolve("shaders") : pack;
		TreeMap<String, byte[]> files = new TreeMap<>();
		try (var stream = Files.walk(base)) {
			for (Path file : stream.filter(Files::isRegularFile).toList()) {
				String name = base.relativize(file).toString().replace('\\', '/');
				files.put(canonicalName(name), Files.readAllBytes(file));
			}
		}
		return files;
	}

	private static TreeMap<String, byte[]> zipFiles(Path pack) throws IOException {
		try (ZipFile zip = new ZipFile(pack.toFile())) {
			boolean shadersRoot = zip.stream().anyMatch(entry -> !entry.isDirectory() && canonicalName(entry.getName()).startsWith("shaders/"));
			String prefix = shadersRoot ? "shaders/" : "";
			TreeMap<String, byte[]> files = new TreeMap<>();
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory()) continue;
				String name = canonicalName(entry.getName());
				if (!name.startsWith(prefix)) continue;
				String relative = name.substring(prefix.length());
				if (relative.isEmpty()) continue;
				files.put(relative, zip.getInputStream(entry).readAllBytes());
			}
			return files;
		}
	}

	private static String canonicalName(String name) {
		String value = name.replace('\\', '/');
		List<String> segments = new ArrayList<>();
		for (String segment : value.split("/")) {
			if (segment.isEmpty() || segment.equals(".")) continue;
			if (segment.equals("..")) {
				if (segments.isEmpty()) throw new IllegalArgumentException("invalid pack path");
				segments.remove(segments.size() - 1);
				continue;
			}
			segments.add(segment);
		}
		if (segments.isEmpty()) throw new IllegalArgumentException("invalid pack path");
		return String.join("/", segments);
	}

	private static void writeDigestPart(MessageDigest digest, byte[] value) {
		digest.update((byte) (value.length >>> 24));
		digest.update((byte) (value.length >>> 16));
		digest.update((byte) (value.length >>> 8));
		digest.update((byte) value.length);
		digest.update(value);
	}

	private static byte[] encode(PackCacheKey key, PackTranslationArtifact artifact) throws IOException {
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(payload);
		out.writeInt(FILE_VERSION);
		writeBytes(out, key.canonicalBytes());
		writeString(out, artifact.vertexMsl());
		writeString(out, artifact.vertexEntry());
		writeString(out, artifact.fragmentMsl());
		writeString(out, artifact.fragmentEntry());
		writeReflection(out, artifact.reflection());
		out.flush();
		byte[] data = payload.toByteArray();
		CRC32 checksum = new CRC32();
		checksum.update(data);
		ByteArrayOutputStream result = new ByteArrayOutputStream();
		DataOutputStream file = new DataOutputStream(result);
		file.write(MAGIC);
		file.writeInt(data.length);
		file.write(data);
		file.writeLong(checksum.getValue());
		file.flush();
		return result.toByteArray();
	}

	private static PackTranslationArtifact decode(PackCacheKey expected, byte[] file) throws IOException {
		DataInputStream input = new DataInputStream(new ByteArrayInputStream(file));
		byte[] magic = input.readNBytes(MAGIC.length);
		if (!java.util.Arrays.equals(magic, MAGIC)) throw new IOException("invalid cache magic");
		int length = input.readInt();
		if (length < 1 || length > MAX_FIELD || length > input.available() - Long.BYTES) throw new IOException("invalid cache length");
		byte[] data = input.readNBytes(length);
		long stored = input.readLong();
		if (input.available() != 0) throw new IOException("trailing cache data");
		CRC32 checksum = new CRC32();
		checksum.update(data);
		if (stored != checksum.getValue()) throw new IOException("invalid cache checksum");
		DataInputStream payload = new DataInputStream(new ByteArrayInputStream(data));
		if (payload.readInt() != FILE_VERSION) throw new IOException("unsupported cache format");
		if (!java.util.Arrays.equals(readBytes(payload), expected.canonicalBytes())) throw new IOException("cache key mismatch");
		PackTranslationArtifact artifact = new PackTranslationArtifact(readString(payload), readString(payload), readString(payload), readString(payload), readReflection(payload));
		if (payload.available() != 0) throw new IOException("incomplete cache artifact");
		return artifact;
	}

	private static void writeReflection(DataOutputStream out, PackTranslationArtifact.Reflection reflection) throws IOException {
		writeBindings(out, reflection.bindings());
		writeVertexInputs(out, reflection.vertexInputs());
		writeFragmentOutputs(out, reflection.fragmentOutputs());
		PackTranslationArtifact.DepthState depth = reflection.depth();
		out.writeBoolean(depth != null);
		if (depth != null) {
			writeString(out, depth.format());
			writeString(out, depth.compare());
			out.writeBoolean(depth.write());
		}
	}

	private static PackTranslationArtifact.Reflection readReflection(DataInputStream in) throws IOException {
		List<PackTranslationArtifact.ResourceBinding> bindings = new ArrayList<>();
		int count = count(in);
		for (int i = 0; i < count; i++) bindings.add(new PackTranslationArtifact.ResourceBinding(readString(in), enumValue(PackTranslationArtifact.Kind.values(), in.readInt()), in.readInt(), in.readInt()));
		List<PackTranslationArtifact.VertexInput> inputs = new ArrayList<>();
		count = count(in);
		for (int i = 0; i < count; i++) inputs.add(new PackTranslationArtifact.VertexInput(readString(in), in.readInt(), readString(in), in.readInt(), in.readInt()));
		List<PackTranslationArtifact.FragmentOutput> outputs = new ArrayList<>();
		count = count(in);
		for (int i = 0; i < count; i++) outputs.add(new PackTranslationArtifact.FragmentOutput(readString(in), in.readInt(), in.readInt()));
		PackTranslationArtifact.DepthState depth = in.readBoolean() ? new PackTranslationArtifact.DepthState(readString(in), readString(in), in.readBoolean()) : null;
		return new PackTranslationArtifact.Reflection(bindings, inputs, outputs, depth);
	}

	private static void writeBindings(DataOutputStream out, List<PackTranslationArtifact.ResourceBinding> values) throws IOException {
		out.writeInt(values.size());
		for (PackTranslationArtifact.ResourceBinding value : values) { writeString(out, value.name()); out.writeInt(value.kind().ordinal()); out.writeInt(value.slot()); out.writeInt(value.stages()); }
	}
	private static void writeVertexInputs(DataOutputStream out, List<PackTranslationArtifact.VertexInput> values) throws IOException {
		out.writeInt(values.size());
		for (PackTranslationArtifact.VertexInput value : values) { writeString(out, value.name()); out.writeInt(value.location()); writeString(out, value.format()); out.writeInt(value.bufferSlot()); out.writeInt(value.offset()); }
	}
	private static void writeFragmentOutputs(DataOutputStream out, List<PackTranslationArtifact.FragmentOutput> values) throws IOException {
		out.writeInt(values.size());
		for (PackTranslationArtifact.FragmentOutput value : values) { writeString(out, value.name()); out.writeInt(value.location()); out.writeInt(value.colorAttachment()); }
	}
	private static void writeString(DataOutputStream out, String value) throws IOException { writeBytes(out, value.getBytes(StandardCharsets.UTF_8)); }
	private static void writeBytes(DataOutputStream out, byte[] value) throws IOException { if (value.length > MAX_FIELD) throw new IOException("cache field too large"); out.writeInt(value.length); out.write(value); }
	private static String readString(DataInputStream in) throws IOException { return new String(readBytes(in), StandardCharsets.UTF_8); }
	private static byte[] readBytes(DataInputStream in) throws IOException { int length = in.readInt(); if (length < 0 || length > MAX_FIELD || length > in.available()) throw new IOException("invalid cache field"); return in.readNBytes(length); }
	private static int count(DataInputStream in) throws IOException { int count = in.readInt(); if (count < 0 || count > MAX_ITEMS) throw new IOException("invalid cache count"); return count; }
	private static <T> T enumValue(T[] values, int index) throws IOException { if (index < 0 || index >= values.length) throw new IOException("invalid cache enum"); return values[index]; }

	private static byte[] digest(byte[] value) {
		try { return MessageDigest.getInstance("SHA-256").digest(value); }
		catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
	}

	private static Path defaultRoot() {
		String override = System.getProperty("mtlrender.packCacheDir");
		if (override != null && !override.isBlank()) return Path.of(override);
		String home = System.getProperty("user.home");
		if (home == null || home.isBlank()) return Path.of(".mtlrender", "cache", "shaderpacks");
		return Path.of(home, "Library", "Caches", "mtlrender", "shaderpacks");
	}
}
