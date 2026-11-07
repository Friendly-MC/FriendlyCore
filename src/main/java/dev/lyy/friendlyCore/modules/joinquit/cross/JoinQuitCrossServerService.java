package dev.lyy.friendlycore.modules.joinquit.cross;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.modules.joinquit.JoinQuitGroup;
import dev.lyy.friendlycore.modules.joinquit.JoinQuitManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

/**
 * Coordinates network-wide join and quit message broadcasting and reception over Redis Pub/Sub,
 * with automatic server-switch and proxy transfer detection to prevent cross-server spam.
 */
public class JoinQuitCrossServerService {

    private static final String PENDING_QUIT_PREFIX = "friendlycore:pending_quit:";
    // ponytail: unmarked external transfers use a grace period; proxy session events cover those transfers.
    private static final long GRACE_PERIOD_SECONDS = 3L;
    public static final long TRANSFER_TIMEOUT_SECONDS = 30L;
    private static final int PENDING_QUIT_TTL_SECONDS = 30;
    private static final String CONSUME_PENDING_QUIT = """
            local value = redis.call('GET', KEYS[1])
            redis.call('DEL', KEYS[1])
            return value
            """;
    private static final String CLAIM_PENDING_QUIT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final FriendlyCore plugin;
    private final JoinQuitManager manager;
    private final Gson gson = new Gson();
    // Local rate limiting cache: UUID -> last broadcast timestamp (ms)
    private final Map<UUID, Long> recentBroadcasts = new ConcurrentHashMap<>();
    private record PreparedTransfer(String token, CompletableFuture<String> ready) {}
    private final Map<UUID, PreparedTransfer> preparedTransfers = new ConcurrentHashMap<>();
    private record JoinSnapshot(UUID uuid, String name, String displayName, String serverName,
                                String formattedMessage, String sound, boolean silent) {}

    public JoinQuitCrossServerService(FriendlyCore plugin, JoinQuitManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void loadConfiguration(FileConfiguration config) {
    }

    public void shutdown() {
        recentBroadcasts.clear();
        preparedTransfers.clear();
    }

    public boolean hasPreparedTransfer(UUID uuid) {
        return preparedTransfers.containsKey(uuid);
    }

    /** Writes the marker before the proxy can connect the destination backend. */
    public CompletableFuture<String> prepareTransfer(UUID uuid) {
        PreparedTransfer transfer = new PreparedTransfer(getLocalServerId() + ":" + UUID.randomUUID(), new CompletableFuture<>());
        if (preparedTransfers.putIfAbsent(uuid, transfer) != null) return CompletableFuture.completedFuture(null);
        try {
            plugin.getScheduler().getAsyncExecutor().execute(() -> {
                try {
                    if (!isCrossServerActive() || plugin.getRedisService().getRedisClient() == null) {
                        throw new IllegalStateException("Redis is unavailable");
                    }
                    plugin.getRedisService().getRedisClient().setex(PENDING_QUIT_PREFIX + uuid,
                            TRANSFER_TIMEOUT_SECONDS + PENDING_QUIT_TTL_SECONDS, transfer.token());
                    transfer.ready().complete(transfer.token());
                } catch (Exception e) {
                    preparedTransfers.remove(uuid, transfer);
                    transfer.ready().complete(null);
                    plugin.getLogger().log(Level.WARNING, "Could not prepare server transfer for " + uuid, e);
                }
            });
        } catch (RejectedExecutionException e) {
            preparedTransfers.remove(uuid, transfer);
            transfer.ready().complete(null);
        }
        return transfer.ready();
    }

    public void cancelPreparedTransfer(UUID uuid, String token) {
        PreparedTransfer transfer = preparedTransfers.get(uuid);
        if (transfer == null || !transfer.token().equals(token) || !preparedTransfers.remove(uuid, transfer)) return;
        submitBackground(() -> {
            try {
                plugin.getRedisService().getRedisClient().eval(CLAIM_PENDING_QUIT,
                        List.of(PENDING_QUIT_PREFIX + uuid), List.of(token));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not cancel server transfer for " + uuid, e);
            }
        }, "cancelled transfer for " + uuid);
    }

    public boolean isCrossServerActive() {
        return manager.isEnabled()
                && plugin.getRedisService() != null
                && plugin.getRedisService().isEnabled();
    }

    public String getLocalServerId() {
        if (plugin.getRedisService() != null && plugin.getRedisService().getServerId() != null) {
            return plugin.getRedisService().getServerId();
        }
        return "server-1";
    }

    /**
     * Checks whether the player is switching from another backend server or freshly joining the network.
     * Consumes a pending quit atomically, including a reconnect to the same backend.
     *
     * @return false; detection and all announcements are performed asynchronously
     */
    public boolean handlePlayerJoin(Player player, JoinQuitGroup group, boolean isSilent) {
        if (!isCrossServerActive() || player == null) {
            return false;
        }
        JoinSnapshot snapshot = captureJoinSnapshot(player, group, isSilent);
        submitBackground(() -> {
                boolean isSwitch = false;
                try {
                    if (plugin.getRedisService().getRedisClient() != null) {
                        isSwitch = plugin.getRedisService().getRedisClient().eval(
                                CONSUME_PENDING_QUIT, List.of(PENDING_QUIT_PREFIX + snapshot.uuid()), List.of()) != null;
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Skipped join announcement: could not check transfer for " + snapshot.name(), e);
                    return;
                }
                if (!isSwitch) {
                    dispatchJoinSnapshot(snapshot);
                }
            }, "network join for " + snapshot.name());
        // Detection is asynchronous so a degraded Redis server can never hold the
        // player join thread. The return value is retained for API compatibility.
        return false;
    }

    /**
     * Dispatches a cross-server join packet to Redis.
     */
    public void dispatchJoin(Player player, JoinQuitGroup group, boolean isSilent) {
        handlePlayerJoin(player, group, isSilent);
    }

    private JoinSnapshot captureJoinSnapshot(Player player, JoinQuitGroup group, boolean isSilent) {
        String displayName;
        try {
            displayName = player.getDisplayName();
        } catch (Throwable ignored) {
            displayName = player.getName();
        }
        String formattedMessage = (group != null && !group.joinMessage().isEmpty())
                ? group.joinMessage() : manager.getJoinMessage();
        String sound = (group != null && !group.joinSound().equalsIgnoreCase("none"))
                ? group.joinSound() : manager.getJoinSound();
        return new JoinSnapshot(player.getUniqueId(), player.getName(), displayName,
                getLocalServerId(), formattedMessage, sound, isSilent);
    }

    private void dispatchJoinSnapshot(JoinSnapshot snapshot) {
        if (!isCrossServerActive() || !manager.isJoinEnabled()) return;

        long now = System.currentTimeMillis();
        Long lastTime = recentBroadcasts.put(snapshot.uuid(), now);
        if (lastTime != null && (now - lastTime) < 3000L) {
            return;
        }
        plugin.getScheduler().runAsyncLater(() -> recentBroadcasts.remove(snapshot.uuid(), now), 100L);

        try {
            JsonObject extra = new JsonObject();
            extra.addProperty("serverName", snapshot.serverName());
            extra.addProperty("displayName", snapshot.displayName());
            extra.addProperty("silent", snapshot.silent());
            if (snapshot.formattedMessage() != null && !snapshot.formattedMessage().isEmpty()) {
                extra.addProperty("formattedMessage", snapshot.formattedMessage());
            }
            if (snapshot.sound() != null && !snapshot.sound().equalsIgnoreCase("none")) {
                extra.addProperty("sound", snapshot.sound());
            }

            CrossServerPacket packet = new CrossServerPacket(
                    snapshot.serverName(),
                    CrossServerPacket.Action.NETWORK_JOIN,
                    snapshot.uuid(),
                    snapshot.name(),
                    0.0,
                    gson.toJson(extra)
            );

            plugin.getRedisService().publish(packet);
            // Redis discards our own pub/sub echo; deliver locally through the same renderer.
            plugin.getScheduler().runGlobal(() -> handleIncomingPacket(packet));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to dispatch cross-server join packet for " + snapshot.name(), e);
        }
    }

    /**
     * Handles player quit: records pending quit in Redis with a grace period.
     * If the player does not join another server within the grace period, broadcasts NETWORK_QUIT.
     */
    public void handlePlayerQuit(Player player, JoinQuitGroup group, boolean isSilent) {
        if (!isCrossServerActive() || player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        PreparedTransfer transfer = preparedTransfers.remove(uuid);
        String name = player.getName();
        String localServer = getLocalServerId();
        String quitToken = localServer + ":" + UUID.randomUUID();
        String formattedMessage = (group != null && !group.quitMessage().isEmpty())
                ? group.quitMessage()
                : manager.getQuitMessage();
        String sound = (group != null && !group.quitSound().equalsIgnoreCase("none"))
                ? group.quitSound()
                : manager.getQuitSound();
        String tempDisplayName;
        try {
            tempDisplayName = player.getDisplayName();
        } catch (Throwable ignored) {
            tempDisplayName = name;
        }
        final String displayName = tempDisplayName;

        CompletableFuture<String> ready = transfer != null ? transfer.ready() : CompletableFuture.completedFuture(null);
        ready.thenAccept(preparedToken -> submitBackground(() -> {
            try {
                if (!isCrossServerActive()) return;
                // Prepared markers already exist and may have been consumed by the destination.
                if (preparedToken == null && plugin.getRedisService().getRedisClient() != null) {
                    plugin.getRedisService().getRedisClient().setex(
                            PENDING_QUIT_PREFIX + uuid, PENDING_QUIT_TTL_SECONDS, quitToken);
                }
                plugin.getScheduler().runAsyncLater(() -> submitBackground(
                        () -> processPendingQuit(uuid, name, displayName, localServer,
                                formattedMessage, sound, isSilent, preparedToken != null ? preparedToken : quitToken),
                        "delayed network quit for " + name),
                        (preparedToken != null ? TRANSFER_TIMEOUT_SECONDS : GRACE_PERIOD_SECONDS) * 20L);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error registering delayed network quit for " + name, e);
            }
        }, "network quit for " + name));
    }

    private void submitBackground(Runnable task, String description) {
        try {
            plugin.getScheduler().getAsyncExecutor().execute(task);
        } catch (RejectedExecutionException ignored) {
            plugin.getLogger().warning("Async queue is saturated; skipped " + description);
        }
    }

    private void processPendingQuit(UUID uuid, String name, String displayName, String localServer,
                                    String formattedMessage, String sound, boolean isSilent, String quitToken) {
        try {
            if (!isCrossServerActive()) return;
            boolean stillPending = false;
            if (plugin.getRedisService().getRedisClient() != null) {
                // Each quit owns its token: an older timer cannot claim a later quit on this backend.
                stillPending = Long.valueOf(1L).equals(plugin.getRedisService().getRedisClient().eval(
                        CLAIM_PENDING_QUIT, List.of(PENDING_QUIT_PREFIX + uuid), List.of(quitToken)));
            }
            if (stillPending) {
                dispatchNetworkQuit(uuid, name, displayName, localServer, formattedMessage, sound, isSilent);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error processing delayed network quit for " + name, e);
        }
    }

    /**
     * Dispatches a cross-server quit packet to Redis.
     */
    public void dispatchQuit(Player player, JoinQuitGroup group, boolean isSilent) {
        handlePlayerQuit(player, group, isSilent);
    }

    /**
     * Dispatches a cross-server quit packet to Redis immediately.
     */
    public void dispatchNetworkQuit(UUID uuid, String name, String displayName, String serverName,
                                    String formattedMessage, String sound, boolean isSilent) {
        if (!isCrossServerActive() || !manager.isQuitEnabled()) return;

        try {
            JsonObject extra = new JsonObject();
            extra.addProperty("serverName", serverName);
            extra.addProperty("displayName", displayName != null ? displayName : name);
            extra.addProperty("silent", isSilent);
            if (formattedMessage != null && !formattedMessage.isEmpty()) {
                extra.addProperty("formattedMessage", formattedMessage);
            }
            if (sound != null && !sound.equalsIgnoreCase("none")) {
                extra.addProperty("sound", sound);
            }

            CrossServerPacket packet = new CrossServerPacket(
                    serverName,
                    CrossServerPacket.Action.NETWORK_QUIT,
                    uuid,
                    name,
                    0.0,
                    gson.toJson(extra)
            );

            plugin.getRedisService().publish(packet);
            plugin.getScheduler().runGlobal(() -> handleIncomingPacket(packet));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to dispatch cross-server quit packet for " + name, e);
        }
    }

    /**
     * Handles incoming network packets from other servers.
     */
    public void handleIncomingPacket(CrossServerPacket packet) {
        if (packet == null || packet.getAction() == null || !manager.isEnabled()) {
            return;
        }

        try {
            String sourceServer = packet.getSourceServer() != null ? packet.getSourceServer() : "Unknown";
            String playerName = packet.getTargetName() != null ? packet.getTargetName() : "Unknown";
            UUID playerUuid = packet.getTargetUuid();

            String displayName = playerName;
            boolean isSilent = false;
            String formattedMessage = null;
            String sound = null;

            if (packet.getExtra() != null && !packet.getExtra().isBlank()) {
                try {
                    JsonObject json = gson.fromJson(packet.getExtra(), JsonObject.class);
                    if (json != null) {
                        if (json.has("serverName")) sourceServer = json.get("serverName").getAsString();
                        if (json.has("displayName")) displayName = json.get("displayName").getAsString();
                        if (json.has("silent")) isSilent = json.get("silent").getAsBoolean();
                        if (json.has("formattedMessage")) formattedMessage = json.get("formattedMessage").getAsString();
                        if (json.has("sound")) sound = json.get("sound").getAsString();
                    }
                } catch (Exception e) {
                    plugin.getLogger().fine("Could not parse extra json from cross-server packet: " + e.getMessage());
                }
            }

            if (packet.getAction() == CrossServerPacket.Action.NETWORK_JOIN) {
                handleRemoteJoin(playerUuid, playerName, displayName, sourceServer, isSilent, formattedMessage, sound);
            } else if (packet.getAction() == CrossServerPacket.Action.NETWORK_QUIT) {
                handleRemoteQuit(playerUuid, playerName, displayName, sourceServer, isSilent, formattedMessage, sound);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to process cross-server join/quit packet", e);
        }
    }

    private void handleRemoteJoin(UUID playerUuid, String playerName, String displayName,
                                  String serverName, boolean isSilent, String formattedMessage, String sound) {
        if (!manager.isJoinEnabled()) return;
        Map<String, String> placeholders = buildPlaceholders(playerName, displayName, serverName);

        if (isSilent) {
            String staffNotify = manager.getStaffJoinNotify();
            if (staffNotify != null && !staffNotify.isEmpty()) {
                String perm = manager.getStaffNotifyPermission();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    plugin.getScheduler().runEntity(p, () -> {
                        if (p.hasPermission(perm) || p.hasPermission("friendlycore.admin")) {
                            plugin.getTextService().sendMessage(p, staffNotify, placeholders);
                        }
                    });
                }
            }
            return;
        }

        String message = (formattedMessage != null && !formattedMessage.isEmpty())
                ? formattedMessage
                : manager.getJoinMessage();

        boolean sendMessage = message != null && !message.isEmpty() && !message.equalsIgnoreCase("DISABLED");
        String playSound = (sound != null && !sound.equalsIgnoreCase("none"))
                ? sound
                : manager.getJoinSound();
        boolean sendSound = playSound != null && !playSound.equalsIgnoreCase("none");
        if (sendMessage || sendSound) {
            for (Player target : List.copyOf(Bukkit.getOnlinePlayers())) {
                plugin.getScheduler().runEntity(target, () -> {
                    if (sendMessage && !manager.isPlayerMuted(target.getUniqueId())) {
                        plugin.getTextService().sendMessage(target, message, placeholders);
                    }
                    if (sendSound) {
                        TextService.playSound(target, playSound, 1.0f, 1.0f);
                    }
                });
            }
        }
    }

    private void handleRemoteQuit(UUID playerUuid, String playerName, String displayName,
                                  String serverName, boolean isSilent, String formattedMessage, String sound) {
        if (!manager.isQuitEnabled()) return;
        Map<String, String> placeholders = buildPlaceholders(playerName, displayName, serverName);

        if (isSilent) {
            String staffNotify = manager.getStaffQuitNotify();
            if (staffNotify != null && !staffNotify.isEmpty()) {
                String perm = manager.getStaffNotifyPermission();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    plugin.getScheduler().runEntity(p, () -> {
                        if (p.hasPermission(perm) || p.hasPermission("friendlycore.admin")) {
                            plugin.getTextService().sendMessage(p, staffNotify, placeholders);
                        }
                    });
                }
            }
            return;
        }

        String message = (formattedMessage != null && !formattedMessage.isEmpty())
                ? formattedMessage
                : manager.getQuitMessage();

        boolean sendMessage = message != null && !message.isEmpty() && !message.equalsIgnoreCase("DISABLED");
        String playSound = (sound != null && !sound.equalsIgnoreCase("none"))
                ? sound
                : manager.getQuitSound();
        boolean sendSound = playSound != null && !playSound.equalsIgnoreCase("none");
        if (sendMessage || sendSound) {
            for (Player target : List.copyOf(Bukkit.getOnlinePlayers())) {
                plugin.getScheduler().runEntity(target, () -> {
                    if (sendMessage && !manager.isPlayerMuted(target.getUniqueId())) {
                        plugin.getTextService().sendMessage(target, message, placeholders);
                    }
                    if (sendSound) {
                        TextService.playSound(target, playSound, 1.0f, 1.0f);
                    }
                });
            }
        }
    }

    private Map<String, String> buildPlaceholders(String playerName, String displayName, String serverName) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("%player%", playerName);
        map.put("%name%", playerName);
        map.put("%displayname%", displayName != null ? displayName : playerName);
        map.put("%server%", serverName != null ? serverName : getLocalServerId());
        map.put("%unique_joins%", String.valueOf(manager.getUniqueJoinsCount()));
        map.put("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()));
        map.put("%max_online%", String.valueOf(Bukkit.getMaxPlayers()));
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            map.put("%network_online%", String.valueOf(plugin.getRedisService().getOnlinePlayerNames().size()));
        } else {
            map.put("%network_online%", String.valueOf(Bukkit.getOnlinePlayers().size()));
        }
        return map;
    }
}
