package dev.lyy.friendlycore.modules.joinquit;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.joinquit.cross.JoinQuitCrossServerService;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Central manager for player join and quit message formatting, priority rank groups,
 * silent staff joins, unique join tracking, and player preferences.
 */
public class JoinQuitManager {

    private final FriendlyCore plugin;
    private final JoinQuitCrossServerService crossServerService;

    private boolean enabled;
    private boolean suppressVanilla;

    private boolean joinEnabled;
    private String joinMessage;
    private String joinSound;

    private boolean quitEnabled;
    private String quitMessage;
    private String quitSound;

    private final List<JoinQuitGroup> groups = new ArrayList<>();
    private String silentPermission;
    private String staffNotifyPermission;
    private String staffJoinNotify;
    private String staffQuitNotify;

    private final AtomicInteger uniqueJoinsCount = new AtomicInteger(0);
    private final Set<UUID> mutedPlayers = ConcurrentHashMap.newKeySet();
    private File dataFile;

    public JoinQuitManager(FriendlyCore plugin) {
        this.plugin = plugin;
        this.crossServerService = new JoinQuitCrossServerService(plugin, this);
    }

    public void initialize() {
        this.dataFile = new File(plugin.getDataFolder(), "joinquit_data.yml");
        loadData();
        loadConfiguration();
    }

    public void reload() {
        loadConfiguration();
    }

    public void shutdown() {
        if (crossServerService != null) {
            crossServerService.shutdown();
        }
        saveData();
    }

    public void loadConfiguration() {
        FileConfiguration config = plugin.getConfigService().getJoinQuitConfig();
        this.enabled = config.getBoolean("enabled", true);
        this.suppressVanilla = config.getBoolean("suppress-vanilla-messages", true);

        this.joinEnabled = config.getBoolean("join.enabled", true);
        this.joinMessage = config.getString("join.message", "<dark_gray>[<color:#00e676>+</color>]</dark_gray> <gray>%player%</gray>");
        this.joinSound = config.getString("join.sound", "none");

        this.quitEnabled = config.getBoolean("quit.enabled", true);
        this.quitMessage = config.getString("quit.message", "<dark_gray>[<color:#ff5252>-</color>]</dark_gray> <gray>%player%</gray>");
        this.quitSound = config.getString("quit.sound", "none");

        this.silentPermission = config.getString("silent.permission", "friendlycore.joinquit.silent");
        this.staffNotifyPermission = config.getString("silent.staff-notify-permission", "friendlycore.joinquit.staffnotify");
        this.staffJoinNotify = config.getString("silent.staff-join-notify", "<dark_gray>[<color:#00d2ff>Silent</color>]</dark_gray> <gray>%player% joined %server%.</gray>");
        this.staffQuitNotify = config.getString("silent.staff-quit-notify", "<dark_gray>[<color:#00d2ff>Silent</color>]</dark_gray> <gray>%player% left %server%.</gray>");

        this.groups.clear();
        ConfigurationSection groupSection = config.getConfigurationSection("groups");
        if (groupSection != null) {
            for (String key : groupSection.getKeys(false)) {
                ConfigurationSection g = groupSection.getConfigurationSection(key);
                if (g != null) {
                    String permission = g.getString("permission", "friendlycore.joinquit.group." + key);
                    int priority = g.getInt("priority", 0);
                    String joinMsg = g.getString("join-message", "");
                    String quitMsg = g.getString("quit-message", "");
                    String joinSnd = g.getString("join-sound", "none");
                    String quitSnd = g.getString("quit-sound", "none");
                    String crossJoinMsg = g.getString("cross-server-join-message", null);
                    String crossQuitMsg = g.getString("cross-server-quit-message", null);

                    groups.add(new JoinQuitGroup(key, permission, priority, joinMsg, quitMsg, joinSnd, quitSnd, crossJoinMsg, crossQuitMsg));
                }
            }
        }
        groups.sort((a, b) -> Integer.compare(b.priority(), a.priority()));

        crossServerService.loadConfiguration(config);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isSuppressVanilla() {
        return suppressVanilla;
    }

    public JoinQuitCrossServerService getCrossServerService() {
        return crossServerService;
    }

    public boolean isJoinEnabled() {
        return joinEnabled;
    }

    public boolean isQuitEnabled() {
        return quitEnabled;
    }

    public String getJoinMessage() {
        return joinMessage;
    }

    public String getJoinSound() {
        return joinSound;
    }

    public String getQuitMessage() {
        return quitMessage;
    }

    public String getQuitSound() {
        return quitSound;
    }

    public String getStaffJoinNotify() {
        return staffJoinNotify;
    }

    public String getStaffQuitNotify() {
        return staffQuitNotify;
    }

    public String getStaffNotifyPermission() {
        return staffNotifyPermission;
    }

    public JoinQuitGroup getMatchingGroup(Player player) {
        if (player == null) return null;
        for (JoinQuitGroup group : groups) {
            if (group.hasPermission() && player.hasPermission(group.permission())) {
                return group;
            }
        }
        return null;
    }

    public boolean isSilent(Player player) {
        if (player == null) return false;
        return player.hasPermission(silentPermission)
                || player.hasPermission("friendlycore.joinquit.silent.join")
                || player.hasPermission("friendlycore.joinquit.silent.quit")
                || player.hasPermission("friendlycore.admin");
    }

    public boolean isPlayerMuted(UUID uuid) {
        return mutedPlayers.contains(uuid);
    }

    public boolean togglePlayerMessages(UUID uuid) {
        if (mutedPlayers.contains(uuid)) {
            mutedPlayers.remove(uuid);
            return true;
        } else {
            mutedPlayers.add(uuid);
            return false;
        }
    }

    public int getUniqueJoinsCount() {
        return uniqueJoinsCount.get();
    }

    public int incrementUniqueJoins() {
        int val = uniqueJoinsCount.incrementAndGet();
        plugin.getScheduler().runAsync(this::saveData);
        return val;
    }

    public Map<String, String> getPlaceholders(Player player) {
        Map<String, String> map = new LinkedHashMap<>();
        if (player != null) {
            map.put("%player%", player.getName());
            map.put("%name%", player.getName());
            try {
                map.put("%displayname%", player.getDisplayName());
            } catch (Throwable ignored) {
                map.put("%displayname%", player.getName());
            }
        }
        String server = plugin.getRedisService() != null && plugin.getRedisService().getServerId() != null
                ? plugin.getRedisService().getServerId()
                : "server-1";
        map.put("%server%", server);
        map.put("%unique_joins%", String.valueOf(uniqueJoinsCount.get()));
        map.put("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()));
        map.put("%max_online%", String.valueOf(Bukkit.getMaxPlayers()));
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            map.put("%network_online%", String.valueOf(plugin.getRedisService().getOnlinePlayerNames().size()));
        } else {
            map.put("%network_online%", String.valueOf(Bukkit.getOnlinePlayers().size()));
        }
        return map;
    }

    /**
     * Broadcasts local join message and sound.
     */
    public void broadcastLocalJoin(Player player) {
        if (!enabled || !joinEnabled || player == null) return;

        Map<String, String> placeholders = getPlaceholders(player);
        JoinQuitGroup group = getMatchingGroup(player);

        String message = (group != null && !group.joinMessage().isEmpty()) ? group.joinMessage() : joinMessage;
        if (message != null && !message.isEmpty() && !message.equalsIgnoreCase("DISABLED")) {
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (!isPlayerMuted(target.getUniqueId())) {
                    plugin.getTextService().sendMessage(target, message, placeholders);
                }
            }
        }

        String snd = (group != null && !group.joinSound().equalsIgnoreCase("none")) ? group.joinSound() : joinSound;
        if (snd != null && !snd.equalsIgnoreCase("none")) {
            for (Player target : Bukkit.getOnlinePlayers()) {
                TextService.playSound(target, snd, 1.0f, 1.0f);
            }
        }
    }

    /**
     * Broadcasts local quit message and sound.
     */
    public void broadcastLocalQuit(Player player) {
        if (!enabled || !quitEnabled || player == null) return;

        Map<String, String> placeholders = getPlaceholders(player);
        JoinQuitGroup group = getMatchingGroup(player);
        UUID quittingUuid = player.getUniqueId();

        String message = (group != null && !group.quitMessage().isEmpty()) ? group.quitMessage() : quitMessage;
        if (message != null && !message.isEmpty() && !message.equalsIgnoreCase("DISABLED")) {
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (target.getUniqueId().equals(quittingUuid) || !target.isOnline()) continue;
                if (!isPlayerMuted(target.getUniqueId())) {
                    plugin.getTextService().sendMessage(target, message, placeholders);
                }
            }
        }

        String snd = (group != null && !group.quitSound().equalsIgnoreCase("none")) ? group.quitSound() : quitSound;
        if (snd != null && !snd.equalsIgnoreCase("none")) {
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (target.getUniqueId().equals(quittingUuid) || !target.isOnline()) continue;
                TextService.playSound(target, snd, 1.0f, 1.0f);
            }
        }
    }

    public void notifyStaffSilentJoin(Player player) {
        if (staffJoinNotify == null || staffJoinNotify.isEmpty() || player == null) return;
        Map<String, String> placeholders = getPlaceholders(player);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline()) continue;
            if (p.hasPermission(staffNotifyPermission) || p.hasPermission("friendlycore.admin")) {
                plugin.getTextService().sendMessage(p, staffJoinNotify, placeholders);
            }
        }
    }

    public void notifyStaffSilentQuit(Player player) {
        if (staffQuitNotify == null || staffQuitNotify.isEmpty() || player == null) return;
        Map<String, String> placeholders = getPlaceholders(player);
        UUID quittingUuid = player.getUniqueId();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(quittingUuid) || !p.isOnline()) continue;
            if (p.hasPermission(staffNotifyPermission) || p.hasPermission("friendlycore.admin")) {
                plugin.getTextService().sendMessage(p, staffQuitNotify, placeholders);
            }
        }
    }

    private synchronized void loadData() {
        if (dataFile == null || !dataFile.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        this.uniqueJoinsCount.set(config.getInt("unique-joins", 0));
        List<String> mutedList = config.getStringList("muted-players");
        for (String s : mutedList) {
            try {
                mutedPlayers.add(UUID.fromString(s));
            } catch (Exception ignored) {}
        }
    }

    public synchronized void saveData() {
        if (dataFile == null) return;
        try {
            YamlConfiguration config = new YamlConfiguration();
            config.set("unique-joins", uniqueJoinsCount.get());
            List<String> list = new ArrayList<>();
            for (UUID u : mutedPlayers) {
                list.add(u.toString());
            }
            config.set("muted-players", list);
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save joinquit data", e);
        }
    }
}
