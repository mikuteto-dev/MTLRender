package dev.mikuteto.mtlrender.pack.uniform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class UniformEngine implements Values {
	public record Definition(String name, Type type, String expression, boolean uniform) {
	}

	private final Values inputs;
	private final Map<String, Value> values = new LinkedHashMap<>();
	private final List<ExpressionCompiler.Program> programs = new ArrayList<>();
	private final List<String> order = new ArrayList<>();

	public UniformEngine(Values inputs, List<Definition> definitions) {
		this.inputs = inputs;
		Map<String, Type> symbols = new LinkedHashMap<>(Builtins.types());
		Set<String> customNames = new HashSet<>();
		for (Definition definition : definitions) {
			if (values.put(definition.name(), new Value(definition.type())) != null) {
				throw new IllegalArgumentException("duplicate custom uniform: " + definition.name());
			}
			symbols.put(definition.name(), definition.type());
			customNames.add(definition.name());
		}
		Map<String, Value> symbolValues = new HashMap<>();
		if (inputs instanceof UniformValues uniformValues) {
			symbolValues.putAll(uniformValues.values());
		}
		for (Map.Entry<String, Type> entry : symbols.entrySet()) {
			Value value = values.get(entry.getKey());
			if (value == null) {
				value = inputs.value(entry.getKey());
			}
			if (value != null) {
				symbolValues.put(entry.getKey(), value);
			}
		}
		Map<String, Definition> byName = new HashMap<>();
		Map<String, ExpressionCompiler.Program> compiled = new HashMap<>();
		for (Definition definition : definitions) {
			byName.put(definition.name(), definition);
			compiled.put(definition.name(), ExpressionCompiler.compile(definition.type(), definition.expression(), symbols, customNames, symbolValues));
		}
		Map<String, Integer> states = new HashMap<>();
		for (Definition definition : definitions) {
			visit(definition.name(), compiled, byName, states);
		}
		for (String name : order) {
			programs.add(compiled.get(name));
		}
	}

	private void visit(String name, Map<String, ExpressionCompiler.Program> compiled, Map<String, Definition> definitions, Map<String, Integer> states) {
		int state = states.getOrDefault(name, 0);
		if (state == 1) {
			throw new IllegalArgumentException("circular custom uniform dependency: " + name);
		}
		if (state == 2) {
			return;
		}
		states.put(name, 1);
		for (String dependency : compiled.get(name).dependencies()) {
			if (definitions.containsKey(dependency)) {
				visit(dependency, compiled, definitions, states);
			}
		}
		states.put(name, 2);
		order.add(name);
	}

	public void update() {
		update(1.0 / 60.0);
	}

	public void update(double deltaSeconds) {
		for (int i = 0; i < programs.size(); i++) {
			Value result = programs.get(i).evaluate(deltaSeconds);
			values.get(order.get(i)).copyFrom(result);
		}
	}

	@Override
	public Value value(String name) {
		Value result = values.get(name);
		return result == null ? inputs.value(name) : result;
	}

	public List<String> order() {
		return List.copyOf(order);
	}
}
