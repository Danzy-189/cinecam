package dev.danzy.cinecam.client.path;

import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Holds the path being edited plus its playback state. Client side only.
 *
 * <p>Every call that crosses between world space and path space takes an {@link AnchorFrame},
 * so an attached path always converts against one consistent snapshot of the subject.
 */
public final class PathManager {
    private static final PathManager INSTANCE = new PathManager();

    private CameraPath path = new CameraPath("shot");
    private boolean playing;
    private double time;
    private int selected = -1;
    /** Set for one tick when playback of a non looping path reaches the end. */
    private boolean justFinished;

    private PathManager() {}

    public static PathManager get() {
        return INSTANCE;
    }

    public CameraPath path() {
        return this.path;
    }

    public void setPath(CameraPath path) {
        this.path = path;
        this.playing = false;
        this.time = 0.0D;
        this.selected = path.isEmpty() ? -1 : 0;
    }

    public int selected() {
        return this.selected;
    }

    public void select(int index) {
        this.selected = index < 0 || index >= this.path.size() ? -1 : index;
    }

    public Keyframe selectedKeyframe() {
        return this.selected < 0 || this.selected >= this.path.size() ? null : this.path.get(this.selected);
    }

    /** Time the selected keyframe is reached at, or 0 when nothing is selected. */
    public double selectedTime() {
        return this.selected < 0 ? 0.0D : this.path.timeOf(this.selected);
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    /** Switches the coordinate space, keeping the curve visually where it is. */
    public boolean setAnchor(PathAnchor anchor, AnchorFrame frame) {
        return this.path.rebase(anchor, frame);
    }

    /** Appends a world space pose and selects it. */
    public int capture(Vec3 position, float yaw, float pitch, float roll, double fov, double duration,
            AnchorFrame frame) {
        Easing easing = this.path.isEmpty() ? Easing.IN_OUT : this.path.get(this.path.size() - 1).easing;
        this.path.add(new Keyframe(this.path.localPosition(position, frame), this.path.localYaw(yaw, frame),
                pitch, roll, fov, Math.max(CameraPath.MIN_SEGMENT, duration), easing));
        this.selected = this.path.size() - 1;
        return this.selected;
    }

    /** Rewrites the selected keyframe with a new world space pose, keeping its timing. */
    public boolean replaceSelected(Vec3 position, float yaw, float pitch, float roll, double fov,
            AnchorFrame frame) {
        Keyframe keyframe = this.selectedKeyframe();
        if (keyframe == null) {
            return false;
        }
        keyframe.position = this.path.localPosition(position, frame);
        keyframe.yaw = this.path.localYaw(yaw, frame);
        keyframe.pitch = pitch;
        keyframe.roll = roll;
        keyframe.fov = fov;
        return true;
    }

    public void removeSelected() {
        if (this.selected < 0) {
            return;
        }
        this.path.remove(this.selected);
        this.selected = Math.min(this.selected, this.path.size() - 1);
        if (this.path.isEmpty()) {
            this.selected = -1;
            this.playing = false;
        }
    }

    public void moveSelected(int offset) {
        if (this.selected >= 0) {
            this.selected = this.path.move(this.selected, offset);
        }
    }

    /** Drags the selected keyframe along the timeline. */
    public double retimeSelected(double seconds) {
        if (this.selected <= 0) {
            return 0.0D;
        }
        return this.path.retime(this.selected, seconds);
    }

    public void clear() {
        this.path.clear();
        this.selected = -1;
        this.playing = false;
        this.time = 0.0D;
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    public boolean isPlaying() {
        return this.playing;
    }

    public boolean canPlay() {
        return this.path.size() >= 1;
    }

    public double time() {
        return this.time;
    }

    public void seek(double seconds) {
        double total = this.path.duration();
        this.time = total <= 0.0D ? 0.0D : Mth.clamp(seconds, 0.0D, total);
    }

    public boolean play() {
        if (!this.canPlay()) {
            return false;
        }
        this.time = 0.0D;
        this.playing = true;
        this.justFinished = false;
        return true;
    }

    /** Starts from wherever the scrub head is parked instead of from the top. */
    public boolean playFrom(double seconds) {
        if (!this.canPlay()) {
            return false;
        }
        this.seek(seconds);
        double total = this.path.duration();
        if (!this.path.loop() && this.time >= total - 1.0E-4D) {
            this.time = 0.0D;
        }
        this.playing = true;
        this.justFinished = false;
        return true;
    }

    public void stop() {
        this.playing = false;
    }

    public boolean consumeFinished() {
        boolean finished = this.justFinished;
        this.justFinished = false;
        return finished;
    }

    /**
     * Advances playback and returns the world space pose for this tick.
     *
     * @param seconds wall clock seconds to advance, already scaled by the playback speed.
     * @param frame the subject snapshot for attached paths.
     * @return the pose to apply, or {@code null} when nothing is playing.
     */
    public PathSample advance(double seconds, AnchorFrame frame) {
        if (!this.playing) {
            return null;
        }
        PathSample sample = this.path.sampleWorld(this.time, frame);
        if (sample == null) {
            this.playing = false;
            return null;
        }
        double total = this.path.duration();
        this.time += Math.max(0.0D, seconds);
        if (!this.path.loop() && this.time >= total) {
            this.time = total;
            this.playing = false;
            this.justFinished = true;
            PathSample last = this.path.sampleWorld(total, frame);
            return last == null ? sample : last;
        }
        return sample;
    }

    /** World space pose at an arbitrary time, used while scrubbing the timeline. */
    public PathSample sampleAt(double seconds, AnchorFrame frame) {
        return this.path.sampleWorld(seconds, frame);
    }

    /** Progress through the current playback, 0..1. */
    public double progress() {
        double total = this.path.duration();
        if (total <= 0.0D) {
            return 0.0D;
        }
        double clock = this.path.loop() ? ((this.time % total) + total) % total : Mth.clamp(this.time, 0.0D, total);
        return clock / total;
    }

    /** Index of the keyframe the playhead is heading towards. */
    public int currentSegment() {
        double total = this.path.duration();
        if (total <= 0.0D) {
            return 0;
        }
        double clock = this.path.loop() ? ((this.time % total) + total) % total : Mth.clamp(this.time, 0.0D, total);
        double elapsed = 0.0D;
        int segments = this.path.segmentCount();
        for (int index = 0; index < segments; index++) {
            elapsed += this.path.segmentDuration(index);
            if (clock <= elapsed) {
                return index;
            }
        }
        return Math.max(0, segments - 1);
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    public boolean save(String name) {
        this.path.setName(PathStorage.sanitize(name));
        return PathStorage.save(this.path);
    }

    public boolean load(String name) {
        CameraPath loaded = PathStorage.load(name);
        if (loaded == null) {
            return false;
        }
        this.setPath(loaded);
        return true;
    }

    public List<String> storedPaths() {
        return PathStorage.list();
    }
}
