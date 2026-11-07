package dev.lyy.friendlycore.modules.rtp;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.spawn.LastLocationPolicy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinates RTP random teleportation workflows, warmup timers, cooldown persistence,
 * economy transactions, spatial spreading, and location cache replenishment.
 */
public class RtpManager {

    /**
     * One-shot state for a warmup timer. A scheduler may deliver a final
     * callback while cancellation is racing, so completion cannot rely on the
     * task handle alone.
     */
    static final class RtpWarmupSession {
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private volatile Object task;

        boolean tryComplete() {
            return terminal.compareAndSet(false, true);
        }

        void cancel() {
            terminal.set(true);
        }

        boolean isTerminal() {
            return terminal.get();
        }

        Object task() {
            return task;
        }

        void attachTask(Object task) {
            this.task = task;
        }
    }

    private final FriendlyCore plugin;
    private final Map<String, RtpWorldConfig> worldConfigs = new ConcurrentHashMap<>();

    private RtpSpreadingManager spreadingManager;
    private RtpSafetyValidator safetyValidator;
    private RtpLocationFinder locationFinder;
    private RtpLocationCache locationCache;
    private RtpActionbarAnimator actionbarAnimator;
    private RtpCrossServerService crossServerService;

    private final Map<UUID, RtpWarmupSession> activeWarmupTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> warmupStartTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Object> activeSearchTasks = new ConcurrentHashMap<>();
    private final Map<UUID, RtpRequestState> activeRequests = new ConcurrentHashMap<>();
    private final Set<UUID> requestPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Location> warmupStartLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> invulnerablePlayers = new ConcurrentHashMap<>();

    private int warmupSeconds;
    private int cooldownSeconds;
    private int cancelCooldownSeconds;
    private boolean cancelOnMove;
    private double moveThreshold;
    private boolean cancelOnDamage;
    private int invulnerabilitySeconds;
    private boolean smoothTransition;
    private int transitionBlindnessTicks;

    private boolean economyEnabled;
    private double economyCost;
    private boolean refundOnCancel;
    private volatile boolean localEnabled = true;

    private static final class RtpRequestState {
        private final AtomicBoolean charged = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private volatile Location targetLoc;
        private volatile boolean teleporting;
        private volatile CompletableFuture<Optional<Location>> search;
        private final double cost;
        private final boolean refundable;

        private RtpRequestState(double cost, boolean refundable) {
            this.cost = cost;
            this.refundable = refundable;
        }
    }

    public RtpManager(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        this.spreadingManager = new RtpSpreadingManager(plugin);
        this.safetyValidator = new RtpSafetyValidator(plugin);
        this.locationFinder = new RtpLocationFinder(plugin, safetyValidator, spreadingManager);
        this.locationCache = new RtpLocationCache(plugin, locationFinder);
        this.actionbarAnimator = new RtpActionbarAnimator(plugin);
        this.crossServerService = new RtpCrossServerService(plugin);

        reload();
    }

    public void reload() {
        for (UUID uuid : List.copyOf(activeRequests.keySet())) {
            RtpRequestState request = activeRequests.get(uuid);
            if (request == null || request.teleporting) continue;
            cancelWarmupTask(uuid);
            finishRequest(uuid, true);
        }
        if (locationFinder != null) locationFinder.cancelAll();
        FileConfiguration config = plugin.getConfigService().getRtpConfig();
        boolean hub = plugin.getRedisService() != null && plugin.getSpawnManager() != null
                && LastLocationPolicy.isHubServer(plugin.getRedisService().getServerId(),
                plugin.getSpawnManager().getSpawnData(),
                plugin.getConfigService().getSpawnConfig().getStringList("join-spawn.last-location.hub-servers"));
        this.localEnabled = config.getBoolean("settings.local-enabled", !hub);
        this.warmupSeconds = Math.max(0, config.getInt("settings.warmup-seconds", 3));
        this.cooldownSeconds = Math.max(0, config.getInt("settings.cooldown-seconds", 60));
        this.cancelCooldownSeconds = Math.max(0, config.getInt("settings.cancel-cooldown-seconds", 3));
        this.cancelOnMove = config.getBoolean("settings.cancel-on-move", true);
        this.moveThreshold = config.getDouble("settings.move-threshold", 0.5);
        this.cancelOnDamage = config.getBoolean("settings.cancel-on-damage", true);
        this.invulnerabilitySeconds = Math.max(0, config.getInt("settings.invulnerability-seconds", 5));
        this.smoothTransition = config.getBoolean("settings.smooth-transition", true);
        this.transitionBlindnessTicks = Math.max(10, config.getInt("settings.transition-blindness-duration-ticks", 20));

        this.economyEnabled = config.getBoolean("settings.economy.enabled", false);
        this.economyCost = Math.max(0.0, config.getDouble("settings.economy.cost", 50.0));
        this.refundOnCancel = config.getBoolean("settings.economy.refund-on-cancel", true);

        if (spreadingManager != null) spreadingManager.reload();
        loadWorldConfigs(config);

        if (safetyValidator != null) safetyValidator.reload();
        if (actionbarAnimator != null) actionbarAnimator.reload();
        if (crossServerService != null) crossServerService.reload();
        if (locationCache != null) locationCache.initialize();
    }

    private void loadWorldConfigs(FileConfiguration config) {
        worldConfigs.clear();

        String defShapeStr = config.getString("settings.default-shape", "CIRCLE");
        RtpWorldConfig.Shape defShape = "SQUARE".equalsIgnoreCase(defShapeStr) ? RtpWorldConfig.Shape.SQUARE : RtpWorldConfig.Shape.CIRCLE;
        int defMinR = config.getInt("settings.default-min-radius", 100);
        int defMaxR = config.getInt("settings.default-max-radius", 5000);
        int defCenterX = config.getInt("settings.default-center-x", 0);
        int defCenterZ = config.getInt("settings.default-center-z", 0);
        String globalAlgoStr = config.getString("spreading.distribution-algorithm", "SECTOR_ROUND_ROBIN");

        ConfigurationSection worldsSec = config.getConfigurationSection("worlds");
        if (worldsSec != null) {
            for (String worldName : worldsSec.getKeys(false)) {
                ConfigurationSection wSec = worldsSec.getConfigurationSection(worldName);
                if (wSec == null) continue;

                boolean enabled = wSec.getBoolean("enabled", true);
                String typeStr = wSec.getString("world-type", "NORMAL");
                RtpWorldConfig.WorldType type;
                try {
                    type = RtpWorldConfig.WorldType.valueOf(typeStr.toUpperCase());
                } catch (Exception e) {
                    World bWorld = Bukkit.getWorld(worldName);
                    type = RtpWorldConfig.detectWorldType(bWorld);
                }

                String shapeStr = wSec.getString("shape", defShapeStr);
                RtpWorldConfig.Shape shape = "SQUARE".equalsIgnoreCase(shapeStr) ? RtpWorldConfig.Shape.SQUARE : RtpWorldConfig.Shape.CIRCLE;
                int minR = wSec.getInt("min-radius", defMinR);
                int maxR = wSec.getInt("max-radius", defMaxR);
                int cX = wSec.getInt("center-x", defCenterX);
                int cZ = wSec.getInt("center-z", defCenterZ);
                var blackBiomes = wSec.getStringList("blacklisted-biomes");
                var whiteBiomes = wSec.getStringList("whitelisted-biomes");
                String worldAlgoStr = wSec.getString("distribution-algorithm", globalAlgoStr);
                RtpSpreadingManager.DistributionAlgorithm worldAlgo = RtpSpreadingManager.DistributionAlgorithm.fromString(worldAlgoStr);

                Set<Material> allowedGround = EnumSet.noneOf(Material.class);
                for (String name : wSec.getStringList("allowed-ground")) {
                    Material material = Material.matchMaterial(name);
                    if (material != null) allowedGround.add(material);
                    else plugin.getLogger().warning("Unknown RTP allowed-ground block " + name + " in world " + worldName);
                }
                if (allowedGround.isEmpty()) allowedGround.add(Material.END_STONE);

                worldConfigs.put(worldName, new RtpWorldConfig(worldName, enabled, type, shape, minR, maxR, cX, cZ, blackBiomes,
                        whiteBiomes, worldAlgo, wSec.getBoolean("landing-pool", false), allowedGround));
            }
        }

    }

    /**
     * Entry point to trigger RTP for a player with player spreading and safety validation.
     */
    public void startRtp(Player player, String worldName, boolean bypassWarmup) {
        if (player == null || !player.isOnline()) return;

        UUID uuid = player.getUniqueId();
        RtpRequestState request = new RtpRequestState(economyCost, refundOnCancel);
        if (!beginRequest(uuid)) {
            plugin.getTextService().sendActionBar(player,
                    plugin.getConfigService().getMessages().getString("rtp.already-teleporting",
                            "&#ff9100You are already preparing to teleport!"), null);
            return;
        }
        activeRequests.put(uuid, request);

        World world = (worldName != null && !worldName.isEmpty()) ? Bukkit.getWorld(worldName) : player.getWorld();
        if (world == null) {
            plugin.getTextService().sendActionBar(player,
                    plugin.getConfigService().getMessages().getString("rtp.invalid-world",
                            "&#ff3845World %world% does not exist or is not loaded!"),
                    Map.of("%world%", worldName != null ? worldName : "unknown"));
            finishRequest(uuid, false);
            return;
        }

        RtpWorldConfig wConfig = getWorldConfig(world.getName());
        if (wConfig == null || !wConfig.isEnabled()) {
            if (worldName == null && crossServerService != null && crossServerService.isEnabled()) {
                String targetServer = crossServerService.getTargetServers().stream()
                        .filter(server -> plugin.getRedisService() == null
                                || !server.equalsIgnoreCase(plugin.getRedisService().getServerId()))
                        .findFirst().orElse(null);
                if (targetServer != null) {
                    finishRequest(uuid, false);
                    crossServerService.requestCrossServerRtp(player, targetServer, null);
                    return;
                }
            }

            plugin.getTextService().sendActionBar(player,
                    plugin.getConfigService().getMessages().getString("rtp.world-disabled",
                            "&#ff3845RTP is disabled in world %world%!"),
                    Map.of("%world%", world.getName()));
            finishRequest(uuid, false);
            return;
        }

        if (!bypassWarmup && !player.hasPermission("friendlycore.rtp.bypass.cooldown")) {
            if (plugin.getCooldownManager().isOnCooldown("rtp", player.getUniqueId(), cooldownSeconds * 1000L)) {
                long remaining = plugin.getCooldownManager().getRemainingSeconds("rtp", player.getUniqueId(), cooldownSeconds * 1000L);
                plugin.getTextService().sendActionBar(player,
                        plugin.getConfigService().getMessages().getString("rtp.cooldown",
                                "&#ff9100Please wait %seconds%s before using RTP again."),
                        Map.of("%seconds%", String.valueOf(remaining),
                                "%second%", String.valueOf(remaining),
                                "%time%", String.valueOf(remaining),
                                "%player%", player.getName()));
                finishRequest(uuid, false);
                return;
            }
            if (cancelCooldownSeconds > 0 && plugin.getCooldownManager().isOnCooldown("rtp_cancel", player.getUniqueId(), cancelCooldownSeconds * 1000L)) {
                long remaining = plugin.getCooldownManager().getRemainingSeconds("rtp_cancel", player.getUniqueId(), cancelCooldownSeconds * 1000L);
                plugin.getTextService().sendActionBar(player,
                        plugin.getConfigService().getMessages().getString("rtp.cooldown",
                                "&#ff9100Please wait %seconds%s before using RTP again."),
                        Map.of("%seconds%", String.valueOf(remaining),
                                "%second%", String.valueOf(remaining),
                                "%time%", String.valueOf(remaining),
                                "%player%", player.getName()));
                finishRequest(uuid, false);
                return;
            }
        }

        if (economyEnabled && request.cost > 0.0 && !player.hasPermission("friendlycore.rtp.bypass.cost")) {
            if (plugin.getEconomyManager() != null) {
                var account = plugin.getEconomyManager().getCachedAccount(player.getUniqueId());
                double bal = account != null ? account.getBalance() : 0.0;
                if (bal < request.cost) {
                    plugin.getTextService().sendActionBar(player,
                            plugin.getConfigService().getMessages().getString("rtp.economy-not-enough",
                                    "&#ff3845You need $%cost% to use RTP! Current balance: $%balance%"),
                            Map.of("%cost%", plugin.getNumberFormatter().format(request.cost),
                                    "%balance%", plugin.getNumberFormatter().format(bal)));
                    finishRequest(uuid, false);
                    return;
                }
                if (!plugin.getEconomyManager().withdraw(uuid, request.cost, true)) {
                    finishRequest(uuid, false);
                    plugin.getTextService().sendActionBar(player,
                            plugin.getConfigService().getMessages().getString("rtp.economy-not-enough",
                                    "&#ff3845You need $%cost% to use RTP! Current balance: $%balance%"),
                            Map.of("%cost%", plugin.getNumberFormatter().format(request.cost),
                                    "%balance%", plugin.getNumberFormatter().format(account != null ? account.getBalance() : 0.0)));
                    return;
                }
                request.charged.set(true);
            }
        }

        // Check pre-generation queue for immediate 0ms dispatch
        Optional<Location> cachedLoc = locationCache.poll(world, uuid);
        if (cachedLoc.isPresent()) {
            Location targetLoc = cachedLoc.get();
            request.targetLoc = targetLoc;
            startWarmupOrExecute(player, targetLoc, bypassWarmup);
            return;
        }

        // Cache miss: initiate live search with animated status indicators
        int maxAttempts = plugin.getConfigService().getRtpConfig().getInt("settings.max-attempts." + wConfig.getWorldType().name().toLowerCase(), 25);
        AtomicInteger currentAttempt = new AtomicInteger(1);
        AtomicInteger spinnerTick = new AtomicInteger(0);

        Object searchTask = plugin.getScheduler().runEntityTimer(player, () -> {
            int tick = spinnerTick.incrementAndGet();
            actionbarAnimator.renderSearching(player, currentAttempt.get(), maxAttempts, tick, world.getName());
        }, 0L, 2L);
        if (searchTask == null) {
            finishRequest(uuid, true);
            return;
        }
        activeSearchTasks.put(uuid, searchTask);

        request.search = locationFinder.findSafeLocation(world, wConfig, maxAttempts, currentAttempt::set, uuid);
        request.search.whenComplete((locOpt, ex) -> {
            plugin.getScheduler().runEntity(player, () -> {
                if (activeRequests.get(uuid) != request) return;
                Object registeredTask = activeSearchTasks.remove(uuid);
                if (registeredTask != null) plugin.getScheduler().cancel(registeredTask);

                if (locOpt != null && locOpt.isPresent() && player.isOnline()) {
                    Location safeLoc = locOpt.get();
                    request.targetLoc = safeLoc;
                    startWarmupOrExecute(player, safeLoc, bypassWarmup);
                } else if (player.isOnline()) {
                    finishRequest(uuid, true);
                    applyCancelCooldown(uuid);
                    actionbarAnimator.renderFailed(player);
                } else {
                    finishRequest(uuid, true);
                }
            }, () -> {
                finishRequest(uuid, request, true, true);
            });
        });
    }

    /**
     * Spreads a group of players across the target world with guaranteed spatial separation.
     */
    public void spreadPlayers(CommandSender sender, List<Player> players, String worldName, int customRadius) {
        if (players == null || players.isEmpty()) {
            if (sender != null) Messages.send(plugin, sender, "rtp", "no-players-specified-to-spread");
            return;
        }

        World world = null;
        if (worldName != null && !worldName.isBlank()) {
            world = Bukkit.getWorld(worldName);
        } else if (sender instanceof Player p) {
            world = p.getWorld();
        } else if (!players.isEmpty() && players.get(0).isOnline()) {
            world = players.get(0).getWorld();
        }

        if (world == null) {
            if (sender != null) Messages.send(plugin, sender, "rtp", "invalid-target-world-for-player-spreading");
            return;
        }

        RtpWorldConfig baseConfig = getWorldConfig(world.getName());
        if (baseConfig == null || !baseConfig.isEnabled()) {
            if (sender != null) Messages.send(plugin, sender, "rtp", "rtp-is-disabled-in-world", "%world_name%", world.getName());
            return;
        }

        int maxR = customRadius > 0 ? customRadius : baseConfig.getMaxRadius();
        int minR = Math.min(baseConfig.getMinRadius(), Math.max(0, maxR - 100));
        RtpWorldConfig effectiveConfig = new RtpWorldConfig(
                world.getName(), true, baseConfig.getWorldType(), baseConfig.getShape(),
                minR, maxR, baseConfig.getCenterX(), baseConfig.getCenterZ(),
                new ArrayList<>(baseConfig.getBlacklistedBiomes()), new ArrayList<>(baseConfig.getWhitelistedBiomes()),
                RtpSpreadingManager.DistributionAlgorithm.SECTOR_ROUND_ROBIN
        );

        if (sender != null) {
            plugin.getTextService().sendMessage(sender,
                    plugin.getConfigService().getMessages().getString("rtp.spread-in-progress",
                            "&#00d2ffSpreading &f%count% &00d2ffplayers across &f%world% &00d2ff(Radius: %radius%)..."),
                    Map.of("%count%", String.valueOf(players.size()),
                            "%world%", world.getName(),
                            "%radius%", String.valueOf(maxR)));
        }

        World targetWorld = world;
        int maxAttempts = plugin.getConfigService().getRtpConfig().getInt("settings.max-attempts." + effectiveConfig.getWorldType().name().toLowerCase(), 30);

        spreadNextPlayer(sender, players, 0, targetWorld, effectiveConfig, maxAttempts, 0);
    }

    private void spreadNextPlayer(CommandSender sender, List<Player> players, int index, World world,
                                  RtpWorldConfig config, int maxAttempts, int successCount) {
        RtpWorldConfig currentConfig = getWorldConfig(world.getName());
        if (index >= players.size() || currentConfig == null || !currentConfig.isEnabled()) {
            if (sender != null) {
                Runnable notify = () -> plugin.getTextService().sendMessage(sender,
                        plugin.getConfigService().getMessages().getString("rtp.spread-success",
                                "&#00e676Successfully spread &f%count%/%total% &00e676players across &f%world%!"),
                        Map.of("%count%", String.valueOf(successCount),
                                "%total%", String.valueOf(players.size()),
                                "%world%", world.getName()));
                if (sender instanceof Player player) plugin.getScheduler().runEntity(player, notify);
                else notify.run();
            }
            return;
        }

        Player target = players.get(index);
        if (target == null || !target.isOnline() || !beginRequest(target.getUniqueId())) {
            spreadNextPlayer(sender, players, index + 1, world, config, maxAttempts, successCount);
            return;
        }

        UUID uuid = target.getUniqueId();
        RtpRequestState request = new RtpRequestState(0, false);
        activeRequests.put(uuid, request);
        Runnable skip = () -> {
            finishRequest(uuid, request, true, false);
            plugin.getScheduler().runGlobal(() -> spreadNextPlayer(sender, players, index + 1, world, config, maxAttempts, successCount));
        };
        request.search = locationFinder.findSafeLocation(world, config, maxAttempts, null, uuid);
        request.search.whenComplete((locOpt, ex) -> {
            plugin.getScheduler().runEntity(target, () -> {
                if (activeRequests.get(uuid) != request) {
                    skip.run();
                    return;
                }
                if (locOpt != null && locOpt.isPresent() && target.isOnline()) {
                    Location dest = locOpt.get();
                    executeTeleportAsync(target, dest).whenComplete((success, error) -> {
                        finishRequest(uuid, request, error != null || !Boolean.TRUE.equals(success), false);
                        plugin.getScheduler().runGlobal(() -> spreadNextPlayer(sender, players, index + 1,
                                world, config, maxAttempts, successCount + (Boolean.TRUE.equals(success) && error == null ? 1 : 0)));
                    });
                } else {
                    if (target.isOnline()) {
                        actionbarAnimator.renderFailed(target);
                    }
                    skip.run();
                }
            }, skip);
        });
    }

    private void startWarmupOrExecute(Player player, Location targetLoc, boolean bypassWarmup) {
        if (!player.isOnline()) {
            finishRequest(player.getUniqueId(), true);
            return;
        }

        boolean canBypass = bypassWarmup || player.hasPermission("friendlycore.rtp.bypass.warmup") || warmupSeconds <= 0;
        if (canBypass) {
            executeTeleport(player, targetLoc);
            return;
        }

        UUID uuid = player.getUniqueId();
        RtpWarmupSession session = beginWarmupSession(uuid);
        if (session == null) {
            return;
        }
        warmupStartLocations.put(uuid, player.getLocation().clone());
        warmupStartTimes.put(uuid, System.currentTimeMillis());

        if (!actionbarAnimator.isSuppressChatMessages()) {
            plugin.getTextService().sendMessage(player,
                    plugin.getConfigService().getMessages().getString("rtp.warmup-started",
                            "&#00d2ffStand still for %seconds%s before teleportation."),
                    Map.of("%seconds%", String.valueOf(warmupSeconds),
                            "%second%", String.valueOf(warmupSeconds),
                            "%time%", String.valueOf(warmupSeconds),
                            "%player%", player.getName()));
        }

        int totalTicks = warmupSeconds * 20;
        AtomicInteger elapsedTicks = new AtomicInteger(0);

        Object task = plugin.getScheduler().runEntityTimer(player, () -> {
            int current = elapsedTicks.addAndGet(2);
            actionbarAnimator.renderWarmup(player, current, totalTicks);

            if (current >= totalTicks) {
                if (!completeWarmupSession(uuid, session)) {
                    return;
                }
                executeTeleport(player, targetLoc);
            }
        }, 0L, 2L);

        attachWarmupTask(uuid, session, task);
    }

    /**
     * Start cross-server warmup before proxying player.
     */
    public void startCrossServerWarmup(Player player, String targetServer) {
        if (player == null || !player.isOnline()) return;

        boolean canBypass = player.hasPermission("friendlycore.rtp.bypass.warmup") || warmupSeconds <= 0;
        if (canBypass) {
            crossServerService.sendPlayerToProxyServer(player, targetServer);
            return;
        }

        UUID uuid = player.getUniqueId();
        RtpWarmupSession session = beginWarmupSession(uuid);
        if (session == null) {
            return;
        }
        warmupStartLocations.put(uuid, player.getLocation().clone());
        warmupStartTimes.put(uuid, System.currentTimeMillis());

        if (!actionbarAnimator.isSuppressChatMessages()) {
            plugin.getTextService().sendMessage(player,
                    plugin.getConfigService().getMessages().getString("rtp.warmup-started",
                            "&#00d2ffStand still for %seconds%s before teleportation."),
                    Map.of("%seconds%", String.valueOf(warmupSeconds),
                            "%second%", String.valueOf(warmupSeconds),
                            "%time%", String.valueOf(warmupSeconds),
                            "%player%", player.getName()));
        }

        int totalTicks = warmupSeconds * 20;
        AtomicInteger elapsedTicks = new AtomicInteger(0);

        Object task = plugin.getScheduler().runEntityTimer(player, () -> {
            int current = elapsedTicks.addAndGet(2);
            actionbarAnimator.renderWarmup(player, current, totalTicks);

            if (current >= totalTicks) {
                if (!completeWarmupSession(uuid, session)) {
                    return;
                }
                crossServerService.sendPlayerToProxyServer(player, targetServer);
            }
        }, 0L, 2L);

        attachWarmupTask(uuid, session, task);
    }

    public void cancelWarmup(Player player, String reasonKey, String defaultMsg) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        RtpRequestState request = activeRequests.get(uuid);
        if (request != null && request.teleporting) return;
        if (activeWarmupTasks.containsKey(uuid) || activeRequests.containsKey(uuid)
                || (crossServerService != null && crossServerService.hasPendingRequest(uuid))) {
            cancelWarmupTask(uuid);
            Object searchTask = activeSearchTasks.remove(uuid);
            if (searchTask != null) plugin.getScheduler().cancel(searchTask);
            if (crossServerService != null) crossServerService.cancelPendingRequest(uuid);
            finishRequest(uuid, true);
            applyCancelCooldown(uuid);

            if (defaultMsg != null) actionbarAnimator.renderCancelled(player, defaultMsg);
        }
    }

    public void applyCancelCooldown(UUID uuid) {
        if (uuid == null || cancelCooldownSeconds <= 0) return;
        plugin.getCooldownManager().setCooldown("rtp_cancel", uuid, cancelCooldownSeconds * 1000L);
    }

    void cancelWarmupTask(UUID uuid) {
        RtpWarmupSession session = activeWarmupTasks.remove(uuid);
        if (session != null) {
            session.cancel();
            plugin.getScheduler().cancel(session.task());
        }
        warmupStartLocations.remove(uuid);
        warmupStartTimes.remove(uuid);
    }

    private RtpWarmupSession beginWarmupSession(UUID uuid) {
        RtpWarmupSession session = new RtpWarmupSession();
        return activeWarmupTasks.putIfAbsent(uuid, session) == null ? session : null;
    }

    private void attachWarmupTask(UUID uuid, RtpWarmupSession session, Object task) {
        session.attachTask(task);
        if (task == null || session.isTerminal()) {
            plugin.getScheduler().cancel(task);
            activeWarmupTasks.remove(uuid, session);
            warmupStartLocations.remove(uuid);
            warmupStartTimes.remove(uuid);
        }
    }

    private boolean completeWarmupSession(UUID uuid, RtpWarmupSession session) {
        if (!session.tryComplete() || !activeWarmupTasks.remove(uuid, session)) {
            return false;
        }
        plugin.getScheduler().cancel(session.task());
        warmupStartLocations.remove(uuid);
        warmupStartTimes.remove(uuid);
        return true;
    }

    public void executeTeleport(Player player, Location targetLoc) {
        executeTeleport(player, targetLoc, null);
    }

    public void executeTeleport(Player player, Location targetLoc, Runnable onComplete) {
        executeTeleportAsync(player, targetLoc).whenComplete((success, error) -> {
            if (onComplete != null) onComplete.run();
        });
    }

    public CompletableFuture<Boolean> executeTeleportAsync(Player player, Location targetLoc) {
        if (player == null) return CompletableFuture.completedFuture(false);
        UUID uuid = player.getUniqueId();
        RtpRequestState request = activeRequests.get(uuid);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        CompletableFuture<Boolean> settled = result.whenComplete((success, error) -> finishRequest(uuid, request,
                error != null || !Boolean.TRUE.equals(success), false));
        if (!player.isOnline() || targetLoc == null || targetLoc.getWorld() == null) {
            result.complete(false);
            return settled;
        }
        try {
            cancelWarmupTask(uuid);
            Location dest = targetLoc.clone();
            result.whenComplete((success, error) -> {
                if (spreadingManager != null) spreadingManager.releaseLanding(uuid, dest);
            });
            // Read the player here, on their own thread; retries run on the destination region's thread.
            if (dest.getYaw() == 0.0f && dest.getPitch() == 0.0f) {
                dest.setYaw(player.getLocation().getYaw());
                dest.setPitch(player.getLocation().getPitch());
            }
            landAt(player, uuid, request, dest, POOL_LANDING_RETRIES, result);
        } catch (Exception e) {
            result.complete(false);
        }
        return settled;
    }

    /** A pool spot can be built over after scanning; try a few more before failing the request. */
    private static final int POOL_LANDING_RETRIES = 3;

    private void landAt(Player player, UUID uuid, RtpRequestState request, Location dest, int retries,
                        CompletableFuture<Boolean> result) {
        World world = dest.getWorld();
        world.getChunkAtAsync(dest.getBlockX() >> 4, dest.getBlockZ() >> 4, true).whenComplete((chunk, error) -> {
            if (error != null || chunk == null) {
                result.complete(false);
                return;
            }
            plugin.getScheduler().runLocation(dest, () -> {
                try {
                    RtpWorldConfig config = getWorldConfig(world.getName());
                    boolean pool = config != null && config.isLandingPool();
                    boolean safe = config != null && config.isEnabled()
                            && safetyValidator.isLocationSafe(dest, chunk, config.getWorldType())
                            && config.isBiomeAllowed(chunk.getBlock(dest.getBlockX() & 15, dest.getBlockY(), dest.getBlockZ() & 15).getBiome().getKey().getKey())
                            && (pool || spreadingManager == null || spreadingManager.reserveLanding(uuid, dest));
                    if (!safe && pool && retries > 0) {
                        Location next = locationCache.poll(world, uuid).orElse(null);
                        if (next != null) {
                            next.setYaw(dest.getYaw());
                            next.setPitch(dest.getPitch());
                            landAt(player, uuid, request, next, retries - 1, result);
                            return;
                        }
                    }
                    plugin.getScheduler().runEntity(player, () -> {
                        try {
                            if (!safe || !player.isOnline()
                                    || (request != null && activeRequests.get(uuid) != request)) {
                                if (player.isOnline()) {
                                    applyCancelCooldown(uuid);
                                    actionbarAnimator.renderFailed(player);
                                }
                                result.complete(false);
                                return;
                            }
                            if (player.isInsideVehicle()) player.leaveVehicle();
                            player.setVelocity(new Vector(0, 0, 0));
                            player.setFallDistance(0.0f);
                            if (smoothTransition) {
                                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, transitionBlindnessTicks, 1, false, false, false));
                                player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, transitionBlindnessTicks, 1, false, false, false));
                                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, Math.max(30, transitionBlindnessTicks + 10), 0, false, false, false));
                            }
                            if (request != null) request.teleporting = true;
                            dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(player, dest, false)
                                    .whenComplete((success, teleportError) -> {
                                        boolean landed = teleportError == null && Boolean.TRUE.equals(success);
                                        if (landed && spreadingManager != null) spreadingManager.recordTeleport(dest);
                                        try {
                                            plugin.getScheduler().runEntity(player, () -> {
                                                try {
                                                    if (!player.isOnline()) return;
                                                    if (landed) {
                                                        if (!player.hasPermission("friendlycore.rtp.bypass.cooldown") && cooldownSeconds > 0) {
                                                            plugin.getCooldownManager().setCooldown("rtp", uuid, cooldownSeconds * 1000L);
                                                            crossServerService.broadcastCooldown(uuid, cooldownSeconds);
                                                        }
                                                        if (invulnerabilitySeconds > 0) {
                                                            invulnerablePlayers.put(uuid, System.currentTimeMillis() + invulnerabilitySeconds * 1000L);
                                                        }
                                                        actionbarAnimator.renderSuccess(player, dest);
                                                    } else {
                                                        applyCancelCooldown(uuid);
                                                        actionbarAnimator.renderFailed(player);
                                                    }
                                                } finally {
                                                    result.complete(landed);
                                                }
                                            }, () -> result.complete(landed));
                                        } catch (Exception e) {
                                            result.complete(landed);
                                        }
                                    });
                        } catch (Exception e) {
                            result.complete(false);
                        }
                    }, () -> result.complete(false));
                } catch (Exception e) {
                    result.complete(false);
                }
            });
        }).exceptionally(error -> { result.complete(false); return null; });
    }

    private void finishRequest(UUID uuid, boolean refund) {
        finishRequest(uuid, refund, true);
    }

    private void finishRequest(UUID uuid, boolean refund, boolean recyclable) {
        finishRequest(uuid, activeRequests.get(uuid), refund, recyclable);
    }

    private void finishRequest(UUID uuid, RtpRequestState request, boolean refund, boolean recyclable) {
        if (request == null || !activeRequests.remove(uuid, request) || !request.finished.compareAndSet(false, true)) return;
        Object searchTask = activeSearchTasks.remove(uuid);
        if (searchTask != null) plugin.getScheduler().cancel(searchTask);
        if (request.search != null) request.search.cancel(false);
        endRequest(uuid);

        if (refund && recyclable && request.targetLoc != null && locationCache != null) {
            locationCache.recycle(request.targetLoc);
            request.targetLoc = null;
        }

        if (refund && request.refundable && request.charged.get() && plugin.getEconomyManager() != null) {
            if (!plugin.getEconomyManager().deposit(uuid, request.cost, true)) {
                plugin.getLogger().severe("Unable to refund failed/cancelled RTP charge to " + uuid);
            }
        }
    }

    public void handleRemoteCooldown(UUID uuid, long expiresAt) {
        if (uuid == null || expiresAt <= System.currentTimeMillis()) return;
        plugin.getCooldownManager().setCooldownUntil("rtp", uuid, expiresAt);
    }

    boolean beginRequest(UUID uuid) {
        return !activeWarmupTasks.containsKey(uuid) && requestPlayers.add(uuid);
    }

    void endRequest(UUID uuid) {
        requestPlayers.remove(uuid);
    }

    public int getWarmupSeconds() {
        return warmupSeconds;
    }

    public boolean isInvulnerable(UUID uuid) {
        Long until = invulnerablePlayers.get(uuid);
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            invulnerablePlayers.remove(uuid);
            return false;
        }
        return true;
    }

    public void removeInvulnerable(UUID uuid) {
        if (uuid != null) {
            invulnerablePlayers.remove(uuid);
        }
    }

    public RtpWorldConfig getWorldConfig(String worldName) {
        if (worldName == null || !localEnabled) return null;
        RtpWorldConfig config = worldConfigs.get(worldName);
        if (config == null) {
            for (Map.Entry<String, RtpWorldConfig> entry : worldConfigs.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(worldName)) {
                    return entry.getValue();
                }
            }
        }
        return config;
    }

    public Map<String, RtpWorldConfig> getWorldConfigs() {
        return Collections.unmodifiableMap(worldConfigs);
    }

    public boolean isWarmupActive(UUID uuid) {
        return uuid != null && activeWarmupTasks.containsKey(uuid);
    }

    public Location getWarmupStartLocation(UUID uuid) {
        return warmupStartLocations.get(uuid);
    }

    public boolean isCancelOnMove() {
        return cancelOnMove;
    }

    public double getMoveThreshold() {
        return moveThreshold;
    }

    public boolean isCancelOnDamage() {
        return cancelOnDamage;
    }

    public boolean isEconomyEnabled() {
        return economyEnabled;
    }

    public double getEconomyCost() {
        return economyCost;
    }

    public boolean isRefundOnCancel() {
        return refundOnCancel;
    }

    public int getCancelCooldownSeconds() {
        return cancelCooldownSeconds;
    }

    public RtpLocationCache getLocationCache() {
        return locationCache;
    }

    public RtpLocationFinder getLocationFinder() {
        return locationFinder;
    }

    public RtpSpreadingManager getSpreadingManager() {
        return spreadingManager;
    }

    public RtpCrossServerService getCrossServerService() {
        return crossServerService;
    }

    public RtpActionbarAnimator getActionbarAnimator() {
        return actionbarAnimator;
    }

    public int getRemainingWarmupSeconds(UUID uuid) {
        if (uuid == null || !activeWarmupTasks.containsKey(uuid)) return 0;
        Long start = warmupStartTimes.get(uuid);
        if (start == null) return warmupSeconds;
        long elapsed = System.currentTimeMillis() - start;
        long totalMs = warmupSeconds * 1000L;
        return (int) Math.max(0, Math.ceil((totalMs - elapsed) / 1000.0));
    }

    public long getRemainingCooldownSeconds(UUID uuid) {
        if (uuid == null) return 0;
        long cd = plugin.getCooldownManager().getRemainingSeconds("rtp", uuid, cooldownSeconds * 1000L);
        long cancelCd = cancelCooldownSeconds > 0 ? plugin.getCooldownManager().getRemainingSeconds("rtp_cancel", uuid, cancelCooldownSeconds * 1000L) : 0;
        return Math.max(cd, cancelCd);
    }

    public boolean isOnCooldown(UUID uuid) {
        if (uuid == null) return false;
        boolean cd = plugin.getCooldownManager().isOnCooldown("rtp", uuid, cooldownSeconds * 1000L);
        boolean cancelCd = cancelCooldownSeconds > 0 && plugin.getCooldownManager().isOnCooldown("rtp_cancel", uuid, cancelCooldownSeconds * 1000L);
        return cd || cancelCd;
    }

    public void shutdown() {
        if (locationFinder != null) locationFinder.cancelAll();
        for (Object task : activeSearchTasks.values()) plugin.getScheduler().cancel(task);
        activeSearchTasks.clear();
        for (UUID uuid : List.copyOf(activeWarmupTasks.keySet())) {
            cancelWarmupTask(uuid);
        }
        for (UUID uuid : List.copyOf(activeRequests.keySet())) finishRequest(uuid, true);
        if (locationCache != null) {
            locationCache.shutdown();
        }
        if (crossServerService != null) {
            crossServerService.shutdown();
        }
        if (spreadingManager != null) {
            spreadingManager.clear();
        }
    }
}
