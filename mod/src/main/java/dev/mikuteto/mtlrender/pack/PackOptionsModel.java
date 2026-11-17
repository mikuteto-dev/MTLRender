package dev.mikuteto.mtlrender.pack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class PackOptionsModel {
	public enum Kind { SPACER, SCREEN, PROFILE, OPTION, UNKNOWN }

	public record ScreenEntry(Kind kind, String token, String label, Optional<PackOption> option) {
		public ScreenEntry {
			option = option == null ? Optional.empty() : option;
		}
	}

	public record Screen(String name, String label, int columns, List<ScreenEntry> entries) {
		public Screen {
			entries = List.copyOf(entries);
		}
	}

	private final ShaderPack pack;
	private final PackLanguage language;
	private final Map<String, String> saved;
	private final Map<String, String> staged;
	private final Map<String, Screen> screens;

	public PackOptionsModel(ShaderPack pack, PackLanguage language, Map<String, String> savedValues) {
		this.pack = pack;
		this.language = language == null ? PackLanguage.empty() : language;
		this.saved = copy(savedValues);
		this.staged = copy(savedValues);
		this.screens = buildScreens();
	}

	public static PackOptionsModel load(ShaderPack pack, java.nio.file.Path packPath,
		Map<String, String> savedValues) throws IOException {
		return new PackOptionsModel(pack, PackLanguage.load(packPath), savedValues);
	}

	private Map<String, Screen> buildScreens() {
		Map<String, Screen> result = new LinkedHashMap<>();
		result.put("", makeScreen("", pack.properties().screen(), ""));
		for (Map.Entry<String, List<String>> entry : pack.properties().screens().entrySet()) {
			result.put(entry.getKey(), makeScreen(entry.getKey(), entry.getValue(), entry.getKey()));
		}
		return Map.copyOf(result);
	}

	private Screen makeScreen(String name, List<String> tokens, String labelName) {
		List<ScreenEntry> entries = new ArrayList<>();
		Map<String, PackOption> options = pack.options().allOptions();
		for (String token : tokens) {
			if ("<empty>".equals(token)) {
				entries.add(new ScreenEntry(Kind.SPACER, token, "", Optional.empty()));
			} else if ("<profile>".equals(token)) {
				if (!pack.profileSet().profiles().isEmpty()) entries.add(new ScreenEntry(Kind.PROFILE, token, "Profile", Optional.empty()));
			} else if (token.startsWith("[") && token.endsWith("]") && token.length() > 2) {
				String child = token.substring(1, token.length() - 1);
				entries.add(new ScreenEntry(Kind.SCREEN, token, language.screen(child), Optional.empty()));
			} else {
				PackOption option = options.get(token);
				if (option == null) entries.add(new ScreenEntry(Kind.UNKNOWN, token, token, Optional.empty()));
				else entries.add(new ScreenEntry(Kind.OPTION, token, language.option(token), Optional.of(option)));
			}
		}
		int columns = pack.properties().screenColumns().getOrDefault(name, 2);
		return new Screen(name, name.isEmpty() ? "" : language.screen(labelName), columns, entries);
	}

	public Screen root() { return screens.get(""); }
	public Optional<Screen> screen(String name) { return Optional.ofNullable(screens.get(name)); }
	public Map<String, Screen> screens() { return screens; }
	public List<String> profiles() { return List.copyOf(pack.profileSet().profiles().keySet()); }
	public String profileLabel(String name) { return language.profile(name); }
	public Map<String, String> values() { return Map.copyOf(staged); }
	public Map<String, String> savedValues() { return Map.copyOf(saved); }
	public String value(String name) {
		PackOption option = pack.options().allOptions().get(name);
		if (option == null) return staged.get(name);
		if (option.isBoolean()) return staged.getOrDefault(name, Boolean.toString(option.booleanDefaultValue()));
		return staged.getOrDefault(name, option.defaultValue());
	}
	public boolean isSlider(String name) { return pack.properties().sliders().contains(name); }
	public List<String> sliderValues(String name) {
		PackOption option = pack.options().allOptions().get(name);
		return option == null ? List.of() : option.allowedValues();
	}
	public boolean set(String name, String value) {
		PackOption option = pack.options().allOptions().get(name);
		if (option == null || value == null) return false;
		if (option.isBoolean()) {
			if (!"true".equals(value) && !"false".equals(value)) return false;
		} else if (!option.allowedValues().contains(value)) {
			return false;
		}
		staged.put(name, value);
		return true;
	}
	public boolean dirty() { return !staged.equals(saved); }
	public boolean reloadNeeded() { return dirty(); }

	public boolean cycle(String name, int direction) {
		PackOption option = pack.options().allOptions().get(name);
		if (option == null || direction == 0) return false;
		if (option.isBoolean()) {
			boolean value = Boolean.parseBoolean(staged.getOrDefault(name, Boolean.toString(option.booleanDefaultValue())));
			staged.put(name, Boolean.toString(!value));
			return true;
		}
		List<String> allowed = option.allowedValues();
		if (allowed.isEmpty()) return false;
		String current = staged.getOrDefault(name, option.defaultValue());
		int index = allowed.indexOf(current);
		if (index < 0) index = direction > 0 ? -1 : 0;
		int next = Math.floorMod(index + (direction > 0 ? 1 : -1), allowed.size());
		staged.put(name, allowed.get(next));
		return true;
	}

	public boolean slide(String name, int direction) { return cycle(name, direction); }

	public boolean reset(String name) {
		if (!pack.options().allOptions().containsKey(name)) return false;
		PackOption option = pack.options().allOptions().get(name);
		if (option.isBoolean()) staged.remove(name);
		else staged.remove(name);
		return true;
	}

	public boolean selectProfile(String name) {
		ProfileSet.Profile profile = pack.profileSet().select(name).orElse(null);
		if (profile == null) return false;
		staged.put("profile", name);
		staged.putAll(profile.options());
		return true;
	}

	public void resetAll() { staged.clear(); }
	public void cancel() { staged.clear(); staged.putAll(saved); }

	public PackManager.Result save(PackManager manager, String packName) {
		if (!dirty()) return new PackManager.Result(true, false, packName, Optional.empty());
		PackManager.Result result = manager.apply(packName, staged);
		if (result.success()) {
			saved.clear();
			saved.putAll(staged);
		}
		return result;
	}

	private static Map<String, String> copy(Map<String, String> values) {
		return values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
	}
}
