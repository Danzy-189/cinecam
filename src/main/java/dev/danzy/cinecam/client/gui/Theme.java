package dev.danzy.cinecam.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/** Common CineCam UI style. */
public final class Theme {
    public static final int ACCENT = 0xFF4DD2FF;
    public static final int ACCENT_SOFT = 0x804DD2FF;
    public static final int PANEL_TOP = 0xE60E1420;
    public static final int PANEL_BOTTOM = 0xE6060910;
    public static final int BORDER = 0x55FFFFFF;
    public static final int TEXT = 0xFFE8EEF5;
    public static final int TEXT_DIM = 0xFF93A1B0;
    public static final int GREEN = 0xFF7BE38B;
    public static final int RED = 0xFFFF5A5A;
    public static final int GRID = 0x59FFFFFF;
    public static final int SCREEN_DIM_TOP = 0x30000000;
    public static final int SCREEN_DIM_BOTTOM = 0x70000000;

    private Theme() {}

    public static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        int right = x + width;
        int bottom = y + height;
        graphics.fillGradient(x + 2, y, right - 2, bottom, PANEL_TOP, PANEL_BOTTOM);
        graphics.fillGradient(x, y + 2, x + 2, bottom - 2, PANEL_TOP, PANEL_BOTTOM);
        graphics.fillGradient(right - 2, y + 2, right, bottom - 2, PANEL_TOP, PANEL_BOTTOM);
        graphics.fill(x + 2, y, right - 2, y + 1, BORDER);
        graphics.fill(x + 2, bottom - 1, right - 2, bottom, BORDER);
        graphics.fill(x, y + 2, x + 1, bottom - 2, BORDER);
        graphics.fill(right - 1, y + 2, right, bottom - 2, BORDER);
        graphics.fill(x + 2, y + 1, right - 2, y + 2, ACCENT);
        graphics.fill(x + 2, y + 2, x + 3, bottom - 2, ACCENT_SOFT);
    }
}
