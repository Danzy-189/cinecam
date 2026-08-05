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

/** Keyframe editor: the shot list on the left, one frame in the middle, the path on the right. */
public class PathScreen extends Screen {
    private static final int PANEL_WIDTH = 480;
    private static final int PANEL_HEIGHT = 256;
    private static final int COLUMN_WIDTH = 150;
    private static final int COLUMN_ONE = 10;
    private static final int COLUMN_TWO = 165;
    private static final int COLUMN_THREE = 320;
    private static final int ROWS = 7;

    private final Screen parent;
    private int left;
    private int top;
    private int page;
    private boolean initialized;
    private String fileName = "";
    private int fileCursor = -1;
    private EditBox nameBox;
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
            if (paths.selected() >= 0) {
                this.page = paths.selected() / ROWS;
            }
        }
        int pages = Math.max(1, (path.size() + ROWS - 1) / ROWS);
        this.page = Math.max(0, Math.min(this.page, pages - 1));

        // ---- Column one: the shot list ----
        int rowY = this.top + 46;
        for (int row = 0; row < ROWS; row++) {
            int index = this.page * ROWS + row;
            if (index >= path.size()) {
                break;
            }
            Keyframe keyframe = path.get(index);
            Component label = Component.literal("#" + (index + 1) + "  "
                            + String.format(Locale.ROOT, "%.1f", keyframe.duration) + "  ")
                    .append(keyframe.easing.title());
            Button button = Button.builder(label, pressed -> {
                PathManager.get().select(index);
                this.rebuildWidgets();
            }).bounds(columnOne, rowY, COLUMN_WIDTH, 18).build();
            button.active = index != paths.selected();
            this.addRenderableWidget(button);
            rowY += 20;
        }

        int pagerY = this.top + 188;
        Button previousPage = Button.builder(Component.literal("<"), pressed -> {
            this.page = Math.max(0, this.page - 1);
            this.rebuildWidgets();
        }).bounds(columnOne, pagerY, 18, 18).build();
        previousPage.active = this.page > 0;
        this.addRenderableWidget(previousPage);

        int lastPage = pages - 1;
        Button nextPage = Button.builder(Component.literal(">"), pressed -> {
            this.page = Math.min(lastPage, this.page + 1);
            this.rebuildWidgets();
        }).bounds(columnOne + COLUMN_WIDTH - 18, pagerY, 18, 18).build();
        nextPage.active = this.page < lastPage;
        this.addRenderableWidget(nextPage);

        // ---- Column two: the selected frame ----
        Keyframe selected = paths.selectedKeyframe();
        if (selected != null) {
            int frameY = this.top + 46;
            this.addRenderableWidget(new CineSlider(columnTwo, frameY, COLUMN_WIDTH, 18, "cinecam.path.duration",
                    0.1D, 20.0D, selected.duration, 1.0D, "%.1f", value -> selected.duration = value));
            frameY += 20;
            this.addRenderableWidget(Button.builder(easingLabel(selected), pressed -> {
                selected.easing = selected.easing.next();
                this.rebuildWidgets();
            }).bounds(columnTwo, frameY, COLUMN_WIDTH, 18).build());
            frameY += 20;
            this.addRenderableWidget(Button.builder(Component.translatable("cinecam.path.update"), pressed -> {
                CameraController camera = CameraController.get();
                PathManager.get().replaceSelected(camera.getPosition(), camera.getYaw(), camera.getPitch(),
                        camera.getRoll(), camera.currentFov());
                this.status = Component.translatable("cinecam.path.status.updated");
                this.rebuildWidgets();
            }).bounds(columnTwo, frameY, COLUMN_WIDTH, 18).build());
            frameY += 20;
            this.addRenderableWidget(Button.builder(Component.translatable("cinecam.path.goto"), pressed ->
                    CameraController.get().applyKeyframe(PathManager.get().selectedKeyframe()))
                    .bounds(columnTwo, frameY, COLUMN_WIDTH, 18).build());
            frameY += 20;
            int half = (COLUMN_WIDTH - 4) / 2;
            Button moveUp = Button.builder(Component.literal("\u25B2"), pressed -> {
                PathManager.get().moveSelected(-1);
                this.page = Math.max(0, PathManager.get().selected() / ROWS);
                this.rebuildWidgets();
            }).bounds(columnTwo, frameY, half, 18).build();
            moveUp.active = paths.selected() > 0;
            this.addRenderableWidget(moveUp);
            Button moveDown = Button.builder(Component.literal("\u25BC"), pressed -> {
                PathManager.get().moveSelected(1);
                this.page = Math.max(0, PathManager.get().selected() / ROWS);
                this.rebuildWidgets();
            }).bounds(columnTwo + COLUMN_WIDTH - half, frameY, half, 18).build();
            moveDown.active = paths.selected() < path.size() - 1;
            this.addRenderableWidget(moveDown);
            frameY += 20;
            this.addRenderableWidget(Button.builder(
                    Component.translatable("cinecam.path.delete").withStyle(ChatFormatting.RED), pressed -> {
                        PathManager.get().removeSelected();
                        this.status = Component.translatable("cinecam.path.status.removed");
                        this.rebuildWidgets();
                    }).bounds(columnTwo, frameY, COLUMN_WIDTH, 18).build());
        }

        // ---- Column three: the path itself ----
        int pathY = this.top + 46;
        Button playButton = Button.builder(paths.isPlaying()
                        ? Component.translatable("cinecam.path.stop").withStyle(ChatFormatting.RED)
                        : Component.translatable("cinecam.path.play").withStyle(ChatFormatting.GREEN),
                pressed -> {
                    CameraController.get().togglePlayback();
                    this.rebuildWidgets();
                }).bounds(columnThree, pathY, COLUMN_WIDTH, 18).build();
        playButton.active = !path.isEmpty();
        this.addRenderableWidget(playButton);
        pathY += 20;
        this.addRenderableWidget(this.toggle(columnThree, pathY, "cinecam.path.loop",
                path::loop, () -> path.setLoop(!path.loop())));
        pathY += 20;
        this.addRenderableWidget(this.toggle(columnThree, pathY, "cinecam.path.aim",
                path::aimTarget, () -> path.setAimTarget(!path.aimTarget())));
        pathY += 20;
        this.addRenderableWidget(this.toggle(columnThree, pathY, "cinecam.opt.path_guides",
                () -> settings.pathGuides, () -> settings.pathGuides = !settings.pathGuides));
        pathY += 20;
        this.addRenderableWidget(new CineSlider(columnThree, pathY, COLUMN_WIDTH, 18, "cinecam.path.tension",
                0.0D, 1.0D, path.tension(), 100.0D, "%.0f%%", path::setTension));
        pathY += 20;
        this.addRenderableWidget(new CineSlider(columnThree, pathY, COLUMN_WIDTH, 18, "cinecam.opt.path_speed",
                0.1D, 3.0D, settings.pathSpeed, 1.0D, "%.2fx", value -> settings.pathSpeed = value));
        pathY += 22;
        this.nameBox = new EditBox(this.font, columnThree, pathY, COLUMN_WIDTH, 18,
                Component.translatable("cinecam.path.name"));
        this.nameBox.setMaxLength(32);
        this.nameBox.setValue(this.fileName);
        this.nameBox.setResponder(value -> this.fileName = value);
        this.addRenderableWidget(this.nameBox);
        pathY += 22;
        int third = (COLUMN_WIDTH - 6) / 3;
        this.addRenderableWidget(Button.builder(Component.translatable("cinecam.path.save"), pressed -> {
            boolean saved = PathManager.get().save(this.fileName);
            this.status = Component.translatable(saved ? "cinecam.path.status.saved" : "cinecam.path.status.failed");
        }).bounds(columnThree, pathY, third, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("cinecam.path.load"), pressed -> {
            boolean loaded = PathManager.get().load(this.fileName);
            this.status = Component.translatable(loaded ? "cinecam.path.status.loaded" : "cinecam.path.status.missing");
            if (loaded) {
                this.page = 0;
            }
            this.rebuildWidgets();
        }).bounds(columnThree + third + 3, pathY, third, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("cinecam.path.files"), pressed ->
                this.cycleFiles()).bounds(columnThree + (third + 3) * 2, pathY, third, 18).build());

        // ---- Footer ----
        int footerY = this.top + 226;
        this.addRenderableWidget(Button.builder(Component.translatable("cinecam.path.add"), pressed -> {
            CameraController camera = CameraController.get();
            if (!camera.isActive()) {
                this.status = Component.translatable("cinecam.path.status.inactive");
            } else {
                camera.captureKeyframe();
                this.status = Component.translatable("cinecam.path.status.added");
                this.page = Math.max(0, (PathManager.get().path().size() - 1) / ROWS);
            }
            this.rebuildWidgets();
        }).bounds(columnOne, footerY, COLUMN_WIDTH, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("cinecam.path.clear"), pressed -> {
            PathManager.get().clear();
            this.page = 0;
            this.status = Component.translatable("cinecam.path.status.cleared");
            this.rebuildWidgets();
        }).bounds(columnTwo, footerY, COLUMN_WIDTH, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, pressed -> this.onClose())
                .bounds(columnThree, footerY, COLUMN_WIDTH, 20).build());
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

        graphics.fill(this.left + COLUMN_ONE, this.top + 33, this.left + PANEL_WIDTH - 10, this.top + 34, Theme.BORDER);
        graphics.drawString(this.font, Component.translatable("cinecam.path.section.frames"),
                this.left + COLUMN_ONE, this.top + 37, Theme.TEXT_DIM, false);
        graphics.drawString(this.font, Component.translatable("cinecam.path.section.frame"),
                this.left + COLUMN_TWO, this.top + 37, Theme.TEXT_DIM, false);
        graphics.drawString(this.font, Component.translatable("cinecam.path.section.path"),
                this.left + COLUMN_THREE, this.top + 37, Theme.ACCENT, false);

        if (path.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("cinecam.path.empty"),
                    this.left + COLUMN_ONE, this.top + 50, Theme.TEXT_DIM, false);
        } else {
            int pages = Math.max(1, (path.size() + ROWS - 1) / ROWS);
            Component pageLabel = Component.literal((this.page + 1) + " / " + pages);
            int centered = this.left + COLUMN_ONE + COLUMN_WIDTH / 2 - this.font.width(pageLabel) / 2;
            graphics.drawString(this.font, pageLabel, centered, this.top + 193, Theme.TEXT_DIM, false);
        }

        Keyframe selected = paths.selectedKeyframe();
        if (selected == null) {
            graphics.drawString(this.font, Component.translatable("cinecam.path.no_selection"),
                    this.left + COLUMN_TWO, this.top + 50, Theme.TEXT_DIM, false);
        } else {
            int infoY = this.top + 170;
            graphics.drawString(this.font, Component.literal(String.format(Locale.ROOT, "XYZ  %.1f  %.1f  %.1f",
                            selected.position.x, selected.position.y, selected.position.z)),
                    this.left + COLUMN_TWO, infoY, Theme.TEXT_DIM, false);
            graphics.drawString(this.font, Component.literal(String.format(Locale.ROOT, "YAW %.1f   PITCH %.1f",
                            selected.yaw, selected.pitch)),
                    this.left + COLUMN_TWO, infoY + 10, Theme.TEXT_DIM, false);
            graphics.drawString(this.font, Component.literal(String.format(Locale.ROOT, "FOV %.0f   ROLL %.1f",
                            selected.fov, selected.roll)),
                    this.left + COLUMN_TWO, infoY + 20, Theme.TEXT_DIM, false);
        }

        if (!this.status.getString().isEmpty()) {
            graphics.drawString(this.font, this.status, this.left + COLUMN_ONE, this.top + 210, Theme.GREEN, false);
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
