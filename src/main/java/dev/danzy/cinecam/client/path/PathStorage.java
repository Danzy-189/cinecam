package dev.danzy.cinecam.client.path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

/** Reads and writes camera paths as JSON under config/cinecam/paths. */
public final class PathStorage {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String EXTENSION = ".json";

    private PathStorage() {}

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve("cinecam").resolve("paths");
    }

    /** Keeps file names harmless without surprising the user too much. */
    public static String sanitize(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            trimmed = "path";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < trimmed.length() && builder.length() < 48; index++) {
            char symbol = trimmed.charAt(index);
            if (Character.isLetterOrDigit(symbol) || symbol == '-' || symbol == '_' || symbol == ' ') {
                builder.append(symbol);
            } else {
                builder.append('_');
            }
        }
        String result = builder.toString().trim();
        return result.isEmpty() ? "path" : result;
    }

    public static boolean save(CameraPath path) {
        String fileName = sanitize(path.name());
        Path target = directory().resolve(fileName + EXTENSION);
        try {
            Files.createDirectories(directory());
            try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                GSON.toJson(path.toJson(), writer);
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("[CineCam] Could not save path {}", target, exception);
            return false;
        }
    }

    public static CameraPath load(String name) {
        Path source = directory().resolve(sanitize(name) + EXTENSION);
        if (!Files.isRegularFile(source)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                return null;
            }
            CameraPath path = CameraPath.fromJson(element.getAsJsonObject());
            path.setName(sanitize(name));
            return path;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("[CineCam] Could not read path {}", source, exception);
            return null;
        }
    }

    public static boolean delete(String name) {
        Path target = directory().resolve(sanitize(name) + EXTENSION);
        try {
            return Files.deleteIfExists(target);
        } catch (IOException exception) {
            LOGGER.warn("[CineCam] Could not delete path {}", target, exception);
            return false;
        }
    }

    /** Names of every stored path, alphabetically. */
    public static List<String> list() {
        List<String> names = new ArrayList<>();
        Path directory = directory();
        if (!Files.isDirectory(directory)) {
            return names;
        }
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(file -> file.toLowerCase(Locale.ROOT).endsWith(EXTENSION))
                    .map(file -> file.substring(0, file.length() - EXTENSION.length()))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(names::add);
        } catch (IOException exception) {
            LOGGER.warn("[CineCam] Could not list paths in {}", directory, exception);
        }
        return names;
    }
}
