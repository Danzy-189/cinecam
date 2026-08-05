package dev.danzy.cinecam;

import dev.danzy.cinecam.client.CineCamClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * CineCam - client only cinematic camera for Minecraft 1.21.1 / NeoForge.
 */
@Mod(value = CineCam.MOD_ID, dist = Dist.CLIENT)
public class CineCam {
    public static final String MOD_ID = "cinecam";

    public CineCam(IEventBus modEventBus, ModContainer container) {
        CineCamClient.init(modEventBus);
    }
}
