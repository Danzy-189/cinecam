package dev.danzy.cinecam.client.gui;

import dev.danzy.cinecam.client.CameraController;
import dev.danzy.cinecam.client.CameraMode;
import dev.danzy.cinecam.client.CameraSettings;
import dev.danzy.cinecam.client.CineCamKeys;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Camera settings.
 *
 * <p>Split into tabs rather than crammed into one grid: three columns of tiny sliders was
 * reaching its limit, and a shooting rig has more knobs than a screen has room. Does not pause
 * the game, so every change is judged on the live shot.
 */
public class CameraScreen extends Screen {
    private static final int PANEL_WIDTH = 460;
    private static final int PANEL_HEIGHT = 256;
    private static final int WIDGET_WIDTH = 210;
    private static final int COLUMN_ONE = 10;
    private static final int COLUMN_TWO = 240;
    private static final int TAB_WIDTH = 146;
    private static final int FOOTER_WIDTH = 140;
    private static final int ROW_STEP = 20;

    /** Remembered between openings, so a session spent tuning the rig stays on its tab. */
    private static Tab activeTab = Tab.CAMERA;

    private final Map<CameraMode, Button> modeButtons = new EnumMap<>(CameraMode.class);
    private int left;
    private int top;

    public CameraScreen() {
        super(Component.translatable("cinecam.screen.title"));
    }

    private enum Tab {
        CAMERA("cinecam.screen.tab.camera"),
        FOLLOW("cinecam.screen.tab.follow"),
        FRAME("cinecam.screen.tab.frame");

        private final String key;

        Tab(String key) {
            this.key = key;
        }

        Component title() {
            return Component.translatable(this.key);
        }
    }

    @Override
    protected void init() {
        this.modeButtons.clear();
        CameraSettings settings = CameraController.get().settings;
        this.left = Math.max(4, (this.width - PANEL_WIDTH) / 2);
        this.top = Math.max(4, (this.height - PANEL_HEIGHT) / 2);
        int columnOne = this.left + COLUMN_ONE;
        int columnTwo = this.left + COLUMN_TWO;

        // ---- Tabs ----
        int tabX = columnOne;
        for (Tab tab : Tab.values()) {
            Button button = Button.builder(tab.title(), pressed -> {
                activeTab = tab;
                this.rebuildWidgets();
            }).bounds(tabX, this.top + 32, TAB_WIDTH, 18).build();
            button.active = tab != activeTab;
            this.addRenderableWidget(button);
            tabX += TAB_WIDTH + 1;
        }

        switch (activeTab) {
            case CAMERA -> this.buildCameraTab(settings, columnOne, columnTwo);
            case FOLLOW -> this.buildFollowTab(settings, columnOne, columnTwo);
            case FRAME -> this.buildFrameTab(settings, columnOne);
            default -> {
            }
        }

        // ---- Footer ----
        int footerY = this.top + 226;
        this.addRenderableWidget(Button.builder(Component.translatable("cinecam.screen.reset"), pressed -> {
            CameraController.get().settings.resetDefaults();
            this.rebuildWidgets();
        }).bounds(columnOne, footerY, FOOTER_WIDTH, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("cinecam.screen.paths"), pressed -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new PathScreen(this));
            }
        }).bounds(this.left + 160, footerY, FOOTER_WIDTH, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, pressed -> this.onClose())
                .bounds(this.left + 310, footerY, FOOTER_WIDTH, 20).build());
    }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    private void buildCameraTab(CameraSettings settings, int columnOne, int columnTwo) {
        int y = this.top + 60;
        for (CameraMode mode : CameraMode.values()) {
            Button button = Button.builder(mode.title(), pressed -> {
                CameraController.get().setMode(mode);
                this.refreshModeButtons();
            }).bounds(columnOne, y, WIDGET_WIDTH, 18).build();
            this.modeButtons.put(mode, button);
            this.addRenderableWidget(button);
            y += ROW_STEP;
        }
        this.refreshModeButtons();

        this.addRenderableWidget(new CineSlider(columnOne, y, WIDGET_WIDTH, 18, "cinecam.opt.speed",
                0.05D, 2.0D, settings.moveSpeed, 20.0D, "%.1f", value -> settings.moveSpeed = value));
        y += ROW_STEP;
        // Two ramps instead of one "smoothing": a camera that leaves hard and lands soft is
        // the difference between a snap zoom and a crane move.
        this.addRenderableWidget(new CineSlider(columnOne, y, WIDGET_WIDTH, 18, "cinecam.opt.move_accel",
                0.0D, 2.0D, settings.moveAccel, 1.0D, "%.2f", value -> settings.moveAccel = value));
        y += ROW_STEP;
        this.addRenderableWidget(new CineSlider(columnOne, y, WIDGET_WIDTH, 18, "cinecam.opt.move_decel",
                0.0D, 2.0D, settings.moveDecel, 1.0D, "%.2f", value -> settings.moveDecel = value));
        y += ROW_STEP;
        this.addRenderableWidget(new CineSlider(columnOne, y, WIDGET_WIDTH, 18, "cinecam.opt.smoothing",
                0.0D, 0.95D, settings.smoothing, 100.0D, "%.0f%%", value -> settings.smoothing = (float) value));

        int right = this.top + 60;
        this.addRenderableWidget(new CineSlider(columnTwo, right, WIDGET_WIDTH, 18, "cinecam.opt.fov",
                20.0D, 120.0D, settings.fov, 1.0D, "%.0f", value -> settings.fov = value));
        right += ROW_STEP;
        this.addRenderableWidget(this.toggle(columnTwo, right, "cinecam.opt.custom_fov",
                () -> settings.customFov, () -> settings.customFov = !settings.customFov));
        right += ROW_STEP;
        this.addRenderableWidget(this.toggle(columnTwo, right, "cinecam.opt.pitch_flight",
                () -> settings.pitchFlight, () -> settings.pitchFlight = !settings.pitchFlight));
        right += ROW_STEP;
        this.addRenderableWidget(new CineSlider(columnTwo, right, WIDGET_WIDTH, 18, "cinecam.opt.aim_height",
                0.0D, 3.0D, settings.aimHeight, 1.0D, "%.2f", value -> settings.aimHeight = value));
        right += ROW_STEP;
        this.addRenderableWidget(new CineSlider(columnTwo, right, WIDGET_WIDTH, 18, "cinecam.opt.roll",
                -45.0D, 45.0D, settings.roll, 1.0D, "%.1f", value -> settings.roll = (float) value));
        right += ROW_STEP;
        this.addRenderableWidget(new CineSlider(columnTwo, right, WIDGET_WIDTH, 18, "cinecam.opt.orbit_radius",
                1.5D, 32.0D, settings.orbitRadius, 1.0D, "%.1f", value -> settings.orbitRadius = value));
        right += ROW_STEP;
        this.addRenderableWidget(new CineSlider(columnTwo, right, WIDGET_WIDTH, 18, "cinecam.opt.orbit_height",
                -8.0D, 16.0D, settings.orbitHeight, 1.0D, "%.1f", value -> settings.orbitHeight = value));
        right += ROW_STEP;
        this.addRenderableWidget(new CineSlider(columnTwo, right, WIDGET_WIDTH, 18, "cinecam.opt.orbit_speed",
                -60.0D, 60.0D, settings.orbitSpeed, 1.0D, "%.0f", value -> settings.orbitSpeed = value));
    }

    private void buildFollowTab(CameraSettings settings, int columnOne, int columnTwo) {
        int y = this.top + 60;
        this.addRenderableWidget(new CineSlider(columnOne, y, WIDGET_WIDTH, 18, "cinecam.opt.follow_distance",
                0.5D, 16.0D, settings.followDistance, 1.0D, "%.1f", value -> settings.followDistance = value));
        y += ROW_STEP;
        this.addRenderableWidget(new CineSlider(columnOne, y, WIDGET_WIDTH, 18, "cinecam.opt.follow_pitch",
                -45.0D, 70.0D, settings.followPitch, 1.0D, "%.0f", value -> settings.followPitch = (float) value));
        y += ROW_STEP;
        // Hard stops for the mouse: past these the view would either look at the sky or dive
        // through the floor, and neither belongs in a shot.
        this.addRenderableWidget(new CineSlider(columnOne, y, WIDGET_WIDTH, 18, "cinecam.opt.follow_pitch_up",
                0.0D, 85.0D, settings.followPitchUp, 1.0D, "%.0f",
                value -> settings.followPitchUp = (float) value));
        y += ROW_STEP;
        this.addRenderableWidget(new CineSlider(columnOne, y, WIDGET_WIDTH, 18, "cinecam.opt.follow_pitch_down",
                0.0D, 85.0D, settings.followPitchDown, 1.0D, "%.0f",
                value -> settings.followPitchDown = (float) value));
        y += ROW_STEP;
        this.addRenderableWidget(new CineSlider(columnOne, y, WIDGET_WIDTH, 18, "cinecam.opt.follow_shoulder",
                -2.0D, 2.0D, settings.followShoulder, 1.0D, "%.2f", value -> settings.followShoulder = value));
        y += ROW_STEP;
        this.addRenderableWidget(new CineSlider(columnOne, y, WIDGET_WIDTH, 18, "cinecam.opt.follow_stiffness",
                0.0D, 1.0D, settings.followStiffness, 100.0D, "%.0f%%",
                value -> settings.followStiffness = (float) value));
        y += ROW_STEP;
        this.addRenderableWidget(new CineSlider(columnOne, y, WIDGET_WIDTH, 18, "cinecam.opt.follow_recenter",
                0.0D, 1.0D, settings.followRecenter, 100.0D, "%.0f%%",
                value -> settings.followRecenter = (float) value));
        y += ROW_STEP;
        this.addRenderableWidget(new CineSlider(columnOne, y, WIDGET_WIDTH, 18, "cinecam.opt.follow_sensitivity",
                0.2D, 3.0D, settings.followSensitivity, 100.0D, "%.0f%%",
                value -> settings.followSensitivity = value));

        int right = this.top + 60;
        this.addRenderableWidget(new CineSlider(columnTwo, right, WIDGET_WIDTH, 18, "cinecam.opt.follow_ahead",
                0.0D, 4.0D, settings.followLookAhead, 1.0D, "%.1f", value -> settings.followLookAhead = value));
        right += ROW_STEP;
        this.addRenderableWidget(this.toggle(columnTwo, right, "cinecam.opt.follow_collision",
                () -> settings.followCollision, () -> settings.followCollision = !settings.followCollision));
    }

    private void buildFrameTab(CameraSettings settings, int columnOne) {
        int y = this.top + 60;
        this.addRenderableWidget(this.toggle(columnOne, y, "cinecam.opt.letterbox",
                () -> settings.letterbox, () -> settings.letterbox = !settings.letterbox));
        y += ROW_STEP;
        this.addRenderableWidget(new CineSlider(columnOne, y, WIDGET_WIDTH, 18, "cinecam.opt.letterbox_ratio",
                1.33D, 3.0D, settings.letterboxRatio, 1.0D, "%.2f", value -> settings.letterboxRatio = value));
        y += ROW_STEP;
        this.addRenderableWidget(this.toggle(columnOne, y, "cinecam.opt.grid",
                () -> settings.grid, () -> settings.grid = !settings.grid));
        y += ROW_STEP;
        this.addRenderableWidget(this.toggle(columnOne, y, "cinecam.opt.path_guides",
                () -> settings.pathGuides, () -> settings.pathGuides = !settings.pathGuides));
        y += ROW_STEP;
        this.addRenderableWidget(new CineSlider(columnOne, y, WIDGET_WIDTH, 18, "cinecam.opt.path_default",
                0.1D, 10.0D, settings.pathDefaultDuration, 1.0D, "%.1f",
                value -> settings.pathDefaultDuration = value));
        y += ROW_STEP;
        this.addRenderableWidget(new CineSlider(columnOne, y, WIDGET_WIDTH, 18, "cinecam.opt.path_speed",
                0.1D, 3.0D, settings.pathSpeed, 1.0D, "%.2fx", value -> settings.pathSpeed = value));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Button toggle(int x, int y, String key, BooleanSupplier getter, Runnable toggler) {
        return Button.builder(toggleLabel(key, getter.getAsBoolean()), pressed -> {
            toggler.run();
            pressed.setMessage(toggleLabel(key, getter.getAsBoolean()));
        }).bounds(x, y, WIDGET_WIDTH, 18).build();
    }

    private static Component toggleLabel(String key, boolean enabled) {
        Component state = Component.translatable(enabled ? "cinecam.state.on" : "cinecam.state.off")
                .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY);
        return Component.translatable(key).append(Component.literal(": ")).append(state);
    }

    private void refreshModeButtons() {
        CameraMode current = CameraController.get().getMode();
        for (Map.Entry<CameraMode, Button> entry : this.modeButtons.entrySet()) {
            entry.getValue().active = entry.getKey() != current;
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, this.width, this.height, Theme.SCREEN_DIM_TOP, Theme.SCREEN_DIM_BOTTOM);
        Theme.panel(graphics, this.left, this.top, PANEL_WIDTH, PANEL_HEIGHT);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(this.font, this.title, this.left + COLUMN_ONE, this.top + 9, Theme.ACCENT, false);
        graphics.drawString(this.font, Component.translatable("cinecam.screen.subtitle"),
                this.left + COLUMN_ONE, this.top + 21, Theme.TEXT_DIM, false);

        Component subject = Component.translatable("cinecam.screen.subject",
                CameraController.get().targetName());
        graphics.drawString(this.font, subject,
                this.left + PANEL_WIDTH - 10 - this.font.width(subject), this.top + 21, Theme.TEXT, false);

        // Underline the tab in use so the panel reads as one sheet with a marker on it.
        int underline = this.left + COLUMN_ONE + activeTab.ordinal() * (TAB_WIDTH + 1);
        graphics.fill(this.left + COLUMN_ONE, this.top + 51, this.left + PANEL_WIDTH - 10, this.top + 52,
                Theme.BORDER);
        graphics.fill(underline, this.top + 50, underline + TAB_WIDTH, this.top + 52, Theme.ACCENT);

        if (activeTab == Tab.FOLLOW) {
            this.renderHelp(graphics, this.left + COLUMN_TWO, this.top + 106, "cinecam.screen.help.follow.", 5);
        } else if (activeTab == Tab.FRAME) {
            this.renderHelp(graphics, this.left + COLUMN_TWO, this.top + 60, "cinecam.screen.help.keys.", 7);
        }
    }

    private void renderHelp(GuiGraphics graphics, int x, int y, String prefix, int lines) {
        for (int line = 1; line <= lines; line++) {
            graphics.drawString(this.font, Component.translatable(prefix + line), x, y, Theme.TEXT_DIM, false);
            y += 12;
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Custom backdrop is drawn in render() so the live shot stays visible.
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (CineCamKeys.MENU.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        CameraController.get().settings.save();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
