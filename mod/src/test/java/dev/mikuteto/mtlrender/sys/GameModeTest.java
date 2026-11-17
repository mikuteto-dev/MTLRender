package dev.mikuteto.mtlrender.sys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GameModeTest {
	@Test
	void parsesRequestsAndUsesOffForInvalidValues() {
		assertEquals(GameMode.Request.AUTO, GameMode.Request.parse("auto"));
		assertEquals(GameMode.Request.ON, GameMode.Request.parse("on"));
		assertEquals(GameMode.Request.OFF, GameMode.Request.parse("off"));
		assertEquals(GameMode.Request.OFF, GameMode.Request.parse("unsupported"));
		assertEquals(GameMode.Request.OFF, GameMode.Request.parse(null));
	}

	@Test
	void appliesDefaultAndExplicitPolicy() {
		assertFalse(GameMode.shouldAttempt(GameMode.Request.OFF, true));
		assertFalse(GameMode.shouldAttempt(GameMode.Request.AUTO, false));
		assertTrue(GameMode.shouldAttempt(GameMode.Request.AUTO, true));
		assertTrue(GameMode.shouldAttempt(GameMode.Request.ON, false));
		assertEquals(GameMode.Status.DISABLED, GameMode.evaluate(GameMode.Request.OFF, true, true, true));
		assertEquals(GameMode.Status.UNVERIFIED_BUILD, GameMode.evaluate(GameMode.Request.AUTO, false, true, true));
		assertEquals(GameMode.Status.ENABLED, GameMode.evaluate(GameMode.Request.AUTO, true, true, true));
		assertEquals(GameMode.Status.UNVERIFIED_BUILD, GameMode.evaluate(GameMode.Request.ON, false, true, true));
	}

	@Test
	void repeatedInitializationKeepsOneCurrentResult() {
		GameMode.Snapshot first = GameMode.initialize(GameMode.Request.OFF, null);
		GameMode.Snapshot second = GameMode.initialize(GameMode.Request.OFF, null);
		assertSame(first, second);
		assertEquals("disabled by config", second.failureReason());
	}

	@Test
	void reportsPathAndRequestFailures() {
		assertEquals(GameMode.Status.UNAVAILABLE_PATH, GameMode.evaluate(GameMode.Request.ON, true, false, false));
		assertEquals(GameMode.Status.FAILED_REQUEST, GameMode.evaluate(GameMode.Request.ON, true, true, false));
		assertEquals(GameMode.Result.DISABLED, GameMode.result(GameMode.Status.DISABLED));
		assertEquals(GameMode.Result.FAILED_REQUEST, GameMode.result(GameMode.Status.FAILED_REQUEST));
	}
}
