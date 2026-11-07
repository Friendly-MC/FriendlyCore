package dev.lyy.friendlycore.modules.teams.command;

import dev.lyy.friendlycore.modules.teams.TeamMessages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.teams.gui.TeamsGuiManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsChatManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsLeaderboardManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsTeleportManager;
import dev.lyy.friendlycore.modules.teams.model.*;
import dev.lyy.friendlycore.modules.orders.input.OrderInputManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Primary user command executor for team creation, membership, economy, homes, and settings.
 */
public class TeamCommand implements CommandExecutor {

    private final FriendlyCore plugin;
    private final TeamsManager teamsManager;
    private final TeamsGuiManager guiManager;
    private final TeamsTeleportManager teleportManager;
    private final TeamsChatManager chatManager;
    private final TeamsLeaderboardManager leaderboardManager;
    private final OrderInputManager inputManager;

    public TeamCommand(FriendlyCore plugin, TeamsManager teamsManager, TeamsGuiManager guiManager,
                       TeamsTeleportManager teleportManager, TeamsChatManager chatManager, TeamsLeaderboardManager leaderboardManager,
                       OrderInputManager inputManager) {
        this.plugin = plugin;
        this.teamsManager = teamsManager;
        this.guiManager = guiManager;
        this.teleportManager = teleportManager;
        this.chatManager = chatManager;
        this.leaderboardManager = leaderboardManager;
        this.inputManager = inputManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            TeamMessages.send(plugin, sender, "this-command-can-only-be-executed-by");
            return true;
        }

        if (!teamsManager.requireReady(sender)) return true;

        if (args.length == 0) {
            guiManager.openMainMenu(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "gui", "menu" -> guiManager.openMainMenu(player);

            case "create" -> handleCreate(player, args);
            case "rename" -> handleRename(player, args);
            case "disband", "delete" -> handleDisband(player);
            case "leave", "quit" -> handleLeave(player);

            case "invite" -> handleInvite(player, args);
            case "accept" -> handleAccept(player, args);
            case "deny" -> handleDeny(player, args);
            case "mailbox", "invites" -> guiManager.openMailboxMenu(player, 1);

            case "kick" -> handleKick(player, args);
            case "promote" -> handlePromote(player, args);
            case "demote" -> handleDemote(player, args);

            case "info", "who", "show" -> handleInfo(player, args);
            case "stats" -> handleStats(player, args);
            case "leaderboard", "top" -> guiManager.openLeaderboardMenu(player, 1, "score");

            case "members", "list" -> guiManager.openMembersMenu(player, 1);
            case "allies" -> handleAllies(player, args);
            case "ally" -> handleAllySubcommand(player, args);

            case "home" -> handleHome(player, args);
            case "sethome" -> handleSetHome(player, args);
            case "delhome", "deletehome", "removehome" -> handleDelHome(player, args);
            case "homes" -> guiManager.openHomesMenu(player, 1);

            case "bank" -> {
                if (!teamsManager.isBankEnabled()) {
                    TeamMessages.send(plugin, player, "the-team-bank-feature-is-currently-disabled");
                    return true;
                }
                if (args.length > 1) {
                    String bankSub = args[1].toLowerCase();
                    if (bankSub.equals("deposit") && args.length > 2) {
                        handleDeposit(player, new String[]{args[0], args[2]});
                        return true;
                    } else if (bankSub.equals("withdraw") && args.length > 2) {
                        handleWithdraw(player, new String[]{args[0], args[2]});
                        return true;
                    }
                }
                guiManager.openBankMenu(player);
            }
            case "deposit" -> {
                if (!teamsManager.isBankEnabled()) {
                    TeamMessages.send(plugin, player, "the-team-bank-feature-is-currently-disabled");
                    return true;
                }
                handleDeposit(player, args);
            }
            case "withdraw" -> {
                if (!teamsManager.isBankEnabled()) {
                    TeamMessages.send(plugin, player, "the-team-bank-feature-is-currently-disabled");
                    return true;
                }
                handleWithdraw(player, args);
            }
            case "enderchest", "ec", "vault", "chest" -> {
                if (!teamsManager.isEnderchestEnabled()) {
                    TeamMessages.send(plugin, player, "the-team-enderchest-feature-is-currently-disabled");
                    return true;
                }
                guiManager.openEnderChest(player);
            }

            case "color", "colors" -> {
                if (args.length > 1) {
                    Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
                    if (teamOpt.isPresent()) {
                        if (teamsManager.getColor(args[1]) == null) {
                            TeamMessages.send(plugin, player, "unknown-team-color-use-a-configured-color");
                            return true;
                        }
                        if (teamsManager.changeTeamColor(teamOpt.get(), player, args[1])) {
                            TeamMessages.send(plugin, player, "team-color-set-to", "%input%", args[1]);
                        } else {
                            TeamMessages.send(plugin, player, "you-do-not-have-permission-to-use");
                        }
                    }
                } else {
                    guiManager.openColorMenu(player, 1);
                }
            }

            case "settings" -> guiManager.openSettingsMenu(player);

            case "chat", "c" -> {
                if (args.length > 1) {
                    Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
                    if (teamOpt.isPresent()) {
                        String msg = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                        chatManager.sendTeamChat(player, teamOpt.get(), msg);
                    } else {
                        TeamMessages.send(plugin, player, "error-no-team");
                    }
                } else {
                    TeamsChatManager.ChatChannel current = chatManager.getPlayerChannel(player.getUniqueId());
                    TeamsChatManager.ChatChannel next = current == TeamsChatManager.ChatChannel.TEAM ? TeamsChatManager.ChatChannel.PUBLIC : TeamsChatManager.ChatChannel.TEAM;
                    chatManager.setPlayerChannel(player.getUniqueId(), next);
                    TeamMessages.send(plugin, player, next == TeamsChatManager.ChatChannel.TEAM ? "info-teammsg-toggled" : "info-teammsg-untoggled");
                }
            }

            case "allychat", "ac" -> {
                if (args.length > 1) {
                    Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
                    if (teamOpt.isPresent()) {
                        String msg = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                        chatManager.sendAllyChat(player, teamOpt.get(), msg);
                    } else {
                        TeamMessages.send(plugin, player, "error-no-team");
                    }
                } else {
                    TeamsChatManager.ChatChannel current = chatManager.getPlayerChannel(player.getUniqueId());
                    TeamsChatManager.ChatChannel next = current == TeamsChatManager.ChatChannel.ALLY ? TeamsChatManager.ChatChannel.PUBLIC : TeamsChatManager.ChatChannel.ALLY;
                    chatManager.setPlayerChannel(player.getUniqueId(), next);
                    TeamMessages.send(plugin, player, next == TeamsChatManager.ChatChannel.ALLY ? "info-allymsg-toggled" : "info-allymsg-untoggled");
                }
            }

            case "help" -> sendHelp(player);

            default -> TeamMessages.send(plugin, player, "error-invalid-command");
        }

        return true;
    }

    private void handleCreate(Player player, String[] args) {
        if (args.length < 2) {
            TeamMessages.send(plugin, player, "use-team-create");
            return;
        }

        if (teamsManager.getTeamByPlayer(player.getUniqueId()).isPresent()) {
            TeamMessages.send(plugin, player, "error-already-team");
            return;
        }

        if (!teamsManager.canCreateTeam(player)) {
            long remaining = teamsManager.getDisbandCooldownRemaining(player);
            TeamMessages.send(plugin, player, "you-must-wait-s-before-creating-another", "%remaining%", String.valueOf(remaining));
            return;
        }

        String tag = args[1];
        if (!teamsManager.isTagValid(tag)) {
            TeamMessages.send(plugin, player, "invalid-tag-tag-must-be-alphanumeric-and", "%tag_max_length%", String.valueOf(teamsManager.getTagMaxLength()));
            return;
        }

        if (teamsManager.getTeamByTag(tag).isPresent()) {
            TeamMessages.send(plugin, player, "error-tag-already-exists", "%team_tag%", tag);
            return;
        }

        Team team = teamsManager.createTeam(player, tag, tag);
        if (team != null) {
            TeamMessages.send(plugin, player, "info-create-team", "%team_tag%", tag);
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        }
    }

    private void handleRename(Player player, String[] args) {
        Optional<Team> team = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (team.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }
        if (!teamsManager.canRenameTeam(team.get(), player)) {
            TeamMessages.send(plugin, player, "you-do-not-have-permission-to-rename");
            return;
        }
        String initial = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : team.get().getName();
        inputManager.openTextInput(player, "Rename Team", "Team name (hex and formatting with permission)", initial,
                TeamNameFormat.MAX_INPUT_LENGTH, name -> {
                    if (teamsManager.renameTeam(team.get(), player, name)) {
                        TeamMessages.send(plugin, player, "team-name-updated");
                    } else {
                        TeamMessages.send(plugin, player, "cannot-rename-this-team-check-your-permissions", "%name_max_length%", String.valueOf(teamsManager.getNameMaxLength()));
                    }
                }, () -> TeamMessages.send(plugin, player, "team-rename-cancelled"));
    }

    private void handleDisband(Player player) {
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        if (!team.isOwner(player.getUniqueId())) {
            TeamMessages.send(plugin, player, "error-not-owner");
            return;
        }

        guiManager.openConfirmMenu(player, "Disband Team?", () -> {
            teamsManager.disbandTeam(team, player);
            player.closeInventory();
        }, player::closeInventory);
    }

    private void handleLeave(Player player) {
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        if (team.isOwner(player.getUniqueId())) {
            TeamMessages.send(plugin, player, "you-are-the-leader-disband-the-team");
            return;
        }

        guiManager.openConfirmMenu(player, "Leave Team?", () -> {
            if (!teamsManager.removeMember(team, player, player.getUniqueId())) {
                player.closeInventory();
                return;
            }
            TeamMessages.send(plugin, player, "info-left-team", "%team_tag%", team.getTag());
            player.playSound(player.getLocation(), Sound.ENTITY_HORSE_SADDLE, 1.0f, 1.0f);
            player.closeInventory();
        }, player::closeInventory);
    }

    private void handleInvite(Player player, String[] args) {
        if (args.length < 2) {
            TeamMessages.send(plugin, player, "use-team-invite");
            return;
        }

        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        TeamMember actorMember = team.getMember(player.getUniqueId());
        if (actorMember == null || !actorMember.hasPermission(RolePermission.INVITE)) {
            TeamMessages.send(plugin, player, "you-do-not-have-permission-to-invite");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null || !target.isOnline()) {
            TeamMessages.send(plugin, player, "error-invalid-player");
            return;
        }

        if (teamsManager.getTeamByPlayer(target.getUniqueId()).isPresent()) {
            TeamMessages.send(plugin, player, "that-player-is-already-in-a-team");
            return;
        }

        if (!teamsManager.sendPlayerInvite(player, team, target)) {
            TeamMessages.send(plugin, player, "the-team-invite-could-not-be-sent");
            return;
        }

        TeamMessages.send(plugin, player, "info-invite-sent", "%player%", target.getName());
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);

    }

    private void handleAccept(Player player, String[] args) {
        TeamMailbox mailbox = teamsManager.getOrCreateMailbox(player.getUniqueId());
        if (!mailbox.hasInvites()) {
            TeamMessages.send(plugin, player, "error-no-such-invite");
            return;
        }

        List<TeamInvite> invites = mailbox.getInvites();
        TeamInvite toAccept = null;
        if (args.length > 1) {
            String tag = args[1];
            for (TeamInvite inv : invites) {
                if (inv.getTeamTag().equalsIgnoreCase(tag)) {
                    toAccept = inv;
                    break;
                }
            }
        } else {
            toAccept = invites.get(0);
        }

        if (toAccept == null) {
            TeamMessages.send(plugin, player, "invite-from-team-not-found");
            return;
        }

        if (toAccept.getType() == TeamInvite.InviteType.PLAYER) {
            Optional<Team> teamOpt = teamsManager.getTeamByUuid(toAccept.getTeamUuid());
            if (teamOpt.isPresent() && teamsManager.addMember(teamOpt.get(), player)) {
                mailbox.removeInvite(toAccept.getTeamUuid(), toAccept.getType());
                TeamMessages.send(plugin, player, "joined-team", "%team_tag%", toAccept.getTeamTag());
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);
            } else {
                TeamMessages.send(plugin, player, "failed-joining-team-or-team-no-longer");
            }
        } else {
            Optional<Team> myTeam = teamsManager.getTeamByPlayer(player.getUniqueId());
            if (myTeam.isPresent()) {
                if (teamsManager.acceptAllyInvite(myTeam.get(), player, toAccept.getTeamUuid())) {
                    TeamMessages.send(plugin, player, "formed-alliance-with-team", "%team_tag%", toAccept.getTeamTag());
                } else {
                    mailbox.removeInvite(toAccept.getTeamUuid(), toAccept.getType());
                    TeamMessages.send(plugin, player, "failed-forming-alliance-limit-reached-or-team");
                }
            } else {
                TeamMessages.send(plugin, player, "you-must-be-in-a-team-to");
            }
        }
    }

    private void handleDeny(Player player, String[] args) {
        TeamMailbox mailbox = teamsManager.getOrCreateMailbox(player.getUniqueId());
        if (!mailbox.hasInvites()) {
            TeamMessages.send(plugin, player, "error-no-such-invite");
            return;
        }

        List<TeamInvite> invites = mailbox.getInvites();
        TeamInvite toDeny = null;
        if (args.length > 1) {
            String tag = args[1];
            for (TeamInvite inv : invites) {
                if (inv.getTeamTag().equalsIgnoreCase(tag)) {
                    toDeny = inv;
                    break;
                }
            }
        } else {
            toDeny = invites.get(0);
        }

        if (toDeny != null) {
            mailbox.removeInvite(toDeny.getTeamUuid(), toDeny.getType());
            TeamMessages.send(plugin, player, "declined-invite-from", "%team_tag%", toDeny.getTeamTag());
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
        }
    }

    private void handleKick(Player player, String[] args) {
        if (args.length < 2) {
            TeamMessages.send(plugin, player, "use-team-kick");
            return;
        }

        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        TeamMember actor = team.getMember(player.getUniqueId());
        if (actor == null || !actor.hasPermission(RolePermission.KICK)) {
            TeamMessages.send(plugin, player, "you-do-not-have-permission-to-kick");
            return;
        }

        // Resolve from the roster: Bukkit.getOfflinePlayer(name) can block the tick on a Mojang lookup.
        TeamMember target = team.getMemberByName(args[1]);
        if (target == null) {
            TeamMessages.send(plugin, player, "info-not-in-your-team");
            return;
        }

        if (teamsManager.removeMember(team, player, target.getUniqueId())) {
            TeamMessages.send(plugin, player, "info-kicked-him", "%player%", target.getUsername());
        }
    }

    private void handlePromote(Player player, String[] args) {
        if (args.length < 2) {
            TeamMessages.send(plugin, player, "use-team-promote");
            return;
        }

        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        TeamMember actor = team.getMember(player.getUniqueId());
        if (actor == null || !actor.hasPermission(RolePermission.PROMOTE)) {
            TeamMessages.send(plugin, player, "you-do-not-have-permission-to-promote");
            return;
        }

        TeamMember target = team.getMemberByName(args[1]);
        if (target == null) {
            TeamMessages.send(plugin, player, "info-not-in-your-team");
            return;
        }
        if (teamsManager.promoteMember(team, player, target.getUniqueId())) {
            TeamMessages.send(plugin, player, "member-promoted", "%player%", target.getUsername());
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 2.0f);
        } else {
            TeamMessages.send(plugin, player, "error-cant-promote");
        }
    }

    private void handleDemote(Player player, String[] args) {
        if (args.length < 2) {
            TeamMessages.send(plugin, player, "use-team-demote");
            return;
        }

        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        TeamMember actor = team.getMember(player.getUniqueId());
        if (actor == null || !actor.hasPermission(RolePermission.DEMOTE)) {
            TeamMessages.send(plugin, player, "you-do-not-have-permission-to-demote");
            return;
        }

        TeamMember target = team.getMemberByName(args[1]);
        if (target == null) {
            TeamMessages.send(plugin, player, "info-not-in-your-team");
            return;
        }
        if (teamsManager.demoteMember(team, player, target.getUniqueId())) {
            TeamMessages.send(plugin, player, "member-demoted", "%player%", target.getUsername());
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_HURT, 1.0f, 2.0f);
        } else {
            TeamMessages.send(plugin, player, "error-cant-demote");
        }
    }

    private void handleInfo(Player player, String[] args) {
        Team team = null;
        if (args.length > 1) {
            Optional<Team> byTag = teamsManager.getTeamByTag(args[1]);
            if (byTag.isPresent()) team = byTag.get();
        } else {
            Optional<Team> myTeam = teamsManager.getTeamByPlayer(player.getUniqueId());
            if (myTeam.isPresent()) team = myTeam.get();
        }

        if (team == null) {
            TeamMessages.send(plugin, player, "error-team-not-found");
            return;
        }

        TeamMessages.send(plugin, player, "info-profile-header", "%team_display_name%", team.getName(), "%team_tag%", team.getTag());
        TeamMessages.send(plugin, player, "info-profile-leader", "%player%", String.valueOf(team.getOwner().getUsername()));
        TeamMessages.send(plugin, player, "info-profile-members", "%member_count%", String.valueOf(team.getMemberCount()), "%members%", String.join(", ", team.getMemberList().stream().map(TeamMember::getUsername).toList()));
        TeamMessages.send(plugin, player, "admin-inspect-bank-balance", "%bank_balance%", String.valueOf(plugin.getNumberFormatter().format(team.getBankBalance())));
        TeamMessages.send(plugin, player, "info-profile-score", "%team_score%", String.valueOf(team.getScore()), "%team_rank%", String.valueOf(team.getRank()));
    }

    private void handleStats(Player player, String[] args) {
        if (args.length > 1) {
            guiManager.openPlayerStatsMenu(player, args[1]);
        } else {
            guiManager.openStatsMenu(player);
        }
    }

    private void handleAllies(Player player, String[] args) {
        guiManager.openAlliesMenu(player, 1);
    }

    private void handleAllySubcommand(Player player, String[] args) {
        if (args.length < 3) {
            TeamMessages.send(plugin, player, "use-team-ally");
            return;
        }

        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        String action = args[1].toLowerCase();
        String targetTag = args[2];
        Optional<Team> targetTeamOpt = teamsManager.getTeamByTag(targetTag);

        if (targetTeamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "target-team-not-found");
            return;
        }

        Team targetTeam = targetTeamOpt.get();
        if (action.equals("add")) {
            if (!teamsManager.sendAllyInvite(player, team, targetTeam)) {
                TeamMessages.send(plugin, player, "the-ally-request-could-not-be-sent");
                return;
            }
            TeamMessages.send(plugin, player, "ally-request-sent-to", "%team_tag%", targetTeam.getTag());
        } else if (action.equals("remove")) {
            if (teamsManager.removeAlly(team, player, targetTeam.getUniqueId())) {
                TeamMessages.send(plugin, player, "alliance-broken-with", "%team_tag%", targetTeam.getTag());
            }
        }
    }

    private void handleHome(Player player, String[] args) {
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        if (!teamsManager.canUseHome(team, player, true)) {
            return;
        }

        String homeName = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : Team.DEFAULT_HOME;
        TeamHome home = team.getHome(homeName);
        if (home == null) {
            TeamMessages.send(plugin, player, "team-home-is-not-set", "%home%", homeName);
            return;
        }

        teleportManager.startTeleport(player, home);
    }

    private void handleSetHome(Player player, String[] args) {
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        String homeName = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : Team.DEFAULT_HOME;
        if (teamsManager.setTeamHome(team, player, homeName, player.getLocation())) {
            TeamMessages.send(plugin, player, "info-set-home", "%home%", homeName);
            player.playSound(player.getLocation(), Sound.UI_LOOM_TAKE_RESULT, 1.0f, 1.0f);
        }
    }

    private void handleDelHome(Player player, String[] args) {
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        String homeName = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : Team.DEFAULT_HOME;
        if (team.getHome(homeName) == null) {
            TeamMessages.send(plugin, player, "error-home-not-found", "%home%", homeName);
            return;
        }

        if (teamsManager.deleteTeamHome(team, player, homeName)) {
            TeamMessages.send(plugin, player, "info-home-deleted", "%home%", homeName);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
        }
    }

    private void handleDeposit(Player player, String[] args) {
        if (args.length < 2) {
            TeamMessages.send(plugin, player, "use-team-deposit");
            return;
        }

        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        if (plugin.getVaultEconomy() == null) {
            TeamMessages.send(plugin, player, "economy-is-not-enabled");
            return;
        }

        double amount;
        try {
            amount = dev.lyy.friendlycore.common.NumberFormatter.parseAmount(args[1]);
        } catch (Exception e) {
            TeamMessages.send(plugin, player, "invalid-money-amount", "%input%", args[1]);
            return;
        }

        if (amount <= 0) {
            TeamMessages.send(plugin, player, "amount-must-be-greater-than-0");
            return;
        }

        if (!plugin.getVaultEconomy().has(player, amount)) {
            TeamMessages.send(plugin, player, "error-not-enough-money");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        teamsManager.depositFromPlayer(player, team, amount).thenAccept(result -> plugin.getScheduler().runEntity(player, () -> {
            if (!result.success()) {
                TeamMessages.send(plugin, player, result.status() == TeamsManager.BankTransactionStatus.LIMIT_EXCEEDED ? "bank-limit-exceeded" : "bank-deposit-failed");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }
            TeamMessages.send(plugin, player, "bank-deposited", "%amount%", plugin.getNumberFormatter().format(amount), "%bank%", plugin.getNumberFormatter().format(result.balance()));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }));
    }

    private void handleWithdraw(Player player, String[] args) {
        if (args.length < 2) {
            TeamMessages.send(plugin, player, "use-team-withdraw");
            return;
        }

        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        if (!team.hasMember(player.getUniqueId()) || !team.getMember(player.getUniqueId()).hasPermission(RolePermission.ACCESS_BANK)) {
            TeamMessages.send(plugin, player, "you-do-not-have-permission-to-withdraw");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        if (plugin.getVaultEconomy() == null) {
            TeamMessages.send(plugin, player, "economy-is-not-enabled");
            return;
        }

        double amount;
        try {
            amount = dev.lyy.friendlycore.common.NumberFormatter.parseAmount(args[1]);
        } catch (Exception e) {
            TeamMessages.send(plugin, player, "invalid-money-amount", "%input%", args[1]);
            return;
        }

        if (amount <= 0) {
            TeamMessages.send(plugin, player, "amount-must-be-greater-than-0");
            return;
        }

        teamsManager.withdrawToPlayer(player, team, amount).thenAccept(result -> plugin.getScheduler().runEntity(player, () -> {
            if (result.success()) {
            TeamMessages.send(plugin, player, "bank-withdrew", "%amount%", plugin.getNumberFormatter().format(amount), "%bank%", plugin.getNumberFormatter().format(result.balance()));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
            } else {
                TeamMessages.send(plugin, player, result.status() == TeamsManager.BankTransactionStatus.INSUFFICIENT_FUNDS ? "bank-insufficient-funds" : "bank-withdraw-failed");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            }
        }));
    }

    private void sendHelp(Player player) {
        TeamMessages.send(plugin, player, "friendlyteams-command-guide");
        TeamMessages.send(plugin, player, "team-open-team-overview-gui");
        TeamMessages.send(plugin, player, "team-create-create-a-new-team");
        TeamMessages.send(plugin, player, "team-rename-open-the-team-name-editor");
        TeamMessages.send(plugin, player, "team-disband-disband-your-team");
        TeamMessages.send(plugin, player, "team-leave-leave-your-current-team");
        TeamMessages.send(plugin, player, "team-invite-invite-player-to-team");
        TeamMessages.send(plugin, player, "team-accept-accept-team-invitation");
        TeamMessages.send(plugin, player, "team-deny-decline-team-invitation");
        TeamMessages.send(plugin, player, "team-mailbox-open-invites-mailbox");
        TeamMessages.send(plugin, player, "team-kick-remove-member-from-team");
        TeamMessages.send(plugin, player, "team-promote-demote-manage-team-roles");
        TeamMessages.send(plugin, player, "team-home-name-teleport-to-team-home");
        TeamMessages.send(plugin, player, "team-sethome-name-set-a-team-home");
        TeamMessages.send(plugin, player, "team-bank-open-team-bank-gui");
        TeamMessages.send(plugin, player, "team-deposit-deposit-into-team-bank");
        TeamMessages.send(plugin, player, "team-withdraw-withdraw-from-team-bank");
        TeamMessages.send(plugin, player, "team-ec-open-shared-enderchest");
        TeamMessages.send(plugin, player, "team-top-view-team-leaderboards");
        TeamMessages.send(plugin, player, "team-info-tag-view-team-profile");
    }
}
