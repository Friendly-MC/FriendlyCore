package dev.lyy.friendlycore.modules.teams.command;

import dev.lyy.friendlycore.modules.teams.manager.TeamsManager;
import dev.lyy.friendlycore.modules.teams.model.Team;
import dev.lyy.friendlycore.modules.teams.model.TeamMember;
import dev.lyy.friendlycore.modules.teams.model.TeamColor;
import dev.lyy.friendlycore.modules.teams.model.RolePermission;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Context-aware tab completer for root `/team` and staff `/teamadmin` commands.
 */
public class TeamTabCompleter implements TabCompleter {

    private final TeamsManager teamsManager;

    private static final List<String> ROOT_SUBS = List.of(
            "gui", "create", "rename", "disband", "leave", "invite", "accept", "deny", "mailbox",
            "kick", "promote", "demote", "info", "stats", "leaderboard", "allies", "ally",
            "home", "sethome", "delhome", "homes", "bank", "deposit", "withdraw", "enderchest", "vault", "ec", "color", "settings", "chat", "allychat", "help"
    );

    private static final List<String> ADMIN_SUBS = List.of(
            "delete", "inspect", "check", "enderchest", "vault", "ec", "unally", "spy", "convert", "reload"
    );

    public TeamTabCompleter(TeamsManager teamsManager) {
        this.teamsManager = teamsManager;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (command.getName().equalsIgnoreCase("teamadmin")) {
            if (args.length == 1) {
                return filterPrefix(ADMIN_SUBS, args[0]);
            } else if (args.length == 2) {
                String sub = args[0].toLowerCase();
                if (sub.equals("delete") || sub.equals("inspect") || sub.equals("enderchest") || sub.equals("unally")) {
                    return filterPrefix(teamsManager.getAllTeams().stream().map(Team::getTag).toList(), args[1]);
                } else if (sub.equals("check")) {
                    return filterPrefix(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
                }
            } else if (args.length == 3 && args[0].equalsIgnoreCase("unally")) {
                return filterPrefix(teamsManager.getAllTeams().stream().map(Team::getTag).toList(), args[2]);
            }
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filterPrefix(ROOT_SUBS, args[0]);
        }

        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }

        String sub = args[0].toLowerCase();
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());

        if (args.length == 2) {
            switch (sub) {
                case "invite" -> {
                    return filterPrefix(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
                }
                case "kick", "promote", "demote" -> {
                    if (teamOpt.isPresent()) {
                        return filterPrefix(teamOpt.get().getMemberList().stream().map(TeamMember::getUsername).toList(), args[1]);
                    }
                }
                case "info", "accept", "deny" -> {
                    return filterPrefix(teamsManager.getAllTeams().stream().map(Team::getTag).toList(), args[1]);
                }
                case "home", "delhome", "deletehome", "removehome" -> {
                    if (teamOpt.isPresent()) {
                        return filterPrefix(new ArrayList<>(teamOpt.get().getHomes().keySet()), args[1]);
                    }
                }
                case "color", "colors" -> {
                    TeamMember member = teamOpt.map(team -> team.getMember(player.getUniqueId())).orElse(null);
                    if (member == null || !member.hasPermission(RolePermission.CHANGE_COLOR)) return Collections.emptyList();
                    return filterPrefix(teamsManager.getAvailableColors(player).stream().map(TeamColor::getKey).toList(), args[1]);
                }
                case "bank" -> {
                    return filterPrefix(List.of("deposit", "withdraw"), args[1]);
                }
                case "deposit", "withdraw" -> {
                    return filterPrefix(List.of("100", "1000", "10k", "100k", "1m"), args[1]);
                }
                case "ally" -> {
                    return filterPrefix(List.of("add", "remove"), args[1]);
                }
            }
        } else if (args.length == 3) {
            if (sub.equals("bank")) {
                return filterPrefix(List.of("100", "1000", "10k", "100k", "1m"), args[2]);
            } else if (sub.equals("ally")) {
                return filterPrefix(teamsManager.getAllTeams().stream().map(Team::getTag).toList(), args[2]);
            }
        }

        return completions;
    }

    private List<String> filterPrefix(Collection<String> items, String prefix) {
        String p = prefix.toLowerCase();
        return items.stream().filter(s -> s.toLowerCase().startsWith(p)).collect(Collectors.toList());
    }
}
