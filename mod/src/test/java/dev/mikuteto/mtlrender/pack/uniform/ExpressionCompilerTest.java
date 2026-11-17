package dev.mikuteto.mtlrender.pack.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ExpressionCompilerTest {
	@Test
	void evaluatesOperatorsFunctionsAndSwizzles() {
		ExpressionCompiler.Program program = ExpressionCompiler.compile(Type.FLOAT, "2 + 3 * 4 + frac(-1.25) + vec3(1, 2, 3).z");
		assertEquals(17.75, program.evaluate(1.0 / 60.0).number(0), 0.000001);
	}

	@Test
	void evaluatesSmoothWithExplicitId() {
		UniformValues inputs = new GameState();
		inputs.set("x", 1.0);
		UniformEngine engine = new UniformEngine(inputs, List.of(new UniformEngine.Definition("smoothed", Type.FLOAT, "smooth(9, x, 1, 1)", true)));
		engine.update(0.1);
		assertEquals(1.0, engine.value("smoothed").number(0), 0.000001);
		inputs.set("x", 0.0);
		engine.update(0.1);
		if (!(engine.value("smoothed").number(0) > 0.0 && engine.value("smoothed").number(0) < 1.0)) {
			throw new AssertionError("smooth did not retain state");
		}
	}

	@Test
	void ordersDependenciesAndRejectsCycles() {
		UniformValues inputs = new GameState();
		inputs.set("x", 2.0);
		UniformEngine engine = new UniformEngine(inputs, List.of(
			new UniformEngine.Definition("b", Type.FLOAT, "a + 1", true),
			new UniformEngine.Definition("a", Type.FLOAT, "x * 2", false)
		));
		engine.update();
		assertEquals(List.of("a", "b"), engine.order());
		assertEquals(5.0, engine.value("b").number(0), 0.000001);
		assertThrows(IllegalArgumentException.class, () -> new UniformEngine(inputs, List.of(
			new UniformEngine.Definition("a", Type.FLOAT, "b", true),
			new UniformEngine.Definition("b", Type.FLOAT, "a", true)
		)));
	}
}
