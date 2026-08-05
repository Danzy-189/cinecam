package dev.danzy.cinecam.client.path;

import net.minecraft.network.chat.Component;

/** Interpolation curve used for the segment that ends at a keyframe. */
public enum Easing {
    /** Constant speed. */
    LINEAR("linear"),
    /** Starts slow, arrives fast. */
    IN("in"),
    /** Starts fast, arrives slow. */
    OUT("out"),
    /** Slow on both ends, the default cinematic move. */
    IN_OUT("in_out"),
    /** Freezes on the previous keyframe for the whole segment: a held shot. */
    HOLD("hold");

    private final String id;

    Easing(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    public Component title() {
        return Component.translatable("cinecam.easing." + this.id);
    }

    public Easing next() {
        Easing[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public static Easing byId(String id) {
        for (Easing easing : values()) {
            if (easing.id.equals(id)) {
                return easing;
            }
        }
        return IN_OUT;
    }

    /** Maps a linear 0..1 progress onto the curve. */
    public double apply(double progress) {
        double t = Math.max(0.0D, Math.min(1.0D, progress));
        switch (this) {
            case LINEAR:
                return t;
            case IN:
                return t * t * t;
            case OUT: {
                double inverse = 1.0D - t;
                return 1.0D - inverse * inverse * inverse;
            }
            case HOLD:
                return 0.0D;
            case IN_OUT:
            default: {
                if (t < 0.5D) {
                    return 4.0D * t * t * t;
                }
                double inverse = -2.0D * t + 2.0D;
                return 1.0D - inverse * inverse * inverse / 2.0D;
            }
        }
    }
}
