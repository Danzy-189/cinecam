package dev.danzy.cinecam.client;

import net.minecraft.network.chat.Component;

public enum CameraMode {
    /** Свободный полёт: мышь крутит камеру, WASD/Space/Shift двигают её. */
    FREE("free"),
    /** Трекер: камера стоит там, где вы её оставили, но всегда смотрит на персонажа. */
    TRACK("track"),
    /** Орбита: камера сама облетает персонажа по кругу и смотрит на него. */
    ORBIT("orbit"),
    /** Следование: камера держит смещение относительно персонажа и смотрит на него. */
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
        CameraMode[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    /** Режимы, в которых камера сама наводится на персонажа. */
    public boolean autoAim() {
        return this != FREE;
    }
}
