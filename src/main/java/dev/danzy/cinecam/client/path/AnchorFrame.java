package dev.danzy.cinecam.client.path;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The reference frame an attached path is measured against for one moment in time.
 *
 * <p>Built once per tick (or per rendered frame) from the current camera subject and then
 * handed to every conversion, so a whole path is always converted against a single consistent
 * snapshot instead of a subject that keeps moving underneath us.
 *
 * <p>The rotation follows Minecraft's yaw convention: at yaw 0 the subject faces +Z, so its
 * local forward axis is +Z and its local side axis is +X. {@link #toWorld} and
 * {@link #toLocal} are exact inverses of each other.
 */
public record AnchorFrame(Vec3 origin, float yaw, boolean valid) {
    /** Used when there is no subject to measure against; conversions become no-ops. */
    public static final AnchorFrame NONE = new AnchorFrame(Vec3.ZERO, 0.0F, false);

    public static AnchorFrame of(Vec3 origin, float yaw) {
        return new AnchorFrame(origin, Mth.wrapDegrees(yaw), true);
    }

    /** World position -> offset in the subject's frame. */
    public Vec3 toLocal(Vec3 world, boolean rotate) {
        if (!this.valid) {
            return world;
        }
        Vec3 offset = world.subtract(this.origin);
        if (!rotate) {
            return offset;
        }
        double radians = Math.toRadians(this.yaw);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        return new Vec3(offset.x * cos + offset.z * sin, offset.y, -offset.x * sin + offset.z * cos);
    }

    /** Offset in the subject's frame -> world position. */
    public Vec3 toWorld(Vec3 local, boolean rotate) {
        if (!this.valid) {
            return local;
        }
        Vec3 offset = local;
        if (rotate) {
            double radians = Math.toRadians(this.yaw);
            double sin = Math.sin(radians);
            double cos = Math.cos(radians);
            offset = new Vec3(local.x * cos - local.z * sin, local.y, local.x * sin + local.z * cos);
        }
        return this.origin.add(offset);
    }

    public float toLocalYaw(float world, boolean rotate) {
        return rotate && this.valid ? Mth.wrapDegrees(world - this.yaw) : world;
    }

    public float toWorldYaw(float local, boolean rotate) {
        return rotate && this.valid ? Mth.wrapDegrees(local + this.yaw) : local;
    }
}
