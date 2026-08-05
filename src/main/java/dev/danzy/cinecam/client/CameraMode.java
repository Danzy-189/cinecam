package dev.danzy.cinecam.client;

import net.minecraft.network.chat.Component;

public enum CameraMode {
    /** Manual flight, mouse rotates the camera. */
    FREE("free"),
    /** Static rig that always keeps the player in frame. */
    TRACK("track"),
    /** Automatic circular dolly around the player. */
    ORBIT("orbit"),
    /** Drone that keeps an offset from the player and looks at them. */
    FOLLOW("follow");

    private final String id;

    CameraMode(String id) {
        this.id = id;
    }

    public String getId() {
        return this.id;
    }

    public String titleKey() {
        return "cinecam.mode." + this.id;
    }

    public Component title() {
        return Component.translatable(this.titleKey());
    }

    public CameraMode next() {
        CameraMode[] modes = values();
        return modes[(this.ordinal() + 1) % modes.length];
    }

    /** True when the camera aims at the player on its own. */
    public boolean autoAim() {
        return this != FREE;
    }
}
