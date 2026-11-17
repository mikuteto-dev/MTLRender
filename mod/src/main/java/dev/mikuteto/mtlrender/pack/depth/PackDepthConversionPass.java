package dev.mikuteto.mtlrender.pack.depth;

import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import java.util.Optional;

public final class PackDepthConversionPass {
	private final PackDepthProducer<GpuTextureView> producer = new PackDepthProducer<>();

	public Optional<GpuTextureView> produce(String name, long version, GpuTextureView source, GpuTextureView destination) {
		if (destination == null) return Optional.empty();
		return producer.produce(name, version, source, value -> {
			MetalAccess.convertPackDepth(value, destination);
			return destination;
		});
	}

	public Optional<GpuTextureView> read(String name) {
		return producer.read(name);
	}

	public void clear() {
		producer.clear();
	}
}
