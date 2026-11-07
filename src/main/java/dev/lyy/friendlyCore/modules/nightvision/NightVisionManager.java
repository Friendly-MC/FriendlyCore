package dev.lyy.friendlycore.modules.nightvision;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.database.DatabaseReadRetry;
import dev.lyy.friendlycore.common.TextService;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * High-performance, Folia-compatible, Java & Bedrock optimized NightVision Manager.
 */
public class NightVisionManager {

    private final FriendlyCore plugin;
    private final ConcurrentHashMap<UUID, NightVisionProfile> profiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> loadTokens = new ConcurrentHashMap<>();

    // Settings (volatile for cross-thread regional visibility)
    private volatile int potionAmplifier = 0;
    private volatile boolean potionInfinite = true;
    private volatile int potionDurationTicks = 2147483647;
    private volatile boolean potionAmbient = true;
    private volatile boolean potionParticles = false;
    private volatile boolean potionIcon = false;

    private volatile boolean bedrockStripParticles = true;
    private volatile boolean bedrockForceAmbient = true;

    private volatile boolean reapplyOnRespawn = true;
    private volatile boolean keepOnMilk = true;
    private final Set<String> disabledWorlds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private volatile boolean soundsEnabled = true;
    private volatile Sound toggleOnSound = Sound.ENTITY_PLAYER_LEVELUP;
    private volatile float toggleOnVolume = 1.0f;
    private volatile float toggleOnPitch = 1.0f;

    private volatile Sound toggleOffSound = Sound.BLOCK_FIRE_EXTINGUISH;
    private volatile float toggleOffVolume = 1.0f;
    private volatile float toggleOffPitch = 1.2f;

    private volatile Sound reloadSound = Sound.BLOCK_ANVIL_USE;
    private volatile float reloadVolume = 1.0f;
    private volatile float reloadPitch = 1.0f;

    private volatile boolean actionbarEnabled = true;
    private Object autoSaveTask = null;

    public NightVisionManager(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        loadSettings();
        importLegacyDataIfPresent();

        long intervalSeconds = plugin.getConfigService().getMainConfig().getLong("optimization.save-interval-seconds", 60L);
        this.autoSaveTask = plugin.getScheduler().runAsyncTimer(this::saveDirtyBatch, intervalSeconds * 20L, intervalSeconds * 20L);

        plugin.getLogger().info("NightVision module initialized successfully!");
    }

    public void loadSettings() {
        FileConfiguration config = plugin.getConfigService().get("nightvision.yml");

        this.potionAmplifier = config.getInt("potion.amplifier", 0);
        this.potionInfinite = config.getBoolean("potion.infinite-duration", true);
        this.potionDurationTicks = potionInfinite ? 2147483647 : config.getInt("potion.duration-ticks", 12000);
        this.potionAmbient = config.getBoolean("potion.ambient", true);
        this.potionParticles = config.getBoolean("potion.particles", false);
        this.potionIcon = config.getBoolean("potion.icon", false);

        this.bedrockStripParticles = config.getBoolean("bedrock.strip-particles", true);
        this.bedrockForceAmbient = config.getBoolean("bedrock.force-ambient", true);

        this.reapplyOnRespawn = config.getBoolean("lifecycle.reapply-on-respawn", true);
        this.keepOnMilk = config.getBoolean("lifecycle.keep-on-milk", true);

        this.disabledWorlds.clear();
        for (String w : config.getStringList("lifecycle.disabled-worlds")) {
            this.disabledWorlds.add(w.toLowerCase(Locale.ROOT));
        }

        this.soundsEnabled = config.getBoolean("sounds.enabled", true);
        this.toggleOnSound = parseSound(config.getString("sounds.toggle-on.name", "ENTITY_PLAYER_LEVELUP"), Sound.ENTITY_PLAYER_LEVELUP);
        this.toggleOnVolume = (float) config.getDouble("sounds.toggle-on.volume", 1.0);
        this.toggleOnPitch = (float) config.getDouble("sounds.toggle-on.pitch", 1.0);

        this.toggleOffSound = parseSound(config.getString("sounds.toggle-off.name", "BLOCK_FIRE_EXTINGUISH"), Sound.BLOCK_FIRE_EXTINGUISH);
        this.toggleOffVolume = (float) config.getDouble("sounds.toggle-off.volume", 1.0);
        this.toggleOffPitch = (float) config.getDouble("sounds.toggle-off.pitch", 1.2);

        this.reloadSound = parseSound(config.getString("sounds.reload.name", "BLOCK_ANVIL_USE"), Sound.BLOCK_ANVIL_USE);
        this.reloadVolume = (float) config.getDouble("sounds.reload.volume", 1.0);
        this.reloadPitch = (float) config.getDouble("sounds.reload.pitch", 1.0);

        this.actionbarEnabled = config.getBoolean("actionbar.enabled", true);
    }

    private Sound parseSound(String soundName, Sound fallback) {
        if (soundName == null || soundName.isEmpty()) return fallback;
        try {
            return Sound.valueOf(soundName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * Imports legacy data from Husly NightVision data.yml or local data.yml if database is empty.
     */
    private void importLegacyDataIfPresent() {
        plugin.getScheduler().runAsync(() -> {
            File localData = new File(plugin.getDataFolder(), "data.yml");
            File oldPluginData = new File(plugin.getDataFolder().getParentFile(), "NightVision/data.yml");

            File target = localData.exists() ? localData : (oldPluginData.exists() ? oldPluginData : null);
            if (target != null && target.exists()) {
                try {
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(target);
                    List<String> list = yaml.getStringList("enabled");
                    if (!list.isEmpty()) {
                        Map<UUID, Boolean> imported = new HashMap<>();
                        for (String s : list) {
                            try {
                                UUID uuid = UUID.fromString(s);
                                imported.put(uuid, true);
                                profiles.computeIfAbsent(uuid, k -> new NightVisionProfile(k, "LegacyUser", true));
                            } catch (IllegalArgumentException ignored) {}
                        }
                        if (!imported.isEmpty() && plugin.getDatabaseManager() != null) {
                            plugin.getDatabaseManager().saveNightVisionBatch(imported);
                            plugin.getLogger().info("Imported " + imported.size() + " NightVision player profiles from legacy data.yml!");
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to parse legacy NightVision data.yml", e);
                }
            }
        });
    }

    /**
     * Get profile from cache or create a default instance in memory.
     */
    public NightVisionProfile getProfile(UUID uuid, String username) {
        if (uuid == null) return null;
        NightVisionProfile existing = profiles.get(uuid);
        if (existing != null) {
            if (username != null) existing.setUsername(username);
            return existing;
        }
        return profiles.computeIfAbsent(uuid, k -> new NightVisionProfile(k, username, false));
    }

    public NightVisionProfile getCachedProfile(UUID uuid) {
        return uuid != null ? profiles.get(uuid) : null;
    }

    public void cancelPendingLoad(UUID uuid) {
        if (uuid != null) loadTokens.remove(uuid);
    }

    public void uncacheProfile(UUID uuid) {
        if (uuid != null) {
            loadTokens.remove(uuid);
            profiles.remove(uuid);
        }
    }

    public CompletableFuture<NightVisionProfile> loadPlayerAsync(UUID uuid, String username) {
        if (uuid == null) return CompletableFuture.completedFuture(null);
        NightVisionProfile existing = profiles.get(uuid);
        if (existing != null) {
            if (username != null) existing.setUsername(username);
            return CompletableFuture.completedFuture(existing);
        }

        if (plugin.getDatabaseManager() != null) {
            Object loadToken = new Object();
            loadTokens.put(uuid, loadToken);
            return DatabaseReadRetry.load(plugin, () -> plugin.getDatabaseManager().loadNightVision(uuid),
                () -> loadTokens.get(uuid) == loadToken).thenApply(enabled -> {
                boolean state = enabled != null && enabled;
                NightVisionProfile prof = new NightVisionProfile(uuid, username, state);
                if (loadTokens.get(uuid) != loadToken) return null;
                return profiles.compute(uuid, (key, current) ->
                    current != null && current.isDirty() ? current : prof);
            });
        }

        NightVisionProfile defaultProf = new NightVisionProfile(uuid, username, false);
        profiles.put(uuid, defaultProf);
        return CompletableFuture.completedFuture(defaultProf);
    }

    /**
     * Toggles night vision for a player.
     */
    public void toggle(Player target, org.bukkit.command.CommandSender actor) {
        if (target == null) return;
        NightVisionProfile profile = getProfile(target.getUniqueId(), target.getName());
        boolean newState = !profile.isEnabled();
        if (newState) {
            enable(target, actor, false);
        } else {
            disable(target, actor, false);
        }
    }

    /**
     * Enables night vision for a player.
     */
    public void enable(Player target, org.bukkit.command.CommandSender actor, boolean silent) {
        if (target == null) return;
        NightVisionProfile profile = getProfile(target.getUniqueId(), target.getName());

        if (profile.isEnabled()) {
            if (!silent) {
                if (actor.equals(target)) {
                    sendMessage(actor, "nightvision.already-on", "&eNight vision is already enabled.");
                } else {
                    sendMessage(actor, "nightvision.target-already-on", "&e%player% already has night vision enabled.", Map.of("player", target.getName()));
                }
            }
            return;
        }

        profile.setEnabled(true);
        applyNightVision(target);

        if (!silent) {
            if (actor.equals(target)) {
                sendMessage(target, "nightvision.toggled-on", "&aNight vision enabled.");
                if (actionbarEnabled) {
                    sendActionbar(target, "nightvision.actionbar-on", "<gradient:#00e676:#00b0ff><bold>NIGHT VISION</bold></gradient> <dark_gray>»</dark_gray> <#00e676>Enabled</#00e676>");
                }
                playToggleOnSound(target);
            } else {
                sendMessage(actor, "nightvision.toggled-on-other", "&aEnabled night vision for %player%.", Map.of("player", target.getName()));
                sendMessage(target, "nightvision.target-toggled-on", "&aNight vision enabled by %sender%.", Map.of("sender", actor.getName()));
                if (actionbarEnabled) {
                    sendActionbar(target, "nightvision.actionbar-on", "<gradient:#00e676:#00b0ff><bold>NIGHT VISION</bold></gradient> <dark_gray>»</dark_gray> <#00e676>Enabled</#00e676>");
                }
                playToggleOnSound(target);
            }
        }
    }

    /**
     * Disables night vision for a player.
     */
    public void disable(Player target, org.bukkit.command.CommandSender actor, boolean silent) {
        if (target == null) return;
        NightVisionProfile profile = getProfile(target.getUniqueId(), target.getName());

        if (!profile.isEnabled()) {
            if (!silent) {
                if (actor.equals(target)) {
                    sendMessage(actor, "nightvision.already-off", "&eNight vision is already disabled.");
                } else {
                    sendMessage(actor, "nightvision.target-already-off", "&e%player% already has night vision disabled.", Map.of("player", target.getName()));
                }
            }
            return;
        }

        profile.setEnabled(false);
        removeNightVision(target);

        if (!silent) {
            if (actor.equals(target)) {
                sendMessage(target, "nightvision.toggled-off", "&cNight vision disabled.");
                if (actionbarEnabled) {
                    sendActionbar(target, "nightvision.actionbar-off", "<gradient:#ff5252:#ff1744><bold>NIGHT VISION</bold></gradient> <dark_gray>»</dark_gray> <#ff5252>Disabled</#ff5252>");
                }
                playToggleOffSound(target);
            } else {
                sendMessage(actor, "nightvision.toggled-off-other", "&cDisabled night vision for %player%.", Map.of("player", target.getName()));
                sendMessage(target, "nightvision.target-toggled-off", "&cNight vision disabled by %sender%.", Map.of("sender", actor.getName()));
                if (actionbarEnabled) {
                    sendActionbar(target, "nightvision.actionbar-off", "<gradient:#ff5252:#ff1744><bold>NIGHT VISION</bold></gradient> <dark_gray>»</dark_gray> <#ff5252>Disabled</#ff5252>");
                }
                playToggleOffSound(target);
            }
        }
    }

    /**
     * Folia-safe entity scheduled application of Night Vision effect.
     * Tuned for Bedrock and Java players.
     */
    public void applyNightVision(Player player) {
        if (player == null || !player.isOnline()) return;

        plugin.getScheduler().runEntity(player, () -> {
            if (!player.isOnline()) return;

            if (isWorldDisabled(player.getWorld())) {
                return;
            }

            boolean isBedrock = plugin.getHookManager() != null 
                    && plugin.getHookManager().getFloodgateHook() != null 
                    && plugin.getHookManager().getFloodgateHook().isBedrockPlayer(player);

            boolean ambient = isBedrock ? (bedrockForceAmbient || potionAmbient) : potionAmbient;
            boolean particles = isBedrock ? (!bedrockStripParticles && potionParticles) : potionParticles;
            boolean icon = isBedrock ? false : potionIcon;

            PotionEffect effect = new PotionEffect(
                    PotionEffectType.NIGHT_VISION,
                    potionDurationTicks,
                    potionAmplifier,
                    ambient,
                    particles,
                    icon
            );

            player.addPotionEffect(effect);
        });
    }

    /**
     * Folia-safe removal of Night Vision effect.
     */
    public void removeNightVision(Player player) {
        if (player == null || !player.isOnline()) return;

        plugin.getScheduler().runEntity(player, () -> {
            if (player.isOnline() && player.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
                player.removePotionEffect(PotionEffectType.NIGHT_VISION);
            }
        });
    }

    public boolean isNightVisionActive(Player player) {
        if (player == null) return false;
        NightVisionProfile profile = getCachedProfile(player.getUniqueId());
        return profile != null && profile.isEnabled();
    }

    public boolean isWorldDisabled(World world) {
        if (world == null) return false;
        return disabledWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    public void playToggleOnSound(Player player) {
        if (!soundsEnabled || toggleOnSound == null || player == null || !player.isOnline()) return;
        TextService.playSound(player, toggleOnSound.name(), toggleOnVolume, toggleOnPitch);
    }

    public void playToggleOffSound(Player player) {
        if (!soundsEnabled || toggleOffSound == null || player == null || !player.isOnline()) return;
        TextService.playSound(player, toggleOffSound.name(), toggleOffVolume, toggleOffPitch);
    }

    public void playReloadSound(Player player) {
        if (!soundsEnabled || reloadSound == null || player == null || !player.isOnline()) return;
        TextService.playSound(player, reloadSound.name(), reloadVolume, reloadPitch);
    }

    private void sendMessage(org.bukkit.command.CommandSender sender, String key, String def) {
        sendMessage(sender, key, def, null);
    }

    private void sendMessage(org.bukkit.command.CommandSender sender, String key, String def, Map<String, String> placeholders) {
        String msg = plugin.getConfigService().getMessages().getString(key, def);
        plugin.getTextService().sendMessage(sender, msg, placeholders);
    }

    private void sendActionbar(Player player, String key, String def) {
        if (player == null || !player.isOnline()) return;
        String msg = plugin.getConfigService().getMessages().getString(key, def);
        plugin.getTextService().sendActionBar(player, msg, null);
    }

    /**
     * Asynchronously flush dirty player records into database with version tracking.
     */
    public void saveDirtyBatch() {
        if (plugin.getDatabaseManager() == null) return;

        List<NightVisionProfile> dirty = new ArrayList<>();
        List<Long> versions = new ArrayList<>();

        for (NightVisionProfile profile : profiles.values()) {
            if (profile.isDirty()) {
                dirty.add(profile);
                versions.add(profile.getVersion());
            }
        }

        if (dirty.isEmpty()) return;

        Map<UUID, Boolean> batch = new HashMap<>();
        for (NightVisionProfile p : dirty) {
            batch.put(p.getUuid(), p.isEnabled());
        }

        plugin.getDatabaseManager().saveNightVisionBatch(batch).thenRun(() -> {
            for (int i = 0; i < dirty.size(); i++) {
                dirty.get(i).markSaved(versions.get(i));
            }
        });
    }

    /**
     * Flush all profiles on shutdown.
     */
    public void shutdown() {
        if (autoSaveTask != null) {
            plugin.getScheduler().cancel(autoSaveTask);
        }

        if (plugin.getDatabaseManager() != null) {
            List<NightVisionProfile> dirty = new ArrayList<>();
            List<Long> versions = new ArrayList<>();

            for (NightVisionProfile profile : profiles.values()) {
                if (profile.isDirty()) {
                    dirty.add(profile);
                    versions.add(profile.getVersion());
                }
            }

            if (!dirty.isEmpty()) {
                Map<UUID, Boolean> batch = new HashMap<>();
                for (NightVisionProfile p : dirty) {
                    batch.put(p.getUuid(), p.isEnabled());
                }
                try {
                    plugin.getDatabaseManager().saveNightVisionBatch(batch).get(3, java.util.concurrent.TimeUnit.SECONDS);
                    for (int i = 0; i < dirty.size(); i++) {
                        dirty.get(i).markSaved(versions.get(i));
                    }
                } catch (Exception ignored) {}
            }
        }
        loadTokens.clear();
        profiles.clear();
    }

    public boolean isReapplyOnRespawn() {
        return reapplyOnRespawn;
    }

    public boolean isKeepOnMilk() {
        return keepOnMilk;
    }

    public boolean isActionbarEnabled() {
        return actionbarEnabled;
    }
}
