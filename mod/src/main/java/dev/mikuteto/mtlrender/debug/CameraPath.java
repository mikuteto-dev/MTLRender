package dev.mikuteto.mtlrender.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CameraPath {
	public enum Interpolation {
		LINEAR,
		SMOOTH
	}

	public record Waypoint(int id, double timeSeconds, double x, double y, double z, float yaw, float pitch) {
		public Waypoint {
			if (timeSeconds < 0.0 || !Double.isFinite(timeSeconds)) {
				throw new IllegalArgumentException("invalid waypoint time");
			}
			if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
				throw new IllegalArgumentException("invalid waypoint transform");
			}
		}
	}

	public record Transform(int waypointId, double x, double y, double z, float yaw, float pitch) {
	}

	private final String id;
	private final Interpolation interpolation;
	private final List<Waypoint> waypoints;
	private final double durationSeconds;

	public CameraPath(String id, Interpolation interpolation, List<Waypoint> waypoints) {
		this.id = Objects.requireNonNull(id);
		this.interpolation = Objects.requireNonNull(interpolation);
		List<Waypoint> copy = new ArrayList<>(waypoints);
		for (int i = 1; i < copy.size(); i++) {
			if (copy.get(i - 1).timeSeconds() >= copy.get(i).timeSeconds()) {
				throw new IllegalArgumentException("waypoint times must increase");
			}
		}
		this.waypoints = List.copyOf(copy);
		this.durationSeconds = copy.isEmpty() ? 0.0 : copy.getLast().timeSeconds();
	}

	public String id() {
		return id;
	}

	public Interpolation interpolation() {
		return interpolation;
	}

	public List<Waypoint> waypoints() {
		return waypoints;
	}

	public double durationSeconds() {
		return durationSeconds;
	}

	public Transform sample(double replayTimeSeconds) {
		if (waypoints.isEmpty()) {
			return new Transform(-1, 0.0, 0.0, 0.0, 0.0F, 0.0F);
		}
		if (waypoints.size() == 1 || durationSeconds == 0.0) {
			return transform(waypoints.getFirst());
		}
		double time = replayTimeSeconds - Math.floor(replayTimeSeconds / durationSeconds) * durationSeconds;
		int next = 1;
		while (next < waypoints.size() && waypoints.get(next).timeSeconds() <= time) {
			next++;
		}
		Waypoint before = waypoints.get(next - 1);
		Waypoint after = next < waypoints.size() ? waypoints.get(next) : waypoints.getFirst();
		double end = next < waypoints.size() ? after.timeSeconds() : durationSeconds;
		double span = end - before.timeSeconds();
		double amount = span == 0.0 ? 0.0 : (time - before.timeSeconds()) / span;
		if (next == waypoints.size()) {
			amount = (time - before.timeSeconds()) / (durationSeconds - before.timeSeconds());
		}
		double eased = interpolation == Interpolation.SMOOTH ? amount * amount * (3.0 - 2.0 * amount) : amount;
		return interpolate(before, after, eased, next == waypoints.size());
	}

	private static Transform transform(Waypoint point) {
		return new Transform(point.id(), point.x(), point.y(), point.z(), point.yaw(), point.pitch());
	}

	private static Transform interpolate(Waypoint before, Waypoint after, double amount, boolean wraps) {
		double x = before.x() + (after.x() - before.x()) * amount;
		double y = before.y() + (after.y() - before.y()) * amount;
		double z = before.z() + (after.z() - before.z()) * amount;
		float yaw = angle(before.yaw(), after.yaw(), amount);
		float pitch = (float) (before.pitch() + (after.pitch() - before.pitch()) * amount);
		int id = amount == 0.0 ? before.id() : (wraps ? after.id() : before.id());
		return new Transform(id, x, y, z, yaw, pitch);
	}

	private static float angle(float before, float after, double amount) {
		float delta = (after - before + 540.0F) % 360.0F - 180.0F;
		return before + (float) (delta * amount);
	}

	public static CameraPath recorded() {
		return recorded("recorded");
	}

	public static CameraPath recorded(String id) {
		return new CameraPath(id, Interpolation.SMOOTH, List.of(
			new Waypoint(0, 0.0, 0.0, 0.0, 0.0, 45.0F, 10.0F),
			new Waypoint(1, 4.0, 24.0, 5.0, 24.0, 0.0F, 8.0F),
			new Waypoint(2, 8.0, 0.0, 10.0, 48.0, -45.0F, 12.0F),
			new Waypoint(3, 12.0, -24.0, 5.0, 24.0, -90.0F, 8.0F),
			new Waypoint(4, 16.0, 0.0, 0.0, 0.0, -135.0F, 10.0F)
		));
	}
}
