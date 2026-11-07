package dev.lyy.friendlycore.modules.teams.listener;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.teams.manager.TeamsChatManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsManager;
import dev.lyy.friendlycore.modules.teams.model.Team;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Optional;

/**
 * Listens for player chat events to intercept interactive prompt responses
 * and route messages through private team and ally channels.
 */
public class TeamsChatListener implements Listener {

    private final FriendlyCore plugin;
    private final TeamsManager teamsManager;
    private final TeamsChatManager chatManager;

    public TeamsChatListener(FriendlyCore plugin, TeamsManager teamsManager, TeamsChatManager chatManager) {
        this.plugin = plugin;
        this.teamsManager = teamsManager;
        this.chatManager = chatManager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPaperChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        if (chatManager.handleChatInput(player, message)) {
            event.setCancelled(true);
            return;
        }

        // Handle team or ally chat channel routing
        TeamsChatManager.ChatChannel channel = chatManager.getPlayerChannel(player.getUniqueId());
        if (channel != TeamsChatManager.ChatChannel.PUBLIC) {
            Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
            if (teamOpt.isPresent()) {
                event.setCancelled(true);
                if (channel == TeamsChatManager.ChatChannel.TEAM) {
                    chatManager.sendTeamChat(player, teamOpt.get(), message);
                } else if (channel == TeamsChatManager.ChatChannel.ALLY) {
                    chatManager.sendAllyChat(player, teamOpt.get(), message);
                }
            } else {
                chatManager.setPlayerChannel(player.getUniqueId(), TeamsChatManager.ChatChannel.PUBLIC);
            }
        }
    }

}
