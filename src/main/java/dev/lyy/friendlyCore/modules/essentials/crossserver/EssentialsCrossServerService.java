package dev.lyy.friendlycore.modules.essentials.crossserver;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.common.ProxyTransferUtil;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.modules.essentials.EssentialsManager;
import dev.lyy.friendlycore.modules.essentials.model.AdminTeleportTicket;
import dev.lyy.friendlycore.modules.essentials.model.EnderchestSession;
import dev.lyy.friendlycore.modules.essentials.model.InvseeSession;
import dev.lyy.friendlycore.modules.essentials.util.InventorySerializer;
import dev.lyy.friendlycore.modules.essentials.util.InvseeLayout;
import dev.lyy.friendlycore.modules.essentials.util.SpeedUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Coordinates cross-server Admin Essentials operations, ticket validation, and Redis Pub/Sub synchronization.
 */
public class EssentialsCrossServerService {

    private static final String TOGGLE = "TOGGLE";
    private static final long SNAPSHOT_TTL_MS = 60L * 60L * 1000L;

    /** What a remote editable view was opened with, so its save only writes slots the admin changed. */
    private record Snapshot(ItemStack[] items, long createdAt) {}

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;
    private final Gson gson = new Gson();
    private final Map<UUID, AdminTeleportTicket> arrivalTickets = new ConcurrentHashMap<>();
    // Keyed by viewer + target: two admins editing the same player must not share a baseline.
    private final Map<String, Snapshot> invseeSnapshots = new ConcurrentHashMap<>();
    private final Map<String, Snapshot> ecSnapshots = new ConcurrentHashMap<>();
    private Object ticketCleanupTask;

    public EssentialsCrossServerService(FriendlyCore plugin, EssentialsManager essentialsManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
        startCleanupTask();
    }

    public void shutdown() {
        if (ticketCleanupTask != null) {
            plugin.getScheduler().cancel(ticketCleanupTask);
            ticketCleanupTask = null;
        }
        arrivalTickets.clear();
        invseeSnapshots.clear();
        ecSnapshots.clear();
    }

    private void startCleanupTask() {
        if (ticketCleanupTask != null) {
            plugin.getScheduler().cancel(ticketCleanupTask);
        }
        this.ticketCleanupTask = plugin.getScheduler().runAsyncTimer(() -> {
            arrivalTickets.entrySet().removeIf(e -> e.getValue().isExpired());
            long cutoff = System.currentTimeMillis() - SNAPSHOT_TTL_MS;
            invseeSnapshots.values().removeIf(s -> s.createdAt() < cutoff);
            ecSnapshots.values().removeIf(s -> s.createdAt() < cutoff);
        }, 100L, 100L);
    }

    /** Drops edit baselines for a player leaving this server; their remote views can no longer be saved here. */
    public void forgetTarget(UUID targetUuid) {
        if (targetUuid == null) return;
        String suffix = ":" + targetUuid;
        invseeSnapshots.keySet().removeIf(k -> k.endsWith(suffix));
        ecSnapshots.keySet().removeIf(k -> k.endsWith(suffix));
    }

    private static String snapshotKey(UUID viewerUuid, UUID targetUuid) {
        return viewerUuid + ":" + targetUuid;
    }

    public boolean isCrossServerEnabled() {
        return plugin.getRedisService() != null && plugin.getRedisService().isEnabled()
                && essentialsManager.getConfig().getBoolean("cross-server.enabled", true);
    }

    public String getLocalServerId() {
        if (plugin.getRedisService() != null) {
            return plugin.getRedisService().getServerId();
        }
        return "server-1";
    }

    /** Case-insensitive lookup of a player online elsewhere on the network; returns their exact name. */
    public String findNetworkPlayer(String name) {
        if (name == null || !isCrossServerEnabled()) return null;
        Set<String> names = plugin.getRedisService().getNetworkOnlinePlayers();
        if (names.contains(name)) return name;
        for (String candidate : names) {
            if (candidate.equalsIgnoreCase(name)) return candidate;
        }
        return null;
    }

    public UUID getNetworkPlayerUuid(String name) {
        return plugin.getRedisService() != null ? plugin.getRedisService().getNetworkPlayerUuid(name) : null;
    }

    public Map<UUID, AdminTeleportTicket> getArrivalTickets() {
        return arrivalTickets;
    }

    public void registerArrivalTicket(AdminTeleportTicket ticket) {
        if (ticket != null && ticket.getPlayerUuid() != null) {
            arrivalTickets.put(ticket.getPlayerUuid(), ticket);
        }
    }

    public AdminTeleportTicket getArrivalTicket(UUID playerUuid) {
        return arrivalTickets.get(playerUuid);
    }

    public AdminTeleportTicket consumeArrivalTicket(UUID playerUuid) {
        return arrivalTickets.remove(playerUuid);
    }

    /**
     * Dispatches and processes incoming cross-server packets received via Redis Pub/Sub.
     */
    public void handleIncomingPacket(CrossServerPacket packet) {
        if (packet == null || packet.getAction() == null || !isCrossServerEnabled()) return;

        try {
            switch (packet.getAction()) {
                case ADMIN_GAMEMODE_SET -> handleGamemodeSet(packet);
                case ADMIN_FLY_SET -> handleFlySet(packet);
                case ADMIN_GOD_SET -> handleGodSet(packet);
                case ADMIN_HEAL -> handleHeal(packet);
                case ADMIN_FEED -> handleFeed(packet);
                case ADMIN_SPEED_SET -> handleSpeedSet(packet);
                case ADMIN_TP_REQUEST -> handleTpRequest(packet);
                case ADMIN_TP_TICKET -> handleTpReply(packet);
                case ADMIN_TPHERE -> handleTpHere(packet);
                case ADMIN_INVSEE_REQ -> handleInvseeRequest(packet);
                case ADMIN_INVSEE_RESP -> handleInvseeResponse(packet);
                case ADMIN_INVSEE_SAVE -> handleInvseeSave(packet);
                case ADMIN_EC_REQ -> handleEnderchestRequest(packet);
                case ADMIN_EC_RESP -> handleEnderchestResponse(packet);
                case ADMIN_EC_SAVE -> handleEnderchestSave(packet);
                case ADMIN_TIME_SYNC -> handleTimeSync(packet);
                case ADMIN_WEATHER_SYNC -> handleWeatherSync(packet);
                case ADMIN_CLEAR_INVENTORY -> handleClearInventory(packet);
                default -> {}
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error handling Admin cross-server packet " + packet.getAction(), e);
        }
    }

    /**
     * The target named by a packet, if they are on this server. Falls back to the exact name because
     * the sender's name-to-UUID cache can miss (entries expire after hours without a lookup).
     */
    private static Player resolveTarget(CrossServerPacket packet) {
        Player target = packet.getTargetUuid() != null ? Bukkit.getPlayer(packet.getTargetUuid()) : null;
        if (target == null && packet.getTargetName() != null) {
            target = Bukkit.getPlayerExact(packet.getTargetName());
        }
        return target != null && target.isOnline() ? target : null;
    }

    private void handleGamemodeSet(CrossServerPacket packet) {
        String modeStr = packet.getExtra();
        Player target = resolveTarget(packet);
        if (target == null || modeStr == null) return;

        GameMode mode;
        try {
            mode = GameMode.valueOf(modeStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return;
        }
        plugin.getScheduler().runEntity(target, () -> {
            target.setGameMode(mode);
            essentialsManager.sendMessage(target, "gamemode.changed-self",
                    Map.of("%gamemode%", mode.name().toLowerCase(Locale.ROOT)));
            TextService.playSound(target, "entity.experience_orb.pickup", 1.0f, 1.2f);
        });
    }

    private void handleFlySet(CrossServerPacket packet) {
        Player target = resolveTarget(packet);
        if (target == null) return;
        boolean toggle = TOGGLE.equals(packet.getExtra());

        plugin.getScheduler().runEntity(target, () -> {
            boolean enabled = toggle ? !target.getAllowFlight() : packet.getAmount() > 0.5;
            target.setAllowFlight(enabled);
            if (!enabled) target.setFlying(false);
            essentialsManager.sendMessage(target, enabled ? "fly.enabled-self" : "fly.disabled-self", Map.of());
        });
    }

    private void handleGodSet(CrossServerPacket packet) {
        Player target = resolveTarget(packet);
        if (target == null) return;
        boolean toggle = TOGGLE.equals(packet.getExtra());

        plugin.getScheduler().runEntity(target, () -> {
            UUID uuid = target.getUniqueId();
            boolean enabled = toggle ? !essentialsManager.isGodMode(uuid) : packet.getAmount() > 0.5;
            essentialsManager.setGodMode(uuid, enabled);
            if (enabled) {
                target.setHealth(target.getMaxHealth());
                target.setFoodLevel(20);
                target.setFireTicks(0);
            }
            essentialsManager.sendMessage(target, enabled ? "god.enabled-self" : "god.disabled-self", Map.of());
        });
    }

    private void handleHeal(CrossServerPacket packet) {
        Player target = resolveTarget(packet);
        if (target == null) return;

        plugin.getScheduler().runEntity(target, () -> {
            target.setHealth(target.getMaxHealth());
            target.setFoodLevel(20);
            target.setSaturation(20.0f);
            target.setFireTicks(0);
            target.setRemainingAir(target.getMaximumAir());
            for (PotionEffect effect : target.getActivePotionEffects()) {
                target.removePotionEffect(effect.getType());
            }
            essentialsManager.sendMessage(target, "heal.healed-self", Map.of());
            TextService.playSound(target, "entity.player.levelup", 1.0f, 1.5f);
        });
    }

    private void handleFeed(CrossServerPacket packet) {
        Player target = resolveTarget(packet);
        if (target == null) return;

        plugin.getScheduler().runEntity(target, () -> {
            target.setFoodLevel(20);
            target.setSaturation(20.0f);
            essentialsManager.sendMessage(target, "feed.fed-self", Map.of());
            TextService.playSound(target, "entity.generic.eat", 1.0f, 1.0f);
        });
    }

    private void handleSpeedSet(CrossServerPacket packet) {
        Player target = resolveTarget(packet);
        if (target == null) return;

        JsonObject obj = gson.fromJson(packet.getExtra(), JsonObject.class);
        if (obj == null) return;

        JsonElement flyElement = obj.get("isFly");
        if (flyElement == null || flyElement.isJsonNull()) return;
        boolean isFly = flyElement.getAsBoolean();
        float userSpeed = (float) packet.getAmount();

        plugin.getScheduler().runEntity(target, () -> {
            boolean bypass = target.hasPermission("friendlycore.speed.bypass");
            float maxSpeed = bypass ? 1.0f : (isFly ? essentialsManager.getMaxFlySpeed() : essentialsManager.getMaxWalkSpeed());
            float bukkitSpeed = SpeedUtil.calculateBukkitSpeed(userSpeed, isFly, maxSpeed);
            if (isFly) {
                target.setFlySpeed(bukkitSpeed);
                essentialsManager.sendMessage(target, "speed.fly-changed-self",
                        Map.of("%speed%", String.valueOf(userSpeed)));
            } else {
                target.setWalkSpeed(bukkitSpeed);
                essentialsManager.sendMessage(target, "speed.walk-changed-self",
                        Map.of("%speed%", String.valueOf(userSpeed)));
            }
        });
    }

    private void handleTpRequest(CrossServerPacket packet) {
        JsonObject req = gson.fromJson(packet.getExtra(), JsonObject.class);
        if (req == null) return;

        UUID adminUuid = readUuid(req, "adminUuid");
        if (adminUuid == null) return;
        String adminName = readString(req, "adminName");
        String finalAdminName = adminName != null ? adminName : "Admin";

        Player target = resolveTarget(packet);
        if (target == null) return;

        plugin.getScheduler().runEntity(target, () -> {
            if (!target.isOnline()) return;
            Location loc = target.getLocation();
            registerArrivalTicket(new AdminTeleportTicket(
                    adminUuid,
                    finalAdminName,
                    target.getUniqueId(),
                    target.getName(),
                    loc.getWorld().getName(),
                    loc.getX(),
                    loc.getY(),
                    loc.getZ(),
                    loc.getYaw(),
                    loc.getPitch()
            ));

            // Send reply ticket back to admin's origin server
            JsonObject reply = new JsonObject();
            reply.addProperty("adminUuid", adminUuid.toString());
            reply.addProperty("adminName", finalAdminName);
            reply.addProperty("targetServer", getLocalServerId());
            reply.addProperty("targetName", target.getName());
            reply.addProperty("world", loc.getWorld().getName());
            reply.addProperty("x", loc.getX());
            reply.addProperty("y", loc.getY());
            reply.addProperty("z", loc.getZ());
            reply.addProperty("yaw", loc.getYaw());
            reply.addProperty("pitch", loc.getPitch());

            plugin.getRedisService().publish(new CrossServerPacket(
                    getLocalServerId(),
                    CrossServerPacket.Action.ADMIN_TP_TICKET,
                    adminUuid,
                    finalAdminName,
                    0.0,
                    gson.toJson(reply)
            ));
        });
    }

    private void handleTpReply(CrossServerPacket packet) {
        JsonObject reply = gson.fromJson(packet.getExtra(), JsonObject.class);
        if (reply == null) return;

        UUID adminUuid = readUuid(reply, "adminUuid");
        if (adminUuid == null) return;
        Player admin = Bukkit.getPlayer(adminUuid);
        if (admin == null || !admin.isOnline()) return;

        String targetServer = readString(reply, "targetServer");
        String targetName = readString(reply, "targetName");
        if (targetServer == null || targetName == null) return;

        // Transfer admin to target server via proxy plugin messaging
        plugin.getScheduler().runEntity(admin, () -> {
            if (!admin.isOnline()) return;
            connectToServer(admin, targetServer);
            essentialsManager.sendMessage(admin, "teleport.connecting-server",
                    Map.of("%player%", targetName, "%server%", targetServer));
        });
    }

    private void handleTpHere(CrossServerPacket packet) {
        // Admin on remote server wants a player (or everyone) on this server to come to them
        JsonObject req = gson.fromJson(packet.getExtra(), JsonObject.class);
        if (req == null) return;

        String adminServer = packet.getSourceServer();
        String adminName = readString(req, "adminName");
        String finalAdminName = adminName != null ? adminName : "Admin";

        JsonElement all = req.get("all");
        if (all != null && !all.isJsonNull() && all.getAsBoolean()) {
            for (Player target : Bukkit.getOnlinePlayers()) {
                summon(target, finalAdminName, adminServer);
            }
            return;
        }

        Player target = resolveTarget(packet);
        if (target != null) summon(target, finalAdminName, adminServer);
    }

    private void summon(Player target, String adminName, String adminServer) {
        plugin.getScheduler().runEntity(target, () -> {
            if (!target.isOnline()) return;
            essentialsManager.sendMessage(target, "teleport.teleported-to-admin", Map.of("%admin%", adminName));
            connectToServer(target, adminServer);
        });
    }

    private void handleInvseeRequest(CrossServerPacket packet) {
        Player target = resolveTarget(packet);
        if (target == null) return;

        JsonObject req = gson.fromJson(packet.getExtra(), JsonObject.class);
        if (req == null) return;

        UUID viewerUuid = readUuid(req, "viewerUuid");
        if (viewerUuid == null) return;
        boolean editable = readBoolean(req, "editable");
        boolean bypass = readBoolean(req, "bypass");

        plugin.getScheduler().runEntity(target, () -> {
            JsonObject resp = new JsonObject();
            resp.addProperty("viewerUuid", viewerUuid.toString());
            resp.addProperty("targetUuid", target.getUniqueId().toString());
            resp.addProperty("targetName", target.getName());
            resp.addProperty("targetServer", getLocalServerId());

            if (target.hasPermission("friendlycore.invsee.prevent") && !bypass) {
                resp.addProperty("denied", true);
            } else {
                ItemStack[] allItems = InvseeLayout.fromPlayer(target.getInventory());
                if (editable) {
                    invseeSnapshots.put(snapshotKey(viewerUuid, target.getUniqueId()),
                            new Snapshot(allItems, System.currentTimeMillis()));
                }
                resp.addProperty("editable", editable);
                resp.addProperty("inventoryData", InventorySerializer.toBase64(allItems));
                resp.addProperty("health", target.getHealth());
                resp.addProperty("maxHealth", target.getMaxHealth());
                resp.addProperty("foodLevel", target.getFoodLevel());
                resp.addProperty("gameMode", target.getGameMode().name());
            }

            plugin.getRedisService().publish(new CrossServerPacket(
                    getLocalServerId(),
                    CrossServerPacket.Action.ADMIN_INVSEE_RESP,
                    viewerUuid,
                    viewerUuid.toString(),
                    0.0,
                    gson.toJson(resp)
            ));
        });
    }

    private void handleInvseeResponse(CrossServerPacket packet) {
        // Every server receives this broadcast; skip parsing the inventory unless the viewer is here.
        if (packet.getTargetUuid() != null && Bukkit.getPlayer(packet.getTargetUuid()) == null) return;

        JsonObject resp = gson.fromJson(packet.getExtra(), JsonObject.class);
        if (resp == null) return;

        UUID viewerUuid = readUuid(resp, "viewerUuid");
        if (viewerUuid == null) return;
        Player viewer = Bukkit.getPlayer(viewerUuid);
        if (viewer == null || !viewer.isOnline()) return;

        UUID targetUuid = readUuid(resp, "targetUuid");
        String targetName = readString(resp, "targetName");
        String targetServer = readString(resp, "targetServer");
        if (targetUuid == null || targetName == null || targetServer == null) return;
        if (readBoolean(resp, "denied")) {
            essentialsManager.sendMessage(viewer, "invsee.exempt", Map.of("%player%", targetName));
            return;
        }
        boolean editable = readBoolean(resp, "editable");
        String base64 = readString(resp, "inventoryData");
        if (base64 == null) return;

        ItemStack[] items = InventorySerializer.fromBase64(base64);
        if (items.length < InvseeLayout.SIZE) return;

        plugin.getScheduler().runEntity(viewer, () -> {
            Inventory inv = InvseeLayout.createGui(
                    Messages.component(plugin, "essentials", editable ? "invsee-title-editable" : "invsee-title-read-only", "%player%", targetName),
                    Messages.component(plugin, "essentials", "armor-equipment"),
                    Messages.component(plugin, "essentials", "server", "%player%", targetName, "%target_server%", targetServer),
                    items);

            InvseeSession session = new InvseeSession(
                    viewer.getUniqueId(),
                    targetUuid,
                    targetName,
                    InvseeSession.SessionType.CROSS_SERVER,
                    targetServer,
                    editable,
                    inv
            );
            // Open first: opening closes the previous inventory, and that close event drops the viewer's session.
            if (viewer.openInventory(inv) != null) {
                essentialsManager.registerInvseeSession(viewer.getUniqueId(), session);
            }
        });
    }

    private void handleInvseeSave(CrossServerPacket packet) {
        Player target = resolveTarget(packet);
        if (target == null) return;

        JsonObject save = gson.fromJson(packet.getExtra(), JsonObject.class);
        if (save == null) return;

        UUID viewerUuid = readUuid(save, "viewerUuid");
        String base64 = readString(save, "inventoryData");
        if (viewerUuid == null || base64 == null) return;
        ItemStack[] items = InventorySerializer.fromBase64(base64);
        if (items.length < InvseeLayout.SIZE) return;

        Snapshot snapshot = invseeSnapshots.remove(snapshotKey(viewerUuid, target.getUniqueId()));
        if (snapshot == null) {
            // Without the opening state we can't tell admin edits from stale slots; never overwrite blindly.
            plugin.getLogger().warning("Discarded remote invsee save for " + target.getName() + ": no matching open view.");
            return;
        }

        plugin.getScheduler().runEntity(target, () -> {
            if (!target.isOnline()) return;
            PlayerInventory inv = target.getInventory();
            int conflicts = InvseeLayout.applyEdits(snapshot.items(), items,
                    i -> InvseeLayout.get(inv, i), (i, item) -> InvseeLayout.set(inv, i, item));
            if (conflicts > 0) {
                plugin.getLogger().info("Remote invsee save for " + target.getName() + " skipped "
                        + conflicts + " slot(s) the player changed meanwhile.");
            }
            target.updateInventory();
            essentialsManager.syncFromTargetToGuis(target);
        });
    }

    private void handleEnderchestRequest(CrossServerPacket packet) {
        Player target = resolveTarget(packet);
        if (target == null) return;

        JsonObject req = gson.fromJson(packet.getExtra(), JsonObject.class);
        if (req == null) return;

        UUID viewerUuid = readUuid(req, "viewerUuid");
        if (viewerUuid == null) return;
        boolean editable = readBoolean(req, "editable");
        boolean bypass = readBoolean(req, "bypass");

        plugin.getScheduler().runEntity(target, () -> {
            JsonObject resp = new JsonObject();
            resp.addProperty("viewerUuid", viewerUuid.toString());
            resp.addProperty("targetUuid", target.getUniqueId().toString());
            resp.addProperty("targetName", target.getName());
            resp.addProperty("targetServer", getLocalServerId());

            if (target.hasPermission("friendlycore.enderchest.prevent") && !bypass) {
                resp.addProperty("denied", true);
            } else {
                ItemStack[] contents = InvseeLayout.copyAll(target.getEnderChest().getContents());
                if (editable) {
                    ecSnapshots.put(snapshotKey(viewerUuid, target.getUniqueId()),
                            new Snapshot(contents, System.currentTimeMillis()));
                }
                resp.addProperty("editable", editable);
                resp.addProperty("enderData", InventorySerializer.toBase64(contents));
            }

            plugin.getRedisService().publish(new CrossServerPacket(
                    getLocalServerId(),
                    CrossServerPacket.Action.ADMIN_EC_RESP,
                    viewerUuid,
                    target.getName(),
                    0.0,
                    gson.toJson(resp)
            ));
        });
    }

    private void handleEnderchestResponse(CrossServerPacket packet) {
        UUID viewerUuid = packet.getTargetUuid();
        if (viewerUuid == null) return;

        Player viewer = Bukkit.getPlayer(viewerUuid);
        if (viewer == null || !viewer.isOnline()) return;

        JsonObject resp = gson.fromJson(packet.getExtra(), JsonObject.class);
        if (resp == null) return;

        UUID targetUuid = readUuid(resp, "targetUuid");
        String targetName = readString(resp, "targetName");
        String targetServer = readString(resp, "targetServer");
        if (targetUuid == null || targetName == null || targetServer == null) return;
        if (readBoolean(resp, "denied")) {
            essentialsManager.sendMessage(viewer, "general.no-permission", Map.of());
            return;
        }
        boolean editable = readBoolean(resp, "editable");
        String base64 = readString(resp, "enderData");
        if (base64 == null) return;

        ItemStack[] items = InventorySerializer.fromBase64(base64);
        if (items.length < 27) return;

        plugin.getScheduler().runEntity(viewer, () -> {
            Inventory inv = Bukkit.createInventory(null, 27,
                    Messages.component(plugin, "essentials", "remote-s-ender-chest", "%player%", targetName));
            inv.setContents(items);

            EnderchestSession session = new EnderchestSession(
                    viewer.getUniqueId(),
                    targetUuid,
                    targetName,
                    EnderchestSession.SessionType.CROSS_SERVER,
                    targetServer,
                    editable,
                    inv
            );
            // Open first: opening closes the previous inventory, and that close event drops the viewer's session.
            if (viewer.openInventory(inv) != null) {
                essentialsManager.registerEnderchestSession(viewer.getUniqueId(), session);
            }
        });
    }

    private void handleEnderchestSave(CrossServerPacket packet) {
        Player target = resolveTarget(packet);
        if (target == null) return;

        JsonObject save = gson.fromJson(packet.getExtra(), JsonObject.class);
        if (save == null) return;

        UUID viewerUuid = readUuid(save, "viewerUuid");
        String base64 = readString(save, "enderData");
        if (viewerUuid == null || base64 == null) return;
        ItemStack[] items = InventorySerializer.fromBase64(base64);
        // A full 27-slot payload is required; corrupt/legacy data must never
        // clear a player's ender chest.
        if (items.length < 27) return;

        Snapshot snapshot = ecSnapshots.remove(snapshotKey(viewerUuid, target.getUniqueId()));
        if (snapshot == null) {
            plugin.getLogger().warning("Discarded remote ender chest save for " + target.getName() + ": no matching open view.");
            return;
        }

        plugin.getScheduler().runEntity(target, () -> {
            if (!target.isOnline()) return;
            Inventory ec = target.getEnderChest();
            InvseeLayout.applyEdits(snapshot.items(), items, ec::getItem, ec::setItem);
            essentialsManager.syncFromTargetEcToGuis(target);
        });
    }

    private void handleTimeSync(CrossServerPacket packet) {
        long timeTicks = (long) packet.getAmount();
        boolean add = "ADD".equalsIgnoreCase(packet.getExtra());

        plugin.getScheduler().runGlobal(() -> {
            for (World world : Bukkit.getWorlds()) {
                long currentTime = world.getTime();
                if (!add) {
                    currentTime -= currentTime % 24000L;
                }
                world.setTime(currentTime + (add ? 0L : 24000L) + timeTicks);
            }
        });
    }

    private void handleWeatherSync(CrossServerPacket packet) {
        boolean isStorm = packet.getAmount() > 0.5;
        JsonObject obj = gson.fromJson(packet.getExtra(), JsonObject.class);
        int duration = obj != null && obj.has("duration") ? obj.get("duration").getAsInt() : 6000;

        plugin.getScheduler().runGlobal(() -> {
            for (World world : Bukkit.getWorlds()) {
                world.setStorm(isStorm);
                world.setWeatherDuration(duration);
            }
        });
    }

    private void handleClearInventory(CrossServerPacket packet) {
        Player target = resolveTarget(packet);
        if (target == null) return;

        JsonObject obj = gson.fromJson(packet.getExtra(), JsonObject.class);
        if (obj == null) return;

        String itemFilterStr = readString(obj, "item");
        int amount = obj.has("amount") ? obj.get("amount").getAsInt() : -1;
        boolean includeArmor = readBoolean(obj, "includeArmor");
        boolean armorOnly = readBoolean(obj, "armorOnly");
        boolean bypass = readBoolean(obj, "bypass");

        Material filterMaterial = null;
        if (itemFilterStr != null) {
            filterMaterial = Material.matchMaterial(itemFilterStr);
            // An unknown filter must not widen into a full clear.
            if (filterMaterial == null) return;
        }

        final Material finalMaterial = filterMaterial;
        plugin.getScheduler().runEntity(target, () -> {
            if (!bypass && (target.hasPermission("friendlycore.clearinventory.bypass")
                    || target.hasPermission("essentials.clearinventory.bypass"))) {
                return;
            }
            int cleared = essentialsManager.clearInventory(target, finalMaterial, amount, includeArmor, armorOnly);
            if (finalMaterial != null) {
                essentialsManager.sendMessage(target, "clear.cleared-item-self",
                        Map.of("%amount%", String.valueOf(cleared), "%item%", finalMaterial.name().toLowerCase(Locale.ROOT)));
            } else if (includeArmor) {
                essentialsManager.sendMessage(target, "clear.cleared-self-armor",
                        Map.of("%count%", String.valueOf(cleared)));
            } else {
                essentialsManager.sendMessage(target, "clear.cleared-self",
                        Map.of("%count%", String.valueOf(cleared)));
            }
        });
    }

    private void publish(CrossServerPacket.Action action, UUID targetUuid, String targetName, double amount, String extra) {
        if (!isCrossServerEnabled()) return;
        plugin.getRedisService().publish(new CrossServerPacket(getLocalServerId(), action, targetUuid, targetName, amount, extra));
    }

    public void dispatchGamemode(UUID targetUuid, String targetName, GameMode mode) {
        publish(CrossServerPacket.Action.ADMIN_GAMEMODE_SET, targetUuid, targetName, 0.0, mode.name());
    }

    /** @param enabled the new state, or null to toggle on the target's server */
    public void dispatchFly(UUID targetUuid, String targetName, Boolean enabled) {
        // Toggle sends amount 1 so servers that predate TOGGLE still enable, as they always did.
        publish(CrossServerPacket.Action.ADMIN_FLY_SET, targetUuid, targetName,
                enabled == null || enabled ? 1.0 : 0.0, enabled == null ? TOGGLE : null);
    }

    /** @param enabled the new state, or null to toggle on the target's server */
    public void dispatchGod(UUID targetUuid, String targetName, Boolean enabled) {
        publish(CrossServerPacket.Action.ADMIN_GOD_SET, targetUuid, targetName,
                enabled == null || enabled ? 1.0 : 0.0, enabled == null ? TOGGLE : null);
    }

    public void dispatchHeal(UUID targetUuid, String targetName) {
        publish(CrossServerPacket.Action.ADMIN_HEAL, targetUuid, targetName, 0.0, null);
    }

    public void dispatchFeed(UUID targetUuid, String targetName) {
        publish(CrossServerPacket.Action.ADMIN_FEED, targetUuid, targetName, 0.0, null);
    }

    public void dispatchSpeed(UUID targetUuid, String targetName, boolean isFly, float speed) {
        JsonObject obj = new JsonObject();
        obj.addProperty("isFly", isFly);
        publish(CrossServerPacket.Action.ADMIN_SPEED_SET, targetUuid, targetName, speed, gson.toJson(obj));
    }

    public void dispatchTeleportToPlayer(Player admin, String targetPlayerName, UUID targetUuid) {
        JsonObject req = new JsonObject();
        req.addProperty("adminUuid", admin.getUniqueId().toString());
        req.addProperty("adminName", admin.getName());
        publish(CrossServerPacket.Action.ADMIN_TP_REQUEST, targetUuid, targetPlayerName, 0.0, gson.toJson(req));
    }

    public void dispatchTeleportPlayerHere(Player admin, String targetPlayerName, UUID targetUuid) {
        if (!isCrossServerEnabled()) return;
        registerArrivalTicket(ticketTo(admin, targetUuid, targetPlayerName));

        JsonObject req = new JsonObject();
        req.addProperty("adminName", admin.getName());
        req.addProperty("adminUuid", admin.getUniqueId().toString());
        publish(CrossServerPacket.Action.ADMIN_TPHERE, targetUuid, targetPlayerName, 0.0, gson.toJson(req));
    }

    /**
     * Summons every player on every other server with one broadcast instead of one packet per player
     * (which overflowed the publish queue on large networks). Arrival tickets are local, so they are
     * registered here for each known remote UUID.
     */
    public void dispatchTeleportAllHere(Player admin) {
        if (!isCrossServerEnabled()) return;
        for (UUID uuid : plugin.getRedisService().getNetworkOnlineUuids()) {
            if (!uuid.equals(admin.getUniqueId()) && Bukkit.getPlayer(uuid) == null) {
                registerArrivalTicket(ticketTo(admin, uuid, null));
            }
        }

        JsonObject req = new JsonObject();
        req.addProperty("adminName", admin.getName());
        req.addProperty("adminUuid", admin.getUniqueId().toString());
        req.addProperty("all", true);
        // A null target makes servers without "all" support ignore the packet.
        publish(CrossServerPacket.Action.ADMIN_TPHERE, null, null, 0.0, gson.toJson(req));
    }

    private static AdminTeleportTicket ticketTo(Player admin, UUID playerUuid, String playerName) {
        Location loc = admin.getLocation();
        return new AdminTeleportTicket(playerUuid, playerName, admin.getUniqueId(), admin.getName(),
                loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    public void dispatchInvseeRequest(Player admin, String targetPlayerName, UUID targetUuid, boolean editable) {
        JsonObject req = new JsonObject();
        req.addProperty("viewerUuid", admin.getUniqueId().toString());
        req.addProperty("editable", editable);
        req.addProperty("bypass", admin.hasPermission("friendlycore.invsee.bypass"));
        publish(CrossServerPacket.Action.ADMIN_INVSEE_REQ, targetUuid, targetPlayerName, 0.0, gson.toJson(req));
    }

    public void dispatchInvseeSave(UUID viewerUuid, UUID targetUuid, String targetName, ItemStack[] items) {
        if (items == null) return;
        JsonObject save = new JsonObject();
        save.addProperty("viewerUuid", viewerUuid.toString());
        save.addProperty("inventoryData", InventorySerializer.toBase64(items));
        publish(CrossServerPacket.Action.ADMIN_INVSEE_SAVE, targetUuid, targetName, 0.0, gson.toJson(save));
    }

    public void dispatchEnderchestRequest(Player admin, String targetPlayerName, UUID targetUuid, boolean editable) {
        JsonObject req = new JsonObject();
        req.addProperty("viewerUuid", admin.getUniqueId().toString());
        req.addProperty("editable", editable);
        req.addProperty("bypass", admin.hasPermission("friendlycore.enderchest.bypass"));
        publish(CrossServerPacket.Action.ADMIN_EC_REQ, targetUuid, targetPlayerName, 0.0, gson.toJson(req));
    }

    public void dispatchEnderchestSave(UUID viewerUuid, UUID targetUuid, String targetName, ItemStack[] items) {
        if (items == null) return;
        JsonObject save = new JsonObject();
        save.addProperty("viewerUuid", viewerUuid.toString());
        save.addProperty("enderData", InventorySerializer.toBase64(items));
        publish(CrossServerPacket.Action.ADMIN_EC_SAVE, targetUuid, targetName, 0.0, gson.toJson(save));
    }

    public void broadcastTimeSync(long timeTicks, boolean isAdd) {
        publish(CrossServerPacket.Action.ADMIN_TIME_SYNC, null, null, (double) timeTicks, isAdd ? "ADD" : "SET");
    }

    public void broadcastWeatherSync(boolean isStorm, int durationTicks) {
        JsonObject obj = new JsonObject();
        obj.addProperty("duration", durationTicks);
        publish(CrossServerPacket.Action.ADMIN_WEATHER_SYNC, null, null, isStorm ? 1.0 : 0.0, gson.toJson(obj));
    }

    /** @param bypassExempt whether the sender may clear players holding the clear-bypass permission */
    public void dispatchClearInventory(UUID targetUuid, String targetName, String itemFilter, int amount,
                                       boolean includeArmor, boolean armorOnly, boolean bypassExempt) {
        JsonObject obj = new JsonObject();
        if (itemFilter != null) {
            obj.addProperty("item", itemFilter);
        }
        obj.addProperty("amount", amount);
        obj.addProperty("includeArmor", includeArmor);
        obj.addProperty("armorOnly", armorOnly);
        obj.addProperty("bypass", bypassExempt);
        publish(CrossServerPacket.Action.ADMIN_CLEAR_INVENTORY, targetUuid, targetName, 0.0, gson.toJson(obj));
    }

    public void connectToServer(Player player, String serverName) {
        if (player == null || !player.isOnline() || serverName == null) return;
        if (plugin.getSpawnManager() != null && plugin.getSpawnManager().getLastLocationService() != null) {
            plugin.getSpawnManager().getLastLocationService().notifyGoingTo(player, serverName);
        }
        if (plugin.getTransferFreezeService() != null) {
            plugin.getTransferFreezeService().freeze(player);
        }
        ProxyTransferUtil.connect(plugin, player, serverName);
    }

    private static UUID readUuid(JsonObject obj, String key) {
        JsonElement element = obj == null ? null : obj.get(key);
        if (element == null || element.isJsonNull()) return null;
        try {
            return UUID.fromString(element.getAsString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String readString(JsonObject obj, String key) {
        JsonElement element = obj == null ? null : obj.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static boolean readBoolean(JsonObject obj, String key) {
        JsonElement element = obj == null ? null : obj.get(key);
        return element != null && !element.isJsonNull() && element.getAsBoolean();
    }
}
