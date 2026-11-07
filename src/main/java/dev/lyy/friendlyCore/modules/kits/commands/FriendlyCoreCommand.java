package dev.lyy.friendlycore.modules.kits.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.kits.ClaimResult;
import dev.lyy.friendlycore.modules.kits.Kit;
import dev.lyy.friendlycore.modules.kits.migration.PlayerKitsMigrator;
import dev.lyy.friendlycore.modules.spawn.SpawnData;
import dev.lyy.friendlycore.modules.spawn.SpawnManager;
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Main management command /friendlycore (/fc) with exact FriendlyCore-1.3.0 and PlayerKits2 parity.
 */
public class FriendlyCoreCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final PlayerKitsMigrator kitMigrator;

    public FriendlyCoreCommand(FriendlyCore plugin) {
        this.plugin = plugin;
        this.kitMigrator = new PlayerKitsMigrator(plugin);
    }

    private boolean isKitAdmin(CommandSender sender) {
        return sender.hasPermission("friendlycore.admin")
                || sender.hasPermission("friendlycore.kits.admin")
                || sender.hasPermission("playerkits.admin");
    }

    private boolean isCoreAdmin(CommandSender sender) {
        return sender.hasPermission("friendlycore.admin");
    }

    private boolean denyUnless(CommandSender sender, boolean allowed) {
        if (allowed) return false;
        plugin.getTextService().sendMessage(sender,
                plugin.getConfigService().getMessages().getString("no-permission", "<red>No permission."), null);
        return true;
    }

    private boolean requireKitsModule(CommandSender sender) {
        if (plugin.getKitManager() != null) return true;
        Messages.send(plugin, sender, "kits", "the-kits-module-is-disabled");
        return false;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "spawnstatus" -> {
                if (denyUnless(sender, isCoreAdmin(sender))) return true;
                SpawnManager spawn = plugin.getSpawnManager();
                if (spawn == null) {
                    sender.sendMessage("[Spawn] Spawn module is not active on this backend.");
                    return true;
                }
                SpawnData target = spawn.getSpawnData();
                var config = plugin.getConfigService().getSpawnConfig();
                sender.sendMessage("[Spawn] Diagnostics active; local=" + spawn.resolveLocalServerId()
                        + " target=" + (target != null ? target.server() : "unset")
                        + " mode=" + config.getString("respawn.mode", "NO_BED")
                        + " debug=" + plugin.getConfigService().getMainConfig().getBoolean("debug", false)
                        + " cross-server=" + config.getBoolean("spawn.cross-server.enabled", true));
                long before = java.util.Arrays.stream(PlayerRespawnEvent.getHandlerList().getRegisteredListeners())
                        .filter(listener -> listener.getListener() == spawn).count();
                long after = java.util.Arrays.stream(PlayerPostRespawnEvent.getHandlerList().getRegisteredListeners())
                        .filter(listener -> listener.getListener() == spawn).count();
                sender.sendMessage("[Spawn] Respawn listeners: before=" + before + " after=" + after
                        + "; config folder=" + plugin.getDataFolder().getAbsolutePath());
                sender.sendMessage(spawn.getLastRespawnTrace());
            }
            case "reload" -> {
                if (denyUnless(sender, isCoreAdmin(sender))) return true;
                long start = System.currentTimeMillis();
                String module = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : null;
                java.util.List<String> problems = module == null ? plugin.reloadAll() : plugin.reloadModule(module);
                if (problems == null) {
                    plugin.getTextService().sendMessage(sender,
                            plugin.getConfigService().getMessages().getString("reload-unknown-module",
                                    "<red>Unknown module %module%. Available: %modules%"),
                            Map.of("%module%", args[1], "%modules%", String.join(", ", plugin.getReloadableModules())));
                    return true;
                }
                plugin.sendReloadResult(sender, module, problems, System.currentTimeMillis() - start);
            }
            case "editkit" -> {
                if (denyUnless(sender, isKitAdmin(sender))) return true;
                if (!requireKitsModule(sender)) return true;
                if (!(sender instanceof Player player)) {
                    Messages.send(plugin, sender, "kits", "this-command-can-only-be-used");
                    return true;
                }
                if (args.length >= 2) {
                    String kitName = args[1];
                    Kit kit = plugin.getKitManager().getKit(kitName);
                    if (kit == null) {
                        plugin.getTextService().sendMessage(player,
                                plugin.getConfigService().getMessages().getString("kits.kit-not-found", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴛʜᴀᴛ ᴋɪᴛ ᴅᴏᴇѕ ɴᴏᴛ ᴇxɪѕᴛ."),
                                Map.of("%kit%", kitName));
                        return true;
                    }
                    plugin.getKitManager().getEditorManager().openKitDetail(player, kit);
                } else {
                    plugin.getKitManager().getEditorManager().openKitList(player, 1);
                }
            }
            case "createkit" -> {
                if (denyUnless(sender, isKitAdmin(sender))) return true;
                if (!requireKitsModule(sender)) return true;
                if (!(sender instanceof Player player)) {
                    Messages.send(plugin, sender, "kits", "this-command-can-only-be-used");
                    return true;
                }
                if (args.length < 2) {
                    Messages.send(plugin, player, "kits", "usage-fc-createkit");
                    return true;
                }
                String kitName = args[1];
                if (plugin.getKitManager().getKit(kitName) != null) {
                    Messages.send(plugin, player, "kits", "a-kit-with-name-already-exists", "%kit%", kitName);
                    return true;
                }

                PlayerInventory inv = player.getInventory();
                int itemCount = 0;
                for (org.bukkit.inventory.ItemStack item : inv.getContents()) {
                    if (item != null && !item.getType().isAir()) itemCount++;
                }
                if (itemCount == 0) {
                    Messages.send(plugin, player, "kits", "your-inventory-is-empty-cannot-create");
                    return true;
                }
                boolean created = plugin.getKitManager().createKit(kitName, player, true);
                if (!created) {
                    Messages.send(plugin, player, "kits", "kit-could-not-be-created");
                    return true;
                }
                plugin.getTextService().sendMessage(player,
                        plugin.getConfigService().getMessages().getString("kits.kit-created", "<gradient:#7BD88F:#B9FBC0><bold>✔ ѕᴜᴄᴄᴇѕѕ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴋɪᴛ <color:#E7B85C>%kit%</color> ᴄʀᴇᴀᴛᴇᴅ ѕᴜᴄᴄᴇѕѕғᴜʟʟʏ."),
                        Map.of("%kit%", kitName, "%count%", String.valueOf(itemCount)));
            }
            case "delkit" -> {
                if (denyUnless(sender, isKitAdmin(sender))) return true;
                if (!requireKitsModule(sender)) return true;
                if (args.length < 2) {
                    Messages.send(plugin, sender, "kits", "usage-fc-delkit");
                    return true;
                }
                String kitName = args[1];
                boolean deleted = plugin.getKitManager().deleteKit(kitName);
                if (deleted) {
                    plugin.getTextService().sendMessage(sender,
                            plugin.getConfigService().getMessages().getString("kits.kit-deleted", "<gradient:#7BD88F:#B9FBC0><bold>✔ ѕᴜᴄᴄᴇѕѕ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴋɪᴛ <color:#E7B85C>%kit%</color> ᴅᴇʟᴇᴛᴇᴅ ѕᴜᴄᴄᴇѕѕғᴜʟʟʏ."),
                            Map.of("%kit%", kitName));
                } else {
                    plugin.getTextService().sendMessage(sender,
                            plugin.getConfigService().getMessages().getString("kits.kit-not-found", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴛʜᴀᴛ ᴋɪᴛ ᴅᴏᴇѕ ɴᴏᴛ ᴇxɪѕᴛ."),
                            Map.of("%kit%", kitName));
                }
            }
            case "givekit" -> {
                if (denyUnless(sender, isKitAdmin(sender))) return true;
                if (!requireKitsModule(sender)) return true;
                if (args.length < 2) {
                    if (sender instanceof Player) {
                        Messages.send(plugin, sender, "kits", "usage-fc-givekit-player");
                    } else {
                        Messages.send(plugin, sender, "kits", "usage-fc-givekit");
                    }
                    return true;
                }
                String kitName = args[1];
                Kit kit = plugin.getKitManager().getKit(kitName);
                if (kit == null) {
                    plugin.getTextService().sendMessage(sender,
                            plugin.getConfigService().getMessages().getString("kits.kit-not-found", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴛʜᴀᴛ ᴋɪᴛ ᴅᴏᴇѕ ɴᴏᴛ ᴇxɪѕᴛ."),
                            Map.of("%kit%", kitName));
                    return true;
                }
                Player target;
                if (args.length >= 3) {
                    String targetName = args[2];
                    target = Bukkit.getPlayer(targetName);
                    if (target == null) {
                        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
                            UUID networkUuid = plugin.getRedisService().getNetworkPlayerUuid(targetName);
                            if (networkUuid != null || plugin.getRedisService().getNetworkOnlinePlayers().contains(targetName)) {
                                boolean dispatched = plugin.getKitManager().getCrossServerService().dispatchRemoteGive(sender, kit.getName(), targetName, networkUuid, false);
                                if (dispatched) {
                                    Messages.send(plugin, sender, "kits", "dispatched-kit-to-across-network", "%kit%", kit.getName(), "%player%", targetName);
                                    return true;
                                }
                            }
                        }
                        plugin.getTextService().sendMessage(sender,
                                plugin.getConfigService().getMessages().getString("player-not-found", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴘʟᴀʏᴇʀ <color:#E7B85C>%player% <color:#D8D0C3>ᴡᴀѕ ɴᴏᴛ ғᴏᴜɴᴅ ᴏɴʟɪɴᴇ."),
                                Map.of("%player%", targetName));
                        return true;
                    }
                } else {
                    if (sender instanceof Player p) {
                        target = p;
                    } else {
                        Messages.send(plugin, sender, "kits", "console-must-specify-a-player-fc");
                        return true;
                    }
                }

                plugin.getKitManager().claimKitAsync(target, kit, true, false).thenAccept(result -> {
                    if (!result.isSuccess()) {
                        String message = result.message() != null ? result.message() : "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴋɪᴛ ᴄᴏᴜʟᴅ ɴᴏᴛ ʙᴇ ᴅᴇʟɪᴠᴇʀᴇᴅ.";
                        plugin.getTextService().sendMessage(sender, message, Map.of("%kit%", kit.getName()));
                        return;
                    }
                    if (!sender.equals(target)) {
                        plugin.getTextService().sendMessage(sender,
                                plugin.getConfigService().getMessages().getString("kits.kit-given", "<gradient:#7BD88F:#B9FBC0><bold>✔ ѕᴜᴄᴄᴇѕѕ</bold></gradient> <dark_gray>» <color:#D8D0C3>ɢɪᴠɪɴɢ ᴋɪᴛ <color:#E7B85C>%kit%</color> ᴛᴏ <color:#E7B85C>%player%</color>."),
                                Map.of("%kit%", kit.getName(), "%player%", target.getName()));
                    } else {
                        plugin.getTextService().sendMessage(sender,
                                plugin.getConfigService().getMessages().getString("kits.kit-given-self", "<gradient:#7BD88F:#B9FBC0><bold>✔ ѕᴜᴄᴄᴇѕѕ</bold></gradient> <dark_gray>» <color:#D8D0C3>ɢɪᴠɪɴɢ ᴋɪᴛ <color:#E7B85C>%kit%</color>."),
                                Map.of("%kit%", kit.getName()));
                    }
                });
                return true;
            }
            case "kitlist" -> {
                if (!requireKitsModule(sender)) return true;
                if (plugin.getKitManager().getAllKits().isEmpty()) {
                    Messages.send(plugin, sender, "kits", "no-kits-are-configured-yet");
                    return true;
                }
                Messages.send(plugin, sender, "kits", "kits-available-kits");
                for (Kit k : plugin.getKitManager().getAllKits()) {
                    Messages.send(plugin, sender, "kits", "items", "%k_name%", k.getName(), "%items%", String.valueOf(k.getItems().size()));
                }
            }
            case "migrate" -> {
                if (args.length < 2 || args[1].equalsIgnoreCase("help")) {
                    Messages.send(plugin, sender, "kits", "friendlycore-migration-guide");
                    if (isKitAdmin(sender)) {
                        Messages.send(plugin, sender, "kits", "fc-migrate-kits-folder-import-playerkits2");
                    }
                    if (isCoreAdmin(sender)) {
                        Messages.send(plugin, sender, "kits", "fc-migrate-economy-folder-import-essentialsx");
                        Messages.send(plugin, sender, "kits", "fc-migrate-homes-folder-import-modernhome");
                        Messages.send(plugin, sender, "kits", "fc-migrate-teams-folder-import-zelteams");
                        Messages.send(plugin, sender, "kits", "fc-migrate-pets-folder-import-ecopets");
                        Messages.send(plugin, sender, "kits", "fc-migrate-all-migrate-all-supported");
                    }
                    return true;
                }

                String module = args[1].toLowerCase(java.util.Locale.ROOT);
                File customPath = (args.length >= 3) ? new File(String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))) : null;

                switch (module) {
                    case "kits", "kit", "playerkits", "playerkits2" -> {
                        if (denyUnless(sender, isKitAdmin(sender))) return true;
                        if (!requireKitsModule(sender)) return true;
                        kitMigrator.migrate(sender, customPath);
                    }
                    case "economy", "eco", "essentials", "essentialsx", "money", "balances" -> {
                        if (denyUnless(sender, isCoreAdmin(sender))) return true;
                        if (plugin.getEconomyManager() == null) {
                            Messages.send(plugin, sender, "kits", "the-economy-module-is-disabled");
                            return true;
                        }
                        plugin.getEconomyManager().getEssentialsMigrator().migrate(sender, customPath);
                    }
                    case "homes", "home", "modernhome", "modernhomes" -> {
                        if (denyUnless(sender, isCoreAdmin(sender))) return true;
                        if (plugin.getHomesManager() == null) {
                            Messages.send(plugin, sender, "kits", "the-homes-module-is-disabled");
                            return true;
                        }
                        new dev.lyy.friendlycore.modules.homes.migration.ModernHomeMigrator(plugin, plugin.getHomesManager()).migrate(sender, customPath);
                    }
                    case "teams", "team", "zelteams", "clans" -> {
                        if (denyUnless(sender, isCoreAdmin(sender))) return true;
                        if (plugin.getTeamsModule() == null || plugin.getTeamsModule().getTeamsManager() == null) {
                            Messages.send(plugin, sender, "kits", "the-teams-module-is-disabled");
                            return true;
                        }
                        new dev.lyy.friendlycore.modules.teams.migration.ZelTeamsMigrator(plugin, plugin.getTeamsModule().getTeamsManager()).migrate(sender, customPath);
                    }
                    case "pets", "pet", "ecopets" -> {
                        if (denyUnless(sender, isCoreAdmin(sender))) return true;
                        if (plugin.getPetManager() == null) {
                            Messages.send(plugin, sender, "kits", "the-pets-module-is-disabled");
                            return true;
                        }
                        new dev.lyy.friendlycore.modules.pets.migration.EcoPetsMigrator(plugin, plugin.getPetManager()).migrateAll(sender, customPath);
                    }
                    case "all" -> {
                        if (denyUnless(sender, isCoreAdmin(sender))) return true;
                        Messages.send(plugin, sender, "kits", "migrating-all-plugin-data-into-friendlycore");
                        if (plugin.getKitManager() != null) {
                            kitMigrator.migrate(sender, null);
                        }
                        if (plugin.getEconomyManager() != null) {
                            plugin.getEconomyManager().getEssentialsMigrator().migrate(sender, null);
                        }
                        if (plugin.getHomesManager() != null) {
                            new dev.lyy.friendlycore.modules.homes.migration.ModernHomeMigrator(plugin, plugin.getHomesManager()).migrate(sender, null);
                        }
                        if (plugin.getTeamsModule() != null && plugin.getTeamsModule().getTeamsManager() != null) {
                            new dev.lyy.friendlycore.modules.teams.migration.ZelTeamsMigrator(plugin, plugin.getTeamsModule().getTeamsManager()).migrate(sender, null);
                        }
                        if (plugin.getPetManager() != null) {
                            new dev.lyy.friendlycore.modules.pets.migration.EcoPetsMigrator(plugin, plugin.getPetManager()).migrateAll(sender, null);
                        }
                    }
                    default -> Messages.send(plugin, sender, "kits", "unknown-migration-target-options-kits-economy", "%module%", String.valueOf(module));
                }
            }
            case "convert", "dbconvert" -> {
                if (denyUnless(sender, isCoreAdmin(sender))) return true;
                if (args.length < 2 || args[1].equalsIgnoreCase("help")) {
                    Messages.send(plugin, sender, "kits", "database-converter");
                    Messages.send(plugin, sender, "kits", "fc-convert-axauctions-path-convert-axauctions");
                    Messages.send(plugin, sender, "kits", "fc-convert-donutorder-path-convert-donutorder");
                    Messages.send(plugin, sender, "kits", "fc-convert-virtualspawner-path-convert-virtualspawner");
                    Messages.send(plugin, sender, "kits", "fc-convert-lands-path-convert-lands");
                    Messages.send(plugin, sender, "kits", "fc-convert-all-convert-all-external");
                    return true;
                }
                String targetPlugin = args[1].toLowerCase(java.util.Locale.ROOT);
                File customPath = null;
                String customServerId = null;
                if (args.length >= 3) {
                    File testFile = new File(args[2]);
                    if (testFile.exists()) {
                        customPath = testFile;
                        if (args.length >= 4) {
                            customServerId = args[3];
                        }
                    } else if (targetPlugin.equals("lands") || targetPlugin.equals("land") || targetPlugin.equals("claims") || targetPlugin.equals("claim")) {
                        customServerId = args[2];
                    } else {
                        customPath = new File(String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
                    }
                }

                switch (targetPlugin) {
                    case "axauctions", "auctions" -> plugin.getDatabaseConverterService().convertAxAuctions(sender, customPath);
                    case "donutorder", "orders" -> plugin.getDatabaseConverterService().convertDonutOrder(sender, customPath);
                    case "virtualspawner", "spawner", "spawners" -> plugin.getDatabaseConverterService().convertVirtualSpawner(sender, customPath);
                    case "lands", "land", "claims", "claim" -> plugin.getDatabaseConverterService().convertLands(sender, customPath, customServerId);
                    case "all" -> plugin.getDatabaseConverterService().convertAll(sender);
                    default -> Messages.send(plugin, sender, "kits", "unknown-plugin-options-axauctions-donutorder-virtualspawner", "%target_plugin%", String.valueOf(targetPlugin));
                }
            }
            case "verify", "audit", "status", "check" -> {
                if (denyUnless(sender, isCoreAdmin(sender))) return true;
                executeVerify(sender);
            }
            default -> sendHelp(sender);
        }

        return true;
    }

    private void executeVerify(CommandSender sender) {
        Messages.send(plugin, sender, "kits", "checking-database-tables-and-services");

        plugin.getScheduler().runAsync(() -> {
            try {
                boolean redisOnline = plugin.getRedisService() != null && plugin.getRedisService().isEnabled();
                int kitsCount = plugin.getKitManager() != null ? plugin.getKitManager().getAllKits().size() : 0;
                int onlinePlayers = Bukkit.getOnlinePlayers().size();

                Messages.send(plugin, sender, "kits", "system-data-integrity-report");
                Messages.send(plugin, sender, "kits", redisOnline ? "redis-service-online-active-kits-players" : "redis-service-disabled-local-active-kits", "%kits_count%", String.valueOf(kitsCount), "%online_players%", String.valueOf(onlinePlayers));

                var db = plugin.getDatabaseManager();
                if (db != null) {
                    boolean isMySql = db instanceof dev.lyy.friendlycore.common.database.MySQLDatabase;
                    Messages.send(plugin, sender, "kits", isMySql ? "database-mode-mysql-mariadb-enterprise" : "database-mode-sqlite-local");
                }

                if (db instanceof dev.lyy.friendlycore.common.database.MySQLDatabase) {
                    Messages.send(plugin, sender, "kits", "database-pool-healthy-all-connections-active");
                }

                Messages.send(plugin, sender, "kits", "data-preservation-status-100-intact");
            } catch (Exception e) {
                Messages.send(plugin, sender, "kits", "audit-encountered-error", "%error%", String.valueOf(e.getMessage()));
            }
        });
    }

    private void sendHelp(CommandSender sender) {
        Messages.send(plugin, sender, "kits", "friendly-core-command-guide");
        Messages.send(plugin, sender, "kits", "list-all-available-kits");
        if (isKitAdmin(sender)) {
            Messages.send(plugin, sender, "kits", "open-visual-kit-editor-gui");
            Messages.send(plugin, sender, "kits", "create-kit-from-inventory");
            Messages.send(plugin, sender, "kits", "delete-an-existing-kit");
            Messages.send(plugin, sender, "kits", "give-kit-to-player");
            Messages.send(plugin, sender, "kits", "import-playerkits2-data");
        }
        if (isCoreAdmin(sender)) {
            sender.sendMessage(" /fc spawnstatus - Show loaded spawn routing and the last respawn trace");
            Messages.send(plugin, sender, "kits", "import-legacy-data");
            Messages.send(plugin, sender, "kits", "convert-to-mysql");
            Messages.send(plugin, sender, "kits", "verify-database-and-services");
            Messages.send(plugin, sender, "kits", "reload-plugin-configurations");
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subcommands = new ArrayList<>();
            subcommands.add("kitlist");
            if (isKitAdmin(sender)) {
                subcommands.addAll(List.of("editkit", "createkit", "delkit", "givekit", "migrate"));
                if (isCoreAdmin(sender)) subcommands.addAll(List.of("reload", "convert", "dbconvert", "verify", "status", "spawnstatus"));
            } else if (isCoreAdmin(sender)) {
                subcommands.addAll(List.of("migrate", "reload", "convert", "dbconvert", "verify", "status", "spawnstatus"));
            }
            String input = args[0].toLowerCase();
            return subcommands.stream().filter(s -> s.startsWith(input)).toList();
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("delkit") || args[0].equalsIgnoreCase("givekit") || args[0].equalsIgnoreCase("editkit"))) {
            if (!isKitAdmin(sender)) return List.of();
            if (plugin.getKitManager() == null) return List.of();
            List<String> list = new ArrayList<>();
            for (Kit k : plugin.getKitManager().getAllKits()) {
                if (k.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    list.add(k.getName());
                }
            }
            return list;
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("convert") || args[0].equalsIgnoreCase("dbconvert"))) {
            if (!isCoreAdmin(sender)) return List.of();
            List<String> targets = List.of("axauctions", "donutorder", "virtualspawner", "lands", "all");
            return targets.stream().filter(t -> t.startsWith(args[1].toLowerCase())).toList();
        } else if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            if (!isCoreAdmin(sender)) return List.of();
            return plugin.getReloadableModules().stream().filter(t -> t.startsWith(args[1].toLowerCase())).toList();
        } else if (args.length == 2 && args[0].equalsIgnoreCase("migrate")) {
            List<String> targets = new ArrayList<>();
            if (isKitAdmin(sender)) targets.add("kits");
            if (isCoreAdmin(sender)) targets.addAll(List.of("economy", "homes", "teams", "pets", "all"));
            return targets.stream().filter(t -> t.startsWith(args[1].toLowerCase())).toList();
        } else if (args.length == 3 && args[0].equalsIgnoreCase("givekit")) {
            if (!isKitAdmin(sender)) return List.of();
            if (plugin.getKitManager() == null) return List.of();
            List<String> list = new ArrayList<>();
            if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
                list.addAll(plugin.getRedisService().getOnlinePlayerNames());
            } else {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    list.add(p.getName());
                }
            }
            return list.stream().filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase())).toList();
        }
        return List.of();
    }
}
