package dev.lyy.friendlycore.modules.teams;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.teams.command.TeamAdminCommand;
import dev.lyy.friendlycore.modules.teams.command.TeamChatCommands;
import dev.lyy.friendlycore.modules.teams.command.TeamCommand;
import dev.lyy.friendlycore.modules.teams.command.TeamShortcutCommands;
import dev.lyy.friendlycore.modules.teams.command.TeamTabCompleter;
import dev.lyy.friendlycore.modules.teams.gui.TeamsGuiManager;
import dev.lyy.friendlycore.modules.teams.hook.TeamsPlaceholderExpansion;
import dev.lyy.friendlycore.modules.teams.listener.TeamsChatListener;
import dev.lyy.friendlycore.modules.teams.listener.TeamsDamageListener;
import dev.lyy.friendlycore.modules.teams.listener.TeamsPlayerListener;
import dev.lyy.friendlycore.modules.teams.manager.TeamsChatManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsLeaderboardManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsTeleportManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;

/**
 * Lifecycle coordinator for the Teams module.
 * <p>
 * Manages initialization and shutdown of managers, event listeners,
 * commands, placeholders, and cross-server Redis PubSub packet synchronization.
 */
public class TeamsModule {

    private final FriendlyCore plugin;

    private TeamsManager teamsManager;
    private TeamsLeaderboardManager leaderboardManager;
    private TeamsTeleportManager teleportManager;
    private TeamsChatManager chatManager;
    private TeamsGuiManager guiManager;
    private TeamsPlaceholderExpansion placeholderExpansion;

    public TeamsModule(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        this.teamsManager = new TeamsManager(plugin);
        this.leaderboardManager = new TeamsLeaderboardManager(plugin, teamsManager);
        this.teleportManager = new TeamsTeleportManager(plugin);
        this.chatManager = new TeamsChatManager(plugin, teamsManager);
        this.guiManager = new TeamsGuiManager(plugin, teamsManager, leaderboardManager, teleportManager, chatManager);

        teamsManager.initialize();
        leaderboardManager.initialize();
        teleportManager.initialize();
        chatManager.initialize();
        guiManager.initialize();

        Bukkit.getPluginManager().registerEvents(new TeamsPlayerListener(teamsManager, teleportManager, chatManager), plugin);
        Bukkit.getPluginManager().registerEvents(new TeamsDamageListener(plugin, teamsManager, teleportManager), plugin);
        Bukkit.getPluginManager().registerEvents(new TeamsChatListener(plugin, teamsManager, chatManager), plugin);

        registerCommands();

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            this.placeholderExpansion = new TeamsPlaceholderExpansion(plugin, teamsManager, leaderboardManager);
            this.placeholderExpansion.register();
        }

        plugin.getLogger().info("TeamsModule initialized successfully.");
    }

    public void shutdown() {
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }

        if (guiManager != null) guiManager.shutdown();
        if (chatManager != null) chatManager.shutdown();
        if (teleportManager != null) teleportManager.shutdown();
        if (leaderboardManager != null) leaderboardManager.shutdown();
        if (teamsManager != null) teamsManager.shutdown();

        plugin.getLogger().info("TeamsModule shut down successfully.");
    }

    public void reload() {
        if (teamsManager != null) teamsManager.loadConfiguration();
        if (chatManager != null) chatManager.loadConfiguration();
        if (leaderboardManager != null) leaderboardManager.loadConfiguration();
        if (teleportManager != null) teleportManager.loadConfiguration();
        if (guiManager != null) guiManager.clearLayoutCache();
    }

    private void registerCommands() {
        TeamTabCompleter tabCompleter = new TeamTabCompleter(teamsManager);

        PluginCommand teamCmd = plugin.getCommand("team");
        if (teamCmd != null) {
            teamCmd.setExecutor(new TeamCommand(plugin, teamsManager, guiManager, teleportManager, chatManager, leaderboardManager, plugin.getTextInputManager()));
            teamCmd.setTabCompleter(tabCompleter);
        }

        PluginCommand teamAdminCmd = plugin.getCommand("teamadmin");
        if (teamAdminCmd != null) {
            teamAdminCmd.setExecutor(new TeamAdminCommand(plugin, teamsManager, chatManager));
            teamAdminCmd.setTabCompleter(tabCompleter);
        }

        PluginCommand tcCmd = plugin.getCommand("teamchat");
        if (tcCmd != null) {
            tcCmd.setExecutor(new TeamChatCommands(plugin, teamsManager, chatManager, false));
        }

        PluginCommand acCmd = plugin.getCommand("allychat");
        if (acCmd != null) {
            acCmd.setExecutor(new TeamChatCommands(plugin, teamsManager, chatManager, true));
        }

        TeamShortcutCommands shortcutExecutor = new TeamShortcutCommands(plugin, teamsManager, guiManager, teleportManager, leaderboardManager);
        String[] shortcuts = {"thome", "tsethome", "tdelhome", "thomes", "tec", "tvault", "tbank", "tstats", "ttop"};
        for (String shortcut : shortcuts) {
            PluginCommand cmd = plugin.getCommand(shortcut);
            if (cmd != null) {
                cmd.setExecutor(shortcutExecutor);
                cmd.setTabCompleter(shortcutExecutor);
            }
        }
    }

    public TeamsManager getTeamsManager() {
        return teamsManager;
    }

    public TeamsLeaderboardManager getLeaderboardManager() {
        return leaderboardManager;
    }

    public TeamsTeleportManager getTeleportManager() {
        return teleportManager;
    }

    public TeamsChatManager getChatManager() {
        return chatManager;
    }

    public TeamsGuiManager getGuiManager() {
        return guiManager;
    }

    public TeamsPlaceholderExpansion getPlaceholderExpansion() {
        return placeholderExpansion;
    }

    public void handleIncomingCrossServerPacket(dev.lyy.friendlycore.common.redis.CrossServerPacket packet) {
        if (packet == null || teamsManager == null) return;
        if (!teamsManager.isReady()) {
            teamsManager.whenReady(() -> plugin.getScheduler().runGlobal(() -> handleIncomingCrossServerPacket(packet)));
            return;
        }
        switch (packet.getAction()) {
            case TEAM_CREATE_SYNC -> {
                java.util.UUID teamUuid = packet.getTargetUuid();
                String tag = packet.getTargetName();
                String extra = packet.getExtra();
                if (teamUuid != null && tag != null && extra != null && extra.contains(";")) {
                    String[] parts = extra.split(";", -1);
                    if (parts.length >= 2) {
                        try {
                            java.util.UUID ownerUuid = java.util.UUID.fromString(parts[0]);
                            String ownerName = parts[1];
                            String teamName = parts.length > 2 && !parts[2].isBlank() ? parts[2] : tag;
                            String colorKey = parts.length > 4 && !parts[4].isBlank() ? parts[4] : dev.lyy.friendlycore.modules.teams.model.Team.DEFAULT_COLOR;
                            boolean ff = parts.length > 5 ? Boolean.parseBoolean(parts[5]) : true;
                            boolean closed = parts.length > 6 && Boolean.parseBoolean(parts[6]);

                            if (teamsManager.getTeamByUuid(teamUuid).isEmpty()) {
                                dev.lyy.friendlycore.modules.teams.model.TeamOwner owner =
                                        new dev.lyy.friendlycore.modules.teams.model.TeamOwner(ownerUuid, ownerName, teamsManager.getOwnerRole(), System.currentTimeMillis());
                                dev.lyy.friendlycore.modules.teams.model.Team team =
                                        new dev.lyy.friendlycore.modules.teams.model.Team(teamUuid, teamName, tag, owner);
                                team.setColorKey(colorKey);
                                team.setFriendlyFireEnabled(ff);
                                team.setClosed(closed);
                                team.setDirty(false);
                                teamsManager.registerTeamInMemory(team);
                                plugin.getLogger().info("[Teams Cross-Server] Synced new team '" + tag + "' (" + teamUuid + ") from " + packet.getSourceServer());
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("[Teams Cross-Server] Failed parsing TEAM_CREATE_SYNC: " + e.getMessage());
                        }
                    }
                }
            }
            case TEAM_DISBAND_SYNC -> {
                java.util.UUID teamUuid = packet.getTargetUuid();
                if (teamUuid != null) {
                    java.util.Optional<dev.lyy.friendlycore.modules.teams.model.Team> teamOpt = teamsManager.getTeamByUuid(teamUuid);
                    if (teamOpt.isPresent()) {
                        dev.lyy.friendlycore.modules.teams.model.Team team = teamOpt.get();
                        for (java.util.UUID allyUuid : team.getAllies()) {
                            java.util.Optional<dev.lyy.friendlycore.modules.teams.model.Team> allyOpt = teamsManager.getTeamByUuid(allyUuid);
                            allyOpt.ifPresent(a -> a.removeAlly(team.getUniqueId()));
                        }
                        for (dev.lyy.friendlycore.modules.teams.model.TeamMember m : team.getMemberList()) {
                            org.bukkit.entity.Player p = Bukkit.getPlayer(m.getUniqueId());
                            if (p != null && p.isOnline()) {
                                plugin.getScheduler().runEntity(p, () -> {
                                    if (p.isOnline()) {
                                        TeamMessages.send(plugin, p, "your-team-has-been-disbanded", "%team_tag%", team.getTag());
                                        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_DEATH, 1.0f, 1.0f);
                                    }
                                });
                            }
                        }
                        teamsManager.unregisterTeamFromMemory(team);
                        plugin.getLogger().info("[Teams Cross-Server] Synced disbanded team '" + team.getTag() + "' from " + packet.getSourceServer());
                    }
                }
            }
            case TEAM_MEMBER_ADD_SYNC -> {
                java.util.UUID teamUuid = packet.getTargetUuid();
                String memberName = packet.getTargetName();
                String extra = packet.getExtra();
                if (teamUuid != null && extra != null && extra.contains(";")) {
                    String[] parts = extra.split(";", -1);
                    if (parts.length >= 2) {
                        try {
                            java.util.UUID memberUuid = java.util.UUID.fromString(parts[0]);
                            String roleKey = parts[1];
                            long joinedAt = parts.length > 2 ? Long.parseLong(parts[2]) : System.currentTimeMillis();

                            java.util.Optional<dev.lyy.friendlycore.modules.teams.model.Team> teamOpt = teamsManager.getTeamByUuid(teamUuid);
                            if (teamOpt.isPresent()) {
                                dev.lyy.friendlycore.modules.teams.model.Team team = teamOpt.get();
                                dev.lyy.friendlycore.modules.teams.model.TeamRole role =
                                        teamsManager.getRoleRegistry().getOrDefault(roleKey, teamsManager.getDefaultMemberRole());
                                dev.lyy.friendlycore.modules.teams.model.TeamMember member =
                                        new dev.lyy.friendlycore.modules.teams.model.TeamMember(memberUuid, memberName, role, joinedAt);
                                team.applyRemoteUpdate(() -> {
                                    team.addMember(member);
                                    teamsManager.getPlayerTeamMap().put(memberUuid, team);
                                });

                                org.bukkit.entity.Player p = Bukkit.getPlayer(memberUuid);
                                if (p != null && p.isOnline()) {
                                    plugin.getScheduler().runEntity(p, () -> {
                                        if (p.isOnline()) {
                                            TeamMessages.send(plugin, p, "you-joined-team", "%team_tag%", team.getTag());
                                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);
                                        }
                                    });
                                }
                                for (dev.lyy.friendlycore.modules.teams.model.TeamMember m : team.getMemberList()) {
                                    if (!m.getUniqueId().equals(memberUuid)) {
                                        org.bukkit.entity.Player teammate = Bukkit.getPlayer(m.getUniqueId());
                                        if (teammate != null && teammate.isOnline()) {
                                            plugin.getScheduler().runEntity(teammate, () -> {
                                                if (teammate.isOnline()) {
                                                    TeamMessages.send(plugin, teammate, "joined-the-team", "%player%", memberName);
                                                }
                                            });
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("[Teams Cross-Server] Failed parsing TEAM_MEMBER_ADD_SYNC: " + e.getMessage());
                        }
                    }
                }
            }
            case TEAM_MEMBER_REMOVE_SYNC -> {
                java.util.UUID teamUuid = packet.getTargetUuid();
                String memberName = packet.getTargetName();
                String extra = packet.getExtra();
                if (teamUuid != null && extra != null) {
                    try {
                        java.util.UUID memberUuid = java.util.UUID.fromString(extra.trim());
                        java.util.Optional<dev.lyy.friendlycore.modules.teams.model.Team> teamOpt = teamsManager.getTeamByUuid(teamUuid);
                        if (teamOpt.isPresent()) {
                            dev.lyy.friendlycore.modules.teams.model.Team team = teamOpt.get();
                            team.applyRemoteUpdate(() -> {
                                team.removeMember(memberUuid);
                                teamsManager.getPlayerTeamMap().remove(memberUuid, team);
                            });

                            org.bukkit.entity.Player p = Bukkit.getPlayer(memberUuid);
                            if (p != null && p.isOnline()) {
                                plugin.getScheduler().runEntity(p, () -> {
                                    if (p.isOnline()) {
                                        TeamMessages.send(plugin, p, "you-left-or-were-removed-from-team", "%team_tag%", team.getTag());
                                    }
                                });
                            }
                            for (dev.lyy.friendlycore.modules.teams.model.TeamMember m : team.getMemberList()) {
                                org.bukkit.entity.Player teammate = Bukkit.getPlayer(m.getUniqueId());
                                if (teammate != null && teammate.isOnline()) {
                                    plugin.getScheduler().runEntity(teammate, () -> {
                                        if (teammate.isOnline()) {
                                            TeamMessages.send(plugin, teammate, "left-the-team", "%player%", memberName != null ? memberName : TeamMessages.raw(plugin, "misc-unknown-member"));
                                        }
                                    });
                                }
                            }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("[Teams Cross-Server] Failed parsing TEAM_MEMBER_REMOVE_SYNC: " + e.getMessage());
                    }
                }
            }
            case TEAM_MEMBER_ROLE_SYNC -> {
                java.util.UUID teamUuid = packet.getTargetUuid();
                String memberName = packet.getTargetName();
                String extra = packet.getExtra();
                if (teamUuid != null && extra != null && extra.contains(";")) {
                    String[] parts = extra.split(";", -1);
                    if (parts.length >= 2) {
                        try {
                            java.util.UUID memberUuid = java.util.UUID.fromString(parts[0]);
                            String roleKey = parts[1];
                            java.util.Optional<dev.lyy.friendlycore.modules.teams.model.Team> teamOpt = teamsManager.getTeamByUuid(teamUuid);
                            if (teamOpt.isPresent()) {
                                dev.lyy.friendlycore.modules.teams.model.Team team = teamOpt.get();
                                dev.lyy.friendlycore.modules.teams.model.TeamMember member = team.getMember(memberUuid);
                                if (member != null) {
                                    dev.lyy.friendlycore.modules.teams.model.TeamRole role = teamsManager.getRoleRegistry().get(roleKey);
                                    if (role != null) {
                                        team.applyRemoteUpdate(() -> member.setRole(role));

                                        org.bukkit.entity.Player p = Bukkit.getPlayer(memberUuid);
                                        if (p != null && p.isOnline()) {
                                            plugin.getScheduler().runEntity(p, () -> {
                                                if (p.isOnline()) {
                                                    TeamMessages.send(plugin, p, "your-team-role-was-updated-to", "%role%", role.getName());
                                                    p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 2.0f);
                                                }
                                            });
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("[Teams Cross-Server] Failed parsing TEAM_MEMBER_ROLE_SYNC: " + e.getMessage());
                        }
                    }
                }
            }
            case TEAM_UPDATE_SYNC -> {
                java.util.UUID teamUuid = packet.getTargetUuid();
                if (teamUuid != null) {
                    java.util.Optional<dev.lyy.friendlycore.modules.teams.model.Team> teamOpt = teamsManager.getTeamByUuid(teamUuid);
                    if (teamOpt.isPresent()) {
                        dev.lyy.friendlycore.modules.teams.model.Team team = teamOpt.get();
                        teamsManager.refreshTeamBankBalance(teamUuid);
                        team.applyRemoteUpdate(() -> {
                            if (packet.getExtra() != null && !packet.getExtra().isBlank()) {
                                String[] parts = packet.getExtra().split(";", -1);
                                if (parts.length > 0 && !parts[0].isBlank()) {
                                    String newTag = parts[0];
                                    if (!team.getTag().equalsIgnoreCase(newTag)) {
                                        teamsManager.getTeamsByTag().remove(team.getTag().toLowerCase(java.util.Locale.ROOT));
                                        team.setTag(newTag);
                                        teamsManager.getTeamsByTag().put(newTag.toLowerCase(java.util.Locale.ROOT), team);
                                    }
                                }
                                if (parts.length > 1 && !parts[1].isBlank()) {
                                    team.setColorKey(parts[1]);
                                }
                                if (parts.length > 2 && !parts[2].isBlank()) {
                                    team.setFriendlyFireEnabled(Boolean.parseBoolean(parts[2]));
                                }
                                if (parts.length > 3 && !parts[3].isBlank()) {
                                    team.setClosed(Boolean.parseBoolean(parts[3]));
                                }
                                if (parts.length > 4 && !parts[4].isBlank()) {
                                    team.setName(parts[4]);
                                }
                                if (parts.length > 5) {
                                    java.util.List<java.util.UUID> allies = new java.util.ArrayList<>();
                                    for (String ally : parts[5].split(",")) {
                                        if (!ally.isBlank()) allies.add(java.util.UUID.fromString(ally));
                                    }
                                    team.getAllies().clear();
                                    team.getAllies().addAll(allies);
                                }
                                if (parts.length > 6 && !parts[6].isBlank()) team.setLastTagChange(Long.parseLong(parts[6]));
                            }
                        });
                    }
                }
            }
            case TEAM_INVITE_SYNC -> {
                java.util.UUID targetUuid = packet.getTargetUuid();
                String targetName = packet.getTargetName();
                String extra = packet.getExtra();
                if (targetUuid != null && extra != null && extra.contains(";")) {
                    String[] tokens = extra.split(";");
                    if (tokens.length >= 6) {
                        try {
                            java.util.UUID inviterUuid = java.util.UUID.fromString(tokens[0]);
                            String inviterName = tokens[1];
                            java.util.UUID tUuid = java.util.UUID.fromString(tokens[2]);
                            String tTag = tokens[3];
                            dev.lyy.friendlycore.modules.teams.model.TeamInvite.InviteType type = dev.lyy.friendlycore.modules.teams.model.TeamInvite.InviteType.valueOf(tokens[4]);
                            long lifetime = Long.parseLong(tokens[5]);

                            dev.lyy.friendlycore.modules.teams.model.TeamInvite invite = new dev.lyy.friendlycore.modules.teams.model.TeamInvite(
                                    inviterUuid, inviterName, targetUuid, targetName, tUuid, tTag, type, lifetime
                            );
                            teamsManager.getOrCreateMailbox(targetUuid).addInvite(invite);

                            org.bukkit.entity.Player targetPlayer = Bukkit.getPlayer(targetUuid);
                            if (targetPlayer != null && targetPlayer.isOnline()) {
                                plugin.getScheduler().runEntity(targetPlayer, () -> {
                                    if (targetPlayer.isOnline()) {
                                        TeamMessages.send(plugin, targetPlayer, type == dev.lyy.friendlycore.modules.teams.model.TeamInvite.InviteType.ALLY ? "mailbox-ally-invite-received" : "mailbox-team-invite-received", "%team_tag%", tTag);
                                        targetPlayer.playSound(targetPlayer.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 3.0f);
                                    }
                                });
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
            case TEAM_CHAT_SYNC -> {
                java.util.UUID teamUuid = packet.getTargetUuid();
                String senderName = packet.getTargetName();
                String extra = packet.getExtra();
                if (teamUuid != null && extra != null && extra.contains("\u0001")) {
                    String[] parts = extra.split("\u0001", 2);
                    String roleName = parts[0];
                    String msg = parts[1];
                    java.util.Optional<dev.lyy.friendlycore.modules.teams.model.Team> teamOpt = teamsManager.getTeamByUuid(teamUuid);
                    if (teamOpt.isPresent()) {
                        dev.lyy.friendlycore.modules.teams.model.Team team = teamOpt.get();
                        net.kyori.adventure.text.Component formatted = plugin.getTextService().parse(
                                TeamMessages.raw(plugin, "misc-teammsg-format"),
                                java.util.Map.of("%role%", roleName, "%player%", senderName, "%message%", msg)
                        );
                        for (dev.lyy.friendlycore.modules.teams.model.TeamMember m : team.getMemberList()) {
                            org.bukkit.entity.Player p = Bukkit.getPlayer(m.getUniqueId());
                            if (p != null && p.isOnline()) {
                                plugin.getScheduler().runEntity(p, () -> {
                                    if (p.isOnline()) p.sendMessage(formatted);
                                });
                            }
                        }
                        if (chatManager != null) {
                            net.kyori.adventure.text.Component spyMsg = plugin.getTextService().parse(
                                    TeamMessages.raw(plugin, "misc-teammsg-spy-format"),
                                    java.util.Map.of("%team%", team.getTag(), "%player%", senderName, "%message%", msg)
                            );
                            for (org.bukkit.entity.Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                                if (chatManager.isSpying(onlinePlayer.getUniqueId()) && !team.hasMember(onlinePlayer.getUniqueId())) {
                                    plugin.getScheduler().runEntity(onlinePlayer, () -> {
                                        if (onlinePlayer.isOnline()) onlinePlayer.sendMessage(spyMsg);
                                    });
                                }
                            }
                        }
                    }
                }
            }
            case ALLY_CHAT_SYNC -> {
                if (!teamsManager.isAllyEnabled()) return;
                java.util.UUID teamUuid = packet.getTargetUuid();
                String senderName = packet.getTargetName();
                String extra = packet.getExtra();
                if (teamUuid != null && extra != null && extra.contains("\u0001")) {
                    String[] parts = extra.split("\u0001", 3);
                    if (parts.length >= 3) {
                        String teamTag = parts[0];
                        String roleName = parts[1];
                        String msg = parts[2];
                        java.util.Optional<dev.lyy.friendlycore.modules.teams.model.Team> teamOpt = teamsManager.getTeamByUuid(teamUuid);
                        if (teamOpt.isPresent()) {
                            dev.lyy.friendlycore.modules.teams.model.Team team = teamOpt.get();
                            net.kyori.adventure.text.Component formatted = plugin.getTextService().parse(
                                    TeamMessages.raw(plugin, "misc-allymsg-format"),
                                    java.util.Map.of("%team%", teamTag, "%role%", roleName, "%player%", senderName, "%message%", msg)
                            );
                            for (dev.lyy.friendlycore.modules.teams.model.TeamMember m : team.getMemberList()) {
                                org.bukkit.entity.Player p = Bukkit.getPlayer(m.getUniqueId());
                                if (p != null && p.isOnline()) {
                                    plugin.getScheduler().runEntity(p, () -> {
                                        if (p.isOnline()) p.sendMessage(formatted);
                                    });
                                }
                            }
                            for (java.util.UUID allyTeamUuid : team.getAllies()) {
                                java.util.Optional<dev.lyy.friendlycore.modules.teams.model.Team> allyTeamOpt = teamsManager.getTeamByUuid(allyTeamUuid);
                                if (allyTeamOpt.isPresent()) {
                                    for (dev.lyy.friendlycore.modules.teams.model.TeamMember m : allyTeamOpt.get().getMemberList()) {
                                        org.bukkit.entity.Player p = Bukkit.getPlayer(m.getUniqueId());
                                        if (p != null && p.isOnline()) {
                                            plugin.getScheduler().runEntity(p, () -> {
                                                if (p.isOnline()) p.sendMessage(formatted);
                                            });
                                        }
                                    }
                                }
                            }
                            if (chatManager != null) {
                                net.kyori.adventure.text.Component spyMsg = plugin.getTextService().parse(
                                        TeamMessages.raw(plugin, "misc-allymsg-spy-format"),
                                        java.util.Map.of("%team%", teamTag, "%player%", senderName, "%message%", msg)
                                );
                                for (org.bukkit.entity.Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                                    if (chatManager.isSpying(onlinePlayer.getUniqueId()) && !team.hasMember(onlinePlayer.getUniqueId())) {
                                        plugin.getScheduler().runEntity(onlinePlayer, () -> {
                                            if (onlinePlayer.isOnline()) onlinePlayer.sendMessage(spyMsg);
                                        });
                                    }
                                }
                            }
                        }
                    }
                }
            }
            case TEAM_ENDERCHEST_SYNC -> {
                // Vaults load from storage under exclusive ownership. Delayed Redis
                // snapshots must never overwrite an inventory being used locally.
            }
            case TEAM_HOME_SYNC -> {
                java.util.UUID teamUuid = packet.getTargetUuid();
                String homesData = packet.getExtra();
                if (teamUuid != null) {
                    java.util.Optional<dev.lyy.friendlycore.modules.teams.model.Team> teamOpt = teamsManager.getTeamByUuid(teamUuid);
                    if (teamOpt.isPresent()) {
                        dev.lyy.friendlycore.modules.teams.model.Team team = teamOpt.get();
                        team.getHomes().clear();
                        if (homesData != null && !homesData.isBlank()) {
                            String[] homeEntries = homesData.contains("||") ? homesData.split("\\|\\|") : new String[]{homesData};
                            for (String hEntry : homeEntries) {
                                dev.lyy.friendlycore.modules.teams.model.TeamHome home = dev.lyy.friendlycore.modules.teams.model.TeamHome.parse(hEntry);
                                if (home != null) {
                                    team.getHomes().put(home.getName().toLowerCase(java.util.Locale.ROOT), home);
                                }
                            }
                        }
                        plugin.getLogger().info("[Teams Cross-Server] Synced Homes (" + team.getHomes().size() + ") for team '" + team.getTag() + "' (" + teamUuid + ") from " + packet.getSourceServer());
                    }
                }
            }
        }
    }
}
