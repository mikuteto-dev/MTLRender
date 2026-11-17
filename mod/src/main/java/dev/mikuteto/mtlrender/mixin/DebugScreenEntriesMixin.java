package dev.mikuteto.mtlrender.mixin;

import java.util.HashMap;
import java.util.Map;

import dev.mikuteto.mtlrender.debug.DebugEntryDiagnostics;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugScreenEntries.class)
abstract class DebugScreenEntriesMixin {
	@Shadow @org.spongepowered.asm.mixin.Final @org.spongepowered.asm.mixin.Mutable
	private static Map<DebugScreenProfile, Map<Identifier, DebugScreenEntryStatus>> PROFILES;

	@Shadow
	private static Identifier register(Identifier identifier, DebugScreenEntry entry) {
		throw new AssertionError();
	}

	@Inject(method = "<clinit>", at = @At("TAIL"))
	private static void mtlrender$registerDiagnostics(CallbackInfo ci) {
		register(DebugEntryDiagnostics.ID, new DebugEntryDiagnostics());
		Map<Identifier, DebugScreenEntryStatus> defaults = new HashMap<>(PROFILES.get(DebugScreenProfile.DEFAULT));
		defaults.put(DebugEntryDiagnostics.ID, DebugScreenEntryStatus.IN_OVERLAY);
		Map<Identifier, DebugScreenEntryStatus> performance = new HashMap<>(PROFILES.get(DebugScreenProfile.PERFORMANCE));
		performance.put(DebugEntryDiagnostics.ID, DebugScreenEntryStatus.IN_OVERLAY);
		PROFILES = Map.of(
			DebugScreenProfile.DEFAULT, Map.copyOf(defaults), DebugScreenProfile.PERFORMANCE, Map.copyOf(performance)
		);
	}
}
