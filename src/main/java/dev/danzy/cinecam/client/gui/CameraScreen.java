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

/** Camera settings screen. Does not pause the game, so the shot stays live. */
public class CameraScreen extends Screen {
    private static final int PANEL_WIDTH = 330;
    private static final int PANEL_HEIGHT = 256;
    private static final int COLUMN_WIDTH = 150;

    private final Map<CameraMode, Button> modeButtons = new EnumMap<>(CameraMode.class);
    private int left;
    private int top;

    public CameraScreen() {
        super(Component.translatable("cinecam.screen.title"));
    }

    @Override
    protected void init() {
        this.modeButtons.clear();
        CameraSettings settings = CameraController.get().settings;
        this.left = (this.width - PANEL_WIDTH) / 2;
        this.top = Math.max(4, (this.height - PANEL_HEIGHT) / 2);
        int columnLeft = this.left + 10;
        int columnRight = this.left + 170;

        int modeY = this.top + 46;
        for (CameraMode mode : CameraMode.values()) {
            Button button = Button.builder(mode.title(), pressed -> {
                CameraController.get().setMode(mode);
                this.refreshModeButtons();
            }).bounds(columnLeft, modeY, COLUMN_WIDTH, 18).build();
            this.modeButtons.put(mode, button);
            this.addRenderableWidget(button);
            modeY += 20;
        }
        this.refreshModeButtons();

        int toggleY = this.top + 142;
        this.addRenderableWidget(this.toggle(columnLeft, toggleY, "cinecam.opt.letterbox",
                () -> settings.letterbox, () -> settings.letterbox = !settings.letterbox));
        toggleY += 20;
        this.addRenderableWidget(this.toggle(columnLeft, toggleY, "cinecam.opt.grid",
                () -> settings.grid, () -> settings.grid = !settings.grid));
        toggleY += 20;
        this.addRenderableWidget(this.toggle(columnLeft, toggleY, "cinecam.opt.pitch_flight",
                () -> settings.pitchFlight, () -> settings.pitchFlight = !settings.pitchFlight));
        toggleY += 20;
        this.addRenderableWidget(this.toggle(columnLeft, toggleY, "cinecam.opt.custom_fov",
                () -> settings.customFov, () -> settings.customFov = !settings.customFov));

        int sliderY = this.top + 46;
        this.addRenderableWidget(new CineSlider(columnRight, sliderY, COLUMN_WIDTH, 18, "cinecam.opt.speed",
                0.05D, 2.0D, settings.moveSpeed, 20.0D, "%.1f", value -> settings.moveSpeed = value));
        sliderY += 20;
        this.addRenderableWidget(new CineSlider(columnRight, sliderY, COLUMN_WIDTH, 18, "cinecam.opt.fov",
                20.0D, 120.0D, settings.fov, 1.0D, "%.0f", value -> settings.fov = value));
        sliderY += 20;
        this.addRenderableWidget(new CineSlider(columnRight, sliderY, COLUMN_WIDTH, 18, "cinecam.opt.smoothing",
                0.0D, 0.95D, settings.smoothing, 100.0D, "%.0f%%", value -> settings.smoothing = (float) value));
        sliderY += 20;
        this.addRenderableWidget(new CineSlider(columnRight, sliderY, COLUMN_WIDTH, 18, "cinecam.opt.roll",
                -45.0D, 45.0D, settings.roll, 1.0D, "%.1f", value -> settings.roll = (float) value));
        sliderY += 20;
        this.addRenderableWidget(new CineSlider(columnRight, sliderY, COLUMN_WIDTH, 18, "cinecam.opt.aim_height",
                0.0D, 3.0D, settings.aimHeight, 1.0D, "%.2f", value -> settings.aimHeight = value));
        sliderY += 20;
        this.addRenderableWidget(new CineSlider(columnRight, sliderY, COLUMN_WIDTH, 18, "cinecam.opt.orbit_radius",
                1.5D, 32.0D, settings.orbitRadius, 1.0D, "%.1f", value -> settings.orbitRadius = value));
        sliderY += 20;
        this.addRenderableWidget(new CineSlider(columnRight, sliderY, COLUMN_WIDTH, 18, "cinecam.opt.orbit_height",
                -8.0D, 16.0D, settings.orbitHeight, 1.0D, "%.1f", value -> settings.orbitHeight = value));
        sliderY += 20;
        this.addRenderableWidget(new CineSlider(columnRight, sliderY, COLUMN_WIDTH, 18, "cinecam.opt.orbit_speed",
                -60.0D, 60.0D, settings.orbitSpeed, 1.0D, "%.0f", value -> settings.orbitSpeed = value));

        int footerY = this.top + 226;
        this.addRenderableWidget(Button.builder(Component.translatable("cinecam.screen.reset"), pressed -> {
            CameraController.get().settings.resetDefaults();
            this.rebuildWidgets();
        }).bounds(columnLeft, footerY, COLUMN_WIDTH, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, pressed -> this.onClose())
                .bounds(columnRight, footerY, COLUMN_WIDTH, 20).build());
    }

    private Button toggle(int x, int y, String key, BooleanSupplier getter, Runnable toggler) {
        return Button.builder(toggleLabel(key, getter.getAsBoolean()), pressed -> {
            toggler.run();
            pressed.setMessage(toggleLabel(key, getter.getAsBoolean()));
        }).bounds(x, y, COLUMN_WIDTH, 18).build();
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, this.width, this.height, Theme.SCREEN_DIM_TOP, Theme.SCREEN_DIM_BOTTOM);
        Theme.panel(graphics, this.left, this.top, PANEL_WIDTH, PANEL_HEIGHT);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(this.font, this.title, this.left + 10, this.top + 9, Theme.ACCENT, false);
        graphics.drawString(this.font, Component.translatable("cinecam.screen.subtitle"),
                this.left + 10, this.top + 21, Theme.TEXT_DIM, false);
        graphics.fill(this.left + 10, this.top + 33, this.left + PANEL_WIDTH - 10, this.top + 34, Theme.BORDER);
        graphics.drawString(this.font, Component.translatable("cinecam.screen.section.modes"),
                this.left + 10, this.top + 37, Theme.TEXT_DIM, false);
        graphics.drawString(this.font, Component.translatable("cinecam.screen.section.controls"),
                this.left + 170, this.top + 37, Theme.TEXT_DIM, false);
        graphics.drawString(this.font, Component.translatable("cinecam.screen.section.frame"),
                this.left + 10, this.top + 130, Theme.TEXT_DIM, false);
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
