package dev.mikuteto.mtlrender.pack.ids;

import dev.mikuteto.mtlrender.pack.PackSource;
import dev.mikuteto.mtlrender.pack.PropertiesPreprocessor;
import net.minecraft.core.Holder;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IdMap {
	private final int[] blockStateIds;
	private final int[] itemIds;
	private final int[] entityIds;
	private final int[] blockLayers;

	private IdMap(Resolved resolved) {
		blockStateIds = resolved.blockStateIds();
		itemIds = resolved.itemIds();
		entityIds = resolved.entityIds();
		blockLayers = resolved.blockLayers();
	}

	public static IdMap fromResolved(Resolved resolved) {
		return new IdMap(resolved);
	}

	public static IdMap load(PackSource source, Map<String, String> macros) {
		String blockSource = source.read("/block.properties", StandardCharsets.ISO_8859_1).orElse("");
		String itemSource = source.read("/item.properties", StandardCharsets.ISO_8859_1).orElse("");
		String entitySource = source.read("/entity.properties", StandardCharsets.ISO_8859_1).orElse("");
		Parsed parsed = parse(blockSource, itemSource, entitySource, macros);
		if (parsed.blocks().isEmpty() && parsed.items().isEmpty() && parsed.entities().isEmpty()
			&& parsed.layers().isEmpty()) {
			return new IdMap(new Resolved(new int[0], new int[0], new int[0], new int[0]));
		}
		return new IdMap(resolveMinecraft(parsed));
	}

	public static Parsed parse(String blockSource, String itemSource, String entitySource,
			Map<String, String> macros) {
		return new Parsed(
			parseEntries(blockSource, "block.", macros),
			parseEntries(itemSource, "item.", macros),
			parseEntries(entitySource, "entity.", macros),
			parseLayers(blockSource, macros));
	}

	public static Resolved resolve(Parsed parsed, List<BlockStateName> blockStates,
			List<String> items, List<String> entities, Map<String, List<String>> tags) {
		Map<String, List<BlockStateName>> statesByBlock = new LinkedHashMap<>();
		int blockLength = 0;
		for (BlockStateName state : blockStates) {
			statesByBlock.computeIfAbsent(normalize(state.block()), ignored -> new ArrayList<>()).add(state);
			if (state.id() >= 0) {
				blockLength = Math.max(blockLength, state.id() + 1);
			}
		}

		int[] blockIds = filled(blockLength);
		applyBlocks(parsed.blocks(), false, statesByBlock, tags, blockIds);
		applyBlocks(parsed.blocks(), true, statesByBlock, tags, blockIds);

		int[] layerIds = filled(blockLength);
		for (LayerEntry layer : parsed.layers()) {
			for (String block : blocksFor(layer.entry(), tags)) {
				for (BlockStateName state : statesByBlock.getOrDefault(block, List.of())) {
					if (state.id() >= 0 && matches(state, layer.entry().properties())) {
						layerIds[state.id()] = layer.layer().ordinal();
					}
				}
			}
		}

		return new Resolved(blockIds, mapNames(parsed.items(), items), mapNames(parsed.entities(), entities), layerIds);
	}

	public int blockStateId(int stateId) {
		return value(blockStateIds, stateId);
	}

	public int itemId(int registryId) {
		return value(itemIds, registryId);
	}

	public int entityId(int registryId) {
		return value(entityIds, registryId);
	}

	public int blockLayer(int stateId) {
		return value(blockLayers, stateId);
	}

	public int[] blockStateIds() {
		return blockStateIds.clone();
	}

	public int[] itemIds() {
		return itemIds.clone();
	}

	public int[] entityIds() {
		return entityIds.clone();
	}

	public int[] blockLayers() {
		return blockLayers.clone();
	}

	private static void applyBlocks(List<Entry> entries, boolean tagsOnly,
			Map<String, List<BlockStateName>> statesByBlock, Map<String, List<String>> tags, int[] ids) {
		for (Entry entry : entries) {
			if (entry.tag() != tagsOnly) {
				continue;
			}
			for (String block : blocksFor(entry, tags)) {
				for (BlockStateName state : statesByBlock.getOrDefault(block, List.of())) {
					if (state.id() >= 0 && ids[state.id()] < 0 && matches(state, entry.properties())) {
						ids[state.id()] = entry.value();
					}
				}
			}
		}
	}

	private static Resolved resolveMinecraft(Parsed parsed) {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		List<BlockStateName> states = new ArrayList<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			Identifier identifier = BuiltInRegistries.BLOCK.getKey(block);
			if (identifier == null) {
				continue;
			}
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				Map<String, String> properties = new LinkedHashMap<>();
				for (Property.Value<?> value : state.getValues().toList()) {
					properties.put(value.property().getName(), value.valueName());
				}
				states.add(new BlockStateName(Block.BLOCK_STATE_REGISTRY.getId(state),
					identifier.toString(), properties));
			}
		}

		List<String> items = registryNames(BuiltInRegistries.ITEM);
		List<String> entities = registryNames(BuiltInRegistries.ENTITY_TYPE);
		Map<String, List<String>> tags = new LinkedHashMap<>();
		for (Entry entry : parsed.blocks()) {
			addTag(entry, tags);
		}
		return resolve(parsed, states, items, entities, tags);
	}

	private static void addTag(Entry entry, Map<String, List<String>> tags) {
		if (!entry.tag()) {
			return;
		}
		Identifier identifier = Identifier.tryParse(entry.name());
		if (identifier == null) {
			return;
		}
		TagKey<Block> tag = TagKey.create(Registries.BLOCK, identifier);
		List<String> blocks = new ArrayList<>();
		try {
			for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tag)) {
				Identifier block = BuiltInRegistries.BLOCK.getKey(holder.value());
				if (block != null) {
					blocks.add(block.toString());
				}
			}
		} catch (IllegalStateException exception) {
			return;
		}
		tags.put(normalize(entry.name()), blocks);
	}

	private static <T> List<String> registryNames(Iterable<T> values) {
		List<String> names = new ArrayList<>();
		for (T value : values) {
			if (value instanceof Item item) {
				setName(names, BuiltInRegistries.ITEM.getId(item), BuiltInRegistries.ITEM.getKey(item));
			} else if (value instanceof EntityType<?> entity) {
				setName(names, BuiltInRegistries.ENTITY_TYPE.getId(entity), BuiltInRegistries.ENTITY_TYPE.getKey(entity));
			}
		}
		return names;
	}

	private static void setName(List<String> names, int id, Identifier identifier) {
		if (id < 0 || identifier == null) {
			return;
		}
		while (names.size() <= id) {
			names.add("");
		}
		names.set(id, identifier.toString());
	}

	private static List<String> blocksFor(Entry entry, Map<String, List<String>> tags) {
		if (entry.tag()) {
			return tags.getOrDefault(normalize(entry.name()), List.of());
		}
		return List.of(normalize(entry.name()));
	}

	private static boolean matches(BlockStateName state, Map<String, String> expected) {
		for (Map.Entry<String, String> property : expected.entrySet()) {
			String actual = state.properties().get(property.getKey());
			if (actual == null || !property.getValue().equals(actual)) {
				return false;
			}
		}
		return true;
	}

	private static int[] mapNames(List<Entry> entries, List<String> names) {
		Map<String, Integer> ids = new LinkedHashMap<>();
		for (int index = 0; index < names.size(); index++) {
			ids.put(normalize(names.get(index)), index);
		}
		int[] result = filled(names.size());
		for (Entry entry : entries) {
			if (entry.tag()) {
				continue;
			}
			for (String name : entry.names()) {
				Integer index = ids.get(normalize(name));
				if (index != null) {
					result[index] = entry.value();
				}
			}
		}
		return result;
	}

	private static List<LayerEntry> parseLayers(String source, Map<String, String> macros) {
		List<LayerEntry> result = new ArrayList<>();
		for (PropertyLine line : properties(source, macros)) {
			if (!line.key().startsWith("layer.")) {
				continue;
			}
			Layer layer = Layer.parse(line.key().substring("layer.".length()));
			if (layer == null) {
				continue;
			}
			for (String token : line.value().split("\\s+")) {
				if (!token.isEmpty() && !token.startsWith("%")) {
					result.add(new LayerEntry(layer, blockEntry(0, token)));
				}
			}
		}
		return List.copyOf(result);
	}

	private static List<Entry> parseEntries(String source, String prefix, Map<String, String> macros) {
		List<Entry> result = new ArrayList<>();
		for (PropertyLine line : properties(source, macros)) {
			if (!line.key().startsWith(prefix)) {
				continue;
			}
			int value;
			try {
				value = Integer.parseInt(line.key().substring(prefix.length()).trim());
			} catch (NumberFormatException exception) {
				continue;
			}
			for (String token : line.value().split("\\s+")) {
				if (token.isEmpty()) {
					continue;
				}
				if (prefix.equals("block.")) {
					result.add(blockEntry(value, token));
				} else if (!token.contains("=")) {
					result.add(new Entry(value, List.of(normalize(token)), Map.of(), false));
				}
			}
		}
		return List.copyOf(result);
	}

	private static Entry blockEntry(int value, String token) {
		boolean tag = token.startsWith("%");
		String valueToken = tag ? token.substring(1) : token;
		String[] parts = valueToken.split(":");
		int nameEnd;
		if (parts.length == 1) {
			nameEnd = 1;
		} else if (parts[1].contains("=")) {
			nameEnd = 1;
		} else {
			nameEnd = 2;
		}
		String name = nameEnd == 1 ? "minecraft:" + parts[0] : parts[0] + ":" + parts[1];
		Map<String, String> properties = new LinkedHashMap<>();
		for (int index = nameEnd; index < parts.length; index++) {
			String[] pair = parts[index].split("=", 2);
			if (pair.length == 2 && !pair[0].isEmpty() && !pair[1].isEmpty()) {
				properties.put(pair[0], pair[1]);
			}
		}
		return new Entry(value, List.of(normalize(name)), properties, tag);
	}

	private static List<PropertyLine> properties(String source, Map<String, String> macros) {
		String processed = PropertiesPreprocessor.preprocess(source, macros == null ? Map.of() : macros);
		List<PropertyLine> result = new ArrayList<>();
		for (String line : processed.split("\\R", -1)) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
				continue;
			}
			int separator = separator(trimmed);
			String key = separator < 0 ? trimmed : trimmed.substring(0, separator).trim();
			String value = separator < 0 ? "" : trimmed.substring(separator + 1).trim();
			if (!key.isEmpty()) {
				result.add(new PropertyLine(key, value));
			}
		}
		return result;
	}

	private static int separator(String line) {
		boolean escaped = false;
		for (int index = 0; index < line.length(); index++) {
			char character = line.charAt(index);
			if (escaped) {
				escaped = false;
			} else if (character == '\\') {
				escaped = true;
			} else if (character == '=' || character == ':') {
				return index;
			}
		}
		return -1;
	}

	private static String normalize(String name) {
		return name.indexOf(':') < 0 ? "minecraft:" + name : name;
	}

	private static int[] filled(int length) {
		int[] result = new int[length];
		Arrays.fill(result, -1);
		return result;
	}

	private static int value(int[] values, int index) {
		return index >= 0 && index < values.length ? values[index] : -1;
	}

	public record Parsed(List<Entry> blocks, List<Entry> items, List<Entry> entities,
		List<LayerEntry> layers) {
		public Parsed {
			blocks = List.copyOf(blocks);
			items = List.copyOf(items);
			entities = List.copyOf(entities);
			layers = List.copyOf(layers);
		}
	}

	public record Resolved(int[] blockStateIds, int[] itemIds, int[] entityIds, int[] blockLayers) {
		public Resolved {
			blockStateIds = blockStateIds.clone();
			itemIds = itemIds.clone();
			entityIds = entityIds.clone();
			blockLayers = blockLayers.clone();
		}
	}

	public record Entry(int value, List<String> names, Map<String, String> properties, boolean tag) {
		public Entry {
			names = List.copyOf(names);
			properties = Map.copyOf(properties);
		}

		String name() {
			return names.getFirst();
		}
	}

	public record LayerEntry(Layer layer, Entry entry) {
	}

	public record BlockStateName(int id, String block, Map<String, String> properties) {
		public BlockStateName {
			properties = Map.copyOf(properties);
		}
	}

	public enum Layer {
		SOLID,
		CUTOUT,
		CUTOUT_MIPPED,
		TRANSLUCENT;

		private static Layer parse(String value) {
			return switch (value) {
				case "solid" -> SOLID;
				case "cutout" -> CUTOUT;
				case "cutout_mipped" -> CUTOUT_MIPPED;
				case "translucent" -> TRANSLUCENT;
				default -> null;
			};
		}
	}

	private record PropertyLine(String key, String value) {
	}
}
