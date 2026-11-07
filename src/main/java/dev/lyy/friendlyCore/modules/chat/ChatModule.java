package dev.lyy.friendlycore.modules.chat;

import com.google.gson.Gson;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.database.DatabaseReadRetry;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.modules.chat.filter.ChatFilterManager;
import dev.lyy.friendlycore.modules.chat.model.ChatChannel;
import dev.lyy.friendlycore.modules.chat.model.ChatMessage;
import dev.lyy.friendlycore.modules.chat.model.ChatPlayerData;
import dev.lyy.friendlycore.modules.chat.model.RenderSnapshot;
import dev.lyy.friendlycore.modules.chat.render.ChatRenderManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * High-performance, all-in-one Chat Module for FriendlyCore.
 * Fully supports Canvas, Folia, Paper 1.20-1.21.x, and Redis Cross-Server Synchronization.
 */
public class ChatModule {

    private final FriendlyCore plugin;
    private final Gson gson = new Gson();
    private final ChatFilterManager filterManager;
    private final ChatRenderManager renderManager;

    private final Map<UUID, ChatPlayerData> playerDataMap = new ConcurrentHashMap<>();
    private final Map<UUID, Object> loadTokens = new ConcurrentHashMap<>();
    private final Set<UUID> pingMutedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> chatToggledPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> staffChatToggledPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> spyingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> lastRepliers = new ConcurrentHashMap<>();

    private final AtomicBoolean isChatMuted = new AtomicBoolean(false);

    private volatile String listenerPriority;
    private volatile String chatFormatTemplate;
    private volatile boolean isStructuredFormat;
    private volatile String structuredFormatMessage;
    private volatile String structuredFormatHover;
    private volatile String structuredFormatAction;
    private volatile String staffChatPrefix;
    private volatile String staffChatFormat;
    private volatile boolean staffChatSoundEnabled;
    private volatile String staffChatSoundName;
    private volatile String spyFormat;
    private volatile String msgSenderFormat;
    private volatile String msgReceiverFormat;
    private volatile boolean msgSoundEnabled;
    private volatile String msgSoundName;

    private volatile boolean cmdMsgEnabled;
    private volatile boolean cmdReplyEnabled;
    private volatile boolean cmdIgnoreEnabled;
    private volatile boolean cmdIgnoreAllEnabled;
    private volatile boolean cmdSpyEnabled;
    private volatile boolean cmdStaffChatEnabled;
    private volatile boolean cmdPingEnabled;
    private volatile boolean cmdChatToggleEnabled;
    private volatile boolean cmdMuteChatEnabled;
    private volatile boolean cmdClearChatEnabled;

    private volatile boolean crossServerEnabled;
    private volatile boolean syncPublicChat;
    private volatile boolean syncStaffChat;
    private volatile boolean syncPrivateMessages;
    private volatile boolean syncSocialSpy;
    private volatile boolean syncMuteChat;
    private volatile boolean syncClearChat;

    public ChatModule(FriendlyCore plugin) {
        this.plugin = plugin;
        this.filterManager = new ChatFilterManager(plugin);
        this.renderManager = new ChatRenderManager(plugin);
    }

    public void initialize() {
        loadSettings();
        filterManager.load();
        renderManager.load();

        // Load currently online players (in case of reload)
        for (Player p : Bukkit.getOnlinePlayers()) {
            loadPlayer(p.getUniqueId(), p.getName());
        }
    }

    public void reload() {
        for (String file : List.of("config.yml", "chat-filter.yml", "filter-rules.yml", "filter-whitelist.yml",
                "leetmap.yml", "placeholders.yml", "renders-gui.yml", "messages.yml")) {
            plugin.getConfigService().reload("modules/chat/" + file);
        }
        loadSettings();
        filterManager.load();
        renderManager.load();
    }

    public void shutdown() {
        if (!playerDataMap.isEmpty() && plugin.getDatabaseManager() != null) {
            try {
                plugin.getDatabaseManager().saveChatPlayerDataBatch(new ArrayList<>(playerDataMap.values()))
                        .get(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Timeout or error flushing chat player data on shutdown", e);
            }
        }
        playerDataMap.clear();
        loadTokens.clear();
        pingMutedPlayers.clear();
        chatToggledPlayers.clear();
        staffChatToggledPlayers.clear();
        spyingPlayers.clear();
        lastRepliers.clear();
    }

    private void loadSettings() {
        FileConfiguration config = plugin.getConfigService().getConfig("modules/chat/config.yml");

        this.listenerPriority = config.getString("settings.listener-priority", "HIGHEST");
        if (config.isConfigurationSection("settings.format")) {
            ConfigurationSection formatSec = config.getConfigurationSection("settings.format");
            this.structuredFormatMessage = formatSec.getString("message", "%luckperms_prefix% &f%player_name% &8➠ ");
            this.structuredFormatHover = formatSec.getString("hover-message", null);
            this.structuredFormatAction = formatSec.getString("action", null);
            this.isStructuredFormat = true;
            this.chatFormatTemplate = buildStructuredChatFormat(structuredFormatMessage, structuredFormatHover, structuredFormatAction);
        } else {
            this.isStructuredFormat = false;
            this.structuredFormatMessage = null;
            this.structuredFormatHover = null;
            this.structuredFormatAction = null;
            this.chatFormatTemplate = config.getString("settings.format",
                    "<gray>%luckperms_prefix%</gray><white>%player%</white><dark_gray> » </dark_gray><white>{message}</white>");
        }

        this.staffChatPrefix = config.getString("features.staffchat.prefix", "#");
        this.staffChatFormat = config.getString("features.staffchat.format",
                "<color:#25c8fa><bold>[STAFF]</bold></color> <dark_gray>|</dark_gray> <red>%player%</red><dark_gray> » </dark_gray><white>{message}</white>");
        this.staffChatSoundEnabled = config.getBoolean("features.staffchat.sound.enabled", true);
        this.staffChatSoundName = config.getString("features.staffchat.sound.name", "block.note_block.bell");

        this.spyFormat = config.getString("features.spy.format",
                "<dark_aqua>[SPY]</dark_aqua> <dark_gray>(</dark_gray><gray>%sender%</gray> <green>➺</green> <gray>%target%</gray><dark_gray>)</dark_gray> <white>{message}</white>");

        this.msgSenderFormat = config.getString("features.msg.sender-format",
                "<white>✉</white> <green>⬆</green> <dark_aqua>MSG</dark_aqua> <dark_gray>(</dark_gray><green>me</green> <white>➺</white> <gray>%target%</gray><dark_gray>)</dark_gray> <white>{message}</white>");
        this.msgReceiverFormat = config.getString("features.msg.receiver-format",
                "<white>✉</white> <green>⬇</green> <dark_aqua>MSG</dark_aqua> <dark_gray>(</dark_gray><green>%sender%</green> <white>➺</white> <gray>me</gray><dark_gray>)</dark_gray> <white>{message}</white>");
        this.msgSoundEnabled = config.getBoolean("features.msg.sound.enabled", true);
        this.msgSoundName = config.getString("features.msg.sound.name", "block.note_block.pling");

        this.cmdMsgEnabled = config.getBoolean("commands.msg", true);
        this.cmdReplyEnabled = config.getBoolean("commands.reply", true);
        this.cmdIgnoreEnabled = config.getBoolean("commands.ignore", true);
        this.cmdIgnoreAllEnabled = config.getBoolean("commands.ignoreall", true);
        this.cmdSpyEnabled = config.getBoolean("commands.spymsg", true);
        this.cmdStaffChatEnabled = config.getBoolean("commands.staffchat", true);
        this.cmdPingEnabled = config.getBoolean("commands.chatping", true);
        this.cmdChatToggleEnabled = config.getBoolean("commands.chattoggle", true);
        this.cmdMuteChatEnabled = config.getBoolean("commands.mutechat", true);
        this.cmdClearChatEnabled = config.getBoolean("commands.clearchat", true);

        this.crossServerEnabled = config.getBoolean("cross-server.enabled", true);
        this.syncPublicChat = config.getBoolean("cross-server.sync-public-chat", false);
        this.syncStaffChat = config.getBoolean("cross-server.sync-staff-chat", true);
        this.syncPrivateMessages = config.getBoolean("cross-server.sync-private-messages", true);
        this.syncSocialSpy = config.getBoolean("cross-server.sync-social-spy", true);
        this.syncMuteChat = config.getBoolean("cross-server.sync-mute-chat", true);
        this.syncClearChat = config.getBoolean("cross-server.sync-clear-chat", true);
    }

    public void loadPlayer(UUID uuid, String name) {
        if (uuid == null) return;
        if (plugin.getDatabaseManager() == null) {
            playerDataMap.put(uuid, new ChatPlayerData(uuid, name));
            return;
        }
        Object loadToken = new Object();
        loadTokens.put(uuid, loadToken);
        DatabaseReadRetry.load(plugin, () -> plugin.getDatabaseManager().loadChatPlayerData(uuid, name),
                () -> loadTokens.get(uuid) == loadToken).thenAccept(data -> {
            if (loadTokens.get(uuid) == loadToken) {
                playerDataMap.put(uuid, data != null ? data : new ChatPlayerData(uuid, name));
            }
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Failed to load chat data for player " + name, ex);
            return null;
        });
    }

    public void unloadPlayer(UUID uuid) {
        if (uuid == null) return;
        loadTokens.remove(uuid);
        ChatPlayerData data = playerDataMap.remove(uuid);
        pingMutedPlayers.remove(uuid);
        chatToggledPlayers.remove(uuid);
        staffChatToggledPlayers.remove(uuid);
        spyingPlayers.remove(uuid);
        lastRepliers.remove(uuid);
        filterManager.cleanupPlayer(uuid);

        if (data != null && plugin.getDatabaseManager() != null) {
            plugin.getDatabaseManager().saveChatPlayerData(data);
        }
    }

    public ChatPlayerData getPlayerData(UUID uuid) {
        if (uuid == null) return null;
        return playerDataMap.get(uuid);
    }

    public Map<UUID, ChatPlayerData> getAllPlayerData() {
        return playerDataMap;
    }

    public boolean canReceivePublicChat(UUID viewerUuid, UUID senderUuid, boolean bypassIgnore, boolean bypassChatToggle) {
        if (isChatToggled(viewerUuid) && !bypassChatToggle) return false;
        ChatPlayerData data = getPlayerData(viewerUuid);
        return data != null && (bypassIgnore || !data.isIgnoring(senderUuid));
    }

    public void addIgnored(UUID playerUuid, UUID targetUuid) {
        ChatPlayerData data = playerDataMap.get(playerUuid);
        if (data != null) {
            data.addIgnored(targetUuid);
        }
        if (plugin.getDatabaseManager() != null) {
            plugin.getDatabaseManager().addChatIgnored(playerUuid, targetUuid);
        }
    }

    public void removeIgnored(UUID playerUuid, UUID targetUuid) {
        ChatPlayerData data = playerDataMap.get(playerUuid);
        if (data != null) {
            data.removeIgnored(targetUuid);
        }
        if (plugin.getDatabaseManager() != null) {
            plugin.getDatabaseManager().removeChatIgnored(playerUuid, targetUuid);
        }
    }

    public void clearIgnored(UUID playerUuid) {
        ChatPlayerData data = playerDataMap.get(playerUuid);
        if (data != null) {
            data.clearIgnored();
        }
        if (plugin.getDatabaseManager() != null) {
            plugin.getDatabaseManager().clearChatIgnored(playerUuid);
        }
    }

    public void setMessagesClosed(UUID playerUuid, boolean closed) {
        ChatPlayerData data = playerDataMap.get(playerUuid);
        if (data != null) {
            data.setMessagesClosed(closed);
            if (plugin.getDatabaseManager() != null) {
                plugin.getDatabaseManager().saveChatPlayerData(data);
            }
        }
    }

    public boolean isPingMuted(UUID uuid) {
        return uuid != null && pingMutedPlayers.contains(uuid);
    }

    public void setPingMuted(UUID uuid, boolean muted) {
        if (uuid == null) return;
        if (muted) pingMutedPlayers.add(uuid);
        else pingMutedPlayers.remove(uuid);
    }

    public Set<UUID> getPingMutedPlayers() {
        return pingMutedPlayers;
    }

    public boolean isChatToggled(UUID uuid) {
        return uuid != null && chatToggledPlayers.contains(uuid);
    }

    public void setChatToggled(UUID uuid, boolean toggled) {
        if (uuid == null) return;
        if (toggled) chatToggledPlayers.add(uuid);
        else chatToggledPlayers.remove(uuid);
    }

    public boolean isStaffChatToggled(UUID uuid) {
        return uuid != null && staffChatToggledPlayers.contains(uuid);
    }

    public void setStaffChatToggled(UUID uuid, boolean toggled) {
        if (uuid == null) return;
        if (toggled) staffChatToggledPlayers.add(uuid);
        else staffChatToggledPlayers.remove(uuid);
    }

    public boolean isSpying(UUID uuid) {
        return uuid != null && spyingPlayers.contains(uuid);
    }

    public void setSpying(UUID uuid, boolean spying) {
        if (uuid == null) return;
        if (spying) spyingPlayers.add(uuid);
        else spyingPlayers.remove(uuid);
    }

    public String getLastReplier(UUID uuid) {
        return uuid != null ? lastRepliers.get(uuid) : null;
    }

    public void setLastReplier(UUID uuid, String name) {
        if (uuid != null && name != null) {
            lastRepliers.put(uuid, name);
        }
    }

    public boolean isChatMuted() {
        return isChatMuted.get();
    }

    public void setChatMuted(boolean muted, String executorName, boolean broadcastRedis) {
        this.isChatMuted.set(muted);

        String broadcastKey = muted ? "chat-mute.mute-broadcast" : "chat-mute.un-mute-broadcast";
        String defMsg = muted ? "<newline><red><b>SERVER CHAT MUTED</b></red><newline><gray>Chat has been muted by <white>%player%</white>.</gray>"
                : "<newline><green><b>SERVER CHAT UNMUTED</b></green><newline><gray>Chat has been unmuted by <white>%player%</white>.</gray>";
        String message = plugin.getConfigService().getMessage("modules/chat/messages.yml", broadcastKey, defMsg)
                .replace("%player%", executorName != null ? executorName : "Console");
        Component comp = plugin.getTextService().parse(message);
        Bukkit.broadcast(comp);

        if (broadcastRedis && crossServerEnabled && syncMuteChat && plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            plugin.getRedisService().publish(new CrossServerPacket(
                    plugin.getRedisService().getServerId(),
                    CrossServerPacket.Action.CHAT_MUTE_SYNC,
                    null,
                    executorName,
                    muted ? 1.0 : 0.0,
                    null
            ));
        }
    }

    public void clearChat(String executorName, boolean broadcastRedis) {
        Component empty = Component.empty();
        for (int i = 0; i < 120; i++) {
            Bukkit.broadcast(empty);
        }

        String clearMsg = plugin.getConfigService().getMessage("modules/chat/messages.yml", "chat-clear.broadcast",
                "<newline><aqua><b>CHAT CLEARED</b></aqua><newline><gray>Chat has been cleared by <white>%player%</white>.</gray>")
                .replace("%player%", executorName != null ? executorName : "Console");
        Bukkit.broadcast(plugin.getTextService().parse(clearMsg));

        if (broadcastRedis && crossServerEnabled && syncClearChat && plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            plugin.getRedisService().publish(new CrossServerPacket(
                    plugin.getRedisService().getServerId(),
                    CrossServerPacket.Action.CHAT_CLEAR_SYNC,
                    null,
                    executorName,
                    0.0,
                    null
            ));
        }
    }

    public void broadcast(String type, String message, String senderName, boolean broadcastRedis) {
        if (message == null || message.isEmpty()) return;
        Component comp = plugin.getTextService().parse(message, Map.of("%player%", senderName != null ? senderName : "Console"));

        switch (type != null ? type.toLowerCase() : "chat") {
            case "actionbar" -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendActionBar(comp);
                }
            }
            case "title" -> {
                String[] split = message.split("\\\\\\\\|//|\\n");
                Component title = plugin.getTextService().parse(split[0]);
                Component subtitle = split.length > 1 ? plugin.getTextService().parse(split[1]) : Component.empty();
                Title t = Title.title(title, subtitle, Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2000), Duration.ofMillis(500)));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.showTitle(t);
                }
            }
            default -> {
                Bukkit.broadcast(comp);
                TextService.playSoundAll("block.note_block.pling", 1.0f, 1.0f);
            }
        }

        if (broadcastRedis && crossServerEnabled && plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            Map<String, String> payload = Map.of("type", type, "message", message, "sender", senderName != null ? senderName : "Console");
            plugin.getRedisService().publish(new CrossServerPacket(
                    plugin.getRedisService().getServerId(),
                    CrossServerPacket.Action.CHAT_BROADCAST,
                    null,
                    senderName,
                    0.0,
                    gson.toJson(payload)
            ));
        }
    }

    public void sendStaffChat(CommandSender sender, String message) {
        String senderName = sender.getName();
        List<RenderSnapshot> snapshots = Collections.emptyList();

        if (sender instanceof Player player && renderManager != null) {
            String sanitized = TextService.sanitizeChatInput(player, message);
            String safeRaw = player.hasPermission("friendlycore.chat.minimessage")
                    ? sanitized
                    : net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(sanitized);
            ChatMessage chatMsg = new ChatMessage(player, safeRaw, null, ChatChannel.STAFF);
            if (!filterManager.processMessage(chatMsg, false) || chatMsg.isCancelled()) {
                return;
            }
            message = renderManager.processRenders(chatMsg);
            snapshots = chatMsg.getCreatedSnapshots();
        }

        Map<String, String> placeholders = Map.of("%player%", senderName, "{message}", message);
        Component comp = plugin.getTextService().parse(staffChatFormat, placeholders);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("friendlycore.chat.staffchat")) {
                plugin.getScheduler().runEntity(p, () -> {
                    p.sendMessage(comp);
                    if (staffChatSoundEnabled && staffChatSoundName != null && !staffChatSoundName.isEmpty()) {
                        TextService.playSound(p, staffChatSoundName, 1.0f, 1.0f);
                    }
                });
            }
        }
        Bukkit.getConsoleSender().sendMessage(comp);

        if (crossServerEnabled && syncStaffChat && plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("senderName", senderName);
            payload.put("message", message);
            if (!snapshots.isEmpty()) {
                List<String> snapJsonList = new ArrayList<>();
                for (RenderSnapshot snap : snapshots) {
                    if (snap != null) snapJsonList.add(snap.serializeToJson(gson));
                }
                payload.put("snapshots", gson.toJson(snapJsonList));
            }
            plugin.getRedisService().publish(new CrossServerPacket(
                    plugin.getRedisService().getServerId(),
                    CrossServerPacket.Action.CHAT_STAFF,
                    sender instanceof Player p ? p.getUniqueId() : null,
                    senderName,
                    0.0,
                    gson.toJson(payload)
            ));
        }
    }

    public void sendPrivateMessage(CommandSender sender, String targetName, String message) {
        List<RenderSnapshot> snapshots = Collections.emptyList();
        if (sender instanceof Player player) {
            String sanitized = dev.lyy.friendlycore.common.TextService.sanitizeChatInput(player, message);
            String safeRaw = (player.hasPermission("friendlycore.chat.minimessage"))
                    ? sanitized
                    : net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(sanitized);
            ChatMessage chatMsg = new ChatMessage(player, safeRaw, null, ChatChannel.PRIVATE);
            if (filterManager != null) {
                boolean approved = filterManager.processMessage(chatMsg, true);
                if (!approved || chatMsg.isCancelled()) {
                    return;
                }
            }
            if (renderManager != null) {
                chatMsg.setRawMessage(renderManager.processRenders(chatMsg));
            }
            message = chatMsg.getRawMessage();
            snapshots = chatMsg.getCreatedSnapshots();
        }

        Player localTarget = Bukkit.getPlayerExact(targetName);
        UUID senderUuid = sender instanceof Player p ? p.getUniqueId() : null;
        String senderName = sender.getName();

        if (localTarget != null && localTarget.isOnline()) {
            // If the sender cannot see the vanished local target, treat as offline
            if (sender instanceof Player p && !p.canSee(localTarget) && !p.hasPermission("friendlycore.vanish.see")) {
                plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                        "general.player-not-found", "<red>Player <yellow>%player%</yellow> is not online.</red>"), Map.of("%player%", targetName));
                return;
            }

            UUID targetUuid = localTarget.getUniqueId();
            ChatPlayerData targetData = playerDataMap.get(targetUuid);
            if (targetData == null) {
                plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                        "private-messages.target-data-loading", "<yellow>%player%'s chat settings are still loading. Please try again.</yellow>"), Map.of("%player%", targetName));
                return;
            }

            boolean bypass = sender.hasPermission("friendlycore.chat.bypass.ignore");
            if (targetData.isIgnoring(senderUuid) && !bypass) {
                plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                        "private-messages.target-ignored-you", "<red>You cannot message %player%.</red>"), Map.of("%player%", targetName));
                return;
            }

            if (targetData.hasMessagesClosed() && !bypass) {
                plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                        "private-messages.target-closed", "<red>%player% has disabled private messages.</red>"), Map.of("%player%", targetName));
                return;
            }

            deliverPrivateMessageLocally(sender, localTarget, message);
            return;
        }

        if (crossServerEnabled && syncPrivateMessages && plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            UUID networkUuid = plugin.getRedisService().getNetworkPlayerUuid(targetName);
            if (networkUuid != null && plugin.getRedisService().isPlayerOnlineOnNetwork(networkUuid)) {
                Map<String, String> payload = new LinkedHashMap<>();
                payload.put("senderUuid", senderUuid != null ? senderUuid.toString() : "");
                payload.put("senderName", senderName);
                payload.put("receiverName", targetName);
                payload.put("message", message);
                payload.put("bypassIgnore", Boolean.toString(sender.hasPermission("friendlycore.chat.bypass.ignore")));
                if (!snapshots.isEmpty()) {
                    List<String> snapJsonList = new ArrayList<>();
                    for (RenderSnapshot snap : snapshots) {
                        if (snap != null) snapJsonList.add(snap.serializeToJson(gson));
                    }
                    payload.put("snapshots", gson.toJson(snapJsonList));
                }

                plugin.getRedisService().publish(new CrossServerPacket(
                        plugin.getRedisService().getServerId(),
                        CrossServerPacket.Action.CHAT_PRIVATE,
                        networkUuid,
                        targetName,
                        0.0,
                        gson.toJson(payload)
                ));

                plugin.getTextService().sendMessage(sender, msgSenderFormat, Map.of("%target%", targetName, "{message}", message));

                if (sender instanceof Player p) {
                    setLastReplier(p.getUniqueId(), targetName);
                }

                notifySocialSpies(senderName, targetName, message, true);
                return;
            }
        }

        plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                "general.player-not-found", "<red>Player <yellow>%player%</yellow> is not online.</red>"), Map.of("%player%", targetName));
    }

    private void deliverPrivateMessageLocally(CommandSender sender, Player target, String message) {
        String senderName = sender.getName();
        String targetName = target.getName();

        plugin.getTextService().sendMessage(sender, msgSenderFormat, Map.of("%target%", targetName, "{message}", message));
        plugin.getTextService().sendMessage(target, msgReceiverFormat, Map.of("%sender%", senderName, "{message}", message));

        if (msgSoundEnabled && msgSoundName != null && !msgSoundName.isEmpty()) {
            TextService.playSound(target, msgSoundName, 1.0f, 1.0f);
        }

        if (sender instanceof Player p) {
            setLastReplier(p.getUniqueId(), targetName);
        }
        if (!(sender instanceof Player p) || target.canSee(p)) {
            setLastReplier(target.getUniqueId(), senderName);
        }

        notifySocialSpies(senderName, targetName, message, true);
    }

    public void notifySocialSpies(String senderName, String targetName, String message, boolean broadcastRedis) {
        Map<String, String> placeholders = Map.of("%sender%", senderName, "%target%", targetName, "{message}", message);
        Component spyComp = plugin.getTextService().parse(spyFormat, placeholders);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isSpying(p.getUniqueId()) && p.hasPermission("friendlycore.chat.command.spymsg")
                    && !p.getName().equalsIgnoreCase(senderName) && !p.getName().equalsIgnoreCase(targetName)) {
                plugin.getScheduler().runEntity(p, () -> p.sendMessage(spyComp));
            }
        }

        if (broadcastRedis && crossServerEnabled && syncSocialSpy && plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            Map<String, String> payload = Map.of("sender", senderName, "target", targetName, "message", message);
            plugin.getRedisService().publish(new CrossServerPacket(
                    plugin.getRedisService().getServerId(),
                    CrossServerPacket.Action.CHAT_SPY,
                    null,
                    null,
                    0.0,
                    gson.toJson(payload)
            ));
        }
    }

    public void broadcastCrossServerPublicChat(Player player, String rawMessage) {
        broadcastCrossServerPublicChat(player, rawMessage, Collections.emptyList());
    }

    public void broadcastCrossServerPublicChat(Player player, String rawMessage, List<RenderSnapshot> snapshots) {
        if (!crossServerEnabled || !syncPublicChat || plugin.getRedisService() == null || !plugin.getRedisService().isEnabled() || player == null) return;

        String prefix = plugin.getTextService().getPlayerPrefix(player);
        String suffix = plugin.getTextService().getPlayerSuffix(player);
        String group = plugin.getTextService().getPlayerPrimaryGroup(player);
        String healthScale = String.valueOf(player.isHealthScaled() ? (int) player.getHealthScale() : (int) Math.round(player.getHealth()));
        String ping = String.valueOf(player.getPing());
        String balance = plugin.getTextService().getPlayerBalance(player);
        String serverId = plugin.getRedisService().getServerId();

        Map<String, String> payload = new java.util.LinkedHashMap<>();
        payload.put("senderUuid", player.getUniqueId().toString());
        payload.put("senderName", player.getName());
        payload.put("displayName", player.getDisplayName() != null ? player.getDisplayName() : player.getName());
        payload.put("serverName", serverId != null ? serverId : "server");
        payload.put("message", rawMessage);
        payload.put("bypassIgnore", Boolean.toString(player.hasPermission("friendlycore.chat.bypass.ignore")));
        payload.put("bypassChatToggle", Boolean.toString(player.hasPermission("friendlycore.chat.bypass.chattoggle")));
        if (prefix != null && !prefix.isEmpty()) payload.put("prefix", prefix);
        if (suffix != null && !suffix.isEmpty()) payload.put("suffix", suffix);
        if (group != null && !group.isEmpty()) payload.put("group", group);
        payload.put("healthScale", healthScale);
        payload.put("ping", ping);
        if (balance != null && !balance.isEmpty()) payload.put("balance", balance);

        if (snapshots != null && !snapshots.isEmpty()) {
            List<String> snapJsonList = new ArrayList<>();
            for (RenderSnapshot snap : snapshots) {
                if (snap != null) {
                    snapJsonList.add(snap.serializeToJson(gson));
                }
            }
            payload.put("snapshots", gson.toJson(snapJsonList));
        }

        plugin.getRedisService().publish(new CrossServerPacket(
                serverId,
                CrossServerPacket.Action.CHAT_PUBLIC,
                player.getUniqueId(),
                player.getName(),
                0.0,
                gson.toJson(payload)
        ));
    }

    public void handleIncomingStaffChat(String senderName, String messageOrJson) {
        String message = messageOrJson;
        if (messageOrJson != null && messageOrJson.trim().startsWith("{")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> map = gson.fromJson(messageOrJson, Map.class);
                if (map.containsKey("senderName")) {
                    senderName = map.get("senderName");
                }
                if (map.containsKey("message")) {
                    message = map.get("message");
                }
                if (map.containsKey("snapshots") && renderManager != null) {
                    @SuppressWarnings("unchecked")
                    List<String> snapList = gson.fromJson(map.get("snapshots"), List.class);
                    if (snapList != null) {
                        for (String sJson : snapList) {
                            RenderSnapshot snap = RenderSnapshot.deserializeFromJson(sJson, gson);
                            if (snap != null) {
                                renderManager.registerSnapshot(snap);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        Map<String, String> placeholders = Map.of("%player%", senderName, "{message}", message);
        Component comp = plugin.getTextService().parse(staffChatFormat, placeholders);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("friendlycore.chat.staffchat")) {
                plugin.getScheduler().runEntity(p, () -> {
                    p.sendMessage(comp);
                    if (staffChatSoundEnabled && staffChatSoundName != null && !staffChatSoundName.isEmpty()) {
                        TextService.playSound(p, staffChatSoundName, 1.0f, 1.0f);
                    }
                });
            }
        }
        Bukkit.getConsoleSender().sendMessage(comp);
    }

    public void handleIncomingPrivateMessage(UUID receiverUuid, String payloadJson) {
        if (payloadJson == null || payloadJson.isEmpty()) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> map = gson.fromJson(payloadJson, Map.class);
            String senderUuidStr = map.get("senderUuid");
            UUID senderUuid = (senderUuidStr != null && !senderUuidStr.isEmpty()) ? UUID.fromString(senderUuidStr) : null;
            String senderName = map.get("senderName");
            String message = map.get("message");
            boolean bypassIgnore = Boolean.parseBoolean(map.getOrDefault("bypassIgnore", "false"));

            if (map.containsKey("snapshots") && renderManager != null) {
                try {
                    @SuppressWarnings("unchecked")
                    List<String> snapList = gson.fromJson(map.get("snapshots"), List.class);
                    if (snapList != null) {
                        for (String sJson : snapList) {
                            RenderSnapshot snap = RenderSnapshot.deserializeFromJson(sJson, gson);
                            if (snap != null) {
                                renderManager.registerSnapshot(snap);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            Player target = receiverUuid != null ? Bukkit.getPlayer(receiverUuid) : null;
            if (target == null) {
                String receiverName = map.get("receiverName");
                target = receiverName != null ? Bukkit.getPlayerExact(receiverName) : null;
            }
            if (target != null && target.isOnline()) {
                ChatPlayerData targetData = playerDataMap.get(target.getUniqueId());
                // Fail closed while preferences are loading so an ignored sender cannot
                // race a join and bypass the receiver's privacy settings.
                if (targetData == null) {
                    return;
                }
                if (senderUuid != null && targetData.isIgnoring(senderUuid) && !bypassIgnore) {
                    return;
                }
                if (targetData.hasMessagesClosed() && !bypassIgnore) {
                    return;
                }

                Player recipient = target;
                plugin.getScheduler().runEntity(recipient, () -> {
                    plugin.getTextService().sendMessage(recipient, msgReceiverFormat, Map.of("%sender%", senderName, "{message}", message));
                    if (msgSoundEnabled && msgSoundName != null && !msgSoundName.isEmpty()) {
                        TextService.playSound(recipient, msgSoundName, 1.0f, 1.0f);
                    }
                    setLastReplier(recipient.getUniqueId(), senderName);
                });
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to parse incoming Redis private message", e);
        }
    }

    public void handleIncomingSocialSpy(String payloadJson) {
        if (payloadJson == null || payloadJson.isEmpty()) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> map = gson.fromJson(payloadJson, Map.class);
            String sender = map.get("sender");
            String target = map.get("target");
            String message = map.get("message");
            notifySocialSpies(sender, target, message, false);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to parse incoming Redis social spy packet", e);
        }
    }

    public void handleIncomingPublicChat(String payloadJson) {
        if (payloadJson == null || payloadJson.isEmpty() || !syncPublicChat) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> map = gson.fromJson(payloadJson, Map.class);
            String senderUuidStr = map.get("senderUuid");
            UUID senderUuid = (senderUuidStr != null && !senderUuidStr.isEmpty()) ? UUID.fromString(senderUuidStr) : null;
            String senderName = map.getOrDefault("senderName", "Unknown");
            String displayName = map.get("displayName");
            if (displayName == null || displayName.isEmpty()) {
                displayName = senderName;
            }
            String serverName = map.getOrDefault("serverName", "server");
            String prefix = map.getOrDefault("prefix", "");
            String suffix = map.getOrDefault("suffix", "");
            String group = map.getOrDefault("group", "default");
            String healthScale = map.getOrDefault("healthScale", "20");
            String ping = map.getOrDefault("ping", "0");
            String balance = map.getOrDefault("balance", "0.00");
            String rawMessage = map.getOrDefault("message", "");
            boolean bypassIgnore = Boolean.parseBoolean(map.getOrDefault("bypassIgnore", "false"));
            boolean bypassChatToggle = Boolean.parseBoolean(map.getOrDefault("bypassChatToggle", "false"));

            // Cache attached snapshots received from remote server
            if (map.containsKey("snapshots") && renderManager != null) {
                try {
                    @SuppressWarnings("unchecked")
                    List<String> snapList = gson.fromJson(map.get("snapshots"), List.class);
                    if (snapList != null) {
                        for (String sJson : snapList) {
                            RenderSnapshot snap = RenderSnapshot.deserializeFromJson(sJson, gson);
                            if (snap != null) {
                                renderManager.registerSnapshot(snap);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            // Populate sender placeholders for cross-server message formatting
            Map<String, String> placeholders = new java.util.LinkedHashMap<>();
            placeholders.put("player", senderName);
            placeholders.put("displayname", displayName);
            placeholders.put("player_displayname", displayName);
            placeholders.put("server", serverName);
            placeholders.put("prefix", prefix);
            placeholders.put("suffix", suffix);
            placeholders.put("group", group);
            placeholders.put("health", healthScale);
            placeholders.put("ping", ping);
            placeholders.put("balance", balance);

            List<Player> viewers = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.isOnline() && canReceivePublicChat(p.getUniqueId(), senderUuid, bypassIgnore, bypassChatToggle)) {
                    viewers.add(p);
                }
            }
            Component messageComp = plugin.getTextService().parse(rawMessage);
            if (renderManager != null && renderManager.getMentionHandler() != null) {
                messageComp = renderManager.getMentionHandler().processCrossServerMentions(
                        senderName, senderUuid, messageComp, viewers, pingMutedPlayers);
            }
            Component formattedComp = renderChatMessage(null, placeholders, messageComp);

            // Broadcast to local viewers respecting ignore lists and toggled chat
            for (Player p : viewers) {
                plugin.getScheduler().runEntity(p, () -> p.sendMessage(formattedComp));
            }
            Bukkit.getConsoleSender().sendMessage(formattedComp);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to parse incoming Redis public chat packet", e);
        }
    }

    public void handleIncomingMuteSync(boolean muted, String executorName) {
        this.isChatMuted.set(muted);
        String broadcastKey = muted ? "chat-mute.mute-broadcast" : "chat-mute.un-mute-broadcast";
        String defMsg = muted ? "<newline><red><b>SERVER CHAT MUTED</b></red><newline><gray>Chat has been muted by <white>%player%</white>.</gray>"
                : "<newline><green><b>SERVER CHAT UNMUTED</b></green><newline><gray>Chat has been unmuted by <white>%player%</white>.</gray>";
        String message = plugin.getConfigService().getMessage("modules/chat/messages.yml", broadcastKey, defMsg)
                .replace("%player%", executorName != null ? executorName : "Network Admin");
        Bukkit.broadcast(plugin.getTextService().parse(message));
    }

    public void handleIncomingClearSync(String executorName) {
        clearChat(executorName, false);
    }

    public void handleIncomingBroadcast(String payloadJson) {
        if (payloadJson == null || payloadJson.isEmpty()) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> map = gson.fromJson(payloadJson, Map.class);
            String type = map.get("type");
            String message = map.get("message");
            String sender = map.get("sender");
            broadcast(type, message, sender, false);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to parse incoming Redis broadcast packet", e);
        }
    }

    public Set<String> getOnlinePlayerNames() {
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            return plugin.getRedisService().getOnlinePlayerNames();
        }
        Set<String> names = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return names;
    }

    public UUID getUuidByName(String username) {
        if (username == null) return null;
        Player p = Bukkit.getPlayerExact(username);
        if (p != null) return p.getUniqueId();
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            return plugin.getRedisService().getNetworkPlayerUuid(username);
        }
        return null;
    }

    public String getUsernameByUuid(UUID uuid) {
        if (uuid == null) return null;
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        ChatPlayerData data = playerDataMap.get(uuid);
        if (data != null && data.getUsername() != null) return data.getUsername();
        return uuid.toString();
    }

    public ChatFilterManager getFilterManager() {
        return filterManager;
    }

    public ChatRenderManager getRenderManager() {
        return renderManager;
    }

    public String getListenerPriority() {
        return listenerPriority;
    }

    public String getChatFormatTemplate() {
        return chatFormatTemplate;
    }

    public String getStaffChatPrefix() {
        return staffChatPrefix;
    }

    public boolean isCrossServerPublicChatEnabled() {
        return crossServerEnabled && syncPublicChat;
    }

    public boolean isCommandMsgEnabled() { return cmdMsgEnabled; }
    public boolean isCommandReplyEnabled() { return cmdReplyEnabled; }
    public boolean isCommandIgnoreEnabled() { return cmdIgnoreEnabled; }
    public boolean isCommandIgnoreAllEnabled() { return cmdIgnoreAllEnabled; }
    public boolean isCommandSpyEnabled() { return cmdSpyEnabled; }
    public boolean isCommandStaffChatEnabled() { return cmdStaffChatEnabled; }
    public boolean isCommandPingEnabled() { return cmdPingEnabled; }
    public boolean isCommandChatToggleEnabled() { return cmdChatToggleEnabled; }
    public boolean isCommandMuteChatEnabled() { return cmdMuteChatEnabled; }
    public boolean isCommandClearChatEnabled() { return cmdClearChatEnabled; }

    public Component renderChatMessage(Player player, Map<String, String> placeholders, Component messageComponent) {
        if (isStructuredFormat && structuredFormatMessage != null) {
            Component headerComp = plugin.getTextService().parseForPlayer(player, structuredFormatMessage, placeholders);

            if (structuredFormatHover != null && !structuredFormatHover.isBlank()) {
                String cleanHover = structuredFormatHover.replace("\r\n", "\n").replace('\r', '\n')
                        .replace("<br>", "<newline>").replace("<br/>", "<newline>").replace("<br />", "<newline>")
                        .replace("<BR>", "<newline>").replace("<BR/>", "<newline>").replace("<BR />", "<newline>");
                Component hoverComp = plugin.getTextService().parseForPlayer(player, cleanHover, placeholders);
                headerComp = headerComp.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(hoverComp));
            }

            if (structuredFormatAction != null && !structuredFormatAction.isBlank()) {
                String targetAction = plugin.getTextService().applyPlayerDefaults(player, structuredFormatAction, placeholders);
                headerComp = attachClickEvent(headerComp, targetAction);
            }

            return Component.empty().append(headerComp).append(messageComponent != null ? messageComponent : Component.empty());
        } else {
            return plugin.getTextService().parseForPlayerWithComponent(
                    player,
                    chatFormatTemplate,
                    placeholders,
                    "{message}",
                    messageComponent
            );
        }
    }

    private static Component attachClickEvent(Component comp, String action) {
        if (comp == null || action == null || action.isBlank()) return comp;
        String act = action.trim();
        if (act.toUpperCase().startsWith("SUGGEST_COMMAND:")) {
            String cmd = act.substring("SUGGEST_COMMAND:".length()).trim();
            return comp.clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(cmd));
        } else if (act.toUpperCase().startsWith("RUN_COMMAND:")) {
            String cmd = act.substring("RUN_COMMAND:".length()).trim();
            return comp.clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(cmd));
        } else if (act.toUpperCase().startsWith("OPEN_URL:")) {
            String url = act.substring("OPEN_URL:".length()).trim();
            return comp.clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(url));
        } else if (act.toUpperCase().startsWith("COPY_TO_CLIPBOARD:")) {
            String copy = act.substring("COPY_TO_CLIPBOARD:".length()).trim();
            return comp.clickEvent(net.kyori.adventure.text.event.ClickEvent.copyToClipboard(copy));
        }
        return comp;
    }

    public static String buildStructuredChatFormat(String message, String hoverMessage, String action) {
        StringBuilder sb = new StringBuilder();
        boolean hasAction = action != null && !action.isBlank();
        boolean hasHover = hoverMessage != null && !hoverMessage.isBlank();

        if (hasAction) {
            String act = action.trim();
            if (act.toUpperCase().startsWith("SUGGEST_COMMAND:")) {
                String cmd = act.substring("SUGGEST_COMMAND:".length()).trim().replace("'", "\\'");
                sb.append("<click:suggest_command:'").append(cmd).append("'>");
            } else if (act.toUpperCase().startsWith("RUN_COMMAND:")) {
                String cmd = act.substring("RUN_COMMAND:".length()).trim().replace("'", "\\'");
                sb.append("<click:run_command:'").append(cmd).append("'>");
            } else if (act.toUpperCase().startsWith("OPEN_URL:")) {
                String url = act.substring("OPEN_URL:".length()).trim().replace("'", "\\'");
                sb.append("<click:open_url:'").append(url).append("'>");
            } else if (act.toUpperCase().startsWith("COPY_TO_CLIPBOARD:")) {
                String copy = act.substring("COPY_TO_CLIPBOARD:".length()).trim().replace("'", "\\'");
                sb.append("<click:copy_to_clipboard:'").append(copy).append("'>");
            } else if (act.startsWith("<click:")) {
                sb.append(act);
            }
        }

        if (hasHover) {
            String cleanHover = TextService.convertLegacyToMiniMessage(hoverMessage)
                    .replace("\r\n", "<newline>")
                    .replace("\n", "<newline>")
                    .replace("<br>", "<newline>")
                    .replace("'", "\\'");
            sb.append("<hover:show_text:'").append(cleanHover).append("'>");
        }

        String convertedMsg = message != null
                ? TextService.convertLegacyToMiniMessage(message)
                : "<white>%player%</white><dark_gray> » </dark_gray>";
        sb.append(convertedMsg);

        if (hasHover) {
            sb.append("</hover>");
        }

        if (hasAction) {
            sb.append("</click>");
        }

        if (message == null || !message.contains("{message}")) {
            sb.append("<white>{message}</white>");
        }

        return sb.toString();
    }
}
