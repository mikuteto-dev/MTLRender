package dev.mikuteto.mtlrender.pathtracing;

import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType.Skybox;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

public record CelestialLighting(
	float sunDirectionX,
	float sunDirectionY,
	float sunDirectionZ,
	float sunColorR,
	float sunColorG,
	float sunColorB,
	float moonDirectionX,
	float moonDirectionY,
	float moonDirectionZ,
	float moonColorR,
	float moonColorG,
	float moonColorB
) {
	public CelestialLighting() {
		this(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
	}

	public static CelestialLighting from(SkyRenderState sky, Matrix4fc view) {
		if (sky.skybox == Skybox.NONE || sky.skybox == Skybox.END) {
			return new CelestialLighting();
		}
		Vector3f sun = direction(view, sky.sunAngle);
		Vector3f moon = direction(view, sky.moonAngle);
		float brightness = sky.rainBrightness;
		return new CelestialLighting(
			sun.x, sun.y, sun.z, brightness, brightness, brightness,
			moon.x, moon.y, moon.z, brightness, brightness, brightness
		);
	}

	static Vector3f direction(Matrix4fc view, float angle) {
		Matrix4f celestial = new Matrix4f(view).rotateY(-(float) Math.PI / 2.0F).rotateX(angle);
		Vector4f position = celestial.transform(new Vector4f(0.0F, 1.0F, 0.0F, 0.0F));
		return new Vector3f(position.x, position.y, position.z).normalize();
	}
}
