package dev.mikuteto.mtlrender.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

public final class PassLabels {
	private static final boolean ENABLED = Boolean.getBoolean("mtlrender.passTiming");
	private static final int MAX_LABELS = 1024;
	private static final List<String> NAMES = new ArrayList<>(List.of("[copy]", "[clear]", "[clear region]", "[present blit]", "[other]", "[cull]", "[path trace]", "[path denoise]"));
	private static final int OTHER = 4;
	private static final Object2IntOpenHashMap<String> IDS = new Object2IntOpenHashMap<>();

	static {
		IDS.defaultReturnValue(-1);
	}

	private PassLabels() {
	}

	public static boolean enabled() {
		return ENABLED;
	}

	public static int id(Supplier<String> label) {
		if (!ENABLED) {
			return OTHER;
		}
		String name = String.valueOf(label.get());
		int id = IDS.getInt(name);
		if (id >= 0) {
			return id;
		}
		if (NAMES.size() >= MAX_LABELS) {
			return OTHER;
		}
		id = NAMES.size();
		NAMES.add(name);
		IDS.put(name, id);
		return id;
	}

	static String name(int id) {
		return id < NAMES.size() ? NAMES.get(id) : "#" + id;
	}
}
