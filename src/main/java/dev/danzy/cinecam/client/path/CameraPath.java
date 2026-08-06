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
 *
 * <p>Keyframes are stored in the space named by {@link #anchor()}. For a world path that is
 * simply world coordinates; for an attached path they are offsets from the camera subject, and
 * every read has to be converted through an {@link AnchorFrame} first. Use
 * {@link #sampleWorld(double, AnchorFrame)} and {@link #worldPosition(Vec3, AnchorFrame)}
 * whenever the result is going to be shown or flown; the plain {@link #sample(double)} stays in
 * path space and is only useful for shape analysis such as the editor's speed curve.
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
    /** Coordinate space the keyframes above are written in. */
    private PathAnchor anchor = PathAnchor.WORLD;

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

    public PathAnchor anchor() {
        return this.anchor;
    }

    /**
     * Moves the path into another coordinate space, rewriting every keyframe so the curve stays
     * exactly where the operator sees it right now.
     *
     * @param frame the subject snapshot to measure against; without one the path is only
     *     relabelled, because there is nothing to be relative to.
     * @return true when the keyframes were actually converted.
     */
    public boolean rebase(PathAnchor next, AnchorFrame frame) {
        if (next == null || next == this.anchor) {
            return false;
        }
        if (frame == null || !frame.valid()) {
            this.anchor = next;
            return false;
        }
        PathAnchor previous = this.anchor;
        for (Keyframe keyframe : this.keyframes) {
            Vec3 world = previous.attached()
                    ? frame.toWorld(keyframe.position, previous.rotates())
                    : keyframe.position;
            float yaw = frame.toWorldYaw(keyframe.yaw, previous.rotates());
            keyframe.position = next.attached() ? frame.toLocal(world, next.rotates()) : world;
            keyframe.yaw = frame.toLocalYaw(yaw, next.rotates());
        }
        this.anchor = next;
        return true;
    }

    // ------------------------------------------------------------------
    // Space conversion
    // ------------------------------------------------------------------

    /** Converts a stored position into world space. */
    public Vec3 worldPosition(Vec3 stored, AnchorFrame frame) {
        if (!this.anchor.attached() || frame == null || !frame.valid()) {
            return stored;
        }
        return frame.toWorld(stored, this.anchor.rotates());
    }

    /** Converts a world position into the path's own space. */
    public Vec3 localPosition(Vec3 world, AnchorFrame frame) {
        if (!this.anchor.attached() || frame == null || !frame.valid()) {
            return world;
        }
        return frame.toLocal(world, this.anchor.rotates());
    }

    public float worldYaw(float stored, AnchorFrame frame) {
        if (!this.anchor.rotates() || frame == null || !frame.valid()) {
            return stored;
        }
        return frame.toWorldYaw(stored, true);
    }

    public float localYaw(float world, AnchorFrame frame) {
        if (!this.anchor.rotates() || frame == null || !frame.valid()) {
            return world;
        }
        return frame.toLocalYaw(world, true);
    }

    /** World space pose at {@code time} seconds, ready to be flown. */
    public PathSample sampleWorld(double time, AnchorFrame frame) {
        PathSample local = this.sample(time);
        if (local == null || !this.anchor.attached() || frame == null || !frame.valid()) {
            return local;
        }
        return new PathSample(
                frame.toWorld(local.position(), this.anchor.rotates()),
                frame.toWorldYaw(local.yaw(), this.anchor.rotates()),
                local.pitch(),
                local.roll(),
                local.fov());
    }

    // ------------------------------------------------------------------
    // Keyframes
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Timing
    // ------------------------------------------------------------------

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
     * Retimes a keyframe so it is reached at {@code time} seconds, by stretching or squashing
     * the segment that leads into it. Later keyframes keep their own segment lengths and simply
     * slide along, which is what dragging a handle on the timeline should feel like.
     *
     * @return the time the keyframe actually ended up at.
     */
    public double retime(int index, double time) {
        if (index <= 0 || index >= this.keyframes.size()) {
            return this.timeOf(index);
        }
        double before = this.timeOf(index - 1);
        Keyframe keyframe = this.keyframes.get(index);
        keyframe.duration = Mth.clamp(time - before, MIN_SEGMENT, 30.0D);
        return before + keyframe.duration;
    }

    // ------------------------------------------------------------------
    // Sampling
    // ------------------------------------------------------------------

    /**
     * Samples the pose at {@code time} seconds, in the path's own coordinate space.
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

    /**
     * Metres per second the camera travels at {@code time}, measured on the curve itself.
     * Used by the editor to draw the speed graph, so easing mistakes are visible before the
     * take instead of after it.
     */
    public double speedAt(double time) {
        double step = 0.02D;
        PathSample before = this.sample(Math.max(0.0D, time - step));
        PathSample after = this.sample(time + step);
        if (before == null || after == null) {
            return 0.0D;
        }
        double span = Math.min(time + step, this.duration()) - Math.max(0.0D, time - step);
        if (span <= 1.0E-4D) {
            return 0.0D;
        }
        return after.position().distanceTo(before.position()) / span;
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
        copy.anchor = this.anchor;
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
        json.addProperty("anchor", this.anchor.id());
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
        path.anchor = PathAnchor.byId(json.has("anchor") ? json.get("anchor").getAsString() : "world");
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
