package dev.danzy.cinecam.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.danzy.cinecam.client.path.CameraPath;
import dev.danzy.cinecam.client.path.Keyframe;
import dev.danzy.cinecam.client.path.PathManager;
import dev.danzy.cinecam.client.path.PathSample;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Draws the spline and its keyframes straight into the world so a flight can be laid out by
 * eye. The guides disappear during playback and whenever the interface is hidden, so they can
 * never end up in a recording.
 */
public final class PathRenderer {
    private static final int SPLINE = 0xCC4DD2FF;
    private static final int FRAME = 0xCCE8EEF5;
    private static final int FIRST = 0xCC7BE38B;
    private static final int SELECTED = 0xFFFFC24D;
    private static final int SAMPLES_PER_SECOND = 12;
    private static final int MIN_SAMPLES = 24;
    private static final int MAX_SAMPLES = 600;

    private PathRenderer() {}

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        CameraController controller = CameraController.get();
        if (!controller.isActive() || controller.isUiHidden() || !controller.settings.pathGuides) {
            return;
        }
        PathManager paths = PathManager.get();
        if (paths.isPlaying()) {
            // Never let the rails show up inside the take itself.
            return;
        }
        CameraPath path = paths.path();
        if (path.isEmpty()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = pose.last().pose();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        double total = path.duration();
        if (path.size() >= 2 && total > 0.0D) {
            // Sample by time, not by keyframe, so slow segments get more detail than fast ones.
            int steps = Mth.clamp((int) (total * SAMPLES_PER_SECOND), MIN_SAMPLES, MAX_SAMPLES);
            Vec3 previous = null;
            for (int step = 0; step <= steps; step++) {
                PathSample sample = path.sample(total * step / steps);
                if (sample == null) {
                    break;
                }
                if (previous != null) {
                    line(lines, matrix, previous, sample.position(), SPLINE);
                }
                previous = sample.position();
            }
        }

        int selected = paths.selected();
        for (int index = 0; index < path.size(); index++) {
            Keyframe keyframe = path.get(index);
            boolean isSelected = index == selected;
            int color = isSelected ? SELECTED : (index == 0 ? FIRST : FRAME);
            box(lines, matrix, keyframe.position, isSelected ? 0.22D : 0.14D, color);
            // A short whisker showing where that frame looks.
            line(lines, matrix, keyframe.position,
                    keyframe.position.add(keyframe.look().scale(isSelected ? 1.2D : 0.7D)), color);
        }

        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    private static void box(VertexConsumer consumer, Matrix4f matrix, Vec3 center, double size, int color) {
        double minX = center.x - size;
        double minY = center.y - size;
        double minZ = center.z - size;
        double maxX = center.x + size;
        double maxY = center.y + size;
        double maxZ = center.z + size;
        Vec3 a = new Vec3(minX, minY, minZ);
        Vec3 b = new Vec3(maxX, minY, minZ);
        Vec3 c = new Vec3(maxX, minY, maxZ);
        Vec3 d = new Vec3(minX, minY, maxZ);
        Vec3 e = new Vec3(minX, maxY, minZ);
        Vec3 f = new Vec3(maxX, maxY, minZ);
        Vec3 g = new Vec3(maxX, maxY, maxZ);
        Vec3 h = new Vec3(minX, maxY, maxZ);
        line(consumer, matrix, a, b, color);
        line(consumer, matrix, b, c, color);
        line(consumer, matrix, c, d, color);
        line(consumer, matrix, d, a, color);
        line(consumer, matrix, e, f, color);
        line(consumer, matrix, f, g, color);
        line(consumer, matrix, g, h, color);
        line(consumer, matrix, h, e, color);
        line(consumer, matrix, a, e, color);
        line(consumer, matrix, b, f, color);
        line(consumer, matrix, c, g, color);
        line(consumer, matrix, d, h, color);
    }

    private static void line(VertexConsumer consumer, Matrix4f matrix, Vec3 from, Vec3 to, int color) {
        float alpha = ((color >>> 24) & 0xFF) / 255.0F;
        float red = ((color >> 16) & 0xFF) / 255.0F;
        float green = ((color >> 8) & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;
        Vec3 direction = to.subtract(from);
        double length = direction.length();
        float normalX = 0.0F;
        float normalY = 1.0F;
        float normalZ = 0.0F;
        if (length > 1.0E-5D) {
            normalX = (float) (direction.x / length);
            normalY = (float) (direction.y / length);
            normalZ = (float) (direction.z / length);
        }
        consumer.addVertex(matrix, (float) from.x, (float) from.y, (float) from.z)
                .setColor(red, green, blue, alpha)
                .setNormal(normalX, normalY, normalZ);
        consumer.addVertex(matrix, (float) to.x, (float) to.y, (float) to.z)
                .setColor(red, green, blue, alpha)
                .setNormal(normalX, normalY, normalZ);
    }
}
