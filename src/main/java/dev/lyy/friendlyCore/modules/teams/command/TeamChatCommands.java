package dev.lyy.friendlycore.modules.teams.command;

import dev.lyy.friendlycore.modules.teams.TeamMessages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.teams.manager.TeamsChatManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsManager;
import dev.lyy.friendlycore.modules.teams.model.Team;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Optional;

/**
 * Command executor for toggling or sending messages in private team and ally chat channels.
 */
public class TeamChatCommands implements CommandExecutor {

    private final FriendlyCore plugin;
    private final TeamsManager teamsManager;
    private final TeamsChatManager chatManager;
    private final boolean isAlly;

    public TeamChatCommands(FriendlyCore plugin, TeamsManager teamsManager, TeamsChatManager chatManager, boolean isAlly) {
        this.plugin = plugin;
        this.teamsManager = teamsManager;
        this.chatManager = chatManager;
        this.isAlly = isAlly;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            TeamMessages.send(plugin, sender, "this-command-can-only-be-executed-by");
            return true;
        }

        if (!teamsManager.requireReady(sender)) return true;
        if (isAlly && !teamsManager.isAllyEnabled()) {
            TeamMessages.send(plugin, player, "alliances-are-disabled");
            return true;
        }

        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return true;
        }

        Team team = teamOpt.get();

        if (args.length > 0) {
            String msg = String.join(" ", args);
            if (isAlly) {
                chatManager.sendAllyChat(player, team, msg);
            } else {
                chatManager.sendTeamChat(player, team, msg);
            }
        } else {
            TeamsChatManager.ChatChannel target = isAlly ? TeamsChatManager.ChatChannel.ALLY : TeamsChatManager.ChatChannel.TEAM;
            TeamsChatManager.ChatChannel current = chatManager.getPlayerChannel(player.getUniqueId());
            TeamsChatManager.ChatChannel next = current == target ? TeamsChatManager.ChatChannel.PUBLIC : target;
            chatManager.setPlayerChannel(player.getUniqueId(), next);
            TeamMessages.send(plugin, player, isAlly
                    ? (next == target ? "info-allymsg-toggled" : "info-allymsg-untoggled")
                    : (next == target ? "info-teammsg-toggled" : "info-teammsg-untoggled"));
        }

        return true;
    }
}
