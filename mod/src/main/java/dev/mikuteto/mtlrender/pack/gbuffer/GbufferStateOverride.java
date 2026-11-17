package dev.mikuteto.mtlrender.pack.gbuffer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.platform.CompareOp;
import dev.mikuteto.mtlrender.pack.ShaderProperties;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

public final class GbufferStateOverride {
	private static final Logger LOGGER = LogUtils.getLogger();

	private final AlphaTest alphaTest;
	private final BlendOverride programBlend;
	private final Map<Integer, BlendOverride> bufferBlends;

	private GbufferStateOverride(AlphaTest alphaTest, BlendOverride programBlend, Map<Integer, BlendOverride> bufferBlends) {
		this.alphaTest = alphaTest;
		this.programBlend = programBlend;
		this.bufferBlends = Map.copyOf(bufferBlends);
	}

	public static Optional<GbufferStateOverride> forProgram(ShaderProperties properties, String program,
		List<Integer> outputBuffers) {
		AlphaTest alpha = alpha(properties.alphaTests().get(program), program);
		BlendOverride blend = blend(properties.blends().get(program), program);
		Map<Integer, BlendOverride> buffers = new LinkedHashMap<>();
		Map<String, String> configured = properties.bufferBlends().get(program);
		if (configured != null) {
			for (Map.Entry<String, String> entry : configured.entrySet()) {
				Integer buffer = buffer(entry.getKey());
				if (buffer == null) {
					LOGGER.warn("Ignoring invalid buffer blend target for {}: {}", program, entry.getKey());
					continue;
				}
				BlendOverride parsed = blend(entry.getValue(), program + "." + entry.getKey());
				if (parsed != null) {
					buffers.put(buffer, parsed);
				}
			}
		}
		if (alpha == null && blend == null && buffers.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new GbufferStateOverride(alpha, blend, buffers));
	}

	public Optional<AlphaTest> alphaTest() {
		return Optional.ofNullable(alphaTest);
	}

	public ColorTargetState colorTarget(int slot, ColorTargetState vanilla, List<Integer> outputBuffers) {
		BlendOverride override = null;
		if (slot >= 0 && slot < outputBuffers.size()) {
			Integer buffer = outputBuffers.get(slot);
			override = buffer == null ? null : bufferBlends.get(buffer);
		}
		if (override == null) {
			override = programBlend;
		}
		if (override == null || vanilla == null) {
			return vanilla;
		}
		return new ColorTargetState(override.enabled() ? Optional.of(override.blendFunction()) : Optional.empty(),
			vanilla.format(), vanilla.writeMask());
	}

	public Map<Integer, BlendOverride> bufferBlends() {
		return bufferBlends;
	}

	public record AlphaTest(CompareOp function, float reference) {
	}

	public record BlendOverride(boolean enabled, BlendFunction blendFunction) {
		public BlendOverride {
			if (enabled && blendFunction == null) {
				throw new IllegalArgumentException("enabled blend override needs a function");
			}
		}

		public static BlendOverride off() {
			return new BlendOverride(false, null);
		}
	}

	private static AlphaTest alpha(String value, String name) {
		if (value == null || value.trim().equalsIgnoreCase("off") || value.trim().equalsIgnoreCase("false")) {
			return null;
		}
		String[] parts = value.trim().split("\\s+");
		if (parts.length != 2) {
			LOGGER.warn("Ignoring invalid alpha test for {}: {}", name, value);
			return null;
		}
		CompareOp compare = switch (parts[0].toUpperCase(java.util.Locale.ROOT)) {
			case "NEVER" -> CompareOp.NEVER_PASS;
			case "LESS" -> CompareOp.LESS_THAN;
			case "EQUAL" -> CompareOp.EQUAL;
			case "LEQUAL" -> CompareOp.LESS_THAN_OR_EQUAL;
			case "GREATER" -> CompareOp.GREATER_THAN;
			case "NOTEQUAL" -> CompareOp.NOT_EQUAL;
			case "GEQUAL" -> CompareOp.GREATER_THAN_OR_EQUAL;
			case "ALWAYS", "GL_ALWAYS" -> CompareOp.ALWAYS_PASS;
			default -> null;
		};
		try {
			float reference = Float.parseFloat(parts[1]);
			if (compare == null || !Float.isFinite(reference)) {
				throw new NumberFormatException();
			}
			return new AlphaTest(compare, reference);
		} catch (NumberFormatException exception) {
			LOGGER.warn("Ignoring invalid alpha test for {}: {}", name, value);
			return null;
		}
	}

	private static BlendOverride blend(String value, String name) {
		if (value == null) {
			return null;
		}
		if (GbufferBlendParser.isOff(value)) {
			return BlendOverride.off();
		}
		Optional<BlendFunction> parsed = GbufferBlendParser.parse(value);
		if (parsed.isEmpty()) {
			LOGGER.warn("Ignoring invalid blend override for {}: {}", name, value);
			return null;
		}
		return new BlendOverride(true, parsed.get());
	}

	private static Integer buffer(String value) {
		String name = value.toLowerCase(java.util.Locale.ROOT);
		if (name.startsWith("colortex")) {
			name = name.substring("colortex".length());
		} else {
			name = switch (name) {
				case "gcolor" -> "0";
				case "gdepth" -> "1";
				case "gnormal" -> "2";
				case "composite" -> "3";
				default -> name;
			};
		}
		try {
			int index = Integer.parseInt(name);
			return index >= 0 && index < ColorTargetState.MAX_COLOR_TARGETS ? index : null;
		} catch (NumberFormatException exception) {
			return null;
		}
	}
}
