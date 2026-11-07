package dev.lyy.friendlycore.common;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class ConfigServiceReloadTest {

    private static final String TEAMS = "modules/teams/messages.yml";

    @Test
    void brokenYamlOnReloadKeepsLastGoodVersion(@TempDir Path folder) throws Exception {
        Path file = write(folder, TEAMS, "misc-prefix:\n  text: \"mine\"\n");
        ConfigService service = service(folder);
        assertEquals("mine", service.loadConfig(TEAMS).getString("misc-prefix.text"));

        Files.writeString(file, "misc-prefix: [unclosed\n", StandardCharsets.UTF_8);
        assertEquals("mine", service.loadConfig(TEAMS).getString("misc-prefix.text"));
        assertEquals(1, service.getLoadErrors().size());
    }

    @Test
    void brokenYamlAtStartupIsNeverOverwrittenBySave(@TempDir Path folder) throws Exception {
        String broken = "misc-prefix: [unclosed\n";
        Path file = write(folder, TEAMS, broken);
        ConfigService service = service(folder);

        service.loadConfig(TEAMS);
        service.save(TEAMS);
        assertEquals(broken, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void missingMessageKeysAreAddedWithoutOverwritingUserValues(@TempDir Path folder) throws Exception {
        // The user flattened a section the bundled file defines as {text, display-type, enabled}.
        Path file = write(folder, TEAMS, "info-max-ally: \"my own text\"\n");
        service(folder).loadConfig(TEAMS);

        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals("my own text", onDisk.getString("info-max-ally"));
        assertTrue(onDisk.isSet("misc-prefix.text"), "new keys should be written for editing");
    }

    @Test
    void removedEndPortalKeysAreDroppedOnceWithABackupAndAdminValuesKept(@TempDir Path folder) throws Exception {
        String rtp = "modules/rtp/config.yml";
        String original = """
                cross-server:
                  # my note
                  request-timeout-seconds: 30
                  end-portal-target: "the-end"
                  end-portal-world: "world_the_end"
                worlds:
                  world_the_end:
                    landing-pool: false
                    portal-arrivals: true
                  my_world:
                    portal-arrivals: false
                """;
        Path file = write(folder, rtp, original);

        service(folder).loadConfig(rtp);

        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(file.toFile());
        assertFalse(onDisk.contains("cross-server.end-portal-target"));
        assertFalse(onDisk.contains("cross-server.end-portal-world"));
        assertFalse(onDisk.contains("worlds.world_the_end.portal-arrivals"));
        assertFalse(onDisk.contains("worlds.my_world.portal-arrivals"));
        assertEquals(30, onDisk.getInt("cross-server.request-timeout-seconds"));
        assertEquals(java.util.List.of("my note"), onDisk.getComments("cross-server.request-timeout-seconds"));
        assertFalse(onDisk.getBoolean("worlds.world_the_end.landing-pool"));
        assertFalse(onDisk.contains("worlds.world"), "config.yml files never gain default entries");
        try (var files = Files.list(file.getParent())) {
            Path backup = files.filter(p -> p.getFileName().toString().startsWith("config.yml.bak-")).findFirst().orElseThrow();
            assertEquals(original, Files.readString(backup, StandardCharsets.UTF_8));
        }

        String updated = Files.readString(file, StandardCharsets.UTF_8);
        service(folder).loadConfig(rtp);
        assertEquals(updated, Files.readString(file, StandardCharsets.UTF_8));
        try (var files = Files.list(file.getParent())) {
            assertEquals(2, files.count(), "a second start writes no new backup");
        }
    }

    private static Path write(Path folder, String path, String content) throws Exception {
        Path file = folder.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static ConfigService service(Path folder) throws Exception {
        FriendlyCore plugin = allocate(FriendlyCore.class);
        set(plugin, "classLoader", ConfigServiceReloadTest.class.getClassLoader());
        set(plugin, "logger", java.util.logging.Logger.getAnonymousLogger());
        set(plugin, "dataFolder", folder.toFile());
        ConfigService service = allocate(ConfigService.class);
        set(service, "plugin", plugin);
        set(service, "configs", new ConcurrentHashMap<>());
        set(service, "files", new ConcurrentHashMap<>());
        set(service, "aliases", new HashMap<>());
        set(service, "moduleContainers", new ConcurrentHashMap<>());
        set(service, "invalidFiles", ConcurrentHashMap.newKeySet());
        set(service, "loadErrors", Collections.synchronizedSet(new LinkedHashSet<>()));
        return service;
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Class<?> type = object.getClass();
        while (type != null) {
            try { type.getDeclaredField(name); break; }
            catch (NoSuchFieldException ignored) { type = type.getSuperclass(); }
        }
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }
}
