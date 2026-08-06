package dev.danzy.cinecam.client.path;

import net.minecraft.network.chat.Component;

/**
 * Coordinate space a camera path lives in.
 *
 * <p>A world path is nailed to the map: the same three blocks above the same hill, forever.
 * An attached path is measured from the current camera target instead, so the whole curve
 * travels with a boat, a horse, a minecart or the player. {@link #TARGET_TURN} additionally
 * rotates the curve with the subject's heading, which is what turns a static flyaround into a
 * proper chase shot: the camera keeps its place relative to the vehicle even as the vehicle
 * turns.
 */
public enum PathAnchor {
    /** Absolute world coordinates. */
    WORLD("world"),
    /** Offsets from the subject, but the curve keeps its compass heading. */
    TARGET("target"),
    /** Offsets in the subject's own frame: the curve turns with it. */
    TARGET_TURN("target_turn");

    private final String id;

    PathAnchor(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    /** True when keyframes are stored as offsets from the subject instead of world positions. */
    public boolean attached() {
        return this != WORLD;
    }

    /** True when those offsets also rotate with the subject. */
    public boolean rotates() {
        return this == TARGET_TURN;
    }

    public Component title() {
        return Component.translatable("cinecam.anchor." + this.id);
    }

    public PathAnchor next() {
        PathAnchor[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public static PathAnchor byId(String id) {
        for (PathAnchor anchor : values()) {
            if (anchor.id.equals(id)) {
                return anchor;
            }
        }
        return WORLD;
    }
}
