package dev.danzy.cinecam.client;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.minecraft.util.Mth;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

/** Client settings persisted to config/cinecam-client.properties. */
public class CameraSettings {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "cinecam-client.properties";

    /** Blocks per tick. */
    public double moveSpeed = 0.35D;
    public double fov = 70.0D;
    public boolean customFov = true;
    /** 0 = instant camera, 0.95 = very heavy dolly. */
    public float smoothing = 0.45F;
    public float roll = 0.0F;
    public boolean pitchFlight = true;
    public boolean letterbox = false;
    public double letterboxRatio = 2.39D;
    public boolean grid = false;
    public double orbitRadius = 6.0D;
    public double orbitHeight = 2.0D;
    /** Degrees per second. */
    public double orbitSpeed = 12.0D;
    /** Aim point height above the player's feet. */
    public double aimHeight = 1.4D;

    // Third person follow camera.
    /** Length of the spring arm behind the player. */
    public double followDistance = 4.5D;
    /** Resting elevation of the arm, in degrees below the horizon. */
    public float followPitch = 18.0F;
    /** Sideways offset for an over the shoulder frame. */
    public double followShoulder = 0.0D;
    /** 0 = the camera never swings behind the player, 1 = it snaps there. */
    public float followAlign = 0.35F;
    /** Pull the camera in instead of letting it clip into blocks. */
    public boolean followCollision = true;

    public void resetDefaults() {
        this.moveSpeed = 0.35D;
        this.fov = 70.0D;
        this.customFov = true;
        this.smoothing = 0.45F;
        this.roll = 0.0F;
        this.pitchFlight = true;
        this.letterbox = false;
        this.letterboxRatio = 2.39D;
        this.grid = false;
        this.orbitRadius = 6.0D;
        this.orbitHeight = 2.0D;
        this.orbitSpeed = 12.0D;
        this.aimHeight = 1.4D;
        this.followDistance = 4.5D;
        this.followPitch = 18.0F;
        this.followShoulder = 0.0D;
        this.followAlign = 0.35F;
        this.followCollision = true;
        this.save();
    }

    public void clampAll() {
        this.moveSpeed = Mth.clamp(this.moveSpeed, 0.02D, 4.0D);
        this.fov = Mth.clamp(this.fov, 10.0D, 130.0D);
        this.smoothing = Mth.clamp(this.smoothing, 0.0F, 0.95F);
        this.roll = Mth.clamp(this.roll, -180.0F, 180.0F);
        this.letterboxRatio = Mth.clamp(this.letterboxRatio, 1.0D, 4.0D);
        this.orbitRadius = Mth.clamp(this.orbitRadius, 1.5D, 64.0D);
        this.orbitHeight = Mth.clamp(this.orbitHeight, -16.0D, 32.0D);
        this.orbitSpeed = Mth.clamp(this.orbitSpeed, -120.0D, 120.0D);
        this.aimHeight = Mth.clamp(this.aimHeight, -2.0D, 4.0D);
        this.followDistance = Mth.clamp(this.followDistance, 0.5D, 24.0D);
        this.followPitch = Mth.clamp(this.followPitch, -80.0F, 80.0F);
        this.followShoulder = Mth.clamp(this.followShoulder, -3.0D, 3.0D);
        this.followAlign = Mth.clamp(this.followAlign, 0.0F, 1.0F);
    }

    public void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.warn("[CineCam] Could not read {}", path, exception);
            return;
        }
        this.moveSpeed = readDouble(properties, "moveSpeed", this.moveSpeed);
        this.fov = readDouble(properties, "fov", this.fov);
        this.customFov = readBoolean(properties, "customFov", this.customFov);
        this.smoothing = (float) readDouble(properties, "smoothing", this.smoothing);
        this.roll = (float) readDouble(properties, "roll", this.roll);
        this.pitchFlight = readBoolean(properties, "pitchFlight", this.pitchFlight);
        this.letterbox = readBoolean(properties, "letterbox", this.letterbox);
        this.letterboxRatio = readDouble(properties, "letterboxRatio", this.letterboxRatio);
        this.grid = readBoolean(properties, "grid", this.grid);
        this.orbitRadius = readDouble(properties, "orbitRadius", this.orbitRadius);
        this.orbitHeight = readDouble(properties, "orbitHeight", this.orbitHeight);
        this.orbitSpeed = readDouble(properties, "orbitSpeed", this.orbitSpeed);
        this.aimHeight = readDouble(properties, "aimHeight", this.aimHeight);
        this.followDistance = readDouble(properties, "followDistance", this.followDistance);
        this.followPitch = (float) readDouble(properties, "followPitch", this.followPitch);
        this.followShoulder = readDouble(properties, "followShoulder", this.followShoulder);
        this.followAlign = (float) readDouble(properties, "followAlign", this.followAlign);
        this.followCollision = readBoolean(properties, "followCollision", this.followCollision);
        this.clampAll();
    }

    public void save() {
        this.clampAll();
        Properties properties = new Properties();
        properties.setProperty("moveSpeed", Double.toString(this.moveSpeed));
        properties.setProperty("fov", Double.toString(this.fov));
        properties.setProperty("customFov", Boolean.toString(this.customFov));
        properties.setProperty("smoothing", Float.toString(this.smoothing));
        properties.setProperty("roll", Float.toString(this.roll));
        properties.setProperty("pitchFlight", Boolean.toString(this.pitchFlight));
        properties.setProperty("letterbox", Boolean.toString(this.letterbox));
        properties.setProperty("letterboxRatio", Double.toString(this.letterboxRatio));
        properties.setProperty("grid", Boolean.toString(this.grid));
        properties.setProperty("orbitRadius", Double.toString(this.orbitRadius));
        properties.setProperty("orbitHeight", Double.toString(this.orbitHeight));
        properties.setProperty("orbitSpeed", Double.toString(this.orbitSpeed));
        properties.setProperty("aimHeight", Double.toString(this.aimHeight));
        properties.setProperty("followDistance", Double.toString(this.followDistance));
        properties.setProperty("followPitch", Float.toString(this.followPitch));
        properties.setProperty("followShoulder", Double.toString(this.followShoulder));
        properties.setProperty("followAlign", Float.toString(this.followAlign));
        properties.setProperty("followCollision", Boolean.toString(this.followCollision));
        Path path = file();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "CineCam client settings");
            }
        } catch (IOException exception) {
            LOGGER.warn("[CineCam] Could not save {}", path, exception);
        }
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    private static double readDouble(Properties properties, String key, double fallback) {
        String raw = properties.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static boolean readBoolean(Properties properties, String key, boolean fallback) {
        String raw = properties.getProperty(key);
        return raw == null ? fallback : Boolean.parseBoolean(raw.trim());
    }
}
