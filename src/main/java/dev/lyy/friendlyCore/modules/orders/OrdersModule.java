package dev.lyy.friendlycore.modules.orders;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.orders.api.OrdersAPI;
import dev.lyy.friendlycore.modules.orders.commands.OrderAdminCommand;
import dev.lyy.friendlycore.modules.orders.commands.OrderCommand;
import dev.lyy.friendlycore.modules.orders.converter.DonutOrderConverter;
import dev.lyy.friendlycore.modules.orders.input.OrderInputManager;
import dev.lyy.friendlycore.modules.orders.listener.OrderPlayerListener;
import dev.lyy.friendlycore.modules.orders.redis.OrderPacketHandler;

public class OrdersModule {

    private final FriendlyCore plugin;
    private final OrderManager orderManager;
    private final OrderPlayerListener playerListener;
    private final OrderInputManager inputManager;
    private final DonutOrderConverter converter;

    public OrdersModule(FriendlyCore plugin) {
        this.plugin = plugin;
        this.orderManager = new OrderManager(plugin);
        this.playerListener = new OrderPlayerListener(plugin);
        this.inputManager = plugin.getTextInputManager();
        this.converter = new DonutOrderConverter(plugin);
    }

    public void initialize() {
        orderManager.initialize();
        OrdersAPI.init(plugin, orderManager);

        plugin.getServer().getPluginManager().registerEvents(playerListener, plugin);

        OrderCommand orderCmd = new OrderCommand(plugin, orderManager);
        plugin.registerCommand("order", orderCmd);
        plugin.registerCommand("orders", orderCmd);
        plugin.registerCommand("dorder", orderCmd);

        OrderAdminCommand adminCmd = new OrderAdminCommand(plugin, orderManager);
        plugin.registerCommand("orderadmin", adminCmd);
        plugin.registerCommand("ordersadmin", adminCmd);
        plugin.registerCommand("dorderadmin", adminCmd);

        plugin.getLogger().info("Orders module initialized successfully.");
    }

    public void reload() {
        orderManager.reload();
    }

    public void shutdown() {
        orderManager.shutdown();
    }

    public OrderManager getOrderManager() {
        return orderManager;
    }

    public OrderPacketHandler getPacketHandler() {
        return orderManager.getPacketHandler();
    }

    public OrderPlayerListener getPlayerListener() {
        return playerListener;
    }

    public OrderInputManager getInputManager() {
        return inputManager;
    }

    @Deprecated
    public OrderInputManager getInputPromptListener() {
        return inputManager;
    }

    public DonutOrderConverter getConverter() {
        return converter;
    }
}
