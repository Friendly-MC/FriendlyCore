package dev.lyy.friendlycore.modules.rtp;

import com.google.gson.Gson;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.modules.economy.EconomyManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import redis.clients.jedis.params.SetParams;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Origin owns search/warmup; destination owns billing and landing. Redis owns authorization. */
public class RtpCrossServerService {
    private static final Gson GSON = new Gson();
    private static final String REQUEST_PREFIX = "friendlycore:rtp:request:";
    private static final String REFUND_PREFIX = "friendlycore:rtp:refunds:";
    private static final String CHANGE_REQUEST = """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], ARGV[2])
            if ARGV[3] == '1' then redis.call('EXPIRE', KEYS[1], 86400) end
            return 1
            """;

    record Transfer(UUID id, UUID playerUuid, String playerName, String originServer,
                    String targetServer, String worldName, double cost, boolean refundOnCancel,
                    int warmupSeconds, String phase, long expiresAt) {
        Transfer withPhase(String phase, long expiresAt) {
            return new Transfer(id, playerUuid, playerName, originServer, targetServer, worldName,
                    cost, refundOnCancel, warmupSeconds, phase, expiresAt);
        }

        boolean expired() { return System.currentTimeMillis() >= expiresAt; }
        boolean terminal() { return phase.equals("SUCCEEDED") || phase.equals("FAILED"); }
        boolean cancellable() {
            return phase.equals("SEARCHING") || phase.equals("READY") || phase.equals("TRANSFERRING");
        }
    }

    public record TeleportTicket(UUID playerUuid, String playerName, String worldName,
                                 double x, double y, double z, float yaw, float pitch,
                                 long timestamp, String originServer, UUID requestId, long expiresAt) {
        public TeleportTicket(UUID playerUuid, String playerName, String worldName,
                              double x, double y, double z, float yaw, float pitch, long timestamp,
                              String originServer) {
            this(playerUuid, playerName, worldName, x, y, z, yaw, pitch, timestamp,
                    originServer, null, timestamp + 60_000L);
        }

        public TeleportTicket(UUID playerUuid, String playerName, String worldName,
                              double x, double y, double z, float yaw, float pitch, long timestamp) {
            this(playerUuid, playerName, worldName, x, y, z, yaw, pitch, timestamp, "");
        }

        public Location toLocation() {
            World world = Bukkit.getWorld(worldName);
            return world == null ? null : new Location(world, x, y, z, yaw, pitch);
        }

        public boolean isExpired() { return System.currentTimeMillis() >= expiresAt; }
    }

    private record Completion(Transfer transfer, boolean success, boolean charged) {}

    private final FriendlyCore plugin;
    private final Map<UUID, TeleportTicket> arrivalTickets = new ConcurrentHashMap<>();
    private final Map<UUID, Transfer> pendingCrossServerRequests = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> dispatchedRequests = new ConcurrentHashMap<>();
    private final Map<UUID, Player> returnArrivals = new ConcurrentHashMap<>();
    private final Map<UUID, Object> activeSearchTasks = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Optional<Location>>> destinationSearches = new ConcurrentHashMap<>();
    private final Map<UUID, Completion> completions = new ConcurrentHashMap<>();
    private final Set<UUID> refundPlayers = ConcurrentHashMap.newKeySet();
    private final Queue<Transfer> arrivalQueue = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Long> queuedUntil = new ConcurrentHashMap<>();
    /** RTP_FAILED amount: the destination refused because it is at max-players. */
    static final double REASON_FULL = 1;
    private Object cleanupTask;
    private Object pacingTask;
    private volatile boolean enabled;
    private volatile boolean stopped;
    private List<String> targetServers = List.of();
    private volatile int arrivalsPerSecond;
    private volatile int maxPlayers;
    private volatile long requestTimeoutMillis = 15_000L;

    public RtpCrossServerService(FriendlyCore plugin) {
        this.plugin = plugin;
        reload();
        cleanupTask = plugin.getScheduler().runAsyncTimer(this::cleanup, 20L, 20L);
    }

    public void reload() {
        var config = plugin.getConfigService().getRtpConfig();
        enabled = config.getBoolean("cross-server.enabled", true);
        targetServers = List.copyOf(config.getStringList("cross-server.target-servers"));
        arrivalsPerSecond = Math.max(0, config.getInt("cross-server.arrivals-per-second", 0));
        maxPlayers = Math.max(0, config.getInt("cross-server.max-players", 0));
        requestTimeoutMillis = Math.max(5, config.getInt("cross-server.request-timeout-seconds", 15)) * 1000L;
        if (!enabled) destinationSearches.values().forEach(search -> search.cancel(false));
        if (pacingTask != null) plugin.getScheduler().cancel(pacingTask);
        pacingTask = null;
        if (arrivalsPerSecond > 0) {
            // Releasing a quarter-second's share every 5 ticks keeps the arrival wave smooth instead of bursty.
            pacingTask = plugin.getScheduler().runGlobalTimer(this::releaseQueued, 5L, 5L);
        } else if (!arrivalQueue.isEmpty()) {
            plugin.getScheduler().runGlobal(() -> drain(arrivalQueue, Integer.MAX_VALUE, this::search));
        }
    }

    private double releaseCredit; // global thread only

    private void releaseQueued() {
        releaseCredit = refill(releaseCredit, arrivalsPerSecond);
        releaseCredit -= drain(arrivalQueue, (int) releaseCredit, this::search);
    }

    /**
     * Adds one 5-tick share of {@code perSecond}. The leftover fraction is always below 1, so a cap of
     * share + 1 never trims it while draining, yet idle time can add at most one extra release.
     */
    static double refill(double credit, int perSecond) {
        double share = perSecond / 4.0;
        return Math.min(credit + share, share + 1);
    }

    /** Hands at most {@code budget} queued requests to {@code search}. */
    static <T> int drain(Queue<T> queue, int budget, Consumer<T> search) {
        int released = 0;
        for (T next; released < budget && (next = queue.poll()) != null; released++) search.accept(next);
        return released;
    }

    /** Seconds until a request joining behind {@code ahead} others is released. */
    static long waitSeconds(int ahead, int perSecond) {
        return perSecond <= 0 ? 0 : (long) Math.ceil((ahead + 1) / (double) perSecond);
    }

    static boolean atCapacity(int maxPlayers, int online, int queued, int inbound) {
        return maxPlayers > 0 && online + queued + inbound >= maxPlayers;
    }

    private void async(Runnable action) {
        plugin.getScheduler().runAsync(() -> {
            try {
                action.run();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "RTP Redis operation failed; authorization remains closed", e);
            }
        });
    }

    private Transfer read(UUID id) {
        String json = plugin.getRedisService().getRedisClient().get(REQUEST_PREFIX + id);
        return json == null ? null : GSON.fromJson(json, Transfer.class);
    }

    private boolean change(Transfer expected, Transfer replacement) {
        boolean retain = replacement.terminal() || replacement.cancellable() || replacement.phase().equals("ARRIVING");
        return Long.valueOf(1).equals(plugin.getRedisService().getRedisClient().eval(CHANGE_REQUEST,
                List.of(REQUEST_PREFIX + expected.id()),
                List.of(GSON.toJson(expected), GSON.toJson(replacement), retain ? "1" : "0")));
    }

    private void cleanup() {
        if (stopped || plugin.getRedisService() == null || !plugin.getRedisService().isEnabled()) return;
        for (var entry : destinationSearches.entrySet()) {
            try {
                Transfer request = read(entry.getKey());
                if (!enabled || request == null || request.expired() || !request.phase().equals("SEARCHING")) {
                    entry.getValue().cancel(false);
                }
            } catch (Exception e) {
                entry.getValue().cancel(false);
            }
        }
        for (Transfer pending : List.copyOf(pendingCrossServerRequests.values())) {
            try {
                Transfer current = read(pending.id());
                if (current == null) {
                    if (pending.expired()) finishOrigin(pending, true);
                    continue;
                }
                if ((current.expired() || !enabled) && current.cancellable()) {
                    Transfer failed = current.withPhase("FAILED", current.expiresAt());
                    if (change(current, failed)) current = failed;
                }
                if (current.terminal() || current.phase().equals("REFUND_PENDING") || current.phase().equals("REFUNDING")) {
                    finishOrigin(pending, !current.phase().equals("SUCCEEDED"));
                } else if (current.expired() && !current.cancellable()) {
                    // Never refund an uncertain landing/debit on a timeout. Its durable record is retained.
                    plugin.getLogger().severe("RTP arrival needs reconciliation: " + current.id() + " (" + current.phase() + ")");
                    finishOrigin(pending, true);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Unable to reconcile RTP request " + pending.id(), e);
            }
        }
        arrivalTickets.entrySet().removeIf(entry -> entry.getValue().isExpired());
        for (Completion completion : List.copyOf(completions.values())) {
            try {
                settle(completion);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "RTP outcome retained for retry: " + completion.transfer().id(), e);
            }
        }
        // Persistent refunds are collected by the current local economy session, never forwarded via Pub/Sub.
        for (UUID uuid : List.copyOf(refundPlayers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) refundPlayers.remove(uuid);
            else collectRefunds(player);
        }
    }

    public void requestCrossServerRtp(Player player, String targetServer, String targetWorld) {
        if (player == null || !player.isOnline()) return;
        if (!enabled || stopped || targetServer == null || targetServer.isBlank()
                || targetServer.contains("|") || plugin.getRedisService() == null
                || !plugin.getRedisService().isEnabled()) {
            fail(player);
            return;
        }
        var manager = plugin.getRtpManager();
        UUID uuid = player.getUniqueId();
        if (targetServer.equalsIgnoreCase(plugin.getRedisService().getServerId())) {
            RtpWorldConfig local = manager.getWorldConfig(targetWorld == null ? player.getWorld().getName() : targetWorld);
            if (local == null || !local.isEnabled()) fail(player);
            else manager.startRtp(player, targetWorld, false);
            return;
        }
        if (!manager.beginRequest(uuid)) {
            plugin.getTextService().sendActionBar(player,
                    plugin.getConfigService().getMessages().getString("rtp.already-teleporting",
                            "&#ff9100You are already preparing to teleport!"), null);
            return;
        }
        long cooldown = manager.getRemainingCooldownSeconds(uuid);
        if (!player.hasPermission("friendlycore.rtp.bypass.cooldown") && cooldown > 0) {
            manager.endRequest(uuid);
            plugin.getTextService().sendActionBar(player,
                    plugin.getConfigService().getMessages().getString("rtp.cooldown",
                            "&#ff9100Please wait %seconds%s before using RTP again."),
                    Map.of("%seconds%", String.valueOf(cooldown), "%second%", String.valueOf(cooldown),
                            "%time%", String.valueOf(cooldown), "%player%", player.getName()));
            return;
        }

        double cost = manager.isEconomyEnabled() && !player.hasPermission("friendlycore.rtp.bypass.cost")
                ? manager.getEconomyCost() : 0;
        var economy = plugin.getEconomyManager();
        var account = economy == null ? null : economy.getCachedAccount(uuid);
        if (!Double.isFinite(cost) || (cost > 0 && (account == null || account.getBalance() < cost))) {
            manager.endRequest(uuid);
            insufficientFunds(player, cost, account == null ? 0 : account.getBalance());
            return;
        }
        Transfer request = new Transfer(UUID.randomUUID(), uuid, player.getName(),
                plugin.getRedisService().getServerId(), targetServer, targetWorld, cost,
                manager.isRefundOnCancel(), manager.getWarmupSeconds(), "SEARCHING",
                System.currentTimeMillis() + requestTimeoutMillis);
        pendingCrossServerRequests.put(uuid, request);
        AtomicInteger tick = new AtomicInteger();
        Object spinner = plugin.getScheduler().runEntityTimer(player, () -> {
            Long until = queuedUntil.get(uuid);
            if (until == null) {
                manager.getActionbarAnimator().renderSearching(player, 1, 1, tick.incrementAndGet(),
                        targetWorld == null ? "Wilderness" : targetWorld);
                return;
            }
            String seconds = String.valueOf(Math.max(1, (until - System.currentTimeMillis() + 999) / 1000));
            plugin.getTextService().sendActionBar(player, plugin.getConfigService().getMessages().getString("rtp.actionbar-queued",
                    "&e⚡ &6&lRTP &8» &fIn queue for %server%, about %seconds%s"), Map.of("%server%", targetServer, "%seconds%", seconds));
        }, 1L, 2L);
        if (spinner != null) activeSearchTasks.put(uuid, spinner);
        async(() -> {
            String result = plugin.getRedisService().getRedisClient().set(REQUEST_PREFIX + request.id(),
                    GSON.toJson(request), SetParams.setParams().nx().ex(86400));
            if (!"OK".equals(result)) {
                finishOrigin(request, true);
                return;
            }
            if (stopped || pendingCrossServerRequests.get(uuid) != request) {
                change(request, request.withPhase("FAILED", request.expiresAt()));
                return;
            }
            publish(CrossServerPacket.Action.RTP_REQUEST, request, targetServer);
        });
    }

    private void publish(CrossServerPacket.Action action, Transfer request, String recipient) {
        publish(action, request, recipient, 0);
    }

    private void publish(CrossServerPacket.Action action, Transfer request, String recipient, double amount) {
        plugin.getRedisService().publish(new CrossServerPacket(plugin.getRedisService().getServerId(),
                action, request.playerUuid(), request.playerName(), amount, recipient + "|" + request.id()));
    }

    private UUID addressedId(CrossServerPacket packet) {
        if (packet.getExtra() == null || packet.getTargetUuid() == null) return null;
        String[] parts = packet.getExtra().split("\\|", -1);
        if (parts.length != 2 || !parts[0].equalsIgnoreCase(plugin.getRedisService().getServerId())) return null;
        try {
            return UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            return null; // Legacy or malformed packets cannot authorize billing or a transfer.
        }
    }

    public void handleIncomingRequest(CrossServerPacket packet) {
        UUID id = addressedId(packet);
        if (id == null) return;
        async(() -> {
            Transfer request = read(id);
            if (request == null || !request.playerUuid().equals(packet.getTargetUuid())
                    || !request.originServer().equalsIgnoreCase(packet.getSourceServer())
                    || !request.targetServer().equalsIgnoreCase(plugin.getRedisService().getServerId())
                    || !request.phase().equals("SEARCHING") || request.expired()) return;
            if (!enabled || stopped) {
                reject(request);
                return;
            }
            if (atCapacity(maxPlayers, Bukkit.getOnlinePlayers().size(), arrivalQueue.size(), arrivalTickets.size())) {
                reject(request, REASON_FULL);
                return;
            }
            if (arrivalsPerSecond <= 0) {
                plugin.getScheduler().runGlobal(() -> search(request));
                return;
            }
            long wait = waitSeconds(arrivalQueue.size(), arrivalsPerSecond);
            arrivalQueue.add(request);
            // One packet per queued player, only when the wait is long enough to notice.
            if (wait >= 2) publish(CrossServerPacket.Action.RTP_QUEUED, request, request.originServer(), wait);
        });
    }

    private void search(Transfer request) {
        if (!enabled || stopped || request.expired()) {
            async(() -> reject(request));
            return;
        }
        World world = request.worldName() == null ? Bukkit.getWorlds().stream()
                .filter(w -> plugin.getRtpManager().getWorldConfig(w.getName()) != null
                        && plugin.getRtpManager().getWorldConfig(w.getName()).isEnabled()).findFirst()
                .orElse(null)
                : Bukkit.getWorld(request.worldName());
        RtpWorldConfig config = world == null ? null : plugin.getRtpManager().getWorldConfig(world.getName());
        if (config == null || !config.isEnabled()) {
            async(() -> reject(request));
            return;
        }
        Optional<Location> cached = plugin.getRtpManager().getLocationCache().poll(world, request.playerUuid());
        if (cached.isPresent()) {
            async(() -> sendLocationReply(request, cached.get()));
            return;
        }
        int attempts = plugin.getConfigService().getRtpConfig()
                .getInt("settings.max-attempts." + config.getWorldType().name().toLowerCase(java.util.Locale.ROOT), 25);
        if (destinationSearches.containsKey(request.id())) return;
        CompletableFuture<Optional<Location>> search = plugin.getRtpManager().getLocationFinder()
                .findSafeLocation(world, config, attempts, null, request.playerUuid());
        destinationSearches.put(request.id(), search);
        search.whenComplete((location, error) -> {
            destinationSearches.remove(request.id(), search);
            async(() -> {
                if (error == null && location != null && location.isPresent()) {
                    sendLocationReply(request, location.get());
                } else {
                    reject(request);
                }
            });
        });
    }

    private void sendLocationReply(Transfer request, Location location) {
        if (!enabled || stopped || request.expired() || !request.equals(read(request.id()))) {
            reject(request);
            return;
        }
        long now = System.currentTimeMillis();
        // Native teleportAsync loads the destination; no plugin chunk leases to leak or reference-count.
        arrivalTickets.put(request.playerUuid(), new TeleportTicket(request.playerUuid(), request.playerName(),
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(), now, request.originServer(), request.id(),
                now + request.warmupSeconds() * 1000L + 65_000L));
        publish(CrossServerPacket.Action.RTP_LOCATION_REPLY, request, request.originServer());
    }

    private void reject(Transfer request) {
        reject(request, 0);
    }

    private void reject(Transfer request, double reason) {
        if (change(request, request.withPhase("FAILED", request.expiresAt()))) {
            publish(CrossServerPacket.Action.RTP_FAILED, request, request.originServer(), reason);
        }
    }

    public void handleIncomingQueued(CrossServerPacket packet) {
        Transfer pending = matchingPending(packet);
        if (pending == null || !Double.isFinite(packet.getAmount()) || packet.getAmount() <= 0) return;
        queuedUntil.put(pending.playerUuid(), System.currentTimeMillis() + (long) (Math.min(packet.getAmount(), 600) * 1000));
    }

    public void handleIncomingReply(CrossServerPacket packet) {
        Transfer pending = matchingPending(packet);
        if (pending == null) return;
        async(() -> {
            Transfer current = read(pending.id());
            if (current == null || !current.phase().equals("SEARCHING") || current.expired()
                    || pendingCrossServerRequests.get(pending.playerUuid()) != pending) return;
            Transfer ready = current.withPhase("READY",
                    System.currentTimeMillis() + current.warmupSeconds() * 1000L + 65_000L);
            if (!change(current, ready)) return;
            stopSearching(pending.playerUuid());
            Player player = Bukkit.getPlayer(pending.playerUuid());
            if (player == null) {
                change(ready, ready.withPhase("FAILED", ready.expiresAt()));
                return;
            }
            plugin.getScheduler().runEntity(player, () -> {
                if (pendingCrossServerRequests.get(pending.playerUuid()) == pending && player.isOnline()) {
                    plugin.getRtpManager().startCrossServerWarmup(player, pending.targetServer());
                }
            }, () -> async(() -> change(ready, ready.withPhase("FAILED", ready.expiresAt()))));
        });
    }

    private Transfer matchingPending(CrossServerPacket packet) {
        UUID id = addressedId(packet);
        if (id == null || packet.getSourceServer() == null) return null;
        Transfer pending = pendingCrossServerRequests.get(packet.getTargetUuid());
        return pending != null && pending.id().equals(id)
                && pending.targetServer().equalsIgnoreCase(packet.getSourceServer()) ? pending : null;
    }

    public void handleIncomingFailure(CrossServerPacket packet) {
        Transfer pending = matchingPending(packet);
        if (pending == null) return;
        boolean full = packet.getAmount() == REASON_FULL;
        async(() -> {
            Transfer current = read(pending.id());
            if (current == null || !current.phase().equals("FAILED")) return;
            finishOrigin(pending, !full);
            Player player = full ? Bukkit.getPlayer(pending.playerUuid()) : null;
            if (player != null) plugin.getScheduler().runEntity(player, () -> {
                if (!player.isOnline()) return;
                plugin.getRtpManager().applyCancelCooldown(player.getUniqueId());
                plugin.getTextService().sendActionBar(player, plugin.getConfigService().getMessages().getString("rtp.actionbar-server-full",
                        "&c✘ &4&lRTP &8» &f%server% is full right now, try again soon"), Map.of("%server%", pending.targetServer()));
            });
        });
    }

    public void sendPlayerToProxyServer(Player player, String serverName) {
        if (player == null || !player.isOnline()) return;
        Transfer pending = pendingCrossServerRequests.get(player.getUniqueId());
        if (pending == null || !pending.targetServer().equalsIgnoreCase(serverName)) return;
        async(() -> {
            Transfer ready = read(pending.id());
            if (!enabled || stopped || ready == null || !ready.phase().equals("READY") || ready.expired()) return;
            Transfer transferring = ready.withPhase("TRANSFERRING", System.currentTimeMillis() + 60_000L);
            if (!change(ready, transferring)) return;
            plugin.getScheduler().runEntity(player, () -> {
                if (!player.isOnline()) {
                    async(() -> change(transferring, transferring.withPhase("FAILED", transferring.expiresAt())));
                    return;
                }
                try {
                    if (plugin.getSpawnManager() != null && plugin.getSpawnManager().getLastLocationService() != null) {
                        plugin.getSpawnManager().getLastLocationService().finishRouting(player);
                    }
                    if (plugin.getTransferFreezeService() != null) plugin.getTransferFreezeService().freeze(player);
                    var economy = plugin.getEconomyManager();
                    var saved = economy == null ? java.util.concurrent.CompletableFuture.<Void>completedFuture(null)
                            : economy.savePlayerAccount(player.getUniqueId());
                    saved.whenComplete((ignored, error) -> async(() -> {
                        if (error != null) {
                            change(transferring, transferring.withPhase("FAILED", transferring.expiresAt()));
                            return;
                        }
                        if (!transferring.equals(read(transferring.id())) || transferring.expired()) return;
                        dev.lyy.friendlycore.common.ProxyTransferUtil.connect(plugin, player, serverName, transferring.id())
                                .thenAccept(sent -> {
                                    if (sent) {
                                        if (pendingCrossServerRequests.get(transferring.playerUuid()) == pending)
                                            dispatchedRequests.put(transferring.playerUuid(), transferring.id());
                                    } else async(() -> change(transferring,
                                            transferring.withPhase("FAILED", transferring.expiresAt())));
                                });
                    }));
                } catch (Exception e) {
                    if (plugin.getTransferFreezeService() != null) plugin.getTransferFreezeService().unfreeze(player);
                    async(() -> change(transferring, transferring.withPhase("FAILED", transferring.expiresAt())));
                    plugin.getLogger().log(Level.WARNING, "RTP proxy handoff failed", e);
                }
            }, () -> async(() -> change(transferring, transferring.withPhase("FAILED", transferring.expiresAt()))));
        });
    }

    public TeleportTicket getTicket(UUID playerUuid) {
        if (playerUuid == null || !enabled || stopped) return null;
        TeleportTicket ticket = arrivalTickets.get(playerUuid);
        if (ticket == null || ticket.isExpired()) return null;
        try {
            Transfer request = read(ticket.requestId());
            return request != null && request.phase().equals("TRANSFERRING") && !request.expired() ? ticket : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void handleArrival(Player player) {
        if (!player.isOnline() || stopped) return;
        Transfer outgoing = pendingCrossServerRequests.get(player.getUniqueId());
        if (outgoing != null && outgoing.id().equals(dispatchedRequests.get(player.getUniqueId()))) {
            // This source instance issued the previous handoff. Keep last-location forwarding
            // from sending the returning player straight back to the unavailable destination.
            returnArrivals.put(player.getUniqueId(), player);
            async(() -> {
                Transfer current = read(outgoing.id());
                if (current != null && current.phase().equals("TRANSFERRING")
                        && change(current, current.withPhase("FAILED", current.expiresAt()))) finishOrigin(outgoing, true);
            });
            return;
        }
        TeleportTicket pending = arrivalTickets.get(player.getUniqueId());
        if (pending != null && !pending.isExpired() && plugin.getHookManager() != null
                && plugin.getHookManager().getMcDataBridgeHook().isPlayerLocked(player)) {
            // Keep the ticket unclaimed until inventory/location synchronization finishes.
            plugin.getScheduler().runEntityLater(player, () -> handleArrival(player), 1L);
            return;
        }
        TeleportTicket ticket = arrivalTickets.remove(player.getUniqueId());
        collectRefunds(player);
        if (ticket == null || ticket.isExpired()) return;
        async(() -> {
            Transfer transferring = read(ticket.requestId());
            if (transferring == null || !transferring.phase().equals("TRANSFERRING")) return;
            if (!enabled || stopped || transferring.expired()) {
                change(transferring, transferring.withPhase("FAILED", transferring.expiresAt()));
                return;
            }
            Transfer arriving = transferring.withPhase("ARRIVING", System.currentTimeMillis() + 60_000L);
            if (!change(transferring, arriving)) return;
            EconomyManager economy = plugin.getEconomyManager();
            if (arriving.cost() > 0 && economy == null) {
                reject(arriving);
                return;
            }
            if (arriving.cost() > 0) {
                economy.loadPlayerAccount(arriving.playerUuid(), arriving.playerName()).whenComplete((account, error) -> {
                    if (error != null || account == null) async(() -> reject(arriving));
                    else async(() -> prepareLanding(player, ticket, arriving));
                });
            } else {
                prepareLanding(player, ticket, arriving);
            }
        });
    }

    private void prepareLanding(Player player, TeleportTicket ticket, Transfer arriving) {
        if (!enabled || stopped || arriving.expired()) {
            reject(arriving);
            return;
        }
        Transfer landing = arriving.withPhase("CHARGING", arriving.expiresAt());
        if (!change(arriving, landing)) return;
        plugin.getScheduler().runEntity(player, () -> {
            var manager = plugin.getRtpManager();
            if (!enabled || stopped || !player.isOnline() || !manager.beginRequest(player.getUniqueId())) {
                complete(landing, false, false);
                return;
            }
            boolean charged = false;
            if (landing.cost() > 0) {
                var economy = plugin.getEconomyManager();
                // Billing is local and atomic; it must never use EconomyManager's remote-forwarding branch.
                charged = economy.isOnlineLocally(player.getUniqueId())
                        && economy.withdraw(player.getUniqueId(), landing.cost(), true);
                if (!charged) {
                    manager.endRequest(player.getUniqueId());
                    insufficientFunds(player, landing.cost(), economy.getCachedAccount(player.getUniqueId()) == null ? 0 : economy.getCachedAccount(player.getUniqueId()).getBalance());
                    complete(landing, false, false);
                    return;
                }
            }
            boolean actualCharge = charged;
            manager.executeTeleportAsync(player, ticket.toLocation()).whenComplete((success, error) -> {
                manager.endRequest(player.getUniqueId());
                complete(landing, error == null && Boolean.TRUE.equals(success), actualCharge);
            });
        }, () -> complete(landing, false, false));
    }

    private void complete(Transfer transfer, boolean success, boolean charged) {
        Completion completion = new Completion(transfer, success, charged);
        if (completions.putIfAbsent(transfer.id(), completion) == null) async(() -> settle(completion));
    }

    private void settle(Completion completion) {
        Transfer transfer = completion.transfer();
        Transfer outcome = transfer.withPhase(completion.success() ? "SUCCEEDED"
                : completion.charged() && transfer.refundOnCancel() ? "REFUND_PENDING" : "FAILED", transfer.expiresAt());
        if (outcome.phase().equals("REFUND_PENDING")) {
            plugin.getRedisService().getRedisClient().sadd(REFUND_PREFIX + transfer.playerUuid(), transfer.id().toString());
        }
        if (change(transfer, outcome) || !transfer.equals(read(transfer.id()))) {
            completions.remove(transfer.id(), completion);
            if (outcome.phase().equals("REFUND_PENDING")) {
                refundPlayers.add(transfer.playerUuid());
                Player player = Bukkit.getPlayer(transfer.playerUuid());
                if (player != null) collectRefunds(player);
            }
        }
    }

    public void collectRefunds(Player player) {
        if (stopped || plugin.getRedisService() == null || !plugin.getRedisService().isEnabled()) return;
        EconomyManager economy = plugin.getEconomyManager();
        if (economy == null || !economy.isOnlineLocally(player.getUniqueId())) return;
        UUID uuid = player.getUniqueId();
        refundPlayers.add(uuid);
        String playerName = player.getName();
        async(() -> {
            Set<String> refunds = plugin.getRedisService().getRedisClient().smembers(REFUND_PREFIX + uuid);
            if (refunds.isEmpty()) refundPlayers.remove(uuid);
            for (String id : refunds) {
                Transfer pending = read(UUID.fromString(id));
                if (pending == null || pending.terminal()) {
                    plugin.getRedisService().getRedisClient().srem(REFUND_PREFIX + uuid, id);
                    continue;
                }
                if (!pending.playerUuid().equals(uuid) || !pending.phase().equals("REFUND_PENDING")) continue;
                economy.loadPlayerAccount(uuid, playerName).whenComplete((account, error) -> {
                    if (error != null || account == null) return;
                    async(() -> {
                        Transfer paying = pending.withPhase("REFUNDING", pending.expiresAt());
                        if (!change(pending, paying)) return;
                        plugin.getScheduler().runEntity(player, () -> {
                            if (!player.isOnline() || !economy.isOnlineLocally(uuid)) {
                                async(() -> change(paying, pending));
                                return;
                            }
                            if (economy.deposit(uuid, pending.cost(), true)) {
                                Completion done = new Completion(paying, false, false);
                                completions.put(paying.id(), done);
                                async(() -> settle(done));
                            } else {
                                // ponytail: ambiguous economy failures stay REFUNDING; reconcile the retained
                                // request manually, or add a transactional economy ledger before automatic retry.
                                plugin.getLogger().severe("RTP refund needs reconciliation: " + pending.id());
                            }
                        }, () -> async(() -> change(paying, pending)));
                    });
                });
            }
        });
    }

    public void cancelPendingRequest(UUID uuid) {
        Transfer pending = pendingCrossServerRequests.get(uuid);
        if (pending == null) return;
        async(() -> {
            Transfer current = read(pending.id());
            if (current != null && (current.phase().equals("SEARCHING") || current.phase().equals("READY"))) {
                if (!change(current, current.withPhase("FAILED", current.expiresAt()))) return;
            } else if (current != null && !current.terminal()) {
                return; // The origin's normal proxy quit must not cancel an authorized handoff.
            }
            finishOrigin(pending, false);
        });
    }

    private void finishOrigin(Transfer pending, boolean failed) {
        UUID uuid = pending.playerUuid();
        if (!pendingCrossServerRequests.remove(uuid, pending)) return;
        dispatchedRequests.remove(uuid, pending.id());
        stopSearching(uuid);
        plugin.getRtpManager().cancelWarmupTask(uuid);
        plugin.getRtpManager().endRequest(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) plugin.getScheduler().runEntity(player, () -> {
            if (failed && player.isOnline()) fail(player);
            if (plugin.getTransferFreezeService() != null) plugin.getTransferFreezeService().unfreeze(player);
        });
    }

    private void stopSearching(UUID uuid) {
        queuedUntil.remove(uuid);
        Object task = activeSearchTasks.remove(uuid);
        if (task != null) plugin.getScheduler().cancel(task);
    }

    private void fail(Player player) {
        plugin.getRtpManager().applyCancelCooldown(player.getUniqueId());
        plugin.getRtpManager().getActionbarAnimator().renderFailed(player);
    }

    private void insufficientFunds(Player player, double cost, double balance) {
        plugin.getTextService().sendActionBar(player,
                plugin.getConfigService().getMessages().getString("rtp.economy-not-enough",
                        "&#ff3845You need $%cost% to use RTP! Current balance: $%balance%"),
                Map.of("%cost%", plugin.getNumberFormatter().format(cost),
                        "%balance%", plugin.getNumberFormatter().format(balance)));
    }

    public void broadcastCooldown(UUID uuid, long cooldownSeconds) {
        if (uuid == null || cooldownSeconds <= 0 || plugin.getRedisService() == null || !plugin.getRedisService().isEnabled()) return;
        long expiresAt = System.currentTimeMillis() + cooldownSeconds * 1000L;
        plugin.getRedisService().publish(new CrossServerPacket(plugin.getRedisService().getServerId(),
                CrossServerPacket.Action.RTP_COOLDOWN_SYNC, uuid, null, expiresAt, "expiry"));
    }

    public boolean hasPendingRequest(UUID uuid) { return uuid != null && pendingCrossServerRequests.containsKey(uuid); }
    public boolean isReturnArrival(UUID uuid) { return returnArrivals.containsKey(uuid); }
    public void clearReturnArrival(Player player) {
        returnArrivals.computeIfPresent(player.getUniqueId(), (uuid, current) -> current == player ? null : current);
    }
    public List<String> getTargetServers() { return targetServers; }
    public boolean isEnabled() { return enabled; }

    public void shutdown() {
        stopped = true;
        dispatchedRequests.clear();
        returnArrivals.clear();
        destinationSearches.values().forEach(search -> search.cancel(false));
        if (cleanupTask != null) plugin.getScheduler().cancel(cleanupTask);
        if (pacingTask != null) plugin.getScheduler().cancel(pacingTask);
        for (Transfer queued; (queued = arrivalQueue.poll()) != null; ) {
            try {
                reject(queued);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Unable to reject queued RTP request on shutdown", e);
            }
        }
        for (Transfer pending : List.copyOf(pendingCrossServerRequests.values())) {
            try {
                Transfer current = read(pending.id());
                if (current != null && current.cancellable()) change(current, current.withPhase("FAILED", current.expiresAt()));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Unable to cancel RTP request on shutdown", e);
            }
            stopSearching(pending.playerUuid());
            plugin.getRtpManager().endRequest(pending.playerUuid());
        }
        for (Completion completion : List.copyOf(completions.values())) {
            try {
                settle(completion);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "RTP payment outcome needs reconciliation: " + completion.transfer().id(), e);
            }
        }
        arrivalTickets.clear();
        pendingCrossServerRequests.clear();
    }
}
