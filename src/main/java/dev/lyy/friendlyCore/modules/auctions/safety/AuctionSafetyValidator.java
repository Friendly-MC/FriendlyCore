package dev.lyy.friendlycore.modules.auctions.safety;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.Collections;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates auction items for safety, enforcing NBT serialization size limits (to prevent chunk-bans/crashes),
 * blacklisted materials/names/lores, concurrency locking, and safe inventory deliveries.
 */
public class AuctionSafetyValidator {

    private final FriendlyCore plugin;
    private final Set<UUID> creatingLock = ConcurrentHashMap.newKeySet();

    private int maxItemBytes = 5000;
    private int maxShulkerBytes = 20000;
    private int maxBundleBytes = 20000;
    private List<String> blacklistedMaterials = Collections.emptyList();
    private List<String> blacklistedNames = Collections.emptyList();
    private List<String> blacklistedLores = Collections.emptyList();
    private List<Map<?, ?>> blacklistedItemRules = Collections.emptyList();

    public AuctionSafetyValidator(FriendlyCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var cfg = plugin.getConfigService().get("modules/auctions/config.yml");
        if (cfg != null) {
            ConfigurationSection byteLimits = cfg.getConfigurationSection("content-limit-bytes");
            if (byteLimits != null) {
                this.maxItemBytes = byteLimits.getInt("item", 5000);
                this.maxShulkerBytes = byteLimits.getInt("shulker", 20000);
                this.maxBundleBytes = byteLimits.getInt("bundle", 20000);
            }
            this.blacklistedMaterials = cfg.getStringList("blacklist.materials");
            this.blacklistedNames = cfg.getStringList("blacklist.names");
            this.blacklistedLores = cfg.getStringList("blacklist.lores");
            this.blacklistedItemRules = new ArrayList<>(cfg.getMapList("blacklist-items"));
        }
    }

    public boolean isLocked(UUID uuid) {
        return creatingLock.contains(uuid);
    }

    public void lock(UUID uuid) {
        creatingLock.add(uuid);
    }

    public void unlock(UUID uuid) {
        creatingLock.remove(uuid);
    }

    public boolean isOversized(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        try {
            byte[] bytes = item.serializeAsBytes();
            if (isShulkerBox(item.getType())) {
                return bytes.length > maxShulkerBytes;
            } else if (item.getType().name().endsWith("BUNDLE")) {
                return bytes.length > maxBundleBytes;
            } else {
                return bytes.length > maxItemBytes;
            }
        } catch (Exception e) {
            return true;
        }
    }

    public boolean isBlacklisted(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return true;

        // AxAuctions checks the outer item and each immediate shulker/bundle entry.
        // This prevents a blacklisted item from bypassing validation inside storage.
        if (isContainer(item)) {
            for (ItemStack contained : getContainerContents(item)) {
                if (contained != null && contained.getType() != Material.AIR && isBlacklistedSingle(contained)) {
                    return true;
                }
            }
        }
        return isBlacklistedSingle(item);
    }

    private boolean isBlacklistedSingle(ItemStack item) {

        for (Map<?, ?> rule : blacklistedItemRules) {
            if (matchesRule(item, rule)) return true;
        }

        String matName = item.getType().name();
        for (String blacklisted : blacklistedMaterials) {
            if (matchesWildcard(matName, blacklisted.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }

        if (item.hasItemMeta()) {
            var meta = item.getItemMeta();
            if (meta.hasDisplayName()) {
                String name = meta.getDisplayName().toLowerCase(Locale.ROOT);
                for (String bName : blacklistedNames) {
                    if (name.contains(bName.toLowerCase(Locale.ROOT))) {
                        return true;
                    }
                }
            }
            if (meta.hasLore()) {
                List<String> lore = meta.getLore();
                if (lore != null) {
                    for (String line : lore) {
                        for (String bLore : blacklistedLores) {
                            if (line.toLowerCase(Locale.ROOT).contains(bLore.toLowerCase(Locale.ROOT))) {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    private boolean matchesRule(ItemStack item, Map<?, ?> rule) {
        if (rule == null || rule.isEmpty()) return false;
        boolean hasSupportedField = false;

        Object material = rule.containsKey("material") ? rule.get("material") : rule.get("type");
        if (material != null) {
            hasSupportedField = true;
            if (!matchesWildcard(item.getType().name(), String.valueOf(material).toUpperCase(Locale.ROOT))) return false;
        }

        Object customModelData = rule.get("custom-model-data");
        if (customModelData != null) {
            hasSupportedField = true;
            int expected;
            try {
                expected = customModelData instanceof Number number
                        ? number.intValue() : Integer.parseInt(String.valueOf(customModelData));
            } catch (NumberFormatException invalid) {
                return false;
            }
            if (!item.hasItemMeta() || !item.getItemMeta().hasCustomModelData()
                    || item.getItemMeta().getCustomModelData() != expected) return false;
        }

        Object name = rule.get("name");
        if (name != null) {
            hasSupportedField = true;
            if (!item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return false;
            String actual = item.getItemMeta().displayName() != null
                    ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName())
                    : item.getItemMeta().getDisplayName();
            if (!matchesWildcard(actual, String.valueOf(name))) return false;
        }
        return hasSupportedField;
    }

    public static boolean isShulkerBox(Material material) {
        if (material == null) return false;
        String name = material.name();
        return name.endsWith("SHULKER_BOX");
    }

    public static boolean isContainer(ItemStack item) {
        if (item == null) return false;
        Material mat = item.getType();
        return isShulkerBox(mat) || mat == Material.BUNDLE || mat.name().endsWith("_BUNDLE");
    }

    public static ItemStack[] getContainerContents(ItemStack container) {
        if (container == null || !container.hasItemMeta()) return new ItemStack[0];

        if (container.getItemMeta() instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox shulker) {
            return shulker.getInventory().getContents();
        }

        if (container.getItemMeta() instanceof BundleMeta bundleMeta) {
            List<ItemStack> items = bundleMeta.getItems();
            return items.toArray(new ItemStack[0]);
        }

        return new ItemStack[0];
    }

    public void giveOrDropItem(Player player, ItemStack item) {
        if (player == null || item == null || item.getType() == Material.AIR) return;
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        ItemStack copy = item.clone();
        plugin.getScheduler().runEntity(player, () -> {
            if (!player.isOnline() || plugin.getAuctionsModule().getAuctionManager().isPlayerDataLocked(player)) {
                plugin.getAuctionsModule().getAuctionManager().persistRecoveryItem(playerUuid, playerName, copy);
                return;
            }
            HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(copy);
            if (!overflow.isEmpty()) {
                for (ItemStack drop : overflow.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
        }, () -> {
            // Player disconnected or retired during async execution - store safely in offline unclaimed database
            dev.lyy.friendlycore.modules.auctions.model.AuctionItem fallback = new dev.lyy.friendlycore.modules.auctions.model.AuctionItem(
                    0, copy, playerUuid, playerName, 0.0, System.currentTimeMillis()
            );
            plugin.getDatabaseManager().insertAuctionDeleted(fallback);
        });
    }

    public static boolean matchesWildcard(String text, String pattern) {
        if (text == null || pattern == null) return false;
        if (pattern.equals("*")) return true;
        String t = text.toLowerCase(Locale.ROOT);
        String p = pattern.toLowerCase(Locale.ROOT);
        if (p.equals(t)) return true;
        if (p.startsWith("*") && p.endsWith("*")) {
            return p.length() <= 2 || t.contains(p.substring(1, p.length() - 1));
        } else if (p.startsWith("*")) {
            return t.endsWith(p.substring(1));
        } else if (p.endsWith("*")) {
            return t.startsWith(p.substring(0, p.length() - 1));
        }
        return t.equalsIgnoreCase(p);
    }
}
