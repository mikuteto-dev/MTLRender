package dev.mikuteto.mtlrender.pathtracing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import dev.mikuteto.mtlrender.mixin.SingleVariantAccess;
import dev.mikuteto.mtlrender.mixin.SpriteContentsAccess;
import dev.mikuteto.mtlrender.mixin.WeightedVariantsAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.blaze3d.platform.NativeImage;

public final class BlockStateAlbedo {
	private static final float FALLBACK = 1.0F;
	private static final AtomicReference<Material[]> MATERIALS = new AtomicReference<>(new Material[0]);

	private BlockStateAlbedo() {
	}

	public static void rebuild(ModelManager manager) {
		int size = Block.BLOCK_STATE_REGISTRY.size();
		Material[] next = new Material[size];
		BlockStateModelSet models = manager.getBlockStateModelSet();
		for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				int id = Block.BLOCK_STATE_REGISTRY.getId(state);
				if (id >= 0 && id < next.length) {
					next[id] = material(state, models.get(state));
				}
			}
		}
		for (int id = 0; id < next.length; id++) {
			if (next[id] == null) {
				next[id] = fallback(0);
			}
		}
		MATERIALS.set(next);
	}

	public static Material lookup(int stateId) {
		Material[] materials = MATERIALS.get();
		return stateId >= 0 && stateId < materials.length && materials[stateId] != null
			? materials[stateId]
			: fallback(0);
	}

	public static float srgbToLinear(float value) {
		return value <= 0.04045F ? value / 12.92F : (float) Math.pow((value + 0.055F) / 1.055F, 2.4F);
	}

	public static float linearToSrgb(float value) {
		return value <= 0.0031308F ? value * 12.92F : 1.055F * (float) Math.pow(value, 1.0F / 2.4F) - 0.055F;
	}

	public static Sample alphaWeightedAverage(int[] argb) {
		float red = 0.0F;
		float green = 0.0F;
		float blue = 0.0F;
		float weight = 0.0F;
		for (int pixel : argb) {
			float alpha = ARGB.alpha(pixel) / 255.0F;
			weight += alpha;
			red += srgbToLinear(ARGB.red(pixel) / 255.0F) * alpha;
			green += srgbToLinear(ARGB.green(pixel) / 255.0F) * alpha;
			blue += srgbToLinear(ARGB.blue(pixel) / 255.0F) * alpha;
		}
		return weight == 0.0F ? new Sample(FALLBACK, FALLBACK, FALLBACK, 0.0F) : new Sample(red / weight, green / weight, blue / weight, weight / argb.length);
	}

	private static Material material(BlockState state, BlockStateModel model) {
		try {
			List<Variant> variants = variants(model);
			if (variants == null || variants.isEmpty()) {
				return fallback(state.getLightEmission());
			}
			float red = 0.0F;
			float green = 0.0F;
			float blue = 0.0F;
			float weight = 0.0F;
			for (Variant variant : variants) {
				for (BlockStateModelPart part : variant.parts()) {
					for (Direction direction : Direction.values()) {
						for (BakedQuad quad : part.getQuads(direction)) {
							Sample sample = quadSample(state, quad);
							float contribution = sample.weight() * variant.weight();
							red += sample.red() * contribution;
							green += sample.green() * contribution;
							blue += sample.blue() * contribution;
							weight += contribution;
						}
					}
					for (BakedQuad quad : part.getQuads(null)) {
						Sample sample = quadSample(state, quad);
						float contribution = sample.weight() * variant.weight();
						red += sample.red() * contribution;
						green += sample.green() * contribution;
						blue += sample.blue() * contribution;
						weight += contribution;
					}
				}
			}
			return weight == 0.0F ? fallback(state.getLightEmission()) : new Material(red / weight, green / weight, blue / weight, state.getLightEmission());
		} catch (RuntimeException exception) {
			return fallback(state.getLightEmission());
		}
	}

	public static Sample multiplyTint(Sample sample, int tint) {
		return new Sample(sample.red() * srgbToLinear(ARGB.red(tint) / 255.0F), sample.green() * srgbToLinear(ARGB.green(tint) / 255.0F), sample.blue() * srgbToLinear(ARGB.blue(tint) / 255.0F), sample.weight());
	}

	private static Sample quadSample(BlockState state, BakedQuad quad) {
		TextureAtlasSprite sprite = quad.materialInfo().sprite();
		if (sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
			return new Sample(FALLBACK, FALLBACK, FALLBACK, 0.0F);
		}
		NativeImage image = ((SpriteContentsAccess) (Object) sprite.contents()).mtlrender$image();
		Sample sample = sample(image);
		BlockTintSource source = Minecraft.getInstance().getBlockColors().getTintSource(state, quad.materialInfo().tintIndex());
		int tint = source == null ? 0xFFFFFFFF : source.color(state);
		return multiplyTint(sample, tint);
	}

	private static Sample sample(NativeImage image) {
		int[] pixels = new int[image.getWidth() * image.getHeight()];
		int index = 0;
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				pixels[index++] = image.getPixel(x, y);
			}
		}
		return alphaWeightedAverage(pixels);
	}

	private static List<Variant> variants(BlockStateModel model) {
		if (model instanceof SingleVariant) {
			return List.of(new Variant(1, List.of(((SingleVariantAccess) model).mtlrender$model())));
		}
		if (model instanceof WeightedVariants) {
			List<Variant> result = new ArrayList<>();
			for (Weighted<BlockStateModel> entry : ((WeightedVariantsAccess) model).mtlrender$list().unwrap()) {
				if (!(entry.value() instanceof SingleVariant single)) {
					return null;
				}
				result.add(new Variant(entry.weight(), List.of(((SingleVariantAccess) single).mtlrender$model())));
			}
			return result;
		}
		return null;
	}

	private static Material fallback(int emission) {
		return new Material(FALLBACK, FALLBACK, FALLBACK, Math.max(0, Math.min(15, emission)));
	}

	public record Material(float red, float green, float blue, int emission) {
	}

	public record Sample(float red, float green, float blue, float weight) {
	}

	private record Variant(int weight, List<BlockStateModelPart> parts) {
	}
}
