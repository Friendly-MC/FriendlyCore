package dev.lyy.friendlycore.common.hooks;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Bukkit;

/**
 * Central registry and lifecycle manager for third-party plugin integrations
 * (Vault, PlaceholderAPI, WorldGuard, GriefPrevention, Floodgate, etc.).
 */
public class HookManager {

    private final FriendlyCore plugin;
    private final ProtectionHook protectionHook;
    private final AutoPickupHook autoPickupHook;
    private final McDataBridgeHook mcDataBridgeHook;
    private final FloodgateHook floodgateHook;
    private boolean vaultAvailable;
    private boolean placeholderApiAvailable;

    public HookManager(FriendlyCore plugin) {
        this.plugin = plugin;
        this.protectionHook = new ProtectionHook();
        this.autoPickupHook = new AutoPickupHook();
        this.mcDataBridgeHook = new McDataBridgeHook(plugin);
        this.floodgateHook = new FloodgateHook();
        checkHooks();
    }

    public void checkHooks() {
        this.vaultAvailable = Bukkit.getPluginManager().isPluginEnabled("Vault");
        this.placeholderApiAvailable = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        protectionHook.checkHooks();
        mcDataBridgeHook.checkHook();
        floodgateHook.checkHooks();
    }

    public ProtectionHook getProtectionHook() {
        return protectionHook;
    }

    public AutoPickupHook getAutoPickupHook() {
        return autoPickupHook;
    }

    public McDataBridgeHook getMcDataBridgeHook() {
        return mcDataBridgeHook;
    }

    public FloodgateHook getFloodgateHook() {
        return floodgateHook;
    }

    public boolean isVaultAvailable() {
        return vaultAvailable;
    }

    public boolean isVaultEconomyAvailable() {
        return vaultAvailable && Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class) != null;
    }

    public net.milkbowl.vault.economy.Economy getVaultEconomy() {
        if (!isVaultEconomyAvailable()) return null;
        var rsp = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
        return rsp != null ? rsp.getProvider() : null;
    }

    public boolean isPlaceholderApiAvailable() {
        return placeholderApiAvailable;
    }
}
