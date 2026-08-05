package dev.danzy.cinecam.client.gui;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Slider labelled "Name: value". */
public class CineSlider extends AbstractSliderButton {
    private final String translationKey;
    private final double min;
    private final double max;
    private final double displayScale;
    private final String format;
    private final DoubleConsumer setter;

    public CineSlider(int x, int y, int width, int height, String translationKey,
                      double min, double max, double current, double displayScale,
                      String format, DoubleConsumer setter) {
        super(x, y, width, height, Component.empty(), Mth.clamp((current - min) / (max - min), 0.0D, 1.0D));
        this.translationKey = translationKey;
        this.min = min;
        this.max = max;
        this.displayScale = displayScale;
        this.format = format;
        this.setter = setter;
        this.updateMessage();
    }

    public double realValue() {
        return this.min + (this.max - this.min) * this.value;
    }

    @Override
    protected void updateMessage() {
        String text = String.format(Locale.ROOT, this.format, this.realValue() * this.displayScale);
        this.setMessage(Component.translatable(this.translationKey).append(Component.literal(": " + text)));
    }

    @Override
    protected void applyValue() {
        this.setter.accept(this.realValue());
    }
}
