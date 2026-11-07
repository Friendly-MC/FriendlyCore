package dev.lyy.friendlycore.modules.essentials;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.SafeTeleportUtil;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.essentials.crossserver.EssentialsCrossServerService;
import dev.lyy.friendlycore.modules.essentials.model.AdminTeleportTicket;
import dev.lyy.friendlycore.modules.essentials.model.EnderchestSession;
import dev.lyy.friendlycore.modules.essentials.model.InvseeSession;
import dev.lyy.friendlycore.modules.essentials.util.InvseeLayout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;

/**
 * Handles Bukkit and Paper events for god mode immunity, inventory inspection synchronization,
 * and incoming cross-server teleportation arrivals.
 */
public class EssentialsListener implements Listener {

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;

    public EssentialsListener(FriendlyCore plugin, EssentialsManager essentialsManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        if (essentialsManager.isGodMode(player.getUniqueId())) {
            event.setCancelled(true);
            if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
                // Teleport god-mode players out of the void to a safe surface or world spawn
                Location loc = player.getLocation();
                Location safeLoc = SafeTeleportUtil.getHighestSafeLocation(loc);
                if (safeLoc == null) {
                    safeLoc = loc.getWorld().getSpawnLocation();
                }
                SafeTeleportUtil.teleportAsync(player, safeLoc);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        if (essentialsManager.isGodMode(player.getUniqueId())) {
            event.setCancelled(true);
            player.setFoodLevel(20);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        EssentialsCrossServerService crossService = essentialsManager.getCrossServerService();
        AdminTeleportTicket ticket = crossService.consumeArrivalTicket(player.getUniqueId());

        if (ticket != null && !ticket.isExpired()) {
            Location dest = ticket.toLocation();
            if (dest != null && dest.getWorld() != null) {
                plugin.getScheduler().runEntityLater(player, () -> {
                    if (!player.isOnline()) return;
                    SafeTeleportUtil.teleportAsync(player, dest).thenAccept(success -> {
                        if (Boolean.TRUE.equals(success)) {
                            TextService.playSound(player, "entity.experience_orb.pickup", 1.0f, 1.2f);
                            essentialsManager.sendMessage(player, "teleport.teleported-arrival", Map.of());
                        }
                    });
                }, 3L);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID uuid = player.getUniqueId();
        SafeTeleportUtil.clearInFlight(uuid);

        // A dying viewer closes the GUI so their edits are written back and the copy stops being editable.
        if (essentialsManager.getInvseeSession(uuid) != null || essentialsManager.getEnderchestSession(uuid) != null) {
            player.closeInventory();
        }
        // A dying target: the server clears the inventory after this event; never touch it here.
        essentialsManager.handleTargetDeath(player, event.getKeepInventory());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player quittingPlayer = event.getPlayer();
        UUID uuid = quittingPlayer.getUniqueId();
        essentialsManager.handleTargetQuit(quittingPlayer);
        essentialsManager.removeInvseeSession(uuid);
        essentialsManager.removeEnderchestSession(uuid);
        essentialsManager.cleanupTargetSessions(uuid);
        essentialsManager.clearPendingConfirmation(uuid);
        essentialsManager.getCrossServerService().forgetTarget(uuid);
        SafeTeleportUtil.clearInFlight(uuid);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        UUID viewerUuid = player.getUniqueId();

        InvseeSession invSession = essentialsManager.getInvseeSession(viewerUuid);
        if (invSession != null) {
            if (closeIfLocalTargetGone(player,
                    invSession.getSessionType() == InvseeSession.SessionType.LOCAL_ONLINE, invSession.getTargetUuid())) {
                event.setCancelled(true);
                return;
            }
            int slot = event.getRawSlot();

            if (slot >= event.getInventory().getSize()) {
                if (!invSession.isEditable() || event.isShiftClick()) {
                    event.setCancelled(true);
                } else {
                    markInvseeModified(player, invSession);
                }
                return;
            }

            if (!invSession.isEditable()) {
                event.setCancelled(true);
                return;
            }

            boolean isHotbarSwap = event.getClick() == ClickType.NUMBER_KEY || event.getClick() == ClickType.SWAP_OFFHAND;
            if (invSession.getInventory().getSize() == InvseeLayout.GUI_SIZE
                    && (InvseeLayout.isLockedGuiSlot(slot) || isHotbarSwap)) {
                event.setCancelled(true);
                return;
            }

            markInvseeModified(player, invSession);
            return;
        }

        // Process active ender chest session click events
        EnderchestSession ecSession = essentialsManager.getEnderchestSession(viewerUuid);
        if (ecSession != null) {
            if (closeIfLocalTargetGone(player,
                    ecSession.getSessionType() == EnderchestSession.SessionType.LOCAL_ONLINE, ecSession.getTargetUuid())) {
                event.setCancelled(true);
                return;
            }
            if (!ecSession.isEditable()) {
                event.setCancelled(true);
            } else {
                markEcModified(player, ecSession);
            }
            return;
        }

        // The click is applied after this event; show it to admins viewing this player next tick.
        essentialsManager.scheduleTargetSync(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        UUID viewerUuid = player.getUniqueId();

        InvseeSession invSession = essentialsManager.getInvseeSession(viewerUuid);
        if (invSession != null) {
            if (closeIfLocalTargetGone(player,
                    invSession.getSessionType() == InvseeSession.SessionType.LOCAL_ONLINE, invSession.getTargetUuid())) {
                event.setCancelled(true);
                return;
            }
            if (!invSession.isEditable()) {
                event.setCancelled(true);
                return;
            }
            if (invSession.getInventory().getSize() == InvseeLayout.GUI_SIZE) {
                for (int slot : event.getRawSlots()) {
                    if (InvseeLayout.isLockedGuiSlot(slot)) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
            markInvseeModified(player, invSession);
            return;
        }

        EnderchestSession ecSession = essentialsManager.getEnderchestSession(viewerUuid);
        if (ecSession != null) {
            if (closeIfLocalTargetGone(player,
                    ecSession.getSessionType() == EnderchestSession.SessionType.LOCAL_ONLINE, ecSession.getTargetUuid())) {
                event.setCancelled(true);
                return;
            }
            if (!ecSession.isEditable()) {
                event.setCancelled(true);
            } else {
                markEcModified(player, ecSession);
            }
            return;
        }

        essentialsManager.scheduleTargetSync(player);
    }

    private boolean closeIfLocalTargetGone(Player viewer, boolean local, UUID targetUuid) {
        if (!local) return false;
        Player target = Bukkit.getPlayer(targetUuid);
        if (target != null && target.isOnline()) return false;
        viewer.closeInventory();
        return true;
    }

    private void markInvseeModified(Player viewer, InvseeSession session) {
        session.setModifiedByViewer(true);
        plugin.getScheduler().runEntityLater(viewer, () -> essentialsManager.syncFromGuiToTarget(session), 1L);
    }

    private void markEcModified(Player viewer, EnderchestSession session) {
        session.setModifiedByViewer(true);
        plugin.getScheduler().runEntityLater(viewer, () -> essentialsManager.syncFromEcGuiToTarget(session, false), 1L);
    }

    // The item has already left the inventory when this fires, so sync immediately: the GUI must
    // not keep showing an item that now exists as a dropped entity.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        essentialsManager.syncFromTargetToGuis(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            essentialsManager.scheduleTargetSync(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        essentialsManager.scheduleTargetSync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        essentialsManager.scheduleTargetSync(event.getPlayer());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        UUID viewerUuid = player.getUniqueId();

        // 1. Prevent cursor item loss or ghost dupe on close: return cursor item safely to chest or player/ground
        ItemStack cursor = event.getView().getCursor();
        if (cursor != null && !cursor.getType().isAir() && cursor.getAmount() > 0) {
            EnderchestSession activeEc = essentialsManager.getEnderchestSession(viewerUuid);
            InvseeSession activeInv = essentialsManager.getInvseeSession(viewerUuid);

            if (activeEc != null || activeInv != null) {
                ItemStack returning = cursor.clone();
                event.getView().setCursor(null);
                player.setItemOnCursor(null);

                ItemStack leftover = returning;
                if (activeEc != null) {
                    Player target = Bukkit.getPlayer(activeEc.getTargetUuid());
                    boolean targetCanReceive = (target != null && target.isOnline())
                            || viewerUuid.equals(activeEc.getTargetUuid())
                            || activeEc.getSessionType() == EnderchestSession.SessionType.CROSS_SERVER;
                    if (activeEc.isEditable() && targetCanReceive) {
                        leftover = activeEc.getInventory().addItem(returning).values().stream().findFirst().orElse(null);
                        activeEc.setModifiedByViewer(true);
                    }
                } else {
                    Player target = Bukkit.getPlayer(activeInv.getTargetUuid());
                    boolean targetCanReceive = (target != null && target.isOnline())
                            || activeInv.getSessionType() == InvseeSession.SessionType.CROSS_SERVER;
                    if (activeInv.isEditable() && targetCanReceive) {
                        leftover = addItemToStorageSlots(activeInv.getInventory(), returning);
                        activeInv.setModifiedByViewer(true);
                    }
                }
                InvseeLayout.giveOrDrop(player, leftover);
            }
        }

        // 2. Dispatch changes made during invsee session
        InvseeSession invSession = essentialsManager.removeInvseeSession(viewerUuid);
        if (invSession != null && invSession.isEditable() && invSession.isModifiedByViewer()) {
            if (invSession.getSessionType() == InvseeSession.SessionType.LOCAL_ONLINE) {
                essentialsManager.syncFromGuiToTarget(invSession);
            } else if (invSession.getSessionType() == InvseeSession.SessionType.CROSS_SERVER) {
                invSession.setModifiedByViewer(false);
                essentialsManager.getCrossServerService().dispatchInvseeSave(
                        viewerUuid,
                        invSession.getTargetUuid(),
                        invSession.getTargetName(),
                        InvseeLayout.fromGui(invSession.getInventory())
                );
            }
        }

        // 3. Dispatch changes made during ender chest session immediately
        EnderchestSession ecSession = essentialsManager.removeEnderchestSession(viewerUuid);
        if (ecSession != null && ecSession.isEditable() && ecSession.isModifiedByViewer()) {
            essentialsManager.syncFromEcGuiToTarget(ecSession, true);
        }

        if (event.getInventory().getType() == InventoryType.ENDER_CHEST) {
            essentialsManager.syncFromTargetEcToGuis(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.ENDER_CHEST && event.getPlayer() instanceof Player player) {
            essentialsManager.syncFromTargetEcToGuis(player);
        }
    }

    private ItemStack addItemToStorageSlots(Inventory inv, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return null;
        int maxStack = item.getMaxStackSize();
        // First, merge with existing compatible stacks in storage slots (0-35)
        for (int i = 0; i < 36; i++) {
            ItemStack slotItem = inv.getItem(i);
            if (slotItem != null && slotItem.isSimilar(item) && slotItem.getAmount() < maxStack) {
                int available = maxStack - slotItem.getAmount();
                int toAdd = Math.min(available, item.getAmount());
                slotItem.setAmount(slotItem.getAmount() + toAdd);
                item.setAmount(item.getAmount() - toAdd);
                if (item.getAmount() <= 0) return null;
            }
        }
        // Second, place in empty slots in storage slots (0-35)
        for (int i = 0; i < 36; i++) {
            ItemStack slotItem = inv.getItem(i);
            if (slotItem == null || slotItem.getType().isAir()) {
                int toAdd = Math.min(item.getAmount(), maxStack);
                ItemStack placed = item.clone();
                placed.setAmount(toAdd);
                inv.setItem(i, placed);
                item.setAmount(item.getAmount() - toAdd);
                if (item.getAmount() <= 0) return null;
            }
        }
        return item; // Leftover that could not fit
    }
}
