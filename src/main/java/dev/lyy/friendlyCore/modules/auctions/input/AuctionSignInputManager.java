package dev.lyy.friendlycore.modules.auctions.input;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.auctions.AuctionManager;
import io.papermc.paper.math.Position;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Client-only sign input, anvil input and chat fallback for Paper/Folia 1.21.11+. */
public class AuctionSignInputManager {
    public record SignSession(UUID playerUuid, Location location, Consumer<String> callback, Object timeoutTask) {}
    public record AnvilSession(AnvilView view, Consumer<String> callback) {}
    private final FriendlyCore plugin;
    private final Map<UUID, SignSession> activeSignSessions = new ConcurrentHashMap<>();
    private final Map<UUID, AnvilSession> anvils = new ConcurrentHashMap<>();
    private final Map<UUID, Consumer<String>> pendingChatInputs = new ConcurrentHashMap<>();

    public AuctionSignInputManager(FriendlyCore plugin, AuctionManager auctionManager) { this.plugin = plugin; }

    public void openSearchInput(Player player, Consumer<String> callback) {
        if (player == null || !player.isOnline()) return;
        var cfg = plugin.getConfigService().get("modules/auctions/config.yml");
        String mode = cfg.getString("search-mode", "SIGN").toUpperCase(Locale.ROOT);
        if (mode.equals("ANVIL")) openAnvilInput(player, callback);
        else if (mode.equals("SIGN") && !cfg.getBoolean("disable-sign-input", false)) openSignInput(player, callback);
        else openChatInput(player, callback);
    }

    public void openSignInput(Player player, Consumer<String> callback) {
        cleanupPlayer(player.getUniqueId());
        player.closeInventory();
        Location loc = player.getLocation().getBlock().getLocation();
        try {
            var messages = plugin.getConfigService().get("modules/auctions/messages.yml");
            List<String> raw = messages.getStringList("input.sign");
            List<Component> lines = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) lines.add(plugin.getTextService().parse(i < raw.size() ? raw.get(i) : ""));
            Object timeout = plugin.getScheduler().runEntityLater(player, () -> cleanupPlayer(player.getUniqueId()), 600L);
            activeSignSessions.put(player.getUniqueId(), new SignSession(player.getUniqueId(), loc, callback, timeout));
            player.sendBlockChange(loc, Material.OAK_SIGN.createBlockData());
            player.sendSignChange(loc, lines);
            player.openVirtualSign(Position.block(loc), Side.FRONT);
        } catch (RuntimeException unavailable) {
            cleanupPlayer(player.getUniqueId());
            openChatInput(player, callback);
        }
    }

    private void openAnvilInput(Player player, Consumer<String> callback) {
        cleanupPlayer(player.getUniqueId());
        player.closeInventory();
        var messages = plugin.getConfigService().get("modules/auctions/messages.yml");
        AnvilView view = MenuType.ANVIL.create(player, plugin.getTextService().parse(messages.getString("input.anvil.title", "Search Here")));
        Material material = Material.matchMaterial(messages.getString("input.anvil.item.material", "PAPER"));
        ItemStack paper = new ItemStack(material != null ? material : Material.PAPER);
        var meta = paper.getItemMeta();
        meta.displayName(plugin.getTextService().parse(messages.getString("input.anvil.item.name", "SEARCH HERE")));
        meta.lore(messages.getStringList("input.anvil.item.lore").stream().map(plugin.getTextService()::parse).toList());
        meta.setEnchantmentGlintOverride(messages.getBoolean("input.anvil.item.glow", true));
        paper.setItemMeta(meta);
        anvils.put(player.getUniqueId(), new AnvilSession(view, callback));
        view.getTopInventory().setItem(0, paper);
        player.openInventory(view);
    }

    public AnvilSession getAnvil(UUID uuid) { return anvils.get(uuid); }

    public void completeAnvil(Player player) {
        AnvilSession session = anvils.remove(player.getUniqueId());
        if (session == null) return;
        String query = session.view().getRenameText();
        session.view().getTopInventory().clear();
        player.closeInventory();
        plugin.getScheduler().runEntityLater(player, () -> session.callback().accept(query == null ? "" : query.trim()), 1L);
    }

    public void closeAnvil(Player player) {
        AnvilSession session = anvils.remove(player.getUniqueId());
        if (session != null) session.view().getTopInventory().clear();
    }

    public void openChatInput(Player player, Consumer<String> callback) {
        cleanupPlayer(player.getUniqueId());
        player.closeInventory();
        var messages = plugin.getConfigService().get("modules/auctions/messages.yml");
        List<String> lines = messages.getStringList("input.chat");
        if (lines.isEmpty()) lines = List.of("What do you want to search for? (type cancel to stop)");
        for (String line : lines) plugin.getTextService().sendMessage(player, line);
        pendingChatInputs.put(player.getUniqueId(), callback);
    }

    public boolean completeSignInput(Player player, Component firstLine) {
        return completeSignInput(player, PlainTextComponentSerializer.plainText().serialize(firstLine));
    }

    public boolean completeSignInput(Player player, String query) {
        SignSession session = activeSignSessions.remove(player.getUniqueId());
        if (session == null) return false;
        restoreClient(player, session);
        plugin.getScheduler().runEntity(player, () -> session.callback().accept(query == null ? "" : query.trim()));
        return true;
    }

    public boolean handleChatInput(Player player, String message) {
        Consumer<String> callback = pendingChatInputs.remove(player.getUniqueId());
        if (callback == null) return false;
        String query = message.trim();
        plugin.getScheduler().runEntity(player, () -> callback.accept(query.equalsIgnoreCase("cancel") ? "" : query));
        return true;
    }

    public SignSession getSession(UUID uuid) { return activeSignSessions.get(uuid); }

    private void restoreClient(Player player, SignSession session) {
        if (session.timeoutTask() != null) plugin.getScheduler().cancel(session.timeoutTask());
        plugin.getScheduler().runLocation(session.location(), () -> {
            var data = session.location().getBlock().getBlockData();
            plugin.getScheduler().runEntity(player, () -> {
                if (player.getWorld().equals(session.location().getWorld())) player.sendBlockChange(session.location(), data);
            });
        });
    }

    public void cleanupPlayer(UUID uuid) {
        pendingChatInputs.remove(uuid);
        SignSession sign = activeSignSessions.remove(uuid);
        Player player = plugin.getServer().getPlayer(uuid);
        if (sign != null) {
            if (sign.timeoutTask() != null) plugin.getScheduler().cancel(sign.timeoutTask());
            if (player != null) restoreClient(player, sign);
        }
        if (player != null) closeAnvil(player);
    }

    public void cleanupAll() {
        for (UUID uuid : activeSignSessions.keySet()) cleanupPlayer(uuid);
        for (UUID uuid : anvils.keySet()) cleanupPlayer(uuid);
        activeSignSessions.clear();
        anvils.clear();
        pendingChatInputs.clear();
    }
}
