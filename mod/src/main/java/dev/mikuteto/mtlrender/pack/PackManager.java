package dev.mikuteto.mtlrender.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import dev.mikuteto.mtlrender.config.Config;
import net.fabricmc.loader.api.FabricLoader;

public final class PackManager {
	private static PackManager instance;

	private final Path shaderpacksDirectory;
	private ShaderPack active;
	private String activeName = "";
	private String selectedName = "";
	private Exception lastError;
	private long version;

	private PackManager() {
		shaderpacksDirectory = FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
		reload();
	}

	public static synchronized PackManager get() {
		if (instance == null) {
			instance = new PackManager();
		}
		return instance;
	}

	public synchronized Optional<ShaderPack> active() {
		return Optional.ofNullable(active);
	}

	public synchronized String activeName() {
		return activeName;
	}

	public synchronized String selectedName() {
		return selectedName;
	}

	public synchronized long version() {
		return version;
	}

	public synchronized Optional<Exception> lastError() {
		return Optional.ofNullable(lastError);
	}

	public Path shaderpacksDirectory() {
		return shaderpacksDirectory;
	}

	public synchronized Result reload() {
		String requested = Config.get().shaderPack;
		selectedName = requested == null ? "" : requested;
		if (selectedName.isEmpty()) {
			return disableInternal(true);
		}

		try {
			PackLocation location = locate(selectedName);
			Map<String, String> options = PackOptionFile.read(optionFile(location.optionName()));
			ShaderPack loaded = ShaderPack.load(location.path(), options, Config.get().packPrecisionPolicy());
			selectedName = location.optionName();
			activate(loaded, location.optionName());
			return success(true, location.optionName());
		} catch (Exception exception) {
			lastError = exception;
			return failure(selectedName, exception);
		}
	}

	public synchronized Result apply(String name) {
		return apply(name, null);
	}

	public synchronized Result apply(String name, Map<String, String> options) {
		try {
			PackLocation location = locate(name);
			if (options != null) {
				PackOptionFile.write(optionFile(location.optionName()), new LinkedHashMap<>(options));
				Config config = Config.get();
				config.shaderPack = location.optionName();
				config.save();
				return reload();
			}

			Map<String, String> requestedOptions = PackOptionFile.read(optionFile(location.optionName()));
			ShaderPack loaded = ShaderPack.load(location.path(), requestedOptions, Config.get().packPrecisionPolicy());
			Config config = Config.get();
			config.shaderPack = location.optionName();
			config.save();
			selectedName = location.optionName();
			activate(loaded, location.optionName());
			return success(true, location.optionName());
		} catch (Exception exception) {
			lastError = exception;
			return failure(name, exception);
		}
	}

	public synchronized Result disable() {
		Config config = Config.get();
		config.shaderPack = "";
		config.save();
		selectedName = "";
		return disableInternal(true);
	}

	private Result disableInternal(boolean clearError) {
		boolean changed = active != null || !activeName.isEmpty();
		active = null;
		activeName = "";
		if (clearError) {
			lastError = null;
		}
		if (changed) {
			version++;
		}
		return success(changed, "");
	}

	private void activate(ShaderPack loaded, String name) {
		active = loaded;
		activeName = name;
		lastError = null;
		version++;
	}

	private Result success(boolean changed, String name) {
		return new Result(true, changed, name, Optional.empty());
	}

	private Result failure(String name, Exception exception) {
		return new Result(false, false, name == null ? "" : name, Optional.of(exception));
	}

	private PackLocation locate(String name) throws IOException {
		if (!validName(name)) {
			throw new IOException("Invalid shader pack name");
		}

		Path exact = shaderpacksDirectory.resolve(name);
		if (Files.isDirectory(exact)) {
			return new PackLocation(exact, stripZip(name));
		}
		if (name.endsWith(".zip") && Files.isRegularFile(exact)) {
			return new PackLocation(exact, stripZip(name));
		}
		if (!name.endsWith(".zip")) {
			Path zip = shaderpacksDirectory.resolve(name + ".zip");
			if (Files.isRegularFile(zip)) {
				return new PackLocation(zip, name);
			}
		}
		throw new IOException("Shader pack does not exist");
	}

	private boolean validName(String name) {
		if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
			|| name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
			return false;
		}
		try {
			String baseName = stripZip(name);
			return !baseName.isBlank() && Path.of(name).getNameCount() == 1;
		} catch (InvalidPathException exception) {
			return false;
		}
	}

	private Path optionFile(String name) {
		return shaderpacksDirectory.resolve(name + ".txt");
	}

	private String stripZip(String name) {
		return name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
	}

	public record Result(boolean success, boolean changed, String name, Optional<Exception> error) {
		public Result {
			error = error == null ? Optional.empty() : error;
		}
	}

	private record PackLocation(Path path, String optionName) {
	}
}
