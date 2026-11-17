package dev.mikuteto.mtlrender.pack;

import dev.mikuteto.mtlrender.pack.ids.IdMap;
import dev.mikuteto.mtlrender.pack.precision.PrecisionPlanner;
import dev.mikuteto.mtlrender.pack.precision.PrecisionPolicy;
import dev.mikuteto.mtlrender.pack.target.RenderTargetPlan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.mikuteto.mtlrender.pack.texture.TextureLoader;
import dev.mikuteto.mtlrender.pack.texture.TextureSpec;

public final class ShaderPack {
	private final Map<String, String> standardMacros;
	private final PackOptions options;
	private final ShaderProperties properties;
	private final ProfileSet profileSet;
	private final ProfileSet.Profile currentProfile;
	private final Map<String, String> optionValues;
	private final Map<String, ProgramSet> programSets;
	private final List<TextureSpec> textures;
	private final Map<String, String> dimensionMap;
	private final String baseFolder;
	private final IdMap idMap;
	private final RenderTargetPlan targetPlan;
	private final PrecisionPlanner.EffectivePlan precisionPlan;

	private ShaderPack(Map<String, String> standardMacros, PackOptions options, ShaderProperties properties,
					   ProfileSet profileSet, ProfileSet.Profile currentProfile, Map<String, String> optionValues,
					   Map<String, ProgramSet> programSets, List<TextureSpec> textures,
					   Map<String, String> dimensionMap, String baseFolder, IdMap idMap,
					   PrecisionPolicy precisionPolicy) {
		this.standardMacros = ordered(standardMacros);
		this.options = options;
		this.properties = properties;
		this.profileSet = profileSet;
		this.currentProfile = currentProfile;
		this.optionValues = ordered(optionValues);
		this.programSets = ordered(programSets);
		this.textures = List.copyOf(textures);
		this.dimensionMap = ordered(dimensionMap);
		this.baseFolder = baseFolder;
		this.idMap = idMap;
		this.targetPlan = RenderTargetPlan.build(this);
		this.precisionPlan = PrecisionPlanner.plan(targetPlan, this, precisionPolicy);
	}

	public static ShaderPack load(Path packPath, Map<String, String> requestedOptions) throws IOException {
		return load(packPath, requestedOptions, PrecisionPolicy.fromSystemProperties());
	}

	public static ShaderPack load(Path packPath, Map<String, String> requestedOptions,
		PrecisionPolicy precisionPolicy) throws IOException {
		try (PackSource source = PackSource.open(packPath)) {
			List<String> roots = new ArrayList<>();
			for (String path : source.walk("/")) {
				if (isShaderSource(path)) {
					roots.add(path);
				}
			}

			Includes includes = Includes.build(source, roots);
			PackOptions options = PackOptions.discover(includes);
			Map<String, String> standardMacros = StandardMacros.environment();

			String dimensionSource = source.read("/dimension.properties", StandardCharsets.ISO_8859_1).orElse("");
			String dimensionPreprocessed = PropertiesPreprocessor.preprocess(dimensionSource, standardMacros);
			Map<String, String> dimensionProperties = ShaderProperties.parse(dimensionPreprocessed);

			Map<String, String> dimensionMap = new LinkedHashMap<>();
			Set<String> dimensionFolders = new LinkedHashSet<>();
			String baseFolder = "";
			boolean hasDimensionEntries = false;

			if (!dimensionSource.isEmpty()) {
				for (Map.Entry<String, String> entry : dimensionProperties.entrySet()) {
					String key = entry.getKey();
					if (!key.startsWith("dimension.")) {
						continue;
					}
					String folder = key.substring("dimension.".length());
					List<String> dimensions = split(entry.getValue());
					if (!dimensions.isEmpty()) {
						hasDimensionEntries = true;
						dimensionFolders.add(folder);
					}
					for (String dimension : dimensions) {
						if ("*".equals(dimension)) {
							baseFolder = folder;
						} else {
							dimensionMap.put(dimension, folder);
						}
					}
				}
			}

			if (!hasDimensionEntries) {
				dimensionMap.clear();
				if (source.isDirectory("/world0")) {
					dimensionFolders.add("world0");
					dimensionMap.put("minecraft:overworld", "world0");
					baseFolder = "world0";
				}
				if (source.isDirectory("/world-1")) {
					dimensionFolders.add("world-1");
					dimensionMap.put("minecraft:the_nether", "world-1");
				}
				if (source.isDirectory("/world1")) {
					dimensionFolders.add("world1");
					dimensionMap.put("minecraft:the_end", "world1");
				}
			}

			String propertiesSource = source.read("/shaders.properties", StandardCharsets.ISO_8859_1).orElse("");
			Map<String, String> initialValues = new LinkedHashMap<>();
			if (requestedOptions != null) {
				initialValues.putAll(requestedOptions);
			}

			Map<String, String> initialMacros = new LinkedHashMap<>(standardMacros);
			initialMacros.putAll(optionMacros(options, initialValues));
			String initialPreprocessed = PropertiesPreprocessor.preprocess(propertiesSource, initialMacros);
			ShaderProperties initialProperties = new ShaderProperties(propertiesSource, initialPreprocessed);

			ProfileSet profileSet = ProfileSet.from(initialProperties.profiles(), options.allOptions());
			ProfileSet.Profile selected = null;
			String requestedProfile = initialValues.get("profile");
			if (requestedProfile != null) {
				selected = profileSet.select(requestedProfile).orElse(null);
			}
			if (selected == null && !initialValues.isEmpty()) {
				selected = profileSet.match(initialValues, options.allOptions()).orElse(null);
			}

			Map<String, String> effectiveValues = new LinkedHashMap<>(initialValues);
			if (selected != null && requestedProfile != null) {
				for (Map.Entry<String, String> entry : selected.options().entrySet()) {
					effectiveValues.putIfAbsent(entry.getKey(), entry.getValue());
				}
				for (PackOption option : options.allOptions().values()) {
					if (option.isBoolean()) {
						effectiveValues.putIfAbsent(option.name(), Boolean.toString(option.booleanDefaultValue()));
					} else {
						effectiveValues.putIfAbsent(option.name(), option.defaultValue());
					}
				}
			}

			Map<String, String> finalMacros = new LinkedHashMap<>(standardMacros);
			finalMacros.putAll(optionMacros(options, effectiveValues));
			String finalPreprocessed = PropertiesPreprocessor.preprocess(propertiesSource, finalMacros);
			ShaderProperties properties = new ShaderProperties(propertiesSource, finalPreprocessed);

			IdMap idMap = IdMap.load(source, finalMacros);
			Set<String> disabledPrograms = new LinkedHashSet<>();
			if (selected != null) {
				disabledPrograms.addAll(selected.disabledPrograms());
			}

			Includes prepared = options.apply(effectiveValues);
			Map<String, ProgramSet> programSets = new LinkedHashMap<>();
			programSets.put("", new ProgramSet(source, prepared, properties, finalMacros, "", disabledPrograms));
			for (String folder : dimensionFolders) {
				if (source.isDirectory("/" + folder)) {
					programSets.put(folder, new ProgramSet(source, prepared, properties, finalMacros,
						folder, disabledPrograms));
				}
			}

			List<TextureSpec> textures = TextureLoader.load(source, properties);
			return new ShaderPack(standardMacros, options, properties, profileSet, selected,
				effectiveValues, programSets, textures, dimensionMap, baseFolder, idMap, precisionPolicy);
		}
	}

	private static boolean isShaderSource(String path) {
		return path.endsWith(".vsh") || path.endsWith(".gsh") || path.endsWith(".tcs")
			|| path.endsWith(".tes") || path.endsWith(".fsh") || path.endsWith(".csh");
	}

	private static List<String> split(String value) {
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			return List.of();
		}
		List<String> result = new ArrayList<>();
		for (String element : trimmed.split("\\s+")) {
			if (!element.isEmpty()) {
				result.add(element);
			}
		}
		return result;
	}

	private static Map<String, String> optionMacros(PackOptions options, Map<String, String> values) {
		Map<String, String> macros = new LinkedHashMap<>();
		for (PackOption option : options.allOptions().values()) {
			if (option.isBoolean()) {
				Boolean explicit = booleanValue(values.get(option.name()));
				boolean value = explicit == null ? option.booleanDefaultValue() : explicit;
				if (value) {
					macros.put(option.name(), "");
				}
			} else {
				String value = values.get(option.name());
				macros.put(option.name(), value == null ? option.defaultValue() : value);
			}
		}
		return macros;
	}

	private static <V> Map<String, V> ordered(Map<String, V> values) {
		return Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	private static Boolean booleanValue(String value) {
		if ("true".equals(value)) {
			return Boolean.TRUE;
		}
		if ("false".equals(value)) {
			return Boolean.FALSE;
		}
		return null;
	}

	public Map<String, String> standardMacros() {
		return standardMacros;
	}

	public PackOptions options() {
		return options;
	}

	public ShaderProperties properties() {
		return properties;
	}

	public ProfileSet profileSet() {
		return profileSet;
	}

	public Optional<ProfileSet.Profile> currentProfile() {
		return Optional.ofNullable(currentProfile);
	}

	public Map<String, String> optionValues() {
		return optionValues;
	}

	public Map<String, ProgramSet> programSets() {
		return programSets;
	}

	public List<TextureSpec> textures() {
		return textures;
	}

	public Map<String, String> dimensionMap() {
		return dimensionMap;
	}

	public String baseFolder() {
		return baseFolder;
	}

	public IdMap idMap() {
		return idMap;
	}

	public RenderTargetPlan targetPlan() {
		return targetPlan;
	}

	public PrecisionPlanner.EffectivePlan precisionPlan() {
		return precisionPlan;
	}

	public ProgramSet rootProgramSet() {
		return programSets.get("");
	}

	public ProgramSet programSet(String dimension) {
		String folder = dimensionMap.getOrDefault(dimension, baseFolder);
		ProgramSet set = programSets.get(folder);
		if (set != null) {
			return set;
		}
		return programSets.get("");
	}
}
