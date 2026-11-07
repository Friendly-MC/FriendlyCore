package dev.lyy.friendlycore.modules.teams.manager;

import dev.lyy.friendlycore.modules.teams.TeamMessages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.teams.model.Team;
import dev.lyy.friendlycore.modules.teams.model.TeamMember;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Handles team and ally chat channel routing, administrative spy monitoring,
 * and interactive chat prompt callbacks.
 */
public class TeamsChatManager {

    public enum ChatChannel {
        PUBLIC,
        TEAM,
        ALLY
    }

    private final FriendlyCore plugin;
    private final TeamsManager teamsManager;

    private final Map<UUID, ChatQuestion> activeQuestions = new ConcurrentHashMap<>();
    private final Map<UUID, ChatChannel> playerChatChannels = new ConcurrentHashMap<>();
    private final Set<UUID> spyAdmins = ConcurrentHashMap.newKeySet();

    private long questionLifetimeSeconds = 30L;
    private String cancelPhrase = "cancel";

    public TeamsChatManager(FriendlyCore plugin, TeamsManager teamsManager) {
        this.plugin = plugin;
        this.teamsManager = teamsManager;
    }

    public void initialize() {
        loadConfiguration();
    }

    public void shutdown() {
        activeQuestions.clear();
        playerChatChannels.clear();
        spyAdmins.clear();
    }

    public void loadConfiguration() {
        FileConfiguration config = plugin.getConfigService().getTeamsConfig();
        this.questionLifetimeSeconds = config.getLong("Plugin-Settings.message-callback-settings.lifetime", 30L);
        this.cancelPhrase = config.getString("Plugin-Settings.message-callback-settings.cancel-phrase", "cancel");
    }

    public void askQuestion(Player player, String promptMessage, Consumer<String> callback) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();

        ChatQuestion question = new ChatQuestion(callback, questionLifetimeSeconds);
        activeQuestions.put(uuid, question);

        if (promptMessage != null && !promptMessage.isBlank()) {
            player.sendMessage(plugin.getTextService().parse(promptMessage));
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);
    }

    public boolean handleChatInput(Player player, String message) {
        if (player == null || message == null) return false;
        UUID uuid = player.getUniqueId();
        ChatQuestion question = activeQuestions.remove(uuid);
        if (question == null) return false;

        plugin.getScheduler().runEntity(player, () -> processChatInput(player, message, question));
        return true;
    }

    public void clearQuestion(UUID playerUuid) { activeQuestions.remove(playerUuid); }

    private void processChatInput(Player player, String message, ChatQuestion question) {
        if (question.isExpired()) {
            TeamMessages.send(plugin, player, "interaction-timed-out");
            return;
        }
        String input = message.trim();
        if (input.equalsIgnoreCase(cancelPhrase)) {
            TeamMessages.send(plugin, player, "info-cancelled");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }
        try {
            question.callback.accept(input);
        } catch (Exception error) {
            plugin.getLogger().warning("Error processing team chat callback for " + player.getName() + ": " + error.getMessage());
        }
    }

    public ChatChannel getPlayerChannel(UUID playerUuid) {
        return playerChatChannels.getOrDefault(playerUuid, ChatChannel.PUBLIC);
    }

    public void setPlayerChannel(UUID playerUuid, ChatChannel channel) {
        if (channel == null || channel == ChatChannel.PUBLIC) {
            playerChatChannels.remove(playerUuid);
        } else {
            playerChatChannels.put(playerUuid, channel);
        }
    }

    public boolean toggleSpy(UUID adminUuid) {
        if (spyAdmins.contains(adminUuid)) {
            spyAdmins.remove(adminUuid);
            return false;
        } else {
            spyAdmins.add(adminUuid);
            return true;
        }
    }

    public boolean isSpying(UUID adminUuid) {
        return spyAdmins.contains(adminUuid);
    }

    public void sendTeamChat(Player sender, Team team, String message) {
        if (sender == null || team == null || !team.hasMember(sender.getUniqueId()) || message == null || message.isBlank()) return;

        TeamMember member = team.getMember(sender.getUniqueId());
        String roleName = member != null && member.getRole() != null ? member.getRole().getName() : "Member";

        Component formatted = plugin.getTextService().parse(
                TeamMessages.raw(plugin, "misc-teammsg-format"),
                Map.of("%role%", roleName, "%player%", sender.getName(), "%message%", message)
        );

        for (TeamMember m : team.getMemberList()) {
            Player p = Bukkit.getPlayer(m.getUniqueId());
            if (p != null && p.isOnline()) {
                plugin.getScheduler().runEntity(p, () -> { if (p.isOnline()) p.sendMessage(formatted); });
            }
        }

        sendToSpies(sender, team, "Team", message);

        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            plugin.getRedisService().publish(new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                    plugin.getRedisService().getServerId(),
                    dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.TEAM_CHAT_SYNC,
                    team.getUniqueId(),
                    sender.getName(),
                    0,
                    roleName + "\u0001" + message
            ));
        }
    }

    public void sendAllyChat(Player sender, Team team, String message) {
        if (!teamsManager.isAllyEnabled() || sender == null || team == null || !team.hasMember(sender.getUniqueId()) || message == null || message.isBlank()) return;

        TeamMember member = team.getMember(sender.getUniqueId());
        String roleName = member != null && member.getRole() != null ? member.getRole().getName() : "Member";

        Component formatted = plugin.getTextService().parse(
                TeamMessages.raw(plugin, "misc-allymsg-format"),
                Map.of("%team%", team.getTag(), "%role%", roleName, "%player%", sender.getName(), "%message%", message)
        );

        for (TeamMember m : team.getMemberList()) {
            Player p = Bukkit.getPlayer(m.getUniqueId());
            if (p != null && p.isOnline()) {
                plugin.getScheduler().runEntity(p, () -> { if (p.isOnline()) p.sendMessage(formatted); });
            }
        }

        for (UUID allyTeamUuid : team.getAllies()) {
            Optional<Team> allyOpt = teamsManager.getTeamByUuid(allyTeamUuid);
            if (allyOpt.isPresent()) {
                Team allyTeam = allyOpt.get();
                for (TeamMember m : allyTeam.getMemberList()) {
                    Player p = Bukkit.getPlayer(m.getUniqueId());
                    if (p != null && p.isOnline()) {
                        plugin.getScheduler().runEntity(p, () -> { if (p.isOnline()) p.sendMessage(formatted); });
                    }
                }
            }
        }

        sendToSpies(sender, team, "Ally", message);

        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            plugin.getRedisService().publish(new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                    plugin.getRedisService().getServerId(),
                    dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.ALLY_CHAT_SYNC,
                    team.getUniqueId(),
                    sender.getName(),
                    0,
                    team.getTag() + "\u0001" + roleName + "\u0001" + message
            ));
        }
    }

    private void sendToSpies(Player sender, Team team, String chatType, String message) {
        Component spyMsg = plugin.getTextService().parse(
                TeamMessages.raw(plugin, "Ally".equals(chatType) ? "misc-allymsg-spy-format" : "misc-teammsg-spy-format"),
                Map.of("%team%", team.getTag(), "%player%", sender.getName(), "%message%", message)
        );
        for (UUID spyUuid : spyAdmins) {
            Player spy = Bukkit.getPlayer(spyUuid);
            if (spy != null && spy.isOnline() && !team.hasMember(spyUuid)) {
                plugin.getScheduler().runEntity(spy, () -> { if (spy.isOnline()) spy.sendMessage(spyMsg); });
            }
        }
    }

    private static class ChatQuestion {
        private final Consumer<String> callback;
        private final long expiresAt;

        public ChatQuestion(Consumer<String> callback, long lifetimeSeconds) {
            this.callback = callback;
            this.expiresAt = System.currentTimeMillis() + (lifetimeSeconds * 1000L);
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
