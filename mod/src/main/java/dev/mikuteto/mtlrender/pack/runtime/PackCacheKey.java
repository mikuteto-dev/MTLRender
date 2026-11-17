package dev.mikuteto.mtlrender.pack.runtime;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class PackCacheKey {
	public static final int CURRENT_SCHEMA = 1;

	private final int schemaVersion;
	private final int translatorVersion;
	private final int mslFormatVersion;
	private final byte[] packDigest;
	private final Map<String, String> options;
	private final Map<String, String> macros;
	private final String modVersion;
	private final String osBuild;
	private final String gpuFamily;
	private final int hash;

	public PackCacheKey(int schemaVersion, int translatorVersion, int mslFormatVersion, byte[] packDigest,
		Map<String, String> options, Map<String, String> macros, String modVersion, String osBuild,
		String gpuFamily) {
		if (schemaVersion < 1 || translatorVersion < 1 || mslFormatVersion < 1) {
			throw new IllegalArgumentException("cache versions must be positive");
		}
		this.schemaVersion = schemaVersion;
		this.translatorVersion = translatorVersion;
		this.mslFormatVersion = mslFormatVersion;
		this.packDigest = copyBytes(packDigest, "pack digest");
		this.options = ordered(options, "options");
		this.macros = ordered(macros, "macros");
		this.modVersion = required(modVersion, "mod version");
		this.osBuild = required(osBuild, "OS build");
		this.gpuFamily = required(gpuFamily, "GPU family");
		this.hash = computeHash();
	}

	public int schemaVersion() { return schemaVersion; }
	public int translatorVersion() { return translatorVersion; }
	public int mslFormatVersion() { return mslFormatVersion; }
	public byte[] packDigest() { return packDigest.clone(); }
	public Map<String, String> options() { return options; }
	public Map<String, String> macros() { return macros; }
	public String modVersion() { return modVersion; }
	public String osBuild() { return osBuild; }
	public String gpuFamily() { return gpuFamily; }

	public byte[] canonicalBytes() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeInt(schemaVersion);
			out.writeInt(translatorVersion);
			out.writeInt(mslFormatVersion);
			writeBytes(out, packDigest);
			writeMap(out, options);
			writeMap(out, macros);
			writeString(out, modVersion);
			writeString(out, osBuild);
			writeString(out, gpuFamily);
			out.flush();
			return bytes.toByteArray();
		} catch (IOException exception) {
			throw new IllegalStateException("failed to encode cache key", exception);
		}
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) return true;
		if (!(object instanceof PackCacheKey other)) return false;
		return schemaVersion == other.schemaVersion && translatorVersion == other.translatorVersion
			&& mslFormatVersion == other.mslFormatVersion && Arrays.equals(packDigest, other.packDigest)
			&& options.equals(other.options) && macros.equals(other.macros) && modVersion.equals(other.modVersion)
			&& osBuild.equals(other.osBuild) && gpuFamily.equals(other.gpuFamily);
	}

	@Override
	public int hashCode() { return hash; }

	private int computeHash() {
		int result = Objects.hash(schemaVersion, translatorVersion, mslFormatVersion, options, macros,
			modVersion, osBuild, gpuFamily);
		return 31 * result + Arrays.hashCode(packDigest);
	}

	private static Map<String, String> ordered(Map<String, String> values, String label) {
		Objects.requireNonNull(values, label);
		TreeMap<String, String> sorted = new TreeMap<>();
		for (Map.Entry<String, String> entry : values.entrySet()) {
			sorted.put(required(entry.getKey(), label + " key"), required(entry.getValue(), label + " value"));
		}
		return Map.copyOf(sorted);
	}

	private static String required(String value, String label) {
		Objects.requireNonNull(value, label);
		if (value.isEmpty()) throw new IllegalArgumentException(label + " must not be empty");
		return value;
	}

	private static byte[] copyBytes(byte[] value, String label) {
		Objects.requireNonNull(value, label);
		return value.clone();
	}

	private static void writeMap(DataOutputStream out, Map<String, String> values) throws IOException {
		out.writeInt(values.size());
		for (Map.Entry<String, String> entry : values.entrySet()) {
			writeString(out, entry.getKey());
			writeString(out, entry.getValue());
		}
	}

	private static void writeString(DataOutputStream out, String value) throws IOException {
		writeBytes(out, value.getBytes(StandardCharsets.UTF_8));
	}

	private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
		out.writeInt(value.length);
		out.write(value);
	}
}
