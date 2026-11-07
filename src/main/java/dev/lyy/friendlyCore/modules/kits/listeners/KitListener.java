package dev.lyy.friendlycore.modules.kits.listeners;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.kits.gui.KitGuiManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Event listener for Kit player data lifecycle, GUI interactions, chat prompt inputs, and effects.
 */
public class KitListener implements Listener {

    private final FriendlyCore plugin;
    private final KitGuiManager guiManager;

    public KitListener(FriendlyCore plugin, KitGuiManager guiManager) {
        this.plugin = plugin;
        this.guiManager = guiManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean hasPlayedBefore = player.hasPlayedBefore();

        plugin.getKitManager().loadPlayer(player.getUniqueId(), player.getName()).thenAccept(data -> {
            if (!hasPlayedBefore) {
                plugin.getScheduler().runEntity(player, () -> {
                    if (player.isOnline()) {
                        plugin.getKitManager().giveFirstJoinKit(player);
                    }
                });
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        guiManager.closeSession(event.getPlayer().getUniqueId());
        if (plugin.getKitManager().getEditorManager() != null) {
            plugin.getKitManager().getEditorManager().removePlayer(event.getPlayer().getUniqueId());
        }
        plugin.getKitManager().uncachePlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPaperChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (plugin.getKitManager().getEditorManager() != null &&
                plugin.getKitManager().getEditorManager().hasActivePrompt(player.getUniqueId())) {
            event.setCancelled(true);
            String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
            plugin.getKitManager().getEditorManager().handleChatInput(player, message);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (plugin.getKitManager().getEditorManager() != null &&
                plugin.getKitManager().getEditorManager().hasActivePrompt(player.getUniqueId())) {
            event.setCancelled(true);
            String message = ChatColor.stripColor(event.getMessage()).trim();
            plugin.getKitManager().getEditorManager().handleChatInput(player, message);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        guiManager.handleClick(event);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof KitGuiManager.KitGuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof KitGuiManager.KitGuiHolder) {
            guiManager.closeSession(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework fw && fw.hasMetadata("friendlycore_no_damage")) {
            event.setCancelled(true);
        }
    }
}
