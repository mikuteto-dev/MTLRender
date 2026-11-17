package dev.mikuteto.mtlrender.pack.runtime;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.TransientMemory;
import dev.mikuteto.mtlrender.pack.compiler.Reflection;
import dev.mikuteto.mtlrender.pack.uniform.Type;
import dev.mikuteto.mtlrender.pack.uniform.UniformWriter;
import dev.mikuteto.mtlrender.pack.uniform.Values;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public final class PackUniformBindings {
	private static final long ALIGNMENT = 256L;
	private final List<UniformWriter.Member> members;
	private final int size;
	private final UniformWriter writer = new UniformWriter();

	public PackUniformBindings(Reflection reflection) {
		this(reflection.uniformMembers());
	}

	public PackUniformBindings(List<Reflection.UniformMember> reflected) {
		List<UniformWriter.Member> result = new ArrayList<>(reflected.size());
		int end = 0;
		for (Reflection.UniformMember member : reflected) {
			Type type = type(member.type());
			if (member.offset() < 0 || member.size() < type.size()) {
				throw new IllegalArgumentException("invalid reflected uniform member: " + member.name());
			}
			result.add(new UniformWriter.Member(member.name(), type, member.offset()));
			end = Math.max(end, member.offset() + member.size());
		}
		members = List.copyOf(result);
		size = aligned(end);
	}

	public int size() {
		return size;
	}

	public List<UniformWriter.Member> members() {
		return members;
	}

	public ByteBuffer write(Values values) {
		ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
		writer.write(buffer, members, values);
		return buffer;
	}

	public GpuBufferSlice write(TransientMemory memory, Values values) {
		GpuBufferSlice.MappedView mapped = memory.allocateGpuMapped(size, ALIGNMENT, GpuBuffer.USAGE_UNIFORM, size, 1);
		ByteBuffer buffer = mapped.data().order(ByteOrder.LITTLE_ENDIAN);
		writer.write(buffer, members, values);
		return mapped.slice();
	}

	private static int aligned(int value) {
		if (value == 0) {
			return 0;
		}
		return Math.addExact(value, 255) & ~255;
	}

	private static Type type(int code) {
		return switch (code) {
			case 1 -> Type.BOOL;
			case 2, 3 -> Type.INT;
			case 4 -> Type.FLOAT;
			case 6 -> Type.VEC2;
			case 7 -> Type.VEC3;
			case 8 -> Type.VEC4;
			case 9, 12 -> Type.IVEC2;
			case 10, 13 -> Type.IVEC3;
			case 11, 14 -> Type.IVEC4;
			case 15 -> Type.MAT2;
			case 16 -> Type.MAT3;
			case 17 -> Type.MAT4;
			default -> throw new IllegalArgumentException("unsupported reflected uniform type: " + code);
		};
	}
}
