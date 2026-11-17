package dev.mikuteto.mtlrender.pack;

import java.util.List;

public final class PackOption {
	public enum Type {
		DEFINE,
		CONST
	}

	private final Type type;
	private final String name;
	private final boolean booleanOption;
	private final String defaultValue;
	private final boolean booleanDefaultValue;
	private final List<String> allowedValues;
	private final String comment;

	PackOption(Type type, String name, boolean booleanOption, String defaultValue,
			   boolean booleanDefaultValue, List<String> allowedValues, String comment) {
		this.type = type;
		this.name = name;
		this.booleanOption = booleanOption;
		this.defaultValue = defaultValue;
		this.booleanDefaultValue = booleanDefaultValue;
		this.allowedValues = List.copyOf(allowedValues);
		this.comment = comment;
	}

	public Type type() {
		return type;
	}

	public String name() {
		return name;
	}

	public boolean isBoolean() {
		return booleanOption;
	}

	public String defaultValue() {
		return defaultValue;
	}

	public boolean booleanDefaultValue() {
		return booleanDefaultValue;
	}

	public List<String> allowedValues() {
		return allowedValues;
	}

	public String comment() {
		return comment;
	}
}
