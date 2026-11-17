package dev.mikuteto.mtlrender.blockentity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class StaticModelPartBakerTest {
	private static final StaticBlockEntityBakeKey KEY = new StaticBlockEntityBakeKey(1, "test:model", "base");
	private static final StaticModelMaterial MATERIAL = new StaticModelMaterial("minecraft:atlas", "solid", "base");

	@Test
	void preservesParentTransformAndNormalizedGeometry() {
		ModelPart child = part(16.0F, 0.0F, 0.0F);
		ModelPart root = new ModelPart(List.of(), new LinkedHashMap<>(java.util.Map.of("child", child)));
		root.setPos(16.0F, 0.0F, 0.0F);
		root.setInitialPose(PartPose.offset(16.0F, 0.0F, 0.0F));
		StaticBlockEntityBakePlan plan = new StaticModelPartBaker().bake(KEY, model(root), MATERIAL, List.of("/child"));

		assertTrue(plan.hasStaticPortion());
		ByteBuffer payload = ByteBuffer.wrap(plan.staticParts().getFirst().geometryPayload()).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(24, payload.getInt());
		assertEquals(3.0F, payload.getFloat());
		assertEquals(0.0F, payload.getFloat());
		assertEquals(1.0F, payload.getFloat());
		assertEquals(1.0F, payload.getFloat());
		assertEquals(0.0F, payload.getFloat());
		assertEquals(0.0F, payload.getFloat());
		assertEquals(-1.0F, payload.getFloat());
		assertEquals(0.0F, payload.getFloat());
	}

	@Test
	void orderingAndResultBytesAreStable() {
		ModelPart root = part(0.0F, 0.0F, 0.0F);
		StaticModelPartBaker baker = new StaticModelPartBaker();
		byte[] first = baker.bake(KEY, model(root), MATERIAL, List.of("")).staticParts().getFirst().geometryPayload();
		byte[] second = baker.bake(KEY, model(root), MATERIAL, List.of("")).staticParts().getFirst().geometryPayload();
		assertArrayEquals(first, second);
	}

	@Test
	void hiddenSkippedAndInvalidPartsProduceFallbacks() {
		ModelPart hidden = part(0.0F, 0.0F, 0.0F);
		hidden.visible = false;
		ModelPart skipped = part(0.0F, 0.0F, 0.0F);
		skipped.skipDraw = true;
		ModelPart root = new ModelPart(List.of(), new LinkedHashMap<>(java.util.Map.of("hidden", hidden, "skipped", skipped)));
		StaticModelPartBaker baker = new StaticModelPartBaker();
		StaticBlockEntityBakePlan plan = baker.bake(KEY, model(root), MATERIAL, List.of("/hidden", "/skipped", "/missing"));
		assertEquals(StaticBlockEntityBakeFallback.Reason.MISSING_PART, plan.fallbacks().getFirst().reason());

		root.setPos(1.0F, 0.0F, 0.0F);
		StaticBlockEntityBakePlan animated = baker.bake(KEY, model(root), MATERIAL, List.of("/hidden"));
		assertEquals(StaticBlockEntityBakeFallback.Reason.ANIMATED_POSE, animated.fallbacks().getFirst().reason());
	}

	private static Model<?> model(ModelPart root) {
		return new Model.Simple(root, texture -> null);
	}

	private static ModelPart part(float x, float y, float z) {
		ModelPart.Cube cube = new ModelPart.Cube(0, 0, 0.0F, 0.0F, 0.0F, 16.0F, 16.0F, 16.0F,
			0.0F, 0.0F, 0.0F, false, 32.0F, 32.0F, EnumSet.allOf(Direction.class));
		ModelPart part = new ModelPart(List.of(cube), java.util.Map.of());
		part.setPos(x, y, z);
		part.setInitialPose(PartPose.offset(x, y, z));
		return part;
	}
}
