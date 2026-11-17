package dev.mikuteto.mtlrender.pack.uniform;

import java.util.HashMap;
import java.util.Map;

public class UniformValues implements Values {
	private final Map<String, Value> values = new HashMap<>();

	protected UniformValues(Map<String, Type> types) {
		for (Map.Entry<String, Type> entry : types.entrySet()) {
			values.put(entry.getKey(), new Value(entry.getValue()));
		}
	}

	public Value value(String name) {
		return values.get(name);
	}

	public Value require(String name, Type type) {
		Value value = values.get(name);
		if (value == null) {
			value = new Value(type);
			values.put(name, value);
		}
		return value;
	}

	public void set(String name, double... components) {
		Value value = require(name, Type.FLOAT);
		value.zero();
		for (int i = 0; i < components.length; i++) {
			value.setNumber(i, components[i]);
		}
	}

	public void set(String name, int... components) {
		Value value = require(name, Type.INT);
		value.zero();
		for (int i = 0; i < components.length; i++) {
			value.setInteger(i, components[i]);
		}
	}

	public void set(String name, boolean value) {
		Value target = require(name, Type.BOOL);
		target.zero();
		target.setBoolean(0, value);
	}

	public Map<String, Value> values() {
		return values;
	}
}
