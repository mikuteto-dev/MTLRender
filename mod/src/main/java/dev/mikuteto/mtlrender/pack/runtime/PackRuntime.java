package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.ShaderPack;

import java.util.Optional;

public interface PackRuntime {
	PackRuntime INACTIVE = new PackRuntime() {
		@Override
		public boolean active() {
			return false;
		}

		@Override
		public long packVersion() {
			return 0L;
		}

		@Override
		public void reload(long version, Optional<ShaderPack> pack) {
		}

		@Override
		public void resize(int width, int height) {
		}

		@Override
		public void afterLevelRender(RuntimeFrame frame) {
		}
	};

	boolean active();

	long packVersion();

	void reload(long version, Optional<ShaderPack> pack);

	void resize(int width, int height);

	void afterLevelRender(RuntimeFrame frame);

	static PackRuntime inactive() {
		return INACTIVE;
	}
}
