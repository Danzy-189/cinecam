package dev.danzy.cinecam.client.path;

import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;

/** One recorded camera pose on a path. */
public class Keyframe {
    public Vec3 position;
    public float yaw;
    public float pitch;
    public float roll;
    public double fov;
    /** Seconds spent travelling from the previous keyframe to this one. */
    public double duration;
    public Easing easing;

    public Keyframe(Vec3 position, float yaw, float pitch, float roll, double fov,
            double duration, Easing easing) {
        this.position = position;
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
        this.fov = fov;
        this.duration = duration;
        this.easing = easing;
    }

    public Keyframe copy() {
        return new Keyframe(this.position, this.yaw, this.pitch, this.roll, this.fov,
                this.duration, this.easing);
    }

    /** Unit vector the camera looks along at this keyframe. */
    public Vec3 look() {
        return Vec3.directionFromRotation(this.pitch, this.yaw);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("x", this.position.x);
        json.addProperty("y", this.position.y);
        json.addProperty("z", this.position.z);
        json.addProperty("yaw", this.yaw);
        json.addProperty("pitch", this.pitch);
        json.addProperty("roll", this.roll);
        json.addProperty("fov", this.fov);
        json.addProperty("duration", this.duration);
        json.addProperty("easing", this.easing.id());
        return json;
    }

    public static Keyframe fromJson(JsonObject json) {
        Vec3 position = new Vec3(
                readDouble(json, "x", 0.0D),
                readDouble(json, "y", 0.0D),
                readDouble(json, "z", 0.0D));
        return new Keyframe(position,
                (float) readDouble(json, "yaw", 0.0D),
                (float) readDouble(json, "pitch", 0.0D),
                (float) readDouble(json, "roll", 0.0D),
                readDouble(json, "fov", 70.0D),
                readDouble(json, "duration", 2.0D),
                Easing.byId(json.has("easing") ? json.get("easing").getAsString() : "in_out"));
    }

    private static double readDouble(JsonObject json, String key, double fallback) {
        try {
            return json.has(key) ? json.get(key).getAsDouble() : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
