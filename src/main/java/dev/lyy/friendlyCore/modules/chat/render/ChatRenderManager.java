package dev.lyy.friendlycore.modules.chat.render;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.chat.model.ChatMessage;
import dev.lyy.friendlycore.modules.chat.model.RenderSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages interactive show elements ([item], [inv], [enderchest], [/command], [discord])
 * and the snapshot preview cache.
 */
public class ChatRenderManager {

    private final FriendlyCore plugin;
    private final MentionHandler mentionHandler;
    private final RenderGuiBuilder guiBuilder;

    private Cache<UUID, RenderSnapshot> snapshotCache;
    private final Map<String, String> customPlaceholders = new ConcurrentHashMap<>();
    private final Map<String, PermissionPlaceholder> permissionPlaceholders = new ConcurrentHashMap<>();

    private volatile boolean commandReplacerEnabled;
    private volatile String commandReplacement;

    private volatile boolean itemShowEnabled;
    private volatile boolean itemShowDisableGui;
    private final List<String> itemShowPrefixes = new ArrayList<>();
    private volatile String itemShowParser;

    private volatile boolean invShowEnabled;
    private final List<String> invShowPrefixes = new ArrayList<>();
    private volatile String invShowParser;

    private volatile boolean ecShowEnabled;
    private final List<String> ecShowPrefixes = new ArrayList<>();
    private volatile String ecShowParser;

    public record PermissionPlaceholder(String prefix, String parser, String permission) {}

    public ChatRenderManager(FriendlyCore plugin) {
        this.plugin = plugin;
        this.mentionHandler = new MentionHandler(plugin);
        this.guiBuilder = new RenderGuiBuilder(plugin);
    }

    private final com.google.gson.Gson gson = new com.google.gson.Gson();

    public void load() {
        mentionHandler.load();
        FileConfiguration config = plugin.getConfigService().getConfig("modules/chat/config.yml");
        FileConfiguration placeholdersConfig = plugin.getConfigService().getConfig("modules/chat/placeholders.yml");

        int cacheSeconds = config.getInt("settings.items-cache-seconds", 60);
        this.snapshotCache = CacheBuilder.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(cacheSeconds, TimeUnit.SECONDS)
                .build();

        this.commandReplacerEnabled = config.getBoolean("features.command-replacer.enabled", true);
        this.commandReplacement = config.getString("features.command-replacer.replacement",
                "<click:run_command:\"/%command%\"><hover:show_text:\"<gray>Click to run <yellow>/%command%</yellow></gray>\"><aqua>[/%command%]</aqua></hover></click>");

        this.itemShowEnabled = config.getBoolean("features.item-show.enabled", true);
        this.itemShowDisableGui = config.getBoolean("features.item-show.disable-gui", false);
        this.itemShowPrefixes.clear();
        this.itemShowPrefixes.addAll(config.getStringList("features.item-show.prefix"));
        if (itemShowPrefixes.isEmpty()) {
            itemShowPrefixes.addAll(List.of("[item]", "[i]", "[hand]"));
        }
        this.itemShowParser = config.getString("features.item-show.parser",
                "<click:run_command:\"/chatinv %uuid%\"><hover:show_text:\"<gray>Click to view <gold>%player%'s</gold> item preview</gray>\"><dark_gray>[<color:#E7B85C>%item%</color>]</dark_gray></hover></click>");

        this.invShowEnabled = config.getBoolean("features.inventory-show.enabled", true);
        this.invShowPrefixes.clear();
        this.invShowPrefixes.addAll(config.getStringList("features.inventory-show.prefix"));
        if (invShowPrefixes.isEmpty()) {
            invShowPrefixes.addAll(List.of("[inv]", "[inventory]"));
        }
        this.invShowParser = config.getString("features.inventory-show.parser",
                "<click:run_command:\"/chatinv %uuid%\"><hover:show_text:\"<color:#D8D0C3>Click to view <color:#E7B85C>%player%'s</color> inventory</color>\"><dark_gray>[<color:#6EDBFF>%player%'s Inventory</color>]</dark_gray></hover></click>");

        this.ecShowEnabled = config.getBoolean("features.enderchest-show.enabled", true);
        this.ecShowPrefixes.clear();
        this.ecShowPrefixes.addAll(config.getStringList("features.enderchest-show.prefix"));
        if (ecShowPrefixes.isEmpty()) {
            ecShowPrefixes.addAll(List.of("[enderchest]", "[ec]", "[ender]"));
        }
        this.ecShowParser = config.getString("features.enderchest-show.parser",
                "<click:run_command:\"/chatinv %uuid%\"><hover:show_text:\"<color:#D8D0C3>Click to view <color:#E7B85C>%player%'s</color> ender chest</color>\"><dark_gray>[<color:#FFD166>%player%'s Enderchest</color>]</dark_gray></hover></click>");

        customPlaceholders.clear();
        permissionPlaceholders.clear();
        if (placeholdersConfig != null && placeholdersConfig.getBoolean("enabled", true)) {
            ConfigurationSection sec = placeholdersConfig.getConfigurationSection("placeholders");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    customPlaceholders.put(key.toLowerCase(), sec.getString(key, ""));
                }
            }

            ConfigurationSection permSec = placeholdersConfig.getConfigurationSection("placeholders-with-permission");
            if (permSec != null) {
                for (String key : permSec.getKeys(false)) {
                    String prefix = permSec.getString(key + ".prefix", "");
                    String parser = permSec.getString(key + ".parser", "");
                    String perm = permSec.getString(key + ".permission", "");
                    if (!prefix.isEmpty() && !parser.isEmpty()) {
                        permissionPlaceholders.put(prefix.toLowerCase(), new PermissionPlaceholder(prefix, parser, perm));
                    }
                }
            }
        }
    }

    /**
     * Replaces interactive elements ([item], [inv], [ec], [/command], placeholders) in raw text.
     */
    public String processRenders(ChatMessage chatMessage) {
        if (chatMessage == null) return "";
        Player player = chatMessage.getSender();
        String raw = chatMessage.getRawMessage();
        if (raw == null) return "";

        for (Map.Entry<String, String> entry : customPlaceholders.entrySet()) {
            if (raw.toLowerCase().contains(entry.getKey())) {
                raw = Pattern.compile(Pattern.quote(entry.getKey()), Pattern.CASE_INSENSITIVE)
                        .matcher(raw).replaceAll(Matcher.quoteReplacement(entry.getValue()));
            }
        }

        if (player != null) {
            for (PermissionPlaceholder p : permissionPlaceholders.values()) {
                if (player.hasPermission(p.permission()) && raw.toLowerCase().contains(p.prefix().toLowerCase())) {
                    raw = Pattern.compile(Pattern.quote(p.prefix()), Pattern.CASE_INSENSITIVE)
                            .matcher(raw).replaceAll(Matcher.quoteReplacement(p.parser()));
                }
            }
        }

        if (commandReplacerEnabled && (player == null || player.hasPermission("friendlycore.chat.parse.commands"))) {
            Pattern cmdPattern = Pattern.compile("\\[/([a-zA-Z0-9_\\-]+)\\]");
            Matcher cmdMatcher = cmdPattern.matcher(raw);
            StringBuffer sb = new StringBuffer();
            while (cmdMatcher.find()) {
                String cmdName = cmdMatcher.group(1);
                String replacement = commandReplacement.replace("%command%", cmdName);
                cmdMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            cmdMatcher.appendTail(sb);
            raw = sb.toString();
        }

        // Main hand item display [item], [i], [hand]
        if (player != null && itemShowEnabled && player.hasPermission("friendlycore.chat.renderitem")) {
            boolean hasItemPrefix = false;
            for (String prefix : itemShowPrefixes) {
                if (raw.toLowerCase().contains(prefix.toLowerCase())) {
                    hasItemPrefix = true;
                    break;
                }
            }

            if (hasItemPrefix) {
                ItemStack handItem = player.getInventory().getItemInMainHand();
                if (handItem.getType() == Material.AIR) {
                    plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml", "renders.no-item-in-hand", "<red>You cannot show air in chat!</red>"));
                } else {
                    UUID snapshotId = UUID.randomUUID();
                    RenderSnapshot.RenderType rType = handItem.getType().name().contains("SHULKER_BOX") ? RenderSnapshot.RenderType.SHULKER : RenderSnapshot.RenderType.ITEM;
                    RenderSnapshot snapshot = new RenderSnapshot(snapshotId, rType, player.getUniqueId(), player.getName(), handItem, null, null, null);
                    addSnapshot(snapshot, chatMessage);

                    String rawItemName = getItemDisplayName(handItem);
                    String itemName = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(rawItemName);
                    String escapedPlayer = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(player.getName());

                    String formattedItem;
                    if (itemShowDisableGui) {
                        formattedItem = "<hover:show_text:\"<gray>Item: <gold>" + itemName + "</gold></gray>\"><dark_gray>[<color:#E7B85C>" + itemName + "</color>]</dark_gray></hover>";
                    } else {
                        formattedItem = itemShowParser
                                .replace("%uuid%", snapshotId.toString())
                                .replace("%player%", escapedPlayer)
                                .replace("%item%", itemName);
                        if (!formattedItem.contains(snapshotId.toString())) {
                            formattedItem = "<click:run_command:\"/chatinv " + snapshotId + "\"><hover:show_text:\"<gray>Click to view <gold>" + escapedPlayer + "'s</gold> item preview</gray>\">" + formattedItem + "</hover></click>";
                        }
                    }

                    for (String prefix : itemShowPrefixes) {
                        raw = Pattern.compile(Pattern.quote(prefix), Pattern.CASE_INSENSITIVE)
                                .matcher(raw).replaceAll(Matcher.quoteReplacement(formattedItem));
                    }
                }
            }
        }

        if (player != null && invShowEnabled && player.hasPermission("friendlycore.chat.renderinventory")) {
            boolean hasInvPrefix = false;
            for (String prefix : invShowPrefixes) {
                if (raw.toLowerCase().contains(prefix.toLowerCase())) {
                    hasInvPrefix = true;
                    break;
                }
            }

            if (hasInvPrefix) {
                UUID snapshotId = UUID.randomUUID();
                RenderSnapshot snapshot = new RenderSnapshot(
                        snapshotId,
                        RenderSnapshot.RenderType.INVENTORY,
                        player.getUniqueId(),
                        player.getName(),
                        player.getInventory().getItemInMainHand(),
                        player.getInventory().getContents(),
                        player.getInventory().getArmorContents(),
                        player.getInventory().getItemInOffHand()
                );
                addSnapshot(snapshot, chatMessage);

                String escapedName = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(player.getName());
                String formattedInv = invShowParser
                        .replace("%uuid%", snapshotId.toString())
                        .replace("%player%", escapedName);

                for (String prefix : invShowPrefixes) {
                    raw = Pattern.compile(Pattern.quote(prefix), Pattern.CASE_INSENSITIVE)
                            .matcher(raw).replaceAll(Matcher.quoteReplacement(formattedInv));
                }
            }
        }

        if (player != null && ecShowEnabled && player.hasPermission("friendlycore.chat.renderenderchest")) {
            boolean hasEcPrefix = false;
            for (String prefix : ecShowPrefixes) {
                if (raw.toLowerCase().contains(prefix.toLowerCase())) {
                    hasEcPrefix = true;
                    break;
                }
            }

            if (hasEcPrefix) {
                UUID snapshotId = UUID.randomUUID();
                RenderSnapshot snapshot = new RenderSnapshot(
                        snapshotId,
                        RenderSnapshot.RenderType.ENDERCHEST,
                        player.getUniqueId(),
                        player.getName(),
                        null,
                        player.getEnderChest().getContents(),
                        null,
                        null
                );
                addSnapshot(snapshot, chatMessage);

                String escapedName = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(player.getName());
                String formattedEc = ecShowParser
                        .replace("%uuid%", snapshotId.toString())
                        .replace("%player%", escapedName);

                for (String prefix : ecShowPrefixes) {
                    raw = Pattern.compile(Pattern.quote(prefix), Pattern.CASE_INSENSITIVE)
                            .matcher(raw).replaceAll(Matcher.quoteReplacement(formattedEc));
                }
            }
        }

        return raw;
    }

    private String getItemDisplayName(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return "Air";
        String amount = item.getAmount() > 1 ? item.getAmount() + "x " : "";

        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return amount + PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
        }
        if (item.hasItemMeta() && item.getItemMeta().hasCustomName()) {
            return amount + PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().customName());
        }
        if (item.hasItemMeta() && item.getItemMeta().hasItemName()) {
            return amount + PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().itemName());
        }

        String typeName = item.getType().name().replace('_', ' ').toLowerCase();
        String formattedTypeName = capitalizeWords(typeName);
        return amount + formattedTypeName;
    }

    private static String capitalizeWords(String text) {
        String[] words = text.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(" ");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Stores a snapshot locally and synchronizes it to Redis with TTL.
     */
    public void addSnapshot(RenderSnapshot snapshot, ChatMessage chatMessage) {
        if (snapshot == null) return;
        if (snapshotCache != null) {
            snapshotCache.put(snapshot.getSnapshotId(), snapshot);
        }
        if (chatMessage != null) {
            chatMessage.addCreatedSnapshot(snapshot);
        }

        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            plugin.getScheduler().runAsync(() -> {
                try {
                    String json = snapshot.serializeToJson(gson);
                    if (plugin.getRedisService().getRedisClient() != null) {
                        plugin.getRedisService().getRedisClient().setex("friendlycore:chat:snapshot:" + snapshot.getSnapshotId(), 180, json);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to sync snapshot to Redis: " + e.getMessage());
                }
            });
        }
    }

    /**
     * Registers an incoming snapshot (e.g. from cross-server Redis packets).
     */
    public void registerSnapshot(RenderSnapshot snapshot) {
        if (snapshot == null || snapshotCache == null) return;
        snapshotCache.put(snapshot.getSnapshotId(), snapshot);
    }

    /**
     * Retrieves a snapshot by ID from local cache with Redis fallback.
     */
    public RenderSnapshot getSnapshot(UUID uuid) {
        if (uuid == null) return null;
        if (snapshotCache != null) {
            RenderSnapshot local = snapshotCache.getIfPresent(uuid);
            if (local != null) return local;
        }

        return null;
    }

    public CompletableFuture<RenderSnapshot> getSnapshotAsync(UUID uuid) {
        RenderSnapshot local = getSnapshot(uuid);
        if (local != null || uuid == null) {
            return CompletableFuture.completedFuture(local);
        }

        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            return CompletableFuture.supplyAsync(() -> {
              try {
                if (plugin.getRedisService().getRedisClient() != null) {
                    String json = plugin.getRedisService().getRedisClient().get("friendlycore:chat:snapshot:" + uuid);
                    if (json != null && !json.isEmpty()) {
                        RenderSnapshot remote = RenderSnapshot.deserializeFromJson(json, gson);
                        if (remote != null) {
                            if (snapshotCache != null) {
                                snapshotCache.put(uuid, remote);
                            }
                            return remote;
                        }
                    }
                }
              } catch (Exception e) {
                plugin.getLogger().warning("Failed to query snapshot from Redis: " + e.getMessage());
              }
              return null;
            }, plugin.getScheduler().getAsyncExecutor());
        }

        return CompletableFuture.completedFuture(null);
    }

    public MentionHandler getMentionHandler() {
        return mentionHandler;
    }

    public RenderGuiBuilder getGuiBuilder() {
        return guiBuilder;
    }
}
