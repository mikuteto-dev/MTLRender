package dev.mikuteto.mtlrender.access;

import java.util.List;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public interface FeaturePreparedFrameAccess {
	@Accessor("allSubmits")
	List<SubmitNode> mtlrender$allSubmits();
}
