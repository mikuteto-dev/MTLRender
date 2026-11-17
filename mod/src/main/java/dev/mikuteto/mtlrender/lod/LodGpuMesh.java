package dev.mikuteto.mtlrender.lod;

import java.util.Objects;

import com.mojang.blaze3d.buffers.GpuBuffer;

public final class LodGpuMesh {
	private static final int FACE_BUCKETS = 7;
	private static final int QUAD_BYTES = 32;

	private final LodData.LodKey key;
	private final long generation;
	private final GpuBuffer buffer;
	private final long address;
	private final int vertexOffset;
	private final int vertexBytes;
	private final int quadOffset;
	private final int quadBytes;
	private final int[] faceCounts;
	private final long byteSize;

	LodGpuMesh(LodMesh mesh, long generation, GpuBuffer buffer, long address) {
		Metadata metadata = validate(mesh);
		this.key = mesh.key();
		this.generation = generation;
		this.buffer = Objects.requireNonNull(buffer, "buffer");
		this.address = address;
		this.vertexOffset = metadata.vertexOffset();
		this.vertexBytes = metadata.vertexBytes();
		this.quadOffset = metadata.quadOffset();
		this.quadBytes = metadata.quadBytes();
		this.faceCounts = mesh.faceBuckets();
		this.byteSize = mesh.bytes().length;
	}

	public LodData.LodKey key() { return key; }
	public long generation() { return generation; }
	public GpuBuffer buffer() { return buffer; }
	public long address() { return address; }
	public int vertexOffset() { return vertexOffset; }
	public int vertexBytes() { return vertexBytes; }
	public int quadOffset() { return quadOffset; }
	public int quadBytes() { return quadBytes; }
	public int[] faceCounts() { return faceCounts.clone(); }
	public long byteSize() { return byteSize; }

	static Metadata validate(LodMesh mesh) {
		Objects.requireNonNull(mesh, "mesh");
		byte[] bytes = mesh.bytes();
		long length = bytes.length;
		if (length <= 0 || mesh.estimatedBytes() != length) {
			throw new IllegalArgumentException("LOD mesh byte size is inconsistent");
		}
		if (!range(mesh.vertexOffset(), mesh.vertexBytes(), length)
			|| !range(mesh.quadOffset(), mesh.quadBytes(), length)) {
			throw new IllegalArgumentException("LOD mesh metadata is outside the buffer");
		}
		int[] faces = mesh.faceBuckets();
		if (faces.length != FACE_BUCKETS) {
			throw new IllegalArgumentException("exactly seven face buckets are required");
		}
		long expected = 0;
		for (int face : faces) {
			if (face < 0) {
				throw new IllegalArgumentException("face counts must be non-negative");
			}
			expected = Math.addExact(expected, Math.multiplyExact((long) face, QUAD_BYTES));
		}
		if (expected != mesh.quadBytes()) {
			throw new IllegalArgumentException("face counts do not match quad bytes");
		}
		return new Metadata(mesh.vertexOffset(), mesh.vertexBytes(), mesh.quadOffset(), mesh.quadBytes());
	}

	private static boolean range(int offset, int size, long length) {
		return offset >= 0 && size >= 0 && (long) offset + size <= length;
	}

	record Metadata(int vertexOffset, int vertexBytes, int quadOffset, int quadBytes) {}
}
