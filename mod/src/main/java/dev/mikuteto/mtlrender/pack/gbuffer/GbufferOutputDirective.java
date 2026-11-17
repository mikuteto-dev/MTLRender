package dev.mikuteto.mtlrender.pack.gbuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GbufferOutputDirective {
	private static final int MAX_TARGETS = 16;
	private static final Pattern COMMENT = Pattern.compile(
		"/\\*\\s*(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([^*]*?)\\*/", Pattern.DOTALL);

	public enum Kind {
		DRAWBUFFERS,
		RENDERTARGETS
	}

	private final Kind kind;
	private final List<OptionalInt> outputs;

	private GbufferOutputDirective(Kind kind, List<OptionalInt> outputs) {
		this.kind = kind;
		this.outputs = List.copyOf(outputs);
	}

	public static Optional<GbufferOutputDirective> find(String fragmentSource) {
		Matcher matcher = COMMENT.matcher(fragmentSource);
		GbufferOutputDirective found = null;
		while (matcher.find()) {
			found = parse(matcher.group(1), matcher.group(2));
		}
		return Optional.ofNullable(found);
	}

	public static GbufferOutputDirective parse(String fragmentSource) {
		return find(fragmentSource).orElseThrow(() -> new IllegalArgumentException("fragment source has no output directive"));
	}

	public static GbufferOutputDirective parse(String kind, String value) {
		Kind parsedKind;
		try {
			parsedKind = Kind.valueOf(kind.trim().toUpperCase());
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("unknown output directive: " + kind, exception);
		}
		List<OptionalInt> outputs = new ArrayList<>();
		if (parsedKind == Kind.DRAWBUFFERS) {
			String text = value.trim();
			if (text.isEmpty()) throw new IllegalArgumentException("empty DRAWBUFFERS directive");
			for (int index = 0; index < text.length(); index++) {
				char character = text.charAt(index);
				if (character == '-') {
					outputs.add(OptionalInt.empty());
				} else if (character >= '0' && character <= '9') {
					outputs.add(OptionalInt.of(character - '0'));
				} else {
					throw new IllegalArgumentException("invalid DRAWBUFFERS slot: " + character);
				}
			}
		} else {
			for (String part : value.trim().split(",", -1)) {
				String text = part.trim();
				if (text.isEmpty()) throw new IllegalArgumentException("empty RENDERTARGETS slot");
				try {
					int target = Integer.parseInt(text);
					if (target < 0 || target >= MAX_TARGETS) throw new NumberFormatException();
					outputs.add(OptionalInt.of(target));
				} catch (NumberFormatException exception) {
					throw new IllegalArgumentException("invalid RENDERTARGETS target: " + text, exception);
				}
			}
		}
		if (outputs.size() > 8) throw new IllegalArgumentException("output directive exceeds 8 color attachments");
		List<Integer> targets = outputs.stream().filter(OptionalInt::present).map(OptionalInt::value).toList();
		if (targets.stream().distinct().count() != targets.size()) {
			throw new IllegalArgumentException("output directive contains duplicate targets");
		}
		return new GbufferOutputDirective(parsedKind, outputs);
	}

	public Kind kind() {
		return kind;
	}

	public List<OptionalInt> outputs() {
		return outputs;
	}

	public record OptionalInt(boolean present, int value) {
		public static OptionalInt of(int value) {
			return new OptionalInt(true, value);
		}

		public static OptionalInt empty() {
			return new OptionalInt(false, -1);
		}
	}
}
