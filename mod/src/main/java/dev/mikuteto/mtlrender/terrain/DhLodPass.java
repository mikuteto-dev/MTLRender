package dev.mikuteto.mtlrender.terrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import dev.mikuteto.mtlrender.pack.ProgramSet;
import dev.mikuteto.mtlrender.pack.dh.DhDepthResources;
import dev.mikuteto.mtlrender.pack.dh.DhProgramKind;
import dev.mikuteto.mtlrender.pack.dh.DhRenderContract;
import dev.mikuteto.mtlrender.pack.dh.DhShadowDraw;
import dev.mikuteto.mtlrender.pack.dh.DhShadowFrame;
import dev.mikuteto.mtlrender.pack.dh.DhUniformState;
import dev.mikuteto.mtlrender.pack.dh.DhVertexAttribute;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

public final class DhLodPass {
	public record Material(ChunkSectionLayer layer, DhRenderContract.MaterialKind kind, int materialId,
		DhRenderContract.WorldOrigin origin) {
		public Material {
			Objects.requireNonNull(layer, "layer");
			Objects.requireNonNull(kind, "kind");
			if (materialId < 0) throw new IllegalArgumentException("material id must be non-negative");
			if (kind == DhRenderContract.MaterialKind.WATER && layer != ChunkSectionLayer.TRANSLUCENT) {
				throw new IllegalArgumentException("water material must use translucent layer");
			}
			if (kind == DhRenderContract.MaterialKind.TERRAIN && layer == ChunkSectionLayer.TRANSLUCENT) {
				throw new IllegalArgumentException("terrain material must not use translucent layer");
			}
			Objects.requireNonNull(origin, "origin");
		}

		public Material(ChunkSectionLayer layer, DhRenderContract.MaterialKind kind, int materialId) {
			this(layer, kind, materialId, new DhRenderContract.WorldOrigin(0, 0, 0));
		}
	}

	public record Prepared(DirectUberBuffer.Entry entry, DhRenderContract contract, DhUniformState uniforms,
		DhDepthResources depthResources, int quads) {
		public Prepared {
			Objects.requireNonNull(entry, "entry");
			Objects.requireNonNull(contract, "contract");
			Objects.requireNonNull(uniforms, "uniforms");
			Objects.requireNonNull(depthResources, "depth resources");
			if (quads <= 0) throw new IllegalArgumentException("LOD entry must contain quads");
		}
	}

	public record ShadowPrepared(Prepared prepared, DhShadowFrame frame, DhShadowDraw.CullingMode culling) {
		public ShadowPrepared {
			Objects.requireNonNull(prepared, "prepared");
			Objects.requireNonNull(frame, "shadow frame");
			Objects.requireNonNull(culling, "culling");
			if (!prepared.contract().shadow()) throw new IllegalArgumentException("shadow contract required");
			if (prepared.uniforms().generation() != frame.generation()
				|| prepared.depthResources().generation() != frame.depthGeneration()) {
				throw new IllegalArgumentException("DH shadow resources do not match frame");
			}
		}
	}

	public record Plane(float x, float y, float z, float w) {
		public boolean intersects(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
			float px = x >= 0.0F ? maxX : minX;
			float py = y >= 0.0F ? maxY : minY;
			float pz = z >= 0.0F ? maxZ : minZ;
			return x * px + y * py + z * pz + w >= 0.0F;
		}
	}

	public static Optional<ShadowPrepared> prepareShadow(DirectUberBuffer.Entry entry, Material material, int lodLayer,
		DhShadowFrame frame, DhDepthResources depthResources) {
		Objects.requireNonNull(frame, "shadow frame");
		Objects.requireNonNull(depthResources, "depth resources");
		if (!frame.enabled() || !frame.shadowProgramAvailable() || !shadowLayerEnabled(material, frame)
			|| frame.uniforms().isEmpty() || frame.depthGeneration() != depthResources.generation()) return Optional.empty();
		DhUniformState uniforms = frame.uniforms().orElseThrow();
		Optional<Prepared> prepared = prepare(entry, material, lodLayer, true, DhProgramKind.SHADOW, uniforms, depthResources);
		return prepared.map(value -> new ShadowPrepared(value, frame, shadowCulling(material)));
	}

	public static boolean shadowVisible(float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
		List<Plane> planes) {
		Objects.requireNonNull(planes, "shadow planes");
		for (Plane plane : planes) {
			if (!plane.intersects(minX, minY, minZ, maxX, maxY, maxZ)) return false;
		}
		return true;
	}

	static DhShadowDraw.CullingMode shadowCulling(Material material) {
		Objects.requireNonNull(material, "material");
		return material.layer() == ChunkSectionLayer.SOLID ? DhShadowDraw.CullingMode.BACK : DhShadowDraw.CullingMode.BOTH;
	}

	private static boolean shadowLayerEnabled(Material material, DhShadowFrame frame) {
		return material.layer() == ChunkSectionLayer.TRANSLUCENT ? frame.canRenderTranslucent() : frame.canRenderTerrain();
	}

	public static Optional<Prepared> prepare(DirectUberBuffer.Entry entry, Material material, int lodLayer,
		boolean shadow, EnumSet<DhProgramKind> availablePrograms, DhUniformState uniforms,
		DhDepthResources depthResources) {
		Objects.requireNonNull(material, "material");
		Objects.requireNonNull(availablePrograms, "available programs");
		Objects.requireNonNull(uniforms, "uniforms");
		Objects.requireNonNull(depthResources, "depth resources");
		if (entry == null || entry.faces() == null) return Optional.empty();
		DhProgramKind program = selectProgram(material, shadow);
		if (!availablePrograms.contains(program)) return Optional.empty();
		int quads = quadCount(entry.faces());
		if (quads == 0) return Optional.empty();
		DhRenderContract.MaterialKind contractKind = material.kind() == DhRenderContract.MaterialKind.GENERIC
			&& program == DhProgramKind.SHADOW ? DhRenderContract.MaterialKind.TERRAIN : material.kind();
		DhRenderContract contract = new DhRenderContract(program, lodLayer, contractKind, attributes(program),
			material.origin(), shadow);
		return Optional.of(new Prepared(entry, contract, uniforms, depthResources, quads));
	}

	public static Optional<Prepared> prepare(DirectUberBuffer.Entry entry, Material material, int lodLayer,
		boolean shadow, DhProgramKind program, DhUniformState uniforms, DhDepthResources depthResources) {
		Objects.requireNonNull(program, "program");
		if (program != selectProgram(material, shadow)) return Optional.empty();
		return prepare(entry, material, lodLayer, shadow, EnumSet.of(program), uniforms, depthResources);
	}

	public static void draw(RenderPass pass, Prepared prepared, GpuBufferSlice vertexBuffer) {
		Objects.requireNonNull(pass, "pass");
		Objects.requireNonNull(prepared, "prepared");
		Objects.requireNonNull(vertexBuffer, "vertex buffer");
		pass.setVertexBuffer(0, vertexBuffer);
		pass.draw(prepared.quads() * 6, 1, 0, 0);
	}

	public static void drawShadow(RenderPass pass, ShadowPrepared prepared, GpuBufferSlice vertexBuffer) {
		Objects.requireNonNull(prepared, "shadow prepared");
		draw(pass, prepared.prepared(), vertexBuffer);
	}

	public static ByteBuffer packTerrainVertex(int x, int y, int z, float red, float green, float blue,
		float alpha, int material, int face, int texture) {
		ByteBuffer result = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
		result.putInt(x).putInt(y).putInt(z).putInt(0);
		result.putFloat(red).putFloat(green).putFloat(blue).putFloat(alpha);
		result.putInt(material).putInt(face).putInt(texture).putInt(0);
		return result.flip();
	}

	public static ByteBuffer packGenericVertex(float x, float y, float z, float red, float green, float blue,
		float alpha, float scaleX, float scaleY, float scaleZ, int chunkX, int chunkY, int chunkZ,
		float subX, float subY, float subZ, int material) {
		ByteBuffer result = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
		result.putFloat(x).putFloat(y).putFloat(z);
		result.putFloat(red).putFloat(green).putFloat(blue).putFloat(alpha);
		result.putFloat(scaleX).putFloat(scaleY).putFloat(scaleZ);
		result.putInt(chunkX).putInt(chunkY).putInt(chunkZ);
		result.putFloat(subX).putFloat(subY).putFloat(subZ).putInt(material);
		return result.flip();
	}

	public static EnumSet<DhProgramKind> availablePrograms(ProgramSet programs) {
		Objects.requireNonNull(programs, "programs");
		EnumSet<DhProgramKind> result = EnumSet.noneOf(DhProgramKind.class);
		for (DhProgramKind kind : DhProgramKind.values()) {
			if (programs.resolve(kind).isPresent()) result.add(kind);
		}
		return result;
	}

	public static DhProgramKind programFor(ChunkSectionLayer layer) {
		Objects.requireNonNull(layer, "layer");
		return layer == ChunkSectionLayer.TRANSLUCENT ? DhProgramKind.WATER
			: layer == ChunkSectionLayer.CUTOUT ? DhProgramKind.GENERIC : DhProgramKind.TERRAIN;
	}

	static DhProgramKind selectProgram(Material material, boolean shadow) {
		Objects.requireNonNull(material, "material");
		if (shadow) return DhProgramKind.SHADOW;
		if (material.kind() == DhRenderContract.MaterialKind.GENERIC) return DhProgramKind.GENERIC;
		return material.layer() == ChunkSectionLayer.TRANSLUCENT ? DhProgramKind.WATER : DhProgramKind.TERRAIN;
	}

	private static List<DhVertexAttribute> attributes(DhProgramKind program) {
		if (program == DhProgramKind.GENERIC) {
			return List.of(attribute(DhVertexAttribute.Identity.POSITION), attribute(DhVertexAttribute.Identity.COLOR),
				attribute(DhVertexAttribute.Identity.SCALE), attribute(DhVertexAttribute.Identity.TRANSLATE_CHUNK),
				attribute(DhVertexAttribute.Identity.TRANSLATE_SUB_CHUNK), attribute(DhVertexAttribute.Identity.MATERIAL));
		}
		return List.of(attribute(DhVertexAttribute.Identity.POSITION), attribute(DhVertexAttribute.Identity.COLOR),
			attribute(DhVertexAttribute.Identity.EXTRA));
	}

	private static DhVertexAttribute attribute(DhVertexAttribute.Identity identity) {
		return new DhVertexAttribute(identity);
	}

	private static int quadCount(int[] faces) {
		int total = 0;
		for (int face : faces) {
			if (face < 0) throw new IllegalArgumentException("negative LOD face count");
			total = Math.addExact(total, face);
		}
		return total;
	}

	private DhLodPass() {
	}
}
