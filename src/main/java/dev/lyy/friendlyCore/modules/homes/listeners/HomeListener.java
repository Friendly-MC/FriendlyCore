package dev.lyy.friendlycore.modules.homes.listeners;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.homes.HomesManager;
import dev.lyy.friendlycore.modules.homes.model.HomeData;
import dev.lyy.friendlycore.modules.homes.model.PlayerHomes;
import dev.lyy.friendlycore.modules.homes.model.SelectedHomeRename;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Event listener for player home initialization, chat-based renaming sessions,
 * combat tagging, and cross-server teleport triggers.
 */
public class HomeListener implements Listener {

    private final FriendlyCore plugin;
    private final HomesManager homesManager;

    public HomeListener(FriendlyCore plugin, HomesManager homesManager) {
        this.plugin = plugin;
        this.homesManager = homesManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        homesManager.loadPlayerHomes(uuid, player.getName());

        Location targetLoc = homesManager.pollPendingTeleport(uuid);
        if (targetLoc != null && targetLoc.getWorld() != null) {
            plugin.getScheduler().runEntityLater(player, () -> {
                if (!player.isOnline()) return;
                dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(player, targetLoc).thenAccept(success -> {
                    if (Boolean.TRUE.equals(success)) {
                        homesManager.playSound(player, "teleported");
                        homesManager.sendHomeMessage(player, "home-teleport-chat", Map.of("%home%", ""));
                        homesManager.sendHomeActionBar(player, "teleported-action-bar", Map.of("%home%", ""));
                    }
                });
            }, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        homesManager.unloadPlayer(player.getUniqueId());
        dev.lyy.friendlycore.common.SafeTeleportUtil.clearInFlight(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPaperChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        SelectedHomeRename renameSession = homesManager.getChatRename(player.getUniqueId());
        if (renameSession == null) return;

        event.setCancelled(true);
        homesManager.removeChatRename(player.getUniqueId());

        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        plugin.getScheduler().runEntity(player, () -> processRenameInput(player, renameSession, message));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        SelectedHomeRename renameSession = homesManager.getChatRename(player.getUniqueId());
        if (renameSession == null) return;

        event.setCancelled(true);
        homesManager.removeChatRename(player.getUniqueId());

        String message = ChatColor.stripColor(event.getMessage()).trim();
        plugin.getScheduler().runEntity(player, () -> processRenameInput(player, renameSession, message));
    }

    private void processRenameInput(Player player, SelectedHomeRename renameSession, String message) {
        if (message.equalsIgnoreCase("cancel")) {
            homesManager.playSound(player, "ui-click");
            homesManager.sendHomeMessage(player, "delete-cancel-chat", Map.of());
            return;
        }

        if (message.length() > 32) {
            homesManager.sendHomeMessage(player, "name-too-long", Map.of());
            homesManager.playSound(player, "error");
            return;
        }

        UUID targetUuid = renameSession.getTargetPlayerUuid();
        int slot = renameSession.getSlot();

        PlayerHomes profile = homesManager.getPlayerHomes(targetUuid);
        if (profile != null) {
            HomeData home = profile.getHome(slot);
            if (home != null) {
                home.setName(message);
                homesManager.savePlayerHomesAsync(profile);
                homesManager.playSound(player, "ui-click");

                if (player.getUniqueId().equals(targetUuid)) {
                    homesManager.sendHomeMessage(player, "home-renamed",
                            Map.of("%home%", String.valueOf(slot), "%name%", message));
                } else {
                    homesManager.sendHomeMessage(player, "home-renamed-someone-else",
                            Map.of("%player%", profile.getUsername(), "%home%", String.valueOf(slot), "%name%", message));
                }
            } else {
                homesManager.sendHomeMessage(player, "home-not-found", Map.of());
                homesManager.playSound(player, "error");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            homesManager.tagCombat(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker) {
            homesManager.tagCombat(attacker.getUniqueId());
        }
        if (event.getEntity() instanceof Player victim) {
            homesManager.tagCombat(victim.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (!homesManager.isSetHomeOnBedInteract()) return;
        Player player = event.getPlayer();
        if (event.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK) {
            homesManager.setHomeAtLocation(player, 1, "", player.getLocation());
        }
    }
}
