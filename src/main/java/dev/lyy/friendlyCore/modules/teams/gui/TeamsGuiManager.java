package dev.lyy.friendlycore.modules.teams.gui;

import dev.lyy.friendlycore.modules.teams.TeamMessages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.PlayerLookup;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.teams.manager.TeamsChatManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsLeaderboardManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsTeleportManager;
import dev.lyy.friendlycore.modules.teams.model.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all inventory GUI menus for the Teams module.
 * <p>
 * Handles disposition parsing, paginated content rendering, click actions,
 * interactive chat prompt handoffs, and vault lease synchronization.
 */
public class TeamsGuiManager {

    private final FriendlyCore plugin;
    private final TeamsManager teamsManager;
    private final TeamsLeaderboardManager leaderboardManager;
    private final TeamsTeleportManager teleportManager;
    private final TeamsChatManager chatManager;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    private final Map<String, TeamMenuParser.ParsedLayout> layoutCache = new ConcurrentHashMap<>();

    public TeamsGuiManager(FriendlyCore plugin, TeamsManager teamsManager, TeamsLeaderboardManager leaderboardManager,
                           TeamsTeleportManager teleportManager, TeamsChatManager chatManager) {
        this.plugin = plugin;
        this.teamsManager = teamsManager;
        this.leaderboardManager = leaderboardManager;
        this.teleportManager = teleportManager;
        this.chatManager = chatManager;
    }

    public void initialize() {
        layoutCache.clear();
    }

    public void shutdown() {
        layoutCache.clear();
    }

    public void clearLayoutCache() {
        layoutCache.clear();
    }

    private FileConfiguration getMenuConfig(String name) {
        return plugin.getConfigService().get("modules/teams/inventories/" + name);
    }

    public TeamMenuParser.ParsedLayout getLayout(String name, boolean useAlternativeDisposition) {
        String cacheKey = name + ":" + useAlternativeDisposition;
        return layoutCache.computeIfAbsent(cacheKey, k -> {
            FileConfiguration config = getMenuConfig(name);
            return TeamMenuParser.parse(config, useAlternativeDisposition);
        });
    }

    public void openMainMenu(Player player) {
        if (player == null || !player.isOnline() || !teamsManager.requireReady(player)) return;

        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        boolean hasTeam = teamOpt.isPresent();
        FileConfiguration config = getMenuConfig("team-gui.yml");
        TeamMenuParser.ParsedLayout layout = getLayout("team-gui.yml", !hasTeam);

        Map<String, String> placeholders = new HashMap<>();
        if (hasTeam) {
            Team team = teamOpt.get();
            placeholders.put("%team_name%", team.getName());
            placeholders.put("%team_tag%", team.getTag());
            placeholders.put("%team_color%", team.getColorKey());
            placeholders.put("%title%", config.getString("values.leave-team-title", "Leave Team"));
            placeholders.put("%money%", plugin.getNumberFormatter().formatShort(team.getBankBalance()));
            placeholders.put("%money_short%", plugin.getNumberFormatter().formatShort(team.getBankBalance()));
            placeholders.put("%money_formatted%", plugin.getNumberFormatter().format(team.getBankBalance()));
            placeholders.put("%money_exact%", plugin.getNumberFormatter().formatExact(team.getBankBalance()));
            placeholders.put("%team_bank%", plugin.getNumberFormatter().formatShort(team.getBankBalance()));
            placeholders.put("%team_bank_short%", plugin.getNumberFormatter().formatShort(team.getBankBalance()));
            placeholders.put("%team_bank_formatted%", plugin.getNumberFormatter().format(team.getBankBalance()));
            placeholders.put("%team_bank_exact%", plugin.getNumberFormatter().formatExact(team.getBankBalance()));
            placeholders.put("%balance%", plugin.getNumberFormatter().formatShort(team.getBankBalance()));
            placeholders.put("%balance_short%", plugin.getNumberFormatter().formatShort(team.getBankBalance()));
            placeholders.put("%balance_formatted%", plugin.getNumberFormatter().format(team.getBankBalance()));
            placeholders.put("%balance_exact%", plugin.getNumberFormatter().formatExact(team.getBankBalance()));
        } else {
            placeholders.put("%title%", config.getString("values.create-team-title", "Create Team"));
        }

        Component titleComp = plugin.getTextService().parseForPlayer(player, layout.getTitle(), placeholders);
        PaginatedGui gui = new PaginatedGui(plugin, player, titleComp, layout.getRows());

        for (Map.Entry<Integer, TeamMenuParser.GuiItemConfig> entry : layout.getSlotMap().entrySet()) {
            int slot = entry.getKey();
            TeamMenuParser.GuiItemConfig cfg = entry.getValue();

            ItemStack stack;

            if (cfg.getSymbol() == 'c') {
                if (hasTeam) {
                    Material leaveMat = Material.matchMaterial(config.getString("values.leave-team-material", "RED_STAINED_GLASS_PANE"));
                    if (leaveMat == null) leaveMat = Material.RED_STAINED_GLASS_PANE;
                    String leaveTitle = config.getString("values.leave-team-title", "&#F53737Leave Team");
                    List<String> leaveLore = config.getStringList("values.leave-team-lore");

                    stack = new ItemBuilder(leaveMat)
                            .name(plugin.getTextService().parseForPlayer(player, leaveTitle, placeholders))
                            .lore(plugin.getTextService().parseListForPlayer(player, leaveLore, placeholders))
                            .build();
                } else {
                    Material createMat = Material.matchMaterial(config.getString("values.create-team-material", "LIME_STAINED_GLASS_PANE"));
                    if (createMat == null) createMat = Material.LIME_STAINED_GLASS_PANE;
                    String createTitle = config.getString("values.create-team-title", "&#87EA7CCreate Team");
                    List<String> createLore = config.getStringList("values.create-team-lore");

                    stack = new ItemBuilder(createMat)
                            .name(plugin.getTextService().parseForPlayer(player, createTitle, placeholders))
                            .lore(plugin.getTextService().parseListForPlayer(player, createLore, placeholders))
                            .build();
                }
            } else {
                stack = cfg.createItemStack(plugin, player, placeholders);
            }

            GuiItem guiItem = new GuiItem(stack, e -> {
                e.setCancelled(true);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                String action = cfg.getActions().get("left-click");
                if (action != null) {
                    handleMainMenuAction(player, action, teamOpt.orElse(null));
                }
            });
            gui.setItem(slot, guiItem);
        }

        gui.open();
    }

    private void handleMainMenuAction(Player player, String action, Team team) {
        switch (action.toLowerCase(Locale.ROOT)) {
            case "open_settings" -> {
                if (team != null) openSettingsMenu(player);
            }
            case "show_members" -> {
                if (team != null) openMembersMenu(player, 1);
            }
            case "show_team_enderchest" -> {
                if (team != null) openEnderChest(player);
            }
            case "open_home_menu" -> {
                if (team != null) openHomesMenu(player, 1);
            }
            case "open_team_bank" -> {
                if (!teamsManager.isBankEnabled()) {
                    TeamMessages.send(plugin, player, "the-team-bank-feature-is-currently-disabled");
                    return;
                }
                if (team != null) openBankMenu(player);
            }
            case "show_stats" -> {
                if (team != null) openStatsMenu(player);
            }
            case "show_allies" -> {
                if (team != null) openAlliesMenu(player, 1);
            }
            case "show_invites", "open_mailbox", "invites", "mailbox" -> {
                openMailboxMenu(player, 1);
            }
            case "create_or_leave_team" -> {
                if (team != null) {
                    if (team.isOwner(player.getUniqueId())) {
                        openConfirmMenu(player, "Disband Team?", () -> {
                            teamsManager.disbandTeam(team, player);
                            player.closeInventory();
                        }, () -> openMainMenu(player));
                    } else {
                        openConfirmMenu(player, "Leave Team?", () -> {
                            if (teamsManager.removeMember(team, player, player.getUniqueId()))
                                TeamMessages.send(plugin, player, "info-left-team");
                            player.closeInventory();
                        }, () -> openMainMenu(player));
                    }
                } else {
                    player.closeInventory();
                    chatManager.askQuestion(player, TeamMessages.raw(plugin, "prompt-create-team-tag"), tag -> {
                        if (!teamsManager.isTagValid(tag)) {
                            TeamMessages.send(plugin, player, "invalid-tag-or-tag-contains-banned-keywords");
                            return;
                        }
                        if (teamsManager.getTeamByTag(tag).isPresent()) {
                            TeamMessages.send(plugin, player, "error-tag-already-exists");
                            return;
                        }
                        Team created = teamsManager.createTeam(player, tag, tag);
                        if (created != null) {
                            TeamMessages.send(plugin, player, "info-create-team", "%team_tag%", tag);
                            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                        }
                    });
                }
            }
        }
    }

    public void openHomesMenu(Player player, int page) {
        if (player == null || !player.isOnline()) return;
        if (!teamsManager.requireReady(player)) return;
        if (!teamsManager.isHomeEnabled()) {
            TeamMessages.send(plugin, player, "the-team-homes-feature-is-disabled");
            return;
        }
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        FileConfiguration config = getMenuConfig("home-gui.yml");
        TeamMenuParser.ParsedLayout layout = getLayout("home-gui.yml", false);

        List<TeamHome> homes = new ArrayList<>(team.getHomes().values());
        int itemsPerPage = Math.max(1, layout.getContentSlots().size());
        int totalPages = Math.max(1, (int) Math.ceil((double) Math.max(1, homes.size()) / itemsPerPage));
        page = Math.min(totalPages, Math.max(1, page));

        Map<String, String> layoutPlaceholders = Map.of(
                "%page%", String.valueOf(page),
                "%total%", String.valueOf(totalPages),
                "%team_tag%", team.getTag(),
                "%team_name%", team.getName()
        );

        Component titleComp = plugin.getTextService().parseForPlayer(player, layout.getTitle(), layoutPlaceholders);
        PaginatedGui gui = new PaginatedGui(plugin, player, titleComp, layout.getRows());

        for (Map.Entry<Integer, TeamMenuParser.GuiItemConfig> entry : layout.getSlotMap().entrySet()) {
            int slot = entry.getKey();
            TeamMenuParser.GuiItemConfig cfg = entry.getValue();

            ItemStack stack = cfg.createItemStack(plugin, player, layoutPlaceholders);
            int finalPage = page;
            GuiItem guiItem = new GuiItem(stack, e -> {
                e.setCancelled(true);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                String action = cfg.getActions().get("left-click");
                if (action != null) {
                    switch (action.toLowerCase(Locale.ROOT)) {
                        case "go_back" -> openMainMenu(player);
                        case "set_home" -> {
                            player.closeInventory();
                            chatManager.askQuestion(player, TeamMessages.raw(plugin, "prompt-new-home-name"), homeName -> {
                                if (teamsManager.setTeamHome(team, player, homeName, player.getLocation())) {
                                    TeamMessages.send(plugin, player, "info-set-home", "%home%", homeName);
                                    player.playSound(player.getLocation(), Sound.UI_LOOM_TAKE_RESULT, 1.0f, 1.0f);
                                }
                            });
                        }
                        case "to_previous_page" -> {
                            if (finalPage > 1) openHomesMenu(player, finalPage - 1);
                        }
                        case "to_next_page" -> {
                            if (finalPage < totalPages) openHomesMenu(player, finalPage + 1);
                        }
                    }
                }
            });
            gui.setItem(slot, guiItem);
        }

        String homeMatName = config.getString("values.home-item-material", "RED_BED");
        Material homeMat = Material.matchMaterial(homeMatName);
        if (homeMat == null) homeMat = Material.RED_BED;
        String homeNameTemplate = config.getString("values.home-item-name", "&e&l%home%");
        List<String> homeLoreTemplate = config.getStringList("values.home-item-lore");

        for (int i = 0; i < itemsPerPage; i++) {
            if (i >= layout.getContentSlots().size()) break;
            int slot = layout.getContentSlots().get(i);
            int homeIdx = (page - 1) * itemsPerPage + i;

            if (homeIdx < homes.size()) {
                TeamHome home = homes.get(homeIdx);
                Map<String, String> ph = new HashMap<>(layoutPlaceholders);
                ph.put("%home%", home.getName());
                ph.put("%world%", home.getWorldName());
                ph.put("%x%", String.format(Locale.US, "%.1f", home.getX()));
                ph.put("%y%", String.format(Locale.US, "%.1f", home.getY()));
                ph.put("%z%", String.format(Locale.US, "%.1f", home.getZ()));
                ph.put("%server%", home.getServer().isBlank() ? (plugin.getRedisService() != null ? plugin.getRedisService().getServerId() : "local") : home.getServer());

                ItemStack homeStack = new ItemBuilder(homeMat)
                        .name(plugin.getTextService().parseForPlayer(player, homeNameTemplate, ph))
                        .lore(plugin.getTextService().parseListForPlayer(player, homeLoreTemplate, ph))
                        .build();

                int curPage = page;
                gui.setItem(slot, new GuiItem(homeStack, e -> {
                    e.setCancelled(true);
                    if (e.isShiftClick()) {
                        if (teamsManager.deleteTeamHome(team, player, home.getName())) {
                            TeamMessages.send(plugin, player, "info-home-deleted", "%home%", home.getName());
                            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
                            openHomesMenu(player, curPage);
                        }
                    } else if (e.isRightClick()) {
                        player.closeInventory();
                        chatManager.askQuestion(player, TeamMessages.raw(plugin, "prompt-rename-home").replace("%home%", home.getName()), newName -> {
                            if (teamsManager.renameTeamHome(team, player, home.getName(), newName)) {
                                TeamMessages.send(plugin, player, "renamed-team-home-to", "%new_name%", newName);
                                player.playSound(player.getLocation(), Sound.UI_LOOM_TAKE_RESULT, 1.0f, 1.0f);
                            }
                        });
                    } else {
                        if (!teamsManager.canUseHome(team, player, true)) {
                            return;
                        }
                        player.closeInventory();
                        teleportManager.startTeleport(player, home);
                    }
                }));
            }
        }

        gui.open();
    }

    public void openMembersMenu(Player player, int page) {
        if (player == null || !player.isOnline()) return;
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        FileConfiguration config = getMenuConfig("members-gui.yml");
        TeamMenuParser.ParsedLayout layout = getLayout("members-gui.yml", false);

        List<TeamMember> nonOwnerMembers = team.getMemberList().stream()
                .filter(m -> !m.getUniqueId().equals(team.getOwner().getUniqueId()))
                .toList();

        int itemsPerPage = Math.max(1, layout.getContentSlots().size());
        int totalItems = nonOwnerMembers.size() + 1; // +1 for invite button
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / itemsPerPage));
        page = Math.min(totalPages, Math.max(1, page));

        Map<String, String> placeholders = Map.of(
                "%page%", String.valueOf(page),
                "%total%", String.valueOf(totalPages),
                "%team_tag%", team.getTag(),
                "%team_name%", team.getName()
        );

        Component titleComp = plugin.getTextService().parseForPlayer(player, layout.getTitle(), placeholders);
        PaginatedGui gui = new PaginatedGui(plugin, player, titleComp, layout.getRows());

        for (Map.Entry<Integer, TeamMenuParser.GuiItemConfig> entry : layout.getSlotMap().entrySet()) {
            int slot = entry.getKey();
            TeamMenuParser.GuiItemConfig cfg = entry.getValue();

            ItemStack stack;
            if (cfg.getSymbol() == 'o') {
                Map<String, String> ownerPh = new HashMap<>(placeholders);
                ownerPh.put("%player%", team.getOwner().getUsername());
                stack = cfg.createItemStack(plugin, player, ownerPh);
            } else {
                stack = cfg.createItemStack(plugin, player, placeholders);
            }

            int finalPage = page;
            GuiItem guiItem = new GuiItem(stack, e -> {
                e.setCancelled(true);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                String action = cfg.getActions().get("left-click");
                if (action != null) {
                    switch (action.toLowerCase(Locale.ROOT)) {
                        case "go_back" -> openMainMenu(player);
                        case "to_previous_page" -> {
                            if (finalPage > 1) openMembersMenu(player, finalPage - 1);
                        }
                        case "to_next_page" -> {
                            if (finalPage < totalPages) openMembersMenu(player, finalPage + 1);
                        }
                    }
                }
            });
            gui.setItem(slot, guiItem);
        }

        String memberNameTemplate = config.getString("values.member-item-name", "&b%player% &8(&7%role%&8)");
        List<String> memberLoreTemplate = config.getStringList("values.member-item-variant-lore");
        String emptySlotName = config.getString("values.empty-slot-name", "&e♯ Click &f to Invite");
        String emptySlotMatName = config.getString("values.empty-slot-material", "GREEN_STAINED_GLASS_PANE");
        Material emptySlotMat = Material.matchMaterial(emptySlotMatName);
        if (emptySlotMat == null) emptySlotMat = Material.GREEN_STAINED_GLASS_PANE;

        for (int i = 0; i < itemsPerPage; i++) {
            if (i >= layout.getContentSlots().size()) break;
            int slot = layout.getContentSlots().get(i);
            int itemIdx = (page - 1) * itemsPerPage + i;

            if (itemIdx < nonOwnerMembers.size()) {
                TeamMember m = nonOwnerMembers.get(itemIdx);
                Map<String, String> mPh = new HashMap<>(placeholders);
                mPh.put("%player%", m.getUsername());
                mPh.put("%role%", m.getRole() != null ? m.getRole().getName() : "Member");
                mPh.put("%promote_role%", "Higher Rank");
                mPh.put("%demote_role%", "Lower Rank");

                ItemStack memberHead = new ItemBuilder(Material.PLAYER_HEAD)
                        .skullOwner(m.getUniqueId(), m.getUsername())
                        .name(plugin.getTextService().parseForPlayer(player, memberNameTemplate, mPh))
                        .lore(plugin.getTextService().parseListForPlayer(player, memberLoreTemplate, mPh))
                        .build();

                int curPage = page;
                gui.setItem(slot, new GuiItem(memberHead, e -> {
                    e.setCancelled(true);
                    if (e.isShiftClick() && e.isRightClick()) {
                        openConfirmMenu(player, "Kick " + m.getUsername() + "?", () -> {
                            if (teamsManager.removeMember(team, player, m.getUniqueId()))
                                TeamMessages.send(plugin, player, "info-kicked-him", "%player%", m.getUsername());
                            else TeamMessages.send(plugin, player, "you-cannot-manage-this-member");
                            openMembersMenu(player, curPage);
                        }, () -> openMembersMenu(player, curPage));
                    } else if (e.isLeftClick()) {
                        if (teamsManager.promoteMember(team, player, m.getUniqueId())) {
                            TeamMessages.send(plugin, player, "gui-member-promoted", "%player%", m.getUsername());
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);
                            openMembersMenu(player, curPage);
                        } else {
                            TeamMessages.send(plugin, player, "error-cant-promote");
                        }
                    } else if (e.isRightClick()) {
                        if (teamsManager.demoteMember(team, player, m.getUniqueId())) {
                            TeamMessages.send(plugin, player, "gui-member-demoted", "%player%", m.getUsername());
                            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_HURT, 1.0f, 2.0f);
                            openMembersMenu(player, curPage);
                        } else {
                            TeamMessages.send(plugin, player, "error-cant-demote");
                        }
                    }
                }));
            } else if (itemIdx == nonOwnerMembers.size()) {
                ItemStack inviteStack = new ItemBuilder(emptySlotMat)
                        .name(plugin.getTextService().parseForPlayer(player, emptySlotName, placeholders))
                        .build();

                gui.setItem(slot, new GuiItem(inviteStack, e -> {
                    e.setCancelled(true);
                    TeamMember actorMember = team.getMember(player.getUniqueId());
                    if (actorMember == null || !actorMember.hasPermission(RolePermission.INVITE)) {
                        TeamMessages.send(plugin, player, "you-do-not-have-permission-to-invite");
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                        return;
                    }
                    player.closeInventory();
                    chatManager.askQuestion(player, TeamMessages.raw(plugin, "prompt-invite-player"), targetName -> {
                        Player target = Bukkit.getPlayer(targetName);
                        if (target == null || !target.isOnline()) {
                            TeamMessages.send(plugin, player, "error-invalid-player");
                            return;
                        }
                        if (target.getUniqueId().equals(player.getUniqueId()) || team.hasMember(target.getUniqueId())) {
                            TeamMessages.send(plugin, player, "error-already-in-your-team");
                            return;
                        }
                        Optional<Team> targetTeam = teamsManager.getTeamByPlayer(target.getUniqueId());
                        if (targetTeam.isPresent()) {
                            TeamMessages.send(plugin, player, "that-player-is-already-in-a-team-2");
                            return;
                        }
                        if (!teamsManager.sendPlayerInvite(player, team, target)) {
                            TeamMessages.send(plugin, player, "the-team-invite-could-not-be-sent");
                            return;
                        }
                        TeamMessages.send(plugin, player, "info-invite-sent", "%player%", target.getName());
                    });
                }));
            }
        }

        gui.open();
    }

    public void openAlliesMenu(Player player, int page) {
        if (player == null || !player.isOnline()) return;
        if (!teamsManager.requireReady(player)) return;
        if (!teamsManager.isAllyEnabled()) {
            TeamMessages.send(plugin, player, "the-team-alliances-feature-is-disabled");
            return;
        }
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        FileConfiguration config = getMenuConfig("allies-gui.yml");
        TeamMenuParser.ParsedLayout layout = getLayout("allies-gui.yml", false);

        List<UUID> allies = new ArrayList<>(team.getAllies());
        int itemsPerPage = Math.max(1, layout.getContentSlots().size());
        int totalItems = allies.size() + (allies.size() < teamsManager.getMaxAllies() ? 1 : 0);
        int totalPages = Math.max(1, (int) Math.ceil((double) Math.max(1, totalItems) / itemsPerPage));
        page = Math.min(totalPages, Math.max(1, page));

        Map<String, String> placeholders = Map.of(
                "%page%", String.valueOf(page),
                "%total%", String.valueOf(totalPages),
                "%team_tag%", team.getTag(),
                "%team_name%", team.getName()
        );

        Component titleComp = plugin.getTextService().parseForPlayer(player, layout.getTitle(), placeholders);
        PaginatedGui gui = new PaginatedGui(plugin, player, titleComp, layout.getRows());

        for (Map.Entry<Integer, TeamMenuParser.GuiItemConfig> entry : layout.getSlotMap().entrySet()) {
            int slot = entry.getKey();
            TeamMenuParser.GuiItemConfig cfg = entry.getValue();

            ItemStack stack;
            if (cfg.getSymbol() == 'o') {
                Map<String, String> teamPh = new HashMap<>(placeholders);
                teamPh.put("%team%", team.getTag());
                stack = cfg.createItemStack(plugin, player, teamPh);
            } else {
                stack = cfg.createItemStack(plugin, player, placeholders);
            }

            int finalPage = page;
            GuiItem guiItem = new GuiItem(stack, e -> {
                e.setCancelled(true);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                String action = cfg.getActions().get("left-click");
                if (action != null) {
                    switch (action.toLowerCase(Locale.ROOT)) {
                        case "go_back" -> openMainMenu(player);
                        case "to_previous_page" -> {
                            if (finalPage > 1) openAlliesMenu(player, finalPage - 1);
                        }
                        case "to_next_page" -> {
                            if (finalPage < totalPages) openAlliesMenu(player, finalPage + 1);
                        }
                    }
                }
            });
            gui.setItem(slot, guiItem);
        }

        String allyNameTemplate = config.getString("values.ally-item-name", "&b%team%");
        List<String> allyLoreTemplate = config.getStringList("values.ally-item-variant-lore");
        String emptySlotName = config.getString("values.empty-slot-name", "&e♯ Click &f to Invite");
        String emptySlotMatName = config.getString("values.empty-slot-material", "GREEN_STAINED_GLASS_PANE");
        Material emptySlotMat = Material.matchMaterial(emptySlotMatName);
        if (emptySlotMat == null) emptySlotMat = Material.GREEN_STAINED_GLASS_PANE;

        for (int i = 0; i < itemsPerPage; i++) {
            if (i >= layout.getContentSlots().size()) break;
            int slot = layout.getContentSlots().get(i);
            int itemIdx = (page - 1) * itemsPerPage + i;

            if (itemIdx < allies.size()) {
                UUID allyUuid = allies.get(itemIdx);
                Optional<Team> allyTeamOpt = teamsManager.getTeamByUuid(allyUuid);
                if (allyTeamOpt.isPresent()) {
                    Team allyTeam = allyTeamOpt.get();
                    Map<String, String> aPh = new HashMap<>(placeholders);
                    aPh.put("%team%", allyTeam.getTag());

                    ItemStack allyStack = new ItemBuilder(Material.SHIELD)
                            .name(plugin.getTextService().parseForPlayer(player, allyNameTemplate, aPh))
                            .lore(plugin.getTextService().parseListForPlayer(player, allyLoreTemplate, aPh))
                            .build();

                    int curPage = page;
                    gui.setItem(slot, new GuiItem(allyStack, e -> {
                        e.setCancelled(true);
                        openConfirmMenu(player, "Unally " + allyTeam.getTag() + "?", () -> {
                            if (teamsManager.removeAlly(team, player, allyTeam.getUniqueId()))
                                TeamMessages.send(plugin, player, "alliance-broken-with-2", "%team_tag%", allyTeam.getTag());
                            openAlliesMenu(player, curPage);
                        }, () -> openAlliesMenu(player, curPage));
                    }));
                }
            } else if (itemIdx == allies.size() && allies.size() < teamsManager.getMaxAllies()) {
                ItemStack inviteStack = new ItemBuilder(emptySlotMat)
                        .name(plugin.getTextService().parseForPlayer(player, emptySlotName, placeholders))
                        .build();

                gui.setItem(slot, new GuiItem(inviteStack, e -> {
                    e.setCancelled(true);
                    TeamMember actorMember = team.getMember(player.getUniqueId());
                    if (actorMember == null || !actorMember.hasPermission(RolePermission.MANAGE_ALLIANCE)) {
                        TeamMessages.send(plugin, player, "you-do-not-have-permission-to-invite-2");
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                        return;
                    }
                    if (team.getAllies().size() >= teamsManager.getMaxAllies()) {
                        TeamMessages.send(plugin, player, "your-team-has-reached-the-maximum-ally", "%max_allies%", String.valueOf(teamsManager.getMaxAllies()));
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                        return;
                    }
                    player.closeInventory();
                    chatManager.askQuestion(player, TeamMessages.raw(plugin, "prompt-ally-team-tag"), targetTag -> {
                        Optional<Team> targetTeamOpt = teamsManager.getTeamByTag(targetTag);
                        if (targetTeamOpt.isEmpty()) {
                            TeamMessages.send(plugin, player, "error-team-not-found");
                            return;
                        }
                        Team targetTeam = targetTeamOpt.get();
                        if (targetTeam.getUniqueId().equals(team.getUniqueId())) {
                            TeamMessages.send(plugin, player, "you-cannot-ally-with-your-own-team");
                            return;
                        }
                        if (team.isAlly(targetTeam.getUniqueId())) {
                            TeamMessages.send(plugin, player, "error-already-ally");
                            return;
                        }
                        if (targetTeam.getAllies().size() >= teamsManager.getMaxAllies()) {
                            TeamMessages.send(plugin, player, "that-team-has-reached-their-maximum-ally");
                            return;
                        }
                        if (!teamsManager.sendAllyInvite(player, team, targetTeam)) {
                            TeamMessages.send(plugin, player, "the-ally-request-could-not-be-sent");
                            return;
                        }
                        TeamMessages.send(plugin, player, "ally-request-sent-to", "%team_tag%", targetTeam.getTag());
                    });
                }));
            }
        }

        gui.open();
    }

    public void openMailboxMenu(Player player, int page) {
        if (player == null || !player.isOnline()) return;

        TeamMailbox mailbox = teamsManager.getOrCreateMailbox(player.getUniqueId());
        FileConfiguration config = getMenuConfig("mailbox-gui.yml");
        TeamMenuParser.ParsedLayout layout = getLayout("mailbox-gui.yml", false);

        List<TeamInvite> invites = mailbox.getInvites();
        int itemsPerPage = Math.max(1, layout.getContentSlots().size());
        int totalPages = Math.max(1, (int) Math.ceil((double) Math.max(1, invites.size()) / itemsPerPage));
        page = Math.min(totalPages, Math.max(1, page));

        Map<String, String> placeholders = Map.of(
                "%page%", String.valueOf(page),
                "%total%", String.valueOf(totalPages),
                "%player%", player.getName()
        );

        Component titleComp = plugin.getTextService().parseForPlayer(player, layout.getTitle(), placeholders);
        PaginatedGui gui = new PaginatedGui(plugin, player, titleComp, layout.getRows());

        for (Map.Entry<Integer, TeamMenuParser.GuiItemConfig> entry : layout.getSlotMap().entrySet()) {
            int slot = entry.getKey();
            TeamMenuParser.GuiItemConfig cfg = entry.getValue();

            ItemStack stack = cfg.createItemStack(plugin, player, placeholders);
            int finalPage = page;
            GuiItem guiItem = new GuiItem(stack, e -> {
                e.setCancelled(true);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                String action = cfg.getActions().get("left-click");
                if (action != null) {
                    switch (action.toLowerCase(Locale.ROOT)) {
                        case "go_back" -> openMainMenu(player);
                        case "to_previous_page" -> {
                            if (finalPage > 1) openMailboxMenu(player, finalPage - 1);
                        }
                        case "to_next_page" -> {
                            if (finalPage < totalPages) openMailboxMenu(player, finalPage + 1);
                        }
                    }
                }
            });
            gui.setItem(slot, guiItem);
        }

        String teamInviteTitle = config.getString("values.team-invite-title", "&#5CFC66%player% Invited You");
        List<String> teamInviteLore = config.getStringList("values.team-invite-lore");
        String allyInviteTitle = config.getString("values.ally-invite-title", "&#5CFC66%player% Proposed Alliance");
        List<String> allyInviteLore = config.getStringList("values.ally-invite-lore");

        for (int i = 0; i < itemsPerPage; i++) {
            if (i >= layout.getContentSlots().size()) break;
            int slot = layout.getContentSlots().get(i);
            int invIdx = (page - 1) * itemsPerPage + i;

            if (invIdx < invites.size()) {
                TeamInvite inv = invites.get(invIdx);
                Map<String, String> invPh = new HashMap<>(placeholders);
                invPh.put("%player%", inv.getInviterName());
                invPh.put("%team%", inv.getTeamTag());
                invPh.put("%date%", dateFormat.format(new Date(inv.getCreatedAt())));

                boolean isAlly = inv.getType() == TeamInvite.InviteType.ALLY;
                String title = isAlly ? allyInviteTitle : teamInviteTitle;
                List<String> lore = isAlly ? allyInviteLore : teamInviteLore;

                ItemStack inviteStack = new ItemBuilder(Material.PLAYER_HEAD)
                        .skullOwner(inv.getInviterUuid(), inv.getInviterName())
                        .name(plugin.getTextService().parseForPlayer(player, title, invPh))
                        .lore(plugin.getTextService().parseListForPlayer(player, lore, invPh))
                        .build();

                int curPage = page;
                gui.setItem(slot, new GuiItem(inviteStack, e -> {
                    e.setCancelled(true);
                    if (e.isLeftClick()) {
                        if (inv.getType() == TeamInvite.InviteType.PLAYER) {
                            Optional<Team> tOpt = teamsManager.getTeamByUuid(inv.getTeamUuid());
                            if (tOpt.isPresent() && teamsManager.addMember(tOpt.get(), player)) {
                                mailbox.removeInvite(inv.getTeamUuid(), inv.getType());
                                TeamMessages.send(plugin, player, "joined-team-2", "%team_tag%", inv.getTeamTag());
                                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);
                                openMainMenu(player);
                            } else {
                                TeamMessages.send(plugin, player, "failed-joining-team-or-team-is-full");
                            }
                        } else {
                            Optional<Team> myTeam = teamsManager.getTeamByPlayer(player.getUniqueId());
                            if (myTeam.isPresent()) {
                                if (teamsManager.acceptAllyInvite(myTeam.get(), player, inv.getTeamUuid())) {
                                    TeamMessages.send(plugin, player, "allied-with-team", "%team_tag%", inv.getTeamTag());
                                    openAlliesMenu(player, 1);
                                } else {
                                    mailbox.removeInvite(inv.getTeamUuid(), inv.getType());
                                    TeamMessages.send(plugin, player, "failed-forming-alliance-limit-reached-or-team-2");
                                    openMailboxMenu(player, curPage);
                                }
                            } else {
                                TeamMessages.send(plugin, player, "you-must-be-in-a-team-to-2");
                            }
                        }
                    } else if (e.isRightClick()) {
                        mailbox.removeInvite(inv.getTeamUuid(), inv.getType());
                        TeamMessages.send(plugin, player, "invite-declined");
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                        openMailboxMenu(player, curPage);
                    }
                }));
            }
        }

        gui.open();
    }

    public void openColorMenu(Player player, int page) {
        if (player == null || !player.isOnline()) return;
        if (!teamsManager.requireReady(player)) return;
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        TeamMember colorMember = team.getMember(player.getUniqueId());
        if (colorMember == null || !colorMember.hasPermission(RolePermission.CHANGE_COLOR)) {
            TeamMessages.send(plugin, player, "you-do-not-have-permission-to-change");
            return;
        }
        FileConfiguration config = getMenuConfig("color-gui.yml");
        TeamMenuParser.ParsedLayout layout = getLayout("color-gui.yml", false);

        List<TeamColor> colors = teamsManager.getAvailableColors(player);
        boolean canUseCustomColor = new TeamColor("custom", "Custom", Material.NAME_TAG, "&f", "").hasPermission(player);
        if (colors.isEmpty() && !canUseCustomColor) {
            TeamMessages.send(plugin, player, "you-do-not-have-access-to-any");
            return;
        }
        int itemsPerPage = Math.max(1, layout.getContentSlots().size());
        int totalPages = Math.max(1, (int) Math.ceil((double) Math.max(1, colors.size()) / itemsPerPage));
        page = Math.min(totalPages, Math.max(1, page));

        Map<String, String> placeholders = Map.of(
                "%page%", String.valueOf(page),
                "%total%", String.valueOf(totalPages),
                "%team_tag%", team.getTag(),
                "%team_name%", team.getName()
        );

        Component titleComp = plugin.getTextService().parseForPlayer(player, layout.getTitle(), placeholders);
        PaginatedGui gui = new PaginatedGui(plugin, player, titleComp, layout.getRows());

        for (Map.Entry<Integer, TeamMenuParser.GuiItemConfig> entry : layout.getSlotMap().entrySet()) {
            int slot = entry.getKey();
            TeamMenuParser.GuiItemConfig cfg = entry.getValue();

            if ("custom_color".equals(cfg.getActions().get("left-click")) && !canUseCustomColor) continue;
            ItemStack stack = cfg.createItemStack(plugin, player, placeholders);
            int finalPage = page;
            GuiItem guiItem = new GuiItem(stack, e -> {
                e.setCancelled(true);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                String action = cfg.getActions().get("left-click");
                if (action != null) {
                    switch (action.toLowerCase(Locale.ROOT)) {
                        case "go_back" -> openSettingsMenu(player);
                        case "custom_color" -> {
                            player.closeInventory();
                            chatManager.askQuestion(player, TeamMessages.raw(plugin, "prompt-custom-color"), input -> {
                                if (TeamColor.fromHex(input) == null) {
                                    TeamMessages.send(plugin, player, "use-a-hex-color-in-the-format");
                                } else if (!teamsManager.changeTeamColor(team, player, input)) {
                                    TeamMessages.send(plugin, player, "you-do-not-have-permission-to-use-2");
                                } else {
                                    TeamMessages.send(plugin, player, "custom-team-color-updated");
                                }
                                openSettingsMenu(player);
                            });
                        }
                        case "to_previous_page" -> {
                            if (finalPage > 1) openColorMenu(player, finalPage - 1);
                        }
                        case "to_next_page" -> {
                            if (finalPage < totalPages) openColorMenu(player, finalPage + 1);
                        }
                    }
                }
            });
            gui.setItem(slot, guiItem);
        }

        String colorNameTemplate = config.getString("values.color-item-name", "%color%&l%name%");
        List<String> selectLore = config.getStringList("values.color-item-lore");
        List<String> selectedLore = config.getStringList("values.color-item-selected-lore");

        for (int i = 0; i < itemsPerPage; i++) {
            if (i >= layout.getContentSlots().size()) break;
            int slot = layout.getContentSlots().get(i);
            int cIdx = (page - 1) * itemsPerPage + i;

            if (cIdx < colors.size()) {
                TeamColor color = colors.get(cIdx);
                Map<String, String> cPh = new HashMap<>(placeholders);
                cPh.put("%color%", color.getColorCode());
                cPh.put("%name%", color.getName());

                boolean isSelected = team.getColorKey().equalsIgnoreCase(color.getKey());
                List<String> lore = isSelected ? selectedLore : selectLore;

                ItemStack colorStack = new ItemBuilder(color.getMaterial())
                        .name(plugin.getTextService().parseForPlayer(player, colorNameTemplate, cPh))
                        .lore(plugin.getTextService().parseListForPlayer(player, lore, cPh))
                        .build();

                gui.setItem(slot, new GuiItem(colorStack, e -> {
                    e.setCancelled(true);
                    if (!teamsManager.changeTeamColor(team, player, color.getKey())) {
                        TeamMessages.send(plugin, player, "you-do-not-have-permission-to-use");
                        return;
                    }
                    TeamMessages.send(plugin, player, "team-color-updated-to", "%color_code%", String.valueOf(color.getColorCode()), "%team_name%", color.getName());
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                    openSettingsMenu(player);
                }));
            }
        }

        gui.open();
    }

    public void openBankMenu(Player player) {
        if (player == null || !player.isOnline()) return;
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
        TeamMenuParser.ParsedLayout layout = getLayout("bank-gui.yml", false);

        double playerBalance = plugin.getVaultEconomy() != null ? plugin.getVaultEconomy().getBalance(player) : 0.0;

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%money%", plugin.getNumberFormatter().formatShort(team.getBankBalance()));
        placeholders.put("%money_short%", plugin.getNumberFormatter().formatShort(team.getBankBalance()));
        placeholders.put("%money_formatted%", plugin.getNumberFormatter().format(team.getBankBalance()));
        placeholders.put("%money_exact%", plugin.getNumberFormatter().formatExact(team.getBankBalance()));
        placeholders.put("%money_raw%", String.valueOf(team.getBankBalance()));
        placeholders.put("%balance%", plugin.getNumberFormatter().formatShort(team.getBankBalance()));
        placeholders.put("%balance_short%", plugin.getNumberFormatter().formatShort(team.getBankBalance()));
        placeholders.put("%balance_formatted%", plugin.getNumberFormatter().format(team.getBankBalance()));
        placeholders.put("%balance_exact%", plugin.getNumberFormatter().formatExact(team.getBankBalance()));
        placeholders.put("%balance_raw%", String.valueOf(team.getBankBalance()));
        placeholders.put("%team_bank%", plugin.getNumberFormatter().formatShort(team.getBankBalance()));
        placeholders.put("%team_bank_short%", plugin.getNumberFormatter().formatShort(team.getBankBalance()));
        placeholders.put("%team_bank_formatted%", plugin.getNumberFormatter().format(team.getBankBalance()));
        placeholders.put("%team_bank_exact%", plugin.getNumberFormatter().formatExact(team.getBankBalance()));
        placeholders.put("%team_bank_raw%", String.valueOf(team.getBankBalance()));
        placeholders.put("%team_name%", team.getName());
        placeholders.put("%team_tag%", team.getTag());
        placeholders.put("%team_color%", team.getColorKey());
        placeholders.put("%player%", player.getName());
        placeholders.put("%player_balance%", plugin.getNumberFormatter().formatShort(playerBalance));
        placeholders.put("%player_balance_short%", plugin.getNumberFormatter().formatShort(playerBalance));
        placeholders.put("%player_balance_formatted%", plugin.getNumberFormatter().format(playerBalance));
        placeholders.put("%player_balance_exact%", plugin.getNumberFormatter().formatExact(playerBalance));
        placeholders.put("%bank_limit%", plugin.getNumberFormatter().formatShort(teamsManager.getBankLimit()));
        placeholders.put("%bank_limit_short%", plugin.getNumberFormatter().formatShort(teamsManager.getBankLimit()));
        placeholders.put("%bank_limit_formatted%", plugin.getNumberFormatter().format(teamsManager.getBankLimit()));
        placeholders.put("%bank_limit_exact%", plugin.getNumberFormatter().formatExact(teamsManager.getBankLimit()));

        Component titleComp = plugin.getTextService().parseForPlayer(player, layout.getTitle(), placeholders);
        PaginatedGui gui = new PaginatedGui(plugin, player, titleComp, layout.getRows());

        for (Map.Entry<Integer, TeamMenuParser.GuiItemConfig> entry : layout.getSlotMap().entrySet()) {
            int slot = entry.getKey();
            TeamMenuParser.GuiItemConfig cfg = entry.getValue();

            ItemStack stack = cfg.createItemStack(plugin, player, placeholders);
            GuiItem guiItem = new GuiItem(stack, e -> {
                e.setCancelled(true);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                String action = cfg.getActions().get("left-click");
                if (action != null) {
                    handleBankAction(player, team, action);
                }
            });
            gui.setItem(slot, guiItem);
        }

        gui.open();
    }

    private void handleBankAction(Player player, Team team, String action) {
        if (action.equalsIgnoreCase("go_back") || action.equalsIgnoreCase("goback")) {
            openMainMenu(player);
            return;
        }
        if (action.equalsIgnoreCase("explain")) {
            return;
        }

        double amount = 0;
        boolean isDeposit = false;

        switch (action.toLowerCase(Locale.ROOT)) {
            case "add_1" -> { amount = 1; isDeposit = true; }
            case "add_10" -> { amount = 10; isDeposit = true; }
            case "add_100" -> { amount = 100; isDeposit = true; }
            case "add_1000" -> { amount = 1000; isDeposit = true; }
            case "add_10k" -> { amount = 10000; isDeposit = true; }
            case "add_100k" -> { amount = 100000; isDeposit = true; }
            case "add_1m" -> { amount = 1000000; isDeposit = true; }

            case "take_1" -> { amount = 1; isDeposit = false; }
            case "take_10" -> { amount = 10; isDeposit = false; }
            case "take_100" -> { amount = 100; isDeposit = false; }
            case "take_1000" -> { amount = 1000; isDeposit = false; }
            case "take_10k" -> { amount = 10000; isDeposit = false; }
            case "take_100k" -> { amount = 100000; isDeposit = false; }
            case "take_1m" -> { amount = 1000000; isDeposit = false; }
        }

        if (amount > 0) {
            if (plugin.getVaultEconomy() == null) {
                TeamMessages.send(plugin, player, "economy-is-not-enabled-2");
                return;
            }

            if (isDeposit) {
                if (!plugin.getVaultEconomy().has(player, amount)) {
                    TeamMessages.send(plugin, player, "error-not-enough-money");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    return;
                }
                double transactionAmount = amount;
                teamsManager.depositFromPlayer(player, team, transactionAmount).thenAccept(result -> plugin.getScheduler().runEntity(player, () -> {
                    if (!result.success()) {
                        TeamMessages.send(plugin, player, result.status() == TeamsManager.BankTransactionStatus.LIMIT_EXCEEDED ? "bank-limit-exceeded" : "bank-deposit-failed");
                        return;
                    }
                    TeamMessages.send(plugin, player, "bank-deposited", "%amount%", plugin.getNumberFormatter().format(transactionAmount), "%bank%", plugin.getNumberFormatter().format(result.balance()));
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                    openBankMenu(player);
                }));
            } else {
                if (!team.hasMember(player.getUniqueId()) || !team.getMember(player.getUniqueId()).hasPermission(RolePermission.ACCESS_BANK)) {
                    TeamMessages.send(plugin, player, "you-do-not-have-permission-to-withdraw-2");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    return;
                }
                double transactionAmount = amount;
                teamsManager.withdrawToPlayer(player, team, transactionAmount).thenAccept(result -> plugin.getScheduler().runEntity(player, () -> {
                    if (result.success()) {
                    TeamMessages.send(plugin, player, "bank-withdrew", "%amount%", plugin.getNumberFormatter().format(transactionAmount), "%bank%", plugin.getNumberFormatter().format(result.balance()));
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
                    } else TeamMessages.send(plugin, player, result.status() == TeamsManager.BankTransactionStatus.INSUFFICIENT_FUNDS ? "bank-insufficient-funds" : "bank-withdraw-failed");
                    openBankMenu(player);
                }));
            }
        }
    }

    public void openSettingsMenu(Player player) {
        if (player == null || !player.isOnline()) return;
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        FileConfiguration config = getMenuConfig("settings-gui.yml");
        TeamMenuParser.ParsedLayout layout = getLayout("settings-gui.yml", false);

        String toggleWord = team.isFriendlyFireEnabled()
                ? config.getString("values.toggle-off-word", "disable")
                : config.getString("values.toggle-on-word", "enable");

        Map<String, String> placeholders = Map.of(
                "%team_name%", team.getName(),
                "%team_tag%", team.getTag(),
                "%color%", team.getColorKey(),
                "%friendlyfire%", team.isFriendlyFireEnabled() ? "<green>Enabled</green>" : "<red>Disabled</red>",
                "%toggle%", toggleWord
        );

        Component titleComp = plugin.getTextService().parseForPlayer(player, layout.getTitle(), placeholders);
        PaginatedGui gui = new PaginatedGui(plugin, player, titleComp, layout.getRows());

        for (Map.Entry<Integer, TeamMenuParser.GuiItemConfig> entry : layout.getSlotMap().entrySet()) {
            int slot = entry.getKey();
            TeamMenuParser.GuiItemConfig cfg = entry.getValue();

            ItemStack stack;
            if (cfg.getSymbol() == 'f') {
                String ffMatName = team.isFriendlyFireEnabled()
                        ? config.getString("values.toggle-on-material", "GREEN_WOOL")
                        : config.getString("values.toggle-off-material", "RED_WOOL");
                Material ffMat = Material.matchMaterial(ffMatName);
                if (ffMat == null) ffMat = team.isFriendlyFireEnabled() ? Material.GREEN_WOOL : Material.RED_WOOL;

                stack = new ItemBuilder(ffMat)
                        .name(plugin.getTextService().parseForPlayer(player, cfg.getDisplayName(), placeholders))
                        .lore(plugin.getTextService().parseListForPlayer(player, cfg.getLore(), placeholders))
                        .build();
            } else {
                stack = cfg.createItemStack(plugin, player, placeholders);
            }

            GuiItem guiItem = new GuiItem(stack, e -> {
                e.setCancelled(true);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                String action = cfg.getActions().get("left-click");
                if (action != null) {
                    handleSettingsAction(player, team, action);
                }
            });
            gui.setItem(slot, guiItem);
        }

        gui.open();
    }

    private void handleSettingsAction(Player player, Team team, String action) {
        switch (action.toLowerCase(Locale.ROOT)) {
            case "go_back" -> openMainMenu(player);
            case "open_color_picker", "open_color" -> openColorMenu(player, 1);
            case "toggle_friendly_fire", "friendly_fire" -> {
                if (!team.hasMember(player.getUniqueId()) || !team.getMember(player.getUniqueId()).hasPermission(RolePermission.SWITCH_FRIENDLY_FIRE)) {
                    TeamMessages.send(plugin, player, "you-do-not-have-permission-to-toggle");
                    return;
                }
                boolean newState = !team.isFriendlyFireEnabled();
                teamsManager.setFriendlyFireEnabled(team, player, newState);
                TeamMessages.send(plugin, player, newState ? "friendly-fire-enabled" : "friendly-fire-disabled");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, newState ? 3.0f : 2.0f);
                openSettingsMenu(player);
            }
            case "change_team_tag", "settag" -> {
                if (!team.hasMember(player.getUniqueId()) || !team.getMember(player.getUniqueId()).hasPermission(RolePermission.CHANGE_TAG)) {
                    TeamMessages.send(plugin, player, "you-do-not-have-permission-to-change-2");
                    return;
                }
                player.closeInventory();
                chatManager.askQuestion(player, TeamMessages.raw(plugin, "prompt-change-team-tag"), newTag -> {
                    if (teamsManager.changeTeamTag(team, player, newTag)) {
                        TeamMessages.send(plugin, player, "team-tag-changed-to", "%new_tag%", newTag);
                        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                    } else {
                        TeamMessages.send(plugin, player, "invalid-tag-or-tag-already-taken");
                    }
                });
            }
        }
    }

    public void openStatsMenu(Player player) {
        if (player == null || !player.isOnline()) return;
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        TeamMenuParser.ParsedLayout layout = getLayout("stats-gui.yml", false);

        int totalKills = 0;
        int totalDeaths = 0;
        int totalMobs = 0;
        int totalBroken = 0;
        int totalPlaced = 0;
        long totalPlaytime = 0;

        for (TeamMember m : team.getMemberList()) {
            TeamPlayerStats s = teamsManager.getOrCreatePlayerStats(m.getUniqueId(), m.getUsername());
            totalKills += s.getKills();
            totalDeaths += s.getDeaths();
            totalMobs += s.getMobKills();
            totalBroken += s.getBlocksBroken();
            totalPlaced += s.getBlocksPlaced();
            totalPlaytime += s.getPlaytimeSeconds();
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%kills%", String.valueOf(totalKills));
        placeholders.put("%deaths%", String.valueOf(totalDeaths));
        placeholders.put("%mob_kills%", String.valueOf(totalMobs));
        placeholders.put("%blocks_broken%", String.valueOf(totalBroken));
        placeholders.put("%blocks_placed%", String.valueOf(totalPlaced));
        placeholders.put("%score%", String.format(Locale.US, "%,.2f", team.getScore()));
        placeholders.put("%rank%", String.valueOf(team.getRank()));
        placeholders.put("%team_tag%", team.getTag());
        placeholders.put("%team_name%", team.getName());
        placeholders.put("%money%", plugin.getNumberFormatter().formatShort(team.getBankBalance()));
        placeholders.put("%money_short%", plugin.getNumberFormatter().formatShort(team.getBankBalance()));
        placeholders.put("%money_formatted%", plugin.getNumberFormatter().format(team.getBankBalance()));
        placeholders.put("%money_exact%", plugin.getNumberFormatter().formatExact(team.getBankBalance()));
        placeholders.put("%team_bank%", plugin.getNumberFormatter().formatShort(team.getBankBalance()));
        placeholders.put("%team_bank_short%", plugin.getNumberFormatter().formatShort(team.getBankBalance()));
        placeholders.put("%team_bank_formatted%", plugin.getNumberFormatter().format(team.getBankBalance()));
        placeholders.put("%team_bank_exact%", plugin.getNumberFormatter().formatExact(team.getBankBalance()));

        Component titleComp = plugin.getTextService().parseForPlayer(player, layout.getTitle(), placeholders);
        PaginatedGui gui = new PaginatedGui(plugin, player, titleComp, layout.getRows());

        for (Map.Entry<Integer, TeamMenuParser.GuiItemConfig> entry : layout.getSlotMap().entrySet()) {
            int slot = entry.getKey();
            TeamMenuParser.GuiItemConfig cfg = entry.getValue();

            ItemStack stack = cfg.createItemStack(plugin, player, placeholders);
            GuiItem guiItem = new GuiItem(stack, e -> {
                e.setCancelled(true);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                String action = cfg.getActions().get("left-click");
                if (action != null) {
                    if (action.equalsIgnoreCase("go_back") || action.equalsIgnoreCase("goback")) {
                        openMainMenu(player);
                    } else {
                        openPlayerStatsBreakdownMenu(player, action, 1);
                    }
                }
            });
            gui.setItem(slot, guiItem);
        }

        gui.open();
    }

    public void openPlayerStatsBreakdownMenu(Player player, String category, int page) {
        if (player == null || !player.isOnline()) return;
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) return;

        Team team = teamOpt.get();
        FileConfiguration config = getMenuConfig("playerstats-gui.yml");
        TeamMenuParser.ParsedLayout layout = getLayout("playerstats-gui.yml", false);

        List<TeamMember> members = new ArrayList<>(team.getMemberList());
        int itemsPerPage = Math.max(1, layout.getContentSlots().size());
        int totalPages = Math.max(1, (int) Math.ceil((double) Math.max(1, members.size()) / itemsPerPage));
        page = Math.min(totalPages, Math.max(1, page));

        Map<String, String> placeholders = Map.of(
                "%page%", String.valueOf(page),
                "%total%", String.valueOf(totalPages),
                "%category%", category,
                "%team_tag%", team.getTag(),
                "%team_name%", team.getName()
        );

        Component titleComp = plugin.getTextService().parseForPlayer(player, layout.getTitle(), placeholders);
        PaginatedGui gui = new PaginatedGui(plugin, player, titleComp, layout.getRows());

        for (Map.Entry<Integer, TeamMenuParser.GuiItemConfig> entry : layout.getSlotMap().entrySet()) {
            int slot = entry.getKey();
            TeamMenuParser.GuiItemConfig cfg = entry.getValue();

            ItemStack stack = cfg.createItemStack(plugin, player, placeholders);
            int finalPage = page;
            GuiItem guiItem = new GuiItem(stack, e -> {
                e.setCancelled(true);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                String action = cfg.getActions().get("left-click");
                if (action != null) {
                    switch (action.toLowerCase(Locale.ROOT)) {
                        case "go_back", "goback" -> openStatsMenu(player);
                        case "to_previous_page" -> {
                            if (finalPage > 1) openPlayerStatsBreakdownMenu(player, category, finalPage - 1);
                        }
                        case "to_next_page" -> {
                            if (finalPage < totalPages) openPlayerStatsBreakdownMenu(player, category, finalPage + 1);
                        }
                    }
                }
            });
            gui.setItem(slot, guiItem);
        }

        String headTitle = config.getString("values.player-head-title", "&#67F17B%player%");
        List<String> headLore = config.getStringList("values.player-head-lore");
        String valueTemplate = config.getString("values." + category, "%value%");

        for (int i = 0; i < itemsPerPage; i++) {
            if (i >= layout.getContentSlots().size()) break;
            int slot = layout.getContentSlots().get(i);
            int mIdx = (page - 1) * itemsPerPage + i;

            if (mIdx < members.size()) {
                TeamMember m = members.get(mIdx);
                TeamPlayerStats stats = teamsManager.getOrCreatePlayerStats(m.getUniqueId(), m.getUsername());

                String rawValue = switch (category.toLowerCase(Locale.ROOT)) {
                    case "kills" -> String.valueOf(stats.getKills());
                    case "deaths" -> String.valueOf(stats.getDeaths());
                    case "mobs_killed" -> String.valueOf(stats.getMobKills());
                    case "blocks_broken" -> String.valueOf(stats.getBlocksBroken());
                    case "blocks_placed" -> String.valueOf(stats.getBlocksPlaced());
                    case "money" -> plugin.getNumberFormatter().formatShort(team.getBankBalance());
                    case "playtime" -> String.valueOf(stats.getPlaytimeSeconds() / 60) + "m";
                    case "level" -> String.valueOf(stats.getLevel());
                    default -> "0";
                };

                String formattedValue = valueTemplate.replace("%value%", rawValue);

                Map<String, String> mPh = new HashMap<>(placeholders);
                mPh.put("%player%", m.getUsername());
                mPh.put("%value%", formattedValue);

                ItemStack head = new ItemBuilder(Material.PLAYER_HEAD)
                        .skullOwner(m.getUniqueId(), m.getUsername())
                        .name(plugin.getTextService().parseForPlayer(player, headTitle, mPh))
                        .lore(plugin.getTextService().parseListForPlayer(player, headLore, mPh))
                        .build();

                gui.setItem(slot, new GuiItem(head, e -> e.setCancelled(true)));
            }
        }

        gui.open();
    }

    public void openPlayerStatsMenu(Player player, String targetName) {
        PlayerLookup.byName(plugin, player, targetName, target -> openPlayerStatsMenu(player, target.getUniqueId()));
    }

    public void openPlayerStatsMenu(Player player, UUID targetUuid) {
        if (player == null || !player.isOnline()) return;

        OfflinePlayer op = Bukkit.getOfflinePlayer(targetUuid);
        String username = op.getName() != null ? op.getName() : targetUuid.toString().substring(0, 8);
        TeamPlayerStats stats = teamsManager.getOrCreatePlayerStats(targetUuid, username);

        TeamMenuParser.ParsedLayout layout = getLayout("playerstats-gui.yml", false);

        Map<String, String> placeholders = Map.of(
                "%player%", username,
                "%kills%", String.valueOf(stats.getKills()),
                "%deaths%", String.valueOf(stats.getDeaths()),
                "%kdr%", String.format(Locale.US, "%.2f", stats.getKdr()),
                "%mob_kills%", String.valueOf(stats.getMobKills()),
                "%blocks_broken%", String.valueOf(stats.getBlocksBroken()),
                "%blocks_placed%", String.valueOf(stats.getBlocksPlaced()),
                "%playtime%", String.valueOf(stats.getPlaytimeSeconds() / 60) + "m",
                "%level%", String.valueOf(stats.getLevel())
        );

        Component titleComp = plugin.getTextService().parseForPlayer(player, layout.getTitle().replace("%player%", username), placeholders);
        PaginatedGui gui = new PaginatedGui(plugin, player, titleComp, layout.getRows());

        for (Map.Entry<Integer, TeamMenuParser.GuiItemConfig> entry : layout.getSlotMap().entrySet()) {
            int slot = entry.getKey();
            TeamMenuParser.GuiItemConfig cfg = entry.getValue();

            ItemStack stack = cfg.createItemStack(plugin, player, placeholders);
            GuiItem guiItem = new GuiItem(stack, e -> {
                e.setCancelled(true);
                if ("go_back".equalsIgnoreCase(cfg.getActions().get("left-click"))) {
                    openMembersMenu(player, 1);
                }
            });
            gui.setItem(slot, guiItem);
        }

        gui.open();
    }

    public void openLeaderboardMenu(Player player, int page, String sortMode) {
        if (player == null || !player.isOnline()) return;
        if (!teamsManager.requireReady(player)) return;
        if (!teamsManager.isLeaderboardEnabled()) {
            TeamMessages.send(plugin, player, "the-team-leaderboard-feature-is-disabled");
            return;
        }

        FileConfiguration config = getMenuConfig("leaderboard-gui.yml");
        TeamMenuParser.ParsedLayout layout = getLayout("leaderboard-gui.yml", false);

        List<Team> teams = leaderboardManager.getTopByScore();
        int itemsPerPage = 21;
        int maxPages = Math.max(1, (int) Math.ceil((double) Math.max(1, teams.size()) / itemsPerPage));
        page = Math.min(maxPages, Math.max(1, page));

        Map<String, String> placeholders = Map.of(
                "%page%", String.valueOf(page),
                "%max%", String.valueOf(maxPages)
        );

        Component titleComp = plugin.getTextService().parseForPlayer(player, layout.getTitle(), placeholders);
        PaginatedGui gui = new PaginatedGui(plugin, player, titleComp, layout.getRows());

        gui.fillBorder(new ItemBuilder(Material.BLACK_STAINED_GLASS_PANE).name(Component.empty()).build());

        int startIndex = (page - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, teams.size());

        int slotIdx = 10;
        for (int i = startIndex; i < endIndex; i++) {
            Team t = teams.get(i);
            while (slotIdx % 9 == 0 || slotIdx % 9 == 8) {
                slotIdx++;
            }
            if (slotIdx >= layout.getSize() - 9) break;

            int teamKills = 0;
            int teamDeaths = 0;
            for (TeamMember m : t.getMemberList()) {
                TeamPlayerStats s = teamsManager.getOrCreatePlayerStats(m.getUniqueId(), m.getUsername());
                teamKills += s.getKills();
                teamDeaths += s.getDeaths();
            }

            Map<String, String> tPh = new HashMap<>(placeholders);
            tPh.put("%rank%", String.valueOf(t.getRank()));
            tPh.put("%team_tag%", t.getTag());
            tPh.put("%team_name%", t.getName());
            tPh.put("%team_score%", String.format(Locale.US, "%,.2f", t.getScore()));
            tPh.put("%team_bank%", plugin.getNumberFormatter().formatShort(t.getBankBalance()));
            tPh.put("%team_bank_short%", plugin.getNumberFormatter().formatShort(t.getBankBalance()));
            tPh.put("%team_bank_formatted%", plugin.getNumberFormatter().format(t.getBankBalance()));
            tPh.put("%team_bank_exact%", plugin.getNumberFormatter().formatExact(t.getBankBalance()));
            tPh.put("%team_kills%", String.valueOf(teamKills));
            tPh.put("%team_deaths%", String.valueOf(teamDeaths));
            tPh.put("%team_members%", String.valueOf(t.getMemberCount()));

            String teamNameTemplate = config.getString("items.team.name", "&#67F17B%rank% &7⏵ &f%team_tag%");
            List<String> teamLoreTemplate = config.getStringList("items.team.lore");

            ItemStack head = new ItemBuilder(Material.PLAYER_HEAD)
                    .skullOwner(t.getOwner().getUniqueId(), t.getOwner().getUsername())
                    .name(plugin.getTextService().parseForPlayer(player, teamNameTemplate, tPh))
                    .lore(plugin.getTextService().parseListForPlayer(player, teamLoreTemplate, tPh))
                    .build();

            gui.setItem(slotIdx, new GuiItem(head, e -> e.setCancelled(true)));
            slotIdx++;
        }

        int finalPage = page;
        gui.addNavigationControls(
                () -> openLeaderboardMenu(player, finalPage - 1, sortMode),
                () -> openLeaderboardMenu(player, finalPage + 1, sortMode)
        );

        ItemStack backItem = new ItemBuilder(Material.GLOBE_BANNER_PATTERN).name(TeamMessages.component(plugin, "gui-back")).build();
        gui.setItem(layout.getSize() - 5, new GuiItem(backItem, e -> openMainMenu(player)));

        gui.open();
    }

    public void openConfirmMenu(Player player, String promptTitle, Runnable onConfirm, Runnable onCancel) {
        if (player == null || !player.isOnline()) return;

        TeamMenuParser.ParsedLayout layout = getLayout("confirm-gui.yml", false);

        Map<String, String> placeholders = Map.of("%reason%", promptTitle);
        Component titleComp = plugin.getTextService().parseForPlayer(player, layout.getTitle(), placeholders);
        PaginatedGui gui = new PaginatedGui(plugin, player, titleComp, Math.max(1, layout.getRows()));

        if (!layout.getSlotMap().isEmpty()) {
            for (Map.Entry<Integer, TeamMenuParser.GuiItemConfig> entry : layout.getSlotMap().entrySet()) {
                int slot = entry.getKey();
                TeamMenuParser.GuiItemConfig cfg = entry.getValue();
                ItemStack stack = cfg.createItemStack(plugin, player, placeholders);
                String action = cfg.getActions().getOrDefault("left-click", "");

                GuiItem guiItem = new GuiItem(stack, e -> {
                    e.setCancelled(true);
                    if ("confirm".equalsIgnoreCase(action)) {
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.5f);
                        if (onConfirm != null) onConfirm.run();
                    } else if ("cancel".equalsIgnoreCase(action)) {
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 0.8f);
                        if (onCancel != null) onCancel.run();
                    }
                });
                gui.setItem(slot, guiItem);
            }
        } else {
            ItemStack confirmItem = new ItemBuilder(Material.LIME_STAINED_GLASS_PANE)
                    .name(TeamMessages.component(plugin, "gui-confirm-name"))
                    .lore(List.of(TeamMessages.component(plugin, "gui-confirm-lore")))
                    .build();

            ItemStack cancelItem = new ItemBuilder(Material.RED_STAINED_GLASS_PANE)
                    .name(TeamMessages.component(plugin, "gui-cancel-name"))
                    .lore(List.of(TeamMessages.component(plugin, "gui-cancel-lore")))
                    .build();

            gui.setItem(3, new GuiItem(cancelItem, e -> {
                e.setCancelled(true);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 0.8f);
                if (onCancel != null) onCancel.run();
            }));

            gui.setItem(5, new GuiItem(confirmItem, e -> {
                e.setCancelled(true);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.5f);
                if (onConfirm != null) onConfirm.run();
            }));
        }

        gui.open();
    }

    public void openEnderChest(Player player) {
        if (player == null || !player.isOnline()) return;
        if (!teamsManager.requireReady(player)) return;
        if (!teamsManager.isEnderchestEnabled()) {
            TeamMessages.send(plugin, player, "the-team-vault-feature-is-disabled");
            return;
        }
        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            TeamMessages.send(plugin, player, "error-no-team");
            return;
        }

        Team team = teamOpt.get();
        TeamMember member = team.getMember(player.getUniqueId());
        if (member == null || !member.hasPermission(RolePermission.ACCESS_ENDERCHEST)) {
            TeamMessages.send(plugin, player, "you-do-not-have-permission-to-access");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }
        if (team.getMemberCount() < teamsManager.getMinMembersEnderchest()) {
            TeamMessages.send(plugin, player, "your-team-needs-at-least-members-to", "%min_members_enderchest%", String.valueOf(teamsManager.getMinMembersEnderchest()));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        FileConfiguration config = getMenuConfig("enderchest-gui.yml");
        String rawTitle = config != null ? config.getString("inventory.title", "&0⏵ Team | Enderchest") : "&0⏵ Team | Enderchest";
        int configuredRows = config != null ? config.getInt("inventory.rows", 6) : 6;

        Map<String, String> placeholders = Map.of(
                "%team_name%", team.getName(),
                "%team_tag%", team.getTag(),
                "%team_color%", team.getColorKey(),
                "%money%", plugin.getNumberFormatter().formatExact(team.getBankBalance()),
                "%balance%", plugin.getNumberFormatter().formatExact(team.getBankBalance()),
                "%money_formatted%", plugin.getNumberFormatter().format(team.getBankBalance()),
                "%balance_formatted%", plugin.getNumberFormatter().format(team.getBankBalance())
        );

        Component parsedTitle = plugin.getTextService().parseForPlayer(player, rawTitle, placeholders);

        if (plugin.getHookManager() != null && plugin.getHookManager().getMcDataBridgeHook() != null
                && plugin.getHookManager().getMcDataBridgeHook().isPlayerLocked(player)) {
            TeamMessages.send(plugin, player, "please-wait-for-inventory-synchronization-to-finish");
            return;
        }

        teamsManager.acquireTeamVault(team, player).thenAccept(snapshot -> plugin.getScheduler().runEntity(player, () -> {
            if (!player.isOnline() || snapshot == null) {
                if (snapshot != null) teamsManager.releaseTeamVault(team, snapshot);
                if (player.isOnline()) TeamMessages.send(plugin, player, "the-team-enderchest-is-currently-in-use-2");
                return;
            }
            Team currentTeam = teamsManager.getTeamByPlayer(player.getUniqueId()).orElse(null);
            TeamMember currentMember = currentTeam != null ? currentTeam.getMember(player.getUniqueId()) : null;
            if (currentTeam != team || currentMember == null || !currentMember.hasPermission(RolePermission.ACCESS_ENDERCHEST)) {
                teamsManager.releaseTeamVault(team, snapshot);
                return;
            }
            teamsManager.openTeamVault(team, player, snapshot, parsedTitle, configuredRows);
            player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.0f);
        }, () -> teamsManager.releaseTeamVault(team, snapshot)));
    }
}
