package dev.mikuteto.mtlrender.pack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ProfileSet {
	private final Map<String, Profile> profiles;

	private ProfileSet(Map<String, Profile> profiles) {
		this.profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
	}

	public static ProfileSet from(Map<String, List<String>> source, Map<String, PackOption> options) {
		Map<String, Profile> profiles = new LinkedHashMap<>();
		for (String name : source.keySet()) {
			profiles.put(name, parse(name, source, options, new LinkedHashSet<>()));
		}
		return new ProfileSet(profiles);
	}

	private static Profile parse(String name, Map<String, List<String>> source,
								 Map<String, PackOption> options, Set<String> parents) {
		List<String> entries = source.getOrDefault(name, List.of());
		Map<String, String> values = new LinkedHashMap<>();
		List<String> disabled = new ArrayList<>();
		Set<String> path = new LinkedHashSet<>(parents);
		path.add(name);

		for (String entry : entries) {
			if (entry.startsWith("!program.")) {
				disabled.add(entry.substring("!program.".length()));
			} else if (entry.startsWith("profile.")) {
				String dependency = entry.substring("profile.".length());
				if (path.contains(dependency)) {
					continue;
				}
				Profile included = parse(dependency, source, options, path);
				values.putAll(included.options());
				disabled.addAll(included.disabledPrograms());
			} else if (entry.startsWith("!")) {
				values.put(entry.substring(1), "false");
			} else {
				int equals = entry.indexOf('=');
				int colon = entry.indexOf(':');
				int split = equals >= 0 ? equals : colon;
				if (split > 0) {
					values.put(entry.substring(0, split), entry.substring(split + 1));
				} else if (options.containsKey(entry) && options.get(entry).isBoolean()) {
					values.put(entry, "true");
				}
			}
		}

		return new Profile(name, values, disabled);
	}

	public Map<String, Profile> profiles() {
		return profiles;
	}

	public Optional<Profile> select(String name) {
		return Optional.ofNullable(profiles.get(name));
	}

	public Optional<Profile> match(Map<String, String> values, Map<String, PackOption> options) {
		List<Profile> candidates = new ArrayList<>(profiles.values());
		candidates.sort(Comparator.comparingInt((Profile profile) -> profile.options().size())
			.reversed().thenComparing(Profile::name));

		for (Profile profile : candidates) {
			if (matches(profile, values, options)) {
				return Optional.of(profile);
			}
		}
		return Optional.empty();
	}

	private static boolean matches(Profile profile, Map<String, String> values, Map<String, PackOption> options) {
		for (Map.Entry<String, String> entry : profile.options().entrySet()) {
			String actual = value(entry.getKey(), values, options);
			if (actual == null || !actual.equals(entry.getValue())) {
				return false;
			}
		}
		return true;
	}

	private static String value(String name, Map<String, String> values, Map<String, PackOption> options) {
		PackOption option = options.get(name);
		if (option == null) {
			return values.get(name);
		}
		String explicit = values.get(name);
		if (option.isBoolean()) {
			if ("true".equals(explicit) || "false".equals(explicit)) {
				return explicit;
			}
			return Boolean.toString(option.booleanDefaultValue());
		}
		return explicit != null ? explicit : option.defaultValue();
	}

	public record Profile(String name, Map<String, String> options, List<String> disabledPrograms) {
		public Profile {
			options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
			disabledPrograms = List.copyOf(disabledPrograms);
		}
	}
}
