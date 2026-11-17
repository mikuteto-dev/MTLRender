package dev.mikuteto.mtlrender.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.mikuteto.mtlrender.pack.runtime.ComputePlan;

public final class ShaderProperties {
	private final Map<String, String> raw = new LinkedHashMap<>();
	private final Map<String, String> programEnabled = new LinkedHashMap<>();
	private final Map<String, Map<String, String>> uniforms = new LinkedHashMap<>();
	private final Map<String, Map<String, String>> variables = new LinkedHashMap<>();
	private final Map<String, Map<String, Boolean>> flips = new LinkedHashMap<>();
	private final Map<String, String> blends = new LinkedHashMap<>();
	private final Map<String, Map<String, String>> bufferBlends = new LinkedHashMap<>();
	private final Map<String, String> alphaTests = new LinkedHashMap<>();
	private final Map<String, String> scales = new LinkedHashMap<>();
	private final Map<String, Integer> screenColumns = new LinkedHashMap<>();
	private final Map<String, String> bufferScales = new LinkedHashMap<>();
	private final Map<String, Map<String, String>> textures = new LinkedHashMap<>();
	private final Map<String, String> images = new LinkedHashMap<>();
	private final Map<Integer, BufferObject> bufferObjects = new LinkedHashMap<>();
	private final Map<String, ComputePlan.CustomImageSpec> computeImages = new LinkedHashMap<>();
	private final Map<String, ComputePlan.DispatchSpec> indirectDispatches = new LinkedHashMap<>();
	private final Map<String, String> customTextures = new LinkedHashMap<>();

	private List<String> screen = List.of();
	private Map<String, List<String>> screens = new LinkedHashMap<>();
	private List<String> sliders = List.of();
	private Map<String, List<String>> profiles = new LinkedHashMap<>();
	private List<String> requiredFeatures = List.of();
	private List<String> optionalFeatures = List.of();
	private String noiseTexture;
	private String clouds;
	private String shadowCulling;
	private Boolean weather;
	private Boolean weatherParticles;
	private Boolean oldLighting;
	private Boolean separateAo;
	private Boolean sun;
	private Boolean moon;
	private Boolean shadowTerrain;
	private Boolean shadowTranslucent;
	private Boolean shadowEntities;
	private Boolean shadowBlockEntities;
	private Boolean allowConcurrentCompute;
	private Boolean prepareBeforeShadow;
	private Boolean supportsColorCorrection;
	private ComputePlan.DispatchSpec workGroups;
	private ComputePlan.DispatchSpec workGroupsRender;

	public ShaderProperties(String original, String preprocessed) {
		parsePreprocessed(preprocessed);
		parseOriginal(original);
	}

	public static ShaderProperties empty() {
		return new ShaderProperties("", "");
	}

	private void parsePreprocessed(String source) {
		for (Map.Entry<String, String> entry : parse(source).entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();

			if ("texture.noise".equals(key)) {
				noiseTexture = value;
			} else if (key.startsWith("program.") && key.endsWith(".enabled")) {
				String name = key.substring("program.".length(), key.length() - ".enabled".length());
				if (!name.isEmpty()) {
					programEnabled.put(name, value);
				}
			} else if (key.startsWith("uniform.")) {
				twoPart(uniforms, key.substring("uniform.".length()), value, key);
			} else if (key.startsWith("variable.")) {
				twoPart(variables, key.substring("variable.".length()), value, key);
			} else if (key.startsWith("flip.")) {
				flip(key.substring("flip.".length()), value, key);
			} else if (key.startsWith("blend.")) {
				blend(key.substring("blend.".length()), value);
			} else if (key.startsWith("alphaTest.")) {
				alphaTests.put(key.substring("alphaTest.".length()), value);
			} else if (key.startsWith("scale.")) {
				scales.put(key.substring("scale.".length()), value);
			} else if (key.startsWith("size.buffer.")) {
				bufferScales.put(key.substring("size.buffer.".length()), value);
			} else if (key.startsWith("texture.")) {
				texture(key.substring("texture.".length()), value, key);
			} else if (key.startsWith("image.")) {
				String name = key.substring("image.".length());
				images.put(name, value);
				image(name, value, key);
			} else if (key.startsWith("bufferObject.")) {
				bufferObject(key.substring("bufferObject.".length()), value, key);
			} else if (key.startsWith("indirect.")) {
				DispatchSpecValue parsed = indirect(value);
				if (parsed != null) {
					indirectDispatches.put(key.substring("indirect.".length()), parsed.spec());
				} else {
					raw.put(key, value);
				}
			} else if ("workGroups".equals(key)) {
				workGroups = dispatch(value, false, key);
				if (workGroups == null) raw.put(key, value);
			} else if ("workGroupsRender".equals(key)) {
				workGroupsRender = dispatch(value, true, key);
				if (workGroupsRender == null) raw.put(key, value);
			} else if (key.startsWith("customTexture.")) {
				customTextures.put(key.substring("customTexture.".length()), value);
			} else if ("shadow.culling".equals(key)) {
				shadowCulling = value;
			} else if ("shadowTerrain".equals(key)) {
				shadowTerrain = booleanValue(value);
			} else if ("shadowTranslucent".equals(key)) {
				shadowTranslucent = booleanValue(value);
			} else if ("shadowEntities".equals(key)) {
				shadowEntities = booleanValue(value);
			} else if ("shadowBlockEntities".equals(key)) {
				shadowBlockEntities = booleanValue(value);
			} else if ("oldLighting".equals(key)) {
				oldLighting = booleanValue(value);
			} else if ("separateAo".equals(key)) {
				separateAo = booleanValue(value);
			} else if ("sun".equals(key)) {
				sun = booleanValue(value);
			} else if ("moon".equals(key)) {
				moon = booleanValue(value);
			} else if ("clouds".equals(key)) {
				clouds = value;
			} else if ("weather".equals(key)) {
				String[] parts = value.trim().split(" +");
				if (parts.length > 0) {
					weather = booleanValue(parts[0]);
				}
				if (parts.length > 1) {
					weatherParticles = booleanValue(parts[1]);
				}
			} else if ("allowConcurrentCompute".equals(key)) {
				allowConcurrentCompute = booleanValue(value);
			} else if ("prepareBeforeShadow".equals(key)) {
				prepareBeforeShadow = booleanValue(value);
			} else if ("supportsColorCorrection".equals(key)) {
				supportsColorCorrection = booleanValue(value);
			} else {
				raw.put(key, value);
			}
		}
	}

	private void parseOriginal(String source) {
		for (Map.Entry<String, String> entry : parse(source).entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();

			if ("screen".equals(key)) {
				screen = list(value);
			} else if ("sliders".equals(key)) {
				sliders = list(value);
			} else if ("iris.features.required".equals(key)) {
				requiredFeatures = list(value);
			} else if ("iris.features.optional".equals(key)) {
				optionalFeatures = list(value);
			} else if (key.startsWith("profile.")) {
				profiles.put(key.substring("profile.".length()), list(value));
			} else if (key.startsWith("screen.") && key.endsWith(".columns")) {
				if (key.length() <= "screen.".length() + ".columns".length()) {
					raw.put(key, value);
					continue;
				}
				String name = key.substring("screen.".length(), key.length() - ".columns".length());
				try {
					screenColumns.put(name, Integer.parseInt(value.trim()));
				} catch (NumberFormatException exception) {
					raw.put(key, value);
				}
			} else if (key.startsWith("screen.")) {
				screens.put(key.substring("screen.".length()), list(value));
			}
		}
	}

	private static void twoPart(Map<String, Map<String, String>> target, String remainder, String value, String key) {
		int dot = remainder.indexOf('.');
		if (dot <= 0 || dot == remainder.length() - 1) {
			return;
		}
		String type = remainder.substring(0, dot);
		String name = remainder.substring(dot + 1);
		target.computeIfAbsent(type, ignored -> new LinkedHashMap<>()).put(name, value);
	}

	private void flip(String remainder, String value, String key) {
		int dot = remainder.indexOf('.');
		if (dot <= 0 || dot == remainder.length() - 1) {
			return;
		}
		Boolean enabled = booleanValue(value);
		if (enabled == null) {
			raw.put(key, value);
			return;
		}
		String pass = remainder.substring(0, dot);
		String buffer = remainder.substring(dot + 1);
		flips.computeIfAbsent(pass, ignored -> new LinkedHashMap<>()).put(buffer, enabled);
	}

	private void blend(String remainder, String value) {
		int dot = remainder.indexOf('.');
		if (dot <= 0) {
			blends.put(remainder, value);
			return;
		}
		String program = remainder.substring(0, dot);
		String buffer = remainder.substring(dot + 1);
		bufferBlends.computeIfAbsent(program, ignored -> new LinkedHashMap<>()).put(buffer, value);
	}

	private void texture(String remainder, String value, String key) {
		int dot = remainder.indexOf('.');
		if (dot <= 0) {
			raw.put(key, value);
			return;
		}
		String stage = remainder.substring(0, dot);
		String name = remainder.substring(dot + 1);
		int version = name.indexOf('.');
		if (version >= 0) {
			name = name.substring(0, version);
		}
		textures.computeIfAbsent(stage, ignored -> new LinkedHashMap<>()).put(name, value);
	}

	private void bufferObject(String remainder, String value, String key) {
		int index;
		try {
			index = Integer.parseInt(remainder);
		} catch (NumberFormatException exception) {
			raw.put(key, value);
			return;
		}

		String[] parts = value.trim().split(" +");
		try {
			long size = Long.parseLong(parts[0]);
			if (size <= 0) throw new NumberFormatException("non-positive size");
			if (parts.length <= 2) {
				String name = parts.length == 2 ? parts[1] : null;
				bufferObjects.put(index, new BufferObject(size, name, false, 0.0f, 0.0f));
			} else if (parts.length == 4 && ("true".equals(parts[1]) || "false".equals(parts[1]))) {
				boolean relative = Boolean.parseBoolean(parts[1]);
				float scaleX = Float.parseFloat(parts[2]);
				float scaleY = Float.parseFloat(parts[3]);
				if (!relative || scaleX <= 0.0f || scaleY <= 0.0f) throw new NumberFormatException("invalid relative size");
				bufferObjects.put(index, new BufferObject(size, null, true, scaleX, scaleY));
			} else {
				raw.put(key, value);
			}
		} catch (NumberFormatException | ArrayIndexOutOfBoundsException exception) {
			raw.put(key, value);
		}
	}

	private void image(String name, String value, String key) {
		String[] parts = value.trim().split(" +");
		try {
			if (name.isEmpty() || parts.length < 7 || parts.length > 9) throw new IllegalArgumentException();
			String format = parts[1];
			String internalFormat = parts[2];
			String pixelType = parts[3];
			Boolean clear = booleanValue(parts[4]);
			Boolean relative = booleanValue(parts[5]);
			if (clear == null || relative == null) throw new IllegalArgumentException();
			int slot = computeImages.containsKey(name) ? computeImages.get(name).slot() : computeImages.size();
			ComputePlan.CustomImageSpec spec;
			if (relative) {
				if (parts.length != 8) throw new IllegalArgumentException();
				float width = Float.parseFloat(parts[6]);
				float height = Float.parseFloat(parts[7]);
				spec = new ComputePlan.CustomImageSpec(name, slot, ComputePlan.ImageDimension.TWO,
						ComputePlan.Dimensions.two(1, 1), ComputePlan.Access.READ_WRITE, format, internalFormat,
						pixelType, clear, true, width, height);
			} else {
				int width = Integer.parseInt(parts[6]);
				if (parts.length == 7) {
					spec = new ComputePlan.CustomImageSpec(name, slot, ComputePlan.ImageDimension.ONE,
						ComputePlan.Dimensions.one(width), ComputePlan.Access.READ_WRITE, format, internalFormat,
						pixelType, clear, false, 0.0f, 0.0f);
				} else if (parts.length == 8) {
					int height = Integer.parseInt(parts[7]);
					spec = new ComputePlan.CustomImageSpec(name, slot, ComputePlan.ImageDimension.TWO,
						ComputePlan.Dimensions.two(width, height), ComputePlan.Access.READ_WRITE, format, internalFormat,
						pixelType, clear, false, 0.0f, 0.0f);
				} else {
					int height = Integer.parseInt(parts[7]);
					int depth = Integer.parseInt(parts[8]);
					spec = new ComputePlan.CustomImageSpec(name, slot, ComputePlan.ImageDimension.THREE,
						new ComputePlan.Dimensions(width, height, depth), ComputePlan.Access.READ_WRITE, format,
						internalFormat, pixelType, clear, false, 0.0f, 0.0f);
				}
			}
			computeImages.put(name, spec);
		} catch (IllegalArgumentException exception) {
			raw.put(key, value);
		}
	}

	private ComputePlan.DispatchSpec dispatch(String value, boolean render, String key) {
		String[] parts = value.trim().split(" +");
		try {
			if (render && parts.length == 2) {
				return new ComputePlan.RenderRelative(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
			}
			if (!render && parts.length == 3) {
				return new ComputePlan.Absolute(new ComputePlan.Dimensions(Integer.parseInt(parts[0]),
						Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
			}
		} catch (IllegalArgumentException exception) {
			return null;
		}
		return null;
	}

	private DispatchSpecValue indirect(String value) {
		String[] parts = value.trim().split(" +");
		try {
			if (parts.length != 2) return null;
			int buffer = Integer.parseInt(parts[0]);
			long offset = Long.parseLong(parts[1]);
			if (buffer < 0) return null;
			return new DispatchSpecValue(new ComputePlan.Indirect("bufferObject." + buffer, offset));
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private record DispatchSpecValue(ComputePlan.DispatchSpec spec) {
	}

	private static Boolean booleanValue(String value) {
		if ("true".equals(value) || "1".equals(value)) {
			return Boolean.TRUE;
		}
		if ("false".equals(value) || "0".equals(value)) {
			return Boolean.FALSE;
		}
		return null;
	}

	private static List<String> list(String value) {
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			return List.of();
		}
		List<String> result = new ArrayList<>();
		for (String element : trimmed.split(" +")) {
			if (!element.isEmpty()) {
				result.add(element);
			}
		}
		return List.copyOf(result);
	}

	static Map<String, String> parse(String source) {
		Map<String, String> properties = new LinkedHashMap<>();
		String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
		List<String> logicalLines = new ArrayList<>();
		StringBuilder logical = new StringBuilder();
		for (String physical : normalized.split("\n", -1)) {
			String line = physical;
			if (logical.length() > 0) {
				int first = 0;
				while (first < line.length() && Character.isWhitespace(line.charAt(first))) first++;
				line = line.substring(first);
			}
			logical.append(line);
			if (continues(line)) {
				logical.setLength(logical.length() - 1);
				continue;
			}
			logicalLines.add(logical.toString());
			logical.setLength(0);
		}
		if (logical.length() > 0) logicalLines.add(logical.toString());
		for (String line : logicalLines) {
			String trimmed = line.stripLeading();
			if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue;
			int separator = separator(trimmed);
			String key = separator < 0 ? trimmed : trimmed.substring(0, separator).stripTrailing();
			String value = "";
			if (separator >= 0) {
				int start = separator + 1;
				while (start < trimmed.length() && Character.isWhitespace(trimmed.charAt(start))) start++;
				if (start < trimmed.length() && (trimmed.charAt(start) == '=' || trimmed.charAt(start) == ':')) start++;
				while (start < trimmed.length() && Character.isWhitespace(trimmed.charAt(start))) start++;
				value = trimmed.substring(start);
			}
			if (!key.isEmpty()) properties.put(unescape(key), unescape(value));
		}
		return properties;
	}

	private static boolean continues(String line) {
		int backslashes = 0;
		for (int index = line.length() - 1; index >= 0 && line.charAt(index) == '\\'; index--) backslashes++;
		return (backslashes & 1) == 1;
	}

	private static int separator(String line) {
		boolean escaped = false;
		for (int index = 0; index < line.length(); index++) {
			char character = line.charAt(index);
			if (escaped) {
				escaped = false;
				continue;
			}
			if (character == '\\') {
				escaped = true;
				continue;
			}
			if (character == '=' || character == ':' || Character.isWhitespace(character)) {
				return index;
			}
		}
		return -1;
	}

	private static String unescape(String value) {
		StringBuilder result = new StringBuilder();
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character != '\\' || index + 1 >= value.length()) {
				result.append(character);
				continue;
			}
			char next = value.charAt(index + 1);
			switch (next) {
				case 'n' -> {
					result.append('\n');
					index++;
				}
				case 't' -> {
					result.append('\t');
					index++;
				}
				case 'r' -> {
					result.append('\r');
					index++;
				}
				case 'f' -> {
					result.append('\f');
					index++;
				}
				case 'u' -> {
					if (index + 5 < value.length()) {
						try {
							result.append((char) Integer.parseInt(value.substring(index + 2, index + 6), 16));
							index += 5;
						} catch (NumberFormatException exception) {
							result.append(next);
							index++;
						}
					} else {
						result.append(next);
						index++;
					}
				}
				default -> {
					result.append(next);
					index++;
				}
			}
		}
		return result.toString();
	}

	public Map<String, String> raw() {
		return Map.copyOf(raw);
	}

	public Map<String, String> programEnabled() {
		return Map.copyOf(programEnabled);
	}

	public Map<String, Map<String, String>> uniforms() {
		return Map.copyOf(uniforms);
	}

	public Map<String, Map<String, String>> variables() {
		return Map.copyOf(variables);
	}

	public Map<String, Map<String, Boolean>> flips() {
		return Map.copyOf(flips);
	}

	public Map<String, String> blends() {
		return Map.copyOf(blends);
	}

	public Map<String, Map<String, String>> bufferBlends() {
		return Map.copyOf(bufferBlends);
	}

	public Map<String, String> alphaTests() {
		return Map.copyOf(alphaTests);
	}

	public Map<String, String> scales() {
		return Map.copyOf(scales);
	}

	public Map<String, String> bufferScales() {
		return Map.copyOf(bufferScales);
	}

	public Map<String, Map<String, String>> textures() {
		return Map.copyOf(textures);
	}

	public Map<String, String> images() {
		return Map.copyOf(images);
	}

	public Map<Integer, BufferObject> bufferObjects() {
		return Map.copyOf(bufferObjects);
	}

	public Map<Integer, ComputePlan.SsboSpec> ssbos() {
		Map<Integer, ComputePlan.SsboSpec> result = new LinkedHashMap<>();
		for (Map.Entry<Integer, BufferObject> entry : bufferObjects.entrySet()) {
			BufferObject buffer = entry.getValue();
			String name = buffer.name() == null ? "bufferObject." + entry.getKey() : buffer.name();
			result.put(entry.getKey(), new ComputePlan.SsboSpec(name, entry.getKey(), buffer.size(),
					ComputePlan.Access.READ_WRITE, buffer.relative(), buffer.scaleX(), buffer.scaleY()));
		}
		return Map.copyOf(result);
	}

	public Map<String, ComputePlan.CustomImageSpec> computeImages() {
		return Map.copyOf(computeImages);
	}

	public Optional<ComputePlan.DispatchSpec> workGroups() {
		return Optional.ofNullable(workGroups);
	}

	public Optional<ComputePlan.DispatchSpec> workGroupsRender() {
		return Optional.ofNullable(workGroupsRender);
	}

	public ComputePlan.DispatchSpec dispatch() {
		return workGroups != null ? workGroups : new ComputePlan.Default();
	}

	public Map<String, ComputePlan.DispatchSpec> indirectDispatches() {
		return Map.copyOf(indirectDispatches);
	}

	public Map<String, ComputePlan.DispatchSpec> indirect() {
		return indirectDispatches();
	}

	public Map<Integer, ComputePlan.SsboSpec> parsedBufferObjects() {
		return ssbos();
	}

	public Map<String, ComputePlan.CustomImageSpec> parsedImages() {
		return computeImages();
	}

	public Map<String, String> customTextures() {
		return Map.copyOf(customTextures);
	}

	public List<String> screen() {
		return screen;
	}

	public Map<String, List<String>> screens() {
		return Map.copyOf(screens);
	}

	public Map<String, Integer> screenColumns() {
		return Map.copyOf(screenColumns);
	}

	public List<String> sliders() {
		return sliders;
	}

	public Map<String, List<String>> profiles() {
		return Map.copyOf(profiles);
	}

	public List<String> requiredFeatures() {
		return requiredFeatures;
	}

	public List<String> optionalFeatures() {
		return optionalFeatures;
	}

	public Optional<String> noiseTexture() {
		return Optional.ofNullable(noiseTexture);
	}

	public Optional<String> clouds() {
		return Optional.ofNullable(clouds);
	}

	public Optional<String> shadowCulling() {
		return Optional.ofNullable(shadowCulling);
	}

	public Optional<Boolean> weather() {
		return Optional.ofNullable(weather);
	}

	public Optional<Boolean> weatherParticles() {
		return Optional.ofNullable(weatherParticles);
	}

	public Optional<Boolean> oldLighting() {
		return Optional.ofNullable(oldLighting);
	}

	public Optional<Boolean> separateAo() {
		return Optional.ofNullable(separateAo);
	}

	public Optional<Boolean> sun() {
		return Optional.ofNullable(sun);
	}

	public Optional<Boolean> moon() {
		return Optional.ofNullable(moon);
	}

	public Optional<Boolean> shadowTerrain() {
		return Optional.ofNullable(shadowTerrain);
	}

	public Optional<Boolean> shadowTranslucent() {
		return Optional.ofNullable(shadowTranslucent);
	}

	public Optional<Boolean> shadowEntities() {
		return Optional.ofNullable(shadowEntities);
	}

	public Optional<Boolean> shadowBlockEntities() {
		return Optional.ofNullable(shadowBlockEntities);
	}

	public Optional<Boolean> allowConcurrentCompute() {
		return Optional.ofNullable(allowConcurrentCompute);
	}

	public Optional<Boolean> prepareBeforeShadow() {
		return Optional.ofNullable(prepareBeforeShadow);
	}

	public Optional<Boolean> supportsColorCorrection() {
		return Optional.ofNullable(supportsColorCorrection);
	}

	public record BufferObject(long size, String name, boolean relative, float scaleX, float scaleY) {
	}
}
