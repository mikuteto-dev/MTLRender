package dev.mikuteto.mtlrender.pack.uniform;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class UniformWriter {
	public record Member(String name, Type type, int offset) {
	}

	private final Set<String> reported = new HashSet<>();
	private final System.Logger logger = System.getLogger(UniformWriter.class.getName());

	public void write(ByteBuffer buffer, List<Member> members, Values values) {
		for (Member member : members) {
			Value value = values.value(member.name());
			if (value == null) {
				if (reported.add(member.name())) {
					logger.log(System.Logger.Level.WARNING, "unknown shader uniform: " + member.name());
				}
				zero(buffer, member.offset(), member.type().size());
				continue;
			}
			writeValue(buffer, member.offset(), member.type(), value);
		}
	}

	private static void writeValue(ByteBuffer buffer, int offset, Type type, Value value) {
		if (type.matrix()) {
			int dimension = type.vectorWidth();
			for (int column = 0; column < dimension; column++) {
				for (int row = 0; row < dimension; row++) {
					put(buffer, offset + column * 16 + row * 4, type, value, column * dimension + row);
				}
				zero(buffer, offset + column * 16 + dimension * 4, (4 - dimension) * 4);
			}
			return;
		}
		for (int i = 0; i < type.components(); i++) {
			put(buffer, offset + i * 4, type, value, i);
		}
		zero(buffer, offset + type.components() * 4, type.size() - type.components() * 4);
	}

	private static void put(ByteBuffer buffer, int offset, Type type, Value value, int index) {
		if (type == Type.BOOL || type == Type.BVEC2 || type == Type.BVEC3 || type == Type.BVEC4) {
			buffer.putInt(offset, value.bool(index) ? 1 : 0);
		} else if (type.integral()) {
			buffer.putInt(offset, value.integer(index));
		} else {
			buffer.putFloat(offset, (float) value.number(index));
		}
	}

	private static void zero(ByteBuffer buffer, int offset, int size) {
		for (int i = 0; i < size; i += 4) {
			buffer.putInt(offset + i, 0);
		}
	}
}
