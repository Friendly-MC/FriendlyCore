package dev.lyy.friendlycore.modules.teams.command;

import dev.lyy.friendlycore.modules.teams.TeamMessages;
import dev.lyy.friendlycore.common.PlayerLookup;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.teams.manager.TeamsChatManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsManager;
import dev.lyy.friendlycore.modules.teams.model.Team;
import dev.lyy.friendlycore.modules.teams.model.TeamMember;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Administrative command executor for staff team inspection, forced deletion,
 * chat spying, vault oversight, and legacy database migration.
 */
public class TeamAdminCommand implements CommandExecutor {

    private final FriendlyCore plugin;
    private final TeamsManager teamsManager;
    private final TeamsChatManager chatManager;
    private final dev.lyy.friendlycore.modules.teams.migration.ZelTeamsMigrator migrator;

    public TeamAdminCommand(FriendlyCore plugin, TeamsManager teamsManager, TeamsChatManager chatManager) {
        this.plugin = plugin;
        this.teamsManager = teamsManager;
        this.chatManager = chatManager;
        this.migrator = new dev.lyy.friendlycore.modules.teams.migration.ZelTeamsMigrator(plugin, teamsManager);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("friendlycore.teams.admin") && !sender.hasPermission("zelteams.admin") && !sender.isOp()) {
            TeamMessages.send(plugin, sender, "you-do-not-have-permission-to-execute");
            return true;
        }

        if (!teamsManager.requireReady(sender)) return true;

        if (args.length == 0) {
            sendAdminHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "delete", "remove", "disband" -> handleDelete(sender, args);
            case "inspect", "info" -> handleInspect(sender, args);
            case "check" -> handleCheck(sender, args);
            case "enderchest", "ec", "vault", "chest" -> handleEnderChest(sender, args);
            case "unally" -> handleUnally(sender, args);
            case "spy" -> handleSpy(sender);
            case "convert", "migrate" -> handleConvert(sender, args);
            case "reload" -> handleReload(sender);
            default -> sendAdminHelp(sender);
        }

        return true;
    }

    private void handleDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            TeamMessages.send(plugin, sender, "use-teamadmin-delete");
            return;
        }

        Optional<Team> teamOpt = teamsManager.getTeamByTag(args[1]);
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, sender, "team-not-found", "%input%", args[1]);
            return;
        }

        Team team = teamOpt.get();
        teamsManager.disbandTeam(team, sender);
    }

    private void handleInspect(CommandSender sender, String[] args) {
        if (args.length < 2) {
            TeamMessages.send(plugin, sender, "use-teamadmin-inspect");
            return;
        }

        Optional<Team> teamOpt = teamsManager.getTeamByTag(args[1]);
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, sender, "team-not-found", "%input%", args[1]);
            return;
        }

        Team team = teamOpt.get();
        TeamMessages.send(plugin, sender, "admin-inspect-header", "%team_display_name%", team.getName(), "%team_tag%", team.getTag());
        TeamMessages.send(plugin, sender, "admin-inspect-uuid", "%uuid%", String.valueOf(team.getUniqueId()));
        TeamMessages.send(plugin, sender, "admin-inspect-leader", "%player%", String.valueOf(team.getOwner().getUsername()), "%uuid%", String.valueOf(team.getOwner().getUniqueId()));
        TeamMessages.send(plugin, sender, "admin-inspect-bank-balance", "%bank_balance%", String.valueOf(plugin.getNumberFormatter().format(team.getBankBalance())));
        TeamMessages.send(plugin, sender, "admin-inspect-color-key", "%color_key%", String.valueOf(team.getColorKey()));
        TeamMessages.send(plugin, sender, team.isFriendlyFireEnabled() ? "admin-inspect-friendly-fire-enabled" : "admin-inspect-friendly-fire-disabled");
        TeamMessages.send(plugin, sender, "admin-inspect-members", "%member_count%", String.valueOf(team.getMemberCount()));
        for (TeamMember m : team.getMemberList()) {
            TeamMessages.send(plugin, sender, "admin-inspect-member-line", "%player%", m.getUsername(), "%role%", m.getRole() != null ? m.getRole().getName() : TeamMessages.raw(plugin, "misc-default-role"));
        }
        TeamMessages.send(plugin, sender, "admin-inspect-allies", "%ally_count%", String.valueOf(team.getAllies().size()), "%allies%", String.valueOf(team.getAllies().toString()));
    }

    private void handleCheck(CommandSender sender, String[] args) {
        if (args.length < 2) {
            TeamMessages.send(plugin, sender, "use-teamadmin-check");
            return;
        }

        PlayerLookup.byName(plugin, sender, args[1], op -> sendCheck(sender, op));
    }

    private void sendCheck(CommandSender sender, OfflinePlayer op) {
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(op.getUniqueId());
        if (teamOpt.isPresent()) {
            Team team = teamOpt.get();
            TeamMessages.send(plugin, sender, "is-in-team", "%player%", op.getName(), "%team_tag%", team.getTag(), "%team_display_name%", team.getName());
        } else {
            TeamMessages.send(plugin, sender, "is-not-in-any-team", "%player%", op.getName());
        }
    }

    private void handleEnderChest(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            TeamMessages.send(plugin, sender, "must-be-a-player-to-view-enderchests");
            return;
        }

        if (args.length < 2) {
            TeamMessages.send(plugin, sender, "use-teamadmin-enderchest");
            return;
        }

        Optional<Team> teamOpt = teamsManager.getTeamByTag(args[1]);
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, sender, "team-not-found", "%input%", args[1]);
            return;
        }

        Team team = teamOpt.get();
        teamsManager.acquireTeamVault(team, player).thenAccept(snapshot -> plugin.getScheduler().runEntity(player, () -> {
            if (!player.isOnline()) {
                if (snapshot != null) teamsManager.releaseTeamVault(team, snapshot);
                return;
            }
            if (snapshot == null) {
                TeamMessages.send(plugin, player, "the-team-enderchest-is-currently-in-use");
                return;
            }
            teamsManager.openTeamVault(team, player, snapshot);
            player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.0f);
        }, () -> teamsManager.releaseTeamVault(team, snapshot)));
    }

    private void handleUnally(CommandSender sender, String[] args) {
        if (args.length < 3) {
            TeamMessages.send(plugin, sender, "use-teamadmin-unally");
            return;
        }

        Optional<Team> t1 = teamsManager.getTeamByTag(args[1]);
        Optional<Team> t2 = teamsManager.getTeamByTag(args[2]);

        if (t1.isEmpty() || t2.isEmpty()) {
            TeamMessages.send(plugin, sender, "one-or-both-teams-not-found");
            return;
        }

        teamsManager.removeAlly(t1.get(), sender, t2.get().getUniqueId());
        TeamMessages.send(plugin, sender, "alliance-revoked-between-and", "%team_tag%", String.valueOf(t1.get().getTag()), "%team_tag_2%", String.valueOf(t2.get().getTag()));
    }

    private void handleSpy(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            TeamMessages.send(plugin, sender, "only-players-can-toggle-spy-mode");
            return;
        }

        boolean active = chatManager.toggleSpy(player.getUniqueId());
        TeamMessages.send(plugin, sender, active ? "info-spy-toggled" : "info-spy-untoggled");
    }

    private void handleConvert(CommandSender sender, String[] args) {
        File customFile = args.length >= 2 ? new File(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))) : null;
        migrator.migrate(sender, customFile);
    }

    private void handleReload(CommandSender sender) {
        if (plugin.getTeamsModule() != null) {
            plugin.getTeamsModule().reload();
        } else {
            teamsManager.loadConfiguration();
            chatManager.loadConfiguration();
        }
        TeamMessages.send(plugin, sender, "teams-configuration-and-menus-reloaded-successfully");
    }

    private void sendAdminHelp(CommandSender sender) {
        TeamMessages.send(plugin, sender, "friendlyteams-admin-staff-commands");
        TeamMessages.send(plugin, sender, "teamadmin-delete-forcefully-delete-team");
        TeamMessages.send(plugin, sender, "teamadmin-inspect-inspect-team-details");
        TeamMessages.send(plugin, sender, "teamadmin-check-check-player-s-team");
        TeamMessages.send(plugin, sender, "teamadmin-enderchest-view-team-enderchest");
        TeamMessages.send(plugin, sender, "teamadmin-unally-break-alliance");
        TeamMessages.send(plugin, sender, "teamadmin-spy-toggle-team-chat-spy");
        TeamMessages.send(plugin, sender, "teamadmin-convert-convert-legacy-zelteams-data");
        TeamMessages.send(plugin, sender, "teamadmin-reload-reload-configurations");
    }
}
