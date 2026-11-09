package dev.lyy.friendlycore.common.redis;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.DefaultJedisSocketFactory;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Redis integration service managing PubSub messaging channels, Jedis connection pools,
 * and cross-network player presence tracking.
 */
public class RedisService {

    private final FriendlyCore plugin;
    private final Gson gson = new Gson();
    private volatile boolean enabled;
    private String serverId;
    private String pubsubChannel;
    private String sharedSecret;
    private int maxClockSkewSeconds;
    private int maxMessageBytes;

    private UnifiedJedis redisClient;
    private CrossServerListener listener;
    private volatile Jedis subJedis;
    private Thread pubSubThread;
    private ExecutorService publishExecutor;
    private ExecutorService presenceExecutor;
    private Object heartbeatTask;
    private boolean listenerRegistered = false;

    private final Set<String> networkOnlinePlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> networkOnlineUuids = ConcurrentHashMap.newKeySet();
    private final Cache<String, UUID> networkPlayerUuids = CacheBuilder.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(6, TimeUnit.HOURS)
            .build();

    public RedisService(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    private ExecutorService newBoundedSingleThreadExecutor(String threadName, int queueCapacity) {
        return new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, threadName);
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public void initialize() {
        FileConfiguration config = plugin.getConfigService().getRedisConfig();
        this.enabled = config.getBoolean("redis.enabled", false);
        if (!enabled) {
            return;
        }

        String host = config.getString("redis.host", "127.0.0.1");
        int port = config.getInt("redis.port", 6379);
        String password = config.getString("redis.password", "");
        boolean ssl = config.getBoolean("redis.ssl", false);
        int timeout = Math.max(5000, config.getInt("redis.timeout", 5000));
        this.serverId = config.getString("redis.server-id", "server-1");
        this.pubsubChannel = config.getString("redis.channels.sync", config.getString("redis.channels.balance-update", "friendlycore:cross_sync"));
        this.sharedSecret = config.getString("redis.security.shared-secret", "");
        this.maxClockSkewSeconds = Math.max(5, config.getInt("redis.security.max-clock-skew-seconds", 30));
        this.maxMessageBytes = Math.max(4096, config.getInt("redis.security.max-message-bytes", 8388608));

        if (sharedSecret == null || sharedSecret.length() < 32) {
            this.enabled = false;
            plugin.getLogger().severe("Redis was enabled without redis.security.shared-secret (minimum 32 characters); cross-server sync has been disabled.");
            return;
        }

        this.publishExecutor = newBoundedSingleThreadExecutor("FriendlyCore-RedisPublisher", 2048);

        // Single-threaded executor preserves join/quit ordering per server,
        // preventing a fast join->quit race from leaving ghost players in Redis.
        this.presenceExecutor = newBoundedSingleThreadExecutor("FriendlyCore-RedisPresence", 512);

        DefaultJedisClientConfig.Builder clientConfig = DefaultJedisClientConfig.builder()
                .timeoutMillis(timeout)
                .ssl(ssl);

        if (password != null && !password.isEmpty()) {
            clientConfig.password(password);
        }

        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
        poolConfig.setMinIdle(2);
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxWait(Duration.ofMillis(Math.max(1, timeout)));

        HostAndPort hostAndPort = new HostAndPort(host, port);
        DefaultJedisClientConfig pooledConfig = clientConfig.build();
        DefaultJedisSocketFactory socketFactory = new DefaultJedisSocketFactory(hostAndPort, pooledConfig) {
            @Override
            public java.net.Socket createSocket() {
                java.net.Socket socket = super.createSocket();
                try {
                    socket.setKeepAlive(true);
                    socket.setTcpNoDelay(true);
                } catch (java.net.SocketException ignored) {
                }
                return socket;
            }
        };

        try {
            this.redisClient = redis.clients.jedis.RedisClient.builder()
                    .connectionProvider(new redis.clients.jedis.providers.PooledConnectionProvider(
                            new redis.clients.jedis.ConnectionFactory(socketFactory, pooledConfig), poolConfig))
                    .clientConfig(pooledConfig).build();

            String ping = redisClient.ping();
            if (!"PONG".equalsIgnoreCase(ping)) {
                throw new IllegalStateException("Redis server returned unexpected ping response: " + ping);
            }

            if (!listenerRegistered) {
                try {
                    Bukkit.getPluginManager().registerEvents(new RedisPlayerListener(), plugin);
                    this.listenerRegistered = true;
                } catch (Throwable ignored) {
                    // Running in headless / test environment without active Bukkit event system
                }
            }

            // Clean up stale entries for this server and sync current online players
            CompletableFuture.runAsync(() -> {
                try {
                    // Clear stale entries from previous runs of this server instance
                    String serverPlayersKey = "friendlycore:server_players:" + serverId;
                    Set<String> stalePlayers = redisClient.smembers(serverPlayersKey);
                    if (stalePlayers != null && !stalePlayers.isEmpty()) {
                        redisClient.srem("friendlycore:online_players", stalePlayers.toArray(new String[0]));
                        redisClient.del(serverPlayersKey);
                    }

                    // Register currently online players (relevant on plugin reload)
                    try {
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            String name = player.getName();
                            UUID uuid = player.getUniqueId();
                            redisClient.sadd(serverPlayersKey, name);
                            redisClient.sadd("friendlycore:online_players", name);
                            redisClient.hset("friendlycore:player_uuids", name.toLowerCase(Locale.ROOT), uuid.toString());
                            networkOnlinePlayers.add(name);
                            networkOnlineUuids.add(uuid);
                            networkPlayerUuids.put(name.toLowerCase(Locale.ROOT), uuid);
                        }
                    } catch (Throwable ignored) {}

                    // Synchronize full network online presence and UUID mapping
                    Set<String> members = redisClient.smembers("friendlycore:online_players");
                    if (members != null) {
                        networkOnlinePlayers.addAll(members);
                    }
                    Map<String, String> uuids = redisClient.hgetAll("friendlycore:player_uuids");
                    if (uuids != null) {
                        Set<String> onlineLower = new HashSet<>();
                        if (members != null) {
                            for (String member : members) onlineLower.add(member.toLowerCase(Locale.ROOT));
                        }
                        List<String> staleUuidKeys = new ArrayList<>();
                        for (Map.Entry<String, String> e : uuids.entrySet()) {
                            String lowerName = e.getKey().toLowerCase(Locale.ROOT);
                            if (!onlineLower.contains(lowerName)) {
                                staleUuidKeys.add(e.getKey());
                                continue;
                            }
                            try {
                                UUID parsedUuid = UUID.fromString(e.getValue());
                                networkPlayerUuids.put(lowerName, parsedUuid);
                                networkOnlineUuids.add(parsedUuid);
                            } catch (Exception ignored) {}
                        }
                        if (!staleUuidKeys.isEmpty()) {
                            redisClient.hdel("friendlycore:player_uuids", staleUuidKeys.toArray(new String[0]));
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to synchronize network player cache from Redis", e);
                }
            }, plugin.getScheduler().getAsyncExecutor());

            // Start PubSub background thread with auto-reconnect
            this.listener = new CrossServerListener(plugin, serverId, pubsubChannel, sharedSecret,
                    maxClockSkewSeconds, maxMessageBytes);
            this.pubSubThread = new Thread(() -> {
                while (enabled && !Thread.currentThread().isInterrupted()) {
                    Jedis jedis = null;
                    try {
                        DefaultJedisClientConfig.Builder pubSubConfigBuilder = DefaultJedisClientConfig.builder()
                                .connectionTimeoutMillis(5000)
                                .socketTimeoutMillis(0)
                                .autoNegotiateProtocol(false)
                                .ssl(ssl);
                        if (password != null && !password.isEmpty()) {
                            pubSubConfigBuilder.password(password);
                        }
                        DefaultJedisClientConfig pubSubConfig = pubSubConfigBuilder.build();
                        DefaultJedisSocketFactory pubSubSocketFactory = new DefaultJedisSocketFactory(hostAndPort, pubSubConfig) {
                            @Override
                            public java.net.Socket createSocket() {
                                java.net.Socket socket = super.createSocket();
                                try {
                                    socket.setKeepAlive(true);
                                    socket.setTcpNoDelay(true);
                                } catch (java.net.SocketException ignored) {
                                }
                                return socket;
                            }
                        };
                        jedis = new Jedis(pubSubSocketFactory, pubSubConfig);
                        this.subJedis = jedis;
                        jedis.subscribe(listener, pubsubChannel);
                    } catch (Exception e) {
                        if (!enabled || Thread.currentThread().isInterrupted()) {
                            break;
                        }
                        plugin.getLogger().log(Level.WARNING, "Redis PubSub connection dropped (" + e.getMessage() + "). Reconnecting in 3s...");
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } finally {
                        if (jedis != null) {
                            try {
                                jedis.close();
                            } catch (Throwable ignored) {}
                        }
                        if (this.subJedis == jedis) {
                            this.subJedis = null;
                        }
                    }
                }
            }, "FriendlyCore-RedisPubSub");
            this.pubSubThread.setDaemon(true);
            this.pubSubThread.start();

            // Periodic heartbeat so crashed servers' players are detected and cleaned up.
            try {
                this.heartbeatTask = plugin.getScheduler().runAsyncTimer(this::heartbeatAndCleanup, 600L, 600L);
            } catch (Throwable ignored) {
                // Headless / test environment without an active scheduler
            }

            plugin.getLogger().info("Redis cross-server synchronization enabled on authenticated channel: " + pubsubChannel);
        } catch (Exception e) {
            this.enabled = false;
            plugin.getLogger().log(Level.SEVERE, "Failed to connect to Redis server: " + e.getMessage(), e);
        }
    }

    public void reload() {
        shutdown();
        initialize();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getServerId() {
        return serverId;
    }

    public Set<String> getNetworkOnlinePlayers() {
        return networkOnlinePlayers;
    }

    public Map<String, UUID> getNetworkPlayerUuids() {
        return networkPlayerUuids.asMap();
    }

    public Set<String> getOnlinePlayerNames() {
        Set<String> names = new LinkedHashSet<>();
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
        } catch (Throwable ignored) {}
        if (enabled) {
            names.addAll(networkOnlinePlayers);
        }
        return names;
    }

    public UUID getNetworkPlayerUuid(String username) {
        if (username == null) return null;
        return networkPlayerUuids.getIfPresent(username.toLowerCase(Locale.ROOT));
    }

    public Set<UUID> getNetworkOnlineUuids() {
        return networkOnlineUuids;
    }

    public boolean isPlayerOnlineOnNetwork(UUID uuid) {
        if (uuid == null) return false;
        try {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) return true;
        } catch (Throwable ignored) {}
        if (!enabled) return false;
        return networkOnlineUuids.contains(uuid);
    }

    public boolean isPlayerOnlineOnNetwork(String username) {
        if (username == null) return false;
        try {
            Player p = Bukkit.getPlayerExact(username);
            if (p != null && p.isOnline()) return true;
        } catch (Throwable ignored) {}
        if (!enabled) return false;
        return networkOnlinePlayers.contains(username);
    }

    public void onPlayerJoin(Player player) {
        if (player == null) return;
        String name = player.getName();
        UUID uuid = player.getUniqueId();
        networkOnlinePlayers.add(name);
        networkOnlineUuids.add(uuid);
        networkPlayerUuids.put(name.toLowerCase(Locale.ROOT), uuid);

        if (!enabled || redisClient == null || presenceExecutor == null) return;

        try {
            presenceExecutor.execute(() -> {
                try {
                    redisClient.sadd("friendlycore:server_players:" + serverId, name);
                    redisClient.sadd("friendlycore:online_players", name);
                    redisClient.hset("friendlycore:player_uuids", name.toLowerCase(Locale.ROOT), uuid.toString());
                    publish(new CrossServerPacket(serverId, CrossServerPacket.Action.PLAYER_JOIN, uuid, name, 0, null));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to register player in Redis on join", e);
                }
            });
        } catch (RejectedExecutionException saturated) {
            plugin.getLogger().warning("Redis presence queue saturated; skipped join synchronization for " + name);
        }
    }

    public void onPlayerQuit(Player player) {
        if (player == null) return;
        String name = player.getName();
        UUID uuid = player.getUniqueId();
        networkOnlinePlayers.remove(name);
        networkOnlineUuids.remove(uuid);

        if (!enabled || redisClient == null || presenceExecutor == null) return;

        try {
            presenceExecutor.execute(() -> {
                try {
                    redisClient.srem("friendlycore:server_players:" + serverId, name);
                    Set<String> knownServers = redisClient.smembers("friendlycore:known_servers");
                    boolean presentOnOther = false;
                    if (knownServers != null) {
                        for (String s : knownServers) {
                            if (s.equalsIgnoreCase(serverId)) continue;
                            if (redisClient.sismember("friendlycore:server_players:" + s, name)) {
                                presentOnOther = true;
                                break;
                            }
                        }
                    }
                    if (!presentOnOther) {
                        redisClient.srem("friendlycore:online_players", name);
                        redisClient.hdel("friendlycore:player_uuids", name.toLowerCase(Locale.ROOT));
                        networkPlayerUuids.invalidate(name.toLowerCase(Locale.ROOT));
                        networkOnlineUuids.remove(uuid);
                        publish(new CrossServerPacket(serverId, CrossServerPacket.Action.PLAYER_QUIT, uuid, name, 0, null));
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to unregister player in Redis on quit", e);
                }
            });
        } catch (RejectedExecutionException saturated) {
            plugin.getLogger().warning("Redis presence queue saturated; skipped quit synchronization for " + name);
        }
    }

    public void publish(CrossServerPacket packet) {
        if (!enabled || redisClient == null || packet == null || publishExecutor == null) return;
        try {
            publishExecutor.execute(() -> {
                try {
                    packet.sign(sharedSecret);
                    String json = gson.toJson(packet);
                    if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxMessageBytes) {
                        plugin.getLogger().warning("Refusing to publish oversized Redis packet: " + packet.getAction());
                        return;
                    }
                    redisClient.publish(pubsubChannel, json);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to publish Redis packet", e);
                }
            });
        } catch (RejectedExecutionException saturated) {
            plugin.getLogger().warning("Redis publish queue saturated; dropped packet " + packet.getAction());
        }
    }

    public void publish(String channel, String message) {
        if (!enabled || redisClient == null || publishExecutor == null) return;
        try {
            publishExecutor.execute(() -> {
                try {
                    redisClient.publish(channel, message);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to publish Redis message", e);
                }
            });
        } catch (RejectedExecutionException saturated) {
            plugin.getLogger().warning("Redis publish queue saturated; dropped message for " + channel);
        }
    }

    /**
     * Atomically consumes a remote kit grant across the Redis-backed network.
     * Uses NX key with TTL to prevent concurrent duplicate deliveries across servers.
     */
    public CompletableFuture<Boolean> tryConsumeKitGrant(String grantId) {
        if (!enabled || redisClient == null || grantId == null || grantId.length() != 36) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            UUID.fromString(grantId);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                String result = redisClient.set("friendlycore:kit_grant:" + grantId, "1",
                        SetParams.setParams().nx().ex(600));
                return "OK".equalsIgnoreCase(result);
            } catch (Exception e) {
                // Fail closed: delivering when Redis cannot arbitrate can duplicate a grant
                plugin.getLogger().log(Level.WARNING, "Unable to atomically consume remote kit grant " + grantId, e);
                return false;
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    /**
     * Atomically claims a packet nonce in Redis to guarantee network-wide replay protection across restarts.
     * Fails closed if Redis communication is unavailable.
     */
    public boolean claimNonce(String nonce, int ttlSeconds) {
        if (!enabled || redisClient == null || nonce == null || nonce.isBlank()) return false;
        try {
            String key = "friendlycore:nonce:" + (serverId != null ? serverId : "local") + ":" + nonce;
            String result = redisClient.set(key, "1",
                    SetParams.setParams().nx().ex(Math.max(1, ttlSeconds)));
            return "OK".equalsIgnoreCase(result);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Unable to atomically claim Redis packet nonce; rejecting packet", e);
            return false;
        }
    }

    /**
     * Acquires a distributed lock in Redis with specified TTL using SET NX EX.
     */
    public boolean acquireDistributedLock(String lockKey, String lockValue, long expireSeconds) {
        if (!enabled || redisClient == null || lockKey == null) return true;
        try {
            String result = redisClient.set(lockKey, lockValue, SetParams.setParams().nx().ex(Math.max(1, expireSeconds)));
            return "OK".equalsIgnoreCase(result);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error acquiring distributed lock for " + lockKey, e);
            return false; // Fail-closed: prevent concurrent dual-execution on distributed lock failure
        }
    }

    /**
     * Releases a distributed lock in Redis atomically via Lua script to prevent token race conditions.
     */
    public void releaseDistributedLock(String lockKey, String lockValue) {
        if (!enabled || redisClient == null || lockKey == null) return;
        try {
            String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
            redisClient.eval(script, List.of(lockKey), List.of(lockValue));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error releasing distributed lock for " + lockKey, e);
        }
    }

    public void setPersistent(String key, String value) {
        setPersistent(key, value, 0L);
    }

    public void setPersistent(String key, String value, long expireSeconds) {
        if (!enabled || redisClient == null || key == null || value == null) {
            return;
        }
        try {
            if (expireSeconds > 0L) {
                redisClient.set(key, value, SetParams.setParams().ex(expireSeconds));
            } else {
                redisClient.set(key, value);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error writing Redis key " + key, e);
        }
    }

    public String getPersistent(String key) {
        if (!enabled || redisClient == null || key == null) {
            return null;
        }
        try {
            return redisClient.get(key);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error reading Redis key " + key, e);
            return null;
        }
    }

    public void deletePersistent(String key) {
        if (!enabled || redisClient == null || key == null) {
            return;
        }
        try {
            redisClient.del(key);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error deleting Redis key " + key, e);
        }
    }

    private void heartbeatAndCleanup() {
        if (!enabled || redisClient == null) return;
        try {
            redisClient.sadd("friendlycore:known_servers", serverId);
            redisClient.set("friendlycore:server_heartbeat:" + serverId, "1",
                    SetParams.setParams().ex(120));
            // Keep PubSub subscriber connection alive with periodic traffic
            publish(new CrossServerPacket(serverId, CrossServerPacket.Action.HEARTBEAT, null, null, 0.0, null));

            Set<String> knownServers = redisClient.smembers("friendlycore:known_servers");
            if (knownServers != null) {
                for (String server : knownServers) {
                    if (serverId.equals(server)) continue;
                    if (redisClient.get("friendlycore:server_heartbeat:" + server) != null) continue;
                    // Heartbeat expired: server is offline. Clean up its ghost entries.
                    Set<String> stalePlayers = redisClient.smembers("friendlycore:server_players:" + server);
                    if (stalePlayers != null && !stalePlayers.isEmpty()) {
                        redisClient.srem("friendlycore:online_players", stalePlayers.toArray(new String[0]));
                    }
                    redisClient.del("friendlycore:server_players:" + server);
                    redisClient.srem("friendlycore:known_servers", server);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to send Redis presence heartbeat", e);
        }
    }

    public void broadcastBalanceUpdate(UUID uuid, String username, double newBalance) {
        publish(new CrossServerPacket(serverId, CrossServerPacket.Action.BALANCE_UPDATE, uuid, username, newBalance, null));
    }

    public void broadcastCrossServerDeposit(UUID uuid, String username, double deltaAmount) {
        publish(new CrossServerPacket(serverId, CrossServerPacket.Action.CROSS_SERVER_DEPOSIT, uuid, username, deltaAmount, null));
    }

    public void broadcastCrossServerWithdraw(UUID uuid, String username, double deltaAmount) {
        publish(new CrossServerPacket(serverId, CrossServerPacket.Action.CROSS_SERVER_WITHDRAW, uuid, username, deltaAmount, null));
    }

    public void broadcastBalanceInvalidate(UUID uuid) {
        publish(new CrossServerPacket(serverId, CrossServerPacket.Action.BALANCE_INVALIDATE, uuid, null, 0.0, null));
    }

    public void broadcastCrossServerPay(UUID receiverUuid, String receiverName, double paymentAmount,
                                        double authoritativeBalance, String senderName) {
        publish(new CrossServerPacket(serverId, CrossServerPacket.Action.CROSS_SERVER_PAY, receiverUuid,
                receiverName, authoritativeBalance, paymentAmount, senderName));
    }

    public void broadcastBaltopInvalidate() {
        publish(new CrossServerPacket(serverId, CrossServerPacket.Action.BALTOP_INVALIDATE, null, null, 0.0, null));
    }

    public void broadcastGlobalReset() {
        publish(new CrossServerPacket(serverId, CrossServerPacket.Action.GLOBAL_RESET, null, null, 0.0, null));
    }

    public UnifiedJedis getRedisClient() {
        return redisClient;
    }

    public CrossServerListener getListener() {
        return listener;
    }

    public void shutdown() {
        this.enabled = false;

        if (heartbeatTask != null) {
            try {
                plugin.getScheduler().cancel(heartbeatTask);
            } catch (Throwable ignored) {}
            heartbeatTask = null;
        }

        // Remove this server's local players from Redis presence set
        if (redisClient != null && serverId != null) {
            try {
                String serverPlayersKey = "friendlycore:server_players:" + serverId;
                Set<String> localPlayers = redisClient.smembers(serverPlayersKey);
                if (localPlayers != null && !localPlayers.isEmpty()) {
                    redisClient.srem("friendlycore:online_players", localPlayers.toArray(new String[0]));
                }
                redisClient.del(serverPlayersKey);
                redisClient.del("friendlycore:server_heartbeat:" + serverId);
                redisClient.srem("friendlycore:known_servers", serverId);
            } catch (Throwable ignored) {}
        }

        if (listener != null && listener.isSubscribed()) {
            try {
                listener.punsubscribe();
            } catch (Throwable ignored) {}
            try {
                listener.unsubscribe();
            } catch (Throwable ignored) {}
        }
        if (subJedis != null) {
            try {
                subJedis.disconnect();
            } catch (Throwable ignored) {}
            try {
                subJedis.close();
            } catch (Throwable ignored) {}
            subJedis = null;
        }
        if (pubSubThread != null) {
            Thread thread = pubSubThread;
            pubSubThread = null;
            thread.interrupt();
            // Wait for old subscriber thread to terminate to prevent duplicate processing on reload
            for (int i = 0; i < 6 && thread.isAlive(); i++) {
                try {
                    thread.join(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (presenceExecutor != null) {
            presenceExecutor.shutdown();
            try {
                if (!presenceExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    presenceExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                presenceExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            presenceExecutor = null;
        }
        if (publishExecutor != null) {
            publishExecutor.shutdown();
            try {
                if (!publishExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    publishExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                publishExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            publishExecutor = null;
        }
        if (redisClient != null) {
            try {
                redisClient.close();
            } catch (Throwable ignored) {}
            redisClient = null;
        }

        networkOnlinePlayers.clear();
        networkOnlineUuids.clear();
        networkPlayerUuids.invalidateAll();
    }

    /**
     * Dedicated event listener ensuring Redis presence tracking is always active
     * even when the joinquit module is disabled in configuration.
     */
    private class RedisPlayerListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPlayerJoin(PlayerJoinEvent event) {
            RedisService.this.onPlayerJoin(event.getPlayer());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPlayerQuit(PlayerQuitEvent event) {
            RedisService.this.onPlayerQuit(event.getPlayer());
        }
    }
}
