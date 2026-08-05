package dev.danzy.cinecam.client;

import dev.danzy.cinecam.client.gui.CameraHudLayer;
import dev.danzy.cinecam.client.gui.CameraScreen;
import dev.danzy.cinecam.client.gui.PathScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

public final class ClientEvents {
    private ClientEvents() {}

    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        CameraController.get().beforeTick();
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        handleKeys();
        CameraController.get().tick();
    }

    /**
     * Solves the chase rig once per frame, before anything is rendered.
     *
     * <p>Fires ahead of {@code GameRenderer#render}, and therefore ahead of
     * {@code Camera#setup}, which is what lets the camera entity be handed a pose that is
     * exact for this frame instead of one interpolated between two ticks.
     */
    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        CameraController controller = CameraController.get();
        if (!controller.isActive()) {
            return;
        }
        DeltaTracker tracker = event.getPartialTick();
        controller.updateFrame(tracker.getGameTimeDeltaPartialTick(false), tracker.getGameTimeDeltaTicks());
    }

    private static void handleKeys() {
        Minecraft minecraft = Minecraft.getInstance();
        CameraController controller = CameraController.get();
        while (CineCamKeys.TOGGLE.consumeClick()) {
            toggleCamera(minecraft, controller);
        }
        while (CineCamKeys.CONTROL.consumeClick()) {
            controller.toggleControl();
        }
        while (CineCamKeys.MODE.consumeClick()) {
            controller.cycleMode();
        }
        while (CineCamKeys.HIDE_UI.consumeClick()) {
            controller.toggleUi();
        }
        while (CineCamKeys.RECENTER.consumeClick()) {
            controller.recenter();
        }
        while (CineCamKeys.LETTERBOX.consumeClick()) {
            controller.toggleLetterbox();
        }
        while (CineCamKeys.GRID.consumeClick()) {
            controller.toggleGrid();
        }
        while (CineCamKeys.TARGET.consumeClick()) {
            controller.pickTarget();
        }
        while (CineCamKeys.KEYFRAME.consumeClick()) {
            controller.captureKeyframe();
        }
        while (CineCamKeys.PLAY.consumeClick()) {
            controller.togglePlayback();
        }
        while (CineCamKeys.PATHS.consumeClick()) {
            if (minecraft.screen == null) {
                minecraft.setScreen(new PathScreen());
            }
        }
        while (CineCamKeys.MENU.consumeClick()) {
            if (minecraft.screen == null) {
                minecraft.setScreen(new CameraScreen());
            }
        }
    }

    /**
     * Leaving camera mode while the player was already in control must not snap the view
     * back to the rotation that was frozen when the camera took over, so the current look
     * direction is preserved across the toggle.
     */
    private static void toggleCamera(Minecraft minecraft, CameraController controller) {
        LocalPlayer player = minecraft.player;
        boolean keepLook = player != null && controller.isActive() && !controller.isControllingCamera();
        float yRot = player == null ? 0.0F : player.getYRot();
        float xRot = player == null ? 0.0F : player.getXRot();
        controller.toggle();
        if (keepLook) {
            player.setYRot(yRot);
            player.setXRot(xRot);
            player.yRotO = yRot;
            player.xRotO = xRot;
            player.setYHeadRot(yRot);
            player.yHeadRotO = yRot;
            player.yBodyRot = yRot;
            player.yBodyRotO = yRot;
        }
    }

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        CameraController.get().applyCameraAngles(event);
    }

    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        CameraController controller = CameraController.get();
        // A path carries its own field of view, so it overrides the setting while it plays.
        if (controller.isActive() && (controller.settings.customFov || controller.isPlayingPath())) {
            event.setFOV(controller.currentFov());
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        PathRenderer.render(event);
    }

    /**
     * Movement plumbing.
     *
     * <p>Vanilla only feeds the keyboard input into the player when
     * {@code LocalPlayer#isControlledCamera()} is true, and that method is simply
     * {@code Minecraft#getCameraEntity() == this}. CineCam replaces the camera entity, so
     * {@code LocalPlayer#serverAiStep()} stops writing {@code xxa}/{@code zza}/{@code jumping}
     * and {@code LocalPlayer#aiStep()} stops applying the vertical creative-flight thrust.
     * The result is a player that can only turn its head and sneak, because those two read
     * the raw input instead. This hook fires right after {@code Input#tick()} and before
     * {@code serverAiStep()}, so whatever is written here is exactly what
     * {@code LivingEntity#travel} consumes on the same tick.
     */
    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        CameraController controller = CameraController.get();
        LocalPlayer player = minecraft.player;
        if (player == null || !controller.isActive() || event.getEntity() != player) {
            return;
        }
        Input input = event.getInput();
        if (controller.isControllingCamera()) {
            // The keyboard drives the camera, so the player has to stand still.
            input.up = false;
            input.down = false;
            input.left = false;
            input.right = false;
            input.jumping = false;
            input.shiftKeyDown = false;
            input.forwardImpulse = 0.0F;
            input.leftImpulse = 0.0F;
        }
        drivePlayer(player, input);
    }

    /** Does the work vanilla skips while the camera entity is not the player. */
    private static void drivePlayer(LocalPlayer player, Input input) {
        player.xxa = input.leftImpulse;
        player.zza = input.forwardImpulse;
        player.setJumping(input.jumping);

        Abilities abilities = player.getAbilities();
        if (abilities.flying) {
            int vertical = 0;
            if (input.shiftKeyDown) {
                vertical--;
            }
            if (input.jumping) {
                vertical++;
            }
            if (vertical != 0) {
                double thrust = (double) ((float) vertical * abilities.getFlyingSpeed() * 3.0F);
                player.setDeltaMovement(player.getDeltaMovement().add(0.0D, thrust, 0.0D));
            }
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (CameraController.get().handleScroll(event.getScrollDeltaY())) {
            event.setCanceled(true);
        }
    }

    /** No attacking or item use while the mouse drives the camera. */
    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (CameraController.get().isControllingCamera()) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        CameraController controller = CameraController.get();
        if (CameraHudLayer.ID.equals(event.getName())) {
            return;
        }
        if (controller.isUiHidden()) {
            event.setCanceled(true);
            return;
        }
        // The crosshair only lies while the camera is being flown; when the player is back
        // in control it marks what will actually be hit, so it has to stay visible.
        if (controller.isControllingCamera() && VanillaGuiLayers.CROSSHAIR.equals(event.getName())) {
            event.setCanceled(true);
        }
    }
}
