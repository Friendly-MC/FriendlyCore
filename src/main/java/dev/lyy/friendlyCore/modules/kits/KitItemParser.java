package dev.lyy.friendlycore.modules.kits;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.kits.model.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.*;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * High-performance, Paper/Folia-optimized item parser and serializer for Kit items.
 */
public final class KitItemParser {

    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private KitItemParser() {}

    public static KitItem fromItemStack(ItemStack item, boolean exactItem) {
        if (item == null || item.getType().isAir()) return null;
        if (exactItem) {
            return new KitItem(item.clone());
        }

        KitItem kitItem = new KitItem(item.getType().name());
        kitItem.setAmount(item.getAmount());

        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();

            if (meta.hasDisplayName()) {
                kitItem.setName(meta.getDisplayName().replace('§', '&'));
            }

            if (meta.hasLore() && meta.getLore() != null) {
                List<String> lore = new ArrayList<>();
                for (String line : meta.getLore()) {
                    lore.add(line.replace('§', '&'));
                }
                kitItem.setLore(lore);
            }

            if (meta instanceof Damageable dmg && dmg.hasDamage()) {
                kitItem.setDurability((short) dmg.getDamage());
            }

            if (meta.hasEnchants()) {
                List<String> enchants = new ArrayList<>();
                for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
                    String key = entry.getKey().getKey().getKey().toUpperCase(Locale.ROOT);
                    enchants.add(key + ";" + entry.getValue());
                }
                kitItem.setEnchants(enchants);
            }

            Set<ItemFlag> flags = meta.getItemFlags();
            if (!flags.isEmpty()) {
                List<String> flagList = new ArrayList<>();
                for (ItemFlag flag : flags) {
                    flagList.add(flag.name());
                }
                kitItem.setFlags(flagList);
            }

            if (meta.hasCustomModelData()) {
                kitItem.setCustomModelData(meta.getCustomModelData());
            }

            try {
                if (meta.hasCustomModelData()) {
                    CustomModelDataComponent cmdComp = meta.getCustomModelDataComponent();
                    if (cmdComp != null) {
                        List<String> cFlags = cmdComp.getFlags().stream().map(String::valueOf).collect(Collectors.toList());
                        List<String> cColors = cmdComp.getColors().stream().map(c -> String.valueOf(c.asRGB())).collect(Collectors.toList());
                        List<String> cFloats = cmdComp.getFloats().stream().map(String::valueOf).collect(Collectors.toList());
                        List<String> cStrings = new ArrayList<>(cmdComp.getStrings());
                        if (!cFlags.isEmpty() || !cColors.isEmpty() || !cFloats.isEmpty() || !cStrings.isEmpty()) {
                            kitItem.setCustomModelComponentData(new KitItemCustomModelComponentData(cFlags, cColors, cFloats, cStrings));
                        }
                    }
                }
            } catch (Throwable ignored) {}

            try {
                if (meta.isHideTooltip()) kitItem.setHideTooltip(true);
                if (meta.hasTooltipStyle() && meta.getTooltipStyle() != null) {
                    kitItem.setTooltipStyle(meta.getTooltipStyle().asString());
                }
                if (meta.hasItemModel() && meta.getItemModel() != null) {
                    kitItem.setModel(meta.getItemModel().asString());
                }
            } catch (Throwable ignored) {}

            if (meta instanceof LeatherArmorMeta leather) {
                kitItem.setColor(leather.getColor().asRGB());
            }

            if (meta instanceof EnchantmentStorageMeta bookMeta && bookMeta.hasStoredEnchants()) {
                List<String> stored = new ArrayList<>();
                for (Map.Entry<Enchantment, Integer> entry : bookMeta.getStoredEnchants().entrySet()) {
                    String key = entry.getKey().getKey().getKey().toUpperCase(Locale.ROOT);
                    stored.add(key + ";" + entry.getValue());
                }
                kitItem.setBookEnchants(stored);
            }

            if (meta instanceof SkullMeta skull) {
                String owner = skull.hasOwner() ? skull.getOwningPlayer() != null ? skull.getOwningPlayer().getName() : null : null;
                String texture = extractSkullTexture(skull);
                if (owner != null || texture != null) {
                    kitItem.setSkullData(new KitItemSkullData(owner, texture, UUID.randomUUID().toString()));
                }
            }

            if (meta instanceof PotionMeta potion) {
                String baseType = potion.getBasePotionType() != null ? potion.getBasePotionType().name() : null;
                int pColor = potion.hasColor() && potion.getColor() != null ? potion.getColor().asRGB() : 0;
                List<String> customEffects = new ArrayList<>();
                if (potion.hasCustomEffects()) {
                    for (PotionEffect effect : potion.getCustomEffects()) {
                        customEffects.add(effect.getType().getKey().getKey() + ";" + effect.getDuration() + ";" + effect.getAmplifier());
                    }
                }
                if (baseType != null || pColor != 0 || !customEffects.isEmpty()) {
                    kitItem.setPotionData(new KitItemPotionData(false, false, baseType, pColor, customEffects));
                }
            }

            if (meta instanceof ArmorMeta armor && armor.hasTrim()) {
                ArmorTrim trim = armor.getTrim();
                if (trim != null) {
                    kitItem.setTrimData(new KitItemTrimData(trim.getPattern().getKey().getKey(), trim.getMaterial().getKey().getKey()));
                }
            }

            if (meta instanceof BookMeta book) {
                String author = book.hasAuthor() ? book.getAuthor() : null;
                String title = book.hasTitle() ? book.getTitle() : null;
                String gen = book.hasGeneration() && book.getGeneration() != null ? book.getGeneration().name() : null;
                List<String> pages = book.hasPages() ? new ArrayList<>(book.getPages()) : new ArrayList<>();
                kitItem.setBookData(new KitItemBookData(pages, author, gen, title));
            }
        }

        return kitItem;
    }

    public static ItemStack toItemStack(KitItem kitItem, Player player, Kit kit) {
        if (kitItem == null) return null;

        if (kitItem.getOriginalItem() != null) {
            ItemStack clone = kitItem.getOriginalItem().clone();
            if (kit != null && kit.isAllowPlaceholdersOnOriginalItems() && clone.hasItemMeta()) {
                ItemMeta meta = clone.getItemMeta();
                if (meta.hasDisplayName()) {
                    meta.setDisplayName(formatString(meta.getDisplayName(), player, kit));
                }
                if (meta.hasLore() && meta.getLore() != null) {
                    List<String> lore = meta.getLore();
                    lore.replaceAll(line -> formatString(line, player, kit));
                    meta.setLore(lore);
                }
                clone.setItemMeta(meta);
            }
            return clone;
        }

        Material mat = Material.matchMaterial(kitItem.getId());
        if (mat == null) {
            mat = Material.getMaterial(kitItem.getId().toUpperCase(Locale.ROOT));
        }
        if (mat == null) {
            mat = Material.STONE;
        }

        int legalAmount = Math.min(Math.max(1, kitItem.getAmount()), Math.max(1, mat.getMaxStackSize()));
        ItemStack item = new ItemStack(mat, legalAmount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (kitItem.getDurability() > 0 && meta instanceof Damageable dmg) {
            dmg.setDamage(kitItem.getDurability());
        }

        if (kitItem.getName() != null && !kitItem.getName().isEmpty()) {
            String parsed = formatString(kitItem.getName(), player, kit);
            meta.displayName(parseComponent(parsed));
        }

        if (kitItem.getLore() != null && !kitItem.getLore().isEmpty()) {
            List<Component> loreComponents = new ArrayList<>();
            for (String line : kitItem.getLore()) {
                String parsed = formatString(line, player, kit);
                loreComponents.add(parseComponent(parsed));
            }
            meta.lore(loreComponents);
        }

        if (kitItem.getCustomModelData() != 0) {
            meta.setCustomModelData(kitItem.getCustomModelData());
        }

        if (kitItem.getCustomModelComponentData() != null) {
            try {
                CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
                KitItemCustomModelComponentData comp = kitItem.getCustomModelComponentData();
                if (comp.getFlags() != null) cmd.setFlags(comp.getFlags().stream().map(Boolean::parseBoolean).collect(Collectors.toList()));
                if (comp.getFloats() != null) cmd.setFloats(comp.getFloats().stream().map(Float::parseFloat).collect(Collectors.toList()));
                if (comp.getColors() != null) cmd.setColors(comp.getColors().stream().map(s -> Color.fromRGB(Integer.parseInt(s))).collect(Collectors.toList()));
                if (comp.getStrings() != null) cmd.setStrings(new ArrayList<>(comp.getStrings()));
                meta.setCustomModelDataComponent(cmd);
            } catch (Throwable ignored) {}
        }

        try {
            if (kitItem.isHideTooltip()) meta.setHideTooltip(true);
            if (kitItem.getTooltipStyle() != null) {
                meta.setTooltipStyle(NamespacedKey.fromString(kitItem.getTooltipStyle()));
            }
            if (kitItem.getModel() != null) {
                meta.setItemModel(NamespacedKey.fromString(kitItem.getModel()));
            }
        } catch (Throwable ignored) {}

        if (kitItem.getEnchants() != null) {
            for (String enchStr : kitItem.getEnchants()) {
                String[] parts = enchStr.split(";");
                if (parts.length >= 2) {
                    Enchantment ench = parseEnchantment(parts[0]);
                    int level = parseInteger(parts[1], 1);
                    if (ench != null) {
                        meta.addEnchant(ench, level, true);
                    }
                }
            }
        }

        if (kitItem.getFlags() != null) {
            for (String flagName : kitItem.getFlags()) {
                try {
                    meta.addItemFlags(ItemFlag.valueOf(flagName.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {}
            }
        }

        if (kitItem.getColor() != 0 && meta instanceof LeatherArmorMeta leather) {
            leather.setColor(Color.fromRGB(kitItem.getColor()));
        }

        if (kitItem.getBookEnchants() != null && meta instanceof EnchantmentStorageMeta bookMeta) {
            for (String enchStr : kitItem.getBookEnchants()) {
                String[] parts = enchStr.split(";");
                if (parts.length >= 2) {
                    Enchantment ench = parseEnchantment(parts[0]);
                    int level = parseInteger(parts[1], 1);
                    if (ench != null) {
                        bookMeta.addStoredEnchant(ench, level, true);
                    }
                }
            }
        }

        if (kitItem.getSkullData() != null && meta instanceof SkullMeta skull) {
            KitItemSkullData sd = kitItem.getSkullData();
            if (sd.getTexture() != null && !sd.getTexture().isEmpty()) {
                applySkullTexture(skull, sd.getTexture());
            } else if (sd.getOwner() != null && !sd.getOwner().isEmpty()) {
                String owner = formatString(sd.getOwner(), player, kit);
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
            }
        }

        if (kitItem.getPotionData() != null && meta instanceof PotionMeta potion) {
            KitItemPotionData pd = kitItem.getPotionData();
            if (pd.getPotionType() != null) {
                try {
                    PotionType pt = PotionType.valueOf(pd.getPotionType().toUpperCase(Locale.ROOT));
                    potion.setBasePotionType(pt);
                } catch (IllegalArgumentException ignored) {}
            }
            if (pd.getPotionColor() != 0) {
                potion.setColor(Color.fromRGB(pd.getPotionColor()));
            }
            if (pd.getPotionEffects() != null) {
                for (String effStr : pd.getPotionEffects()) {
                    String[] p = effStr.split(";");
                    if (p.length >= 3) {
                        PotionEffectType pet = PotionEffectType.getByName(p[0].toUpperCase(Locale.ROOT));
                        if (pet != null) {
                            int dur = parseInteger(p[1], 200);
                            int amp = parseInteger(p[2], 0);
                            potion.addCustomEffect(new PotionEffect(pet, dur, amp), true);
                        }
                    }
                }
            }
        }

        if (kitItem.getTrimData() != null && meta instanceof ArmorMeta armor) {
            KitItemTrimData td = kitItem.getTrimData();
            TrimMaterial trimMat = parseTrimMaterial(td.getMaterial());
            TrimPattern trimPat = parseTrimPattern(td.getPattern());
            if (trimMat != null && trimPat != null) {
                armor.setTrim(new ArmorTrim(trimMat, trimPat));
            }
        }

        if (kitItem.getBookData() != null && meta instanceof BookMeta book) {
            KitItemBookData bd = kitItem.getBookData();
            if (bd.getTitle() != null) book.setTitle(formatString(bd.getTitle(), player, kit));
            if (bd.getAuthor() != null) book.setAuthor(formatString(bd.getAuthor(), player, kit));
            if (bd.getPages() != null) {
                for (String page : bd.getPages()) {
                    book.addPage(formatString(page, player, kit));
                }
            }
        }

        item.setItemMeta(meta);
        return item;
    }

    public static void saveKitItem(KitItem item, ConfigurationSection section, String path) {
        if (item == null) return;
        if (item.getOriginalItem() != null) {
            section.set(path + ".original", item.getOriginalItem().clone());
            if (item.getEquipmentSlot() != null) section.set(path + ".equipment_slot", item.getEquipmentSlot().name());
            if (item.isOffhand()) section.set(path + ".offhand", true);
            if (item.getPreviewSlot() != -1) section.set(path + ".preview_slot", item.getPreviewSlot());
            return;
        }

        section.set(path + ".id", item.getId());
        if (item.getAmount() > 1) section.set(path + ".amount", item.getAmount());
        if (item.getName() != null) section.set(path + ".name", item.getName());
        if (item.getLore() != null && !item.getLore().isEmpty()) section.set(path + ".lore", item.getLore());
        if (item.getDurability() > 0) section.set(path + ".durability", item.getDurability());
        if (item.getCustomModelData() != 0) section.set(path + ".custom_model_data", item.getCustomModelData());
        if (item.getColor() != 0) section.set(path + ".color", item.getColor());
        if (item.getEnchants() != null && !item.getEnchants().isEmpty()) section.set(path + ".enchants", item.getEnchants());
        if (item.getFlags() != null && !item.getFlags().isEmpty()) section.set(path + ".item_flags", item.getFlags());
        if (item.getBookEnchants() != null && !item.getBookEnchants().isEmpty()) section.set(path + ".book_enchants", item.getBookEnchants());
        if (item.getEquipmentSlot() != null) section.set(path + ".equipment_slot", item.getEquipmentSlot().name());
        if (item.isOffhand()) section.set(path + ".offhand", true);
        if (item.getPreviewSlot() != -1) section.set(path + ".preview_slot", item.getPreviewSlot());

        if (item.getSkullData() != null) {
            KitItemSkullData sd = item.getSkullData();
            if (sd.getOwner() != null) section.set(path + ".skull_data.owner", sd.getOwner());
            if (sd.getTexture() != null) section.set(path + ".skull_data.texture", sd.getTexture());
        }

        if (item.getPotionData() != null) {
            KitItemPotionData pd = item.getPotionData();
            if (pd.getPotionType() != null) section.set(path + ".potion_data.type", pd.getPotionType());
            if (pd.getPotionColor() != 0) section.set(path + ".potion_data.color", pd.getPotionColor());
            if (pd.getPotionEffects() != null && !pd.getPotionEffects().isEmpty()) section.set(path + ".potion_data.effects", pd.getPotionEffects());
        }

        if (item.getTrimData() != null) {
            section.set(path + ".trim_data.material", item.getTrimData().getMaterial());
            section.set(path + ".trim_data.pattern", item.getTrimData().getPattern());
        }

        if (item.getFireworkData() != null) {
            KitItemFireworkData fd = item.getFireworkData();
            if (fd.getRocketEffects() != null && !fd.getRocketEffects().isEmpty()) section.set(path + ".firework_data.rocket_effects", fd.getRocketEffects());
            if (fd.getStarEffect() != null) section.set(path + ".firework_data.star_effect", fd.getStarEffect());
            if (fd.getPower() > 0) section.set(path + ".firework_data.power", fd.getPower());
        }

        if (item.getBannerData() != null) {
            KitItemBannerData bd = item.getBannerData();
            if (bd.getBaseColor() != null) section.set(path + ".banner_data.base_color", bd.getBaseColor());
            if (bd.getPatterns() != null && !bd.getPatterns().isEmpty()) section.set(path + ".banner_data.patterns", bd.getPatterns());
        }

        if (item.getBookData() != null) {
            KitItemBookData bd = item.getBookData();
            if (bd.getTitle() != null) section.set(path + ".book_data.title", bd.getTitle());
            if (bd.getAuthor() != null) section.set(path + ".book_data.author", bd.getAuthor());
            if (bd.getPages() != null && !bd.getPages().isEmpty()) section.set(path + ".book_data.pages", bd.getPages());
            if (bd.getGeneration() != null) section.set(path + ".book_data.generation", bd.getGeneration());
        }

        if (item.isHideTooltip()) section.set(path + ".hide_tooltip", true);
        if (item.getTooltipStyle() != null) section.set(path + ".tooltip_style", item.getTooltipStyle());
        if (item.getModel() != null) section.set(path + ".model", item.getModel());

        if (item.getNbt() != null && !item.getNbt().isEmpty()) section.set(path + ".nbt", item.getNbt());
        if (item.getAttributes() != null && !item.getAttributes().isEmpty()) section.set(path + ".attributes", item.getAttributes());
        if (item.getCanPlace() != null && !item.getCanPlace().isEmpty()) section.set(path + ".can_place", item.getCanPlace());
        if (item.getCanDestroy() != null && !item.getCanDestroy().isEmpty()) section.set(path + ".can_destroy", item.getCanDestroy());
    }

    public static KitItem loadKitItem(ConfigurationSection section, String path) {
        if (section == null || !section.contains(path)) return null;

        boolean offhand = section.getBoolean(path + ".offhand", false);
        KitItem.EquipmentSlot equipmentSlot = parseEquipmentSlot(section.getString(path + ".equipment_slot"));
        int previewSlot = section.getInt(path + ".preview_slot", -1);

        KitItem kitItem = null;

        if (section.contains(path + ".original")) {
            Object originalObj = section.get(path + ".original");
            if (originalObj instanceof ItemStack is) {
                kitItem = new KitItem(is.clone());
            } else {
                ItemStack is = section.getItemStack(path + ".original");
                if (is != null) {
                    kitItem = new KitItem(is.clone());
                } else if (originalObj instanceof String base64 && !base64.isEmpty()) {
                    List<ItemStack> decoded = KitSerializer.fromBase64(base64);
                    if (!decoded.isEmpty()) {
                        kitItem = new KitItem(decoded.get(0));
                    }
                }
            }
        }

        if (kitItem == null) {
            Object directObj = section.get(path);
            if (directObj instanceof ItemStack is) {
                kitItem = new KitItem(is.clone());
            }
        }

        if (kitItem == null) {
            String id = section.getString(path + ".id");
            if (id == null && section.contains(path + ".original")) {
                ItemStack is = section.getItemStack(path + ".original");
                if (is != null) kitItem = new KitItem(is);
            }

            if (kitItem == null) {
                if (id == null || id.isEmpty()) id = "STONE";
                kitItem = new KitItem(id);
                kitItem.setAmount(section.getInt(path + ".amount", 1));
                kitItem.setName(section.getString(path + ".name"));
                kitItem.setLore(section.getStringList(path + ".lore"));
                kitItem.setDurability((short) section.getInt(path + ".durability", 0));
                kitItem.setCustomModelData(section.getInt(path + ".custom_model_data", 0));
                kitItem.setColor(section.getInt(path + ".color", 0));
                kitItem.setEnchants(section.getStringList(path + ".enchants"));
                kitItem.setFlags(section.getStringList(path + ".item_flags"));
                kitItem.setBookEnchants(section.getStringList(path + ".book_enchants"));
                kitItem.setNbt(section.getStringList(path + ".nbt"));
                kitItem.setAttributes(section.getStringList(path + ".attributes"));
                kitItem.setCanPlace(section.getStringList(path + ".can_place"));
                kitItem.setCanDestroy(section.getStringList(path + ".can_destroy"));

                if (section.contains(path + ".skull_data")) {
                    String owner = section.getString(path + ".skull_data.owner");
                    String texture = section.getString(path + ".skull_data.texture");
                    String sid = section.getString(path + ".skull_data.id", UUID.randomUUID().toString());
                    kitItem.setSkullData(new KitItemSkullData(owner, texture, sid));
                }

                if (section.contains(path + ".potion_data")) {
                    String type = section.getString(path + ".potion_data.type");
                    int color = section.getInt(path + ".potion_data.color", 0);
                    List<String> eff = section.getStringList(path + ".potion_data.effects");
                    boolean ext = section.getBoolean(path + ".potion_data.extended", false);
                    boolean upg = section.getBoolean(path + ".potion_data.upgraded", false);
                    kitItem.setPotionData(new KitItemPotionData(ext, upg, type, color, eff));
                }

                if (section.contains(path + ".trim_data")) {
                    String mat = section.getString(path + ".trim_data.material");
                    String pat = section.getString(path + ".trim_data.pattern");
                    kitItem.setTrimData(new KitItemTrimData(pat, mat));
                }

                if (section.contains(path + ".firework_data")) {
                    List<String> rEffects = section.getStringList(path + ".firework_data.rocket_effects");
                    String sEffect = section.getString(path + ".firework_data.star_effect");
                    int power = section.getInt(path + ".firework_data.power", 0);
                    kitItem.setFireworkData(new KitItemFireworkData(rEffects, sEffect, power));
                }

                if (section.contains(path + ".banner_data")) {
                    List<String> patterns = section.getStringList(path + ".banner_data.patterns");
                    String baseColor = section.getString(path + ".banner_data.base_color");
                    kitItem.setBannerData(new KitItemBannerData(patterns, baseColor));
                }

                if (section.contains(path + ".book_data")) {
                    String author = section.getString(path + ".book_data.author");
                    String title = section.getString(path + ".book_data.title");
                    List<String> pages = section.getStringList(path + ".book_data.pages");
                    String gen = section.getString(path + ".book_data.generation");
                    kitItem.setBookData(new KitItemBookData(pages, author, gen, title));
                }

                if (section.contains(path + ".custom_model_component_data")) {
                    List<String> flags = section.getStringList(path + ".custom_model_component_data.flags");
                    List<String> floats = section.getStringList(path + ".custom_model_component_data.floats");
                    List<String> colors = section.getStringList(path + ".custom_model_component_data.colors");
                    List<String> strings = section.getStringList(path + ".custom_model_component_data.strings");
                    kitItem.setCustomModelComponentData(new KitItemCustomModelComponentData(flags, floats, colors, strings));
                }

                kitItem.setHideTooltip(section.getBoolean(path + ".hide_tooltip", false));
                kitItem.setTooltipStyle(section.getString(path + ".tooltip_style"));
                kitItem.setModel(section.getString(path + ".item_model", section.getString(path + ".model")));
            }
        }

        if (equipmentSlot != null) {
            kitItem.setEquipmentSlot(equipmentSlot);
        } else {
            kitItem.setOffhand(offhand);
        }
        kitItem.setPreviewSlot(previewSlot);
        return kitItem;
    }

    static KitItem.EquipmentSlot parseEquipmentSlot(String value) {
        if (value == null || value.isBlank()) return null;
        return switch (value.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
            case "HEAD", "HELMET" -> KitItem.EquipmentSlot.HEAD;
            case "CHEST", "CHESTPLATE" -> KitItem.EquipmentSlot.CHEST;
            case "LEGS", "LEGGINGS" -> KitItem.EquipmentSlot.LEGS;
            case "FEET", "BOOTS" -> KitItem.EquipmentSlot.FEET;
            case "OFF_HAND", "OFFHAND" -> KitItem.EquipmentSlot.OFF_HAND;
            default -> null;
        };
    }

    private static Component parseComponent(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        if (text.contains("<") && text.contains(">")) {
            try {
                return MINI_MESSAGE.deserialize(text);
            } catch (Exception ignored) {}
        }
        return LEGACY_AMPERSAND.deserialize(text);
    }

    private static String formatString(String text, Player player, Kit kit) {
        if (text == null) return "";
        if (player != null) {
            text = text.replace("%player%", player.getName())
                    .replace("%player_name%", player.getName());
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                text = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
            }
        }
        if (kit != null) {
            text = text.replace("%kit%", kit.getName())
                    .replace("%kit_name%", kit.getName());
        }
        return text;
    }

    private static Enchantment parseEnchantment(String name) {
        try {
            NamespacedKey key = NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT));
            Enchantment ench = Registry.ENCHANTMENT.get(key);
            if (ench != null) return ench;
        } catch (Throwable ignored) {}
        return Enchantment.getByName(name.toUpperCase(Locale.ROOT));
    }

    private static TrimMaterial parseTrimMaterial(String name) {
        if (name == null) return null;
        try {
            return Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static TrimPattern parseTrimPattern(String name) {
        if (name == null) return null;
        try {
            return Registry.TRIM_PATTERN.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void applySkullTexture(SkullMeta skull, String textureUrlOrBase64) {
        try {
            PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
            String base64 = textureUrlOrBase64;
            if (textureUrlOrBase64.startsWith("http")) {
                base64 = Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"" + textureUrlOrBase64 + "\"}}}").getBytes());
            }
            profile.setProperty(new ProfileProperty("textures", base64));
            skull.setPlayerProfile(profile);
        } catch (Throwable ignored) {}
    }

    private static String extractSkullTexture(SkullMeta skull) {
        try {
            PlayerProfile profile = skull.getPlayerProfile();
            if (profile != null) {
                for (ProfileProperty prop : profile.getProperties()) {
                    if ("textures".equals(prop.getName())) {
                        return prop.getValue();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static int parseInteger(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
