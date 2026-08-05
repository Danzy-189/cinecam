package dev.danzy.cinecam.client;

import dev.danzy.cinecam.client.gui.CameraHudLayer;
import dev.danzy.cinecam.client.gui.CameraScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
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

    private static void handleKeys() {
        Minecraft minecraft = Minecraft.getInstance();
        CameraController controller = CameraController.get();
        while (CineCamKeys.TOGGLE.consumeClick()) {
            controller.toggle();
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
        while (CineCamKeys.MENU.consumeClick()) {
            if (minecraft.screen == null) {
                minecraft.setScreen(new CameraScreen());
            }
        }
    }

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        CameraController.get().applyCameraAngles(event);
    }

    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        CameraController controller = CameraController.get();
        if (controller.isActive() && controller.settings.customFov) {
            event.setFOV(controller.currentFov());
        }
    }

    /** While the camera is driven, the player must not walk around. */
    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        CameraController controller = CameraController.get();
        if (!controller.isControllingCamera() || event.getEntity() != Minecraft.getInstance().player) {
            return;
        }
        Input input = event.getInput();
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
        input.forwardImpulse = 0.0F;
        input.leftImpulse = 0.0F;
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
        if (controller.isActive() && VanillaGuiLayers.CROSSHAIR.equals(event.getName())) {
            event.setCanceled(true);
        }
    }
}
