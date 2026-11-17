package dev.mikuteto.mtlrender.pack.uniform;

import java.util.Arrays;

public final class Value {
	private Type type;
	private final double[] numbers = new double[16];
	private final int[] integers = new int[16];
	private final boolean[] booleans = new boolean[16];

	public Value(Type type) {
		this.type = type;
	}

	public Type type() {
		return type;
	}

	public void type(Type type) {
		this.type = type;
	}

	public double number(int index) {
		return numbers[index];
	}

	public int integer(int index) {
		return integers[index];
	}

	public boolean bool(int index) {
		return booleans[index];
	}

	public void setNumber(int index, double value) {
		numbers[index] = value;
		integers[index] = (int) value;
		booleans[index] = value != 0.0;
	}

	public void setInteger(int index, int value) {
		integers[index] = value;
		numbers[index] = value;
		booleans[index] = value != 0;
	}

	public void setBoolean(int index, boolean value) {
		booleans[index] = value;
		integers[index] = value ? 1 : 0;
		numbers[index] = value ? 1.0 : 0.0;
	}

	public void zero() {
		Arrays.fill(numbers, 0.0);
		Arrays.fill(integers, 0);
		Arrays.fill(booleans, false);
	}

	public void copyFrom(Value source) {
		type = source.type;
		System.arraycopy(source.numbers, 0, numbers, 0, numbers.length);
		System.arraycopy(source.integers, 0, integers, 0, integers.length);
		System.arraycopy(source.booleans, 0, booleans, 0, booleans.length);
	}
}
