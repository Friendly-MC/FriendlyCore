package dev.lyy.friendlycore.common;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text formatting, color parsing (MiniMessage, hex, and legacy codes),
 * placeholder resolution, and messaging utilities.
 */
public class TextService {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer SECTION_SERIALIZER = LegacyComponentSerializer.legacySection();
    private static final LegacyComponentSerializer AMPERSAND_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private static final Pattern HEX_AMP_PATTERN = Pattern.compile("(?i)[&§]#([A-Fa-f0-9]{6})");
    private static final Pattern HEX_SEMI_PATTERN = Pattern.compile("(?i)[&§]#([A-Fa-f0-9]{6});");
    private static final Pattern BRACKET_HEX_PATTERN = Pattern.compile("(?i)[{\\[]#([A-Fa-f0-9]{6})[\\]}]");
    private static final Pattern RAW_HEX_PATTERN = Pattern.compile("(?<!</)(?<![<:;&§])#([A-Fa-f0-9]{6})");
    private static final Pattern LEGACY_AMP_HEX_PATTERN = Pattern.compile("(?i)(?:&|§)x(?:(?:&|§)([0-9a-fA-F])){6}");
    private static final Pattern GRADIENT_COMMA_PATTERN = Pattern.compile("<(gradient|transition):([^>]+)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CUSTOM_FONT_LEGACY_PATTERN = Pattern.compile("%customfontlegacy[^%]*,(?<name>[^%]+)%");

    private static final Set<String> FORMATTING_KEYS = Set.of(
            "prefix", "suffix", "displayname", "display_name",
            "luckperms_prefix", "luckperms_suffix", "vault_prefix", "vault_suffix",
            "group", "rank", "primary_group", "luckperms_primary_group_name",
            "badge", "tag", "tags", "format", "header", "footer", "title", "subtitle"
    );

    public static String safeEscapeTags(String text) {
        if (text == null || text.isEmpty()) return "";
        if (text.indexOf('<') == -1 && text.indexOf('\\') == -1) {
            return text;
        }
        return MINI_MESSAGE.escapeTags(text);
    }

    public static String cleanPlaceholderKey(String rawKey) {
        if (rawKey == null || rawKey.isEmpty()) return "";
        int len = rawKey.length();
        boolean needsClean = false;
        for (int i = 0; i < len; i++) {
            char c = rawKey.charAt(i);
            if (c == '%' || c == '{' || c == '}' || (c >= 'A' && c <= 'Z') ||
                    !((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
                needsClean = true;
                break;
            }
        }
        if (!needsClean) {
            return rawKey;
        }
        StringBuilder clean = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = rawKey.charAt(i);
            if (c == '%' || c == '{' || c == '}') continue;
            boolean asciiAlphaNumeric = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (asciiAlphaNumeric || c == '_' || c == '-') {
                clean.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
            } else {
                clean.append('_');
            }
        }
        return clean.toString();
    }

    public static boolean isFormattingPlaceholder(String key) {
        if (key == null || key.isEmpty()) return false;
        String clean = cleanPlaceholderKey(key);
        return FORMATTING_KEYS.contains(clean) ||
                clean.endsWith("_formatted") ||
                clean.contains("prefix") ||
                clean.contains("suffix") ||
                clean.contains("displayname") ||
                clean.contains("display_name");
    }

    public static final class PreparedPlaceholders {
        private final Map<String, String> lookup;
        private final Map<String, String> original;
        private final boolean hasExplicitPlayer;
        private final String explicitPlayerName;
        private final boolean hasExplicitPrefix;
        private final boolean hasExplicitSuffix;
        private final boolean hasExplicitDisplay;
        private final boolean hasExplicitGroup;
        private final boolean hasExplicitBalance;
        private final boolean hasExplicitHealth;
        private final boolean hasExplicitPing;
        private final boolean hasExplicitServer;

        public PreparedPlaceholders(Map<String, String> explicitPlaceholders) {
            this.original = explicitPlaceholders;
            if (explicitPlaceholders == null || explicitPlaceholders.isEmpty()) {
                this.lookup = Collections.emptyMap();
                this.hasExplicitPlayer = false;
                this.explicitPlayerName = null;
                this.hasExplicitPrefix = false;
                this.hasExplicitSuffix = false;
                this.hasExplicitDisplay = false;
                this.hasExplicitGroup = false;
                this.hasExplicitBalance = false;
                this.hasExplicitHealth = false;
                this.hasExplicitPing = false;
                this.hasExplicitServer = false;
                return;
            }

            Map<String, String> map = new HashMap<>(explicitPlaceholders.size() * 2);
            String foundPlayerName = null;
            boolean expPrefix = false;
            boolean expSuffix = false;
            boolean expDisplay = false;
            boolean expGroup = false;
            boolean expBalance = false;
            boolean expHealth = false;
            boolean expPing = false;
            boolean expServer = false;

            for (Map.Entry<String, String> entry : explicitPlaceholders.entrySet()) {
                String rawKey = entry.getKey();
                if (rawKey == null || rawKey.isEmpty()) continue;
                String rawVal = entry.getValue() != null ? entry.getValue() : "";

                String cleanKey = cleanPlaceholderKey(rawKey);
                if (cleanKey.isEmpty()) continue;

                boolean isFormatting = isFormattingPlaceholder(cleanKey);
                String subVal = isFormatting ? convertLegacyToMiniMessage(rawVal) : safeEscapeTags(rawVal);

                map.put(cleanKey, subVal);

                if (cleanKey.equals("player") || cleanKey.equals("player_name") || cleanKey.equals("name") || cleanKey.equals("target")) {
                    foundPlayerName = rawVal;
                    map.put("player", subVal);
                    map.put("player_name", subVal);
                    map.put("name", subVal);
                    map.put("target", subVal);
                } else if (cleanKey.equals("seconds") || cleanKey.equals("second") || cleanKey.equals("time") || cleanKey.equals("sec")) {
                    map.put("seconds", subVal);
                    map.put("second", subVal);
                    map.put("time", subVal);
                    map.put("sec", subVal);
                } else if (cleanKey.equals("displayname") || cleanKey.equals("display_name") || cleanKey.equals("player_displayname") || cleanKey.equals("player_display_name")) {
                    map.put("displayname", subVal);
                    map.put("display_name", subVal);
                    map.put("player_displayname", subVal);
                    map.put("player_display_name", subVal);
                    expDisplay = true;
                } else if (cleanKey.equals("prefix") || cleanKey.equals("luckperms_prefix") || cleanKey.equals("vault_prefix")) {
                    map.put("prefix", subVal);
                    map.put("luckperms_prefix", subVal);
                    map.put("vault_prefix", subVal);
                    expPrefix = true;
                } else if (cleanKey.equals("suffix") || cleanKey.equals("luckperms_suffix") || cleanKey.equals("vault_suffix")) {
                    map.put("suffix", subVal);
                    map.put("luckperms_suffix", subVal);
                    map.put("vault_suffix", subVal);
                    expSuffix = true;
                } else if (cleanKey.equals("group") || cleanKey.equals("luckperms_primary_group_name") || cleanKey.equals("rank")) {
                    map.put("group", subVal);
                    map.put("luckperms_primary_group_name", subVal);
                    map.put("rank", subVal);
                    expGroup = true;
                } else if (cleanKey.equals("health") || cleanKey.equals("player_health_scale")) {
                    map.put("health", subVal);
                    map.put("player_health_scale", subVal);
                    expHealth = true;
                } else if (cleanKey.equals("ping") || cleanKey.equals("player_ping")) {
                    map.put("ping", subVal);
                    map.put("player_ping", subVal);
                    expPing = true;
                } else if (cleanKey.equals("balance") || cleanKey.equals("vault_eco_balance") || cleanKey.equals("vault_eco_balance_formatted") || cleanKey.equals("money")) {
                    map.put("balance", subVal);
                    map.put("vault_eco_balance", subVal);
                    map.put("vault_eco_balance_formatted", subVal);
                    map.put("money", subVal);
                    expBalance = true;
                } else if (cleanKey.equals("server") || cleanKey.equals("server_name") || cleanKey.equals("server_id")) {
                    map.put("server", subVal);
                    map.put("server_name", subVal);
                    map.put("server_id", subVal);
                    expServer = true;
                }
            }

            this.lookup = map;
            this.explicitPlayerName = foundPlayerName;
            this.hasExplicitPlayer = foundPlayerName != null;
            this.hasExplicitPrefix = expPrefix;
            this.hasExplicitSuffix = expSuffix;
            this.hasExplicitDisplay = expDisplay;
            this.hasExplicitGroup = expGroup;
            this.hasExplicitBalance = expBalance;
            this.hasExplicitHealth = expHealth;
            this.hasExplicitPing = expPing;
            this.hasExplicitServer = expServer;
        }

        public Map<String, String> getLookup() {
            return lookup;
        }

        public Map<String, String> getOriginal() {
            return original;
        }
    }

    public static String replacePlaceholdersFast(String text, Map<String, String> lookup) {
        if (text == null || text.isEmpty() || lookup == null || lookup.isEmpty()) {
            return text;
        }
        int len = text.length();
        int firstPercent = text.indexOf('%');
        int firstBrace = text.indexOf('{');
        if (firstPercent == -1 && firstBrace == -1) {
            return text;
        }

        StringBuilder sb = null;
        int lastCopied = 0;
        int i = 0;
        while (i < len) {
            char c = text.charAt(i);
            if (c == '%') {
                int nextPercent = text.indexOf('%', i + 1);
                if (nextPercent != -1 && nextPercent > i + 1) {
                    String inner = text.substring(i + 1, nextPercent);
                    if (isValidPlaceholderToken(inner)) {
                        String cleanKey = cleanPlaceholderKey(inner);
                        String val = lookup.get(cleanKey);
                        if (val != null) {
                            if (sb == null) {
                                sb = new StringBuilder(len + Math.max(16, val.length()));
                            }
                            sb.append(text, lastCopied, i);
                            sb.append(val);
                            i = nextPercent + 1;
                            lastCopied = i;
                            continue;
                        }
                    }
                }
                i++;
            } else if (c == '{') {
                int nextBrace = text.indexOf('}', i + 1);
                if (nextBrace != -1 && nextBrace > i + 1) {
                    String inner = text.substring(i + 1, nextBrace);
                    if (isValidPlaceholderToken(inner)) {
                        String cleanKey = cleanPlaceholderKey(inner);
                        String val = lookup.get(cleanKey);
                        if (val != null) {
                            if (sb == null) {
                                sb = new StringBuilder(len + Math.max(16, val.length()));
                            }
                            sb.append(text, lastCopied, i);
                            sb.append(val);
                            i = nextBrace + 1;
                            lastCopied = i;
                            continue;
                        }
                    }
                }
                i++;
            } else {
                i++;
            }
        }

        if (sb == null) {
            return text;
        }
        if (lastCopied < len) {
            sb.append(text, lastCopied, len);
        }
        return sb.toString();
    }

    private static boolean isValidPlaceholderToken(String token) {
        if (token == null || token.isEmpty()) return false;
        int len = token.length();
        if (len > 64) return false;
        for (int i = 0; i < len; i++) {
            char c = token.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '<' || c == '>') {
                return false;
            }
        }
        return true;
    }

    private static String replaceIgnoreCase(String source, String target, String replacement) {
        if (source == null || source.isEmpty() || target == null || target.isEmpty()) return source;
        int match = indexOfIgnoreCase(source, target, 0);
        if (match < 0) return source;

        String safeReplacement = replacement != null ? replacement : "";
        StringBuilder result = new StringBuilder(source.length() + Math.max(0, safeReplacement.length() - target.length()));
        int copiedUntil = 0;
        while (match >= 0) {
            result.append(source, copiedUntil, match).append(safeReplacement);
            copiedUntil = match + target.length();
            match = indexOfIgnoreCase(source, target, copiedUntil);
        }
        return result.append(source, copiedUntil, source.length()).toString();
    }

    private static int indexOfIgnoreCase(String source, String target, int fromIndex) {
        int limit = source.length() - target.length();
        for (int i = Math.max(0, fromIndex); i <= limit; i++) {
            if (source.regionMatches(true, i, target, 0, target.length())) return i;
        }
        return -1;
    }

    private static boolean containsPlayerToken(String text) {
        if (text == null || text.isEmpty()) return false;
        return indexOfIgnoreCase(text, "%player%", 0) >= 0 ||
                indexOfIgnoreCase(text, "%player_name%", 0) >= 0 ||
                indexOfIgnoreCase(text, "%name%", 0) >= 0 ||
                indexOfIgnoreCase(text, "%target%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{player}", 0) >= 0 ||
                indexOfIgnoreCase(text, "{player_name}", 0) >= 0 ||
                indexOfIgnoreCase(text, "{name}", 0) >= 0 ||
                indexOfIgnoreCase(text, "{target}", 0) >= 0;
    }

    private static String replacePlayerTokens(String text, String safePlayerName) {
        return replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(
                replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(
                        text,
                        "%player%", safePlayerName),
                        "%player_name%", safePlayerName),
                        "%name%", safePlayerName),
                        "%target%", safePlayerName),
                        "{player}", safePlayerName),
                        "{player_name}", safePlayerName),
                        "{name}", safePlayerName),
                        "{target}", safePlayerName);
    }

    private static boolean containsDisplayToken(String text) {
        return indexOfIgnoreCase(text, "%displayname%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{displayname}", 0) >= 0 ||
                indexOfIgnoreCase(text, "%display_name%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{display_name}", 0) >= 0 ||
                indexOfIgnoreCase(text, "%player_displayname%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{player_displayname}", 0) >= 0 ||
                indexOfIgnoreCase(text, "%player_display_name%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{player_display_name}", 0) >= 0;
    }

    private static String replaceDisplayTokens(String text, String display) {
        return replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(
                replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(
                        text,
                        "%player_display_name%", display),
                        "{player_display_name}", display),
                        "%player_displayname%", display),
                        "{player_displayname}", display),
                        "%displayname%", display),
                        "{displayname}", display),
                        "%display_name%", display),
                        "{display_name}", display);
    }

    private static boolean containsHealthToken(String text) {
        return indexOfIgnoreCase(text, "%player_health_scale%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{player_health_scale}", 0) >= 0 ||
                indexOfIgnoreCase(text, "%health%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{health}", 0) >= 0;
    }

    private static String replaceHealthTokens(String text, String health) {
        return replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(
                text,
                "%player_health_scale%", health),
                "{player_health_scale}", health),
                "%health%", health),
                "{health}", health);
    }

    private static boolean containsPingToken(String text) {
        return indexOfIgnoreCase(text, "%player_ping%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{player_ping}", 0) >= 0 ||
                indexOfIgnoreCase(text, "%ping%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{ping}", 0) >= 0;
    }

    private static String replacePingTokens(String text, String ping) {
        return replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(
                text,
                "%player_ping%", ping),
                "{player_ping}", ping),
                "%ping%", ping),
                "{ping}", ping);
    }

    private static boolean containsBalanceToken(String text) {
        return indexOfIgnoreCase(text, "%vault_eco_balance_formatted%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{vault_eco_balance_formatted}", 0) >= 0 ||
                indexOfIgnoreCase(text, "%vault_eco_balance%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{vault_eco_balance}", 0) >= 0 ||
                indexOfIgnoreCase(text, "%balance%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{balance}", 0) >= 0 ||
                indexOfIgnoreCase(text, "%money%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{money}", 0) >= 0;
    }

    private static String replaceBalanceTokens(String text, String balance) {
        return replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(
                replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(
                        text,
                        "%vault_eco_balance_formatted%", balance),
                        "{vault_eco_balance_formatted}", balance),
                        "%vault_eco_balance%", balance),
                        "{vault_eco_balance}", balance),
                        "%balance%", balance),
                        "{balance}", balance),
                        "%money%", balance),
                        "{money}", balance);
    }

    private static boolean containsPrefixToken(String text) {
        return indexOfIgnoreCase(text, "%luckperms_prefix%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{luckperms_prefix}", 0) >= 0 ||
                indexOfIgnoreCase(text, "%vault_prefix%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{vault_prefix}", 0) >= 0 ||
                indexOfIgnoreCase(text, "%prefix%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{prefix}", 0) >= 0;
    }

    private static String replacePrefixTokens(String text, String prefix) {
        return replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(
                replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(
                        text,
                        "%luckperms_prefix%", prefix),
                        "{luckperms_prefix}", prefix),
                        "%vault_prefix%", prefix),
                        "{vault_prefix}", prefix),
                        "%prefix%", prefix),
                        "{prefix}", prefix);
    }

    private static boolean containsSuffixToken(String text) {
        return indexOfIgnoreCase(text, "%luckperms_suffix%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{luckperms_suffix}", 0) >= 0 ||
                indexOfIgnoreCase(text, "%vault_suffix%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{vault_suffix}", 0) >= 0 ||
                indexOfIgnoreCase(text, "%suffix%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{suffix}", 0) >= 0;
    }

    private static String replaceSuffixTokens(String text, String suffix) {
        return replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(
                replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(
                        text,
                        "%luckperms_suffix%", suffix),
                        "{luckperms_suffix}", suffix),
                        "%vault_suffix%", suffix),
                        "{vault_suffix}", suffix),
                        "%suffix%", suffix),
                        "{suffix}", suffix);
    }

    private static boolean containsGroupToken(String text) {
        return indexOfIgnoreCase(text, "%luckperms_primary_group_name%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{luckperms_primary_group_name}", 0) >= 0 ||
                indexOfIgnoreCase(text, "%group%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{group}", 0) >= 0 ||
                indexOfIgnoreCase(text, "%rank%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{rank}", 0) >= 0;
    }

    private static String replaceGroupTokens(String text, String group) {
        return replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(
                replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(
                        text,
                        "%luckperms_primary_group_name%", group),
                        "{luckperms_primary_group_name}", group),
                        "%group%", group),
                        "{group}", group),
                        "%rank%", group),
                        "{rank}", group);
    }

    private static boolean containsServerToken(String text) {
        if (text == null || text.isEmpty()) return false;
        return indexOfIgnoreCase(text, "%server%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{server}", 0) >= 0 ||
                indexOfIgnoreCase(text, "%server_name%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{server_name}", 0) >= 0 ||
                indexOfIgnoreCase(text, "%server_id%", 0) >= 0 ||
                indexOfIgnoreCase(text, "{server_id}", 0) >= 0;
    }

    private static String replaceServerTokens(String text, String server) {
        return replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(
                replaceIgnoreCase(replaceIgnoreCase(replaceIgnoreCase(
                        text,
                        "%server_name%", server),
                        "{server_name}", server),
                        "%server_id%", server),
                        "{server_id}", server),
                        "%server%", server),
                        "{server}", server);
    }

    private static List<TagResolver> collectAnglePlaceholderResolvers(String template, PreparedPlaceholders prep) {
        if (prep == null || prep.lookup.isEmpty() || template == null || template.indexOf('<') < 0) return List.of();

        List<TagResolver> resolvers = null;
        int len = template.length();
        int i = 0;
        while (i < len) {
            int open = template.indexOf('<', i);
            if (open == -1) break;
            int close = template.indexOf('>', open + 1);
            if (close == -1) break;

            String tagContent = template.substring(open + 1, close).trim();
            if (!tagContent.isEmpty() && !tagContent.startsWith("/")) {
                int colon = tagContent.indexOf(':');
                String tagName = colon >= 0 ? tagContent.substring(0, colon).trim() : tagContent;
                String cleanKey = cleanPlaceholderKey(tagName);
                String rawValue = prep.lookup.get(cleanKey);
                if (rawValue != null) {
                    if (resolvers == null) {
                        resolvers = new ArrayList<>();
                    }
                    boolean isFormatting = isFormattingPlaceholder(cleanKey);
                    resolvers.add(isFormatting
                            ? Placeholder.parsed(cleanKey, rawValue)
                            : Placeholder.unparsed(cleanKey, rawValue));
                }
            }
            i = close + 1;
        }

        return resolvers != null ? resolvers : List.of();
    }

    private static List<TagResolver> collectAnglePlaceholderResolvers(String template, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty() || template.indexOf('<') < 0) return List.of();
        return collectAnglePlaceholderResolvers(template, new PreparedPlaceholders(placeholders));
    }

    private Component parseResolvedTemplate(String template, PreparedPlaceholders prep) {
        if (template == null || template.isEmpty()) return Component.empty();
        List<TagResolver> resolvers = collectAnglePlaceholderResolvers(template, prep);
        if (resolvers.isEmpty()) return parse(template);
        try {
            return MINI_MESSAGE.deserialize(convertLegacyToMiniMessage(template), TagResolver.resolver(resolvers));
        } catch (Throwable t) {
            return SECTION_SERIALIZER.deserialize(template.replace('&', '§'));
        }
    }

    private Component parseResolvedTemplate(String template, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) return parse(template);
        return parseResolvedTemplate(template, new PreparedPlaceholders(placeholders));
    }

    public boolean hasPlaceholderAPI() {
        try {
            return Bukkit.getServer() != null && Bukkit.getPluginManager() != null && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private ConfigService configService;

    public TextService() {
    }

    public void setConfigService(ConfigService configService) {
        this.configService = configService;
    }

    public String resolveMessage(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        // If the string contains markup or whitespace, treat it directly as a template rather than a config key
        if (input.contains(" ") || input.contains("\n") || input.contains("<") || input.contains("&") || input.contains("§")) {
            return input;
        }
        if (configService != null) {
            if (configService.getPvpMessages() != null && configService.getPvpMessages().contains(input)) {
                return configService.getPvpMessages().getString(input, input);
            }
            if (configService.getMessages() != null && configService.getMessages().contains(input)) {
                return configService.getMessages().getString(input, input);
            }
        }
        return input;
    }

    /**
     * Parses a string into an Adventure Component.
     * Supports both MiniMessage syntax and legacy '&' / hex color codes (#rrggbb and &#rrggbb).
     */
    public Component parse(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        try {
            String converted = convertLegacyToMiniMessage(input);
            return MINI_MESSAGE.deserialize(converted);
        } catch (Throwable t) {
            return SECTION_SERIALIZER.deserialize(input.replace('&', '§'));
        }
    }

    private static final int PARSE_CACHE_LIMIT = 2048;
    private static final int PARSE_CACHE_MAX_INPUT = 256;
    private final Map<String, Component> parseCache = new ConcurrentHashMap<>();

    /**
     * {@link #parse(String)} memoized for config templates rendered over and over (GUI lore, button names).
     * Components are immutable, so sharing them is safe. Keep player-typed text on {@link #parse(String)}.
     */
    public Component parseCached(String input) {
        if (input == null || input.isEmpty()) return Component.empty();
        if (input.length() > PARSE_CACHE_MAX_INPUT) return parse(input);
        Component cached = parseCache.get(input);
        if (cached != null) return cached;
        Component parsed = parse(input);
        // ponytail: wipe-when-full eviction; switch to an LRU if the hit rate ever drops.
        if (parseCache.size() >= PARSE_CACHE_LIMIT) parseCache.clear();
        parseCache.put(input, parsed);
        return parsed;
    }

    /**
     * Alias for parse(String) to easily convert formatted string to Adventure Component.
     */
    public Component toComponent(String input) {
        return parse(input);
    }

    /**
     * Parses a string with placeholders into an Adventure Component.
     * Escapes standard user inputs via Placeholder#unparsed to prevent tag injection,
     * while preserving formatting in prefixes and suffixes.
     */
    public Component parse(String input, Map<String, String> placeholders) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        if (placeholders == null || placeholders.isEmpty()) {
            return parse(input);
        }
        if (input.indexOf('%') == -1 && input.indexOf('{') == -1 && input.indexOf('<') == -1) {
            return parse(input);
        }

        PreparedPlaceholders prep = new PreparedPlaceholders(placeholders);
        String template = replacePlaceholdersFast(input, prep.lookup);
        return parseResolvedTemplate(template, prep);
    }

    /**
     * Parse for a specific player (applies default player placeholders, LuckPerms/Vault prefix/suffix, and PlaceholderAPI if present).
     */
    public Component parseForPlayer(Player player, String input, Map<String, String> placeholders) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        if (input.indexOf('%') == -1 && input.indexOf('{') == -1 && input.indexOf('<') == -1) {
            return parse(input);
        }
        PreparedPlaceholders prep = new PreparedPlaceholders(placeholders);
        String template = applyPlayerDefaults(player, input, prep);
        return parseResolvedTemplate(template, prep);
    }

    /**
     * Parse for a specific player with a pre-parsed Component placeholder.
     */
    public Component parseForPlayerWithComponent(Player player, String input, Map<String, String> placeholders, String componentKey, Component componentValue) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        PreparedPlaceholders prep = new PreparedPlaceholders(placeholders);
        String template = applyPlayerDefaults(player, input, prep);

        List<TagResolver> resolvers = new ArrayList<>(collectAnglePlaceholderResolvers(template, prep));

        if (componentKey != null && componentValue != null) {
            String cleanCompKey = cleanPlaceholderKey(componentKey);
            resolvers.add(Placeholder.component(cleanCompKey, componentValue));
            template = replaceIgnoreCase(template, "%" + cleanCompKey + "%", "<" + cleanCompKey + ">");
            template = replaceIgnoreCase(template, "{" + cleanCompKey + "}", "<" + cleanCompKey + ">");
            if (componentKey.startsWith("%") && componentKey.endsWith("%")) {
                template = replaceIgnoreCase(template, componentKey, "<" + cleanCompKey + ">");
            } else if (componentKey.startsWith("{") && componentKey.endsWith("}")) {
                template = replaceIgnoreCase(template, componentKey, "<" + cleanCompKey + ">");
            }
        }

        String converted = convertLegacyToMiniMessage(template);
        return MINI_MESSAGE.deserialize(converted, TagResolver.resolver(resolvers));
    }

    public String getPlayerPrefix(Player player) {
        if (player == null) return "";
        if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            try {
                net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
                net.luckperms.api.model.user.User user = lp.getUserManager().getUser(player.getUniqueId());
                if (user != null) {
                    String p = user.getCachedData().getMetaData().getPrefix();
                    if (p != null && !p.isEmpty()) return p;
                }
            } catch (Throwable ignored) {}
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            try {
                org.bukkit.plugin.RegisteredServiceProvider<net.milkbowl.vault.chat.Chat> rsp =
                        Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.chat.Chat.class);
                if (rsp != null && rsp.getProvider() != null) {
                    String p = rsp.getProvider().getPlayerPrefix(player);
                    if (p != null && !p.isEmpty()) return p;
                }
            } catch (Throwable ignored) {}
        }
        return "";
    }

    public String getPlayerSuffix(Player player) {
        if (player == null) return "";
        if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            try {
                net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
                net.luckperms.api.model.user.User user = lp.getUserManager().getUser(player.getUniqueId());
                if (user != null) {
                    String s = user.getCachedData().getMetaData().getSuffix();
                    if (s != null && !s.isEmpty()) return s;
                }
            } catch (Throwable ignored) {}
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            try {
                org.bukkit.plugin.RegisteredServiceProvider<net.milkbowl.vault.chat.Chat> rsp =
                        Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.chat.Chat.class);
                if (rsp != null && rsp.getProvider() != null) {
                    String s = rsp.getProvider().getPlayerSuffix(player);
                    if (s != null && !s.isEmpty()) return s;
                }
            } catch (Throwable ignored) {}
        }
        return "";
    }

    public String getPlayerPrimaryGroup(Player player) {
        if (player == null) return "default";
        if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            try {
                net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
                net.luckperms.api.model.user.User user = lp.getUserManager().getUser(player.getUniqueId());
                if (user != null) {
                    String g = user.getPrimaryGroup();
                    if (g != null && !g.isEmpty()) return g;
                }
            } catch (Throwable ignored) {}
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            try {
                org.bukkit.plugin.RegisteredServiceProvider<net.milkbowl.vault.permission.Permission> rsp =
                        Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.permission.Permission.class);
                if (rsp != null && rsp.getProvider() != null) {
                    String g = rsp.getProvider().getPrimaryGroup(player);
                    if (g != null && !g.isEmpty()) return g;
                }
            } catch (Throwable ignored) {}
        }
        return "default";
    }

    public String getPlayerBalance(Player player) {
        if (player == null) return "0.00";
        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            try {
                org.bukkit.plugin.RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp =
                        Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
                if (rsp != null && rsp.getProvider() != null) {
                    double bal = rsp.getProvider().getBalance(player);
                    return String.format(Locale.US, "%,.2f", bal);
                }
            } catch (Throwable ignored) {}
        }
        return "0.00";
    }

    public String applyPlayerDefaults(Player player, String template) {
        return applyPlayerDefaults(player, template, Collections.emptyMap());
    }

    public String applyPlayerDefaults(Player player, String template, Map<String, String> explicitPlaceholders) {
        if (template == null || template.isEmpty()) return "";
        if (template.indexOf('%') == -1 && template.indexOf('{') == -1 && template.indexOf('<') == -1) {
            return template;
        }
        PreparedPlaceholders prep = new PreparedPlaceholders(explicitPlaceholders);
        return applyPlayerDefaults(player, template, prep);
    }

    public String applyPlayerDefaults(Player player, String template, PreparedPlaceholders prep) {
        if (template == null || template.isEmpty()) return "";
        if (template.indexOf('%') == -1 && template.indexOf('{') == -1 && template.indexOf('<') == -1) {
            return template;
        }

        String targetPlayerName = prep.explicitPlayerName != null ? prep.explicitPlayerName : (player != null ? player.getName() : null);

        // Pre-substitute player name tokens so nested PAPI expressions receive the resolved player name
        if (targetPlayerName != null && (template.indexOf('%') != -1 || template.indexOf('{') != -1)) {
            if (containsPlayerToken(template)) {
                String safePlayerName = safeEscapeTags(targetPlayerName);
                template = replacePlayerTokens(template, safePlayerName);
            }
        }

        // Pre-substitute explicit metadata placeholders from prep.lookup before PlaceholderAPI evaluates.
        // This prevents PlaceholderAPI from erasing explicit caller/cross-server placeholders (e.g. prefix, suffix,
        // rank, health, ping, balance, displayname, server) when player == null or overriding caller overrides,
        // while leaving general user inputs (like {reason} or {message}) untouched until after PAPI.
        if (prep.hasExplicitPrefix && containsPrefixToken(template)) {
            String val = prep.lookup.get("prefix");
            if (val != null) template = replacePrefixTokens(template, val);
        }
        if (prep.hasExplicitSuffix && containsSuffixToken(template)) {
            String val = prep.lookup.get("suffix");
            if (val != null) template = replaceSuffixTokens(template, val);
        }
        if (prep.hasExplicitDisplay && containsDisplayToken(template)) {
            String val = prep.lookup.get("displayname");
            if (val != null) template = replaceDisplayTokens(template, val);
        }
        if (prep.hasExplicitGroup && containsGroupToken(template)) {
            String val = prep.lookup.get("group");
            if (val != null) template = replaceGroupTokens(template, val);
        }
        if (prep.hasExplicitHealth && containsHealthToken(template)) {
            String val = prep.lookup.get("health");
            if (val != null) template = replaceHealthTokens(template, val);
        }
        if (prep.hasExplicitPing && containsPingToken(template)) {
            String val = prep.lookup.get("ping");
            if (val != null) template = replacePingTokens(template, val);
        }
        if (prep.hasExplicitBalance && containsBalanceToken(template)) {
            String val = prep.lookup.get("balance");
            if (val != null) template = replaceBalanceTokens(template, val);
        }
        if (prep.hasExplicitServer && containsServerToken(template)) {
            String val = prep.lookup.get("server");
            if (val != null) template = replaceServerTokens(template, val);
        }

        // When player == null, resolve all player-scoped metadata fallbacks BEFORE PlaceholderAPI.
        // This ensures PlaceholderAPI cannot wipe unmapped player tokens (like %player_health_scale%
        // or %vault_eco_balance%) to empty string, and only evaluates remaining global server tokens.
        if (player == null && (template.indexOf('%') != -1 || template.indexOf('{') != -1)) {
            if (targetPlayerName == null && !prep.hasExplicitPlayer && containsPlayerToken(template)) {
                template = replacePlayerTokens(template, "");
            }
            if (!prep.hasExplicitDisplay && containsDisplayToken(template)) {
                String fallbackDisplay = targetPlayerName != null ? safeEscapeTags(targetPlayerName) : "";
                template = replaceDisplayTokens(template, fallbackDisplay);
            }
            if (!prep.hasExplicitPrefix && containsPrefixToken(template)) {
                template = replacePrefixTokens(template, "");
            }
            if (!prep.hasExplicitSuffix && containsSuffixToken(template)) {
                template = replaceSuffixTokens(template, "");
            }
            if (!prep.hasExplicitGroup && containsGroupToken(template)) {
                template = replaceGroupTokens(template, "");
            }
            if (!prep.hasExplicitHealth && containsHealthToken(template)) {
                template = replaceHealthTokens(template, "20");
            }
            if (!prep.hasExplicitPing && containsPingToken(template)) {
                template = replacePingTokens(template, "0");
            }
            if (!prep.hasExplicitBalance && containsBalanceToken(template)) {
                template = replaceBalanceTokens(template, "0.00");
            }
        }

        // Evaluate PlaceholderAPI on the template for remaining tokens (global server placeholders when player == null, or player placeholders when player != null)
        if (hasPlaceholderAPI() && template.indexOf('%') != -1) {
            try {
                template = PlaceholderAPI.setPlaceholders(player, template);
            } catch (Throwable ignored) {}
        }

        // Strip customfontlegacy formatting prefix if PlaceholderAPI fails to expand it
        if (template.contains("%customfontlegacy")) {
            template = CUSTOM_FONT_LEGACY_PATTERN.matcher(template).replaceAll("${name}");
        }

        if (player != null && (template.indexOf('%') != -1 || template.indexOf('{') != -1)) {
            if (!prep.hasExplicitDisplay && containsDisplayToken(template)) {
                String displayName = player.getDisplayName() != null ? player.getDisplayName() : player.getName();
                String displayConverted = convertLegacyToMiniMessage(displayName);
                template = replaceDisplayTokens(template, displayConverted);
            }
            if (!prep.hasExplicitHealth && containsHealthToken(template)) {
                String healthScale = String.valueOf(player.isHealthScaled() ? (int) player.getHealthScale() : (int) Math.round(player.getHealth()));
                template = replaceHealthTokens(template, healthScale);
            }
            if (!prep.hasExplicitPing && containsPingToken(template)) {
                String ping = String.valueOf(player.getPing());
                template = replacePingTokens(template, ping);
            }
            if (!prep.hasExplicitBalance && containsBalanceToken(template)) {
                String balance = getPlayerBalance(player);
                template = replaceBalanceTokens(template, balance);
            }
            if (!prep.hasExplicitPrefix && containsPrefixToken(template)) {
                String prefixConverted = convertLegacyToMiniMessage(getPlayerPrefix(player));
                template = replacePrefixTokens(template, prefixConverted);
            }
            if (!prep.hasExplicitSuffix && containsSuffixToken(template)) {
                String suffixConverted = convertLegacyToMiniMessage(getPlayerSuffix(player));
                template = replaceSuffixTokens(template, suffixConverted);
            }
            if (!prep.hasExplicitGroup && containsGroupToken(template)) {
                String group = getPlayerPrimaryGroup(player);
                template = replaceGroupTokens(template, group);
            }
        }

        // Apply explicit caller placeholders again if any remain or were generated
        if (!prep.lookup.isEmpty() && (template.indexOf('%') != -1 || template.indexOf('{') != -1)) {
            template = replacePlaceholdersFast(template, prep.lookup);
        }

        return template;
    }

    /**
     * Parses a list of strings into a list of Adventure Components.
     */
    public List<Component> parseList(List<String> lines, Map<String, String> placeholders) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        PreparedPlaceholders prep = new PreparedPlaceholders(placeholders);
        List<Component> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                result.add(Component.empty());
                continue;
            }
            if (line.indexOf('%') == -1 && line.indexOf('{') == -1 && line.indexOf('<') == -1) {
                result.add(parse(line));
                continue;
            }
            String template = replacePlaceholdersFast(line, prep.lookup);
            result.add(parseResolvedTemplate(template, prep));
        }
        return result;
    }

    public List<Component> parseListForPlayer(Player player, List<String> lines, Map<String, String> placeholders) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        PreparedPlaceholders prep = new PreparedPlaceholders(placeholders);
        List<Component> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                result.add(Component.empty());
                continue;
            }
            if (line.indexOf('%') == -1 && line.indexOf('{') == -1 && line.indexOf('<') == -1) {
                result.add(parse(line));
                continue;
            }
            String template = applyPlayerDefaults(player, line, prep);
            result.add(parseResolvedTemplate(template, prep));
        }
        return result;
    }

    /**
     * Send formatted message to CommandSender with varargs placeholders (key, value, key, value...).
     */
    public void send(CommandSender sender, String messageKey, String... replacements) {
        if (sender == null || messageKey == null || messageKey.isEmpty()) return;
        Map<String, String> map = new java.util.LinkedHashMap<>();
        if (replacements != null && replacements.length > 0) {
            for (int i = 0; i < replacements.length - 1; i += 2) {
                map.put(replacements[i], replacements[i + 1]);
            }
        }
        sendMessage(sender, messageKey, map);
    }

    /**
     * Resolves message string with optional replacements.
     */
    public String getMessage(String key, String... replacements) {
        String resolved = resolveMessage(key);
        if (replacements != null && replacements.length > 0) {
            for (int i = 0; i < replacements.length - 1; i += 2) {
                String k = replacements[i];
                String v = replacements[i + 1];
                resolved = resolved.replace(k, v);
            }
        }
        return resolved;
    }

    /**
     * Send formatted message to CommandSender without placeholders.
     */
    public void sendMessage(CommandSender sender, String message) {
        sendMessage(sender, message, java.util.Collections.emptyMap());
    }

    /**
     * Send formatted message to CommandSender.
     */
    public void sendMessage(CommandSender sender, String message, Map<String, String> placeholders) {
        if (sender == null || message == null || message.isEmpty() || message.equalsIgnoreCase("DISABLED")) {
            return;
        }
        String resolved = resolveMessage(message);
        if (sender instanceof Player player) {
            if (!player.isOnline()) return;
            player.sendMessage(parseForPlayer(player, resolved, placeholders));
        } else {
            Map<String, String> merged = new java.util.LinkedHashMap<>();
            merged.put("%player%", sender.getName());
            merged.put("%player_name%", sender.getName());
            merged.put("%name%", sender.getName());
            if (placeholders != null) {
                merged.putAll(placeholders);
            }
            sender.sendMessage(parse(resolved, merged));
        }
    }

    /**
     * Broadcast formatted message to all online players and console.
     */
    public void broadcastMessage(String message, Map<String, String> placeholders) {
        if (message == null || message.isEmpty() || message.equalsIgnoreCase("DISABLED")) {
            return;
        }
        String resolved = resolveMessage(message);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.isOnline()) {
                sendMessage(online, resolved, placeholders);
            }
        }
        sendMessage(Bukkit.getConsoleSender(), resolved, placeholders);
    }

    /**
     * Send action bar to player.
     */
    public void sendActionBar(Player player, String message, Map<String, String> placeholders) {
        if (player == null || !player.isOnline() || message == null || message.isEmpty() || message.equalsIgnoreCase("DISABLED")) {
            return;
        }
        String resolved = resolveMessage(message);
        player.sendActionBar(parseForPlayer(player, resolved, placeholders));
    }

    /**
     * Broadcast formatted action bar to all online players.
     */
    public void broadcastActionBar(String message, Map<String, String> placeholders) {
        broadcastActionBar(message, placeholders, null);
    }

    /**
     * Broadcast formatted action bar to online players matching a filter predicate.
     */
    public void broadcastActionBar(String message, Map<String, String> placeholders, java.util.function.Predicate<Player> filter) {
        if (message == null || message.isEmpty() || message.equalsIgnoreCase("DISABLED")) {
            return;
        }
        String resolved = resolveMessage(message);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online != null && online.isOnline() && (filter == null || filter.test(online))) {
                sendActionBar(online, resolved, placeholders);
            }
        }
    }

    /**
     * Send title and subtitle to player.
     */
    public void sendTitle(Player player, String titleText, String subtitleText, int fadeInTicks, int stayTicks, int fadeOutTicks, Map<String, String> placeholders) {
        if (player == null || !player.isOnline()) return;
        String resolvedTitle = resolveMessage(titleText);
        String resolvedSub = resolveMessage(subtitleText);
        Component titleComp = (resolvedTitle != null && !resolvedTitle.isEmpty()) ? parseForPlayer(player, resolvedTitle, placeholders) : Component.empty();
        Component subComp = (resolvedSub != null && !resolvedSub.isEmpty()) ? parseForPlayer(player, resolvedSub, placeholders) : Component.empty();

        Title.Times times = Title.Times.times(
                Duration.ofMillis(fadeInTicks * 50L),
                Duration.ofMillis(stayTicks * 50L),
                Duration.ofMillis(fadeOutTicks * 50L)
        );
        player.showTitle(Title.title(titleComp, subComp, times));
    }

    /**
     * Convert legacy '&', '§', '&#RRGGBB', and raw '#RRGGBB' formatting into MiniMessage tags.
     * Tokenizes text so that MiniMessage tags and attribute values are never corrupted.
     */
    public static String convertLegacyToMiniMessage(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }

        String text = message.replace("\r\n", "\n").replace('\r', '\n');
        if (text.toLowerCase(Locale.ROOT).contains("<gradient:") || text.toLowerCase(Locale.ROOT).contains("<transition:")) {
            text = GRADIENT_COMMA_PATTERN.matcher(text).replaceAll(mr -> {
                String tag = mr.group(1);
                String args = mr.group(2).replace(',', ':');
                return "<" + tag + ":" + args + ">";
            });
        }

        StringBuilder result = new StringBuilder(text.length() + 32);
        StringBuilder buffer = new StringBuilder();
        boolean insideTag = false;
        char quoteChar = 0;

        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);

            if (!insideTag) {
                if (c == '<') {
                    int backslashes = 0;
                    for (int j = i - 1; j >= 0 && text.charAt(j) == '\\'; j--) {
                        backslashes++;
                    }
                    if (backslashes % 2 != 0) {
                        // Escaped tag \<tag> -> do not treat as opening tag
                        buffer.append(c);
                        continue;
                    }
                    if (text.regionMatches(true, i, "<br>", 0, 4)) {
                        buffer.append("<newline>");
                        i += 3;
                        continue;
                    }
                    if (text.regionMatches(true, i, "<br/>", 0, 5)) {
                        buffer.append("<newline>");
                        i += 4;
                        continue;
                    }
                    if (text.regionMatches(true, i, "<br />", 0, 6)) {
                        buffer.append("<newline>");
                        i += 5;
                        continue;
                    }
                    if (buffer.length() > 0) {
                        result.append(convertLegacyChunk(buffer.toString()));
                        buffer.setLength(0);
                    }
                    insideTag = true;
                    quoteChar = 0;
                    buffer.append(c);
                } else {
                    buffer.append(c);
                }
            } else {
                buffer.append(c);
                if (quoteChar == 0) {
                    if (c == '\'' || c == '"') {
                        quoteChar = c;
                    } else if (c == '>') {
                        insideTag = false;
                        result.append(buffer.toString());
                        buffer.setLength(0);
                    }
                } else {
                    if (c == quoteChar) {
                        int backslashes = 0;
                        for (int j = i - 1; j >= 0 && text.charAt(j) == '\\'; j--) {
                            backslashes++;
                        }
                        if (backslashes % 2 == 0) {
                            quoteChar = 0;
                        }
                    }
                }
            }
        }

        if (buffer.length() > 0) {
            if (insideTag) {
                result.append(convertLegacyChunk(buffer.toString()));
            } else {
                result.append(convertLegacyChunk(buffer.toString()));
            }
        }

        return result.toString();
    }

    private static String convertLegacyChunk(String text) {
        if (text == null || text.isEmpty()) return "";
        if (text.indexOf('&') == -1 && text.indexOf('§') == -1 && text.indexOf('#') == -1) {
            return text;
        }

        String result = text;

        // Convert &x&r&r&g&g&b&b and §x§r§r§g§g§b§b legacy hex sequences
        if (result.indexOf('&') != -1 || result.indexOf('§') != -1) {
            Matcher legacyHexMatcher = LEGACY_AMP_HEX_PATTERN.matcher(result);
            if (legacyHexMatcher.find()) {
                result = legacyHexMatcher.replaceAll(mr -> {
                    String raw = mr.group().replace("&", "").replace("§", "").substring(1);
                    return "<#" + raw + ">";
                });
            }
        }

        if (result.contains("&#") || result.contains("§#")) {
            result = HEX_SEMI_PATTERN.matcher(result).replaceAll("<#$1>");
            result = HEX_AMP_PATTERN.matcher(result).replaceAll("<#$1>");
        }

        // Convert bracketed hex patterns {#RRGGBB} and [#RRGGBB]
        if (result.contains("{#") || result.contains("[#")) {
            result = BRACKET_HEX_PATTERN.matcher(result).replaceAll("<#$1>");
        }

        if (result.contains("#")) {
            result = RAW_HEX_PATTERN.matcher(result).replaceAll("<#$1>");
        }

        // Convert legacy formatting and color codes
        if (result.indexOf('&') != -1 || result.indexOf('§') != -1) {
            StringBuilder out = new StringBuilder(result.length() + 32);
            int len = result.length();
            for (int i = 0; i < len; i++) {
                char c = result.charAt(i);
                if ((c == '&' || c == '§') && i + 1 < len) {
                    char next = result.charAt(i + 1);
                    String rep = getLegacyReplacement(next);
                    if (rep != null) {
                        out.append(rep);
                        i++;
                        continue;
                    }
                }
                out.append(c);
            }
            result = out.toString();
        }

        return result;
    }

    private static String getLegacyReplacement(char c) {
        return switch (Character.toLowerCase(c)) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<!bold><!italic><!underlined><!strikethrough><!obfuscated><white>";
            default -> null;
        };
    }

    /**
     * Sanitizes raw chat input based on player formatting permissions.
     */
    public static String sanitizeChatInput(Player player, String rawInput) {
        if (rawInput == null || rawInput.isEmpty()) return rawInput;
        boolean allowColor = player != null && (player.hasPermission("friendlycore.chat.color") || player.hasPermission("friendlycore.chat.colors") || player.hasPermission("friendlycore.chat.admin"));
        boolean allowHex = player != null && (player.hasPermission("friendlycore.chat.hex") || player.hasPermission("friendlycore.chat.admin"));
        boolean allowFormat = player != null && (player.hasPermission("friendlycore.chat.format") || player.hasPermission("friendlycore.chat.formatting") || player.hasPermission("friendlycore.chat.admin"));

        String text = rawInput;
        if (!allowHex) {
            text = text.replaceAll("(?i)(?:&|§)x(?:(?:&|§)[0-9a-fA-F]){6}", "");
            text = text.replaceAll("(?i)(?:&|§)#[0-9a-fA-F]{6}", "");
            text = text.replaceAll("(?i)#[0-9a-fA-F]{6}", "");
        }
        if (!allowColor) {
            text = text.replaceAll("(?i)[&§][0-9a-fA-F]", "");
        }
        if (!allowFormat) {
            text = text.replaceAll("(?i)[&§][k-orK-OR]", "");
        }
        return text;
    }

    /**
     * Plays sounds using Adventure Key identifiers with Bukkit Registry fallback.
     */
    public static void playSound(Player player, String soundName, float volume, float pitch) {
        if (player == null || !player.isOnline() || soundName == null || soundName.isEmpty() || soundName.equalsIgnoreCase("none")) return;
        try {
            player.playSound(net.kyori.adventure.sound.Sound.sound(
                    resolveSoundKey(soundName),
                    net.kyori.adventure.sound.Sound.Source.PLAYER,
                    volume,
                    pitch
            ));
        } catch (Throwable t) {
            try {
                org.bukkit.Sound bukkitSound = org.bukkit.Registry.SOUNDS.get(org.bukkit.NamespacedKey.minecraft(soundName.toLowerCase(Locale.ROOT).replace('.', '_')));
                if (bukkitSound != null) {
                    player.playSound(player.getLocation(), bukkitSound, volume, pitch);
                }
            } catch (Throwable ignored) {}
        }
    }

    public static void playSound(Location loc, String soundName, float volume, float pitch) {
        if (loc == null || loc.getWorld() == null || soundName == null || soundName.isEmpty() || soundName.equalsIgnoreCase("none")) return;
        try {
            loc.getWorld().playSound(
                    net.kyori.adventure.sound.Sound.sound(
                            resolveSoundKey(soundName),
                            net.kyori.adventure.sound.Sound.Source.BLOCK,
                            volume,
                            pitch
                    ),
                    loc.getX(), loc.getY(), loc.getZ()
            );
        } catch (Throwable t) {
            try {
                org.bukkit.Sound bukkitSound = org.bukkit.Registry.SOUNDS.get(org.bukkit.NamespacedKey.minecraft(soundName.toLowerCase(Locale.ROOT).replace('.', '_')));
                if (bukkitSound != null) {
                    loc.getWorld().playSound(loc, bukkitSound, volume, pitch);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static net.kyori.adventure.key.Key resolveSoundKey(String soundName) {
        // Bukkit enum names need the registry mapping: NOTE_BLOCK must remain note_block in resource keys.
        if (soundName.indexOf('.') == -1 && soundName.indexOf(':') == -1) {
            try {
                return org.bukkit.Sound.valueOf(soundName.toUpperCase(Locale.ROOT)).getKey();
            } catch (IllegalArgumentException ignored) {}
        }
        return net.kyori.adventure.key.Key.key(soundName.toLowerCase(Locale.ROOT));
    }

    public static void playSoundAll(String soundName, float volume, float pitch) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOnline()) {
                playSound(p, soundName, volume, pitch);
            }
        }
    }
}
