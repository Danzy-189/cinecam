package dev.danzy.cinecam.client.gui;

import dev.danzy.cinecam.client.CameraController;
import dev.danzy.cinecam.client.path.CameraPath;
import dev.danzy.cinecam.client.path.Keyframe;
import dev.danzy.cinecam.client.path.PathManager;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * A horizontal timeline for the path being edited.
 *
 * <p>Three things are stacked inside it. On top a ruler in seconds. In the middle the keyframe
 * handles, which can be grabbed and dragged to retime a move without ever opening a slider.
 * At the bottom the speed graph: the real distance the camera covers per second along the
 * curve, so a segment that lurches or stalls is visible before the take instead of after it.
 *
 * <p>Clicking anywhere on the empty track scrubs, and scrubbing parks the live camera on that
 * exact point of the flight, which turns the editor into a viewfinder.
 */
public class TimelineWidget extends AbstractWidget {
    private static final int RULER_HEIGHT = 9;
    private static final int TRACK_HEIGHT = 14;
    private static final int PADDING = 5;
    /** How close the cursor has to be to grab a handle. */
    private static final int GRAB = 4;
    private static final int BACKGROUND = 0x66060B14;
    private static final int BAND_EVEN = 0x14FFFFFF;
    private static final int BAND_ODD = 0x0AFFFFFF;
    private static final int SELECTED = 0xFFFFC24D;
    private static final int PLAYHEAD = 0xFFFFFFFF;
    private static final double[] RULER_STEPS = {0.5D, 1.0D, 2.0D, 5.0D, 10.0D, 15.0D, 30.0D, 60.0D};

    private final Font font;
    private final Runnable onEdit;

    private float[] curve = new float[0];
    private double peak;
    private long signature = Long.MIN_VALUE;
    private int dragIndex = -1;
    private boolean scrubbing;
    private int hovered = -1;

    public TimelineWidget(int x, int y, int width, int height, Font font, Runnable onEdit) {
        super(x, y, width, height, Component.translatable("cinecam.path.timeline"));
        this.font = font;
        this.onEdit = onEdit;
    }

    /** Forces the speed graph to be recomputed on the next frame. */
    public void refresh() {
        this.signature = Long.MIN_VALUE;
    }

    /** Index of the keyframe under the cursor, or -1. */
    public int hovered() {
        return this.hovered;
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    private int trackLeft() {
        return this.getX() + PADDING;
    }

    private int span() {
        return Math.max(1, this.width - PADDING * 2);
    }

    private double timeAt(double mouseX) {
        double duration = PathManager.get().path().duration();
        if (duration <= 0.0D) {
            return 0.0D;
        }
        double progress = (mouseX - this.trackLeft()) / this.span();
        return Mth.clamp(progress, 0.0D, 1.0D) * duration;
    }

    private int pixelOf(double time) {
        double duration = PathManager.get().path().duration();
        if (duration <= 0.0D) {
            return this.trackLeft();
        }
        return this.trackLeft() + (int) Math.round(Mth.clamp(time / duration, 0.0D, 1.0D) * this.span());
    }

    private int handleAt(double mouseX, double mouseY) {
        int trackTop = this.getY() + RULER_HEIGHT;
        if (mouseY < trackTop - 2 || mouseY > trackTop + TRACK_HEIGHT + 2) {
            return -1;
        }
        CameraPath path = PathManager.get().path();
        int best = -1;
        double closest = GRAB + 1.0D;
        for (int index = 0; index < path.size(); index++) {
            double distance = Math.abs(mouseX - this.pixelOf(path.timeOf(index)));
            if (distance <= GRAB && distance < closest) {
                closest = distance;
                best = index;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Speed graph
    // ------------------------------------------------------------------

    private void ensureCurve(CameraPath path) {
        long current = fingerprint(path);
        if (current == this.signature) {
            return;
        }
        this.signature = current;
        int columns = Mth.clamp(this.span(), 16, 192);
        float[] values = new float[columns];
        double duration = path.duration();
        double highest = 0.0D;
        if (duration > 0.0D && path.size() >= 2) {
            for (int column = 0; column < columns; column++) {
                double time = duration * (column + 0.5D) / columns;
                double speed = path.speedAt(time);
                values[column] = (float) speed;
                if (speed > highest) {
                    highest = speed;
                }
            }
            if (highest > 1.0E-4D) {
                for (int column = 0; column < columns; column++) {
                    values[column] = (float) (values[column] / highest);
                }
            }
        }
        this.peak = highest;
        this.curve = values;
    }

    /** Cheap change detector: rebuild the graph only when the shape or the timing moved. */
    private static long fingerprint(CameraPath path) {
        long hash = 1469598103934665603L;
        hash = mix(hash, path.size());
        hash = mix(hash, path.loop() ? 1L : 0L);
        hash = mix(hash, Double.doubleToLongBits(path.tension()));
        for (Keyframe keyframe : path.keyframes()) {
            hash = mix(hash, Double.doubleToLongBits(keyframe.duration));
            hash = mix(hash, keyframe.easing.ordinal());
            hash = mix(hash, Double.doubleToLongBits(keyframe.position.x));
            hash = mix(hash, Double.doubleToLongBits(keyframe.position.y));
            hash = mix(hash, Double.doubleToLongBits(keyframe.position.z));
        }
        return hash;
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * 1099511628211L;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        PathManager paths = PathManager.get();
        CameraPath path = paths.path();
        this.ensureCurve(path);
        this.hovered = this.isMouseOver(mouseX, mouseY) ? this.handleAt(mouseX, mouseY) : -1;

        int left = this.getX();
        int top = this.getY();
        int right = left + this.width;
        int bottom = top + this.height;
        int trackTop = top + RULER_HEIGHT;
        int trackBottom = trackTop + TRACK_HEIGHT;
        int curveTop = trackBottom + 2;
        int curveBottom = bottom - 2;

        graphics.fill(left, top, right, bottom, BACKGROUND);
        graphics.fill(left, top, right, top + 1, Theme.BORDER);
        graphics.fill(left, bottom - 1, right, bottom, Theme.BORDER);
        graphics.fill(left, top, left + 1, bottom, Theme.BORDER);
        graphics.fill(right - 1, top, right, bottom, Theme.BORDER);

        if (path.isEmpty()) {
            Component empty = Component.translatable("cinecam.path.empty");
            graphics.drawString(this.font, empty,
                    left + this.width / 2 - this.font.width(empty) / 2, top + this.height / 2 - 4,
                    Theme.TEXT_DIM, false);
            return;
        }

        double duration = path.duration();

        // Alternating bands, one per segment, so the cuts are readable at a glance.
        int segments = path.segmentCount();
        for (int segment = 0; segment < segments; segment++) {
            int from = this.pixelOf(path.timeOf(segment));
            int to = this.pixelOf(path.timeOf(segment) + path.segmentDuration(segment));
            graphics.fill(from, trackTop, Math.max(to, from + 1), trackBottom,
                    (segment & 1) == 0 ? BAND_EVEN : BAND_ODD);
        }

        // Ruler.
        double step = RULER_STEPS[RULER_STEPS.length - 1];
        for (double candidate : RULER_STEPS) {
            if (duration <= 0.0D || candidate / duration * this.span() >= 30.0D) {
                step = candidate;
                break;
            }
        }
        for (double time = 0.0D; time <= duration + 1.0E-6D; time += step) {
            int tick = this.pixelOf(time);
            graphics.fill(tick, top + 2, tick + 1, trackTop, Theme.BORDER);
            String label = String.format(Locale.ROOT, step < 1.0D ? "%.1f" : "%.0f", time);
            int labelX = Math.min(tick + 2, right - 2 - this.font.width(label));
            graphics.drawString(this.font, label, labelX, top + 1, Theme.TEXT_DIM, false);
        }

        // Speed graph.
        graphics.fill(left + 1, curveBottom, right - 1, curveBottom + 1, Theme.BORDER);
        int columns = this.curve.length;
        int graphHeight = Math.max(1, curveBottom - curveTop);
        for (int column = 0; column < columns; column++) {
            int fromX = this.trackLeft() + column * this.span() / columns;
            int toX = this.trackLeft() + (column + 1) * this.span() / columns;
            int height = (int) Math.round(this.curve[column] * graphHeight);
            if (height <= 0) {
                continue;
            }
            graphics.fill(fromX, curveBottom - height, Math.max(toX, fromX + 1), curveBottom, Theme.ACCENT_SOFT);
            graphics.fill(fromX, curveBottom - height, Math.max(toX, fromX + 1),
                    curveBottom - height + 1, Theme.ACCENT);
        }
        if (this.peak > 0.01D) {
            Component unit = Component.translatable("cinecam.path.speed_unit",
                    String.format(Locale.ROOT, "%.1f", this.peak));
            graphics.drawString(this.font, unit, right - 4 - this.font.width(unit), curveTop, Theme.TEXT_DIM, false);
        }

        // Keyframe handles.
        int selected = paths.selected();
        for (int index = 0; index < path.size(); index++) {
            int handle = this.pixelOf(path.timeOf(index));
            int color = index == selected ? SELECTED : (index == 0 ? Theme.GREEN : Theme.TEXT);
            if (index == this.hovered && index != selected) {
                color = Theme.ACCENT;
            }
            graphics.fill(handle - 2, trackTop + 1, handle + 3, trackBottom - 1, color);
            graphics.fill(handle - 1, trackTop + 2, handle + 2, trackBottom - 2, 0x99000000);
            graphics.fill(handle, trackTop + 1, handle + 1, trackBottom - 1, color);
        }

        // Playhead.
        int head = this.pixelOf(paths.time());
        graphics.fill(head, top + 1, head + 1, bottom - 1, PLAYHEAD);
        graphics.fill(head - 2, top + 1, head + 3, top + 2, PLAYHEAD);
        graphics.fill(head - 1, top + 2, head + 2, top + 3, PLAYHEAD);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, this.getMessage());
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.active || !this.visible || button != 0 || !this.isMouseOver(mouseX, mouseY)) {
            return false;
        }
        PathManager paths = PathManager.get();
        CameraPath path = paths.path();
        if (path.isEmpty()) {
            return false;
        }
        int index = this.handleAt(mouseX, mouseY);
        if (index >= 0) {
            paths.select(index);
            this.dragIndex = index;
            this.scrubbing = false;
            double time = path.timeOf(index);
            CameraController.get().previewPath(time);
            this.onEdit.run();
            return true;
        }
        this.dragIndex = -1;
        this.scrubbing = true;
        CameraController.get().previewPath(this.timeAt(mouseX));
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button != 0) {
            return false;
        }
        PathManager paths = PathManager.get();
        if (this.dragIndex > 0 && paths.selected() == this.dragIndex) {
            // Dragging a handle stretches the segment that leads into it; everything after
            // keeps its own timing and simply slides along.
            double actual = paths.retimeSelected(this.timeAt(mouseX));
            this.refresh();
            CameraController.get().previewPath(actual);
            return true;
        }
        if (this.dragIndex == 0) {
            // The opening frame is always at zero, there is nothing to retime.
            return true;
        }
        if (this.scrubbing) {
            CameraController.get().previewPath(this.timeAt(mouseX));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = this.dragIndex >= 0 || this.scrubbing;
        boolean retimed = this.dragIndex > 0;
        this.dragIndex = -1;
        this.scrubbing = false;
        if (retimed) {
            this.onEdit.run();
        }
        return handled;
    }
}
