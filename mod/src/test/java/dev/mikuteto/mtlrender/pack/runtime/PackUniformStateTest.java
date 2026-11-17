package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.ShaderProperties;
import dev.mikuteto.mtlrender.pack.uniform.Type;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PackUniformStateTest {
	@Test
	void compilesCustomUniformsAndVariablesInDependencyOrder() {
		ShaderProperties properties = new ShaderProperties("", "uniform.float.base = rainStrength * 2\nvariable.float.result = base + 1");
		PackUniformState state = new PackUniformState(properties);
		state.gameState().set("rainStrength", 3.0);
		state.updateCustom(1.0 / 60.0);

		assertEquals(java.util.List.of("base", "result"), state.customOrder());
		assertEquals(7.0, state.values().value("result").number(0));
	}

	@Test
	void passValuesOverlayGameStateWithoutChangingIt() {
		PackUniformState state = new PackUniformState(ShaderProperties.empty());
		state.gameState().set("renderStage", 11);
		state.setPassValue("renderStage", Type.INT, 3);

		assertEquals(3, state.values().value("renderStage").integer(0));
		assertEquals(11, state.gameState().value("renderStage").integer(0));
	}
}
