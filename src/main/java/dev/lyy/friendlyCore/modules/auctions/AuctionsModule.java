package dev.lyy.friendlycore.modules.auctions;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.auctions.commands.AuctionAdminCommand;
import dev.lyy.friendlycore.modules.auctions.commands.AuctionCommand;
import dev.lyy.friendlycore.modules.auctions.listener.AuctionListener;
import dev.lyy.friendlycore.modules.auctions.redis.AuctionPacketHandler;

/**
 * Module bootstrap for the auction house system. Registers commands, listeners,
 * and initializes auction lifecycle managers.
 */
public class AuctionsModule {

    private final FriendlyCore plugin;
    private final AuctionManager auctionManager;
    private final AuctionListener auctionListener;

    public AuctionsModule(FriendlyCore plugin) {
        this.plugin = plugin;
        this.auctionManager = new AuctionManager(plugin);
        this.auctionListener = new AuctionListener(plugin, auctionManager);
    }

    public void initialize() {
        auctionManager.initialize();
        plugin.getServer().getPluginManager().registerEvents(auctionListener, plugin);

        AuctionCommand ahCmd = new AuctionCommand(plugin, auctionManager);
        plugin.registerCommand("ah", ahCmd);
        plugin.registerCommand("auction", ahCmd);
        plugin.registerCommand("auctions", ahCmd);

        AuctionAdminCommand adminCmd = new AuctionAdminCommand(plugin, auctionManager);
        plugin.registerCommand("ahadmin", adminCmd);
        plugin.registerCommand("aha", adminCmd);

        if (!auctionManager.getEconomyService().hasEconomy()) {
            plugin.getLogger().warning("Auctions loaded, but trading requires a registered Vault economy provider.");
        } else {
            plugin.getLogger().info("Auctions module initialized (Vault only, no tax).");
        }
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()
                && !(plugin.getDatabaseManager() instanceof dev.lyy.friendlycore.common.database.MySQLDatabase)) {
            plugin.getLogger().severe("Cross-server auctions require all servers to share one MySQL database. Redis auction packets are disabled for local storage.");
        }
        plugin.getScheduler().runGlobalLater(() -> plugin.getServer().getPluginManager().callEvent(
                new dev.lyy.friendlycore.modules.auctions.events.AuctionLoadEvent()), 20L);
    }

    public void reload() {
        auctionManager.reload();
    }

    public void shutdown() {
        auctionManager.shutdown();
    }

    public AuctionManager getAuctionManager() {
        return auctionManager;
    }

    public AuctionPacketHandler getPacketHandler() {
        return auctionManager.getPacketHandler();
    }

    public dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator getSafetyValidator() {
        return auctionManager.getSafetyValidator();
    }
}
