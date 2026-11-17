package dev.mikuteto.mtlrender.pack.gbuffer;

import java.util.Objects;
import java.util.regex.Pattern;

import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;

public final class GbufferShaderSource implements ShaderSource {
	private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)");

	private final ShaderSource vanilla;
	private final Identifier vanillaVertex;
	private final Identifier vanillaFragment;
	private final Identifier vertex;
	private final Identifier fragment;
	private final String vertexSource;
	private final String fragmentSource;

	public GbufferShaderSource(ShaderSource vanilla, Identifier vanillaVertex, Identifier vanillaFragment,
		Identifier vertex, Identifier fragment, String vertexSource, String fragmentSource) {
		this(vanilla, vanillaVertex, vanillaFragment, vertex, fragment, vertexSource, fragmentSource, null);
	}

	public GbufferShaderSource(ShaderSource vanilla, Identifier vanillaVertex, Identifier vanillaFragment,
		Identifier vertex, Identifier fragment, String vertexSource, String fragmentSource,
		GbufferPipelineSpec.InputProfile inputProfile) {
		this.vanilla = Objects.requireNonNull(vanilla, "vanilla source");
		this.vanillaVertex = Objects.requireNonNull(vanillaVertex, "vanilla vertex");
		this.vanillaFragment = Objects.requireNonNull(vanillaFragment, "vanilla fragment");
		this.vertex = Objects.requireNonNull(vertex, "gbuffer vertex");
		this.fragment = Objects.requireNonNull(fragment, "gbuffer fragment");
		String source = Objects.requireNonNull(vertexSource, "vertex source");
		this.vertexSource = inputProfile == null ? source : GbufferInputAdapter.adapt(source, inputProfile);
		this.fragmentSource = Objects.requireNonNull(fragmentSource, "fragment source");
	}

	@Override
	public String get(Identifier id, ShaderType type) {
		if (id.equals(vertex) && type == ShaderType.VERTEX) return vertexSource;
		if (id.equals(fragment) && type == ShaderType.FRAGMENT) return fragmentSource;
		if (id.equals(vertex) || id.equals(vanillaVertex)) return vanilla.get(vanillaVertex, type);
		if (id.equals(fragment) || id.equals(vanillaFragment)) return vanilla.get(vanillaFragment, type);
		return vanilla.get(id, type);
	}

	public Identifier vertex() {
		return vertex;
	}

	public Identifier fragment() {
		return fragment;
	}

	public static ShaderSource withAlphaTest(ShaderSource source, Identifier fragment, GbufferStateOverride.AlphaTest alpha) {
		return (id, type) -> {
			String text = source.get(id, type);
			if (!fragment.equals(id) || type != ShaderType.FRAGMENT || alpha == null || text == null) {
				return text;
			}
			return inject(text, alpha);
		};
	}

	static String inject(String source, GbufferStateOverride.AlphaTest alpha) {
		if (!MAIN.matcher(source).find()) return source;
		String body = MAIN.matcher(source).replaceFirst("void mtlr_gbuffer_main()");
		String test = switch (alpha.function()) {
			case NEVER_PASS -> "discard";
			case ALWAYS_PASS -> "";
			case LESS_THAN -> "fragColor.a >= %s".formatted(Float.toString(alpha.reference()));
			case EQUAL -> "fragColor.a != %s".formatted(Float.toString(alpha.reference()));
			case LESS_THAN_OR_EQUAL -> "fragColor.a > %s".formatted(Float.toString(alpha.reference()));
			case GREATER_THAN -> "fragColor.a <= %s".formatted(Float.toString(alpha.reference()));
			case NOT_EQUAL -> "fragColor.a == %s".formatted(Float.toString(alpha.reference()));
			case GREATER_THAN_OR_EQUAL -> "fragColor.a < %s".formatted(Float.toString(alpha.reference()));
		};
		String check = test.isEmpty() ? "" : test.equals("discard") ? "\n    discard;" : "\n    if (" + test + ") { discard; }";
		return body + "\nvoid main() {\n    mtlr_gbuffer_main();" + check + "\n}\n";
	}
}
