package dev.lyy.friendlycore.modules.teams.command;

import dev.lyy.friendlycore.modules.teams.TeamMessages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.teams.gui.TeamsGuiManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsLeaderboardManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsTeleportManager;
import dev.lyy.friendlycore.modules.teams.model.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Command executor and tab completer for team shortcut aliases
 * (e.g., /thome, /tsethome, /tdelhome, /thomes, /tec, /tbank, /tstats, /ttop).
 */
public class TeamShortcutCommands implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final TeamsManager teamsManager;
    private final TeamsGuiManager guiManager;
    private final TeamsTeleportManager teleportManager;
    private final TeamsLeaderboardManager leaderboardManager;

    public TeamShortcutCommands(FriendlyCore plugin, TeamsManager teamsManager, TeamsGuiManager guiManager,
                                TeamsTeleportManager teleportManager, TeamsLeaderboardManager leaderboardManager) {
        this.plugin = plugin;
        this.teamsManager = teamsManager;
        this.guiManager = guiManager;
        this.teleportManager = teleportManager;
        this.leaderboardManager = leaderboardManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            TeamMessages.send(plugin, sender, "this-command-can-only-be-executed-by");
            return true;
        }

        if (!teamsManager.requireReady(sender)) return true;

        String cmdName = command.getName().toLowerCase(Locale.ROOT);
        switch (cmdName) {
            case "thome" -> handleTHome(player, args);
            case "tsethome" -> handleTSetHome(player, args);
            case "tdelhome" -> handleTDelHome(player, args);
            case "thomes" -> handleTHomes(player);
            case "tec", "tvault" -> handleTEc(player);
            case "tbank" -> handleTBank(player, args);
            case "tstats" -> handleTStats(player, args);
            case "ttop" -> handleTTop(player);
            default -> TeamMessages.send(plugin, player, "unknown-team-shortcut-command");
        }

        return true;
    }

    private void handleTHome(Player player, String[] args) {
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        if (!teamsManager.canUseHome(team, player, true)) {
            return;
        }

        if (args.length > 0) {
            String homeName = args[0].toLowerCase(Locale.ROOT);
            TeamHome home = team.getHome(homeName);
            if (home == null) {
                TeamMessages.send(plugin, player, "team-home-is-not-set", "%home%", homeName);
                return;
            }
            teleportManager.startTeleport(player, home);
        } else {
            Map<String, TeamHome> homes = team.getHomes();
            if (homes.isEmpty()) {
                TeamMessages.send(plugin, player, "your-team-has-no-homes-set-use");
            } else if (homes.size() == 1) {
                TeamHome home = homes.values().iterator().next();
                teleportManager.startTeleport(player, home);
            } else {
                guiManager.openHomesMenu(player, 1);
            }
        }
    }

    private void handleTSetHome(Player player, String[] args) {
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        String homeName = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : Team.DEFAULT_HOME;
        if (teamsManager.setTeamHome(team, player, homeName, player.getLocation())) {
            TeamMessages.send(plugin, player, "info-set-home", "%home%", homeName);
            player.playSound(player.getLocation(), Sound.UI_LOOM_TAKE_RESULT, 1.0f, 1.0f);
        }
    }

    private void handleTDelHome(Player player, String[] args) {
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        String homeName = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : Team.DEFAULT_HOME;
        if (team.getHome(homeName) == null) {
            TeamMessages.send(plugin, player, "error-home-not-found", "%home%", homeName);
            return;
        }

        if (teamsManager.deleteTeamHome(team, player, homeName)) {
            TeamMessages.send(plugin, player, "info-home-deleted", "%home%", homeName);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
        }
    }

    private void handleTHomes(Player player) {
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }
        guiManager.openHomesMenu(player, 1);
    }

    private void handleTEc(Player player) {
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }
        guiManager.openEnderChest(player);
    }

    private void handleTBank(Player player, String[] args) {
        if (!teamsManager.isBankEnabled()) {
            TeamMessages.send(plugin, player, "the-team-bank-feature-is-currently-disabled");
            return;
        }

        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        if (args.length == 0) {
            guiManager.openBankMenu(player);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if ((sub.equals("deposit") || sub.equals("add")) && args.length > 1) {
            if (plugin.getVaultEconomy() == null) {
                TeamMessages.send(plugin, player, "economy-is-not-enabled-2");
                return;
            }
            try {
                double amount = dev.lyy.friendlycore.common.NumberFormatter.parseAmount(args[1]);
                if (amount <= 0) {
                    TeamMessages.send(plugin, player, "amount-must-be-greater-than-0-2");
                    return;
                }
                if (!plugin.getVaultEconomy().has(player, amount)) {
                    TeamMessages.send(plugin, player, "error-not-enough-money");
                    return;
                }
                teamsManager.depositFromPlayer(player, team, amount).thenAccept(result -> plugin.getScheduler().runEntity(player, () -> {
                    if (!result.success()) {
                        TeamMessages.send(plugin, player, result.status() == TeamsManager.BankTransactionStatus.LIMIT_EXCEEDED ? "bank-limit-exceeded" : "bank-deposit-failed");
                        return;
                    }
                    TeamMessages.send(plugin, player, "bank-deposited", "%amount%", plugin.getNumberFormatter().formatExact(amount), "%bank%", plugin.getNumberFormatter().formatExact(result.balance()));
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                }));
            } catch (Exception e) {
                TeamMessages.send(plugin, player, "invalid-amount", "%input%", args[1]);
            }
        } else if ((sub.equals("withdraw") || sub.equals("take")) && args.length > 1) {
            if (plugin.getVaultEconomy() == null) {
                TeamMessages.send(plugin, player, "economy-is-not-enabled-2");
                return;
            }
            if (!team.hasMember(player.getUniqueId()) || !team.getMember(player.getUniqueId()).hasPermission(RolePermission.ACCESS_BANK)) {
                TeamMessages.send(plugin, player, "you-do-not-have-permission-to-withdraw-2");
                return;
            }
            try {
                double amount = dev.lyy.friendlycore.common.NumberFormatter.parseAmount(args[1]);
                if (amount <= 0) {
                    TeamMessages.send(plugin, player, "amount-must-be-greater-than-0-2");
                    return;
                }
                teamsManager.withdrawToPlayer(player, team, amount).thenAccept(result -> plugin.getScheduler().runEntity(player, () -> {
                    if (result.success()) {
                    TeamMessages.send(plugin, player, "bank-withdrew", "%amount%", plugin.getNumberFormatter().formatExact(amount), "%bank%", plugin.getNumberFormatter().formatExact(result.balance()));
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
                    } else TeamMessages.send(plugin, player, result.status() == TeamsManager.BankTransactionStatus.INSUFFICIENT_FUNDS ? "bank-insufficient-funds" : "bank-withdraw-failed");
                }));
            } catch (Exception e) {
                TeamMessages.send(plugin, player, "invalid-amount", "%input%", args[1]);
            }
        } else {
            guiManager.openBankMenu(player);
        }
    }

    private void handleTStats(Player player, String[] args) {
        if (args.length > 0) {
            guiManager.openPlayerStatsMenu(player, args[0]);
        } else {
            Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
            if (teamOpt.isEmpty()) {
                TeamMessages.send(plugin, player, "error-no-team");
                return;
            }
            guiManager.openStatsMenu(player);
        }
    }

    private void handleTTop(Player player) {
        guiManager.openLeaderboardMenu(player, 1, "score");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }

        String cmdName = command.getName().toLowerCase(Locale.ROOT);
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());

        if (cmdName.equals("thome") || cmdName.equals("tdelhome")) {
            if (args.length == 1 && teamOpt.isPresent()) {
                return filterPrefix(teamOpt.get().getHomes().keySet(), args[0]);
            }
        } else if (cmdName.equals("tbank")) {
            if (args.length == 1) {
                return filterPrefix(List.of("deposit", "withdraw"), args[0]);
            } else if (args.length == 2) {
                return filterPrefix(List.of("100", "1000", "10k", "100k", "1m"), args[1]);
            }
        } else if (cmdName.equals("tstats")) {
            if (args.length == 1) {
                return filterPrefix(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[0]);
            }
        }

        return Collections.emptyList();
    }

    private List<String> filterPrefix(Collection<String> items, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return items.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p)).collect(Collectors.toList());
    }
}
