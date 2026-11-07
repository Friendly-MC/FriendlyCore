package dev.lyy.friendlycore.modules.homes.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.common.PlayerLookup;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.homes.HomesManager;
import dev.lyy.friendlycore.modules.homes.gui.HomeGui;
import dev.lyy.friendlycore.modules.homes.model.HomeData;
import dev.lyy.friendlycore.modules.homes.model.PlayerHomes;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;

/**
 * Administrative command executor and tab completer for managing other players' homes (/ahome).
 */
public class AHomeCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final HomesManager homesManager;
    private final dev.lyy.friendlycore.modules.homes.migration.ModernHomeMigrator migrator;

    public AHomeCommand(FriendlyCore plugin, HomesManager homesManager) {
        this.plugin = plugin;
        this.homesManager = homesManager;
        this.migrator = new dev.lyy.friendlycore.modules.homes.migration.ModernHomeMigrator(plugin, homesManager);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!homesManager.hasHomeAdminPermission(sender)) {
            Messages.send(plugin, sender, "homes", "you-do-not-have-permission-to");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            List<String> helpList = plugin.getConfigService().get("modules/homes/messages.yml").getStringList("help-message-admin");
            for (String line : helpList) {
                sender.sendMessage(plugin.getTextService().parse(line));
            }
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("reload")) {
            homesManager.reload();
            Messages.send(plugin, sender, "homes", "friendlycore-homes-module-reloaded-successfully");
            return true;
        }

        if (sub.equals("see")) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "homes", "this-subcommand-can-only-be-executed");
                return true;
            }
            if (args.length < 2) {
                Messages.send(plugin, player, "homes", "use-ahome-see");
                return true;
            }
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                homesManager.sendHomeMessage(player, "invalid-player", Map.of());
                return true;
            }
            new HomeGui(plugin, homesManager, player, target.getUniqueId(), target.getName(), 1).open();
            return true;
        }

        if (sub.equals("osee")) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "homes", "this-subcommand-can-only-be-executed");
                return true;
            }
            if (args.length < 2) {
                Messages.send(plugin, player, "homes", "use-ahome-osee");
                return true;
            }
            PlayerLookup.byName(plugin, sender, args[1], target -> {
                if (target.getName() == null) {
                    homesManager.sendHomeMessage(player, "invalid-player", Map.of());
                    return;
                }
                homesManager.loadPlayerHomes(target.getUniqueId(), target.getName()).thenAccept(profile -> {
                    plugin.getScheduler().runEntity(player, () -> {
                        new HomeGui(plugin, homesManager, player, target.getUniqueId(), target.getName(), 1).open();
                    });
                });
            });
            return true;
        }

        if (sub.equals("tp")) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "homes", "this-subcommand-can-only-be-executed");
                return true;
            }
            if (args.length < 3) {
                Messages.send(plugin, player, "homes", "use-ahome-tp");
                return true;
            }
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                homesManager.sendHomeMessage(player, "invalid-player", Map.of());
                return true;
            }
            PlayerHomes profile = homesManager.getPlayerHomes(target.getUniqueId());
            if (profile == null) {
                homesManager.sendHomeMessage(player, "home-not-found", Map.of());
                return true;
            }
            HomeData home = profile.getHomeByNameOrSlot(args[2]);
            if (home == null) {
                homesManager.sendHomeMessage(player, "home-not-found", Map.of());
                return true;
            }
            homesManager.teleportPlayerToHome(player, home);
            return true;
        }

        if (sub.equals("otp")) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "homes", "this-subcommand-can-only-be-executed");
                return true;
            }
            if (args.length < 3) {
                Messages.send(plugin, player, "homes", "use-ahome-otp");
                return true;
            }
            PlayerLookup.byName(plugin, sender, args[1], target -> {
                if (target.getName() == null) {
                    homesManager.sendHomeMessage(player, "invalid-player", Map.of());
                    return;
                }
                homesManager.loadPlayerHomes(target.getUniqueId(), target.getName()).thenAccept(profile -> {
                    HomeData home = profile.getHomeByNameOrSlot(args[2]);
                    if (home == null) {
                        homesManager.sendHomeMessage(player, "home-not-found", Map.of());
                        return;
                    }
                    homesManager.teleportPlayerToHome(player, home);
                });
            });
            return true;
        }

        if (sub.equals("rename")) {
            if (args.length < 4) {
                Messages.send(plugin, sender, "homes", "use-ahome-rename");
                return true;
            }
            PlayerLookup.byName(plugin, sender, args[1], target -> {
                if (target.getName() == null) {
                    Messages.send(plugin, sender, "homes", "player-not-found");
                    return;
                }
                String newName = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                homesManager.loadPlayerHomes(target.getUniqueId(), target.getName()).thenAccept(profile -> {
                    HomeData home = profile.getHomeByNameOrSlot(args[2]);
                    if (home == null) {
                        Messages.send(plugin, sender, "homes", "home-not-found-2");
                        return;
                    }
                    home.setName(newName);
                    homesManager.savePlayerHomesAsync(profile);
                    homesManager.sendHomeMessage(sender, "home-renamed-someone-else",
                            Map.of("%player%", target.getName(), "%home%", String.valueOf(home.getSlot()), "%name%", newName));
                });
            });
            return true;
        }

        if (sub.equals("delete")) {
            if (args.length < 3) {
                Messages.send(plugin, sender, "homes", "use-ahome-delete");
                return true;
            }
            PlayerLookup.byName(plugin, sender, args[1], target -> {
                if (target.getName() == null) {
                    Messages.send(plugin, sender, "homes", "player-not-found");
                    return;
                }
                homesManager.loadPlayerHomes(target.getUniqueId(), target.getName()).thenAccept(profile -> {
                    HomeData home = profile.getHomeByNameOrSlot(args[2]);
                    if (home == null) {
                        Messages.send(plugin, sender, "homes", "home-not-found-2");
                        return;
                    }
                    profile.removeHome(home.getSlot());
                    homesManager.savePlayerHomesAsync(profile);
                    homesManager.sendHomeMessage(sender, "home-removed-someone-else",
                            Map.of("%player%", target.getName(), "%home%", String.valueOf(home.getSlot())));
                });
            });
            return true;
        }

        if (sub.equals("migrate")) {
            File customFile = args.length >= 2 ? new File(String.join(" ", Arrays.copyOfRange(args, 1, args.length))) : null;
            migrator.migrate(sender, customFile);
            return true;
        }

        Messages.send(plugin, sender, "homes", "unknown-subcommand-type-ahome-help-for");
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!homesManager.hasHomeAdminPermission(sender)) return List.of();
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String input = args[0].toLowerCase();
            List<String> subs = List.of("help", "reload", "see", "osee", "tp", "otp", "rename", "delete", "migrate");
            for (String s : subs) {
                if (s.startsWith(input)) completions.add(s);
            }
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (List.of("see", "osee", "tp", "otp", "rename", "delete").contains(sub)) {
                String input = args[1].toLowerCase();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase().startsWith(input)) completions.add(p.getName());
                }
            }
        } else if (args.length == 3) {
            String sub = args[0].toLowerCase();
            if (List.of("tp", "otp", "rename", "delete").contains(sub)) {
                Player target = Bukkit.getPlayer(args[1]);
                if (target != null) {
                    PlayerHomes profile = homesManager.getPlayerHomes(target.getUniqueId());
                    if (profile != null) {
                        String input = args[2].toLowerCase();
                        for (HomeData h : profile.getHomes().values()) {
                            if (String.valueOf(h.getSlot()).startsWith(input)) completions.add(String.valueOf(h.getSlot()));
                            if (h.getName() != null && !h.getName().isEmpty() && h.getName().toLowerCase().startsWith(input)) {
                                completions.add(h.getName());
                            }
                        }
                    }
                }
            }
        }

        return completions;
    }
}
