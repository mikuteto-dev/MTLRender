package dev.mikuteto.mtlrender.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class CameraPathTest {
	private static CameraPath path(CameraPath.Interpolation interpolation, CameraPath.Waypoint... points) {
		return new CameraPath("test", interpolation, List.of(points));
	}

	@Test
	void handlesEmptyAndSinglePointPaths() {
		assertEquals(-1, path(CameraPath.Interpolation.LINEAR).sample(4.0).waypointId());
		CameraPath one = path(CameraPath.Interpolation.LINEAR, new CameraPath.Waypoint(7, 0.0, 1.0, 2.0, 3.0, 4.0F, 5.0F));
		assertEquals(new CameraPath.Transform(7, 1.0, 2.0, 3.0, 4.0F, 5.0F), one.sample(60.0));
	}

	@Test
	void preservesWaypointBoundariesAndWraps() {
		CameraPath path = path(CameraPath.Interpolation.LINEAR,
			new CameraPath.Waypoint(10, 0.0, 0.0, 0.0, 0.0, 0.0F, 0.0F),
			new CameraPath.Waypoint(11, 1.0, 10.0, 0.0, 0.0, 90.0F, 0.0F),
			new CameraPath.Waypoint(12, 2.0, 10.0, 10.0, 0.0, 180.0F, 0.0F));
		assertEquals(10, path.sample(0.0).waypointId());
		assertEquals(11, path.sample(1.0).waypointId());
		assertEquals(10, path.sample(2.0).waypointId());
		assertEquals(10, path.sample(2.5).waypointId());
		assertEquals(5.0, path.sample(2.5).x(), 1.0e-9);
	}

	@Test
	void smoothInterpolationIsDeterministicAtRefreshRates() {
		CameraPath path = path(CameraPath.Interpolation.SMOOTH,
			new CameraPath.Waypoint(0, 0.0, 0.0, 0.0, 0.0, 0.0F, 0.0F),
			new CameraPath.Waypoint(1, 1.0, 10.0, 0.0, 0.0, 90.0F, 0.0F));
		assertEquals(path.sample(60.0 / 60.0), path.sample(120.0 / 120.0));
		assertEquals(path.sample(13.0 / 60.0), path.sample(26.0 / 120.0));
	}

	@Test
	void commonReplayTimesHaveTheSameWaypointSequence() {
		CameraPath path = path(CameraPath.Interpolation.LINEAR,
			new CameraPath.Waypoint(3, 0.0, 0.0, 0.0, 0.0, 0.0F, 0.0F),
			new CameraPath.Waypoint(4, 1.0, 10.0, 0.0, 0.0, 30.0F, 0.0F),
			new CameraPath.Waypoint(5, 2.0, 20.0, 0.0, 0.0, 60.0F, 0.0F));
		for (int frame = 0; frame <= 240; frame += 10) {
			assertEquals(path.sample(frame / 60.0), path.sample((frame * 2) / 120.0));
		}
	}
}
