package dev.lyy.friendlycore.modules.chat.filter;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.chat.model.ChatMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-performance orchestrator for all chat filtering, anti-spam, and rule enforcement.
 */
public class ChatFilterManager {

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private final FriendlyCore plugin;
    private final LeetMap leetMap = new LeetMap();
    private final RegexFactory regexFactory;

    private final List<ChatRule> blacklistRules = new CopyOnWriteArrayList<>();
    private final List<ChatRule> whitelistRules = new CopyOnWriteArrayList<>();

    private final Map<UUID, Long> messageCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastMessages = new ConcurrentHashMap<>();

    private volatile boolean privateMessagesFilterDisabled;
    private volatile boolean similarityEnabled;
    private volatile double similarityThreshold;
    private volatile boolean antiFloodEnabled;
    private volatile int maxMessageLength;
    private volatile int maxRepeatedCharacters;
    private volatile boolean antiFloodBroadcastStaff;
    private volatile boolean antiCapsEnabled;
    private volatile int maxCaps;
    private volatile boolean cooldownEnabled;
    private volatile int cooldownSeconds;
    private volatile boolean chatRulesEnabled;
    private volatile boolean aggressiveRegexes;
    private volatile String replaceWith;
    private volatile boolean cancelMessageOnFlag;
    private volatile boolean alertStaffOnFlag;

    private volatile boolean blockIps;
    private volatile boolean blockUrls;
    private volatile boolean blockDomains;
    private volatile boolean blockPhoneNumbers;
    private volatile boolean blockAllUnicodes;

    private volatile boolean pointsEnabled;
    private volatile boolean resetPointsEnabled;
    private volatile int resetPointsMinutes;
    private volatile int pointsIp;
    private volatile int pointsUrl;
    private volatile int pointsDomain;
    private volatile int pointsPhone;
    private volatile int pointsUnicode;
    private final Map<String, ActionThreshold> actionThresholds = new LinkedHashMap<>();

    public record TextSpan(int start, int end) {
        public boolean contains(int s, int e) {
            return this.start <= s && e <= this.end;
        }
    }

    public record ActionThreshold(int pointsRequired, List<String> commands) {}

    public ChatFilterManager(FriendlyCore plugin) {
        this.plugin = plugin;
        this.regexFactory = new RegexFactory(plugin, leetMap);
    }

    public void load() {
        FileConfiguration filterConfig = plugin.getConfigService().getConfig("modules/chat/chat-filter.yml");
        FileConfiguration rulesConfig = plugin.getConfigService().getConfig("modules/chat/filter-rules.yml");
        FileConfiguration whitelistConfig = plugin.getConfigService().getConfig("modules/chat/filter-whitelist.yml");
        FileConfiguration leetConfig = plugin.getConfigService().getConfig("modules/chat/leetmap.yml");

        leetMap.load(leetConfig);
        regexFactory.reload();

        this.privateMessagesFilterDisabled = filterConfig.getBoolean("private-messages.disable-filter", false);
        this.similarityEnabled = filterConfig.getBoolean("similarity-check.enabled", true);
        this.similarityThreshold = Math.max(0.0, Math.min(1.0, filterConfig.getDouble("similarity-check.threshold", 0.80)));

        this.antiFloodEnabled = filterConfig.getBoolean("anti-flood.enabled", true);
        this.maxMessageLength = Math.max(1, filterConfig.getInt("anti-flood.max-length-message", 120));
        this.maxRepeatedCharacters = Math.max(1, filterConfig.getInt("anti-flood.max-repeated-characters", 4));
        this.antiFloodBroadcastStaff = filterConfig.getBoolean("anti-flood.broadcast-staff", true);

        this.antiCapsEnabled = filterConfig.getBoolean("anti-caps.enabled", true);
        this.maxCaps = Math.max(1, filterConfig.getInt("anti-caps.max-caps", 5));

        this.cooldownEnabled = filterConfig.getBoolean("cooldown-messages.enabled", true);
        this.cooldownSeconds = Math.max(0, filterConfig.getInt("cooldown-messages.seconds", 3));

        this.chatRulesEnabled = filterConfig.getBoolean("chat-rules.enabled", true);
        this.aggressiveRegexes = filterConfig.getBoolean("chat-rules.use-aggressive-regexes", false);
        this.replaceWith = filterConfig.getString("chat-rules.replace-with", "*");
        if (this.replaceWith == null || this.replaceWith.isEmpty()) this.replaceWith = "*";
        this.cancelMessageOnFlag = filterConfig.getBoolean("chat-rules.cancel-message", true);
        this.alertStaffOnFlag = filterConfig.getBoolean("chat-rules.alert-staff", true);

        this.blockIps = filterConfig.getBoolean("default-rules.block-ips", true);
        this.blockUrls = filterConfig.getBoolean("default-rules.block-url", true);
        this.blockDomains = filterConfig.getBoolean("default-rules.block-domains", true);
        this.blockPhoneNumbers = filterConfig.getBoolean("default-rules.block-phone-numbers", true);
        this.blockAllUnicodes = filterConfig.getBoolean("default-rules.block-all-unicodes", false);

        this.pointsEnabled = filterConfig.getBoolean("actions-and-points.enabled", true);
        this.resetPointsEnabled = filterConfig.getBoolean("actions-and-points.reset-points.enabled", true);
        this.resetPointsMinutes = Math.max(1, filterConfig.getInt("actions-and-points.reset-points.after-last-infraction-minutes", 10));
        this.pointsIp = filterConfig.getInt("actions-and-points.points-when-flag-ip-rule", 3);
        this.pointsUrl = filterConfig.getInt("actions-and-points.points-when-flag-url-rule", 3);
        this.pointsDomain = filterConfig.getInt("actions-and-points.points-when-flag-domain-rule", 3);
        this.pointsPhone = filterConfig.getInt("actions-and-points.points-when-flag-phone-rule", 3);
        this.pointsUnicode = filterConfig.getInt("actions-and-points.points-when-flag-unicode-rule", 1);

        actionThresholds.clear();
        ConfigurationSection actionsSec = filterConfig.getConfigurationSection("actions-and-points.actions-when-points-reached");
        if (actionsSec != null) {
            for (String key : actionsSec.getKeys(false)) {
                int pts = actionsSec.getInt(key + ".points-required", 50);
                List<String> cmds = actionsSec.getStringList(key + ".commands-to-execute");
                actionThresholds.put(key, new ActionThreshold(pts, cmds));
            }
        }

        loadRules(rulesConfig, whitelistConfig);
    }

    private void loadRules(FileConfiguration rulesConfig, FileConfiguration whitelistConfig) {
        blacklistRules.clear();
        whitelistRules.clear();

        if (whitelistConfig != null) {
            for (String domain : whitelistConfig.getStringList("whitelist.domains")) {
                whitelistRules.add(new ChatRule(domain, ChatRule.RuleType.WHITELIST, regexFactory.generateWhitelistPattern(domain), 0));
            }
            for (String ip : whitelistConfig.getStringList("whitelist.ips")) {
                whitelistRules.add(new ChatRule(ip, ChatRule.RuleType.WHITELIST, regexFactory.generateWhitelistPattern(ip), 0));
            }
            for (String word : whitelistConfig.getStringList("whitelist.words")) {
                whitelistRules.add(new ChatRule(word, ChatRule.RuleType.WHITELIST, regexFactory.generateWhitelistPattern(word), 0));
            }
        }

        // Built-in pattern rules (IPs, URLs, domains, phones, unicodes)
        if (blockIps) {
            blacklistRules.add(new ChatRule("IP", ChatRule.RuleType.IP, regexFactory.getIpPattern(), pointsIp));
        }
        if (blockUrls) {
            blacklistRules.add(new ChatRule("URL", ChatRule.RuleType.URL, regexFactory.getUrlPattern(), pointsUrl));
        }
        if (blockDomains) {
            blacklistRules.add(new ChatRule("DOMAIN", ChatRule.RuleType.DOMAIN, regexFactory.getDomainPattern(), pointsDomain));
        }
        if (blockPhoneNumbers) {
            blacklistRules.add(new ChatRule("PHONE", ChatRule.RuleType.PHONE, regexFactory.getPhonePattern(), pointsPhone));
        }
        if (blockAllUnicodes) {
            blacklistRules.add(new ChatRule("UNICODE", ChatRule.RuleType.UNICODE, regexFactory.getUnicodePattern(), pointsUnicode));
        }

        // Custom word categories and advanced regex rules
        if (rulesConfig != null) {
            ConfigurationSection rulesSec = rulesConfig.getConfigurationSection("rules");
            if (rulesSec != null) {
                for (String category : rulesSec.getKeys(false)) {
                    int pts = rulesSec.getInt(category + ".points", 3);
                    List<String> words = rulesSec.getStringList(category + ".words");
                    ChatRule.RuleType rtype = category.equalsIgnoreCase("racism") ? ChatRule.RuleType.RACISM : ChatRule.RuleType.SWEARING;
                    for (String w : words) {
                        Pattern pat = regexFactory.generateWordPattern(w, aggressiveRegexes);
                        if (pat != null) {
                            blacklistRules.add(new ChatRule(w, rtype, pat, pts));
                        }
                    }
                }
            }

            ConfigurationSection advSec = rulesConfig.getConfigurationSection("advanced");
            if (advSec != null) {
                for (String category : advSec.getKeys(false)) {
                    int pts = advSec.getInt(category + ".points", 10);
                    List<String> regexes = advSec.getStringList(category + ".regexes");
                    for (String r : regexes) {
                        try {
                            Pattern pat = Pattern.compile(r);
                            blacklistRules.add(new ChatRule(category, ChatRule.RuleType.CUSTOM, pat, pts));
                        } catch (Exception e) {
                            plugin.getLogger().log(Level.WARNING, "Invalid custom regex in filter-rules.yml: " + r, e);
                        }
                    }
                }
            }
        }
    }

    /**
     * Filters and processes an incoming ChatMessage. Returns true if message is approved, false if cancelled.
     */
    public boolean processMessage(ChatMessage chatMessage, boolean isPrivateMessage) {
        if (chatMessage == null || chatMessage.isCancelled()) return false;
        Player player = chatMessage.getSender();
        String raw = chatMessage.getRawMessage();

        if (raw == null || raw.trim().isEmpty()) {
            chatMessage.cancel("message-empty");
            if (player != null) {
                plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml", "chat-filter.message-empty", "<red>Please enter a message to send.</red>"));
            }
            return false;
        }

        if (player != null && cooldownEnabled && !player.hasPermission("friendlycore.chat.bypass.cooldown")) {
            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();
            long cooldownMillis = Math.max(0L, cooldownSeconds * 1000L);
            AtomicLong remainingMillis = new AtomicLong();
            messageCooldowns.compute(uuid, (ignored, lastTime) -> {
                long elapsed = lastTime == null ? cooldownMillis : Math.max(0L, now - lastTime);
                if (lastTime != null && elapsed < cooldownMillis) {
                    remainingMillis.set(cooldownMillis - elapsed);
                    return lastTime;
                }
                return now;
            });
            if (remainingMillis.get() > 0L) {
                long remainingSeconds = Math.max(1L, (remainingMillis.get() + 999L) / 1000L);
                chatMessage.cancel("cooldown");
                plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml", "chat-filter.cooldown", "<red>Please wait %seconds%s before sending another message.</red>"), Map.of("%seconds%", String.valueOf(remainingSeconds)));
                return false;
            }
        }

        // Anti-caps: lowercase message if upper-case threshold exceeded
        if (player != null && antiCapsEnabled && !player.hasPermission("friendlycore.chat.bypass.anticaps")) {
            int capsCount = 0;
            for (char c : raw.toCharArray()) {
                if (Character.isUpperCase(c)) {
                    capsCount++;
                }
            }
            if (capsCount >= maxCaps && raw.length() > maxCaps) {
                raw = raw.toLowerCase();
                chatMessage.setRawMessage(raw);
            }
        }

        // Anti-flood: message length and repetitive character checks
        if (player != null && antiFloodEnabled && !player.hasPermission("friendlycore.chat.bypass.flood")) {
            if (raw.length() > maxMessageLength) {
                chatMessage.cancel("message-too-long");
                plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml", "chat-filter.message-too-long", "<red>Your message is too long and was cancelled.</red>"));
                if (antiFloodBroadcastStaff) alertStaff(player, raw, 0);
                return false;
            }

            if (hasConsecutiveRepeats(raw, maxRepeatedCharacters)) {
                chatMessage.cancel("chars-flood");
                plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml", "chat-filter.chars-flood", "<red>Please avoid spamming repetitive characters!</red>"));
                if (antiFloodBroadcastStaff) alertStaff(player, raw, 0);
                return false;
            }
        }

        // Similarity check: prevent repetitive identical or near-identical messages
        if (player != null && similarityEnabled && !player.hasPermission("friendlycore.chat.bypass.similarity")) {
            UUID uuid = player.getUniqueId();
            String comparable = WHITESPACE_PATTERN.matcher(raw.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
            AtomicBoolean tooSimilar = new AtomicBoolean(false);
            lastMessages.compute(uuid, (ignored, previous) -> {
                if (previous != null && SimilarityEvaluator.evaluateSimilarity(comparable, previous) >= similarityThreshold) {
                    tooSimilar.set(true);
                    return previous;
                }
                return comparable;
            });
            if (tooSimilar.get()) {
                chatMessage.cancel("similar");
                plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml", "chat-filter.similar", "<red>Please avoid repeating the same or similar message!</red>"));
                return false;
            }
        }

        // Rule matching against blacklist and default patterns
        // Disabling the private-message content filter must not disable cooldown,
        // anti-flood, anti-caps, or similarity protections above.
        boolean skipContentRules = isPrivateMessage && privateMessagesFilterDisabled;
        if (!skipContentRules && chatRulesEnabled && (player == null || !player.hasPermission("friendlycore.chat.bypass.blacklist"))) {
            List<TextSpan> whitelistSpans = new ArrayList<>();
            for (ChatRule wRule : whitelistRules) {
                if (wRule.getPattern() != null) {
                    Matcher m = wRule.getPattern().matcher(raw);
                    while (m.find()) {
                        whitelistSpans.add(new TextSpan(m.start(), m.end()));
                    }
                }
            }

            Map<String, ChatRule> flaggedMap = new LinkedHashMap<>();
            for (ChatRule bRule : blacklistRules) {
                if (bRule.getPattern() == null) continue;
                Matcher matcher = bRule.getPattern().matcher(raw);
                while (matcher.find()) {
                    int matchStart = matcher.start();
                    int matchEnd = matcher.end();
                    if (!isSpanWhitelisted(matchStart, matchEnd, whitelistSpans)) {
                        String matchText = matcher.group();
                        flaggedMap.put(matchText, bRule);
                        chatMessage.addFlaggedWord(matchText);
                        chatMessage.addInfractionPoints(bRule.getPoints());
                    }
                }
            }

            if (!flaggedMap.isEmpty()) {
                if (cancelMessageOnFlag) {
                    chatMessage.cancel("filtered");
                    if (player != null) {
                        plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml", "chat-filter.filtered", "<red>Your message was cancelled due to prohibited content.</red>"));
                    }
                } else {
                    String filtered = raw;
                    for (String flaggedWord : flaggedMap.keySet()) {
                        String mask = replaceWith.repeat(Math.max(1, flaggedWord.length()));
                        filtered = Pattern.compile(Pattern.quote(flaggedWord), Pattern.CASE_INSENSITIVE)
                                .matcher(filtered).replaceAll(Matcher.quoteReplacement(mask));
                    }
                    chatMessage.setRawMessage(filtered);
                }

                if (alertStaffOnFlag) {
                    alertStaff(player, raw, chatMessage.getInfractionPoints());
                }

                handleInfractions(player, raw, chatMessage);

                return !cancelMessageOnFlag;
            }
        }

        return true;
    }

    private static boolean hasConsecutiveRepeats(String text, int maxAllowed) {
        if (text == null || text.length() <= maxAllowed) return false;
        int count = 1;
        char prev = text.charAt(0);
        for (int i = 1; i < text.length(); i++) {
            char curr = text.charAt(i);
            if (curr == prev && !Character.isWhitespace(curr)) {
                count++;
                if (count > maxAllowed) {
                    return true;
                }
            } else {
                prev = curr;
                count = 1;
            }
        }
        return false;
    }

    private boolean isSpanWhitelisted(int start, int end, List<TextSpan> whitelistSpans) {
        for (TextSpan span : whitelistSpans) {
            if (span.contains(start, end)) {
                return true;
            }
        }
        return false;
    }

    private void alertStaff(Player sender, String message, int points) {
        String staffMsg = plugin.getConfigService().getMessage("modules/chat/messages.yml", "chat-filter.staff-alert",
                "<dark_gray>[<red>FILTER</red>]</dark_gray> <gray>%player%</gray> <dark_gray>»</dark_gray> <red>%message%</red> <dark_gray>(<yellow>Points: %points%</yellow>)</dark_gray>");
        Map<String, String> placeholders = Map.of(
                "%player%", sender != null ? sender.getName() : "CONSOLE",
                "%message%", message != null ? message : "",
                "%points%", String.valueOf(points)
        );
        Component alertComp = plugin.getTextService().parse(staffMsg, placeholders);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("friendlycore.chat.staff.filtered") || online.hasPermission("friendlycore.chat.staffchat")) {
                plugin.getScheduler().runEntity(online, () -> online.sendMessage(alertComp));
            }
        }
    }

    public double evaluateSimilarity(String s1, String s2) {
        return SimilarityEvaluator.evaluateSimilarity(s1, s2);
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    private void handleInfractions(Player player, String raw, ChatMessage chatMessage) {
        if (!pointsEnabled || player == null || chatMessage.getInfractionPoints() <= 0) return;
        UUID uuid = player.getUniqueId();
        dev.lyy.friendlycore.modules.chat.model.ChatPlayerData playerData =
                plugin.getChatModule() != null ? plugin.getChatModule().getPlayerData(uuid) : null;
        if (playerData == null) return;

        long now = System.currentTimeMillis();
        if (resetPointsEnabled && playerData.getLastInfractionTime() > 0) {
            long diffMinutes = (now - playerData.getLastInfractionTime()) / 60_000L;
            if (diffMinutes >= resetPointsMinutes) {
                playerData.setPoints(0);
            }
        }

        int previousPoints = playerData.getPoints();
        playerData.addPoints(chatMessage.getInfractionPoints());
        playerData.setLastInfractionTime(now);

        // Async persist infraction & updated points to DB
        int pts = chatMessage.getInfractionPoints();
        String serverId = (plugin.getRedisService() != null && plugin.getRedisService().isEnabled())
                ? plugin.getRedisService().getServerId() : "local";
        plugin.getScheduler().runAsync(() -> {
            if (plugin.getDatabaseManager() != null) {
                plugin.getDatabaseManager().logChatInfraction(uuid, raw, pts, serverId);
                plugin.getDatabaseManager().saveChatPlayerData(playerData);
            }
        });

        // Check action thresholds - only trigger when crossing the required threshold boundary
        int totalPoints = playerData.getPoints();
        for (ActionThreshold threshold : actionThresholds.values()) {
            if (previousPoints < threshold.pointsRequired() && totalPoints >= threshold.pointsRequired()) {
                for (String cmd : threshold.commands()) {
                    String formattedCmd = cmd.replace("%player%", player.getName());
                    plugin.getScheduler().runGlobal(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formattedCmd));
                }
            }
        }
    }

    public void cleanupPlayer(UUID uuid) {
        messageCooldowns.remove(uuid);
        lastMessages.remove(uuid);
    }
}
