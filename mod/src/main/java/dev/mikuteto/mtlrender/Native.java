package dev.mikuteto.mtlrender;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import net.fabricmc.loader.api.FabricLoader;

public final class Native {
	private static boolean loaded;

	private Native() {
	}

	public static synchronized void load() throws IOException {
		if (loaded) {
			return;
		}
		byte[] bytes;
		try (InputStream in = Native.class.getResourceAsStream("/natives/libmtlrender.dylib")) {
			if (in == null) {
				throw new IOException("natives/libmtlrender.dylib is missing from the mod jar");
			}
			bytes = in.readAllBytes();
		}
		Path dir;
		try {
			dir = FabricLoader.getInstance().getGameDir().resolve(".mtlrender/natives");
		} catch (IllegalStateException exception) {
			dir = Path.of(System.getProperty("user.dir")).resolve("run/.mtlrender/natives");
		}
		Path lib = dir.resolve(sha256(bytes) + ".dylib");
		if (!Files.exists(lib)) {
			Files.createDirectories(dir);
			Path tmp = Files.createTempFile(dir, "lib", ".tmp");
			Files.write(tmp, bytes);
			Files.move(tmp, lib, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		}
		System.load(lib.toAbsolutePath().toString());
		loaded = true;
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
