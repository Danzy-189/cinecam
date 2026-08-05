package dev.danzy.cinecam.client.path;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * An ordered list of keyframes sampled as a smooth Catmull-Rom spline.
 *
 * <p>Every keyframe stores the time it takes to travel to it from the previous one, so the
 * segment between keyframe {@code i} and {@code i + 1} lasts {@code keyframe(i + 1).duration}
 * seconds. On a looping path the closing segment back to the first keyframe uses the first
 * keyframe's own duration, which is otherwise unused.
 */
public class CameraPath {
    public static final double MIN_SEGMENT = 0.05D;

    private final List<Keyframe> keyframes = new ArrayList<>();
    private String name;
    private boolean loop;
    /** Aim at the tracked entity instead of the recorded angles: a dolly with a live subject. */
    private boolean aimTarget;
    /** 0 = straight lines between keyframes, 0.5 = classic Catmull-Rom, 1 = very round. */
    private double tension = 0.5D;

    public CameraPath(String name) {
        this.name = name;
    }

    public String name() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean loop() {
        return this.loop;
    }

    public void setLoop(boolean loop) {
        this.loop = loop;
    }

    public boolean aimTarget() {
        return this.aimTarget;
    }

    public void setAimTarget(boolean aimTarget) {
        this.aimTarget = aimTarget;
    }

    public double tension() {
        return this.tension;
    }

    public void setTension(double tension) {
        this.tension = Mth.clamp(tension, 0.0D, 1.0D);
    }

    public List<Keyframe> keyframes() {
        return this.keyframes;
    }

    public int size() {
        return this.keyframes.size();
    }

    public boolean isEmpty() {
        return this.keyframes.isEmpty();
    }

    public Keyframe get(int index) {
        return this.keyframes.get(index);
    }

    public void add(Keyframe keyframe) {
        this.keyframes.add(keyframe);
    }

    public void insert(int index, Keyframe keyframe) {
        this.keyframes.add(Mth.clamp(index, 0, this.keyframes.size()), keyframe);
    }

    public void remove(int index) {
        if (index >= 0 && index < this.keyframes.size()) {
            this.keyframes.remove(index);
        }
    }

    public void clear() {
        this.keyframes.clear();
    }

    /** Swaps a keyframe with its neighbour and returns its new index. */
    public int move(int index, int offset) {
        int target = index + offset;
        if (index < 0 || index >= this.keyframes.size() || target < 0 || target >= this.keyframes.size()) {
            return index;
        }
        Keyframe moved = this.keyframes.remove(index);
        this.keyframes.add(target, moved);
        return target;
    }

    /** Duration of the segment that ends at keyframe {@code segment + 1}. */
    public double segmentDuration(int segment) {
        int count = this.keyframes.size();
        if (count < 2) {
            return MIN_SEGMENT;
        }
        int target = (segment + 1) % count;
        return Math.max(MIN_SEGMENT, this.keyframes.get(target).duration);
    }

    public int segmentCount() {
        int count = this.keyframes.size();
        if (count < 2) {
            return 0;
        }
        return this.loop ? count : count - 1;
    }

    /** Total playback length in seconds. */
    public double duration() {
        double total = 0.0D;
        int segments = this.segmentCount();
        for (int segment = 0; segment < segments; segment++) {
            total += this.segmentDuration(segment);
        }
        return total;
    }

    /** Time at which the given keyframe is reached. */
    public double timeOf(int index) {
        double total = 0.0D;
        for (int segment = 0; segment < index && segment < this.segmentCount(); segment++) {
            total += this.segmentDuration(segment);
        }
        return total;
    }

    /**
     * Samples the pose at {@code time} seconds.
     *
     * @return the pose, or {@code null} when the path has no keyframes.
     */
    public PathSample sample(double time) {
        int count = this.keyframes.size();
        if (count == 0) {
            return null;
        }
        if (count == 1) {
            Keyframe only = this.keyframes.get(0);
            return new PathSample(only.position, only.yaw, only.pitch, only.roll, only.fov);
        }

        double total = this.duration();
        double clock = time;
        if (this.loop && total > 0.0D) {
            clock = ((clock % total) + total) % total;
        } else {
            clock = Mth.clamp(clock, 0.0D, total);
        }

        int segments = this.segmentCount();
        int segment = segments - 1;
        double local = 1.0D;
        double elapsed = 0.0D;
        for (int index = 0; index < segments; index++) {
            double length = this.segmentDuration(index);
            if (clock <= elapsed + length || index == segments - 1) {
                segment = index;
                local = length <= 0.0D ? 1.0D : (clock - elapsed) / length;
                break;
            }
            elapsed += length;
        }
        local = Mth.clamp(local, 0.0D, 1.0D);

        Keyframe first = this.at(segment);
        Keyframe second = this.at(segment + 1);
        Keyframe before = this.at(segment - 1);
        Keyframe after = this.at(segment + 2);
        double t = second.easing.apply(local);

        Vec3 position = new Vec3(
                spline(before.position.x, first.position.x, second.position.x, after.position.x, t),
                spline(before.position.y, first.position.y, second.position.y, after.position.y, t),
                spline(before.position.z, first.position.z, second.position.z, after.position.z, t));

        // Angles are unwrapped around the current segment first, otherwise a pan across the
        // 180 degree seam would spin the camera all the way around.
        double yaw1 = first.yaw;
        double yaw2 = near(yaw1, second.yaw);
        double yaw0 = near(yaw1, before.yaw);
        double yaw3 = near(yaw2, after.yaw);
        double roll1 = first.roll;
        double roll2 = near(roll1, second.roll);
        double roll0 = near(roll1, before.roll);
        double roll3 = near(roll2, after.roll);

        float yaw = (float) Mth.wrapDegrees(spline(yaw0, yaw1, yaw2, yaw3, t));
        float pitch = (float) Mth.clamp(
                spline(before.pitch, first.pitch, second.pitch, after.pitch, t), -90.0D, 90.0D);
        float roll = (float) Mth.wrapDegrees(spline(roll0, roll1, roll2, roll3, t));
        double fov = Mth.clamp(spline(before.fov, first.fov, second.fov, after.fov, t), 5.0D, 160.0D);
        return new PathSample(position, yaw, pitch, roll, fov);
    }

    /** Clamped on open paths, wrapped on looping ones, so end tangents behave. */
    private Keyframe at(int index) {
        int count = this.keyframes.size();
        if (this.loop) {
            return this.keyframes.get(((index % count) + count) % count);
        }
        return this.keyframes.get(Mth.clamp(index, 0, count - 1));
    }

    private double spline(double before, double from, double to, double after, double t) {
        double tangentFrom = this.tension * (to - before);
        double tangentTo = this.tension * (after - from);
        double t2 = t * t;
        double t3 = t2 * t;
        return (2.0D * t3 - 3.0D * t2 + 1.0D) * from
                + (t3 - 2.0D * t2 + t) * tangentFrom
                + (-2.0D * t3 + 3.0D * t2) * to
                + (t3 - t2) * tangentTo;
    }

    private static double near(double reference, double angle) {
        return reference + Mth.wrapDegrees(angle - reference);
    }

    public CameraPath copy() {
        CameraPath copy = new CameraPath(this.name);
        copy.loop = this.loop;
        copy.aimTarget = this.aimTarget;
        copy.tension = this.tension;
        for (Keyframe keyframe : this.keyframes) {
            copy.keyframes.add(keyframe.copy());
        }
        return copy;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("name", this.name);
        json.addProperty("loop", this.loop);
        json.addProperty("aimTarget", this.aimTarget);
        json.addProperty("tension", this.tension);
        JsonArray array = new JsonArray();
        for (Keyframe keyframe : this.keyframes) {
            array.add(keyframe.toJson());
        }
        json.add("keyframes", array);
        return json;
    }

    public static CameraPath fromJson(JsonObject json) {
        CameraPath path = new CameraPath(json.has("name") ? json.get("name").getAsString() : "path");
        path.loop = json.has("loop") && json.get("loop").getAsBoolean();
        path.aimTarget = json.has("aimTarget") && json.get("aimTarget").getAsBoolean();
        path.setTension(json.has("tension") ? json.get("tension").getAsDouble() : 0.5D);
        if (json.has("keyframes")) {
            for (JsonElement element : json.getAsJsonArray("keyframes")) {
                if (element.isJsonObject()) {
                    path.keyframes.add(Keyframe.fromJson(element.getAsJsonObject()));
                }
            }
        }
        return path;
    }
}
