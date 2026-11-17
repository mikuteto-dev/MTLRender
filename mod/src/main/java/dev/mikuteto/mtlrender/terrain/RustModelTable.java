package dev.mikuteto.mtlrender.terrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import com.mojang.logging.LogUtils;
import dev.mikuteto.mtlrender.mixin.MultiPartModelAccess;
import dev.mikuteto.mtlrender.mixin.MultiPartSharedBakedStateAccess;
import dev.mikuteto.mtlrender.mixin.SingleVariantAccess;
import dev.mikuteto.mtlrender.mixin.WeightedVariantsAccess;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

public final class RustModelTable {
	private static final int TABLE_MAGIC = 0x4d54_4232;
	private static final int TABLE_VERSION = 2;
	private static final int STATE_FORCE_OPAQUE = 1 << 20;
	private static final int STATE_VIEW_BLOCKING = 1 << 21;
	private static final int STATE_COLLISION_FULL = 1 << 22;
	private static final int STATE_SOLID_RENDER = 1 << 23;
	private static final int QUAD_UNCULLED = 1 << 1;
	private static final int TABLE_VERTEX_BYTES = 24;
	private static final Logger LOGGER = LogUtils.getLogger();

	private RustModelTable() {
	}

	public static void export(ModelManager manager) {
		if (!RustMesher.enabled()) {
			return;
		}
		RustMesher.beginExport();
		BlockStateModelSet models = manager.getBlockStateModelSet();
		int states = 0;
		for (Block block : BuiltInRegistries.BLOCK) {
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				int id = Block.BLOCK_STATE_REGISTRY.getId(state);
				if (id < 0) {
					continue;
				}
				BlockStateModel model = models.get(state);
				if (!model.getClass().getName().startsWith("net.minecraft.")) {
					continue;
				}
				List<ModelSelector> selectors = modelSelectors(state, model);
				if (selectors == null) {
					continue;
				}
				int flags = model.materialFlags();
				if (state.isSolidRender()) {
					flags |= STATE_SOLID_RENDER | STATE_VIEW_BLOCKING;
				}
				if (state.isCollisionShapeFullBlock(null, BlockPos.ZERO)) {
					flags |= STATE_COLLISION_FULL;
				}
				if (!state.isSolidRender() && state.isViewBlocking(null, BlockPos.ZERO)) {
					flags |= STATE_VIEW_BLOCKING;
				}
				if (state.getBlock() instanceof LeavesBlock) {
					flags |= STATE_FORCE_OPAQUE;
				}
				flags |= Math.min(state.getLightEmission(), 15) << 9;
				flags |= Math.min(state.getLightDampening(), 15) << 13;
				RustMesher.exportState(id, flags, encode(state, flags, selectors));
				states++;
			}
		}
		RustMesher.endExport();
		LOGGER.info("Rust mesher model export completed for {} block states", states);
	}

	private static List<ModelSelector> modelSelectors(BlockState state, BlockStateModel model) {
		List<ModelVariant> variants = modelVariants(model);
		if (variants != null) {
			return List.of(new ModelSelector(List.of(stateId(state)), variants));
		}
		if (!(model instanceof MultiPartModel)) {
			return null;
		}
		try {
			MultiPartModelAccess multipart = (MultiPartModelAccess) model;
			MultiPartSharedBakedStateAccess shared = (MultiPartSharedBakedStateAccess) multipart.mtlrender$shared();
			BlockState owner = multipart.mtlrender$blockState();
			List<MultiPartModel.Selector<BlockStateModel>> bakedSelectors = shared.mtlrender$selectors();
			List<ModelSelector> selectors = new ArrayList<>(bakedSelectors.size());
			for (MultiPartModel.Selector<BlockStateModel> selector : bakedSelectors) {
				List<Integer> matches = new ArrayList<>();
				for (BlockState candidate : owner.getBlock().getStateDefinition().getPossibleStates()) {
					if (selector.condition().test(candidate)) {
						matches.add(stateId(candidate));
					}
				}
				if (matches.isEmpty() || selector.model() == null) {
					return null;
				}
				List<ModelVariant> childVariants = modelVariants(selector.model());
				if (childVariants == null) {
					return null;
				}
				selectors.add(new ModelSelector(matches, childVariants));
			}
			return selectors.isEmpty() ? null : selectors;
		} catch (ClassCastException exception) {
			return null;
		}
	}

	private static int stateId(BlockState state) {
		return Block.BLOCK_STATE_REGISTRY.getId(state);
	}

	private static List<ModelVariant> modelVariants(BlockStateModel model) {
		if (model instanceof SingleVariant) {
			BlockStateModelPart part = ((SingleVariantAccess) model).mtlrender$model();
			return List.of(new ModelVariant(1, List.of(part)));
		}
		if (model instanceof WeightedVariants) {
			List<ModelVariant> variants = new ArrayList<>();
			for (Weighted<BlockStateModel> entry : ((WeightedVariantsAccess) model).mtlrender$list().unwrap()) {
				if (!(entry.value() instanceof SingleVariant)) {
					return null;
				}
				BlockStateModelPart part = ((SingleVariantAccess) entry.value()).mtlrender$model();
				variants.add(new ModelVariant(entry.weight(), List.of(part)));
			}
			return variants;
		}
		return null;
	}

	private static ByteBuffer encode(BlockState state, int flags, List<ModelSelector> selectors) {
		ByteBuffer data = ByteBuffer.allocateDirect(bytes(state, selectors)).order(ByteOrder.LITTLE_ENDIAN);
		data.putInt(TABLE_MAGIC).putInt(TABLE_VERSION).putInt(flags).putInt(0);
		for (Direction direction : Direction.values()) {
			List<AABB> boxes = state.getFaceOcclusionShape(direction).toAabbs();
			data.putInt(boxes.size());
			for (AABB box : boxes) {
				data.putFloat((float) box.minX).putFloat((float) box.minY).putFloat((float) box.minZ);
				data.putFloat((float) box.maxX).putFloat((float) box.maxY).putFloat((float) box.maxZ);
			}
		}
		data.putInt(selectors.size());
		for (ModelSelector selector : selectors) {
			data.putInt(selector.states.size());
			for (int stateId : selector.states) {
				data.putInt(stateId);
			}
			data.putInt(selector.variants.size());
			for (ModelVariant variant : selector.variants) {
				data.putInt(variant.weight).putInt(variant.parts.size());
				for (BlockStateModelPart part : variant.parts) {
					List<BakedQuad> quads = allQuads(part);
					data.putInt(part.useAmbientOcclusion() ? 1 : 0).putInt(quads.size());
					for (Direction direction : Direction.values()) {
						for (BakedQuad quad : part.getQuads(direction)) {
							writeQuad(data, quad, false);
						}
					}
					for (BakedQuad quad : part.getQuads(null)) {
						writeQuad(data, quad, true);
					}
				}
			}
		}
		data.flip();
		return data;
	}

	private static List<BakedQuad> allQuads(BlockStateModelPart part) {
		List<BakedQuad> quads = new ArrayList<>();
		for (Direction direction : Direction.values()) {
			quads.addAll(part.getQuads(direction));
		}
		quads.addAll(part.getQuads(null));
		return quads;
	}

	private static void writeQuad(ByteBuffer data, BakedQuad quad, boolean unculled) {
		for (int vertex = 0; vertex < 4; vertex++) {
			var position = quad.position(vertex);
			long packed = quad.packedUV(vertex);
			data.putFloat(position.x()).putFloat(position.y()).putFloat(position.z());
			data.putFloat(net.minecraft.client.model.geom.builders.UVPair.unpackU(packed));
			data.putFloat(net.minecraft.client.model.geom.builders.UVPair.unpackV(packed));
			data.putInt(0);
		}
		int quadFlags = quad.materialInfo().shade() ? 1 : 0;
		if (unculled) {
			quadFlags |= QUAD_UNCULLED;
		}
		quadFlags |= quad.materialInfo().lightEmission() << 2;
		data.putInt(quad.direction().ordinal()).putInt(quad.materialInfo().tintIndex());
		data.putInt(quadFlags).putInt(quad.materialInfo().layer().ordinal());
	}

	private static int bytes(BlockState state, List<ModelSelector> selectors) {
		int bytes = 16 + 6 * 4 + 4;
		for (Direction direction : Direction.values()) {
			bytes = Math.addExact(bytes, Math.multiplyExact(state.getFaceOcclusionShape(direction).toAabbs().size(), 24));
		}
		for (ModelSelector selector : selectors) {
			bytes = Math.addExact(bytes, 4 + Math.multiplyExact(selector.states.size(), 4));
			bytes = Math.addExact(bytes, 4);
			for (ModelVariant variant : selector.variants) {
				bytes = Math.addExact(bytes, 8);
				for (BlockStateModelPart part : variant.parts) {
					bytes = Math.addExact(bytes, 8);
					bytes = Math.addExact(bytes, Math.multiplyExact(allQuads(part).size(), TABLE_VERTEX_BYTES * 4 + 16));
				}
			}
		}
		return bytes;
	}

	private record ModelSelector(List<Integer> states, List<ModelVariant> variants) {
	}

	private record ModelVariant(int weight, List<BlockStateModelPart> parts) {
	}
}
