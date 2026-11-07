package dev.lyy.friendlycore.modules.tpa.crossserver;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.modules.tpa.SafeTeleportChecker;
import dev.lyy.friendlycore.modules.tpa.TpaCountdownTask;
import dev.lyy.friendlycore.modules.tpa.TpaManager;
import dev.lyy.friendlycore.modules.tpa.TpaProfile;
import dev.lyy.friendlycore.modules.tpa.TpaRequest;
import dev.lyy.friendlycore.modules.tpa.gui.TpaConfirmGui;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates cross-server teleportation using Redis Pub/Sub packets and BungeeCord/Velocity messaging channels.
 * Manages arrival ticket lifecycle, remote permission validations, and multi-server countdown sequencing.
 */
public class TpaCrossServerService {

    private final FriendlyCore plugin;
    private final TpaManager tpaManager;
    private final Map<UUID, TpaTeleportTicket> arrivalTickets = new ConcurrentHashMap<>();
    private Object cleanupTask;

    public TpaCrossServerService(FriendlyCore plugin, TpaManager tpaManager) {
        this.plugin = plugin;
        this.tpaManager = tpaManager;
        startCleanupTask();
    }

    private void startCleanupTask() {
        if (cleanupTask != null) {
            plugin.getScheduler().cancel(cleanupTask);
        }
        // Purge expired tickets every 5 seconds (100 ticks)
        this.cleanupTask = plugin.getScheduler().runAsyncTimer(() -> {
            arrivalTickets.entrySet().removeIf(e -> e.getValue().isExpired());
        }, 100L, 100L);
    }

    public boolean isCrossServerEnabled() {
        return plugin.getRedisService() != null && plugin.getRedisService().isEnabled();
    }

    public String getLocalServerId() {
        if (plugin.getRedisService() != null) {
            return plugin.getRedisService().getServerId();
        }
        return "server-1";
    }

    /**
     * Dispatches an outgoing cross-server TPA or TPHERE request.
     */
    public void sendCrossServerRequest(Player sender, String targetName, UUID targetUuid, TpaRequest.Type type, boolean guiConfirmed) {
        if (sender == null || !sender.isOnline()) return;

        if (sender.getName().equalsIgnoreCase(targetName)) {
            tpaManager.sendConfigMessage(sender, "tpa.self-teleport", Map.of());
            return;
        }

        List<String> blacklistedWorlds = tpaManager.getConfig().getStringList("blacklisted-worlds");
        if (isWorldBlacklisted(sender.getWorld().getName(), blacklistedWorlds)) {
            tpaManager.sendConfigMessage(sender, "tpa.blacklisted-world", Map.of());
            return;
        }

        List<String> blacklistedRegions = tpaManager.getConfig().getStringList("world-guard.blacklisted-regions");
        if (plugin.getHookManager().getProtectionHook().isRegionBlocked(sender.getLocation(), blacklistedRegions)) {
            tpaManager.sendConfigMessage(sender, "tpa.blacklisted-region", Map.of());
            return;
        }

        TpaProfile senderProfile = tpaManager.getProfile(sender);

        int limit = tpaManager.getConfig().getInt("teleport-limitations", -1);
        if (limit > 0 && !sender.hasPermission("friendlycore.bypass.cooldown") && !sender.hasPermission("donuttpa.admin")) {
            if (senderProfile.getDailyCount() >= limit) {
                tpaManager.sendConfigMessage(sender, "tpa.daily-limit-reached", Map.of("%limit%", String.valueOf(limit)));
                return;
            }
        }

        long cooldownDuration = tpaManager.getConfig().getLong("request-cooldown", 30000L);
        if (cooldownDuration > 0 && !sender.hasPermission("friendlycore.bypass.cooldown")) {
            if (targetUuid != null && senderProfile.isOnCooldown(targetUuid)) {
                tpaManager.sendConfigMessage(sender, "tpa.request-cooldown", Map.of("%player%", targetName));
                return;
            }
        }

        // Avoid duplicate pending requests
        if (targetUuid != null && senderProfile.getOutgoingRequests().containsKey(targetUuid)) {
            tpaManager.sendConfigMessage(sender, "tpa.already-sent-request", Map.of("%player%", targetName));
            return;
        }

        boolean disableGuiGlobal = tpaManager.getConfig().getBoolean("disable-tpa-gui", false);
        if (!disableGuiGlobal && senderProfile.isShowGui() && !guiConfirmed) {
            new TpaConfirmGui(plugin, tpaManager, sender, null, type, TpaConfirmGui.GuiMode.REQUEST).open();
            return;
        }

        if (targetUuid != null) {
            senderProfile.setCooldown(targetUuid, cooldownDuration);
        }

        TpaRequest request = new TpaRequest(sender.getUniqueId(), sender.getName(), targetUuid, targetName, type);
        request.setOriginServer(getLocalServerId());
        request.setInitialLocation(sender.getLocation().clone());

        if (targetUuid != null) {
            senderProfile.addOutgoingRequest(request);
        }

        TpaCrossServerPacketPayload payload = new TpaCrossServerPacketPayload();
        payload.setRequestId(request.getId());
        payload.setSenderUuid(sender.getUniqueId());
        payload.setSenderName(sender.getName());
        payload.setTargetUuid(targetUuid);
        payload.setTargetName(targetName);
        payload.setOriginServer(getLocalServerId());
        payload.setType(type);

        if (type == TpaRequest.Type.TPHERE) {
            Location loc = sender.getLocation();
            payload.setWorldName(loc.getWorld().getName());
            payload.setX(loc.getX());
            payload.setY(loc.getY());
            payload.setZ(loc.getZ());
            payload.setYaw(loc.getYaw());
            payload.setPitch(loc.getPitch());
        }

        CrossServerPacket packet = new CrossServerPacket(
                getLocalServerId(),
                CrossServerPacket.Action.TPA_REQUEST,
                targetUuid,
                targetName,
                0.0,
                plugin.getGson().toJson(payload)
        );

        plugin.getRedisService().publish(packet);

        if (type == TpaRequest.Type.TPA) {
            tpaManager.sendConfigMessage(sender, "tpa.sent-request", Map.of("%player%", targetName));
            tpaManager.sendConfigMessage(sender, "tpa.tpa-request-sender-actionbar", Map.of("%player%", targetName));
        } else {
            tpaManager.sendConfigMessage(sender, "tpa.sent-here-request", Map.of("%player%", targetName));
            tpaManager.sendConfigMessage(sender, "tpa.tpa-here-request-sender-actionbar", Map.of("%player%", targetName));
        }
    }

    /**
     * Accepts a cross-server request on target's server.
     */
    public void acceptCrossServerRequest(Player target, TpaRequest request) {
        if (target == null || !target.isOnline() || request == null) return;

        TpaProfile targetProfile = tpaManager.getProfile(target);
        targetProfile.removeIncomingRequest(request.getSenderUuid());
        request.setStatus(TpaRequest.Status.ACCEPTED);

        if (request.getType() == TpaRequest.Type.TPA || request.getType() == TpaRequest.Type.TPAALL) {
            // Target is local host; sender will connect from remote origin server
            plugin.getScheduler().runLocation(target.getLocation(), () -> {
                Location safeDest = SafeTeleportChecker.getSafeDestination(null, target, target.getLocation(), tpaManager.getSafeSettings());
                if (safeDest == null) {
                    tpaManager.sendConfigMessage(target, "tpa.teleport-fail-safe", Map.of());
                    sendFailure(request.getSenderUuid(), request.getSenderName(), request.getOriginServer(), "UNSAFE");
                    return;
                }

                // Register arrival ticket on this server for the incoming player
                TpaTeleportTicket ticket = new TpaTeleportTicket(
                        request.getSenderUuid(),
                        request.getSenderName(),
                        target.getUniqueId(),
                        target.getName(),
                        safeDest.getWorld().getName(),
                        safeDest.getX(),
                        safeDest.getY(),
                        safeDest.getZ(),
                        safeDest.getYaw(),
                        safeDest.getPitch(),
                        request.getType(),
                        ticketTimeoutMs()
                );
                arrivalTickets.put(request.getSenderUuid(), ticket);

                Player traveler = Bukkit.getPlayer(request.getSenderUuid());
                if (traveler != null && traveler.isOnline()) {
                    TpaTeleportTicket claimed = consumeTicket(request.getSenderUuid());
                    if (claimed != null) {
                        Location finalDest = safeDest;
                        dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(traveler, finalDest).thenAccept(success -> {
                            if (Boolean.TRUE.equals(success)) {
                                String sound = tpaManager.getConfig().getString("sounds.accept-teleport", "entity.experience_orb.pickup");
                                TextService.playSound(traveler, sound, 1.0f, 1.0f);
                                tpaManager.sendConfigMessage(traveler, "tpa.accepted-teleport", Map.of("%player%", target.getName()));
                            }
                        });
                    }
                }

                plugin.getScheduler().runEntity(target, () -> {
                    tpaManager.sendConfigMessage(target, "tpa.accepted-teleport", Map.of("%player%", request.getSenderName()));
                });

                // Publish accept notification back to sender's origin server
                TpaCrossServerPacketPayload payload = new TpaCrossServerPacketPayload();
                payload.setRequestId(request.getId());
                payload.setSenderUuid(request.getSenderUuid());
                payload.setSenderName(request.getSenderName());
                payload.setTargetUuid(target.getUniqueId());
                payload.setTargetName(target.getName());
                payload.setOriginServer(request.getOriginServer());
                payload.setTargetServer(getLocalServerId());
                payload.setType(request.getType());

                CrossServerPacket packet = new CrossServerPacket(
                        getLocalServerId(),
                        CrossServerPacket.Action.TPA_ACCEPT,
                        request.getSenderUuid(),
                        request.getSenderName(),
                        0.0,
                        plugin.getGson().toJson(payload)
                );
                plugin.getRedisService().publish(packet);
            });
        } else {
            // TPHERE: Target accepts; target will travel to sender on remote origin server
            TpaCrossServerPacketPayload payload = new TpaCrossServerPacketPayload();
            payload.setRequestId(request.getId());
            payload.setSenderUuid(request.getSenderUuid());
            payload.setSenderName(request.getSenderName());
            payload.setTargetUuid(target.getUniqueId());
            payload.setTargetName(target.getName());
            payload.setOriginServer(request.getOriginServer());
            payload.setTargetServer(getLocalServerId());
            payload.setType(request.getType());

            CrossServerPacket packet = new CrossServerPacket(
                    getLocalServerId(),
                    CrossServerPacket.Action.TPA_ACCEPT,
                    request.getSenderUuid(),
                    request.getSenderName(),
                    0.0,
                    plugin.getGson().toJson(payload)
            );
            plugin.getRedisService().publish(packet);
        }
    }

    /**
     * Denies a cross-server request on target's server.
     */
    public void denyCrossServerRequest(Player target, TpaRequest request) {
        if (target == null || request == null) return;

        TpaProfile targetProfile = tpaManager.getProfile(target);
        targetProfile.removeIncomingRequest(request.getSenderUuid());
        request.setStatus(TpaRequest.Status.DECLINED);

        tpaManager.sendConfigMessage(target, "tpa.cancelled-request", Map.of("%player%", request.getSenderName()));

        TpaCrossServerPacketPayload payload = new TpaCrossServerPacketPayload();
        payload.setRequestId(request.getId());
        payload.setSenderUuid(request.getSenderUuid());
        payload.setSenderName(request.getSenderName());
        payload.setTargetUuid(target.getUniqueId());
        payload.setTargetName(target.getName());
        payload.setOriginServer(request.getOriginServer());
        payload.setTargetServer(getLocalServerId());
        payload.setType(request.getType());

        CrossServerPacket packet = new CrossServerPacket(
                getLocalServerId(),
                CrossServerPacket.Action.TPA_DENY,
                request.getSenderUuid(),
                request.getSenderName(),
                0.0,
                plugin.getGson().toJson(payload)
        );
        plugin.getRedisService().publish(packet);
    }

    /**
     * Cancels an outgoing cross-server request.
     */
    public void cancelCrossServerRequest(Player sender, TpaRequest request) {
        if (sender == null || request == null) return;

        TpaCrossServerPacketPayload payload = new TpaCrossServerPacketPayload();
        payload.setRequestId(request.getId());
        payload.setSenderUuid(sender.getUniqueId());
        payload.setSenderName(sender.getName());
        payload.setTargetUuid(request.getTargetUuid());
        payload.setTargetName(request.getTargetName());
        payload.setOriginServer(getLocalServerId());
        payload.setTargetServer(request.getTargetServer());
        payload.setType(request.getType());

        CrossServerPacket packet = new CrossServerPacket(
                getLocalServerId(),
                CrossServerPacket.Action.TPA_CANCEL,
                request.getTargetUuid(),
                request.getTargetName(),
                0.0,
                plugin.getGson().toJson(payload)
        );
        plugin.getRedisService().publish(packet);
    }

    /**
     * Central incoming Redis packet router for TPA.
     */
    public void handleIncomingPacket(CrossServerPacket packet) {
        if (packet.getExtra() == null || packet.getExtra().isEmpty()) return;

        TpaCrossServerPacketPayload payload;
        try {
            payload = plugin.getGson().fromJson(packet.getExtra(), TpaCrossServerPacketPayload.class);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse cross-server TPA payload: " + e.getMessage());
            return;
        }

        if (payload == null) return;

        switch (packet.getAction()) {
            case TPA_REQUEST -> handleIncomingRequest(packet, payload);
            case TPA_ACCEPT -> handleIncomingAccept(packet, payload);
            case TPA_DENY -> handleIncomingDeny(packet, payload);
            case TPA_CANCEL -> handleIncomingCancel(packet, payload);
            case TPA_EXECUTE -> handleIncomingExecute(packet, payload);
        }
    }

    private void handleIncomingRequest(CrossServerPacket packet, TpaCrossServerPacketPayload payload) {
        Player target = Bukkit.getPlayer(payload.getTargetUuid());
        if (target == null || !target.isOnline()) {
            target = Bukkit.getPlayerExact(payload.getTargetName());
        }
        if (target == null || !target.isOnline()) {
            return; // Target is not on this server instance
        }

        Player finalTarget = target;
        plugin.getScheduler().runEntity(finalTarget, () -> {
            if (!finalTarget.isOnline()) return;

            TpaProfile targetProfile = tpaManager.getProfile(finalTarget);

            if (payload.getType() == TpaRequest.Type.TPA && !targetProfile.isTpaEnabled()) {
                sendFailure(payload.getSenderUuid(), payload.getSenderName(), packet.getSourceServer(), "BLOCKED_TPA");
                return;
            }
            if (payload.getType() == TpaRequest.Type.TPHERE && !targetProfile.isTpaHereEnabled()) {
                sendFailure(payload.getSenderUuid(), payload.getSenderName(), packet.getSourceServer(), "BLOCKED_TPHERE");
                return;
            }

            List<String> blacklistedWorlds = tpaManager.getConfig().getStringList("blacklisted-worlds");
            if (isWorldBlacklisted(finalTarget.getWorld().getName(), blacklistedWorlds)) {
                sendFailure(payload.getSenderUuid(), payload.getSenderName(), packet.getSourceServer(), "BLACKLISTED_WORLD");
                return;
            }

            List<String> blacklistedRegions = tpaManager.getConfig().getStringList("world-guard.blacklisted-regions");
            if (plugin.getHookManager().getProtectionHook().isRegionBlocked(finalTarget.getLocation(), blacklistedRegions)) {
                sendFailure(payload.getSenderUuid(), payload.getSenderName(), packet.getSourceServer(), "BLACKLISTED_REGION");
                return;
            }

            TpaRequest request = new TpaRequest(
                    payload.getRequestId(),
                    payload.getSenderUuid(),
                    payload.getSenderName(),
                    finalTarget.getUniqueId(),
                    finalTarget.getName(),
                    payload.getType(),
                    System.currentTimeMillis(),
                    TpaRequest.Status.PENDING
            );
            request.setOriginServer(packet.getSourceServer());
            request.setTargetServer(getLocalServerId());

            targetProfile.addIncomingRequest(request);

            if (targetProfile.isTpAutoEnabled() && payload.getType() == TpaRequest.Type.TPA) {
                acceptCrossServerRequest(finalTarget, request);
                return;
            }

            if (payload.getType() == TpaRequest.Type.TPA) {
                tpaManager.sendConfigMessage(finalTarget, "tpa.receive-request", Map.of("%player%", payload.getSenderName()));
                tpaManager.sendConfigMessage(finalTarget, "tpa.tpa-request-requester-actionbar", Map.of("%player%", payload.getSenderName()));
                String sound = tpaManager.getConfig().getString("sounds.tpa-receive", "entity.experience_orb.pickup");
                TextService.playSound(finalTarget, sound, 1.0f, 1.0f);
            } else {
                tpaManager.sendConfigMessage(finalTarget, "tpa.receive-here-request", Map.of("%player%", payload.getSenderName()));
                tpaManager.sendConfigMessage(finalTarget, "tpa.tpa-here-request-requester-actionbar", Map.of("%player%", payload.getSenderName()));
                String sound = tpaManager.getConfig().getString("sounds.tpa-receive", "entity.experience_orb.pickup");
                TextService.playSound(finalTarget, sound, 1.0f, 1.0f);
            }
        });
    }

    private void handleIncomingAccept(CrossServerPacket packet, TpaCrossServerPacketPayload payload) {
        if (payload.getType() == TpaRequest.Type.TPA || payload.getType() == TpaRequest.Type.TPAALL) {
            // Sender is on this server; accepted by target on remote targetServer
            Player sender = Bukkit.getPlayer(payload.getSenderUuid());
            if (sender == null || !sender.isOnline()) {
                return;
            }

            plugin.getScheduler().runEntity(sender, () -> {
                if (!sender.isOnline()) return;

                TpaProfile senderProfile = tpaManager.getProfile(sender);
                senderProfile.removeOutgoingRequest(payload.getTargetUuid());

                tpaManager.sendConfigMessage(sender, "tpa.accepted-teleport", Map.of("%player%", payload.getTargetName()));

                long duration = sender.hasPermission("friendlycore.bypass.warmup") ? 0 : tpaManager.getConfig().getLong("tpa-countdown-seconds", 5L);
                boolean smooth = tpaManager.getConfig().getBoolean("use-smooth-countdown", true);
                double walkRange = tpaManager.getConfig().getDouble("allowed-walk-range", 1.0);

                tpaManager.removeActiveCountdown(sender.getUniqueId());

                String targetServer = payload.getTargetServer() != null ? payload.getTargetServer() : packet.getSourceServer();

                TpaCountdownTask task = new TpaCountdownTask(plugin, tpaManager, sender, null, duration, smooth, walkRange, success -> {
                    if (success && sender.isOnline()) {
                        senderProfile.incrementDailyCount();
                        sendPlayerToProxyServer(sender, targetServer);
                    } else {
                        // Cancelled due to movement/damage: notify remote server to drop arrival ticket
                        cancelCrossServerRequest(sender, new TpaRequest(sender.getUniqueId(), sender.getName(), payload.getTargetUuid(), payload.getTargetName(), payload.getType()));
                    }
                });

                tpaManager.getActiveCountdownMap().put(sender.getUniqueId(), task);
                task.start();
            });
        } else {
            // TPHERE: Target accepted on remote server. Host is sender on this server.
            Player host = Bukkit.getPlayer(payload.getSenderUuid());
            if (host == null || !host.isOnline()) return;

            plugin.getScheduler().runLocation(host.getLocation(), () -> {
                Location safeDest = SafeTeleportChecker.getSafeDestination(null, host, host.getLocation(), tpaManager.getSafeSettings());
                if (safeDest == null) {
                    tpaManager.sendConfigMessage(host, "tpa.teleport-fail-safe", Map.of());
                    sendFailure(payload.getTargetUuid(), payload.getTargetName(), packet.getSourceServer(), "UNSAFE");
                    return;
                }

                TpaTeleportTicket ticket = new TpaTeleportTicket(
                        payload.getTargetUuid(),
                        payload.getTargetName(),
                        host.getUniqueId(),
                        host.getName(),
                        safeDest.getWorld().getName(),
                        safeDest.getX(),
                        safeDest.getY(),
                        safeDest.getZ(),
                        safeDest.getYaw(),
                        safeDest.getPitch(),
                        payload.getType(),
                        ticketTimeoutMs()
                );
                arrivalTickets.put(payload.getTargetUuid(), ticket);

                Player traveler = Bukkit.getPlayer(payload.getTargetUuid());
                if (traveler != null && traveler.isOnline()) {
                    TpaTeleportTicket claimed = consumeTicket(payload.getTargetUuid());
                    if (claimed != null) {
                        Location finalDest = safeDest;
                        dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(traveler, finalDest).thenAccept(success -> {
                            if (Boolean.TRUE.equals(success)) {
                                String sound = tpaManager.getConfig().getString("sounds.accept-teleport", "entity.experience_orb.pickup");
                                TextService.playSound(traveler, sound, 1.0f, 1.0f);
                                tpaManager.sendConfigMessage(traveler, "tpa.accepted-teleport", Map.of("%player%", host.getName()));
                            }
                        });
                    }
                }

                plugin.getScheduler().runEntity(host, () -> {
                    TpaProfile hostProfile = tpaManager.getProfile(host);
                    hostProfile.removeOutgoingRequest(payload.getTargetUuid());
                    tpaManager.sendConfigMessage(host, "tpa.tpa-here-accept", Map.of("%player%", payload.getTargetName()));
                    tpaManager.sendConfigMessage(host, "tpa.tpa-here-accept-actionbar", Map.of("%player%", payload.getTargetName()));
                });

                TpaCrossServerPacketPayload execPayload = new TpaCrossServerPacketPayload();
                execPayload.setRequestId(payload.getRequestId());
                execPayload.setSenderUuid(host.getUniqueId());
                execPayload.setSenderName(host.getName());
                execPayload.setTargetUuid(payload.getTargetUuid());
                execPayload.setTargetName(payload.getTargetName());
                execPayload.setOriginServer(getLocalServerId());
                execPayload.setTargetServer(getLocalServerId());
                execPayload.setType(payload.getType());

                CrossServerPacket execPacket = new CrossServerPacket(
                        getLocalServerId(),
                        CrossServerPacket.Action.TPA_EXECUTE,
                        payload.getTargetUuid(),
                        payload.getTargetName(),
                        0.0,
                        plugin.getGson().toJson(execPayload)
                );
                plugin.getRedisService().publish(execPacket);
            });
        }
    }

    private void handleIncomingExecute(CrossServerPacket packet, TpaCrossServerPacketPayload payload) {
        Player traveler = Bukkit.getPlayer(payload.getTargetUuid());
        if (traveler == null || !traveler.isOnline()) return;

        plugin.getScheduler().runEntity(traveler, () -> {
            if (!traveler.isOnline()) return;

            TpaProfile travelerProfile = tpaManager.getProfile(traveler);
            travelerProfile.removeIncomingRequest(payload.getSenderUuid());

            tpaManager.sendConfigMessage(traveler, "tpa.accepted-teleport", Map.of("%player%", payload.getSenderName()));

            long duration = traveler.hasPermission("friendlycore.bypass.warmup") ? 0 : tpaManager.getConfig().getLong("tpa-countdown-seconds", 5L);
            boolean smooth = tpaManager.getConfig().getBoolean("use-smooth-countdown", true);
            double walkRange = tpaManager.getConfig().getDouble("allowed-walk-range", 1.0);

            tpaManager.removeActiveCountdown(traveler.getUniqueId());

            String targetServer = payload.getTargetServer() != null ? payload.getTargetServer() : packet.getSourceServer();

            TpaCountdownTask task = new TpaCountdownTask(plugin, tpaManager, traveler, null, duration, smooth, walkRange, success -> {
                if (success && traveler.isOnline()) {
                    travelerProfile.incrementDailyCount();
                    sendPlayerToProxyServer(traveler, targetServer);
                } else {
                    cancelCrossServerRequest(traveler, new TpaRequest(payload.getSenderUuid(), payload.getSenderName(), traveler.getUniqueId(), traveler.getName(), payload.getType()));
                }
            });

            tpaManager.getActiveCountdownMap().put(traveler.getUniqueId(), task);
            task.start();
        });
    }

    private void handleIncomingDeny(CrossServerPacket packet, TpaCrossServerPacketPayload payload) {
        Player sender = Bukkit.getPlayer(payload.getSenderUuid());
        if (sender != null && sender.isOnline()) {
            plugin.getScheduler().runEntity(sender, () -> {
                TpaProfile senderProfile = tpaManager.getProfile(sender);
                if (senderProfile != null) {
                    senderProfile.removeOutgoingRequest(payload.getTargetUuid());
                }

                String reason = payload.getReason();
                String targetName = payload.getTargetName() != null ? payload.getTargetName() : "";
                if ("BLOCKED_TPA".equals(reason)) {
                    tpaManager.sendConfigMessage(sender, "tpa.block-tpa-request", Map.of("%player%", targetName));
                } else if ("BLOCKED_TPHERE".equals(reason)) {
                    tpaManager.sendConfigMessage(sender, "tpa.block-tphere-request", Map.of("%player%", targetName));
                } else if ("UNSAFE".equals(reason)) {
                    tpaManager.sendConfigMessage(sender, "tpa.teleport-fail-safe", Map.of());
                } else if ("BLACKLISTED_WORLD".equals(reason)) {
                    tpaManager.sendConfigMessage(sender, "tpa.blacklisted-world", Map.of());
                } else if ("BLACKLISTED_REGION".equals(reason)) {
                    tpaManager.sendConfigMessage(sender, "tpa.blacklisted-region", Map.of());
                } else {
                    tpaManager.sendConfigMessage(sender, "tpa.cancelled-request-sender", Map.of("%player%", targetName));
                }
            });
        }
    }

    private void handleIncomingCancel(CrossServerPacket packet, TpaCrossServerPacketPayload payload) {
        Player target = Bukkit.getPlayer(payload.getTargetUuid());
        if (target != null && target.isOnline()) {
            plugin.getScheduler().runEntity(target, () -> {
                TpaProfile targetProfile = tpaManager.getProfile(target);
                if (targetProfile != null) {
                    targetProfile.removeIncomingRequest(payload.getSenderUuid());
                }
            });
        }
        if (payload.getSenderUuid() != null) {
            arrivalTickets.remove(payload.getSenderUuid());
        }
        if (payload.getTargetUuid() != null) {
            arrivalTickets.remove(payload.getTargetUuid());
        }
    }

    private void sendFailure(UUID targetUuid, String targetName, String targetServer, String reason) {
        TpaCrossServerPacketPayload payload = new TpaCrossServerPacketPayload();
        payload.setSenderUuid(targetUuid);
        payload.setSenderName(targetName);
        payload.setTargetUuid(targetUuid);
        payload.setTargetName(targetName);
        payload.setTargetServer(targetServer);
        payload.setReason(reason);

        CrossServerPacket packet = new CrossServerPacket(
                getLocalServerId(),
                CrossServerPacket.Action.TPA_DENY,
                targetUuid,
                targetName,
                0.0,
                plugin.getGson().toJson(payload)
        );
        plugin.getRedisService().publish(packet);
    }

    public void sendPlayerToProxyServer(Player player, String serverName) {
        if (player == null || !player.isOnline() || serverName == null || serverName.isEmpty()) return;
        if (serverName.equalsIgnoreCase(getLocalServerId())) return;

        if (plugin.getSpawnManager() != null && plugin.getSpawnManager().getLastLocationService() != null) {
            plugin.getSpawnManager().getLastLocationService().notifyGoingTo(player, serverName);
        }
        if (plugin.getTransferFreezeService() != null) {
            plugin.getTransferFreezeService().freeze(player);
        }

        dev.lyy.friendlycore.common.ProxyTransferUtil.connect(plugin, player, serverName);
    }

    public boolean hasTicket(UUID playerUuid) {
        if (playerUuid == null) return false;
        TpaTeleportTicket ticket = arrivalTickets.get(playerUuid);
        return ticket != null && !ticket.isExpired();
    }

    public TpaTeleportTicket consumeTicket(UUID playerUuid) {
        if (playerUuid == null) return null;
        TpaTeleportTicket ticket = arrivalTickets.remove(playerUuid);
        if (ticket != null && !ticket.isExpired()) {
            return ticket;
        }
        return null;
    }

    /**
     * Ticket lifetime matches the configured TPA warmup countdown plus a buffer,
     * ensuring tickets remain valid while travelers wait out warmup countdowns.
     */
    private long ticketTimeoutMs() {
        long countdownSeconds = tpaManager.getConfig().getLong("tpa-countdown-seconds", 5L);
        return Math.max(60_000L, countdownSeconds * 1000L + 30_000L);
    }

    private boolean isWorldBlacklisted(String worldName, List<String> blacklistedWorlds) {
        if (worldName == null || blacklistedWorlds == null) return false;
        for (String w : blacklistedWorlds) {
            if (worldName.equalsIgnoreCase(w)) {
                return true;
            }
        }
        return false;
    }

    public void shutdown() {
        if (cleanupTask != null) {
            plugin.getScheduler().cancel(cleanupTask);
            cleanupTask = null;
        }
        arrivalTickets.clear();
    }
}
