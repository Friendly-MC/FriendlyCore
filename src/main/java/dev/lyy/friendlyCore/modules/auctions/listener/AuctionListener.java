package dev.lyy.friendlycore.modules.auctions.listener;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.auctions.AuctionManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listens for chat queries, sign search input, player join offline delivery checks,
 * quit safety cleanup, and GUI inventory click/drag/close events.
 */
public class AuctionListener implements Listener {

    private final FriendlyCore plugin;
    private final AuctionManager auctionManager;

    public AuctionListener(FriendlyCore plugin, AuctionManager auctionManager) {
        this.plugin = plugin;
        this.auctionManager = auctionManager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVirtualSign(io.papermc.paper.event.packet.UncheckedSignChangeEvent event) {
        var input = auctionManager.getSignInputManager();
        var session = input.getSession(event.getPlayer().getUniqueId());
        if (session == null) return;
        var position = event.getEditedBlockPosition();
        var loc = session.location();
        if (!event.getPlayer().getWorld().equals(loc.getWorld()) || event.getSide() != org.bukkit.block.sign.Side.FRONT
                || position.blockX() != loc.getBlockX() || position.blockY() != loc.getBlockY() || position.blockZ() != loc.getBlockZ()) return;
        event.setCancelled(true);
        input.completeSignInput(event.getPlayer(), event.lines().isEmpty() ? net.kyori.adventure.text.Component.empty() : event.lines().getFirst());
    }

    @EventHandler
    public void onPrepareAnvil(org.bukkit.event.inventory.PrepareAnvilEvent event) {
        var session = auctionManager.getSignInputManager().getAnvil(event.getView().getPlayer().getUniqueId());
        if (session == null || !session.view().getTopInventory().equals(event.getInventory())) return;
        var item = event.getInventory().getItem(0);
        if (item != null) event.setResult(item.clone());
        event.getView().setRepairCost(0);
        event.getView().setRepairItemCountCost(0);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAnvilClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        var input = auctionManager.getSignInputManager();
        var session = input.getAnvil(player.getUniqueId());
        if (session == null || !session.view().getTopInventory().equals(event.getView().getTopInventory())) return;
        event.setCancelled(true);
        if (event.getRawSlot() == 2) plugin.getScheduler().runEntityLater(player, () -> input.completeAnvil(player), 1L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAnvilDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        var session = auctionManager.getSignInputManager().getAnvil(event.getWhoClicked().getUniqueId());
        if (session != null && session.view().getTopInventory().equals(event.getView().getTopInventory())) event.setCancelled(true);
    }

    @EventHandler
    public void onAnvilClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        var session = auctionManager.getSignInputManager().getAnvil(event.getPlayer().getUniqueId());
        if (session != null && session.view().getTopInventory().equals(event.getInventory())) {
            auctionManager.getSignInputManager().closeAnvil((Player) event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String plainMessage = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        if (auctionManager.handleSearchChatInput(player, plainMessage)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        auctionManager.checkPendingDeliveries(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        auctionManager.onPlayerQuit(event.getPlayer().getUniqueId());
    }

}
