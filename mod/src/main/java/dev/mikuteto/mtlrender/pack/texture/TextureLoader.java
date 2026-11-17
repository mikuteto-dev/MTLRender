package dev.mikuteto.mtlrender.pack.texture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mikuteto.mtlrender.pack.PackSource;
import dev.mikuteto.mtlrender.pack.ShaderProperties;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextureLoader {
	private static final int DEFAULT_NOISE_TEXTURE_RESOLUTION = 256;
	private static final Pattern NOISE_RESOLUTION = Pattern.compile(
		"\\bconst\\s+int\\s+noiseTextureResolution\\s*=\\s*(-?\\d+)\\b");

	private TextureLoader() {
	}

	public static List<TextureSpec> load(PackSource source, ShaderProperties properties) throws IOException {
		return load(source, properties, noiseTextureResolution(source));
	}

	public static List<TextureSpec> load(PackSource source, ShaderProperties properties,
									int noiseTextureResolution) throws IOException {
		if (noiseTextureResolution < 1) {
			throw new IOException("noiseTextureResolution must be positive");
		}

		List<TextureSpec> textures = new ArrayList<>();
		Optional<String> noisePath = properties.noiseTexture();
		if (noisePath.isPresent()) {
			textures.add(loadEntry(source, "noisetex", null, noisePath.get(), true));
		} else {
			textures.add(defaultNoise(noiseTextureResolution));
		}

		List<Map.Entry<String, Map<String, String>>> stages = new ArrayList<>(properties.textures().entrySet());
		stages.sort(Map.Entry.comparingByKey());
		for (Map.Entry<String, Map<String, String>> stageEntry : stages) {
			Optional<TextureStage> stage = TextureStage.parse(stageEntry.getKey());
			if (stage.isEmpty()) {
				continue;
			}
			List<Map.Entry<String, String>> entries = new ArrayList<>(stageEntry.getValue().entrySet());
			entries.sort(Map.Entry.comparingByKey());
			for (Map.Entry<String, String> entry : entries) {
				textures.add(loadEntry(source, entry.getKey(), stage.get(), entry.getValue(), false));
			}
		}

		List<Map.Entry<String, String>> customTextures = new ArrayList<>(properties.customTextures().entrySet());
		customTextures.sort(Map.Entry.comparingByKey());
		for (Map.Entry<String, String> entry : customTextures) {
			textures.add(loadEntry(source, entry.getKey(), null, entry.getValue(), false));
		}
		return List.copyOf(textures);
	}

	private static TextureSpec loadEntry(PackSource source, String sampler, TextureStage stage,
									String definition, boolean noise) throws IOException {
		String value = definition.trim();
		if (value.isEmpty()) {
			throw new IOException("Empty texture definition for " + sampler);
		}
		String[] parts = value.split("\\s+");
		if (parts.length == 1) {
			Optional<TextureReference> reference = TextureReference.parse(parts[0]);
			if (reference.isPresent()) {
				return referenceSpec(sampler, stage, reference.get());
			}
			return png(source, sampler, stage, parts[0], noise);
		}
		return raw(source, sampler, stage, parts);
	}

	private static TextureSpec png(PackSource source, String sampler, TextureStage stage,
									String path, boolean noise) throws IOException {
		byte[] encoded = bytes(source, path);
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(encoded));
		if (image == null) {
			throw new IOException("Invalid PNG texture " + path);
		}
		byte[] pixels = rgba(image);
		Filter filter = filter(source, path, false, false);
		return new TextureSpec(sampler, stage, noise ? TextureSpec.Kind.NOISE : TextureSpec.Kind.PNG,
			TextureSpec.Target.TEXTURE_2D, image.getWidth(), image.getHeight(), 1,
			"RGBA8", "RGBA", "UNSIGNED_BYTE", filter.blur(), filter.clamp(), pixels,
			Optional.empty());
	}

	private static TextureSpec raw(PackSource source, String sampler, TextureStage stage,
									String[] parts) throws IOException {
		if (parts.length < 6 || parts.length > 8) {
			throw new IOException("Invalid raw texture definition for " + sampler);
		}
		TextureSpec.Target target;
		try {
			target = TextureSpec.Target.valueOf(parts[1].toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			throw new IOException("Invalid texture target " + parts[1], exception);
		}
		int dimensionCount = switch (target) {
			case TEXTURE_1D -> 6;
			case TEXTURE_2D -> 7;
			case TEXTURE_3D -> 8;
		};
		if (parts.length != dimensionCount) {
			throw new IOException("Invalid dimensions for " + target + " texture " + sampler);
		}
		int width = positive(parts[3], "width");
		int height = target == TextureSpec.Target.TEXTURE_1D ? 1 : positive(parts[4], "height");
		int depth = target == TextureSpec.Target.TEXTURE_3D ? positive(parts[5], "depth") : 1;
		String internalFormat = upperIdentifier(parts[2], "internal format");
		int pixelFormatIndex = switch (target) {
			case TEXTURE_1D -> 4;
			case TEXTURE_2D -> 5;
			case TEXTURE_3D -> 6;
		};
		int pixelTypeIndex = pixelFormatIndex + 1;
		String pixelFormat = upperIdentifier(parts[pixelFormatIndex], "pixel format");
		String pixelType = upperIdentifier(parts[pixelTypeIndex], "pixel type");
		int components = components(pixelFormat);
		int bytesPerComponent = bytesPerComponent(pixelType);
		long expected = (long) width * height * depth * components * bytesPerComponent;
		if (expected > Integer.MAX_VALUE) {
			throw new IOException("Raw texture is too large: " + sampler);
		}
		byte[] pixels = bytes(source, parts[0]);
		if (pixels.length < expected) {
			throw new IOException("Raw texture " + sampler + " was " + pixels.length
				+ " bytes; expected at least " + expected);
		}
		Filter filter = filter(source, parts[0], true, true);
		return new TextureSpec(sampler, stage, TextureSpec.Kind.RAW, target, width, height, depth,
			internalFormat, pixelFormat, pixelType, filter.blur(), filter.clamp(), pixels, Optional.empty());
	}

	private static TextureSpec referenceSpec(String sampler, TextureStage stage, TextureReference reference) {
		return new TextureSpec(sampler, stage, TextureSpec.Kind.REFERENCE, TextureSpec.Target.TEXTURE_2D,
			0, 0, 0, null, null, null, false, false, new byte[0], Optional.of(reference));
	}

	private static TextureSpec defaultNoise(int resolution) {
		Random random = new Random(0L);
		byte[] pixels = new byte[resolution * resolution * 4];
		for (int index = 0; index < pixels.length; index += 4) {
			int color = random.nextInt() | 0xff000000;
			pixels[index] = (byte) (color >>> 16);
			pixels[index + 1] = (byte) (color >>> 8);
			pixels[index + 2] = (byte) color;
			pixels[index + 3] = (byte) (color >>> 24);
		}
		return new TextureSpec("noisetex", null, TextureSpec.Kind.NOISE, TextureSpec.Target.TEXTURE_2D,
			resolution, resolution, 1, "RGBA8", "RGBA", "UNSIGNED_BYTE", true, false, pixels,
			Optional.empty());
	}

	private static byte[] rgba(BufferedImage image) {
		byte[] pixels = new byte[image.getWidth() * image.getHeight() * 4];
		int index = 0;
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int color = image.getRGB(x, y);
				pixels[index++] = (byte) (color >>> 16);
				pixels[index++] = (byte) (color >>> 8);
				pixels[index++] = (byte) color;
				pixels[index++] = (byte) (color >>> 24);
			}
		}
		return pixels;
	}

	private static byte[] bytes(PackSource source, String path) throws IOException {
		String normalized = path.startsWith("/") ? path.substring(1) : path;
		return source.readBytes(normalized).orElseThrow(() -> new IOException("Missing texture " + path));
	}

	private static Filter filter(PackSource source, String path, boolean defaultBlur, boolean defaultClamp) {
		boolean blur = defaultBlur;
		boolean clamp = defaultClamp;
		Optional<byte[]> metadata = source.readBytes(path + ".mcmeta");
		if (metadata.isEmpty()) {
			return new Filter(blur, clamp);
		}
		try {
			JsonElement root = JsonParser.parseString(new String(metadata.get(), StandardCharsets.UTF_8));
			if (!root.isJsonObject()) {
				return new Filter(blur, clamp);
			}
			JsonObject texture = root.getAsJsonObject().getAsJsonObject("texture");
			if (texture == null) {
				return new Filter(blur, clamp);
			}
			if (texture.has("blur")) {
				blur = texture.get("blur").getAsBoolean();
			}
			if (texture.has("clamp")) {
				clamp = texture.get("clamp").getAsBoolean();
			}
		} catch (RuntimeException ignored) {
			return new Filter(defaultBlur, defaultClamp);
		}
		return new Filter(blur, clamp);
	}

	private static int positive(String value, String name) throws IOException {
		try {
			int parsed = Integer.parseInt(value);
			if (parsed > 0) {
				return parsed;
			}
		} catch (NumberFormatException ignored) {
		}
		throw new IOException("Invalid " + name + " " + value);
	}

	private static String upperIdentifier(String value, String name) throws IOException {
		String result = value.toUpperCase(java.util.Locale.ROOT);
		if (!result.matches("[A-Z0-9_]+")) {
			throw new IOException("Invalid " + name + " " + value);
		}
		return result;
	}

	private static int components(String format) throws IOException {
		return switch (format) {
			case "RED", "RED_INTEGER" -> 1;
			case "RG", "RG_INTEGER" -> 2;
			case "RGB", "BGR", "RGB_INTEGER", "BGR_INTEGER" -> 3;
			case "RGBA", "BGRA", "RGBA_INTEGER", "BGRA_INTEGER" -> 4;
			default -> throw new IOException("Invalid pixel format " + format);
		};
	}

	private static int bytesPerComponent(String type) throws IOException {
		return switch (type) {
			case "BYTE", "UNSIGNED_BYTE", "UNSIGNED_BYTE_3_3_2", "UNSIGNED_BYTE_2_3_3_REV" -> 1;
			case "SHORT", "HALF_FLOAT", "UNSIGNED_SHORT", "UNSIGNED_SHORT_5_6_5",
				"UNSIGNED_SHORT_5_6_5_REV", "UNSIGNED_SHORT_4_4_4_4", "UNSIGNED_SHORT_4_4_4_4_REV",
				"UNSIGNED_SHORT_5_5_5_1", "UNSIGNED_SHORT_1_5_5_5_REV" -> 2;
			case "INT", "FLOAT", "UNSIGNED_INT", "UNSIGNED_INT_8_8_8_8", "UNSIGNED_INT_8_8_8_8_REV",
				"UNSIGNED_INT_10_10_10_2", "UNSIGNED_INT_2_10_10_10_REV", "UNSIGNED_INT_10F_11F_11F_REV",
				"UNSIGNED_INT_5_9_9_9_REV" -> 4;
			default -> throw new IOException("Invalid pixel type " + type);
		};
	}

	private static int noiseTextureResolution(PackSource source) {
		int resolution = DEFAULT_NOISE_TEXTURE_RESOLUTION;
		for (String path : source.walk("/")) {
			if (!isShaderSource(path)) {
				continue;
			}
			Optional<String> sourceText = source.read(path, StandardCharsets.UTF_8);
			if (sourceText.isEmpty()) {
				continue;
			}
			Matcher matcher = NOISE_RESOLUTION.matcher(sourceText.get());
			while (matcher.find()) {
				try {
					int parsed = Integer.parseInt(matcher.group(1));
					if (parsed > 0) {
						resolution = parsed;
					}
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return resolution;
	}

	private static boolean isShaderSource(String path) {
		return path.endsWith(".vsh") || path.endsWith(".gsh") || path.endsWith(".tcs")
			|| path.endsWith(".tes") || path.endsWith(".fsh") || path.endsWith(".csh");
	}

	private record Filter(boolean blur, boolean clamp) {
	}
}
