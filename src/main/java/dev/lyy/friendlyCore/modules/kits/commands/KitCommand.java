package dev.lyy.friendlycore.modules.kits.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.kits.ClaimResult;
import dev.lyy.friendlycore.modules.kits.Kit;
import dev.lyy.friendlycore.modules.kits.gui.KitGuiManager;
import dev.lyy.friendlycore.modules.kits.migration.PlayerKitsMigrator;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Universal Kit command handler supporting GUI opening, claiming, previewing,
 * creation, deletion, giving, resetting, and migration with full PlayerKits2 parity.
 */
public class KitCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final KitGuiManager guiManager;
    private final PlayerKitsMigrator migrator;

    public KitCommand(FriendlyCore plugin, KitGuiManager guiManager) {
        this.plugin = plugin;
        this.guiManager = guiManager;
        this.migrator = new PlayerKitsMigrator(plugin);
    }

    private boolean isAdmin(CommandSender sender) {
        return sender.hasPermission("friendlycore.admin")
                || sender.hasPermission("friendlycore.kits.admin")
                || sender.hasPermission("playerkits.admin");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                guiManager.openMainInventory(player);
            } else {
                sendKitList(sender);
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "edit", "editor" -> {
                if (!isAdmin(sender)) {
                    plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("no-permission", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪѕѕɪᴏɴ ᴛᴏ ᴜѕᴇ ᴛʜɪѕ ᴄᴏᴍᴍᴀɴᴅ."), null);
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    Messages.send(plugin, sender, "kits", "this-command-can-only-be-used");
                    return true;
                }
                if (args.length >= 2) {
                    String kitName = args[1];
                    if (kitName.equalsIgnoreCase("cancel")) {
                        plugin.getKitManager().getEditorManager().cancelPrompt(player);
                        return true;
                    }
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
            case "claim" -> {
                if (!(sender instanceof Player player)) {
                    Messages.send(plugin, sender, "kits", "this-command-can-only-be-used");
                    return true;
                }
                if (args.length < 2) {
                    Messages.send(plugin, player, "kits", "usage-kit-claim");
                    return true;
                }
                handleClaim(player, args[1]);
            }
            case "preview" -> {
                if (args.length < 2) {
                    Messages.send(plugin, sender, "kits", "usage-kit-preview-player");
                    return true;
                }
                String kitName = args[1];
                Player target;
                if (args.length >= 3) {
                    if (!isAdmin(sender)) {
                        plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("no-permission", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪѕѕɪᴏɴ ᴛᴏ ᴜѕᴇ ᴛʜɪѕ ᴄᴏᴍᴍᴀɴᴅ."), null);
                        return true;
                    }
                    target = Bukkit.getPlayer(args[2]);
                    if (target == null) {
                        plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("player-not-found", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴘʟᴀʏᴇʀ <color:#E7B85C>%player% <color:#D8D0C3>ᴡᴀѕ ɴᴏᴛ ғᴏᴜɴᴅ ᴏɴʟɪɴᴇ."), Map.of("%player%", args[2]));
                        return true;
                    }
                } else {
                    if (!(sender instanceof Player p)) {
                        Messages.send(plugin, sender, "kits", "console-must-specify-a-player-kit");
                        return true;
                    }
                    target = p;
                }
                guiManager.openPreview(target, kitName, "main_inventory");
            }
            case "open" -> {
                if (!isAdmin(sender)) {
                    plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("no-permission", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪѕѕɪᴏɴ ᴛᴏ ᴜѕᴇ ᴛʜɪѕ ᴄᴏᴍᴍᴀɴᴅ."), null);
                    return true;
                }
                if (args.length < 2) {
                    Messages.send(plugin, sender, "kits", "usage-kit-open-player");
                    return true;
                }
                String invName = args[1];
                Player target;
                if (args.length >= 3) {
                    target = Bukkit.getPlayer(args[2]);
                    if (target == null) {
                        plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("player-not-found", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴘʟᴀʏᴇʀ <color:#E7B85C>%player% <color:#D8D0C3>ᴡᴀѕ ɴᴏᴛ ғᴏᴜɴᴅ ᴏɴʟɪɴᴇ."), Map.of("%player%", args[2]));
                        return true;
                    }
                } else {
                    if (!(sender instanceof Player p)) {
                        Messages.send(plugin, sender, "kits", "console-must-specify-a-player-kit-2");
                        return true;
                    }
                    target = p;
                }
                guiManager.openInventory(target, invName, null);
            }
            case "create" -> {
                if (!isAdmin(sender)) {
                    plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("no-permission", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪѕѕɪᴏɴ ᴛᴏ ᴜѕᴇ ᴛʜɪѕ ᴄᴏᴍᴍᴀɴᴅ."), null);
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    Messages.send(plugin, sender, "kits", "this-command-can-only-be-used");
                    return true;
                }
                if (args.length < 2) {
                    Messages.send(plugin, player, "kits", "usage-kit-create-original-configurable");
                    return true;
                }
                String name = args[1];
                boolean original = args.length >= 3 && args[2].equalsIgnoreCase("original");
                boolean created = plugin.getKitManager().createKit(name, player, original);
                if (created) {
                    plugin.getTextService().sendMessage(player,
                            plugin.getConfigService().getMessages().getString("kits.kit-created", "<gradient:#7BD88F:#B9FBC0><bold>✔ ѕᴜᴄᴄᴇѕѕ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴋɪᴛ <color:#E7B85C>%kit%</color> ᴄʀᴇᴀᴛᴇᴅ ѕᴜᴄᴄᴇѕѕғᴜʟʟʏ."),
                            Map.of("%kit%", name));
                } else {
                    Messages.send(plugin, player, "kits", "your-inventory-is-empty-or-kit");
                }
            }
            case "delete", "del" -> {
                if (!isAdmin(sender)) {
                    plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("no-permission", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪѕѕɪᴏɴ ᴛᴏ ᴜѕᴇ ᴛʜɪѕ ᴄᴏᴍᴍᴀɴᴅ."), null);
                    return true;
                }
                if (args.length < 2) {
                    Messages.send(plugin, sender, "kits", "usage-kit-delete");
                    return true;
                }
                String name = args[1];
                boolean deleted = plugin.getKitManager().deleteKit(name);
                if (deleted) {
                    plugin.getTextService().sendMessage(sender,
                            plugin.getConfigService().getMessages().getString("kits.kit-deleted", "<gradient:#7BD88F:#B9FBC0><bold>✔ ѕᴜᴄᴄᴇѕѕ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴋɪᴛ <color:#E7B85C>%kit%</color> ᴅᴇʟᴇᴛᴇᴅ ѕᴜᴄᴄᴇѕѕғᴜʟʟʏ."),
                            Map.of("%kit%", name));
                } else {
                    plugin.getTextService().sendMessage(sender,
                            plugin.getConfigService().getMessages().getString("kits.kit-not-found", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴛʜᴀᴛ ᴋɪᴛ ᴅᴏᴇѕ ɴᴏᴛ ᴇxɪѕᴛ."),
                            Map.of("%kit%", name));
                }
            }
            case "give" -> {
                if (!isAdmin(sender)) {
                    plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("no-permission", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪѕѕɪᴏɴ ᴛᴏ ᴜѕᴇ ᴛʜɪѕ ᴄᴏᴍᴍᴀɴᴅ."), null);
                    return true;
                }
                if (args.length < 3) {
                    Messages.send(plugin, sender, "kits", "usage-kit-give");
                    return true;
                }
                String kitName = args[1];
                String targetName = args[2];
                Kit kit = plugin.getKitManager().getKit(kitName);
                if (kit == null) {
                    plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("kits.kit-not-found", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴛʜᴀᴛ ᴋɪᴛ ᴅᴏᴇѕ ɴᴏᴛ ᴇxɪѕᴛ."), Map.of("%kit%", kitName));
                    return true;
                }

                Player localTarget = Bukkit.getPlayer(targetName);
                if (localTarget != null && localTarget.isOnline()) {
                    plugin.getKitManager().claimKitAsync(localTarget, kit, true, false);
                    plugin.getTextService().sendMessage(sender,
                            plugin.getConfigService().getMessages().getString("kits.kit-given", "<gradient:#7BD88F:#B9FBC0><bold>✔ ѕᴜᴄᴄᴇѕѕ</bold></gradient> <dark_gray>» <color:#D8D0C3>ɢɪᴠɪɴɢ ᴋɪᴛ <color:#E7B85C>%kit%</color> ᴛᴏ <color:#E7B85C>%player%</color>."),
                            Map.of("%kit%", kit.getName(), "%player%", localTarget.getName()));
                    return true;
                }

                // Check cross-server network if player not local
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

                plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("player-not-found", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴘʟᴀʏᴇʀ <color:#E7B85C>%player% <color:#D8D0C3>ᴡᴀѕ ɴᴏᴛ ғᴏᴜɴᴅ ᴏɴʟɪɴᴇ."), Map.of("%player%", targetName));
            }
            case "reset" -> {
                if (!isAdmin(sender)) {
                    plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("no-permission", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪѕѕɪᴏɴ ᴛᴏ ᴜѕᴇ ᴛʜɪѕ ᴄᴏᴍᴍᴀɴᴅ."), null);
                    return true;
                }
                if (args.length < 3) {
                    Messages.send(plugin, sender, "kits", "usage-kit-reset");
                    return true;
                }
                String kitName = args[1];
                String targetName = args[2];

                if (targetName.equals("*")) {
                    plugin.getKitManager().resetKitForAll(kitName);
                    Messages.send(plugin, sender, "kits", "reset-kit-cooldown-for-all-players", "%kit%", kitName);
                    return true;
                }

                UUID targetUuid = null;
                Player targetPlayer = Bukkit.getPlayer(targetName);
                if (targetPlayer != null) {
                    targetUuid = targetPlayer.getUniqueId();
                } else if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
                    targetUuid = plugin.getRedisService().getNetworkPlayerUuid(targetName);
                }

                if (targetUuid == null) {
                    plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("player-not-found", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴘʟᴀʏᴇʀ <color:#E7B85C>%player% <color:#D8D0C3>ᴡᴀѕ ɴᴏᴛ ғᴏᴜɴᴅ ᴏɴʟɪɴᴇ."), Map.of("%player%", targetName));
                    return true;
                }

                plugin.getKitManager().resetKit(kitName, targetUuid);
                Messages.send(plugin, sender, "kits", "reset-kit-cooldown-for", "%kit%", kitName, "%player%", targetName);
            }
            case "list" -> sendKitList(sender);
            case "reload" -> {
                if (!isAdmin(sender)) {
                    plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("no-permission", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪѕѕɪᴏɴ ᴛᴏ ᴜѕᴇ ᴛʜɪѕ ᴄᴏᴍᴍᴀɴᴅ."), null);
                    return true;
                }
                plugin.getKitManager().loadSettings();
                plugin.getKitManager().loadKits();
                if (guiManager != null) {
                    guiManager.loadConfig();
                }
                if (plugin.getKitManager().getCrossServerService() != null) {
                    plugin.getKitManager().getCrossServerService().broadcastReload();
                }
                Messages.send(plugin, sender, "kits", "kits-and-gui-configurations-reloaded-successfully");
            }
            case "migrate" -> {
                if (!isAdmin(sender)) {
                    plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("no-permission", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪѕѕɪᴏɴ ᴛᴏ ᴜѕᴇ ᴛʜɪѕ ᴄᴏᴍᴍᴀɴᴅ."), null);
                    return true;
                }
                File customPath = args.length >= 2 ? new File(args[1]) : null;
                migrator.migrate(sender, customPath);
            }
            default -> {
                // If the subcommand matches a kit name, open preview or claim
                Kit kit = plugin.getKitManager().getKit(sub);
                if (kit != null) {
                    if (!(sender instanceof Player p)) {
                        Messages.send(plugin, sender, "kits", "console-must-use-kit-give");
                        return true;
                    }
                    handleClaim(p, sub);
                    return true;
                }
                sendHelp(sender);
            }
        }
        return true;
    }

    private void handleClaim(Player player, String kitName) {
        Kit kit = plugin.getKitManager().getKit(kitName);
        if (kit == null) {
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessages().getString("kits.kit-not-found", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴛʜᴀᴛ ᴋɪᴛ ᴅᴏᴇѕ ɴᴏᴛ ᴇxɪѕᴛ."), Map.of("%kit%", kitName));
            return;
        }

        plugin.getKitManager().claimKitAsync(player, kit, false, true).thenAccept(result -> {
            plugin.getScheduler().runEntity(player, () -> {
                if (!player.isOnline()) return;
                if (result.proceedToBuy()) {
                    guiManager.openBuyConfirmation(player, kitName, "main_inventory");
                } else if (!result.isSuccess() && result.message() != null) {
                    plugin.getTextService().sendMessage(player, result.message(), Map.of("%kit%", kit.getName()));
                }
            });
        });
    }

    private void sendKitList(CommandSender sender) {
        if (plugin.getKitManager().getAllKits().isEmpty()) {
            Messages.send(plugin, sender, "kits", "no-kits-are-configured-yet");
            return;
        }
        Messages.send(plugin, sender, "kits", "kits-available-kits");
        for (Kit k : plugin.getKitManager().getAllKits()) {
            if (k.hasPermission(sender)) {
                Messages.send(plugin, sender, "kits", "click-to-claim-kit-claim-click", "%k_name%", k.getName());
            }
        }
    }

    private void sendHelp(CommandSender sender) {
        if (isAdmin(sender)) {
            Messages.send(plugin, sender, "kits", "kits-staff-admin-guide");
            Messages.send(plugin, sender, "kits", "open-kit-selection-menu");
            Messages.send(plugin, sender, "kits", "claim-a-kit-directly");
            Messages.send(plugin, sender, "kits", "preview-kit-contents");
            Messages.send(plugin, sender, "kits", "list-all-available-kits-2");
            Messages.send(plugin, sender, "kits", "open-visual-kit-editor-gui-2");
            Messages.send(plugin, sender, "kits", "create-kit-from-inventory-2");
            Messages.send(plugin, sender, "kits", "delete-an-existing-kit-2");
            Messages.send(plugin, sender, "kits", "give-kit-to-player-2");
            Messages.send(plugin, sender, "kits", "reset-kit-cooldowns");
            Messages.send(plugin, sender, "kits", "open-custom-kit-gui");
            Messages.send(plugin, sender, "kits", "reload-kit-configurations");
            Messages.send(plugin, sender, "kits", "import-playerkits2-data-2");
        } else {
            Messages.send(plugin, sender, "kits", "kits-command-guide");
            Messages.send(plugin, sender, "kits", "open-kit-selection-menu");
            Messages.send(plugin, sender, "kits", "claim-a-kit-directly");
            Messages.send(plugin, sender, "kits", "preview-kit-contents");
            Messages.send(plugin, sender, "kits", "list-all-available-kits-2");
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        boolean admin = isAdmin(sender);

        if (args.length == 1) {
            completions.add("claim");
            completions.add("preview");
            completions.add("list");
            if (admin) {
                completions.add("edit");
                completions.add("editor");
                completions.add("create");
                completions.add("delete");
                completions.add("give");
                completions.add("reset");
                completions.add("open");
                completions.add("reload");
                completions.add("migrate");
            }
            for (Kit k : plugin.getKitManager().getAllKits()) {
                if (k.hasPermission(sender)) {
                    completions.add(k.getName());
                }
            }
            return filter(completions, args[0]);
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("claim") || sub.equals("preview") || sub.equals("delete") || sub.equals("give") || sub.equals("reset") || sub.equals("edit") || sub.equals("editor")) {
                for (Kit k : plugin.getKitManager().getAllKits()) {
                    completions.add(k.getName());
                }
                if (sub.equals("edit") || sub.equals("editor")) {
                    completions.add("cancel");
                }
                return filter(completions, args[1]);
            }
            if (sub.equals("open")) {
                completions.add("main_inventory");
                completions.add("premium_kits_inventory");
                return filter(completions, args[1]);
            }
        } else if (args.length == 3 && admin) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("give") || sub.equals("open") || sub.equals("preview")) {
                if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
                    completions.addAll(plugin.getRedisService().getOnlinePlayerNames());
                } else {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        completions.add(p.getName());
                    }
                }
                return filter(completions, args[2]);
            }
            if (sub.equals("reset")) {
                completions.add("*");
                if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
                    completions.addAll(plugin.getRedisService().getOnlinePlayerNames());
                } else {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        completions.add(p.getName());
                    }
                }
                return filter(completions, args[2]);
            }
            if (sub.equals("create")) {
                completions.add("original");
                completions.add("configurable");
                return filter(completions, args[2]);
            }
        }
        return List.of();
    }

    private List<String> filter(List<String> list, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> res = new ArrayList<>();
        for (String s : list) {
            if (s.toLowerCase(Locale.ROOT).startsWith(lower)) {
                res.add(s);
            }
        }
        return res;
    }
}
