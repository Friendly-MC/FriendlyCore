package dev.lyy.friendlycore.modules.essentials;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.essentials.crossserver.EssentialsCrossServerService;
import dev.lyy.friendlycore.modules.essentials.model.EnderchestSession;
import dev.lyy.friendlycore.modules.essentials.model.InvseeSession;
import dev.lyy.friendlycore.modules.essentials.util.InvseeLayout;
import dev.lyy.friendlycore.modules.essentials.vanish.VanishManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central manager for Admin Essentials features including god mode, inventory inspection,
 * ender chest viewing, and inventory clearing.
 *
 * <p>Inspection GUIs are copies. Each session keeps a baseline of what its GUI last showed; viewer
 * edits are written back slot by slot, and only where the target still holds the baseline item
 * ({@link InvseeLayout#applyEdits}). The GUIs are refreshed from the target after every write,
 * on inventory events, and every {@value #REFRESH_TICKS} ticks while any session is open.</p>
 */
public class EssentialsManager {

    private static final long REFRESH_TICKS = 10L;

    private final FriendlyCore plugin;
    private final Set<UUID> godModePlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, InvseeSession> invseeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, EnderchestSession> enderchestSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> targetToInvseeViewers = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> targetToEcViewers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> clearConfirmations = new ConcurrentHashMap<>();
    private final EssentialsCrossServerService crossServerService;
    private final VanishManager vanishManager;
    private Object refreshTask;

    public EssentialsManager(FriendlyCore plugin) {
        this.plugin = plugin;
        this.crossServerService = new EssentialsCrossServerService(plugin, this);
        this.vanishManager = new VanishManager(plugin, this);
    }

    public void initialize() {
        this.vanishManager.initialize();
        reload();
        refreshTask = plugin.getScheduler().runGlobalTimer(this::refreshOpenSessions, REFRESH_TICKS, REFRESH_TICKS);
        plugin.getLogger().info("Essentials Module initialized successfully.");
    }

    public void reload() {
        if (vanishManager != null) {
            vanishManager.reload();
        }
    }

    public void shutdown() {
        if (refreshTask != null) {
            plugin.getScheduler().cancel(refreshTask);
            refreshTask = null;
        }
        if (crossServerService != null) {
            crossServerService.shutdown();
        }
        if (vanishManager != null) {
            vanishManager.shutdown();
        }
        godModePlayers.clear();

        // The plugin is already disabled here, so close events never reach our listener and entity
        // tasks are refused: return cursors and write pending edits back inline.
        for (EnderchestSession session : new ArrayList<>(enderchestSessions.values())) {
            closeViewerNow(session.getViewerUuid());
            if (session.isEditable() && session.isModifiedByViewer()) {
                syncFromEcGuiToTarget(session, true);
            }
        }
        enderchestSessions.clear();

        for (InvseeSession session : new ArrayList<>(invseeSessions.values())) {
            closeViewerNow(session.getViewerUuid());
            if (session.isEditable() && session.isModifiedByViewer()) {
                syncFromGuiToTarget(session);
            }
        }
        invseeSessions.clear();
        targetToInvseeViewers.clear();
        targetToEcViewers.clear();
    }

    private void closeViewerNow(UUID viewerUuid) {
        Player viewer = Bukkit.getPlayer(viewerUuid);
        if (viewer != null && viewer.isOnline()) {
            InvseeLayout.returnCursor(viewer);
            viewer.closeInventory();
        }
    }

    public VanishManager getVanishManager() {
        return vanishManager;
    }

    public boolean isVanished(UUID uuid) {
        return vanishManager != null && vanishManager.isVanished(uuid);
    }

    public boolean canSee(Player viewer, Player target) {
        return vanishManager == null || vanishManager.canSee(viewer, target);
    }

    public FriendlyCore getPlugin() {
        return plugin;
    }

    public EssentialsCrossServerService getCrossServerService() {
        return crossServerService;
    }

    public FileConfiguration getConfig() {
        return plugin.getConfigService().get("essentials.yml");
    }

    public FileConfiguration getMessages() {
        return plugin.getConfigService().getMessages();
    }

    public void sendMessage(CommandSender sender, String messageKey, Map<String, String> placeholders) {
        if (sender == null) return;
        String template = getMessages().getString("essentials." + messageKey, "<gray>" + messageKey);
        plugin.getTextService().sendMessage(sender, template, placeholders);
    }

    public boolean isGodMode(UUID uuid) {
        return uuid != null && godModePlayers.contains(uuid);
    }

    public void setGodMode(UUID uuid, boolean enabled) {
        if (uuid == null) return;
        if (enabled) {
            godModePlayers.add(uuid);
        } else {
            godModePlayers.remove(uuid);
        }
    }

    public void registerInvseeSession(UUID viewerUuid, InvseeSession session) {
        if (viewerUuid != null && session != null) {
            if (session.getBaseline() == null && session.getInventory() != null
                    && session.getInventory().getSize() == InvseeLayout.GUI_SIZE) {
                session.setBaseline(InvseeLayout.fromGui(session.getInventory()));
            }
            invseeSessions.put(viewerUuid, session);
            targetToInvseeViewers.computeIfAbsent(session.getTargetUuid(), k -> ConcurrentHashMap.newKeySet()).add(viewerUuid);
        }
    }

    public InvseeSession getInvseeSession(UUID viewerUuid) {
        return viewerUuid != null ? invseeSessions.get(viewerUuid) : null;
    }

    public InvseeSession removeInvseeSession(UUID viewerUuid) {
        if (viewerUuid == null) return null;
        InvseeSession session = invseeSessions.remove(viewerUuid);
        if (session != null) {
            unindex(targetToInvseeViewers, session.getTargetUuid(), viewerUuid);
        }
        return session;
    }

    public void registerEnderchestSession(UUID viewerUuid, EnderchestSession session) {
        if (viewerUuid != null && session != null) {
            if (session.getBaseline() == null && session.getInventory() != null) {
                session.setBaseline(InvseeLayout.copyAll(session.getInventory().getContents()));
            }
            enderchestSessions.put(viewerUuid, session);
            targetToEcViewers.computeIfAbsent(session.getTargetUuid(), k -> ConcurrentHashMap.newKeySet()).add(viewerUuid);
        }
    }

    public EnderchestSession getEnderchestSession(UUID viewerUuid) {
        return viewerUuid != null ? enderchestSessions.get(viewerUuid) : null;
    }

    public EnderchestSession removeEnderchestSession(UUID viewerUuid) {
        if (viewerUuid == null) return null;
        EnderchestSession session = enderchestSessions.remove(viewerUuid);
        if (session != null) {
            unindex(targetToEcViewers, session.getTargetUuid(), viewerUuid);
        }
        return session;
    }

    private static void unindex(Map<UUID, Set<UUID>> index, UUID targetUuid, UUID viewerUuid) {
        index.computeIfPresent(targetUuid, (k, viewers) -> {
            viewers.remove(viewerUuid);
            return viewers.isEmpty() ? null : viewers;
        });
    }

    public void cleanupTargetSessions(UUID targetUuid) {
        if (targetUuid == null) return;
        targetToInvseeViewers.remove(targetUuid);
        targetToEcViewers.remove(targetUuid);
    }

    public Set<UUID> getTargetEcViewers(UUID targetUuid) {
        if (targetUuid == null) return Collections.emptySet();
        Set<UUID> viewers = targetToEcViewers.get(targetUuid);
        return viewers != null ? Collections.unmodifiableSet(viewers) : Collections.emptySet();
    }

    public Set<UUID> getTargetInvseeViewers(UUID targetUuid) {
        if (targetUuid == null) return Collections.emptySet();
        Set<UUID> viewers = targetToInvseeViewers.get(targetUuid);
        return viewers != null ? Collections.unmodifiableSet(viewers) : Collections.emptySet();
    }

    /** Runs inline when the caller already owns the entity (always on Paper's main thread), else on its thread. */
    private void onEntityThread(Entity entity, Runnable task) {
        if (!plugin.getScheduler().isFolia() || Bukkit.isOwnedByCurrentRegion(entity)) {
            task.run();
        } else {
            plugin.getScheduler().runEntity(entity, task);
        }
    }

    /** Writes the viewer's invsee edits into the live target. Call on the viewer's thread. */
    public void syncFromGuiToTarget(InvseeSession session) {
        if (session == null || !session.isEditable() || session.getSessionType() != InvseeSession.SessionType.LOCAL_ONLINE) return;
        Inventory inv = session.getInventory();
        if (inv == null || inv.getSize() != InvseeLayout.GUI_SIZE) return;

        ItemStack[] base = session.getBaseline();
        ItemStack[] edited = InvseeLayout.fromGui(inv);
        session.setBaseline(edited);
        session.setModifiedByViewer(false);
        if (base == null) return;

        Player target = Bukkit.getPlayer(session.getTargetUuid());
        if (target == null || !target.isOnline()) return;
        onEntityThread(target, () -> {
            if (!target.isOnline()) return;
            PlayerInventory targetInv = target.getInventory();
            InvseeLayout.applyEdits(base, edited, i -> InvseeLayout.get(targetInv, i), (i, item) -> InvseeLayout.set(targetInv, i, item));
            target.updateInventory();
            // Conflicting slots kept the target's item; show every viewer the authoritative state.
            syncFromTargetToGuis(target);
        });
    }

    public void syncFromEcGuiToTarget(EnderchestSession session) {
        syncFromEcGuiToTarget(session, false);
    }

    /** Writes the viewer's ender chest edits into the target. Call on the viewer's thread. */
    public void syncFromEcGuiToTarget(EnderchestSession session, boolean isClosing) {
        if (session == null || !session.isEditable()) return;
        Inventory inv = session.getInventory();
        if (inv == null) return;

        if (session.getSessionType() == EnderchestSession.SessionType.CROSS_SERVER) {
            if (isClosing) {
                session.setModifiedByViewer(false);
                crossServerService.dispatchEnderchestSave(session.getViewerUuid(), session.getTargetUuid(),
                        session.getTargetName(), inv.getContents());
            }
            return;
        }
        if (session.getSessionType() != EnderchestSession.SessionType.LOCAL_ONLINE) return;

        ItemStack[] base = session.getBaseline();
        ItemStack[] edited = InvseeLayout.copyAll(inv.getContents());
        session.setBaseline(edited);
        session.setModifiedByViewer(false);
        if (base == null) return;

        Player target = Bukkit.getPlayer(session.getTargetUuid());
        if (target == null || !target.isOnline()) {
            if (isClosing) {
                plugin.getLogger().warning("Could not write back Ender Chest changes for "
                        + session.getTargetName() + " (UUID: " + session.getTargetUuid() + "): player is offline.");
            }
            return;
        }
        onEntityThread(target, () -> {
            if (!target.isOnline()) return;
            Inventory ec = target.getEnderChest();
            InvseeLayout.applyEdits(base, edited, ec::getItem, ec::setItem);
            syncFromTargetEcToGuis(target);
        });
    }

    /** Refreshes every open invsee GUI of this target. Call on the target's thread. */
    public void syncFromTargetToGuis(Player target) {
        if (target == null || !target.isOnline() || !targetToInvseeViewers.containsKey(target.getUniqueId())) return;
        pushToInvseeGuis(target.getUniqueId(), InvseeLayout.fromPlayer(target.getInventory()));
    }

    /** Re-syncs the target's viewers one tick later, after the triggering event has been applied. */
    public void scheduleTargetSync(Player target) {
        if (target == null || !targetToInvseeViewers.containsKey(target.getUniqueId())) return;
        plugin.getScheduler().runEntityLater(target, () -> syncFromTargetToGuis(target), 1L);
    }

    /**
     * Shows viewers the inventory the target will have after death, before the drops spawn,
     * so nobody can take an item from the GUI that is also lying on the ground.
     */
    public void handleTargetDeath(Player target, boolean keepInventory) {
        if (target == null) return;
        if (keepInventory) {
            syncFromTargetToGuis(target);
        } else {
            // ponytail: shows an empty inventory even for items kept via getItemsToKeep(); the next refresh corrects it.
            pushToInvseeGuis(target.getUniqueId(), new ItemStack[InvseeLayout.SIZE]);
        }
    }

    private void pushToInvseeGuis(UUID targetUuid, ItemStack[] snapshot) {
        Set<UUID> viewers = targetToInvseeViewers.get(targetUuid);
        if (viewers == null) return;
        for (UUID viewerUuid : viewers) {
            Player viewer = Bukkit.getPlayer(viewerUuid);
            InvseeSession session = invseeSessions.get(viewerUuid);
            if (viewer == null || !viewer.isOnline() || session == null || session.isModifiedByViewer()
                    || session.getSessionType() != InvseeSession.SessionType.LOCAL_ONLINE) continue;
            onEntityThread(viewer, () -> {
                InvseeSession active = invseeSessions.get(viewerUuid);
                // Pending viewer edits win; their write-back re-syncs this GUI right after.
                if (!viewer.isOnline() || active == null || active.isModifiedByViewer()) return;
                Inventory inv = active.getInventory();
                if (inv == null || inv.getSize() != InvseeLayout.GUI_SIZE) return;
                InvseeLayout.toGui(inv, snapshot);
                active.setBaseline(snapshot);
            });
        }
    }

    /** Refreshes every open ender chest GUI of this target. Call on the target's thread. */
    public void syncFromTargetEcToGuis(Player target) {
        if (target == null || !target.isOnline()) return;
        Set<UUID> viewers = targetToEcViewers.get(target.getUniqueId());
        if (viewers == null || viewers.isEmpty()) return;

        ItemStack[] snapshot = InvseeLayout.copyAll(target.getEnderChest().getContents());
        for (UUID viewerUuid : viewers) {
            Player viewer = Bukkit.getPlayer(viewerUuid);
            EnderchestSession session = enderchestSessions.get(viewerUuid);
            if (viewer == null || !viewer.isOnline() || session == null || session.isModifiedByViewer()
                    || session.getSessionType() != EnderchestSession.SessionType.LOCAL_ONLINE) continue;
            onEntityThread(viewer, () -> {
                EnderchestSession active = enderchestSessions.get(viewerUuid);
                if (!viewer.isOnline() || active == null || active.isModifiedByViewer() || active.getInventory() == null) return;
                Inventory inv = active.getInventory();
                for (int i = 0; i < Math.min(inv.getSize(), snapshot.length); i++) {
                    if (!InvseeLayout.same(inv.getItem(i), snapshot[i])) inv.setItem(i, InvseeLayout.copy(snapshot[i]));
                }
                active.setBaseline(snapshot);
            });
        }
    }

    // Catches inventory changes no event covers (block place, durability, crafting...).
    private void refreshOpenSessions() {
        if (targetToInvseeViewers.isEmpty() && targetToEcViewers.isEmpty()) return;
        Set<UUID> targets = new HashSet<>(targetToInvseeViewers.keySet());
        targets.addAll(targetToEcViewers.keySet());
        for (UUID targetUuid : targets) {
            Player target = Bukkit.getPlayer(targetUuid);
            if (target == null || !target.isOnline()) continue;
            plugin.getScheduler().runEntity(target, () -> {
                syncFromTargetToGuis(target);
                syncFromTargetEcToGuis(target);
            });
        }
    }

    /**
     * Handles target player disconnect during active inspection sessions: writes pending viewer
     * edits into the target before their data is saved, then closes the viewers' GUIs.
     * Runs on the quitting target's thread.
     */
    public void handleTargetQuit(Player target) {
        if (target == null) return;
        UUID targetUuid = target.getUniqueId();

        for (UUID viewerUuid : new ArrayList<>(getTargetEcViewers(targetUuid))) {
            EnderchestSession session = enderchestSessions.get(viewerUuid);
            if (session == null) continue;
            ItemStack[] base = session.getBaseline();
            if (session.getSessionType() == EnderchestSession.SessionType.LOCAL_ONLINE
                    && session.isEditable() && session.isModifiedByViewer() && base != null && session.getInventory() != null) {
                ItemStack[] edited = InvseeLayout.copyAll(session.getInventory().getContents());
                session.setBaseline(edited);
                session.setModifiedByViewer(false);
                Inventory ec = target.getEnderChest();
                InvseeLayout.applyEdits(base, edited, ec::getItem, ec::setItem);
            }
            closeViewerLater(viewerUuid, "enderchest.target-disconnected", target.getName(), true);
        }

        for (UUID viewerUuid : new ArrayList<>(getTargetInvseeViewers(targetUuid))) {
            InvseeSession session = invseeSessions.get(viewerUuid);
            if (session == null) continue;
            ItemStack[] base = session.getBaseline();
            Inventory inv = session.getInventory();
            if (session.getSessionType() == InvseeSession.SessionType.LOCAL_ONLINE
                    && session.isEditable() && session.isModifiedByViewer() && base != null
                    && inv != null && inv.getSize() == InvseeLayout.GUI_SIZE) {
                ItemStack[] edited = InvseeLayout.fromGui(inv);
                session.setBaseline(edited);
                session.setModifiedByViewer(false);
                PlayerInventory targetInv = target.getInventory();
                InvseeLayout.applyEdits(base, edited, i -> InvseeLayout.get(targetInv, i), (i, item) -> InvseeLayout.set(targetInv, i, item));
            }
            closeViewerLater(viewerUuid, "invsee.target-disconnected", target.getName(), false);
        }
    }

    private void closeViewerLater(UUID viewerUuid, String messageKey, String targetName, boolean enderchest) {
        Player viewer = Bukkit.getPlayer(viewerUuid);
        Runnable forget = () -> {
            if (enderchest) removeEnderchestSession(viewerUuid);
            else removeInvseeSession(viewerUuid);
        };
        if (viewer == null || !viewer.isOnline()) {
            forget.run();
            return;
        }
        plugin.getScheduler().runEntity(viewer, () -> {
            if (!viewer.isOnline()) {
                forget.run();
                return;
            }
            InvseeLayout.returnCursor(viewer);
            viewer.closeInventory();
            sendMessage(viewer, messageKey, Map.of("%player%", targetName));
        }, forget);
    }

    public float getMaxFlySpeed() {
        return (float) getConfig().getDouble("speed.max-fly-speed", 1.0);
    }

    public float getMaxWalkSpeed() {
        return (float) getConfig().getDouble("speed.max-walk-speed", 1.0);
    }

    public boolean isClearArmorByDefault() {
        return getConfig().getBoolean("clearinventory.clear-armor-by-default", false);
    }

    public boolean isConfirmSelfClearEnabled() {
        return getConfig().getBoolean("clearinventory.confirm-self-clear", false);
    }

    public int getConfirmTimeoutSeconds() {
        return getConfig().getInt("clearinventory.confirm-timeout-seconds", 10);
    }

    public boolean isPendingConfirmation(UUID uuid) {
        if (uuid == null) return false;
        Long timestamp = clearConfirmations.get(uuid);
        if (timestamp == null) return false;
        long maxAgeMs = getConfirmTimeoutSeconds() * 1000L;
        if (System.currentTimeMillis() - timestamp > maxAgeMs) {
            clearConfirmations.remove(uuid);
            return false;
        }
        return true;
    }

    public void setPendingConfirmation(UUID uuid) {
        if (uuid != null) {
            clearConfirmations.put(uuid, System.currentTimeMillis());
        }
    }

    public void clearPendingConfirmation(UUID uuid) {
        if (uuid != null) {
            clearConfirmations.remove(uuid);
        }
    }

    /**
     * Clears inventory of target player with optional material filter, amount limit, and armor inclusion.
     * Note: Must be invoked on target's entity thread on Folia.
     *
     * @param target The target player
     * @param filter Material to remove, or null to remove all
     * @param maxAmount Max items to remove (-1 or <= 0 for unlimited)
     * @param includeArmor Whether to clear armor in full clear
     * @param armorOnly Whether to clear only armor
     * @return Total count of individual items cleared
     */
    public int clearInventory(Player target, Material filter, int maxAmount, boolean includeArmor, boolean armorOnly) {
        if (target == null || !target.isOnline()) return 0;

        int removedCount = 0;
        PlayerInventory inv = target.getInventory();

        if (filter == null) {
            if (armorOnly) {
                removedCount += countItems(inv.getArmorContents());
                inv.setArmorContents(new ItemStack[4]);
            } else {
                removedCount += countItems(inv.getStorageContents());
                inv.setStorageContents(new ItemStack[36]);

                ItemStack offhand = inv.getItemInOffHand();
                if (offhand != null && offhand.getType() != Material.AIR) {
                    removedCount += offhand.getAmount();
                    inv.setItemInOffHand(null);
                }

                if (includeArmor) {
                    removedCount += countItems(inv.getArmorContents());
                    inv.setArmorContents(new ItemStack[4]);
                }
            }
        } else {
            int[] remaining = {(maxAmount > 0) ? maxAmount : Integer.MAX_VALUE};

            ItemStack[] storage = inv.getStorageContents();
            removedCount += removeMatching(storage, filter, remaining);
            inv.setStorageContents(storage);

            if (remaining[0] > 0) {
                ItemStack[] offhand = {inv.getItemInOffHand()};
                int removed = removeMatching(offhand, filter, remaining);
                if (removed > 0) {
                    removedCount += removed;
                    inv.setItemInOffHand(offhand[0]);
                }
            }

            if ((includeArmor || armorOnly) && remaining[0] > 0) {
                ItemStack[] armor = inv.getArmorContents();
                removedCount += removeMatching(armor, filter, remaining);
                inv.setArmorContents(armor);
            }
        }

        target.updateInventory();
        syncFromTargetToGuis(target);
        return removedCount;
    }

    private static int countItems(ItemStack[] items) {
        int count = 0;
        for (ItemStack item : items) {
            if (item != null && item.getType() != Material.AIR) count += item.getAmount();
        }
        return count;
    }

    /** Removes up to {@code remaining[0]} items of {@code filter} from the array in place. */
    private static int removeMatching(ItemStack[] items, Material filter, int[] remaining) {
        int removed = 0;
        for (int i = 0; i < items.length && remaining[0] > 0; i++) {
            ItemStack item = items[i];
            if (item == null || item.getType() != filter) continue;
            int amt = item.getAmount();
            if (amt <= remaining[0]) {
                items[i] = null;
                removed += amt;
                remaining[0] -= amt;
            } else {
                item.setAmount(amt - remaining[0]);
                removed += remaining[0];
                remaining[0] = 0;
            }
        }
        return removed;
    }
}
