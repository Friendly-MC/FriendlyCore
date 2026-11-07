package dev.lyy.friendlycore.common;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles automatic migration of legacy configuration layouts into the modular
 * per-module directory structure on plugin startup.
 */
public final class ConfigMigrator {

    private final File dataFolder;
    private final Logger logger;

    public ConfigMigrator(Plugin plugin) {
        this(plugin.getDataFolder(), plugin.getLogger());
    }

    public ConfigMigrator(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger != null ? logger : Logger.getLogger("FriendlyCore");
    }

    /**
     * Scans for old configuration paths and moves them to their updated locations.
     */
    public void migrate() {
        if (dataFolder == null || !dataFolder.exists()) {
            return;
        }

        Map<String, String> migrations = new LinkedHashMap<>();

        migrations.put("messages/general.yml", "messages.yml");

        migrations.put("modules/economy.yml", "modules/economy/config.yml");
        migrations.put("modules/kits.yml", "modules/kits/config.yml");
        migrations.put("modules/nightvision.yml", "modules/nightvision/config.yml");
        migrations.put("modules/pvp.yml", "modules/pvp/config.yml");
        migrations.put("modules/rtp.yml", "modules/rtp/config.yml");
        migrations.put("modules/spawn.yml", "modules/spawn/config.yml");
        migrations.put("modules/teams.yml", "modules/teams/config.yml");
        migrations.put("modules/tools.yml", "modules/tools/config.yml");
        migrations.put("modules/tpa.yml", "modules/tpa/config.yml");

        // Legacy root aliases
        migrations.put("settings.yml", "modules/teams/config.yml");
        migrations.put("language.yml", "modules/teams/messages.yml");

        migrations.put("messages/economy.yml", "modules/economy/messages.yml");
        migrations.put("messages/kits.yml", "modules/kits/messages.yml");
        migrations.put("messages/nightvision.yml", "modules/nightvision/messages.yml");
        migrations.put("messages/pvp.yml", "modules/pvp/messages.yml");
        migrations.put("messages/rtp.yml", "modules/rtp/messages.yml");
        migrations.put("messages/spawn.yml", "modules/spawn/messages.yml");
        migrations.put("messages/teams.yml", "modules/teams/messages.yml");
        migrations.put("messages/tools.yml", "modules/tools/messages.yml");
        migrations.put("messages/tpa.yml", "modules/tpa/messages.yml");

        migrations.put("inventories/kits.yml", "modules/kits/inventories/kits.yml");

        migrations.put("inventories/tpa-admin.yml", "modules/tpa/inventories/tpa-admin.yml");
        migrations.put("inventories/tpa-here-inventory.yml", "modules/tpa/inventories/tpa-here-inventory.yml");
        migrations.put("inventories/tpa-inventory.yml", "modules/tpa/inventories/tpa-inventory.yml");
        migrations.put("inventories/tpa-safe-teleport-settings.yml", "modules/tpa/inventories/tpa-safe-teleport-settings.yml");
        migrations.put("inventories/tpa-settings.yml", "modules/tpa/inventories/tpa-settings.yml");

        String[] teamGuis = {
                "allies-gui.yml", "bank-gui.yml", "color-gui.yml", "confirm-gui.yml",
                "enderchest-gui.yml", "home-gui.yml", "leaderboard-gui.yml", "mailbox-gui.yml",
                "members-gui.yml", "playerstats-gui.yml", "settings-gui.yml", "stats-gui.yml", "team-gui.yml"
        };
        for (String gui : teamGuis) {
            migrations.put("inventories/teams/" + gui, "modules/teams/inventories/" + gui);
            migrations.put("menu/" + gui, "modules/teams/inventories/" + gui);
        }

        int migratedCount = 0;
        for (Map.Entry<String, String> entry : migrations.entrySet()) {
            File sourceFile = new File(dataFolder, entry.getKey());
            File targetFile = new File(dataFolder, entry.getValue());

            if (sourceFile.exists() && sourceFile.isFile()) {
                if (!targetFile.exists()) {
                    File parent = targetFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    try {
                        Files.move(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        migratedCount++;
                        logger.info("Migrated configuration: " + entry.getKey() + " -> " + entry.getValue());
                    } catch (IOException e) {
                        logger.log(Level.WARNING, "Failed to move " + entry.getKey() + " to " + entry.getValue(), e);
                    }
                } else {
                    // Target already exists, clean up the redundant legacy file
                    sourceFile.delete();
                }
            }
        }

        // Deprecated standalone files
        File legacySounds = new File(dataFolder, "sounds-new.yml");
        if (legacySounds.exists()) {
            legacySounds.delete();
        }
        File legacyRedis = new File(dataFolder, "modules/redis.yml");
        if (legacyRedis.exists()) {
            legacyRedis.delete();
        }

        // Prune empty legacy directories
        cleanEmptyDirectory(new File(dataFolder, "menu"));
        cleanEmptyDirectory(new File(dataFolder, "inventories/teams"));
        cleanEmptyDirectory(new File(dataFolder, "inventories"));
        cleanEmptyDirectory(new File(dataFolder, "messages"));

        if (migratedCount > 0) {
            logger.info("Configuration migration completed successfully (" + migratedCount + " files updated).");
        }
    }

    private void cleanEmptyDirectory(File dir) {
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                dir.delete();
            }
        }
    }
}
