package dev.danzy.cinecam.client.gui;

import dev.danzy.cinecam.client.CameraController;
import dev.danzy.cinecam.client.CameraSettings;
import dev.danzy.cinecam.client.CineCamKeys;
import dev.danzy.cinecam.client.path.CameraPath;
import dev.danzy.cinecam.client.path.Keyframe;
import dev.danzy.cinecam.client.path.PathManager;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The path editor.
 *
 * <p>The whole top of the panel is a timeline: keyframes sit on it as handles, they are dragged
 * to retime a move, the empty track scrubs the shot and the speed graph underneath shows what
 * the audience will actually feel. Everything below is the detail of whatever is selected.
 */
public class PathScreen extends Screen {
    private static final int PANEL_WIDTH = 460;
    private static final int PANEL_HEIGHT = 256;
    private static final int COLUMN_WIDTH = 140;
    private static final int COLUMN_ONE = 10;
    private static final int COLUMN_TWO = 160;
    private static final int COLUMN_THREE = 310;
    private static final int TIMELINE_HEIGHT = 46;

    private final Screen parent;
    private int left;
    private int top;
    private boolean initialized;
    private String fileName = "";
    private int fileCursor = -1;
    private EditBox nameBox;
    /** Kept between rebuilds so a drag survives the widgets being recreated under it. */
    private TimelineWidget timeline;
    private Component status = Component.empty();

    public PathScreen() {
        this(null);
    }

    public PathScreen(Screen parent) {
        super(Component.translatable("cinecam.path.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        PathManager paths = PathManager.get();
        CameraPath path = paths.path();
        CameraSettings settings = CameraController.get().settings;
        this.left = Math.max(4, (this.width - PANEL_WIDTH) / 2);
        this.top = Math.max(4, (this.height - PANEL_HEIGHT) / 2);
        int columnOne = this.left + COLUMN_ONE;
        int columnTwo = this.left + COLUMN_TWO;
        int columnThree = this.left + COLUMN_THREE;

        if (!this.initialized) {
            this.initialized = true;
            this.fileName = path.name();
        }

        // ---- Timeline ----
        int timelineWidth = PANEL_WIDTH - COLUMN_ONE * 2;
        if (this.timeline == null) {
            this.timeline = new TimelineWidget(columnOne, this.top + 34, timelineWidth, TIMELINE_HEIGHT,
                    this.font, this::onTimelineEdit);
        } else {
            this.timeline.setPosition(columnOne, this.top + 34);
        }
        this.addRenderableWidget(this.timeline);

        // ---- Column one: the selected keyframe ----
        Keyframe selected = paths.selectedKeyframe();
        if (selected != null) {
            int frameY = this.top + 98;
            this.addRenderableWidget(new CineSlider(columnOne, frameY, COLUMN_WIDTH, 18, "cinecam.path.duration",
                    0.1D, 20.0D, selected.duration, 1.0D, "%.1f", value -> {
                        selected.duration = value;
                        this.timeline.refresh();
                    }));
            frameY += 20;
            this.addRenderableWidget(Button.builder(easingLabel(selected), pressed -> {
                selected.easing = selected.easing.next();
                this.timeline.refresh();
                this.rebuildWidgets();
            }).bounds(columnOne, frameY, COLUMN_WIDTH, 18).build());
            frameY += 20;
            this.addRenderableWidget(Button.builder(Component.translatable("cinecam.path.update"), pressed -> {
                CameraController camera = CameraController.get();
                PathManager.get().replaceSelected(camera.getPosition(), camera.getYaw(), camera.getPitch(),
                        camera.getRoll(), camera.currentFov(), camera.anchorFrame());
                this.status = Component.translatable("cinecam.path.status.updated");
                this.timeline.refresh();
                this.rebuildWidgets();
            }).bounds(columnOne, frameY, COLUMN_WIDTH, 18).build());
            frameY += 20;
            int half = (COLUMN_WIDTH - 4) / 2;
            Button moveUp = Button.builder(Component.literal("\u25B2"), pressed -> {
                PathManager.get().moveSelected(-1);
                this.timeline.refresh();
                this.rebuildWidgets();
            }).bounds(columnOne, frameY, half, 18).build();
            moveUp.active = paths.selected() > 0;
            this.addRenderableWidget(moveUp);
            Button moveDown = Button.builder(Component.literal("\u25BC"), pressed -> {
                PathManager.get().moveSelected(1);
                this.timeline.refresh();
                this.rebuildWidgets();
            }).bounds(columnOne + COLUMN_WIDTH - half, frameY, half, 18).build();
            moveDown.active = paths.selected() < path.size() - 1;
            this.addRenderableWidget(moveDown);
            frameY += 20;
            this.addRenderableWidget(Button.builder(Component.translatable("cinecam.path.goto_short"), pressed ->
                    CameraController.get().applyKeyframe(PathManager.get().selectedKeyframe()))
                    .bounds(columnOne, frameY, half, 18).build());
            this.addRenderableWidget(Button.builder(
                    Component.translatable("cinecam.path.delete_short").withStyle(ChatFormatting.RED), pressed -> {
                        PathManager.get().removeSelected();
                        this.status = Component.translatable("cinecam.path.status.removed");
                        this.timeline.refresh();
                        this.rebuildWidgets();
                    }).bounds(columnOne + COLUMN_WIDTH - half, frameY, half, 18).build());
        }

        // ---- Column two: the path itself ----
        int pathY = this.top + 98;
        Button playButton = Button.builder(paths.isPlaying()
                        ? Component.translatable("cinecam.path.stop").withStyle(ChatFormatting.RED)
                        : Component.translatable("cinecam.path.play").withStyle(ChatFormatting.GREEN),
                pressed -> {
                    CameraController.get().togglePlayback();
                    this.rebuildWidgets();
                }).bounds(columnTwo, pathY, COLUMN_WIDTH, 18).build();
        playButton.active = !path.isEmpty();
        this.addRenderableWidget(playButton);
        pathY += 20;
        this.addRenderableWidget(Button.builder(anchorLabel(path), pressed -> {
            // Re-anchoring keeps the curve where it looks right now and only changes what it
            // is measured against, so switching space never scrambles a finished shot.
            PathManager.get().setAnchor(path.anchor().next(), CameraController.get().anchorFrame());
            this.timeline.refresh();
            this.rebuildWidgets();
        }).bounds(columnTwo, pathY, COLUMN_WIDTH, 18).build());
        pathY += 20;
        this.addRenderableWidget(this.toggle(columnTwo, pathY, "cinecam.path.loop",
                path::loop, () -> path.setLoop(!path.loop())));
        pathY += 20;
        this.addRenderableWidget(this.toggle(columnTwo, pathY, "cinecam.path.aim",
                path::aimTarget, () -> path.setAimTarget(!path.aimTarget())));
        pathY += 20;
        this.addRenderableWidget(new CineSlider(columnTwo, pathY, COLUMN_WIDTH, 18, "cinecam.path.tension",
                0.0D, 1.0D, path.tension(), 100.0D, "%.0f%%", value -> {
                    path.setTension(value);
                    this.timeline.refresh();
                }));

        // ---- Column three: playback and files ----
        int fileY = this.top + 98;
        this.addRenderableWidget(this.toggle(columnThree, fileY, "cinecam.opt.path_guides",
                () -> settings.pathGuides, () -> settings.pathGuides = !settings.pathGuides));
        fileY += 20;
        this.addRenderableWidget(new CineSlider(columnThree, fileY, COLUMN_WIDTH, 18, "cinecam.opt.path_speed",
                0.1D, 3.0D, settings.pathSpeed, 1.0D, "%.2fx", value -> settings.pathSpeed = value));
        fileY += 20;
        this.addRenderableWidget(new CineSlider(columnThree, fileY, COLUMN_WIDTH, 18, "cinecam.opt.path_default",
                0.1D, 10.0D, settings.pathDefaultDuration, 1.0D, "%.1f",
                value -> settings.pathDefaultDuration = value));
        fileY += 20;
        this.nameBox = new EditBox(this.font, columnThree, fileY, COLUMN_WIDTH, 18,
                Component.translatable("cinecam.path.name"));
        this.nameBox.setMaxLength(32);
        this.nameBox.setValue(this.fileName);
        this.nameBox.setResponder(value -> this.fileName = value);
        this.addRenderableWidget(this.nameBox);
        fileY += 20;
        int third = (COLUMN_WIDTH - 6) / 3;
        this.addRenderableWidget(Button.builder(Component.translatable("cinecam.path.save"), pressed -> {
            boolean saved = PathManager.get().save(this.fileName);
            this.status = Component.translatable(saved ? "cinecam.path.status.saved" : "cinecam.path.status.failed");
        }).bounds(columnThree, fileY, third, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("cinecam.path.load"), pressed -> {
            boolean loaded = PathManager.get().load(this.fileName);
            this.status = Component.translatable(loaded ? "cinecam.path.status.loaded" : "cinecam.path.status.missing");
            this.timeline.refresh();
            this.rebuildWidgets();
        }).bounds(columnThree + third + 3, fileY, third, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("cinecam.path.files"), pressed ->
                this.cycleFiles()).bounds(columnThree + (third + 3) * 2, fileY, third, 18).build());

        // ---- Footer ----
        int footerY = this.top + 226;
        this.addRenderableWidget(Button.builder(Component.translatable("cinecam.path.add"), pressed -> {
            CameraController camera = CameraController.get();
            if (!camera.isActive()) {
                this.status = Component.translatable("cinecam.path.status.inactive");
            } else {
                camera.captureKeyframe();
                this.status = Component.translatable("cinecam.path.status.added");
            }
            this.timeline.refresh();
            this.rebuildWidgets();
        }).bounds(columnOne, footerY, COLUMN_WIDTH, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("cinecam.path.clear"), pressed -> {
            PathManager.get().clear();
            this.status = Component.translatable("cinecam.path.status.cleared");
            this.timeline.refresh();
            this.rebuildWidgets();
        }).bounds(columnTwo, footerY, COLUMN_WIDTH, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, pressed -> this.onClose())
                .bounds(columnThree, footerY, COLUMN_WIDTH, 20).build());
    }

    /** Called by the timeline when a handle was selected or retimed. */
    private void onTimelineEdit() {
        this.status = Component.empty();
        this.rebuildWidgets();
    }

    private void cycleFiles() {
        List<String> files = PathManager.get().storedPaths();
        if (files.isEmpty()) {
            this.status = Component.translatable("cinecam.path.status.no_files");
            return;
        }
        this.fileCursor = (this.fileCursor + 1) % files.size();
        this.fileName = files.get(this.fileCursor);
        if (this.nameBox != null) {
            this.nameBox.setValue(this.fileName);
        }
        this.status = Component.translatable("cinecam.path.status.files", files.size());
    }

    private static Component easingLabel(Keyframe keyframe) {
        return Component.translatable("cinecam.path.easing")
                .append(Component.literal(": "))
                .append(keyframe.easing.title());
    }

    private static Component anchorLabel(CameraPath path) {
        return Component.translatable("cinecam.path.anchor")
                .append(Component.literal(": "))
                .append(path.anchor().title().copy().withStyle(
                        path.anchor().attached() ? ChatFormatting.AQUA : ChatFormatting.GRAY));
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, this.width, this.height, Theme.SCREEN_DIM_TOP, Theme.SCREEN_DIM_BOTTOM);
        Theme.panel(graphics, this.left, this.top, PANEL_WIDTH, PANEL_HEIGHT);
        super.render(graphics, mouseX, mouseY, partialTick);

        PathManager paths = PathManager.get();
        CameraPath path = paths.path();

        graphics.drawString(this.font, this.title, this.left + COLUMN_ONE, this.top + 9, Theme.ACCENT, false);
        graphics.drawString(this.font, Component.translatable("cinecam.path.subtitle"),
                this.left + COLUMN_ONE, this.top + 21, Theme.TEXT_DIM, false);

        Component total = Component.translatable("cinecam.path.total", path.size(),
                String.format(Locale.ROOT, "%.1f", path.duration()));
        graphics.drawString(this.font, total,
                this.left + PANEL_WIDTH - 10 - this.font.width(total), this.top + 21, Theme.TEXT, false);

        graphics.fill(this.left + COLUMN_ONE, this.top + 84, this.left + PANEL_WIDTH - 10, this.top + 85,
                Theme.BORDER);
        graphics.drawString(this.font, Component.translatable("cinecam.path.section.frame"),
                this.left + COLUMN_ONE, this.top + 88, Theme.TEXT_DIM, false);
        graphics.drawString(this.font, Component.translatable("cinecam.path.section.path"),
                this.left + COLUMN_TWO, this.top + 88, Theme.ACCENT, false);
        graphics.drawString(this.font, Component.translatable("cinecam.path.section.file"),
                this.left + COLUMN_THREE, this.top + 88, Theme.TEXT_DIM, false);

        if (paths.selectedKeyframe() == null) {
            graphics.drawString(this.font, Component.translatable("cinecam.path.no_selection"),
                    this.left + COLUMN_ONE, this.top + 102, Theme.TEXT_DIM, false);
        }

        // Detail line: the frame under the cursor wins over the selected one, so hovering the
        // timeline reads out the whole shot without clicking anything.
        int detail = this.timeline != null && this.timeline.hovered() >= 0
                ? this.timeline.hovered()
                : paths.selected();
        if (detail >= 0 && detail < path.size()) {
            Keyframe keyframe = path.get(detail);
            String space = path.anchor().attached() ? "REL" : "XYZ";
            String line = String.format(Locale.ROOT,
                    "#%d  %s %.1f %.1f %.1f   Y %.0f  P %.0f  R %.0f   FOV %.0f   %.1fs",
                    detail + 1, space, keyframe.position.x, keyframe.position.y, keyframe.position.z,
                    keyframe.yaw, keyframe.pitch, keyframe.roll, keyframe.fov, keyframe.duration);
            graphics.drawString(this.font, line, this.left + COLUMN_ONE, this.top + 202, Theme.TEXT_DIM, false);
        }

        if (!this.status.getString().isEmpty()) {
            graphics.drawString(this.font, this.status, this.left + COLUMN_ONE, this.top + 213, Theme.GREEN, false);
        } else {
            graphics.drawString(this.font, Component.translatable("cinecam.path.hint"),
                    this.left + COLUMN_ONE, this.top + 213, Theme.TEXT_DIM, false);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Custom backdrop is drawn in render() so the live shot stays visible.
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // The name box owns the keyboard while it is focused, otherwise typing "o" would
        // close the editor.
        if (this.nameBox != null && this.nameBox.isFocused()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (CineCamKeys.PATHS.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        CameraController.get().settings.save();
        if (this.parent != null && this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
            return;
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
