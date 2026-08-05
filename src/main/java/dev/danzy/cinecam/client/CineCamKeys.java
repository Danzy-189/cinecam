package dev.danzy.cinecam.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public final class CineCamKeys {
    public static final String CATEGORY = "key.categories.cinecam";

    public static final KeyMapping TOGGLE = create("toggle", GLFW.GLFW_KEY_F6);
    public static final KeyMapping CONTROL = create("control", GLFW.GLFW_KEY_V);
    public static final KeyMapping MODE = create("mode", GLFW.GLFW_KEY_C);
    public static final KeyMapping HIDE_UI = create("hide_ui", GLFW.GLFW_KEY_H);
    public static final KeyMapping MENU = create("menu", GLFW.GLFW_KEY_N);
    public static final KeyMapping RECENTER = create("recenter", GLFW.GLFW_KEY_R);
    public static final KeyMapping LETTERBOX = create("letterbox", GLFW.GLFW_KEY_B);
    public static final KeyMapping GRID = create("grid", GLFW.GLFW_KEY_G);
    public static final KeyMapping ROLL_LEFT = create("roll_left", GLFW.GLFW_KEY_Z);
    public static final KeyMapping ROLL_RIGHT = create("roll_right", GLFW.GLFW_KEY_X);
    /** Store the current pose as a keyframe. */
    public static final KeyMapping KEYFRAME = create("keyframe", GLFW.GLFW_KEY_K);
    /** Play or stop the current path. */
    public static final KeyMapping PLAY = create("play", GLFW.GLFW_KEY_P);
    /** Open the keyframe editor. */
    public static final KeyMapping PATHS = create("paths", GLFW.GLFW_KEY_O);
    /** Pick whatever the view points at as the framed subject. */
    public static final KeyMapping TARGET = create("target", GLFW.GLFW_KEY_Y);

    private CineCamKeys() {}

    private static KeyMapping create(String name, int key) {
        return new KeyMapping("key.cinecam." + name, InputConstants.Type.KEYSYM, key, CATEGORY);
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
        event.register(CONTROL);
        event.register(MODE);
        event.register(HIDE_UI);
        event.register(MENU);
        event.register(RECENTER);
        event.register(LETTERBOX);
        event.register(GRID);
        event.register(ROLL_LEFT);
        event.register(ROLL_RIGHT);
        event.register(KEYFRAME);
        event.register(PLAY);
        event.register(PATHS);
        event.register(TARGET);
    }
}
