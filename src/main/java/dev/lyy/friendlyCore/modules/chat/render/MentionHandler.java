package dev.lyy.friendlycore.modules.chat.render;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.chat.model.ChatMessage;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.title.Title;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles @PlayerName detection, Adventure hover/color formatting, and ping/title notifications.
 */
public class MentionHandler {

    private static final Pattern MENTION_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}_@])@([A-Za-z0-9_]{1,16})(?![\\p{L}\\p{N}_])");

    private final FriendlyCore plugin;
    private final Map<UUID, Set<String>> chatCompletions = new ConcurrentHashMap<>();
    private volatile boolean mentionsEnabled;
    private volatile String mentionPrefix;
    private volatile boolean soundEnabled;
    private volatile String soundName;
    private volatile float soundVolume;
    private volatile float soundPitch;
    private volatile boolean titleEnabled;
    private volatile String titleText;
    private volatile String subtitleText;
    private volatile int titleFadeIn;
    private volatile int titleStay;
    private volatile int titleFadeOut;

    public MentionHandler(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration config = plugin.getConfigService().getConfig("modules/chat/config.yml");
        this.mentionsEnabled = config.getBoolean("features.mentions.enabled", true);
        this.mentionPrefix = config.getString("features.mentions.prefix", "<!bold><gradient:#00d2ff:#3a7bd5>@%player%</gradient>");
        this.soundEnabled = config.getBoolean("features.mentions.sound.enabled", true);
        this.soundName = config.getString("features.mentions.sound.name", "block.note_block.pling");
        this.soundVolume = (float) config.getDouble("features.mentions.sound.volume", 1.0);
        this.soundPitch = (float) config.getDouble("features.mentions.sound.pitch", 2.0);
        this.titleEnabled = config.getBoolean("features.mentions.title.enabled", true);
        this.titleText = config.getString("features.mentions.title.title", "<gradient:#ff416c:#ff4b2b><b>Mentioned!</b></gradient>");
        this.subtitleText = config.getString("features.mentions.title.subtitle", "<gray>%sender% mentioned you in chat!</gray>");
        this.titleFadeIn = Math.max(0, config.getInt("features.mentions.title.fade-in", 5));
        this.titleStay = Math.max(0, config.getInt("features.mentions.title.stay", 30));
        this.titleFadeOut = Math.max(0, config.getInt("features.mentions.title.fade-out", 10));
        refreshCompletions();
    }

    public void refreshCompletions() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            refreshCompletions(viewer);
        }
    }

    public void refreshCompletions(Player viewer) {
        plugin.getScheduler().runEntity(viewer, () -> {
            if (!viewer.isOnline()) return;
            Set<String> current = new HashSet<>();
            if (mentionsEnabled) {
                for (Player target : Bukkit.getOnlinePlayers()) {
                    if (canSuggest(viewer, target)) {
                        current.add("@" + target.getName());
                    }
                }
            }
            Set<String> previous = chatCompletions.getOrDefault(viewer.getUniqueId(), Set.of());
            Set<String> removed = new HashSet<>(previous);
            removed.removeAll(current);
            Set<String> added = new HashSet<>(current);
            added.removeAll(previous);
            if (!removed.isEmpty()) viewer.removeCustomChatCompletions(removed);
            if (!added.isEmpty()) viewer.addCustomChatCompletions(added);
            chatCompletions.put(viewer.getUniqueId(), current);
        });
    }

    public void addCompletions(Player target) {
        refreshCompletions(target);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(target.getUniqueId())) updateCompletion(viewer, target);
        }
    }

    public void updateCompletion(Player viewer, Player target) {
        plugin.getScheduler().runEntity(viewer, () -> {
            if (!viewer.isOnline()) return;
            Set<String> completions = chatCompletions.computeIfAbsent(viewer.getUniqueId(), key -> new HashSet<>());
            String name = "@" + target.getName();
            if (mentionsEnabled && canSuggest(viewer, target)) {
                if (completions.add(name)) viewer.addCustomChatCompletions(Set.of(name));
            } else if (completions.remove(name)) {
                viewer.removeCustomChatCompletions(Set.of(name));
            }
        });
    }

    private static boolean canSuggest(Player viewer, Player target) {
        return target.isOnline() && !target.getUniqueId().equals(viewer.getUniqueId())
                && viewer.canSee(target) && !isVanished(target);
    }

    public void removeCompletions(Player target) {
        chatCompletions.remove(target.getUniqueId());
        String name = "@" + target.getName();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(target.getUniqueId())) continue;
            plugin.getScheduler().runEntity(viewer, () -> {
                Set<String> completions = chatCompletions.get(viewer.getUniqueId());
                if (viewer.isOnline() && completions != null && completions.remove(name)) {
                    viewer.removeCustomChatCompletions(Set.of(name));
                }
            });
        }
    }

    /**
     * Styles visible mentions and notifies only players in the final chat audience.
     */
    public Component processMentions(ChatMessage chatMessage, Component message,
                                     Collection<? extends Audience> viewers, Set<UUID> pingMutedPlayers) {
        if (chatMessage == null || chatMessage.isCancelled()) return message;
        return processMentions(message, chatMessage.getSender(), chatMessage.getSenderUuid(),
                chatMessage.getSenderName(), viewers, pingMutedPlayers, chatMessage.getMentions());
    }

    public Component processCrossServerMentions(String senderName, UUID senderUuid, Component message,
                                                Collection<? extends Audience> viewers, Set<UUID> pingMutedPlayers) {
        return processMentions(message, null, senderUuid, senderName != null ? senderName : "Unknown",
                viewers, pingMutedPlayers, new HashSet<>());
    }

    private Component processMentions(Component message, Player sender, UUID senderUuid, String senderName,
                                      Collection<? extends Audience> viewers, Set<UUID> pingMutedPlayers,
                                      Set<UUID> mentions) {
        if (!mentionsEnabled || message == null) return message;
        StringBuilder visible = new StringBuilder();
        appendVisibleText(message, visible);
        Matcher matcher = MENTION_PATTERN.matcher(visible);
        if (!matcher.find()) return message;

        Map<String, Player> targets = new HashMap<>();
        for (Audience viewer : viewers) {
            if (!(viewer instanceof Player target) || !target.isOnline() || isVanished(target)) continue;
            if (target.getUniqueId().equals(senderUuid)) continue;
            if (sender != null && !sender.canSee(target) && !sender.hasPermission("friendlycore.vanish.see")) continue;
            targets.put(target.getName().toLowerCase(Locale.ROOT), target);
        }
        if (targets.isEmpty()) return message;

        List<MentionSpan> spans = new ArrayList<>();
        do {
            Player target = targets.get(matcher.group(1).toLowerCase(Locale.ROOT));
            if (target == null) continue;
            mentions.add(target.getUniqueId());
            String name = TextService.safeEscapeTags(target.getName());
            Component replacement = plugin.getTextService().parse(
                    mentionPrefix.replace("%player%", name).replace("{player}", name));
            spans.add(new MentionSpan(matcher.start(), matcher.end(), replacement));
        } while (matcher.find());
        if (spans.isEmpty()) return message;
        Component result = replaceMentionSpans(message, spans, new int[]{0});

        // Repeated tokens are all styled, with one notification per target per message.
        for (Player target : targets.values()) {
            UUID targetUuid = target.getUniqueId();
            if (mentions.contains(targetUuid) && (pingMutedPlayers == null || !pingMutedPlayers.contains(targetUuid))) {
                if (plugin.getScheduler() != null) {
                    plugin.getScheduler().runEntity(target, () -> {
                        if (mentionsEnabled && !isVanished(target)
                                && (pingMutedPlayers == null || !pingMutedPlayers.contains(targetUuid))) {
                            notifyTarget(target, senderName);
                        }
                    });
                }
            }
        }
        return result;
    }

    private record MentionSpan(int start, int end, Component replacement) {}

    private static void appendVisibleText(Component component, StringBuilder text) {
        // Non-text content is a boundary. Hover text, click actions and translation arguments are excluded.
        text.append(component instanceof TextComponent literal ? literal.content() : "\0");
        for (Component child : component.children()) appendVisibleText(child, text);
    }

    private static Component replaceMentionSpans(Component component, List<MentionSpan> spans, int[] offset) {
        Component result = component.children(List.of());
        if (component instanceof TextComponent literal) {
            String text = literal.content();
            int start = offset[0];
            int end = start + text.length();
            offset[0] = end;
            int cursor = 0;
            // ponytail: scan the short chat span list per text node; index spans if profiling warrants it.
            for (MentionSpan span : spans) {
                if (span.end() <= start || span.start() >= end) continue;
                if (cursor == 0) result = literal.content("").children(List.of());
                int from = Math.max(0, span.start() - start);
                if (from > cursor) result = result.append(Component.text(text.substring(cursor, from)));
                if (span.start() >= start) result = result.append(span.replacement());
                cursor = Math.min(text.length(), span.end() - start);
            }
            if (cursor > 0 && cursor < text.length()) result = result.append(Component.text(text.substring(cursor)));
        } else {
            offset[0]++;
        }
        for (Component child : component.children()) result = result.append(replaceMentionSpans(child, spans, offset));
        return result;
    }

    private void notifyTarget(Player target, String senderName) {
        if (target == null || !target.isOnline()) return;

        if (soundEnabled && soundName != null && !soundName.isEmpty()) {
            TextService.playSound(target, soundName, soundVolume, soundPitch);
        }

        if (titleEnabled) {
            senderName = TextService.safeEscapeTags(senderName);
            String titleParsed = titleText.replace("%sender%", senderName).replace("{sender}", senderName)
                    .replace("%player%", target.getName()).replace("{player}", target.getName());
            String subParsed = subtitleText.replace("%sender%", senderName).replace("{sender}", senderName)
                    .replace("%player%", target.getName()).replace("{player}", target.getName());
            Title title = Title.title(
                    plugin.getTextService().parse(titleParsed),
                    plugin.getTextService().parse(subParsed),
                    Title.Times.times(Duration.ofMillis(titleFadeIn * 50L), Duration.ofMillis(titleStay * 50L), Duration.ofMillis(titleFadeOut * 50L))
            );
            target.showTitle(title);
        }
    }

    private static boolean isVanished(Player player) {
        if (player == null) return false;
        for (MetadataValue meta : player.getMetadata("vanished")) {
            if (meta.asBoolean()) return true;
        }
        return false;
    }
}
