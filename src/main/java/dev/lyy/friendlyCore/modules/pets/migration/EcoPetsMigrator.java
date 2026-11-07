package dev.lyy.friendlycore.modules.pets.migration;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.pets.PetManager;
import dev.lyy.friendlycore.modules.pets.model.PlayerPetData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Migration utility for importing pet configurations and player data from legacy EcoPets & eco data storage.
 */
public class EcoPetsMigrator {

    private final FriendlyCore plugin;
    private final PetManager petManager;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    public EcoPetsMigrator(FriendlyCore plugin, PetManager petManager) {
        this.plugin = plugin;
        this.petManager = petManager;
    }

    public boolean isRunning() {
        return RUNNING.get();
    }

    public CompletableFuture<MigrationSummary> migrateAll(CommandSender sender, File customFolder) {
        if (!RUNNING.compareAndSet(false, true)) {
            Messages.send(plugin, sender, "pets", "migrate-a-pet-migration-is-already-in");
            return CompletableFuture.completedFuture(new MigrationSummary(0, 0, false, "Already running"));
        }

        return plugin.getScheduler().submitMigration(() -> {
            long startTime = System.currentTimeMillis();
            Messages.send(plugin, sender, "pets", "migrate-starting-complete-ecopets-eco-data-migration");

            int configsMigrated = migrateConfigsInternal(customFolder, sender);

            int playersMigrated = migratePlayerDataInternal(customFolder, sender);

            plugin.getScheduler().runGlobal(petManager::reload);

            long elapsed = System.currentTimeMillis() - startTime;
            Messages.send(plugin, sender, "pets", "migrate-pets-migration-completed");
            Messages.send(plugin, sender, "pets", "migrate-pet-configs-imported", "%configs_migrated%", String.valueOf(configsMigrated));
            Messages.send(plugin, sender, "pets", "migrate-player-records-migrated", "%players_migrated%", String.valueOf(playersMigrated));
            Messages.send(plugin, sender, "pets", "migrate-time-taken-ms", "%elapsed%", String.valueOf(elapsed));

            return new MigrationSummary(configsMigrated, playersMigrated, true, null);
        }).whenComplete((result, error) -> RUNNING.set(false));
    }

    public MigrationReport migrateConfigs() {
        int count = migrateConfigsInternal(null, null);
        plugin.getScheduler().runGlobal(petManager::reload);
        return new MigrationReport(true, count, "Successfully migrated " + count + " pet configurations from EcoPets.");
    }

    public int migrateConfigsInternal(File customFolder, CommandSender sender) {
        File ecoPetsDir = customFolder;
        if (ecoPetsDir == null || !ecoPetsDir.exists()) {
            ecoPetsDir = findEcoPetsFolder();
        }

        if (ecoPetsDir == null || !ecoPetsDir.exists()) {
            if (sender != null) {
                Messages.send(plugin, sender, "pets", "migrate-ecopets-folder-not-found-skipping-pet");
            }
            return 0;
        }

        File ecoPetsPetsDir = new File(ecoPetsDir, "pets");
        if (!ecoPetsPetsDir.exists() || !ecoPetsPetsDir.isDirectory()) {
            if (ecoPetsDir.getName().equalsIgnoreCase("pets") && ecoPetsDir.isDirectory()) {
                ecoPetsPetsDir = ecoPetsDir;
            } else {
                return 0;
            }
        }

        File targetPetsDir = new File(plugin.getDataFolder(), "modules/pets/pets");
        if (!targetPetsDir.exists()) {
            targetPetsDir.mkdirs();
        }

        File[] petFiles = ecoPetsPetsDir.listFiles((dir, name) -> name.endsWith(".yml") && !name.startsWith("_"));
        if (petFiles == null || petFiles.length == 0) {
            return 0;
        }

        int migratedCount = 0;
        for (File srcFile : petFiles) {
            try {
                YamlConfiguration srcConfig = YamlConfiguration.loadConfiguration(srcFile);
                YamlConfiguration targetConfig = new YamlConfiguration();

                targetConfig.set("name", srcConfig.getString("name", srcFile.getName().replace(".yml", "")));
                targetConfig.set("description", srcConfig.getString("description", ""));
                targetConfig.set("icon", srcConfig.getString("icon", "player_head"));
                targetConfig.set("entity-texture", srcConfig.getString("entity-texture", ""));

                List<String> effectDescs = new ArrayList<>();
                if (srcConfig.isConfigurationSection("effects-description")) {
                    var sec = srcConfig.getConfigurationSection("effects-description");
                    for (String k : sec.getKeys(false)) {
                        effectDescs.addAll(sec.getStringList(k));
                    }
                } else if (srcConfig.isList("effects-description")) {
                    effectDescs.addAll(srcConfig.getStringList("effects-description"));
                }
                targetConfig.set("effects-description", effectDescs);

                if (srcConfig.isConfigurationSection("spawn-egg")) {
                    var eggSec = srcConfig.getConfigurationSection("spawn-egg");
                    targetConfig.set("spawn-egg.enabled", eggSec.getBoolean("enabled", true));
                    targetConfig.set("spawn-egg.item", eggSec.getString("item", "blaze_spawn_egg"));
                    targetConfig.set("spawn-egg.name", eggSec.getString("name"));
                    targetConfig.set("spawn-egg.lore", eggSec.getStringList("lore"));
                    targetConfig.set("spawn-egg.craftable", eggSec.getBoolean("craftable", false));
                    targetConfig.set("spawn-egg.recipe-permission", eggSec.getString("recipe-permission"));
                    targetConfig.set("spawn-egg.shapeless", eggSec.getBoolean("shapeless", false));
                    targetConfig.set("spawn-egg.recipe", eggSec.getStringList("recipe"));
                    targetConfig.set("spawn-egg.withdrawable", eggSec.getBoolean("withdrawable", true));

                    if (eggSec.isConfigurationSection("withdraw-price")) {
                        var pSec = eggSec.getConfigurationSection("withdraw-price");
                        targetConfig.set("spawn-egg.withdraw-price.enabled", pSec.getBoolean("enabled", false));
                        targetConfig.set("spawn-egg.withdraw-price.type", pSec.getString("type", "coins"));
                        targetConfig.set("spawn-egg.withdraw-price.value", pSec.getDouble("value", 0.0));
                        targetConfig.set("spawn-egg.withdraw-price.display", pSec.getString("display", ""));
                    }
                }

                List<Map<?, ?>> rawEffects = srcConfig.getMapList("effects");
                targetConfig.set("effects", rawEffects);

                if (srcConfig.contains("conditions")) {
                    targetConfig.set("conditions", srcConfig.get("conditions"));
                }
                if (srcConfig.contains("activate-conditions")) {
                    targetConfig.set("activate-conditions", srcConfig.get("activate-conditions"));
                }

                File destFile = new File(targetPetsDir, srcFile.getName());
                targetConfig.save(destFile);
                migratedCount++;
                if (sender != null) {
                    Messages.send(plugin, sender, "pets", "migrate-migrated-pet-config", "%src_file_name%", srcFile.getName());
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to migrate EcoPets file: " + srcFile.getName(), e);
            }
        }

        return migratedCount;
    }

    public int migratePlayerDataInternal(File customFolder, CommandSender sender) {
        File ecoDir = customFolder;
        if (ecoDir == null || !ecoDir.exists()) {
            ecoDir = findEcoFolder();
        }

        if (ecoDir == null || !ecoDir.exists()) {
            if (sender != null) {
                Messages.send(plugin, sender, "pets", "migrate-eco-folder-not-found-skipping-player");
            }
            return 0;
        }

        // Check if eco config specifies MySQL / MariaDB storage
        File ecoConfigFile = ecoDir.isDirectory() ? new File(ecoDir, "config.yml") : new File(ecoDir.getParentFile(), "config.yml");
        if (ecoConfigFile.exists()) {
            try {
                YamlConfiguration ecoConf = YamlConfiguration.loadConfiguration(ecoConfigFile);
                String handler = ecoConf.getString("data-handler", "yaml");
                if (handler.equalsIgnoreCase("mysql") || handler.equalsIgnoreCase("mariadb")) {
                    int mySqlCount = migrateFromEcoMySQL(ecoConf, sender);
                    if (mySqlCount > 0) return mySqlCount;
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed reading eco config.yml", e);
            }
        }

        // Fallback to legacy flat-file data.yml
        File dataFile = ecoDir.isFile() && ecoDir.getName().endsWith(".yml") ? ecoDir : new File(ecoDir, "data.yml");
        if (dataFile.exists() && dataFile.isFile()) {
            return migrateFromEcoDataYml(dataFile, sender);
        }

        return 0;
    }

    public int migrateFromEcoDataYml(File dataFile, CommandSender sender) {
        int count = 0;
        int batchSize = 500;
        List<PlayerPetData> batch = new ArrayList<>(batchSize);

        try {
            if (sender != null) {
                Messages.send(plugin, sender, "pets", "migrate-reading-eco-player-database-from", "%data_file_name%", dataFile.getName());
            }

            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
            ConfigurationSection playerSec = yaml.getConfigurationSection("player");
            if (playerSec == null) {
                return 0;
            }

            for (String uuidStr : playerSec.getKeys(false)) {
                try {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidStr.trim());
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }

                    ConfigurationSection userSec = playerSec.getConfigurationSection(uuidStr);
                    if (userSec == null) continue;

                    String username = userSec.getString("eco:player_name", userSec.getString("eco:player_display_name", "Player"));
                    String activePet = userSec.getString("ecopets:active_pet", null);
                    boolean hidePet = userSec.getBoolean("ecopets:hide_pet", false);

                    Set<String> unlocked = new HashSet<>();
                    for (String key : userSec.getKeys(false)) {
                        if (key.startsWith("ecopets:") && (key.endsWith("_level") || key.endsWith("_xp"))) {
                            String petId = key.substring(8);
                            if (petId.endsWith("_level")) petId = petId.substring(0, petId.length() - 6);
                            if (petId.endsWith("_xp")) petId = petId.substring(0, petId.length() - 3);
                            if (!petId.isBlank()) {
                                unlocked.add(petId.toLowerCase(Locale.ROOT));
                            }
                        }
                    }

                    if (activePet != null && !activePet.isBlank()) {
                        unlocked.add(activePet.toLowerCase(Locale.ROOT));
                    }

                    if (!unlocked.isEmpty() || (activePet != null && !activePet.isBlank()) || hidePet) {
                        PlayerPetData petData = new PlayerPetData(uuid, username);
                        for (String pid : unlocked) {
                            petData.unlockPet(pid);
                        }
                        if (activePet != null && !activePet.isBlank()) {
                            petData.setActivePet(activePet);
                        }
                        petData.setHidePet(hidePet);
                        petData.setDirty(true);

                        batch.add(petData);
                        count++;

                        if (batch.size() >= batchSize) {
                            plugin.getDatabaseManager().savePlayerPetDataBatch(batch).join();
                            batch.clear();
                            if (sender != null && count % 500 == 0) {
                                Messages.send(plugin, sender, "pets", "migrate-migrated-player-pet-records", "%count%", String.valueOf(count));
                            }
                        }
                    }
                } catch (Exception rowEx) {
                    plugin.getLogger().log(Level.WARNING, "Skipping corrupt player pet entry: " + uuidStr, rowEx);
                }
            }

            if (!batch.isEmpty()) {
                plugin.getDatabaseManager().savePlayerPetDataBatch(batch).join();
                batch.clear();
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed migrating from eco data.yml: " + dataFile.getAbsolutePath(), e);
            if (sender != null) {
                Messages.send(plugin, sender, "pets", "migrate-failed-parsing-eco-data-yml", "%error%", String.valueOf(e.getMessage()));
            }
        }

        return count;
    }

    public int migrateFromEcoMySQL(YamlConfiguration config, CommandSender sender) {
        int count = 0;
        int batchSize = 500;
        List<PlayerPetData> batch = new ArrayList<>(batchSize);

        String host = config.getString("mysql.host", "localhost");
        int port = config.getInt("mysql.port", 3306);
        String user = config.getString("mysql.user", "username");
        String pass = config.getString("mysql.password", "");
        String database = config.getString("mysql.database", "database");
        String prefix = config.getString("mysql.prefix", "eco_");

        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database + "?allowPublicKeyRetrieval=true&useSSL=false";

        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, pass)) {
            if (sender != null) {
                Messages.send(plugin, sender, "pets", "migrate-connected-to-eco-mysql-database-migrating");
            }

            String sql = "SELECT uuid, `key`, `value` FROM " + prefix + "data WHERE `key` LIKE 'ecopets:%' OR `key` LIKE 'eco:player_%'";
            Map<UUID, Map<String, String>> playerKeyValues = new HashMap<>();

            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    try {
                        String uuidStr = rs.getString("uuid");
                        if (uuidStr == null || uuidStr.isBlank()) continue;
                        UUID uuid = UUID.fromString(uuidStr.trim());
                        String key = rs.getString("key");
                        String val = rs.getString("value");
                        playerKeyValues.computeIfAbsent(uuid, u -> new HashMap<>()).put(key, val);
                    } catch (Exception ignored) {}
                }
            }

            for (Map.Entry<UUID, Map<String, String>> entry : playerKeyValues.entrySet()) {
                UUID uuid = entry.getKey();
                Map<String, String> kv = entry.getValue();

                String username = kv.getOrDefault("eco:player_name", kv.getOrDefault("eco:player_display_name", "Player"));
                String activePet = kv.get("ecopets:active_pet");
                boolean hidePet = Boolean.parseBoolean(kv.getOrDefault("ecopets:hide_pet", "false"));

                Set<String> unlocked = new HashSet<>();
                for (String key : kv.keySet()) {
                    if (key.startsWith("ecopets:") && (key.endsWith("_level") || key.endsWith("_xp"))) {
                        String petId = key.substring(8);
                        if (petId.endsWith("_level")) petId = petId.substring(0, petId.length() - 6);
                        if (petId.endsWith("_xp")) petId = petId.substring(0, petId.length() - 3);
                        if (!petId.isBlank()) {
                            unlocked.add(petId.toLowerCase(Locale.ROOT));
                        }
                    }
                }
                if (activePet != null && !activePet.isBlank()) {
                    unlocked.add(activePet.toLowerCase(Locale.ROOT));
                }

                if (!unlocked.isEmpty() || (activePet != null && !activePet.isBlank()) || hidePet) {
                    PlayerPetData petData = new PlayerPetData(uuid, username);
                    for (String pid : unlocked) {
                        petData.unlockPet(pid);
                    }
                    if (activePet != null && !activePet.isBlank()) {
                        petData.setActivePet(activePet);
                    }
                    petData.setHidePet(hidePet);
                    petData.setDirty(true);

                    batch.add(petData);
                    count++;

                    if (batch.size() >= batchSize) {
                        plugin.getDatabaseManager().savePlayerPetDataBatch(batch).join();
                        batch.clear();
                    }
                }
            }

            if (!batch.isEmpty()) {
                plugin.getDatabaseManager().savePlayerPetDataBatch(batch).join();
                batch.clear();
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed migrating from eco MySQL", e);
        }

        return count;
    }

    private File findEcoPetsFolder() {
        File[] candidates = {
                new File(plugin.getDataFolder().getParentFile(), "EcoPets"),
                new File(plugin.getDataFolder().getParentFile(), "ecopets"),
                new File(plugin.getDataFolder().getParentFile(), "plugin-config/EcoPets"),
                new File(plugin.getDataFolder().getParentFile(), "plugin-config/ecopets"),
                new File("plugins/EcoPets"),
                new File("plugins/ecopets"),
                new File("plugin-config/EcoPets"),
                new File("plugin-config/ecopets"),
                new File(new File("."), "plugin-config/EcoPets"),
                new File(new File("."), "EcoPets")
        };

        for (File f : candidates) {
            if (f.exists()) return f;
        }
        return null;
    }

    private File findEcoFolder() {
        File[] candidates = {
                new File(plugin.getDataFolder().getParentFile(), "eco"),
                new File(plugin.getDataFolder().getParentFile(), "Eco"),
                new File(plugin.getDataFolder().getParentFile(), "plugin-config/eco"),
                new File(plugin.getDataFolder().getParentFile(), "plugin-config/Eco"),
                new File("plugins/eco"),
                new File("plugins/Eco"),
                new File("plugin-config/eco"),
                new File("plugin-config/Eco"),
                new File(new File("."), "plugin-config/eco"),
                new File(new File("."), "eco")
        };

        for (File f : candidates) {
            if (f.exists()) return f;
        }
        return null;
    }

    public record MigrationSummary(int configsMigrated, int playersMigrated, boolean success, String errorMessage) {}

    public static class MigrationReport {
        private final boolean success;
        private final int count;
        private final String message;

        public MigrationReport(boolean success, int count, String message) {
            this.success = success;
            this.count = count;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public int getCount() {
            return count;
        }

        public String getMessage() {
            return message;
        }
    }
}
