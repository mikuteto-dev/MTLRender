package dev.mikuteto.mtlrender.backend;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.buffers.GpuBufferSlice;

final class BindingTable {
	private static final int SLOTS = 64;
	private static final long UNSET = Long.MIN_VALUE;

	private static final class SampledTexture {
		long view;
		long sampler;
		boolean set;
	}

	private final CommandStream commands;
	private final Map<String, GpuBufferSlice> uniforms = new HashMap<>();
	private final Map<String, SampledTexture> textures = new HashMap<>();
	private final Map<String, Integer> layoutIndices = new HashMap<>();
	private final long[] bufferFirst = new long[SLOTS];
	private final long[] bufferSecond = new long[SLOTS];
	private final long[] textureFirst = new long[SLOTS];
	private final long[] textureSecond = new long[SLOTS];
	private final ShaderBinding.Kind[] textureKind = new ShaderBinding.Kind[SLOTS];
	private final int[] textureStages = new int[SLOTS];
	private List<ShaderBinding> layout = List.of();
	private long dirty;

	BindingTable(CommandStream commands) {
		this.commands = commands;
		reset();
	}

	void reset() {
		uniforms.clear();
		for (SampledTexture texture : textures.values()) {
			texture.set = false;
		}
		Arrays.fill(bufferFirst, UNSET);
		Arrays.fill(bufferSecond, UNSET);
		Arrays.fill(textureFirst, UNSET);
		Arrays.fill(textureSecond, UNSET);
		Arrays.fill(textureKind, null);
		Arrays.fill(textureStages, 0);
		layout = List.of();
		layoutIndices.clear();
		dirty = 0;
	}

	void useLayout(List<ShaderBinding> bindings) {
		if (bindings.size() > SLOTS) {
			throw new IllegalStateException("Pipeline uses " + bindings.size() + " bindings; at most " + SLOTS + " are supported");
		}
		layout = bindings;
		layoutIndices.clear();
		for (int index = 0; index < bindings.size(); index++) {
			layoutIndices.put(bindings.get(index).name(), index);
		}
		dirty = bindings.size() == SLOTS ? -1L : (1L << bindings.size()) - 1;
	}

	void setUniform(String name, GpuBufferSlice slice) {
		if (uniforms.put(name, slice) != slice) {
			markDirty(name);
		}
	}

	void clearUniform(String name) {
		if (uniforms.remove(name) != null) {
			markDirty(name);
		}
	}

	void setTexture(String name, long view, long sampler) {
		SampledTexture texture = textures.get(name);
		if (texture == null) {
			texture = new SampledTexture();
			textures.put(name, texture);
		}
		if (texture.set && texture.view == view && texture.sampler == sampler) {
			return;
		}
		texture.view = view;
		texture.sampler = sampler;
		texture.set = true;
		markDirty(name);
	}

	void clearTexture(String name) {
		SampledTexture texture = textures.get(name);
		if (texture != null && texture.set) {
			texture.set = false;
			markDirty(name);
		}
	}

	void flush() {
		long pending = dirty;
		while (pending != 0) {
			int index = Long.numberOfTrailingZeros(pending);
			pending &= pending - 1;
			emit(index, layout.get(index));
		}
		dirty = 0;
	}

	private void markDirty(String name) {
		Integer index = layoutIndices.get(name);
		if (index != null) {
			dirty |= 1L << index;
		}
	}

	private void emit(int index, ShaderBinding binding) {
		switch (binding.kind()) {
			case UNIFORM_BUFFER -> {
				GpuBufferSlice slice = uniform(binding);
				long buffer = MetalBuffer.handle(slice.buffer());
				if (bufferChanged(index, buffer, slice.offset())) {
					commands.bindBuffer(index, buffer, slice.offset());
				}
			}
			case TEXEL_BUFFER -> {
				long buffer = MetalBuffer.handle(uniform(binding).buffer());
				int format = Formats.pixelFormat(binding.texelFormat());
				if (textureChanged(index, ShaderBinding.Kind.TEXEL_BUFFER, buffer, format, binding.stageMask())) {
					commands.bindTexelBuffer(index, buffer, format);
				}
			}
			case SAMPLED_IMAGE -> {
				SampledTexture texture = textures.get(binding.name());
				if (texture == null || !texture.set) {
					throw new IllegalStateException("Missing sampler " + binding.name());
				}
				if (textureChanged(index, ShaderBinding.Kind.SAMPLED_IMAGE, texture.view, texture.sampler, binding.stageMask())) {
					commands.bindTexture(index, index, texture.view, texture.sampler, binding.stageMask());
				}
			}
		}
	}

	private boolean bufferChanged(int index, long buffer, long offset) {
		if (bufferFirst[index] == buffer && bufferSecond[index] == offset) {
			return false;
		}
		bufferFirst[index] = buffer;
		bufferSecond[index] = offset;
		return true;
	}

	private boolean textureChanged(int index, ShaderBinding.Kind kind, long first, long second, int stages) {
		if (textureKind[index] == kind && textureFirst[index] == first && textureSecond[index] == second && textureStages[index] == stages) {
			return false;
		}
		textureKind[index] = kind;
		textureFirst[index] = first;
		textureSecond[index] = second;
		textureStages[index] = stages;
		return true;
	}

	private GpuBufferSlice uniform(ShaderBinding binding) {
		GpuBufferSlice slice = uniforms.get(binding.name());
		if (slice == null) {
			throw new IllegalStateException("Missing uniform " + binding.name() + " (should be " + binding.kind() + ")");
		}
		return slice;
	}
}
