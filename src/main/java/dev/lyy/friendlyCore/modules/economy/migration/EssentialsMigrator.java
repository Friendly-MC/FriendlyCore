package dev.lyy.friendlycore.modules.economy.migration;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.economy.AccountData;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asynchronous migrator for importing player economy data from Essentials/EssentialsX YAML files.
 */
public class EssentialsMigrator {

    private final FriendlyCore plugin;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private static final Pattern MONEY_PATTERN = Pattern.compile("^\\s*money:\\s*['\"]?([-+]?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)['\"]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_PATTERN = Pattern.compile("^\\s*lastAccountName:\\s*['\"]?([^'\"\\r\\n]+)['\"]?", Pattern.CASE_INSENSITIVE);

    public EssentialsMigrator(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Performs an asynchronous migration of balances from an Essentials userdata directory.
     *
     * @param sender the command sender requesting the migration
     * @param customFolder custom directory to scan, or null to auto-detect
     * @return future containing the migration summary
     */
    public CompletableFuture<MigrationResult> migrate(CommandSender sender, File customFolder) {
        if (!running.compareAndSet(false, true)) {
            Messages.send(plugin, sender, "economy", "migrate-a-migration-is-already-in-progress");
            return CompletableFuture.completedFuture(new MigrationResult(0, 0, 0, 0, false, "Already running"));
        }

        return plugin.getScheduler().submitMigration(() -> {
            long startTime = System.currentTimeMillis();
            Messages.send(plugin, sender, "economy", "migrate-migration-scanning-essentialsx-userdata");

            File userdataFolder = customFolder;
            if (userdataFolder == null || !userdataFolder.exists()) {
                userdataFolder = findEssentialsUserdataFolder();
            }

            if (userdataFolder == null || !userdataFolder.exists() || !userdataFolder.isDirectory()) {
                String error = "Essentials userdata folder not found. Expected at 'plugins/Essentials/userdata/'";
                Messages.send(plugin, sender, "economy", "migrate-label", "%error%", String.valueOf(error));
                return new MigrationResult(0, 0, 0, 0, false, error);
            }

            Messages.send(plugin, sender, "economy", "migrate-migration-processing-player-files-from", "%path%", String.valueOf(userdataFolder.getPath()));

            int totalFiles = 0;
            int migratedCount = 0;
            int skippedCount = 0;
            double totalMoney = 0;
            int batchSize = 500;

            List<AccountData> batch = new ArrayList<>(batchSize);

            try (java.nio.file.DirectoryStream<java.nio.file.Path> stream = java.nio.file.Files.newDirectoryStream(userdataFolder.toPath(), "*.yml")) {
                for (java.nio.file.Path path : stream) {
                    totalFiles++;
                    File file = path.toFile();
                    String fileName = file.getName();
                    String uuidStr = fileName.substring(0, fileName.length() - 4);

                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidStr);
                    } catch (IllegalArgumentException e) {
                        skippedCount++;
                        continue;
                    }

                    // Fast regex line scan to avoid overhead of full YAML parsing across large player datasets
                    ParsedUserData parsed = fastParseUserData(file);
                    if (parsed != null && !Double.isNaN(parsed.balance) && !Double.isInfinite(parsed.balance) && parsed.balance >= 0.0) {
                        AccountData account = new AccountData(uuid, parsed.username != null ? parsed.username : "Player", parsed.balance);
                        account.setDirty(true);
                        batch.add(account);
                        migratedCount++;
                        totalMoney += parsed.balance;

                        AccountData cached = plugin.getEconomyManager().getCachedAccount(uuid);
                        if (cached != null) {
                            cached.setBalance(parsed.balance);
                            if (parsed.username != null) cached.setUsername(parsed.username);
                            cached.setDirty(false);
                        }
                    } else {
                        skippedCount++;
                    }

                    if (batch.size() >= batchSize) {
                        plugin.getDatabaseManager().saveAccountsBatch(batch).join();
                        batch.clear();

                        if (totalFiles % 2500 == 0) {
                            Messages.send(plugin, sender, "economy", "migrate-migration-processed-files-migrated", "%total_files%", String.valueOf(totalFiles), "%migrated_count%", String.valueOf(migratedCount));
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed scanning directory", e);
            }

            // Flush any remaining accounts in the final batch
            if (!batch.isEmpty()) {
                plugin.getDatabaseManager().saveAccountsBatch(batch).join();
                batch.clear();
            }

            long elapsed = System.currentTimeMillis() - startTime;

            plugin.getEconomyManager().invalidateBaltopCache();
            if (plugin.getRedisService().isEnabled()) {
                plugin.getRedisService().broadcastBaltopInvalidate();
            }

            String formattedTotal = plugin.getNumberFormatter().format(totalMoney);
            Messages.send(plugin, sender, "economy", "migrate-migration-completed");
            Messages.send(plugin, sender, "economy", "migrate-total-accounts-processed", "%total_files%", String.valueOf(totalFiles));
            Messages.send(plugin, sender, "economy", "migrate-accounts-with-balance-migrated", "%migrated_count%", String.valueOf(migratedCount));
            Messages.send(plugin, sender, "economy", "migrate-total-economy-balance-imported", "%formatted_total%", String.valueOf(formattedTotal));
            Messages.send(plugin, sender, "economy", "migrate-time-taken-ms", "%elapsed%", String.valueOf(elapsed));

            return new MigrationResult(totalFiles, migratedCount, skippedCount, totalMoney, true, null);
        }).whenComplete((result, error) -> running.set(false));
    }

    private File findEssentialsUserdataFolder() {
        File[] candidates = {
                new File(plugin.getDataFolder().getParentFile(), "Essentials/userdata"),
                new File(plugin.getDataFolder().getParentFile(), "EssentialsX/userdata"),
                new File(plugin.getDataFolder().getParentFile(), "essentials/userdata"),
                new File(plugin.getDataFolder().getParentFile(), "Essentials/UserData")
        };

        for (File f : candidates) {
            if (f.exists() && f.isDirectory()) {
                return f;
            }
        }
        return null;
    }

    private ParsedUserData fastParseUserData(File file) {
        String username = null;
        Double balance = null;

        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (username == null) {
                    Matcher nameMatcher = NAME_PATTERN.matcher(line);
                    if (nameMatcher.find()) {
                        username = nameMatcher.group(1).trim();
                    }
                }

                if (balance == null) {
                    Matcher moneyMatcher = MONEY_PATTERN.matcher(line);
                    if (moneyMatcher.find()) {
                        try {
                            balance = Double.parseDouble(moneyMatcher.group(1));
                        } catch (NumberFormatException ignored) {}
                    }
                }

                if (username != null && balance != null) {
                    break;
                }
            }
        } catch (Exception e) {
            // Fallback to YamlConfiguration if fast read encounters encoding error
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                username = yaml.getString("lastAccountName", "Player");
                balance = yaml.getDouble("money", 0.0);
            } catch (Exception ignored) {}
        }

        double finalBalance = balance != null ? balance : 0.0;
        return new ParsedUserData(username != null ? username : "Player", finalBalance);
    }

    private static class ParsedUserData {
        final String username;
        final double balance;

        ParsedUserData(String username, double balance) {
            this.username = username;
            this.balance = balance;
        }
    }

    public record MigrationResult(
            int totalFiles,
            int migratedCount,
            int skippedCount,
            double totalMoney,
            boolean success,
            String errorMessage
    ) {}
}
