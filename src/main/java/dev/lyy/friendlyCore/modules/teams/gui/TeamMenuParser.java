package dev.lyy.friendlycore.modules.teams.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Parses disposition-based inventory configuration layouts into structured GUI models.
 * <p>
 * Supports dynamic row wildcard expansion ('*'), symbol mapping, and placeholder substitution.
 */
public class TeamMenuParser {

    public static class ParsedLayout {
        private final String title;
        private final int rows;
        private final int size;
        private final Map<Integer, GuiItemConfig> slotMap = new HashMap<>();
        private final List<Integer> contentSlots = new ArrayList<>();
        private final Map<Character, List<Integer>> symbolSlots = new HashMap<>();

        public ParsedLayout(String title, int rows) {
            this.title = title != null ? title : "Menu";
            this.rows = Math.min(6, Math.max(1, rows));
            this.size = this.rows * 9;
        }

        public String getTitle() {
            return title;
        }

        public int getRows() {
            return rows;
        }

        public int getSize() {
            return size;
        }

        public Map<Integer, GuiItemConfig> getSlotMap() {
            return slotMap;
        }

        public List<Integer> getContentSlots() {
            return contentSlots;
        }

        public Map<Character, List<Integer>> getSymbolSlots() {
            return symbolSlots;
        }

        public List<Integer> getSlotsForSymbol(char symbol) {
            return symbolSlots.getOrDefault(symbol, Collections.emptyList());
        }

        public int getFirstSlotForSymbol(char symbol) {
            List<Integer> slots = symbolSlots.get(symbol);
            return (slots != null && !slots.isEmpty()) ? slots.get(0) : -1;
        }
    }

    public static class GuiItemConfig {
        private final char symbol;
        private final String materialName;
        private final String displayName;
        private final List<String> lore;
        private final Map<String, String> actions;
        private final int customModelData;
        private final String skullOwner;
        private final String skullTexture;

        public GuiItemConfig(char symbol, String materialName, String displayName, List<String> lore, Map<String, String> actions, int customModelData) {
            this(symbol, materialName, displayName, lore, actions, customModelData, null, null);
        }

        private final Material cachedMaterial;
        private final boolean hasNamePlaceholders;
        private final boolean hasLorePlaceholders;
        private final boolean hasSkullPlaceholders;
        private final boolean isFullyStatic;

        private volatile ItemStack cachedStaticItem;
        private volatile Component cachedStaticName;
        private volatile List<Component> cachedStaticLore;

        public GuiItemConfig(char symbol, String materialName, String displayName, List<String> lore,
                             Map<String, String> actions, int customModelData, String skullOwner, String skullTexture) {
            this.symbol = symbol;
            this.materialName = materialName != null ? materialName : "STONE";
            this.displayName = displayName != null ? displayName : "";
            this.lore = lore != null ? lore : new ArrayList<>();
            this.actions = actions != null ? actions : new HashMap<>();
            this.customModelData = customModelData;
            this.skullOwner = skullOwner;
            this.skullTexture = skullTexture;

            Material mat = Material.matchMaterial(this.materialName.toUpperCase(Locale.ROOT));
            this.cachedMaterial = mat != null ? mat : Material.BLACK_STAINED_GLASS_PANE;

            this.hasNamePlaceholders = hasPlaceholders(this.displayName);
            this.hasLorePlaceholders = hasPlaceholdersInList(this.lore);
            this.hasSkullPlaceholders = hasPlaceholders(this.skullOwner) || hasPlaceholders(this.skullTexture);
            this.isFullyStatic = !this.hasNamePlaceholders && !this.hasLorePlaceholders && !this.hasSkullPlaceholders
                    && (this.skullOwner == null || this.skullOwner.isEmpty())
                    && (this.skullTexture == null || this.skullTexture.isEmpty());
        }

        public char getSymbol() {
            return symbol;
        }

        public String getMaterialName() {
            return materialName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public List<String> getLore() {
            return lore;
        }

        public Map<String, String> getActions() {
            return actions;
        }

        public int getCustomModelData() {
            return customModelData;
        }

        public String getSkullOwner() {
            return skullOwner;
        }

        public String getSkullTexture() {
            return skullTexture;
        }

        public ItemStack createItemStack(FriendlyCore plugin, Map<String, String> placeholders) {
            return createItemStack(plugin, null, placeholders);
        }

        public ItemStack createItemStack(FriendlyCore plugin, Player player, Map<String, String> placeholders) {
            if (isFullyStatic && cachedStaticItem != null) {
                return cachedStaticItem.clone();
            }

            ItemBuilder builder = new ItemBuilder(cachedMaterial);

            if (skullTexture != null && !skullTexture.isEmpty()) {
                String parsedTex = applyPlaceholders(skullTexture, placeholders);
                builder.skullTexture(parsedTex);
            }

            if (skullOwner != null && !skullOwner.isEmpty()) {
                String parsedOwner = applyPlaceholders(skullOwner, placeholders);
                if (player != null && (parsedOwner.equalsIgnoreCase("%player%") || parsedOwner.equalsIgnoreCase("{player}") || parsedOwner.equalsIgnoreCase(player.getName()))) {
                    builder.skullOwner(player.getUniqueId(), player.getName());
                } else {
                    builder.skullOwner(parsedOwner);
                }
            }

            if (displayName != null && !displayName.isEmpty()) {
                if (!hasNamePlaceholders) {
                    if (cachedStaticName == null) {
                        cachedStaticName = (plugin != null && plugin.getTextService() != null)
                                ? plugin.getTextService().parse(displayName)
                                : Component.text(displayName);
                    }
                    builder.name(cachedStaticName);
                } else {
                    Component nameComp;
                    if (plugin != null && plugin.getTextService() != null) {
                        nameComp = (player != null)
                                ? plugin.getTextService().parseForPlayer(player, displayName, placeholders)
                                : plugin.getTextService().parse(displayName, placeholders);
                    } else {
                        nameComp = Component.text(displayName);
                    }
                    builder.name(nameComp);
                }
            }

            if (lore != null && !lore.isEmpty()) {
                if (!hasLorePlaceholders) {
                    if (cachedStaticLore == null) {
                        cachedStaticLore = (plugin != null && plugin.getTextService() != null)
                                ? plugin.getTextService().parseList(lore, Collections.emptyMap())
                                : lore.stream().<Component>map(Component::text).toList();
                    }
                    builder.lore(cachedStaticLore);
                } else {
                    List<Component> loreComps;
                    if (plugin != null && plugin.getTextService() != null) {
                        loreComps = (player != null)
                                ? plugin.getTextService().parseListForPlayer(player, lore, placeholders)
                                : plugin.getTextService().parseList(lore, placeholders);
                    } else {
                        loreComps = lore.stream().<Component>map(Component::text).toList();
                    }
                    builder.lore(loreComps);
                }
            }

            if (customModelData > 0) {
                builder.customModelData(customModelData);
            }

            ItemStack built = builder.build();
            if (isFullyStatic) {
                cachedStaticItem = built;
                return built.clone();
            }
            return built;
        }

        private static boolean hasPlaceholders(String str) {
            if (str == null || str.isEmpty()) return false;
            return str.indexOf('%') != -1 || str.indexOf('{') != -1 || str.indexOf('<') != -1;
        }

        private static boolean hasPlaceholdersInList(List<String> list) {
            if (list == null || list.isEmpty()) return false;
            for (String s : list) {
                if (hasPlaceholders(s)) return true;
            }
            return false;
        }
    }

    public static ParsedLayout parse(FileConfiguration config, boolean useAlternativeDisposition) {
        if (config == null) {
            return new ParsedLayout("Menu", 3);
        }

        String rawTitle = config.getString("inventory.title", "Menu");
        List<String> rawDisposition;

        if (useAlternativeDisposition && config.contains("inventory.disposition-alternative")) {
            if (config.isList("inventory.disposition-alternative")) {
                rawDisposition = config.getStringList("inventory.disposition-alternative");
            } else {
                rawDisposition = List.of(config.getString("inventory.disposition-alternative", "---------"));
            }
        } else if (config.contains("inventory.disposition")) {
            if (config.isList("inventory.disposition")) {
                rawDisposition = config.getStringList("inventory.disposition");
            } else {
                rawDisposition = List.of(config.getString("inventory.disposition", "---------"));
            }
        } else {
            rawDisposition = Collections.emptyList();
        }

        int configuredRows = config.getInt("inventory.rows", 0);

        Map<Character, GuiItemConfig> symbolMap = new HashMap<>();
        ConfigurationSection itemsSec = config.getConfigurationSection("items");
        if (itemsSec != null) {
            for (String key : itemsSec.getKeys(false)) {
                String symbolStr = itemsSec.getString(key + ".symbol", "");
                if (symbolStr.isEmpty()) continue;
                char symbol = symbolStr.charAt(0);

                String material = itemsSec.getString(key + ".material", "STONE");
                String name = itemsSec.getString(key + ".name", "");
                List<String> lore = itemsSec.getStringList(key + ".lore");
                int cmd = itemsSec.getInt(key + ".custom-model-data", 0);
                String skullOwner = itemsSec.getString(key + ".skull-owner",
                        itemsSec.getString(key + ".player-head-owner",
                                itemsSec.getString(key + ".owner", null)));
                String texture = itemsSec.getString(key + ".texture",
                        itemsSec.getString(key + ".skull-texture", null));

                Map<String, String> actions = new HashMap<>();
                ConfigurationSection actSec = itemsSec.getConfigurationSection(key + ".actions");
                if (actSec != null) {
                    for (String actType : actSec.getKeys(false)) {
                        actions.put(actType.toLowerCase(Locale.ROOT), actSec.getString(actType));
                    }
                }

                GuiItemConfig itemCfg = new GuiItemConfig(symbol, material, name, lore, actions, cmd, skullOwner, texture);
                symbolMap.put(symbol, itemCfg);
            }
        }

        List<String> expandedLines = new ArrayList<>();
        if (rawDisposition.isEmpty()) {
            int rows = Math.min(6, Math.max(1, configuredRows > 0 ? configuredRows : 3));
            for (int r = 0; r < rows; r++) {
                expandedLines.add("---------");
            }
        } else {
            long starCount = rawDisposition.stream().filter(l -> l.trim().equals("*")).count();
            if (starCount == 1 && configuredRows > 0) {
                for (String line : rawDisposition) {
                    if (line.trim().equals("*")) {
                        for (int i = 0; i < configuredRows; i++) {
                            expandedLines.add("*********");
                        }
                    } else {
                        expandedLines.add(padLine(line));
                    }
                }
            } else {
                for (String line : rawDisposition) {
                    if (line.trim().equals("*")) {
                        expandedLines.add("*********");
                    } else {
                        expandedLines.add(padLine(line));
                    }
                }
            }
        }

        int totalRows = Math.min(6, Math.max(1, expandedLines.size()));
        if (configuredRows > totalRows && rawDisposition.stream().noneMatch(l -> l.contains("*"))) {
            totalRows = Math.min(6, configuredRows);
        }

        ParsedLayout layout = new ParsedLayout(rawTitle, totalRows);

        for (int r = 0; r < layout.getRows(); r++) {
            String line = r < expandedLines.size() ? expandedLines.get(r) : "---------";
            for (int c = 0; c < 9; c++) {
                int slot = r * 9 + c;
                char sym = c < line.length() ? line.charAt(c) : '-';

                layout.getSymbolSlots().computeIfAbsent(sym, k -> new ArrayList<>()).add(slot);

                if (sym == '*') {
                    layout.getContentSlots().add(slot);
                } else if (sym != ' ') {
                    GuiItemConfig cfg = symbolMap.get(sym);
                    if (cfg != null) {
                        layout.getSlotMap().put(slot, cfg);
                    }
                }
            }
        }

        return layout;
    }

    private static String padLine(String line) {
        if (line == null) return "---------";
        if (line.length() >= 9) return line;
        return line + "-".repeat(9 - line.length());
    }

    private static String applyPlaceholders(String str, Map<String, String> placeholders) {
        if (str == null || str.isEmpty() || placeholders == null || placeholders.isEmpty()) {
            return str != null ? str : "";
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            str = str.replace(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
        }
        return str;
    }
}
