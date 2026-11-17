package dev.mikuteto.mtlrender.backend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

record VertexLayout(List<Binding> bindings, List<Attribute> attributes) {
	record Binding(int slot, int stride, int stepRate) {
	}

	record Attribute(String name, int location, int slot, int offset, GpuFormat format) {
	}

	static VertexLayout of(RenderPipeline pipeline) {
		List<Binding> bindings = new ArrayList<>();
		List<Attribute> attributes = new ArrayList<>();
		VertexFormat[] formats = pipeline.getVertexFormatBindings();
		for (int slot = 0; slot < formats.length; slot++) {
			VertexFormat format = formats[slot];
			if (format == null) {
				continue;
			}
			bindings.add(new Binding(slot, format.getVertexSize(), format.getStepRate()));
			for (VertexFormatElement element : format.getElements()) {
				attributes.add(new Attribute(element.name(), attributes.size(), slot, element.offset(), element.format()));
			}
		}
		return new VertexLayout(List.copyOf(bindings), List.copyOf(attributes));
	}

	Map<String, Integer> locations() {
		Map<String, Integer> locations = new HashMap<>();
		for (Attribute attribute : attributes) {
			locations.putIfAbsent(attribute.name(), attribute.location());
		}
		return locations;
	}
}
