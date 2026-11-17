package dev.mikuteto.mtlrender.mixin;

import java.util.Map;

import dev.mikuteto.mtlrender.debug.DebugEntryDiagnostics;
import dev.mikuteto.mtlrender.diag.Diagnostics;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugScreenEntryList.class)
abstract class DebugScreenEntryListMixin {
	@org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final
	private Map<Identifier, DebugScreenEntryStatus> allStatuses;

	@Inject(method = "setOverlayVisible", at = @At("HEAD"))
	private void mtlrender$writeDiagnostics(boolean visible, CallbackInfo ci) {
		if (visible) {
			DebugScreenEntryList entries = (DebugScreenEntryList)(Object)this;
			if (!allStatuses.containsKey(DebugEntryDiagnostics.ID)) {
				entries.setStatus(DebugEntryDiagnostics.ID, DebugScreenEntryStatus.IN_OVERLAY);
			}
			Diagnostics.writeCurrent();
		}
	}
}
