package dev.danzy.cinecam.client;

import dev.danzy.cinecam.client.gui.CameraHudLayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

public final class CineCamClient {
    private CineCamClient() {}

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(CineCamKeys::register);
        modEventBus.addListener(CameraHudLayer::register);
        NeoForge.EVENT_BUS.register(ClientEvents.class);
        CameraController.get().settings.load();
    }
}
