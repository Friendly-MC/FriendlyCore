package dev.lyy.friendlycore.modules.teams.manager;

import dev.lyy.friendlycore.modules.teams.TeamMessages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.teams.model.*;
import dev.lyy.friendlycore.modules.teams.storage.TeamsDao;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.milkbowl.vault.economy.EconomyResponse;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Core business logic manager for team operations.
 * <p>
 * Coordinates team lifecycles, member roles, bank economy, home waypoints,
 * distributed vault locking, ally relationships, and persistence synchronization.
 */
public class TeamsManager {

    private static final Pattern NAME_FORMATTING = Pattern.compile("[&§<>;\\p{Cntrl}]|#[0-9a-fA-F]{6}");

    private final FriendlyCore plugin;
    private final TeamsDao dao;

    private final Map<UUID, Team> teamsByUuid = new ConcurrentHashMap<>();
    private final Map<String, Team> teamsByTag = new ConcurrentHashMap<>();
    private final Map<UUID, Team> playerTeamMap = new ConcurrentHashMap<>();
    private final Map<UUID, TeamPlayerStats> playerStatsMap = new ConcurrentHashMap<>();
    private final Map<UUID, TeamMailbox> mailboxes = new ConcurrentHashMap<>();
    private final Map<String, TeamRole> roleRegistry = new ConcurrentHashMap<>();
    private final Map<String, TeamColor> colorRegistry = new ConcurrentHashMap<>();
    private final Map<UUID, VaultSession> localVaultHolders = new ConcurrentHashMap<>();
    private volatile boolean stopping;
    private volatile boolean ready;
    private final CompletableFuture<Void> loaded = new CompletableFuture<>();
    private final Set<UUID> disbanding = ConcurrentHashMap.newKeySet();

    private final Map<UUID, Long> disbandCooldowns = new ConcurrentHashMap<>();

    private int tagMaxLength = 12;
    private long tagChangeCooldownMs = 3600000L; // 1h
    private boolean tagChangeCostEnabled = false;
    private double tagChangeCostAmount = 1000.0;

    private int nameMaxLength = 12;
    private final List<String> bannedKeywords = new CopyOnWriteArrayList<>();

    private boolean returnBalanceOnDisband = true;
    private boolean defaultFriendlyFire = true;
    private boolean creationRequiresPayment = false;
    private double creationPaymentAmount = 100.0;
    private long creationCooldownMs = 10000L; // 10s

    private boolean maxYLevelEnabled = false;
    private int maxYLevel = 100;

    private double bankLimit = 1000000000.0;
    private int minMembersEnderchest = 2;
    private int minMembersHome = 2;
    private int minMembersBank = 1;
    private int maxAllies = 5;
    private long inviteLifetimeSeconds = 60L;
    private boolean bankEnabled = true;
    private boolean enderchestEnabled = true;
    private boolean homeEnabled = true;
    private boolean allyEnabled = true;
    private boolean leaderboardEnabled = true;

    private TeamRole ownerRole;
    private TeamRole defaultMemberRole;
    private Object autoSaveTask;
    private Object leaseWatchdogTask;

    public TeamsManager(FriendlyCore plugin) {
        this.plugin = plugin;
        this.dao = new TeamsDao(plugin);
    }

    public void initialize() {
        loadConfiguration();
        dao.initializeTables();

        // Asynchronously load cached stats and teams into memory
        dev.lyy.friendlycore.common.database.DatabaseReadRetry.load(plugin,
            dao::loadAllPlayerStats, () -> !stopping).thenAccept(stats -> {
            if (stopping) return;
            stats.forEach((uuid, loaded) -> playerStatsMap.merge(uuid, loaded, TeamPlayerStats::mergeLoaded));
            dev.lyy.friendlycore.common.database.DatabaseReadRetry.load(plugin,
                () -> dao.loadAllTeams(roleRegistry, defaultMemberRole, ownerRole), () -> !stopping).thenAccept(teams -> {
                if (stopping) return;
                for (Team team : teams) {
                    registerTeamInMemory(team);
                }
                if (stopping) return;
                ready = true;
                loaded.complete(null);
                plugin.getScheduler().runGlobal(() -> Bukkit.getOnlinePlayers().forEach(this::deliverPendingPayments));
                plugin.getLogger().info("Successfully loaded " + teams.size() + " teams into memory.");
            });
        });

        // Periodic asynchronous auto-save task (every 5 minutes)
        this.autoSaveTask = plugin.getScheduler().runAsyncTimer(this::saveDirtyTeams, 6000L, 6000L);

        // Watchdog for team vault lease expiration and auto-closing (every 5 seconds)
        this.leaseWatchdogTask = plugin.getScheduler().runGlobalTimer(this::watchdogVaultLeases, 100L, 100L);
    }

    public void shutdown() {
        if (autoSaveTask != null) {
            plugin.getScheduler().cancel(autoSaveTask);
            autoSaveTask = null;
        }
        if (leaseWatchdogTask != null) {
            plugin.getScheduler().cancel(leaseWatchdogTask);
            leaseWatchdogTask = null;
        }

        stopping = true;
        for (Map.Entry<UUID, VaultSession> entry : new HashMap<>(localVaultHolders).entrySet()) {
            VaultSession session = entry.getValue();
            Player player = Bukkit.getPlayer(session.playerUuid());
            Team team = getTeamByUuid(entry.getKey()).orElse(null);
            // Folia entity schedulers are no longer available during disable. Keep
            // ownership for recovery rather than reading another region's inventory.
            if (!plugin.getScheduler().isFolia() && team != null && session.isOpen()) {
                if (player != null && player.isOnline()) player.closeInventory();
                saveAndReleaseTeamVault(team, session.playerUuid());
            }
            try {
                session.pending().join();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Vault remains locked for recovery: " + entry.getKey(), e);
            }
        }

        // Force synchronous final save of dirty data during server shutdown
        saveDirtyTeamsNow();
        teamsByUuid.clear();
        teamsByTag.clear();
        playerTeamMap.clear();
        mailboxes.clear();
    }

    public void loadConfiguration() {
        FileConfiguration config = plugin.getConfigService().getTeamsConfig();

        this.inviteLifetimeSeconds = config.getLong("Plugin-Settings.invite-settings.lifetime", 60L);
        this.bankEnabled = config.getBoolean("Plugin-Settings.toggle-features.bank", true) && !config.getBoolean("Plugin-Settings.toggle-features.disable-economy", false);
        this.enderchestEnabled = config.getBoolean("Plugin-Settings.toggle-features.enderchest", true);
        this.homeEnabled = config.getBoolean("Plugin-Settings.toggle-features.home", true);
        this.allyEnabled = config.getBoolean("Plugin-Settings.toggle-features.ally", true);
        this.leaderboardEnabled = config.getBoolean("Plugin-Settings.toggle-features.leaderboard-gui", true);

        this.tagMaxLength = config.getInt("Team-Settings.team-tag.max-length", 12);
        String cooldownStr = config.getString("Team-Settings.team-tag.change-cooldown", "1h");
        this.tagChangeCooldownMs = parseDuration(cooldownStr);
        this.tagChangeCostEnabled = config.getBoolean("Team-Settings.team-tag.change-cost.enabled", false);
        this.tagChangeCostAmount = config.getDouble("Team-Settings.team-tag.change-cost.amount", 1000.0);

        this.nameMaxLength = config.getInt("Team-Settings.team-name.max-length", 12);

        bannedKeywords.clear();
        bannedKeywords.addAll(config.getStringList("Team-Settings.banned-keywords"));

        this.returnBalanceOnDisband = config.getBoolean("Team-Settings.return-balance-on-disband", true);
        this.defaultFriendlyFire = config.getBoolean("Team-Settings.default-friendly-fire-state", true);

        this.creationRequiresPayment = config.getBoolean("Team-Settings.team-creation.requires-payment", false);
        this.creationPaymentAmount = config.getDouble("Team-Settings.team-creation.payment-amount", 100.0);
        this.creationCooldownMs = config.getLong("Team-Settings.team-creation-cooldown", 10L) * 1000L;

        this.maxYLevelEnabled = config.getBoolean("Team-Settings.team-home.max-y-level-enabled", false);
        this.maxYLevel = config.getInt("Team-Settings.team-home.max-y-Level", 100);

        this.bankLimit = Math.max(0.0, config.getDouble("Team-Settings.team-bank.limit", 1000000000.0));

        // Min members required
        this.minMembersEnderchest = config.getInt("Team-Settings.min-members-required.enderchest", 2);
        this.minMembersHome = config.getInt("Team-Settings.min-members-required.home", 2);
        this.minMembersBank = config.getInt("Team-Settings.min-members-required.bank", 1);
        this.maxAllies = config.getInt("Team-Settings.max-allies", 5);

        roleRegistry.clear();
        ownerRole = null;
        ConfigurationSection rolesSec = config.getConfigurationSection("Team-Settings.team-roles");
        if (rolesSec != null) {
            for (String key : rolesSec.getKeys(false)) {
                try {
                    int priority = Integer.parseInt(key);
                    String name = rolesSec.getString(key + ".name", "Member");
                    List<String> permStrings = rolesSec.getStringList(key + ".permissions");
                    Set<RolePermission> perms = EnumSet.noneOf(RolePermission.class);
                    for (String ps : permStrings) {
                        RolePermission rp = RolePermission.fromString(ps);
                        if (rp != null) perms.add(rp);
                    }
                    TeamRole role = new TeamRole(priority, key, name, perms);
                    roleRegistry.put(key, role);
                    if (priority == -1) {
                        this.ownerRole = role;
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed parsing role key: " + key);
                }
            }
        }

        if (this.ownerRole == null) {
            this.ownerRole = new TeamRole(-1, "-1", "Leader", EnumSet.allOf(RolePermission.class));
            roleRegistry.put("-1", this.ownerRole);
        }
        this.defaultMemberRole = roleRegistry.getOrDefault("6", roleRegistry.getOrDefault("1", new TeamRole(1, "1", "Recruit", EnumSet.of(RolePermission.USE_HOME))));
        for (Team team : teamsByUuid.values()) {
            synchronized (team) {
                for (TeamMember member : team.getMemberList()) {
                    TeamRole previous = member.getRole();
                    member.setRole(team.isOwner(member.getUniqueId()) ? ownerRole
                            : roleRegistry.getOrDefault(previous != null ? previous.getKey() : "6", defaultMemberRole));
                }
            }
        }

        colorRegistry.clear();
        ConfigurationSection colorsSec = config.getConfigurationSection("Team-Settings.team-colors");
        if (colorsSec != null) {
            for (String key : colorsSec.getKeys(false)) {
                String cName = colorsSec.getString(key + ".name", key);
                String matStr = colorsSec.getString(key + ".material", "WHITE_WOOL");
                Material mat = Material.matchMaterial(matStr);
                if (mat == null) mat = Material.WHITE_WOOL;
                String colorCode = colorsSec.getString(key + ".color", "&f");
                String perm = colorsSec.getString(key + ".permission", "");
                colorRegistry.put(key.toLowerCase(), new TeamColor(key, cName, mat, colorCode, perm));
            }
        }
    }

    private long parseDuration(String str) {
        if (str == null || str.isBlank() || str.equals("0")) return 0L;
        try {
            str = str.trim().toLowerCase();
            long mult = 1000L;
            if (str.endsWith("d")) {
                mult = 86400000L;
                str = str.substring(0, str.length() - 1);
            } else if (str.endsWith("h")) {
                mult = 3600000L;
                str = str.substring(0, str.length() - 1);
            } else if (str.endsWith("m")) {
                mult = 60000L;
                str = str.substring(0, str.length() - 1);
            } else if (str.endsWith("s")) {
                mult = 1000L;
                str = str.substring(0, str.length() - 1);
            }
            return Long.parseLong(str) * mult;
        } catch (Exception e) {
            return 3600000L;
        }
    }

    public void registerTeamInMemory(Team team) {
        if (team == null) return;
        teamsByUuid.put(team.getUniqueId(), team);
        teamsByTag.put(team.getTag().toLowerCase(), team);
        for (TeamMember member : team.getMemberList()) {
            playerTeamMap.put(member.getUniqueId(), team);
        }
    }

    public void unregisterTeamFromMemory(Team team) {
        if (team == null) return;
        teamsByUuid.remove(team.getUniqueId());
        teamsByTag.remove(team.getTag().toLowerCase());
        for (TeamMember member : team.getMemberList()) {
            playerTeamMap.remove(member.getUniqueId());
        }
    }

    public boolean isBankEnabled() {
        return bankEnabled;
    }

    public boolean isEnderchestEnabled() {
        return enderchestEnabled;
    }

    public boolean isHomeEnabled() {
        return homeEnabled;
    }

    public boolean isAllyEnabled() {
        return allyEnabled;
    }

    public boolean isLeaderboardEnabled() {
        return leaderboardEnabled;
    }

    public Optional<Team> getTeamByUuid(UUID teamUuid) {
        if (teamUuid == null) return Optional.empty();
        return Optional.ofNullable(teamsByUuid.get(teamUuid));
    }

    public Optional<Team> getTeamByTag(String tag) {
        if (tag == null || tag.isBlank()) return Optional.empty();
        return Optional.ofNullable(teamsByTag.get(tag.trim().toLowerCase()));
    }

    public Optional<Team> getTeamByPlayer(UUID playerUuid) {
        if (playerUuid == null) return Optional.empty();
        return Optional.ofNullable(playerTeamMap.get(playerUuid));
    }

    public Collection<Team> getAllTeams() {
        return Collections.unmodifiableCollection(teamsByUuid.values());
    }

    public TeamPlayerStats getOrCreatePlayerStats(UUID uuid, String username) {
        return playerStatsMap.computeIfAbsent(uuid, k -> new TeamPlayerStats(k, username));
    }

    public TeamMailbox getOrCreateMailbox(UUID uuid) {
        return mailboxes.computeIfAbsent(uuid, TeamMailbox::new);
    }

    public boolean isTagValid(String tag) {
        if (tag == null || tag.isBlank()) return false;
        if (tag.length() > tagMaxLength) return false;
        String lower = tag.toLowerCase();
        for (String banned : bannedKeywords) {
            if (lower.contains(banned.toLowerCase())) return false;
        }
        return tag.matches("^[a-zA-Z0-9_]+$");
    }

    public boolean isNameValid(String name) {
        if (name == null || name.isBlank()) return false;
        if (NAME_FORMATTING.matcher(name).find()) return false;
        if (name.length() > nameMaxLength) return false;
        String lower = name.toLowerCase();
        for (String banned : bannedKeywords) {
            if (lower.contains(banned.toLowerCase())) return false;
        }
        return true;
    }

    public boolean canCreateTeam(Player player) {
        if (!ready || stopping || player == null) return false;
        Long cooldown = disbandCooldowns.get(player.getUniqueId());
        if (cooldown != null && System.currentTimeMillis() < cooldown) {
            return false;
        }
        if (cooldown != null) disbandCooldowns.remove(player.getUniqueId(), cooldown);
        return true;
    }

    public long getDisbandCooldownRemaining(Player player) {
        if (player == null) return 0L;
        Long cooldown = disbandCooldowns.get(player.getUniqueId());
        if (cooldown == null) return 0L;
        long remaining = (cooldown - System.currentTimeMillis()) / 1000L;
        if (remaining <= 0L) disbandCooldowns.remove(player.getUniqueId(), cooldown);
        return Math.max(0L, remaining);
    }

    // ponytail: one local registry lock; use database uniqueness if cross-node simultaneous creation is required.
    public synchronized Team createTeam(Player player, String tag, String name) {
        if (player == null || !isTagValid(tag) || !isNameValid(name != null ? name : tag) || !canCreateTeam(player)) {
            if (player != null) TeamMessages.send(plugin, player, "team-creation-is-unavailable-the-name-is");
            return null;
        }
        if (teamsByTag.containsKey(tag.toLowerCase())) return null;
        if (playerTeamMap.containsKey(player.getUniqueId())) return null;

        UUID teamUuid = UUID.randomUUID();
        TeamOwner owner = new TeamOwner(player.getUniqueId(), player.getName(), ownerRole, System.currentTimeMillis());
        Team team = new Team(teamUuid, name != null && !name.isBlank() ? name : tag, tag, owner);
        team.setFriendlyFireEnabled(defaultFriendlyFire);

        if (creationRequiresPayment && !charge(player, creationPaymentAmount)) return null;

        registerTeamInMemory(team);
        team.setDirty(true);
        saveDirtyTeams().thenRun(() -> {
            if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
                String payload = owner.getUniqueId() + ";" + owner.getUsername() + ";" + team.getName() + ";" + team.getTag() + ";" + team.getColorKey() + ";" + team.isFriendlyFireEnabled() + ";" + team.isClosed();
                plugin.getRedisService().publish(new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                        plugin.getRedisService().getServerId(),
                        dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.TEAM_CREATE_SYNC,
                        team.getUniqueId(),
                        team.getTag(),
                        0,
                        payload
                ));
            }
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Team creation remains unsaved: " + teamUuid, error);
            return null;
        });
        return team;
    }

    public boolean isReady() { return ready && !stopping; }

    public void whenReady(Runnable action) { loaded.thenRun(action); }

    public boolean requireReady(org.bukkit.command.CommandSender sender) {
        if (ready && !stopping) return true;
        TeamMessages.send(plugin, sender, "teams-are-still-loading-please-try-again");
        return false;
    }

    private boolean active(Team team) {
        return ready && !stopping && team != null && teamsByUuid.get(team.getUniqueId()) == team
                && !disbanding.contains(team.getUniqueId());
    }

    private boolean permitted(Team team, Player actor, RolePermission permission) {
        TeamMember member = team != null && actor != null ? team.getMember(actor.getUniqueId()) : null;
        return active(team) && member != null && member.hasPermission(permission);
    }

    public static boolean canManageMember(Team team, UUID actorUuid, UUID targetUuid, RolePermission permission) {
        if (team == null || actorUuid == null || targetUuid == null || team.isOwner(targetUuid)) return false;
        TeamMember actor = team.getMember(actorUuid), target = team.getMember(targetUuid);
        return actor != null && target != null && !actorUuid.equals(targetUuid)
                && actor.hasPermission(permission) && actor.getRole().isHigherThan(target.getRole());
    }

    private boolean charge(Player player, double amount) {
        if (amount == 0) return true;
        if (!Double.isFinite(amount) || amount < 0 || plugin.getVaultEconomy() == null) {
            TeamMessages.send(plugin, player, "the-required-team-payment-is-unavailable");
            return false;
        }
        EconomyResponse response = plugin.getVaultEconomy().withdrawPlayer(player, amount);
        if (response != null && response.transactionSuccess()) return true;
        TeamMessages.send(plugin, player, "you-could-not-pay-the-required-team", "%amount%", String.valueOf(amount));
        return false;
    }

    public CompletableFuture<Boolean> disbandTeam(Team team, org.bukkit.command.CommandSender actor) {
        boolean admin = actor != null && (actor.hasPermission("friendlycore.teams.admin") || actor.hasPermission("zelteams.admin") || actor.isOp());
        if (!active(team) || actor == null || (!admin && (!(actor instanceof Player p) || !team.isOwner(p.getUniqueId())))
                || localVaultHolders.containsKey(team.getUniqueId()) || !disbanding.add(team.getUniqueId())) {
            return CompletableFuture.completedFuture(false);
        }
        return dao.disbandTeam(team.getUniqueId(), returnBalanceOnDisband).thenApply(result -> {
            if (!result.deleted()) return false;
            team.setBankBalanceFromStorage(0);
            for (UUID allyUuid : team.getAllies()) {
                Team ally = teamsByUuid.get(allyUuid);
                if (ally != null) { ally.removeAlly(team.getUniqueId()); broadcastTeamUpdate(ally); }
            }
            if (creationCooldownMs > 0) disbandCooldowns.put(team.getOwner().getUniqueId(), System.currentTimeMillis() + creationCooldownMs);
            unregisterTeamFromMemory(team);
            if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
                plugin.getRedisService().publish(new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                        plugin.getRedisService().getServerId(), dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.TEAM_DISBAND_SYNC,
                        team.getUniqueId(), team.getTag(), 0, null));
            }
            if (result.payout() != null) {
                Player owner = Bukkit.getPlayer(result.payout().playerUuid());
                if (owner != null) deliverPendingPayments(owner);
            }
            return true;
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Could not disband team " + team.getUniqueId(), error);
            return false;
        }).whenComplete((result, error) -> {
            disbanding.remove(team.getUniqueId());
            Runnable notify = () -> TeamMessages.send(plugin, actor, Boolean.TRUE.equals(result) ? "team-disbanded-by-actor" : "team-disband-failed", "%team_tag%", team.getTag());
            if (actor instanceof Player player) plugin.getScheduler().runEntity(player, notify);
            else plugin.getScheduler().runGlobal(notify);
        });
    }

    public synchronized boolean addMember(Team team, Player player) {
        if (!active(team) || player == null) return false;
        if (playerTeamMap.containsKey(player.getUniqueId())
                || getOrCreateMailbox(player.getUniqueId()).getInvite(team.getUniqueId(), TeamInvite.InviteType.PLAYER) == null) return false;

        TeamMember member = new TeamMember(player.getUniqueId(), player.getName(), defaultMemberRole, System.currentTimeMillis());
        team.addMember(member);
        playerTeamMap.put(player.getUniqueId(), team);
        team.setDirty(true);

        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            plugin.getRedisService().publish(new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                    plugin.getRedisService().getServerId(),
                    dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.TEAM_MEMBER_ADD_SYNC,
                    team.getUniqueId(),
                    player.getName(),
                    0,
                    player.getUniqueId() + ";" + defaultMemberRole.getKey() + ";" + System.currentTimeMillis()
            ));
        }
        return true;
    }

    public synchronized boolean removeMember(Team team, Player actor, UUID playerUuid) {
        if (!active(team) || actor == null || playerUuid == null || !team.hasMember(playerUuid)) return false;
        if (!actor.getUniqueId().equals(playerUuid) && !canManageMember(team, actor.getUniqueId(), playerUuid, RolePermission.KICK)) return false;
        if (team.isOwner(playerUuid)) return false; // The leader cannot be removed; must disband or transfer leadership

        TeamMember m = team.getMember(playerUuid);
        String memberName = m != null ? m.getUsername() : "";

        team.removeMember(playerUuid);
        playerTeamMap.remove(playerUuid);
        team.setDirty(true);

        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            plugin.getRedisService().publish(new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                    plugin.getRedisService().getServerId(),
                    dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.TEAM_MEMBER_REMOVE_SYNC,
                    team.getUniqueId(),
                    memberName,
                    0,
                    playerUuid.toString()
            ));
        }
        return true;
    }

    public synchronized boolean promoteMember(Team team, Player actor, UUID playerUuid) {
        if (!active(team) || actor == null || !canManageMember(team, actor.getUniqueId(), playerUuid, RolePermission.PROMOTE)) return false;
        TeamMember member = team.getMember(playerUuid);
        if (member == null) return false;

        int currentPriority = member.getRole().getPriority();
        TeamRole nextRole = null;
        int nextDiff = Integer.MAX_VALUE;

        for (TeamRole role : roleRegistry.values()) {
            if (role.getPriority() > currentPriority && role.getPriority() != -1) {
                int diff = role.getPriority() - currentPriority;
                if (diff < nextDiff) {
                    nextDiff = diff;
                    nextRole = role;
                }
            }
        }

        if (nextRole != null && (team.isOwner(actor.getUniqueId()) || actorRoleAbove(actor, team, nextRole))) {
            member.setRole(nextRole);
            team.setDirty(true);

            if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
                plugin.getRedisService().publish(new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                        plugin.getRedisService().getServerId(),
                        dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.TEAM_MEMBER_ROLE_SYNC,
                        team.getUniqueId(),
                        member.getUsername(),
                        0,
                        playerUuid + ";" + nextRole.getKey()
                ));
            }
            return true;
        }
        return false;
    }

    public synchronized boolean demoteMember(Team team, Player actor, UUID playerUuid) {
        if (!active(team) || actor == null || !canManageMember(team, actor.getUniqueId(), playerUuid, RolePermission.DEMOTE)) return false;
        TeamMember member = team.getMember(playerUuid);
        if (member == null) return false;

        int currentPriority = member.getRole().getPriority();
        TeamRole prevRole = null;
        int prevDiff = Integer.MAX_VALUE;

        for (TeamRole role : roleRegistry.values()) {
            if (role.getPriority() < currentPriority && role.getPriority() != -1) {
                int diff = currentPriority - role.getPriority();
                if (diff < prevDiff) {
                    prevDiff = diff;
                    prevRole = role;
                }
            }
        }

        if (prevRole != null) {
            member.setRole(prevRole);
            team.setDirty(true);

            if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
                plugin.getRedisService().publish(new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                        plugin.getRedisService().getServerId(),
                        dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.TEAM_MEMBER_ROLE_SYNC,
                        team.getUniqueId(),
                        member.getUsername(),
                        0,
                        playerUuid + ";" + prevRole.getKey()
                ));
            }
            return true;
        }
        return false;
    }

    private boolean actorRoleAbove(Player actor, Team team, TeamRole role) {
        return team.getMember(actor.getUniqueId()).getRole().isHigherThan(role);
    }

    public enum BankTransactionStatus { SUCCESS, INVALID, LIMIT_EXCEEDED, INSUFFICIENT_FUNDS, ECONOMY_FAILURE, STORAGE_FAILURE }
    public record BankTransactionResult(BankTransactionStatus status, double balance) {
        public boolean success() { return status == BankTransactionStatus.SUCCESS; }
    }

    private boolean canUseBank(Player player, Team team, boolean withdrawal) {
        TeamMember member = team != null && player != null ? team.getMember(player.getUniqueId()) : null;
        return active(team) && bankEnabled && member != null && team.getMemberCount() >= minMembersBank
                && (!withdrawal || member.hasPermission(RolePermission.ACCESS_BANK));
    }

    public CompletableFuture<BankTransactionResult> depositFromPlayer(Player player, Team team, double amount) {
        if (!canUseBank(player, team, false) || !Double.isFinite(amount) || amount <= 0 || plugin.getVaultEconomy() == null) {
            return CompletableFuture.completedFuture(new BankTransactionResult(BankTransactionStatus.INVALID, team != null ? team.getBankBalance() : 0));
        }
        return dao.preparePayment(team.getUniqueId(), player.getUniqueId(), amount, "deposit-refund").thenCompose(payment -> {
            CompletableFuture<BankTransactionResult> result = new CompletableFuture<>();
            java.util.concurrent.atomic.AtomicBoolean dispatched = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable cancel = () -> {
                try {
                    dao.transitionPayment(payment.id(), "PREPARED", "CANCELLED")
                            .whenComplete((ignored, error) -> result.complete(new BankTransactionResult(BankTransactionStatus.INVALID, team.getBankBalance())));
                } catch (RuntimeException error) {
                    result.complete(new BankTransactionResult(BankTransactionStatus.STORAGE_FAILURE, team.getBankBalance()));
                }
            };
            plugin.getScheduler().runEntity(player, () -> {
                if (!dispatched.compareAndSet(false, true)) return;
                if (!canUseBank(player, team, false)) { cancel.run(); return; }
                try {
                    EconomyResponse debit = plugin.getVaultEconomy().withdrawPlayer(player, amount);
                    if (debit == null || !debit.transactionSuccess()) { cancel.run(); return; }
                    dao.mutateBank(team.getUniqueId(), amount, bankLimit).thenCompose(bank -> {
                        if (bank.success()) {
                            applyBankBalance(team, bank.balance());
                            return dao.transitionPayment(payment.id(), "PREPARED", "CANCELLED").thenApply(ignored ->
                                    new BankTransactionResult(BankTransactionStatus.SUCCESS, bank.balance()));
                        }
                        BankTransactionStatus status = bank.balance() >= 0 && bank.balance() + amount > bankLimit
                                ? BankTransactionStatus.LIMIT_EXCEEDED : BankTransactionStatus.STORAGE_FAILURE;
                        return dao.transitionPayment(payment.id(), "PREPARED", "PENDING")
                                .thenCompose(activated -> activated ? deliverPayment(player, payment) : CompletableFuture.completedFuture(false))
                                .thenApply(paid -> new BankTransactionResult(status, Math.max(0, bank.balance())));
                    }).whenComplete((answer, error) -> {
                        if (error != null) {
                            plugin.getLogger().log(Level.SEVERE, "Reconcile prepared team payment " + payment.id(), error);
                            result.complete(new BankTransactionResult(BankTransactionStatus.STORAGE_FAILURE, team.getBankBalance()));
                        } else result.complete(answer);
                    });
                } catch (RuntimeException error) {
                    plugin.getLogger().log(Level.SEVERE, "Reconcile prepared team payment " + payment.id(), error);
                    result.complete(new BankTransactionResult(BankTransactionStatus.ECONOMY_FAILURE, team.getBankBalance()));
                }
            }, () -> { if (dispatched.compareAndSet(false, true)) cancel.run(); });
            return result;
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Could not prepare team deposit", error);
            return new BankTransactionResult(BankTransactionStatus.STORAGE_FAILURE, team.getBankBalance());
        });
    }

    public CompletableFuture<BankTransactionResult> withdrawToPlayer(Player player, Team team, double amount) {
        if (!canUseBank(player, team, true) || !Double.isFinite(amount) || amount <= 0 || plugin.getVaultEconomy() == null) {
            return CompletableFuture.completedFuture(new BankTransactionResult(BankTransactionStatus.INVALID, team != null ? team.getBankBalance() : 0));
        }
        return dao.reserveWithdrawal(team.getUniqueId(), player.getUniqueId(), amount, bankLimit).thenCompose(reserved -> {
            if (!reserved.bank().success()) return CompletableFuture.completedFuture(new BankTransactionResult(
                    reserved.bank().balance() >= 0 ? BankTransactionStatus.INSUFFICIENT_FUNDS : BankTransactionStatus.STORAGE_FAILURE,
                    Math.max(0, reserved.bank().balance())));
            applyBankBalance(team, reserved.bank().balance());
            return deliverPayment(player, reserved.payout()).thenApply(paid -> new BankTransactionResult(
                    paid ? BankTransactionStatus.SUCCESS : BankTransactionStatus.ECONOMY_FAILURE, reserved.bank().balance()));
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Could not reserve team withdrawal", error);
            return new BankTransactionResult(BankTransactionStatus.STORAGE_FAILURE, team.getBankBalance());
        });
    }

    private CompletableFuture<Boolean> deliverPayment(Player player, TeamsDao.Payment payment) {
        CompletableFuture<Boolean> delivered = new CompletableFuture<>();
        dao.transitionPayment(payment.id(), "PENDING", "PAYING").whenComplete((claimed, claimError) -> {
            if (claimError != null || !Boolean.TRUE.equals(claimed)) { delivered.complete(false); return; }
            Runnable retry = () -> {
                try {
                    dao.transitionPayment(payment.id(), "PAYING", "PENDING").whenComplete((ignored, error) -> {
                        if (error != null) plugin.getLogger().log(Level.SEVERE, "Payment needs recovery: " + payment.id(), error);
                        delivered.complete(false);
                    });
                } catch (RuntimeException error) {
                    plugin.getLogger().log(Level.SEVERE, "Payment needs recovery: " + payment.id(), error);
                    delivered.complete(false);
                }
            };
            java.util.concurrent.atomic.AtomicBoolean dispatched = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                plugin.getScheduler().runEntity(player, () -> {
                    if (!dispatched.compareAndSet(false, true)) return;
                    if (!player.isOnline() || plugin.getVaultEconomy() == null) { retry.run(); return; }
                    try {
                        EconomyResponse credit = plugin.getVaultEconomy().depositPlayer(player, payment.amount());
                        if (credit == null || !credit.transactionSuccess()) { retry.run(); return; }
                        dao.transitionPayment(payment.id(), "PAYING", "PAID").whenComplete((saved, error) -> {
                            if (error != null) plugin.getLogger().log(Level.SEVERE, "Credit sent; reconcile payment " + payment.id(), error);
                            delivered.complete(error == null && Boolean.TRUE.equals(saved));
                        });
                    } catch (RuntimeException error) {
                        // An exception does not establish whether the external provider applied the credit.
                        plugin.getLogger().log(Level.SEVERE, "Reconcile uncertain team payment " + payment.id(), error);
                        delivered.complete(false);
                    }
                }, () -> { if (dispatched.compareAndSet(false, true)) retry.run(); });
            } catch (RuntimeException error) {
                if (dispatched.compareAndSet(false, true)) retry.run();
                else delivered.complete(false);
            }
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Payment needs recovery: " + payment.id(), error);
            delivered.complete(false);
            return null;
        });
        return delivered;
    }

    public CompletableFuture<Void> deliverPendingPayments(Player player) {
        return dao.loadPendingPayments(player.getUniqueId()).thenCompose(payments -> CompletableFuture.allOf(payments.stream().map(payment ->
                deliverPayment(player, payment).thenAccept(paid -> {
                    if (paid) plugin.getScheduler().runEntity(player, () -> TeamMessages.send(plugin, player, "delivered-your-pending-team-payment-of", "%amount%", String.valueOf(payment.amount())));
                })).toArray(CompletableFuture[]::new))).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Could not load pending team payments for " + player.getUniqueId(), error);
            return null;
        });
    }

    private void applyBankBalance(Team team, double balance) {
        team.setBankBalanceFromStorage(balance);
        broadcastTeamUpdate(team);
    }

    public void refreshTeamBankBalance(UUID teamUuid) {
        if (teamUuid == null) return;
        dao.loadBankBalance(teamUuid).thenAccept(balance -> {
            if (balance < 0) return;
            Team team = teamsByUuid.get(teamUuid);
            if (team != null) team.setBankBalanceFromStorage(balance);
        });
    }

    public void setFriendlyFireEnabled(Team team, Player actor, boolean enabled) {
        if (!permitted(team, actor, RolePermission.SWITCH_FRIENDLY_FIRE)) return;
        team.setFriendlyFireEnabled(enabled);
        broadcastTeamUpdate(team);
    }

    public void setClosed(Team team, boolean closed) {
        if (team == null) return;
        team.setClosed(closed);
        broadcastTeamUpdate(team);
    }

    public void broadcastTeamUpdate(Team team) {
        if (team == null) return;
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            String payload = team.getTag() + ";" + team.getColorKey() + ";" + team.isFriendlyFireEnabled() + ";" + team.isClosed() + ";" + team.getName()
                    + ";" + String.join(",", team.getAllies().stream().map(UUID::toString).toList()) + ";" + team.getLastTagChange();
            plugin.getRedisService().publish(new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                    plugin.getRedisService().getServerId(),
                    dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.TEAM_UPDATE_SYNC,
                    team.getUniqueId(),
                    team.getTag(),
                    team.getBankBalance(),
                    payload
            ));
        }
    }

    private String vaultServerId() {
        String serverId = plugin.getRedisService() != null ? plugin.getRedisService().getServerId() : null;
        return serverId == null || serverId.isBlank() ? "local" : serverId;
    }

    public record VaultOpen(VaultSession session, String snapshot) {}

    /** Returns the authoritative stored vault snapshot while holding exclusive ownership. */
    public CompletableFuture<VaultOpen> acquireTeamVault(Team team, Player player) {
        if (!active(team) || !enderchestEnabled || player == null || !player.isOnline()) return CompletableFuture.completedFuture(null);
        if (plugin.getHookManager() != null && plugin.getHookManager().getMcDataBridgeHook() != null
                && plugin.getHookManager().getMcDataBridgeHook().isPlayerLocked(player)) {
            return CompletableFuture.completedFuture(null);
        }
        VaultSession session = new VaultSession(player.getUniqueId(), vaultServerId());
        UUID teamUuid = team.getUniqueId();
        if (localVaultHolders.putIfAbsent(teamUuid, session) != null) return CompletableFuture.completedFuture(null);
        return dao.acquireVaultLease(teamUuid, session.token(), session.serverId())
                .thenCompose(acquired -> {
                    if (!acquired) {
                        localVaultHolders.remove(teamUuid, session);
                        return CompletableFuture.completedFuture(null);
                    }
                    return dao.loadVault(teamUuid).thenApply(data -> {
                        if (data == null || stopping) {
                            releaseSession(teamUuid, session);
                            return null;
                        }
                        session.ready();
                        return new VaultOpen(session, data);
                    });
                }).exceptionally(error -> {
                    // Acquisition may have committed before the connection failed.
                    // Retain the local reservation until the exact token is released.
                    plugin.getLogger().log(Level.SEVERE, "Failed to acquire vault " + teamUuid, error);
                    releaseSession(teamUuid, session);
                    return null;
                });
    }

    /** Called on the player's entity thread; validation and opening are one operation. */
    public void openTeamVault(Team team, Player player, VaultOpen opening) {
        openTeamVault(team, player, opening, null, 6);
    }

    public void openTeamVault(Team team, Player player, VaultOpen opening, Component title, int rows) {
        VaultSession session = localVaultHolders.get(team.getUniqueId());
        if (opening == null || session != opening.session() || !session.playerUuid().equals(player.getUniqueId()) || !session.isReady()) return;
        boolean syncLocked = plugin.getHookManager() != null && plugin.getHookManager().getMcDataBridgeHook() != null
                && plugin.getHookManager().getMcDataBridgeHook().isPlayerLocked(player);
        boolean admin = player.hasPermission("friendlycore.teams.admin") || player.hasPermission("zelteams.admin");
        if (!active(team) || !enderchestEnabled || !player.isOnline() || syncLocked
                || !admin && (!permitted(team, player, RolePermission.ACCESS_ENDERCHEST) || team.getMemberCount() < minMembersEnderchest)) {
            releaseTeamVault(team, opening);
            return;
        }
        team.getEnderChest().updateLayout(title, rows);
        if (!team.getEnderChest().deserialize(opening.snapshot())) {
            TeamMessages.send(plugin, player, "the-team-vault-could-not-be-loaded");
            releaseTeamVault(team, opening);
            return;
        }
        if (!session.open()) return;
        try {
            player.openInventory(team.getEnderChest().getInventory());
            if (player.getOpenInventory().getTopInventory() != team.getEnderChest().getInventory()) {
                saveAndReleaseTeamVault(team, player.getUniqueId());
            }
        } catch (RuntimeException error) {
            saveAndReleaseTeamVault(team, player.getUniqueId());
            throw error;
        }
    }

    public void releaseTeamVault(Team team, VaultOpen opening) {
        if (team == null || opening == null) return;
        VaultSession session = localVaultHolders.get(team.getUniqueId());
        if (session == opening.session() && session.beginRelease()) {
            releaseSession(team.getUniqueId(), session);
        }
    }

    private void releaseSession(UUID teamUuid, VaultSession session) {
        CompletableFuture<Void> pending = dao.releaseVaultLease(teamUuid, session.token(), session.serverId())
                .thenRun(() -> localVaultHolders.remove(teamUuid, session));
        session.pending(pending);
        pending.exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Vault remains locked; release failed for " + teamUuid, error);
            return null;
        });
    }

    public boolean isTeamVaultOpen(Team team, UUID playerUuid) {
        if (stopping || team == null || playerUuid == null) return false;
        VaultSession session = localVaultHolders.get(team.getUniqueId());
        TeamMember member = team.getMember(playerUuid);
        Player player = Bukkit.getPlayer(playerUuid);
        boolean admin = player != null && (player.hasPermission("friendlycore.teams.admin") || player.hasPermission("zelteams.admin") || player.isOp());
        return enderchestEnabled && (admin || member != null && member.hasPermission(RolePermission.ACCESS_ENDERCHEST))
                && session != null && session.playerUuid().equals(playerUuid) && session.isOpen();
    }

    public void saveAndReleaseTeamVault(Team team, UUID playerUuid) {
        if (team == null || playerUuid == null) return;
        VaultSession session = localVaultHolders.get(team.getUniqueId());
        if (session == null || !session.playerUuid().equals(playerUuid) || !session.beginSave()) return;
        try {
            // Bukkit handles the cursor on close. It belongs to the player and is
            // excluded from this chest snapshot; manually returning it races close.
            String snapshot = team.getEnderChest().serialize();
            CompletableFuture<Void> pending = CompletableFuture.supplyAsync(() -> {
                try {
                    return VaultRecoveryJournal.write(plugin.getDataFolder().toPath().resolve("teams/vault-recovery"),
                            team.getUniqueId(), session, snapshot);
                } catch (java.io.IOException error) {
                    throw new java.util.concurrent.CompletionException(error);
                }
            }, plugin.getScheduler().getAsyncExecutor()).thenCompose(journal ->
                    dao.saveVault(team.getUniqueId(), session.token(), session.serverId(), snapshot)
                    .thenCompose(saved -> {
                        if (!saved) return CompletableFuture.failedFuture(new IllegalStateException("Vault ownership lost or team missing"));
                        team.getEnderChest().setStoredSnapshot(snapshot);
                        team.getEnderChest().setDirty(false);
                        return dao.releaseVaultLease(team.getUniqueId(), session.token(), session.serverId());
                    }).thenRun(() -> {
                        localVaultHolders.remove(team.getUniqueId(), session);
                        try {
                            java.nio.file.Files.deleteIfExists(journal);
                        } catch (java.io.IOException error) {
                            plugin.getLogger().log(Level.WARNING, "Saved vault but could not remove recovery file " + journal, error);
                        }
                    }));
            session.pending(pending);
            pending.exceptionally(error -> {
                plugin.getLogger().log(Level.SEVERE, "Vault remains locked for recovery; save/release failed for " + team.getUniqueId(), error);
                return null;
            });
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.SEVERE, "Vault remains locked; snapshot failed for " + team.getUniqueId(), error);
        }
    }

    private void watchdogVaultLeases() {
        for (Map.Entry<UUID, VaultSession> entry : new HashMap<>(localVaultHolders).entrySet()) {
            VaultSession session = entry.getValue();
            // Never treat an asynchronous acquisition or a pending save as an
            // abandoned open inventory. Persistent ownership needs no renewal.
            if (!session.isOpen()) continue;
            Player player = Bukkit.getPlayer(session.playerUuid());
            Team team = getTeamByUuid(entry.getKey()).orElse(null);
            if (player == null || !player.isOnline()) {
                // An offline session without a close event needs recovery.
                continue;
            }
            plugin.getScheduler().runEntity(player, () -> {
                if (team != null && session.isOpen()
                        && player.getOpenInventory().getTopInventory() != team.getEnderChest().getInventory()) {
                    saveAndReleaseTeamVault(team, session.playerUuid());
                }
            });
        }
    }

    public void broadcastTeamHomeUpdate(Team team) {
        if (team == null) return;
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            StringBuilder sbHomes = new StringBuilder();
            for (TeamHome h : team.getHomes().values()) {
                if (sbHomes.length() > 0) sbHomes.append("||");
                sbHomes.append(h.serialize());
            }
            plugin.getRedisService().publish(new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                    plugin.getRedisService().getServerId(),
                    dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.TEAM_HOME_SYNC,
                    team.getUniqueId(),
                    team.getTag(),
                    0,
                    sbHomes.toString()
            ));
        }
    }

    public int getMaxHomes(Team team, Player player) {
        int max = team != null && team.getMaxHomes() > 0 ? team.getMaxHomes() : 5;
        FileConfiguration config = plugin.getConfigService().getTeamsConfig();
        if (config.contains("Team-Settings.homes-limit")) {
            max = config.getInt("Team-Settings.homes-limit", max);
        }
        if (player != null) {
            for (org.bukkit.permissions.PermissionAttachmentInfo pai : player.getEffectivePermissions()) {
                String perm = pai.getPermission().toLowerCase(Locale.ROOT);
                if (perm.startsWith("friendlycore.teams.homes.") || perm.startsWith("zelteams.homes.")) {
                    try {
                        String limitStr = perm.substring(perm.lastIndexOf('.') + 1);
                        int permLimit = Integer.parseInt(limitStr);
                        if (permLimit > max) {
                            max = permLimit;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return max;
    }

    public boolean canSetHome(Team team, Player player, String homeName, Location location, boolean notify) {
        if (!active(team) || !homeEnabled || player == null || location == null) return false;

        if (team.getMemberCount() < minMembersHome && !player.hasPermission("friendlycore.teams.admin")) {
            if (notify) {
                TeamMessages.send(plugin, player, "your-team-needs-at-least-members-to-2", "%min_members_home%", String.valueOf(minMembersHome));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            }
            return false;
        }

        TeamMember member = team.getMember(player.getUniqueId());
        if (member == null || !member.hasPermission(RolePermission.SET_HOME)) {
            if (notify) {
                TeamMessages.send(plugin, player, "you-do-not-have-permission-to-set");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            }
            return false;
        }

        if (homeName == null || homeName.isBlank() || !homeName.matches("^[a-zA-Z0-9_]{1,16}$")) {
            if (notify) {
                TeamMessages.send(plugin, player, "invalid-home-name-use-letters-numbers-only");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            }
            return false;
        }

        if (maxYLevelEnabled && location.getY() > maxYLevel && !player.hasPermission("friendlycore.teams.admin")) {
            if (notify) {
                TeamMessages.send(plugin, player, "home-location-y-coordinate-exceeds-maximum-allowed", "%team_y%", String.valueOf((int) location.getY()), "%max_y_level%", String.valueOf(maxYLevel));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            }
            return false;
        }

        int maxHomes = getMaxHomes(team, player);
        if (!team.getHomes().containsKey(homeName.toLowerCase(Locale.ROOT)) && team.getHomes().size() >= maxHomes) {
            if (notify) {
                TeamMessages.send(plugin, player, "team-has-reached-its-maximum-home-limit", "%max_homes%", String.valueOf(maxHomes));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            }
            return false;
        }

        return true;
    }

    public boolean canUseHome(Team team, Player player, boolean notify) {
        if (!active(team) || !homeEnabled || player == null) return false;
        if (team.getMemberCount() < minMembersHome && !player.hasPermission("friendlycore.teams.admin")) {
            if (notify) {
                TeamMessages.send(plugin, player, "your-team-needs-at-least-members-to-2", "%min_members_home%", String.valueOf(minMembersHome));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            }
            return false;
        }
        TeamMember member = team.getMember(player.getUniqueId());
        if (member == null || !member.hasPermission(RolePermission.USE_HOME)) {
            if (notify) {
                TeamMessages.send(plugin, player, "you-do-not-have-permission-to-use-3");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            }
            return false;
        }
        return true;
    }

    public boolean setTeamHome(Team team, Player player, String homeName, Location location) {
        if (!canSetHome(team, player, homeName, location, true)) {
            return false;
        }
        String server = plugin.getRedisService() != null && plugin.getRedisService().isEnabled()
                ? plugin.getRedisService().getServerId()
                : "";
        team.setHome(homeName, location, server);
        team.setDirty(true);
        saveDirtyTeams();
        broadcastTeamHomeUpdate(team);
        return true;
    }

    public boolean deleteTeamHome(Team team, Player player, String homeName) {
        if (!active(team) || !homeEnabled || player == null || homeName == null) return false;
        TeamMember member = team.getMember(player.getUniqueId());
        if (member == null || !member.hasPermission(RolePermission.DELETE_HOME)) {
            TeamMessages.send(plugin, player, "you-do-not-have-permission-to-delete");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return false;
        }
        boolean ok = team.deleteHome(homeName);
        if (ok) {
            team.setDirty(true);
            saveDirtyTeams();
            broadcastTeamHomeUpdate(team);
        }
        return ok;
    }

    public boolean renameTeamHome(Team team, Player player, String oldName, String newName) {
        if (!active(team) || !homeEnabled || player == null || oldName == null || newName == null) return false;
        TeamHome oldHome = team.getHome(oldName);
        if (oldHome == null) return false;
        Location loc = oldHome.toLocation();
        if (loc == null) return false;
        if (!canSetHome(team, player, newName, loc, true)) return false;

        team.deleteHome(oldName);
        team.setHome(newName, loc, oldHome.getServer());
        team.setDirty(true);
        saveDirtyTeams();
        broadcastTeamHomeUpdate(team);
        return true;
    }

    public synchronized boolean changeTeamTag(Team team, Player actor, String newTag) {
        if (!permitted(team, actor, RolePermission.CHANGE_TAG) || !isTagValid(newTag)) return false;
        if (team.getLastTagChange() > 0 && System.currentTimeMillis() - team.getLastTagChange() < tagChangeCooldownMs) {
            TeamMessages.send(plugin, actor, "the-team-tag-change-cooldown-is-still");
            return false;
        }
        if (teamsByTag.containsKey(newTag.toLowerCase())) return false;

        if (tagChangeCostEnabled && !charge(actor, tagChangeCostAmount)) return false;
        teamsByTag.remove(team.getTag().toLowerCase());
        team.setTag(newTag);
        team.setLastTagChange(System.currentTimeMillis());
        teamsByTag.put(newTag.toLowerCase(), team);
        team.setDirty(true);

        broadcastTeamUpdate(team);
        return true;
    }

    public synchronized boolean renameTeam(Team team, Player actor, String name) {
        if (!canRenameTeam(team, actor)) return false;
        if (name == null || name.length() > TeamNameFormat.MAX_INPUT_LENGTH
                || name.indexOf(';') >= 0 || name.chars().anyMatch(Character::isISOControl)) return false;
        String newName = name.trim();
        String plain;
        try {
            plain = TeamNameFormat.plain(newName);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        if (!isNameValid(plain) || (!newName.equals(plain) && !canFormatTeamName(actor))) return false;
        team.setName(newName);
        broadcastTeamUpdate(team);
        return true;
    }

    public boolean canRenameTeam(Team team, Player actor) {
        return permitted(team, actor, RolePermission.SET_NAME) && actor.hasPermission("friendlycore.teams.rename");
    }

    public boolean canFormatTeamName(Player actor) {
        return actor != null && actor.hasPermission("friendlycore.teams.rename.format")
                && new TeamColor("custom", "Custom", Material.NAME_TAG, "&f", "").hasPermission(actor);
    }

    public boolean changeTeamColor(Team team, Player actor, String colorKey) {
        if (!active(team) || actor == null || colorKey == null) return false;
        TeamMember member = team.getMember(actor.getUniqueId());
        if (member == null || !member.hasPermission(RolePermission.CHANGE_COLOR)) return false;
        TeamColor color = getColor(colorKey);
        if (color == null || !color.hasPermission(actor)) return false;
        team.setColorKey(color.getKey());

        broadcastTeamUpdate(team);
        return true;
    }

    public synchronized boolean sendAllyInvite(Player actor, Team team, Team targetTeam) {
        if (!allyEnabled || !permitted(team, actor, RolePermission.MANAGE_ALLIANCE) || !active(targetTeam) || team == targetTeam) return false;
        if (team.getAllies().size() >= maxAllies || targetTeam.getAllies().size() >= maxAllies) return false;
        if (team.isAlly(targetTeam.getUniqueId())) return false;

        TeamInvite invite = new TeamInvite(
                actor.getUniqueId(), actor.getName(),
                targetTeam.getOwner().getUniqueId(), targetTeam.getOwner().getUsername(),
                team.getUniqueId(), team.getTag(),
                TeamInvite.InviteType.ALLY,
                inviteLifetimeSeconds
        );

        TeamMailbox mailbox = getOrCreateMailbox(targetTeam.getOwner().getUniqueId());
        mailbox.addInvite(invite);

        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            plugin.getRedisService().publish(new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                    plugin.getRedisService().getServerId(),
                    dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.TEAM_INVITE_SYNC,
                    targetTeam.getOwner().getUniqueId(),
                    targetTeam.getOwner().getUsername(),
                    0,
                    actor.getUniqueId() + ";" + actor.getName() + ";" + team.getUniqueId() + ";" + team.getTag() + ";" + TeamInvite.InviteType.ALLY.name() + ";" + inviteLifetimeSeconds
            ));
        }
        return true;
    }

    public synchronized boolean sendPlayerInvite(Player actor, Team team, Player target) {
        if (!permitted(team, actor, RolePermission.INVITE) || target == null || playerTeamMap.containsKey(target.getUniqueId())) return false;
        TeamInvite invite = new TeamInvite(actor.getUniqueId(), actor.getName(), target.getUniqueId(), target.getName(), team.getUniqueId(), team.getTag(), TeamInvite.InviteType.PLAYER, inviteLifetimeSeconds);
        getOrCreateMailbox(target.getUniqueId()).addInvite(invite);

        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            plugin.getRedisService().publish(new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                    plugin.getRedisService().getServerId(),
                    dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.TEAM_INVITE_SYNC,
                    target.getUniqueId(),
                    target.getName(),
                    0,
                    actor.getUniqueId() + ";" + actor.getName() + ";" + team.getUniqueId() + ";" + team.getTag() + ";" + TeamInvite.InviteType.PLAYER.name() + ";" + inviteLifetimeSeconds
            ));
        }
        plugin.getScheduler().runEntity(target, () -> {
            if (!target.isOnline()) return;
            TeamMessages.send(plugin, target, "you-received-a-team-invite-from-use", "%team_tag%", team.getTag());
            target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 3.0f);
        });
        return true;
    }

    public synchronized boolean acceptAllyInvite(Team targetTeam, Player actor, UUID requestingTeamUuid) {
        Team requestingTeam = teamsByUuid.get(requestingTeamUuid);
        if (!allyEnabled || !active(requestingTeam) || !permitted(targetTeam, actor, RolePermission.MANAGE_ALLIANCE)
                || requestingTeam == targetTeam || targetTeam.isAlly(requestingTeamUuid)
                || targetTeam.getAllies().size() >= maxAllies || requestingTeam.getAllies().size() >= maxAllies
                || getOrCreateMailbox(actor.getUniqueId()).getInvite(requestingTeamUuid, TeamInvite.InviteType.ALLY) == null) return false;

        targetTeam.addAlly(requestingTeam.getUniqueId());
        requestingTeam.addAlly(targetTeam.getUniqueId());

        targetTeam.setDirty(true);
        requestingTeam.setDirty(true);

        TeamMailbox mailbox = getOrCreateMailbox(targetTeam.getOwner().getUniqueId());
        mailbox.removeInvite(requestingTeamUuid, TeamInvite.InviteType.ALLY);

        broadcastTeamUpdate(targetTeam);
        broadcastTeamUpdate(requestingTeam);
        return true;
    }

    public synchronized boolean removeAlly(Team team, org.bukkit.command.CommandSender actor, UUID otherTeamUuid) {
        boolean admin = actor != null && (actor.hasPermission("friendlycore.teams.admin") || actor.hasPermission("zelteams.admin") || actor.isOp());
        if (!active(team) || otherTeamUuid == null || (!admin && (!(actor instanceof Player p) || !allyEnabled || !permitted(team, p, RolePermission.MANAGE_ALLIANCE)))) return false;
        Team otherTeam = teamsByUuid.get(otherTeamUuid);

        team.removeAlly(otherTeamUuid);
        team.setDirty(true);
        broadcastTeamUpdate(team);

        if (otherTeam != null) {
            otherTeam.removeAlly(team.getUniqueId());
            otherTeam.setDirty(true);
            broadcastTeamUpdate(otherTeam);
        }
        return true;
    }

    public CompletableFuture<Void> saveDirtyTeams() {
        if (!ready) return CompletableFuture.completedFuture(null);
        List<Team> dirtyTeams = new ArrayList<>();
        for (Team team : teamsByUuid.values()) {
            if (team.isDirty()) {
                dirtyTeams.add(team);
            }
        }
        CompletableFuture<Void> f1 = !dirtyTeams.isEmpty() ? dao.saveTeamsBatch(dirtyTeams) : CompletableFuture.completedFuture(null);

        List<TeamPlayerStats> dirtyStats = new ArrayList<>();
        Map<UUID, UUID> playerToTeam = new HashMap<>();
        for (TeamPlayerStats stats : playerStatsMap.values()) {
            if (stats.isDirty()) {
                dirtyStats.add(stats);
                Team t = playerTeamMap.get(stats.getPlayerUuid());
                if (t != null) {
                    playerToTeam.put(stats.getPlayerUuid(), t.getUniqueId());
                }
            }
        }
        CompletableFuture<Void> f2 = !dirtyStats.isEmpty() ? dao.savePlayerStatsBatch(dirtyStats, playerToTeam) : CompletableFuture.completedFuture(null);

        return CompletableFuture.allOf(f1, f2);
    }

    public void saveDirtyTeamsNow() {
        try {
            saveDirtyTeams().join();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed saving teams synchronously on shutdown", e);
        }
    }

    public Map<UUID, Team> getTeamsByUuid() { return teamsByUuid; }
    public Map<String, Team> getTeamsByTag() { return teamsByTag; }
    public Map<UUID, Team> getPlayerTeamMap() { return playerTeamMap; }
    public Map<UUID, TeamPlayerStats> getPlayerStatsMap() { return playerStatsMap; }
    public Map<UUID, TeamMailbox> getMailboxes() { return mailboxes; }

    public TeamsDao getDao() { return dao; }
    public Map<String, TeamRole> getRoleRegistry() { return roleRegistry; }
    public Map<String, TeamColor> getColorRegistry() { return colorRegistry; }

    public TeamColor getColor(String key) {
        if (key == null) return null;
        TeamColor configured = colorRegistry.get(key.toLowerCase(Locale.ROOT));
        return configured != null ? configured : TeamColor.fromHex(key);
    }

    public List<TeamColor> getAvailableColors(Player player) {
        return colorRegistry.values().stream()
                .filter(color -> color.hasPermission(player))
                .sorted(Comparator.comparing(TeamColor::getKey))
                .toList();
    }
    public TeamRole getOwnerRole() { return ownerRole; }
    public TeamRole getDefaultMemberRole() { return defaultMemberRole; }
    public int getTagMaxLength() { return tagMaxLength; }
    public long getTagChangeCooldownMs() { return tagChangeCooldownMs; }
    public boolean isTagChangeCostEnabled() { return tagChangeCostEnabled; }
    public double getTagChangeCostAmount() { return tagChangeCostAmount; }
    public int getNameMaxLength() { return nameMaxLength; }
    public boolean isAllowColors() { return false; }
    public boolean isAllowSpecialColors() { return false; }
    public boolean isCreationRequiresPayment() { return creationRequiresPayment; }
    public double getCreationPaymentAmount() { return creationPaymentAmount; }
    public boolean isMaxYLevelEnabled() { return maxYLevelEnabled; }
    public int getMaxYLevel() { return maxYLevel; }
    public double getBankLimit() { return bankLimit; }
    public int getMinMembersEnderchest() { return minMembersEnderchest; }
    public int getMinMembersHome() { return minMembersHome; }
    public int getMinMembersBank() { return minMembersBank; }
    public int getMaxAllies() { return maxAllies; }
    public long getInviteLifetimeSeconds() { return inviteLifetimeSeconds; }
}
