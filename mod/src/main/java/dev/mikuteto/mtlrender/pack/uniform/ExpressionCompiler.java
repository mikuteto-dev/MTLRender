package dev.mikuteto.mtlrender.pack.uniform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class ExpressionCompiler {
	private ExpressionCompiler() {
	}

	public static Program compile(Type expected, String source) {
		return compile(expected, source, Builtins.types(), Set.of());
	}

	public static Program compile(Type expected, String source, Map<String, Type> symbols, Set<String> customNames) {
		return compile(expected, source, symbols, customNames, Map.of());
	}

	public static Program compile(Type expected, String source, Map<String, Type> symbols, Set<String> customNames, Map<String, Value> values) {
		String expression = source.trim();
		if (expression.endsWith(";")) {
			expression = expression.substring(0, expression.length() - 1).trim();
		}
		Parser parser = new Parser(expression, symbols, customNames, values);
		Node root = parser.parse();
		if (!compatible(expected, root.type)) {
			throw new IllegalArgumentException("expression type " + root.type.shaderName() + " does not match " + expected.shaderName());
		}
		return new Program(root, parser.dependencies);
	}

	private static boolean compatible(Type expected, Type actual) {
		return expected == actual || expected == Type.FLOAT && actual == Type.INT || expected == Type.INT && actual == Type.FLOAT;
	}

	public static final class Program {
		private final Node root;
		private final Set<String> dependencies;

		private Program(Node root, Set<String> dependencies) {
			this.root = root;
			this.dependencies = Set.copyOf(dependencies);
		}

		public Set<String> dependencies() {
			return dependencies;
		}

		public Type type() {
			return root.type;
		}

		public Value evaluate(double deltaSeconds) {
			return root.eval(deltaSeconds);
		}
	}

	private abstract static class Node {
		protected final Type type;

		private Node(Type type) {
			this.type = type;
		}

		abstract Value eval(double deltaSeconds);
	}

	private static final class Literal extends Node {
		private final Value value;
		private final boolean integerLiteral;

		private Literal(double number, boolean integerLiteral) {
			super(Type.FLOAT);
			this.integerLiteral = integerLiteral;
			value = new Value(Type.FLOAT);
			value.setNumber(0, number);
		}

		private Literal(boolean bool) {
			super(Type.BOOL);
			integerLiteral = false;
			value = new Value(Type.BOOL);
			value.setBoolean(0, bool);
		}

		@Override
		Value eval(double deltaSeconds) {
			return value;
		}
	}

	private static final class Name extends Node {
		private final Value value;
		private final String name;

		private Name(String name, Type type, Value value) {
			super(type);
			this.name = name;
			this.value = value;
		}

		@Override
		Value eval(double deltaSeconds) {
			return value;
		}
	}

	private static final class Unary extends Node {
		private final char operator;
		private final Node child;
		private final Value value;

		private Unary(char operator, Node child, Type type) {
			super(type);
			this.operator = operator;
			this.child = child;
			this.value = new Value(type);
		}

		@Override
		Value eval(double deltaSeconds) {
			Value input = child.eval(deltaSeconds);
			for (int i = 0; i < type.components(); i++) {
				if (operator == '!') {
					value.setBoolean(i, !input.bool(i));
				} else {
					value.setNumber(i, -input.number(i));
				}
			}
			return value;
		}
	}

	private static final class Binary extends Node {
		private final String operator;
		private final Node left;
		private final Node right;
		private final Value value;

		private Binary(String operator, Node left, Node right, Type type) {
			super(type);
			this.operator = operator;
			this.left = left;
			this.right = right;
			this.value = new Value(type);
		}

		@Override
		Value eval(double deltaSeconds) {
			Value a = left.eval(deltaSeconds);
			Value b = right.eval(deltaSeconds);
			if (type == Type.BOOL) {
				boolean result = switch (operator) {
					case "&&" -> a.bool(0) && b.bool(0);
					case "||" -> a.bool(0) || b.bool(0);
					case "==" -> a.number(0) == b.number(0);
					case "!=" -> a.number(0) != b.number(0);
					case "<" -> a.number(0) < b.number(0);
					case "<=" -> a.number(0) <= b.number(0);
					case ">" -> a.number(0) > b.number(0);
					case ">=" -> a.number(0) >= b.number(0);
					default -> throw new IllegalStateException(operator);
				};
				value.setBoolean(0, result);
				return value;
			}
			int width = type.components();
			for (int i = 0; i < width; i++) {
				double x = a.number(a.type().components() == 1 ? 0 : i);
				double y = b.number(b.type().components() == 1 ? 0 : i);
				double result = switch (operator) {
					case "+" -> x + y;
					case "-" -> x - y;
					case "*" -> x * y;
					case "/" -> x / y;
					case "%" -> x % y;
					default -> throw new IllegalStateException(operator);
				};
				value.setNumber(i, result);
			}
			return value;
		}
	}

	private static final class Access extends Node {
		private final Node source;
		private final int index;
		private final Value value;

		private Access(Node source, int index) {
			super(source.type.integral() ? Type.INT : Type.FLOAT);
			this.source = source;
			this.index = index;
			this.value = new Value(type);
		}

		@Override
		Value eval(double deltaSeconds) {
			Value input = source.eval(deltaSeconds);
			if (type == Type.INT) {
				value.setInteger(0, input.integer(index));
			} else {
				value.setNumber(0, input.number(index));
			}
			return value;
		}
	}

	private static final class Conditional extends Node {
		private final Node[] conditions;
		private final Node[] branches;
		private final Node fallback;

		private Conditional(Node[] conditions, Node[] branches, Node fallback, Type type) {
			super(type);
			this.conditions = conditions;
			this.branches = branches;
			this.fallback = fallback;
		}

		@Override
		Value eval(double deltaSeconds) {
			for (int i = 0; i < conditions.length; i++) {
				if (conditions[i].eval(deltaSeconds).bool(0)) {
					return branches[i].eval(deltaSeconds);
				}
			}
			return fallback.eval(deltaSeconds);
		}
	}

	private static final class Function extends Node {
		private final String name;
		private final Node[] arguments;
		private final Value value;
		private final Random random = new Random();
		private double smoothValue;
		private boolean smoothInitialized;

		private Function(String name, Node[] arguments, Type type) {
			super(type);
			this.name = name;
			this.arguments = arguments;
			this.value = new Value(type);
		}

		@Override
		Value eval(double deltaSeconds) {
			if (name.equals("if")) {
				return arguments[0].eval(deltaSeconds).bool(0) ? arguments[1].eval(deltaSeconds) : arguments[2].eval(deltaSeconds);
			}
			if (name.equals("smooth")) {
				int targetIndex = arguments.length > 1 && arguments[0] instanceof Literal literal && literal.integerLiteral ? 1 : 0;
				double target = arguments[targetIndex].eval(deltaSeconds).number(0);
				int fadeIndex = targetIndex + 1;
				double up = arguments.length > fadeIndex ? arguments[fadeIndex].eval(deltaSeconds).number(0) : 1.0;
				double down = arguments.length > fadeIndex + 1 ? arguments[fadeIndex + 1].eval(deltaSeconds).number(0) : up;
				if (!smoothInitialized) {
					smoothValue = target;
					smoothInitialized = true;
				} else {
					double halfLife = target > smoothValue ? up : down;
					double factor = halfLife <= 0.0 ? 1.0 : 1.0 - Math.pow(0.5, deltaSeconds / halfLife);
					smoothValue += (target - smoothValue) * factor;
				}
				value.setNumber(0, smoothValue);
				return value;
			}
			if (name.equals("random")) {
				double result;
				if (arguments.length == 0) {
					result = random.nextDouble();
				} else {
					double min = arguments[0].eval(deltaSeconds).number(0);
					double max = arguments[1].eval(deltaSeconds).number(0);
					result = min + random.nextDouble() * (max - min);
				}
				value.setNumber(0, result);
				return value;
			}
			if (name.equals("randomInt")) {
				int result;
				if (arguments.length == 0) {
					result = random.nextInt();
				} else if (arguments.length == 1) {
					result = random.nextInt((int) arguments[0].eval(deltaSeconds).number(0));
				} else {
					int min = (int) arguments[0].eval(deltaSeconds).number(0);
					int max = (int) arguments[1].eval(deltaSeconds).number(0);
					result = min + random.nextInt(max - min);
				}
				value.setInteger(0, result);
				return value;
			}
			if (name.equals("between") || name.equals("equals") || name.equals("notEquals") || name.equals("in") || name.equals("lessThan") || name.equals("lessThanOrEquals") || name.equals("moreThan") || name.equals("moreThanOrEquals")) {
				return evalBoolean(deltaSeconds);
			}
			if (name.equals("not")) {
				value.setBoolean(0, !arguments[0].eval(deltaSeconds).bool(0));
				return value;
			}
			if (name.equals("and") || name.equals("or")) {
				boolean result = name.equals("and");
				for (Node argument : arguments) {
					result = name.equals("and") ? result && argument.eval(deltaSeconds).bool(0) : result || argument.eval(deltaSeconds).bool(0);
				}
				value.setBoolean(0, result);
				return value;
			}
			for (int i = 0; i < type.components(); i++) {
				double a = arguments[0].eval(deltaSeconds).number(0);
				double b = arguments.length > 1 ? arguments[1].eval(deltaSeconds).number(0) : 0.0;
				double c = arguments.length > 2 ? arguments[2].eval(deltaSeconds).number(0) : 0.0;
				double result = switch (name) {
					case "negate" -> -a;
					case "sin" -> Math.sin(a);
					case "cos" -> Math.cos(a);
					case "tan" -> Math.tan(a);
					case "asin" -> Math.asin(a);
					case "acos" -> Math.acos(a);
					case "atan" -> arguments.length > 1 ? Math.atan2(a, b) : Math.atan(a);
					case "atan2" -> Math.atan2(a, b);
					case "torad", "radians" -> Math.toRadians(a);
					case "todeg", "degrees" -> Math.toDegrees(a);
					case "pow" -> Math.pow(a, b);
					case "exp" -> Math.exp(a);
					case "exp2" -> Math.pow(2.0, a);
					case "exp10" -> Math.pow(10.0, a);
					case "log" -> arguments.length > 1 ? Math.log(b) / Math.log(a) : Math.log(a);
					case "log2" -> Math.log(a) / Math.log(2.0);
					case "log10" -> Math.log10(a);
					case "sqrt" -> Math.sqrt(a);
					case "inversesqrt" -> 1.0 / Math.sqrt(a);
					case "abs" -> Math.abs(a);
					case "sign", "signum" -> Math.signum(a);
					case "floor" -> Math.floor(a);
					case "ceil" -> Math.ceil(a);
					case "frac" -> a - Math.floor(a);
					case "round" -> Math.round(a);
					case "min" -> Math.min(a, b);
					case "max" -> Math.max(a, b);
					case "clamp" -> Math.max(b, Math.min(c, a));
					case "mix" -> a + (b - a) * c;
					case "edge", "step" -> a < b ? 0.0 : 1.0;
					case "fmod" -> (a % b + b) % b;
					case "add" -> a + b;
					case "subtract" -> a - b;
					case "multiply" -> a * b;
					case "divide" -> a / b;
					case "remainder" -> a % b;
					default -> throw new IllegalArgumentException("unknown function: " + name);
				};
				value.setNumber(i, result);
			}
			return value;
		}

		private Value evalBoolean(double deltaSeconds) {
			double first = arguments[0].eval(deltaSeconds).number(0);
			boolean result = switch (name) {
				case "between" -> first >= arguments[1].eval(deltaSeconds).number(0) && first <= arguments[2].eval(deltaSeconds).number(0);
				case "equals" -> arguments.length == 2 ? first == arguments[1].eval(deltaSeconds).number(0) : Math.abs(first - arguments[1].eval(deltaSeconds).number(0)) <= arguments[2].eval(deltaSeconds).number(0);
				case "notEquals" -> arguments.length == 2 ? first != arguments[1].eval(deltaSeconds).number(0) : Math.abs(first - arguments[1].eval(deltaSeconds).number(0)) > arguments[2].eval(deltaSeconds).number(0);
				case "in" -> {
					boolean found = false;
					for (int i = 1; i < arguments.length; i++) {
						found |= first == arguments[i].eval(deltaSeconds).number(0);
					}
					yield found;
				}
				case "lessThan" -> first < arguments[1].eval(deltaSeconds).number(0);
				case "lessThanOrEquals" -> first <= arguments[1].eval(deltaSeconds).number(0);
				case "moreThan" -> first > arguments[1].eval(deltaSeconds).number(0);
				case "moreThanOrEquals" -> first >= arguments[1].eval(deltaSeconds).number(0);
				default -> false;
			};
			value.setBoolean(0, result);
			return value;
		}
	}

	private static final class Parser {
		private final Lexer lexer;
		private final Map<String, Type> symbols;
		private final Set<String> customNames;
		private final Map<String, Value> values;
		private final Set<String> dependencies = new HashSet<>();
		private Token current;

		private Parser(String source, Map<String, Type> symbols, Set<String> customNames, Map<String, Value> values) {
			this.lexer = new Lexer(source);
			this.symbols = symbols;
			this.customNames = customNames;
			this.values = values;
			this.current = lexer.next();
		}

		private Node parse() {
			Node result = expression(0);
			if (current.kind != Kind.END) {
				throw error("unexpected token " + current.text);
			}
			return result;
		}

		private Node expression(int precedence) {
			Node left = unary();
			while (current.kind == Kind.OP && precedence < precedence(current.text)) {
				String operator = current.text;
				int level = precedence(operator);
				next();
				Node right = expression(level);
				Type type = resultType(operator, left.type, right.type);
				left = new Binary(operator, left, right, type);
			}
			return left;
		}

		private Node unary() {
			if (current.kind == Kind.OP && (current.text.equals("-") || current.text.equals("!"))) {
			char operator = current.text.charAt(0);
			next();
			Node child = unary();
			return new Unary(operator, child, operator == '!' ? Type.BOOL : child.type);
			}
			return primary();
		}

		private Node primary() {
			Node result;
			if (current.kind == Kind.NUMBER) {
				result = new Literal(Double.parseDouble(current.text), !current.text.contains(".") && !current.text.contains("e") && !current.text.contains("E"));
				next();
			} else if (current.kind == Kind.BOOLEAN) {
				result = new Literal(Boolean.parseBoolean(current.text));
				next();
			} else if (current.kind == Kind.IDENT) {
				String name = current.text;
				next();
				if (current.kind == Kind.LPAREN) {
					result = function(name);
				} else {
					Type type = symbols.getOrDefault(name, constantType(name));
					Value value = values.get(name);
					if (value == null) {
						value = new Value(type);
					}
					if (customNames.contains(name)) {
						dependencies.add(name);
					}
					result = new Name(name, type, value);
				}
			} else if (current.kind == Kind.LPAREN) {
				next();
				result = expression(0);
				expect(Kind.RPAREN, ")");
				next();
			} else {
				throw error("expected expression");
			}
			return postfix(result);
		}

		private Node postfix(Node result) {
			while (current.kind == Kind.DOT) {
				next();
				if (current.kind != Kind.IDENT && current.kind != Kind.NUMBER) {
					throw error("expected member");
				}
				String member = current.text;
				next();
				if (result.type.matrix()) {
					String[] indices = member.split("\\.", -1);
					if (indices.length == 2) {
						if (!indices[0].matches("[0-9]+")) {
							throw error("matrix member must be numeric");
						}
						String columnName = indices[1];
						if (columnName.isEmpty()) {
							if (current.kind != Kind.NUMBER && current.kind != Kind.IDENT) {
								throw error("matrix column");
							}
							columnName = current.text;
							next();
						}
						int column = switch (columnName) {
							case "0", "r", "x", "s" -> 0;
							case "1", "g", "y", "t" -> 1;
							case "2", "b", "z", "p" -> 2;
							case "3", "a", "w", "q" -> 3;
							default -> throw error("unknown matrix column " + columnName);
						};
						result = new Access(result, column * result.type.vectorWidth() + Integer.parseInt(indices[0]));
						continue;
					}
					if (!member.matches("[0-9]+")) {
						throw error("matrix member must be numeric");
					}
					int row = Integer.parseInt(member);
					expect(Kind.DOT, ".");
					next();
					if (current.kind != Kind.NUMBER && current.kind != Kind.IDENT) {
						throw error("matrix column");
					}
					int column = switch (current.text) {
						case "0", "r", "x", "s" -> 0;
						case "1", "g", "y", "t" -> 1;
						case "2", "b", "z", "p" -> 2;
						case "3", "a", "w", "q" -> 3;
						default -> throw error("unknown matrix column " + current.text);
					};
					next();
					result = new Access(result, column * result.type.vectorWidth() + row);
				} else {
					int index = switch (member) {
						case "0", "r", "x", "s" -> 0;
						case "1", "g", "y", "t" -> 1;
						case "2", "b", "z", "p" -> 2;
						case "3", "a", "w", "q" -> 3;
						default -> throw error("unknown member " + member);
					};
					if (index >= result.type.components()) {
						throw error("member outside vector");
					}
					result = new Access(result, index);
				}
			}
			return result;
		}

		private Node function(String name) {
			next();
			List<Node> arguments = new ArrayList<>();
			if (current.kind != Kind.RPAREN) {
				while (true) {
					arguments.add(expression(0));
					if (current.kind != Kind.COMMA) {
						break;
					}
					next();
				}
			}
			expect(Kind.RPAREN, ")");
			next();
			if (name.equals("if")) {
				if (arguments.size() < 3 || arguments.size() % 2 == 0) {
					throw error("if requires condition/value pairs and fallback");
				}
				int count = (arguments.size() - 1) / 2;
				Node[] conditions = new Node[count];
				Node[] branches = new Node[count];
				for (int i = 0; i < count; i++) {
					conditions[i] = arguments.get(i * 2);
					branches[i] = arguments.get(i * 2 + 1);
				}
				return new Conditional(conditions, branches, arguments.get(arguments.size() - 1), branches[0].type);
			}
			if (name.equals("vec2") || name.equals("vec3") || name.equals("vec4") || name.equals("ivec2") || name.equals("ivec3") || name.equals("ivec4") || name.equals("bvec2") || name.equals("bvec3") || name.equals("bvec4")) {
				Type type = Type.parse(name);
				if (arguments.size() != type.vectorWidth()) {
					throw error("vector constructor requires " + type.vectorWidth() + " arguments");
				}
				return new Constructor(type, arguments.toArray(Node[]::new));
			}
			if (name.equals("smooth") && arguments.size() < 1) {
				throw error("smooth requires an argument");
			}
			Type type = functionType(name, arguments);
			return new Function(name, arguments.toArray(Node[]::new), type);
		}

		private static Type functionType(String name, List<Node> arguments) {
		if (name.equals("between") || name.equals("equals") || name.equals("notEquals") || name.equals("in") || name.equals("lessThan") || name.equals("lessThanOrEquals") || name.equals("moreThan") || name.equals("moreThanOrEquals") || name.equals("not") || name.equals("and") || name.equals("or")) {
			return Type.BOOL;
		}
		if (name.equals("randomInt") || name.equals("floor") && arguments.get(0).type == Type.INT || name.equals("ceil") && arguments.get(0).type == Type.INT) {
			return Type.INT;
		}
		if (name.equals("smooth")) {
			return Type.FLOAT;
		}
		return arguments.isEmpty() ? Type.FLOAT : arguments.get(0).type;
		}

		private static Type constantType(String name) {
		return name.startsWith("CAT_") || name.startsWith("BIOME_") || name.startsWith("PPT_") ? Type.INT : Type.FLOAT;
		}

		private static Type resultType(String operator, Type left, Type right) {
		if (operator.equals("&&") || operator.equals("||") || operator.equals("==") || operator.equals("!=") || operator.equals("<") || operator.equals("<=") || operator.equals(">") || operator.equals(">=")) {
			return Type.BOOL;
		}
		if (left.components() > right.components()) {
			return left;
		}
		return right;
		}

		private static int precedence(String operator) {
			return switch (operator) {
				case "||" -> 1;
				case "&&" -> 2;
				case "==", "!=", "<", "<=", ">", ">=" -> 3;
				case "+", "-" -> 4;
				case "*", "/", "%" -> 5;
				default -> -1;
			};
		}

		private void next() {
			current = lexer.next();
		}

		private void expect(Kind kind, String text) {
			if (current.kind != kind) {
				throw error("expected " + text);
			}
		}

		private IllegalArgumentException error(String message) {
			return new IllegalArgumentException(message + " at " + current.position);
		}
	}

	private static final class Constructor extends Node {
		private final Node[] arguments;
		private final Value value;

		private Constructor(Type type, Node[] arguments) {
			super(type);
			this.arguments = arguments;
			this.value = new Value(type);
		}

		@Override
		Value eval(double deltaSeconds) {
			for (int i = 0; i < arguments.length; i++) {
				Value argument = arguments[i].eval(deltaSeconds);
				if (type.integral()) {
					if (type == Type.BOOL || type == Type.BVEC2 || type == Type.BVEC3 || type == Type.BVEC4) {
						value.setBoolean(i, argument.bool(0));
					} else {
						value.setInteger(i, argument.integer(0));
					}
				} else {
					value.setNumber(i, argument.number(0));
				}
			}
			return value;
		}
	}

	private enum Kind { NUMBER, IDENT, BOOLEAN, OP, LPAREN, RPAREN, COMMA, DOT, END }

	private record Token(Kind kind, String text, int position) {
	}

	private static final class Lexer {
		private final String source;
		private int position;

		private Lexer(String source) {
			this.source = source.trim();
		}

		private Token next() {
			while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
				position++;
			}
			if (position >= source.length()) {
				return new Token(Kind.END, "", position);
			}
			int start = position;
			char c = source.charAt(position);
			if (Character.isDigit(c)) {
				position++;
				while (position < source.length() && (Character.isDigit(source.charAt(position)) || source.charAt(position) == '.' || source.charAt(position) == 'e' || source.charAt(position) == 'E' || source.charAt(position) == '+' && position > start && (source.charAt(position - 1) == 'e' || source.charAt(position - 1) == 'E') || source.charAt(position) == '-' && position > start && (source.charAt(position - 1) == 'e' || source.charAt(position - 1) == 'E'))) {
					position++;
				}
				return new Token(Kind.NUMBER, source.substring(start, position), start);
			}
			if (Character.isLetter(c) || c == '_') {
				position++;
				while (position < source.length() && (Character.isLetterOrDigit(source.charAt(position)) || source.charAt(position) == '_')) {
					position++;
				}
				String text = source.substring(start, position);
				return new Token(text.equals("true") || text.equals("false") ? Kind.BOOLEAN : Kind.IDENT, text, start);
			}
			if (c == '(') {
				position++;
				return new Token(Kind.LPAREN, "(", start);
			}
			if (c == ')') {
				position++;
				return new Token(Kind.RPAREN, ")", start);
			}
			if (c == ',') {
				position++;
				return new Token(Kind.COMMA, ",", start);
			}
			if (c == '.') {
				position++;
				return new Token(Kind.DOT, ".", start);
			}
			String two = position + 1 < source.length() ? source.substring(position, position + 2) : "";
			if (two.equals("&&") || two.equals("||") || two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")) {
				position += 2;
				return new Token(Kind.OP, two, start);
			}
			if ("+-*/%<>!".indexOf(c) >= 0) {
				position++;
				return new Token(Kind.OP, Character.toString(c), start);
			}
			throw new IllegalArgumentException("unexpected character at " + position + ": " + c);
		}
	}
}
