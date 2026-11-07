package dev.lyy.friendlycore.common;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Fluent builder for creating and configuring {@link ItemStack} instances,
 * handling Adventure display components, skull textures, and persistent data tags.
 */
public class ItemBuilder {

    private final ItemStack item;
    private final ItemMeta meta;

    public ItemBuilder(Material material) {
        this(new ItemStack(material != null ? material : Material.STONE, 1));
    }

    public ItemBuilder(Material material, int amount) {
        this(new ItemStack(material != null ? material : Material.STONE, amount));
    }

    public ItemBuilder(ItemStack item) {
        this.item = item.clone();
        this.meta = this.item.getItemMeta();
    }

    public ItemBuilder amount(int amount) {
        item.setAmount(Math.max(1, amount));
        return this;
    }

    public ItemBuilder name(Component component) {
        if (meta != null) {
            if (component != null && !component.hasDecoration(net.kyori.adventure.text.format.TextDecoration.ITALIC)) {
                component = component.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
            }
            meta.displayName(component);
        }
        return this;
    }

    public ItemBuilder name(String rawMiniMessage) {
        if (rawMiniMessage == null) return this;
        return name(new TextService().parse(rawMiniMessage));
    }

    public ItemBuilder lore(List<Component> lore) {
        if (meta != null) {
            if (lore != null) {
                List<Component> nonItalicLore = new ArrayList<>(lore.size());
                for (Component line : lore) {
                    if (line != null && !line.hasDecoration(net.kyori.adventure.text.format.TextDecoration.ITALIC)) {
                        nonItalicLore.add(line.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                    } else {
                        nonItalicLore.add(line);
                    }
                }
                meta.lore(nonItalicLore);
            } else {
                meta.lore(null);
            }
        }
        return this;
    }

    public ItemBuilder lore(Component... lore) {
        return lore(Arrays.asList(lore));
    }

    public ItemBuilder loreStrings(List<String> lines) {
        if (lines == null) {
            return lore((List<Component>) null);
        }
        TextService textService = new TextService();
        List<Component> comps = new ArrayList<>(lines.size());
        for (String l : lines) {
            comps.add(textService.parse(l));
        }
        return lore(comps);
    }

    public ItemBuilder loreStrings(String... lines) {
        return loreStrings(Arrays.asList(lines));
    }

    public ItemBuilder addLore(Component... lines) {
        if (meta != null) {
            List<Component> current = meta.lore();
            if (current == null) {
                current = new ArrayList<>();
            } else {
                current = new ArrayList<>(current);
            }
            for (Component line : lines) {
                if (line != null && !line.hasDecoration(net.kyori.adventure.text.format.TextDecoration.ITALIC)) {
                    current.add(line.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                } else {
                    current.add(line);
                }
            }
            meta.lore(current);
        }
        return this;
    }

    public ItemBuilder enchant(Enchantment enchantment, int level) {
        if (meta != null) {
            meta.addEnchant(enchantment, level, true);
        }
        return this;
    }

    public ItemBuilder glow(boolean glow) {
        if (meta != null) {
            meta.setEnchantmentGlintOverride(glow);
        }
        return this;
    }

    public ItemBuilder flags(ItemFlag... flags) {
        if (meta != null) {
            meta.addItemFlags(flags);
        }
        return this;
    }

    public ItemBuilder hideFlags() {
        if (meta != null) {
            meta.addItemFlags(ItemFlag.values());
        }
        return this;
    }

    public ItemBuilder unbreakable(boolean unbreakable) {
        if (meta != null) {
            meta.setUnbreakable(unbreakable);
        }
        return this;
    }

    public ItemBuilder damage(int damage) {
        if (meta instanceof Damageable damageable) {
            damageable.setDamage(damage);
        }
        return this;
    }

    public ItemBuilder pdcString(NamespacedKey key, String value) {
        if (meta != null && key != null && value != null) {
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
        }
        return this;
    }

    public ItemBuilder pdcByte(NamespacedKey key, byte value) {
        if (meta != null && key != null) {
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, value);
        }
        return this;
    }

    public ItemBuilder skullTexture(String base64) {
        if (meta instanceof SkullMeta skullMeta && base64 != null && !base64.isBlank()) {
            try {
                PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
                String encoded = base64;
                if (base64.startsWith("http")) {
                    encoded = Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"" + base64 + "\"}}}").getBytes());
                }
                profile.setProperty(new ProfileProperty("textures", encoded));
                skullMeta.setPlayerProfile(profile);
            } catch (Throwable ignored) {}
        }
        return this;
    }

    public ItemBuilder skullOwner(String ownerName) {
        if (meta instanceof SkullMeta skullMeta && ownerName != null && !ownerName.isBlank()) {
            try {
                if (ownerName.length() == 36 && ownerName.contains("-")) {
                    skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(UUID.fromString(ownerName)));
                } else {
                    Player online = Bukkit.getPlayerExact(ownerName);
                    if (online != null) {
                        skullMeta.setOwningPlayer(online);
                    } else {
                        try {
                            skullMeta.setPlayerProfile(Bukkit.createProfile(ownerName));
                        } catch (Throwable t) {
                            skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(ownerName));
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return this;
    }

    public ItemBuilder skullOwner(UUID uuid, String ownerName) {
        if (meta instanceof SkullMeta skullMeta) {
            try {
                if (uuid != null) {
                    skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
                } else if (ownerName != null && !ownerName.isBlank()) {
                    if (ownerName.length() == 36 && ownerName.contains("-")) {
                        skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(UUID.fromString(ownerName)));
                    } else {
                        Player online = Bukkit.getPlayerExact(ownerName);
                        if (online != null) {
                            skullMeta.setOwningPlayer(online);
                        } else {
                            try {
                                skullMeta.setPlayerProfile(Bukkit.createProfile(ownerName));
                            } catch (Throwable t) {
                                skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(ownerName));
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return this;
    }

    public ItemBuilder customModelData(int customModelData) {
        if (meta != null && customModelData > 0) {
            meta.setCustomModelData(customModelData);
        }
        return this;
    }

    public ItemBuilder editMeta(Consumer<ItemMeta> consumer) {
        if (meta != null) {
            consumer.accept(meta);
        }
        return this;
    }

    public ItemStack build() {
        if (meta != null) {
            item.setItemMeta(meta);
        }
        return item;
    }
}
