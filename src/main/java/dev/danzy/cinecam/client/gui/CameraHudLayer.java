package dev.danzy.cinecam.client.gui;

import dev.danzy.cinecam.CineCam;
import dev.danzy.cinecam.client.CameraController;
import dev.danzy.cinecam.client.CameraSettings;
import dev.danzy.cinecam.client.CineCamKeys;
import dev.danzy.cinecam.client.path.PathManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/** Camera HUD: cinematic bars, composition grid and the info panel. */
public class CameraHudLayer implements LayeredDraw.Layer {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(CineCam.MOD_ID, "camera_hud");

    public static void register(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ID, new CameraHudLayer());
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        CameraController controller = CameraController.get();
        CameraSettings settings = controller.settings;
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();

        if (settings.letterbox) {
            int visible = (int) (width / Math.max(1.0D, settings.letterboxRatio));
            int bar = Math.max(0, (height - visible) / 2);
            if (bar > 0) {
                graphics.fill(0, 0, width, bar, 0xFF000000);
                graphics.fill(0, height - bar, width, height, 0xFF000000);
            }
        }

        if (!controller.isActive() || controller.isUiHidden()) {
            return;
        }

        if (settings.grid) {
            int thirdX = width / 3;
            int thirdY = height / 3;
            graphics.fill(thirdX, 0, thirdX + 1, height, Theme.GRID);
            graphics.fill(thirdX * 2, 0, thirdX * 2 + 1, height, Theme.GRID);
            graphics.fill(0, thirdY, width, thirdY + 1, Theme.GRID);
            graphics.fill(0, thirdY * 2, width, thirdY * 2 + 1, Theme.GRID);
            int centerX = width / 2;
            int centerY = height / 2;
            graphics.fill(centerX - 6, centerY, centerX + 6, centerY + 1, Theme.ACCENT);
            graphics.fill(centerX, centerY - 6, centerX + 1, centerY + 6, Theme.ACCENT);
        }

        this.renderPanel(graphics, minecraft, controller);
    }

    private void renderPanel(GuiGraphics graphics, Minecraft minecraft, CameraController controller) {
        Font font = minecraft.font;
        CameraSettings settings = controller.settings;
        PathManager paths = PathManager.get();
        Vec3 position = controller.getPosition();
        boolean cameraControl = controller.isControllingCamera();
        boolean playing = paths.isPlaying();

        List<Component> labels = new ArrayList<>();
        List<Component> values = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();

        labels.add(Component.translatable("cinecam.hud.control"));
        values.add(Component.translatable(cameraControl ? "cinecam.hud.control.camera" : "cinecam.hud.control.player"));
        colors.add(cameraControl ? Theme.ACCENT : Theme.GREEN);

        labels.add(Component.translatable("cinecam.hud.target"));
        values.add(controller.targetName());
        colors.add(controller.target().isCustom() ? Theme.ACCENT : Theme.TEXT);

        labels.add(Component.translatable("cinecam.hud.speed"));
        values.add(Component.literal(String.format(Locale.ROOT, "%.1f", controller.speedPerSecond())));
        colors.add(Theme.TEXT);

        labels.add(Component.translatable("cinecam.hud.fov"));
        values.add(Component.literal(String.format(Locale.ROOT, "%.0f", controller.currentFov())));
        colors.add(Theme.TEXT);

        labels.add(Component.translatable("cinecam.hud.smoothing"));
        values.add(Component.literal(String.format(Locale.ROOT, "%.0f%%", settings.smoothing * 100.0F)));
        colors.add(Theme.TEXT);

        if (!paths.path().isEmpty()) {
            labels.add(Component.translatable("cinecam.hud.path"));
            if (playing) {
                values.add(Component.translatable("cinecam.hud.path.playing",
                        String.format(Locale.ROOT, "%.0f", paths.progress() * 100.0D)));
                colors.add(Theme.RED);
            } else {
                values.add(Component.translatable("cinecam.hud.path.ready", paths.path().size(),
                        String.format(Locale.ROOT, "%.1f", paths.path().duration())));
                colors.add(Theme.TEXT);
            }
        }

        labels.add(Component.translatable("cinecam.hud.pos"));
        values.add(Component.literal(String.format(Locale.ROOT, "%.1f %.1f %.1f", position.x, position.y, position.z)));
        colors.add(Theme.TEXT);

        Component title = Component.literal("CINECAM");
        Component modeName = controller.getMode().title();

        int labelWidth = 0;
        int valueWidth = 0;
        for (int i = 0; i < labels.size(); i++) {
            labelWidth = Math.max(labelWidth, font.width(labels.get(i)));
            valueWidth = Math.max(valueWidth, font.width(values.get(i)));
        }
        int headerWidth = font.width(title) + 14 + font.width(modeName);
        int content = Math.max(headerWidth, labelWidth + 14 + valueWidth);
        int padding = 7;
        int lineHeight = font.lineHeight + 3;
        int panelWidth = content + padding * 2;
        int panelHeight = padding * 2 + font.lineHeight + 6 + labels.size() * lineHeight + (playing ? 5 : 0);
        int x = 8;
        int y = 8;

        Theme.panel(graphics, x, y, panelWidth, panelHeight);

        int textX = x + padding;
        int textY = y + padding;
        graphics.fill(textX, textY + 1, textX + 3, textY + 8, playing || cameraControl ? Theme.RED : Theme.TEXT_DIM);
        graphics.drawString(font, title, textX + 7, textY, Theme.ACCENT, false);
        graphics.drawString(font, modeName, x + panelWidth - padding - font.width(modeName), textY, Theme.TEXT, false);

        int rowY = textY + font.lineHeight + 6;
        for (int i = 0; i < labels.size(); i++) {
            Component value = values.get(i);
            graphics.drawString(font, labels.get(i), textX, rowY, Theme.TEXT_DIM, false);
            graphics.drawString(font, value, x + panelWidth - padding - font.width(value), rowY, colors.get(i), false);
            rowY += lineHeight;
        }

        if (playing) {
            // A playhead along the bottom of the panel, so the take can be read at a glance.
            int barLeft = textX;
            int barRight = x + panelWidth - padding;
            int barY = y + panelHeight - padding + 1;
            graphics.fill(barLeft, barY, barRight, barY + 2, Theme.BORDER);
            int filled = (int) ((barRight - barLeft) * Math.max(0.0D, Math.min(1.0D, paths.progress())));
            graphics.fill(barLeft, barY, barLeft + filled, barY + 2, Theme.ACCENT);
        }

        Component hint = Component.translatable("cinecam.hud.hint",
                CineCamKeys.CONTROL.getTranslatedKeyMessage(),
                CineCamKeys.MODE.getTranslatedKeyMessage(),
                CineCamKeys.HIDE_UI.getTranslatedKeyMessage(),
                CineCamKeys.MENU.getTranslatedKeyMessage());
        graphics.drawString(font, hint, x, y + panelHeight + 5, Theme.TEXT_DIM, true);

        Component pathHint = Component.translatable("cinecam.hud.hint.path",
                CineCamKeys.TARGET.getTranslatedKeyMessage(),
                CineCamKeys.KEYFRAME.getTranslatedKeyMessage(),
                CineCamKeys.PLAY.getTranslatedKeyMessage(),
                CineCamKeys.PATHS.getTranslatedKeyMessage());
        graphics.drawString(font, pathHint, x, y + panelHeight + 16, Theme.TEXT_DIM, true);
    }
}
