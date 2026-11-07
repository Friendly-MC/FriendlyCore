package dev.lyy.friendlycore.modules.teams.storage;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ConfigService;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.common.database.DatabaseManager;
import dev.lyy.friendlycore.common.hooks.HookManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsChatManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsManager;
import dev.lyy.friendlycore.modules.teams.command.TeamTabCompleter;
import dev.lyy.friendlycore.modules.teams.command.TeamCommand;
import dev.lyy.friendlycore.modules.teams.hook.TeamsPlaceholderExpansion;
import dev.lyy.friendlycore.modules.teams.model.*;
import dev.lyy.friendlycore.modules.orders.input.OrderInputManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class TeamsReviewFixTest {
    private Team team(UUID owner) {
        TeamRole leader = new TeamRole(-1, "-1", "Leader", EnumSet.allOf(RolePermission.class));
        return new Team(UUID.randomUUID(), "Test", "TEST", new TeamOwner(owner, "Owner", leader, 1));
    }

    private Connection database(boolean mysql) throws SQLException {
        Connection conn = DriverManager.getConnection(mysql ? "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL" : "jdbc:sqlite::memory:");
        TeamsDao.initializeTables(conn, mysql);
        return conn;
    }

    private String value(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void withdrawalReservesMoneyAndPaymentTogetherAndClaimsOnlyOnce(boolean mysql) throws Exception {
        try (Connection conn = database(mysql)) {
            Team team = team(UUID.randomUUID()); team.setBankBalance(100);
            TeamsDao.saveTeams(conn, mysql, List.of(team));
            TeamsDao.Withdrawal result = TeamsDao.reserveWithdrawal(conn, team.getUniqueId(), team.getOwner().getUniqueId(), 60, 1000);
            assertTrue(result.bank().success()); assertEquals(40, result.bank().balance());
            assertFalse(TeamsDao.reserveWithdrawal(conn, team.getUniqueId(), team.getOwner().getUniqueId(), 60, 1000).bank().success());
            assertEquals("1", value(conn, "SELECT COUNT(*) FROM friendlycore_team_payments"));
            assertTrue(TeamsDao.transitionPayment(conn, result.payout().id(), "PENDING", "PAYING"));
            assertFalse(TeamsDao.transitionPayment(conn, result.payout().id(), "PENDING", "PAYING"));
            assertTrue(TeamsDao.transitionPayment(conn, result.payout().id(), "PAYING", "PAID"));
            assertFalse(TeamsDao.transitionPayment(conn, result.payout().id(), "PENDING", "PAYING"));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void disbandUsesStoredBalanceAndTombstoneStopsStaleSavesAndVaultOpens(boolean mysql) throws Exception {
        try (Connection conn = database(mysql)) {
            Team team = team(UUID.randomUUID()); team.setBankBalance(100);
            TeamsDao.saveTeams(conn, mysql, List.of(team));
            conn.createStatement().executeUpdate("UPDATE friendlycore_teams SET balance=70");
            TeamsDao.Disband result = TeamsDao.disbandTeam(conn, mysql, team.getUniqueId(), true);
            assertTrue(result.deleted()); assertEquals(70, result.payout().amount());
            assertFalse(TeamsDao.disbandTeam(conn, mysql, team.getUniqueId(), true).deleted());
            team.setName("stale save"); TeamsDao.saveTeams(conn, mysql, List.of(team));
            assertEquals("0", value(conn, "SELECT COUNT(*) FROM friendlycore_teams"));
            assertEquals("1", value(conn, "SELECT COUNT(*) FROM friendlycore_team_payments"));
            assertFalse(TeamsDao.acquireVaultLease(conn, mysql, team.getUniqueId(), UUID.randomUUID(), "server"));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failedDisbandRollsBackMoneyDeletionAndTombstone(boolean mysql) throws Exception {
        try (Connection conn = database(mysql)) {
            Team team = team(UUID.randomUUID()); team.setBankBalance(90);
            TeamsDao.saveTeams(conn, mysql, List.of(team));
            conn.createStatement().execute("DROP TABLE friendlycore_team_payments");
            assertThrows(SQLException.class, () -> TeamsDao.disbandTeam(conn, mysql, team.getUniqueId(), true));
            assertEquals(90, Double.parseDouble(value(conn, "SELECT balance FROM friendlycore_teams")));
            conn.setAutoCommit(false);
            assertTrue(TeamsDao.lockTeamState(conn, mysql, team.getUniqueId())); conn.rollback(); conn.setAutoCommit(true);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void disbandRefusesAnOwnedVault(boolean mysql) throws Exception {
        try (Connection conn = database(mysql)) {
            Team team = team(UUID.randomUUID()); TeamsDao.saveTeams(conn, mysql, List.of(team));
            assertTrue(TeamsDao.acquireVaultLease(conn, mysql, team.getUniqueId(), UUID.randomUUID(), "server"));
            assertFalse(TeamsDao.disbandTeam(conn, mysql, team.getUniqueId(), false).deleted());
            assertEquals("1", value(conn, "SELECT COUNT(*) FROM friendlycore_teams"));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void metadataPreservesRawLastRowAndCorruptMigrationSnapshotsWithoutInventoryReads(boolean mysql) throws Exception {
        try (Connection conn = database(mysql)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(bytes)) {
                data.writeInt(1); data.writeInt(53); data.writeInt(1); data.writeByte(0);
            }
            for (String raw : List.of(Base64.getEncoder().encodeToString(bytes.toByteArray()), "corrupt-preserved-for-recovery!")) {
                Team team = team(UUID.randomUUID()); team.getEnderChest().setStoredSnapshot(raw);
                assertEquals(54, team.getEnderChest().getSize());
                assertThrows(IllegalStateException.class, () -> team.getEnderChest().serialize());
                TeamsDao.saveTeams(conn, mysql, List.of(team));
                assertEquals(raw, value(conn, "SELECT enderchest_data FROM friendlycore_teams WHERE uuid='" + team.getUniqueId() + "'"));
            }
        }
    }

    private Connection mutateDuringBatch(Connection conn, Runnable mutation) {
        return proxy(Connection.class, (object, method, args) -> {
            try {
                Object result = method.invoke(conn, args);
                if (!method.getName().equals("prepareStatement")) return result;
                PreparedStatement statement = (PreparedStatement) result;
                return proxy(PreparedStatement.class, (ignored, call, arguments) -> {
                    if (call.getName().equals("executeBatch")) mutation.run();
                    try { return call.invoke(statement, arguments); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
            } catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void savesDoNotClearMutationsAfterBindingTeamOrPlayerSnapshots(boolean mysql) throws Exception {
        try (Connection conn = database(mysql)) {
            Team team = team(UUID.randomUUID());
            TeamsDao.saveTeams(mutateDuringBatch(conn, () -> team.setName("newer")), mysql, List.of(team));
            assertTrue(team.isDirty()); assertEquals("Test", value(conn, "SELECT name FROM friendlycore_teams"));
            TeamsDao.saveTeams(conn, mysql, List.of(team));
            assertFalse(team.isDirty()); assertEquals("newer", value(conn, "SELECT name FROM friendlycore_teams"));
            TeamPlayerStats stats = new TeamPlayerStats(team.getOwner().getUniqueId(), "Owner"); stats.incrementKills();
            TeamsDao.savePlayerStats(mutateDuringBatch(conn, stats::incrementKills), mysql, List.of(stats), Map.of());
            assertTrue(stats.isDirty()); assertTrue(value(conn, "SELECT stats_data FROM friendlycore_team_players").startsWith("1;"));
            TeamsDao.savePlayerStats(conn, mysql, List.of(stats), Map.of());
            assertFalse(stats.isDirty()); assertTrue(value(conn, "SELECT stats_data FROM friendlycore_team_players").startsWith("2;"));
        }
    }

    @Test
    void remoteUpdatesPreservePendingLocalChangesAndDoNotDirtyCleanReplicas() {
        Team team = team(UUID.randomUUID()); team.setDirty(false);
        team.applyRemoteUpdate(() -> team.setColorKey("red")); assertFalse(team.isDirty());
        team.setName("local"); long revision = team.getRevision();
        team.applyRemoteUpdate(() -> team.setColorKey("blue"));
        assertTrue(team.isDirty()); assertEquals(revision, team.getRevision()); assertEquals("local", team.getName());
        team.markSaved(revision); assertFalse(team.isDirty());
    }

    @Test
    void legacyManagementPermissionsCannotPromoteSelfOrManagePeersOrLeaders() {
        UUID owner = UUID.randomUUID(), officer = UUID.randomUUID(), peer = UUID.randomUUID(), recruit = UUID.randomUUID();
        Team team = team(owner);
        TeamRole management = new TeamRole(8, "8", "Officer", EnumSet.of(RolePermission.MANAGE_MEMBERS, RolePermission.SET_NAME));
        assertTrue(management.hasPermission(RolePermission.INVITE)); assertTrue(management.hasPermission(RolePermission.CHANGE_TAG));
        team.addMember(new TeamMember(officer, "officer", management, 1));
        team.addMember(new TeamMember(peer, "peer", management, 1));
        team.addMember(new TeamMember(recruit, "recruit", new TeamRole(1, "1", "Recruit", Set.of()), 1));
        assertTrue(TeamsManager.canManageMember(team, officer, recruit, RolePermission.KICK));
        assertFalse(TeamsManager.canManageMember(team, recruit, officer, RolePermission.PROMOTE));
        assertFalse(TeamsManager.canManageMember(team, officer, officer, RolePermission.PROMOTE));
        assertFalse(TeamsManager.canManageMember(team, officer, peer, RolePermission.DEMOTE));
        assertFalse(TeamsManager.canManageMember(team, officer, owner, RolePermission.KICK));
        team.removeMember(officer);
        assertFalse(TeamsManager.canManageMember(team, officer, recruit, RolePermission.KICK));
    }

    @Test
    void startupMergeKeepsStatsCollectedBeforeLoading() {
        TeamPlayerStats early = new TeamPlayerStats(UUID.randomUUID(), "Current"); early.incrementKills();
        TeamPlayerStats loaded = TeamPlayerStats.deserialize(early.getPlayerUuid(), "Old", "20;3;4;5;6;7;8");
        early.mergeLoaded(loaded);
        assertEquals(21, early.getKills()); assertEquals(3, early.getDeaths()); assertTrue(early.isDirty());
        assertEquals("Current", early.getUsername());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static void set(Object object, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }

    private static class Config extends ConfigService {
        final YamlConfiguration settings = new YamlConfiguration();
        private Config() { super(null); }
        @Override public FileConfiguration getMainConfig() { return settings; }
        @Override public FileConfiguration getTeamsConfig() { return settings; }
        @Override public FileConfiguration get(String fileName) { return MESSAGES; }
    }

    private static final YamlConfiguration MESSAGES = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
            TeamsReviewFixTest.class.getClassLoader().getResourceAsStream("modules/teams/messages.yml"),
            java.nio.charset.StandardCharsets.UTF_8));

    private static class Hooks extends HookManager {
        Economy economy;
        private Hooks() { super(null); }
        @Override public Economy getVaultEconomy() { return economy; }
    }

    private class Runtime implements AutoCloseable {
        final FriendlyCore plugin;
        final FriendlyScheduler scheduler;
        final TeamsManager manager;
        final YamlConfiguration config;
        final String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        final AtomicReference<Double> credits = new AtomicReference<>(0.0);
        final AtomicReference<Double> debits = new AtomicReference<>(0.0);
        final UUID playerUuid = UUID.randomUUID();
        final Set<String> permissions = new HashSet<>();

        Runtime() throws Exception {
            plugin = allocate(FriendlyCore.class);
            Config service = allocate(Config.class);
            config = new YamlConfiguration(); config.set("database.type", "MYSQL");
            set(service, Config.class, "settings", config);
            set(plugin, FriendlyCore.class, "configService", service);
            set(plugin, FriendlyCore.class, "textService", new TextService());
            scheduler = new FriendlyScheduler(proxy(Plugin.class, (object, method, args) -> method.getName().equals("isEnabled") ? true : null));
            set(scheduler, FriendlyScheduler.class, "isFolia", true);
            set(plugin, FriendlyCore.class, "scheduler", scheduler);
            set(plugin, FriendlyCore.class, "databaseManager", proxy(DatabaseManager.class, (object, method, args) -> {
                if (method.getName().equals("getConnection")) return DriverManager.getConnection(url);
                throw new UnsupportedOperationException(method.getName());
            }));
            Hooks hooks = allocate(Hooks.class);
            hooks.economy = proxy(Economy.class, (object, method, args) -> {
                if (method.getName().equals("withdrawPlayer") || method.getName().equals("depositPlayer")) {
                    double amount = (double) args[1];
                    (method.getName().equals("depositPlayer") ? credits : debits).updateAndGet(balance -> balance + amount);
                    return new EconomyResponse(amount, 1000, EconomyResponse.ResponseType.SUCCESS, null);
                }
                throw new UnsupportedOperationException(method.getName());
            });
            set(plugin, FriendlyCore.class, "hookManager", hooks);
            manager = new TeamsManager(plugin); manager.loadConfiguration();
            set(manager, TeamsManager.class, "ready", true);
            try (Connection conn = DriverManager.getConnection(url)) { TeamsDao.initializeTables(conn, true); }
        }

        Player player(java.util.function.Function<Consumer<ScheduledTask>, ScheduledTask> schedule) {
            EntityScheduler entity = proxy(EntityScheduler.class, (object, method, args) -> schedule.apply((Consumer<ScheduledTask>) args[1]));
            return proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> playerUuid;
                case "getName" -> "Owner";
                case "getPlayer" -> object;
                case "getScheduler" -> entity;
                case "isOnline", "isValid" -> true;
                case "hasPermission" -> permissions.contains(args[0]);
                case "sendMessage", "playSound", "getLocation", "closeInventory" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        Player livePlayer() {
            return player(callback -> {
                ScheduledTask task = proxy(ScheduledTask.class, (object, method, args) -> null);
                callback.accept(task); return task;
            });
        }

        Team bank(double balance) throws Exception {
            Team team = team(playerUuid); team.setBankBalance(balance); manager.registerTeamInMemory(team);
            try (Connection conn = DriverManager.getConnection(url)) { TeamsDao.saveTeams(conn, true, List.of(team)); }
            return team;
        }

        @Override public void close() { scheduler.getAsyncExecutor().shutdownNow(); }
    }

    @Test
    void colorSelectionAndCompletionRequireCurrentPermissionsAndRole() throws Exception {
        try (Runtime runtime = new Runtime()) {
            Team team = team(runtime.playerUuid);
            runtime.manager.registerTeamInMemory(team);
            runtime.manager.getColorRegistry().put("blue", new TeamColor("blue", "Blue", Material.BLUE_WOOL, "&9", ""));
            runtime.manager.getColorRegistry().put("red", new TeamColor("red", "Red", Material.RED_WOOL, "&c", "custom.red"));
            Player player = runtime.livePlayer();
            assertTrue(runtime.manager.getAvailableColors(player).isEmpty());
            assertFalse(runtime.manager.changeTeamColor(team, player, "blue"));
            runtime.permissions.add("friendlycore.teams.color.blue");
            assertEquals(List.of("blue"), runtime.manager.getAvailableColors(player).stream().map(TeamColor::getKey).toList());
            long revision = team.getRevision();
            assertTrue(runtime.manager.changeTeamColor(team, player, "BLUE"));
            assertEquals("blue", team.getColorKey());
            assertEquals(revision + 1, team.getRevision());
            Command command = new Command("team") {
                @Override public boolean execute(CommandSender sender, String label, String[] args) { return true; }
            };
            TeamTabCompleter completer = new TeamTabCompleter(runtime.manager);
            assertEquals(List.of("blue"), completer.onTabComplete(player, command, "team", new String[]{"colors", ""}));
            runtime.permissions.clear();
            assertFalse(runtime.manager.changeTeamColor(team, player, "blue"), "Revoked permission must be checked at selection time");
            assertEquals(List.of(), completer.onTabComplete(player, command, "team", new String[]{"color", ""}));
            runtime.permissions.add("friendlycore.teams.color.*");
            Team restricted = team(UUID.randomUUID());
            restricted.addMember(new TeamMember(runtime.playerUuid, "Recruit", new TeamRole(1, "1", "Recruit", Set.of()), 1));
            runtime.manager.registerTeamInMemory(restricted);
            assertFalse(runtime.manager.changeTeamColor(restricted, player, "red"));
            assertEquals(List.of(), completer.onTabComplete(player, command, "team", new String[]{"color", ""}));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"friendlycore.teams.color.blue", "zelteams.color.blue", "custom.blue", "friendlycore.teams.color.*", "zelteams.color.*"})
    void colorAcceptsCanonicalLegacyCustomAndWildcardPermissions(String permission) throws Exception {
        try (Runtime runtime = new Runtime()) {
            TeamColor color = new TeamColor("blue", "Blue", Material.BLUE_WOOL, "&9", "custom.blue");
            assertFalse(color.hasPermission(null));
            assertFalse(color.hasPermission(runtime.livePlayer()));
            runtime.permissions.add(permission);
            assertTrue(color.hasPermission(runtime.livePlayer()));
        }
    }

    @Test
    void operatorFlagDoesNotBypassDeniedColorPermissions() {
        Player player = proxy(Player.class, (object, method, args) -> method.getName().equals("isOp"));
        assertFalse(new TeamColor("white", "White", Material.WHITE_WOOL, "&f", "").hasPermission(player));
    }

    @ParameterizedTest
    @ValueSource(strings = {"&9", "&#123456", "<color:#123456>", "<gradient:#123456:#abcdef>"})
    void coloredNamePlaceholderRendersSupportedFormatsAndResets(String colorCode) throws Exception {
        try (Runtime runtime = new Runtime()) {
            Team team = team(runtime.playerUuid);
            team.setColorKey("white");
            team.setName("Test");
            runtime.manager.registerTeamInMemory(team);
            runtime.manager.getColorRegistry().put("white", new TeamColor("white", "White", Material.WHITE_WOOL, colorCode, ""));
            TeamsPlaceholderExpansion expansion = new TeamsPlaceholderExpansion(runtime.plugin, runtime.manager, null);
            String display = expansion.onRequest(runtime.livePlayer(), "team_name_colored");
            assertEquals("Test", PlainTextComponentSerializer.plainText().serialize(LegacyComponentSerializer.legacySection().deserialize(display)));
            assertTrue(display.startsWith(colorCode.equals("&9") ? "§9" : "§x§1§2§3§4§5§6"), display);
            assertTrue(display.endsWith("§r"));
            runtime.manager.getColorRegistry().clear();
            assertEquals("§x§0§0§b§1§f§fTest§r", expansion.onRequest(runtime.livePlayer(), "team_name_colored"));
            runtime.manager.unregisterTeamFromMemory(team);
            assertEquals("", expansion.onRequest(runtime.livePlayer(), "team_name_colored"));
        }
    }

    @Test
    void defaultNameIsBlueWithoutColorPermissionAndSurvivesPersistence() throws Exception {
        try (Runtime runtime = new Runtime()) {
            Team team = team(runtime.playerUuid);
            runtime.manager.registerTeamInMemory(team);
            Player player = runtime.livePlayer();
            TeamsPlaceholderExpansion expansion = new TeamsPlaceholderExpansion(runtime.plugin, runtime.manager, null);
            assertEquals("#00b1ff", team.getColorKey());
            assertEquals("§x§0§0§b§1§f§fTest§r", expansion.onRequest(player, "team_name_colored"));
            assertFalse(runtime.manager.changeTeamColor(team, player, "#123456"));
            runtime.permissions.add("friendlycore.teams.color.*");
            assertEquals("§x§0§0§b§1§f§fTest§r", expansion.onRequest(player, "team_name_colored"));
            runtime.manager.saveDirtyTeams().get(5, TimeUnit.SECONDS);
            try (Connection conn = DriverManager.getConnection(runtime.url)) {
                assertEquals("#00b1ff", value(conn, "SELECT color_key FROM friendlycore_teams"));
            }
        }
    }

    @Test
    void customHexSelectionNeedsPermissionButDisplayIsVisibleToEveryone() throws Exception {
        try (Runtime runtime = new Runtime()) {
            Team team = team(runtime.playerUuid);
            runtime.manager.registerTeamInMemory(team);
            Player player = runtime.livePlayer();
            TeamsPlaceholderExpansion expansion = new TeamsPlaceholderExpansion(runtime.plugin, runtime.manager, null);
            assertFalse(runtime.manager.changeTeamColor(team, player, "#12ABEF"));
            runtime.permissions.add("friendlycore.teams.color.blue");
            assertFalse(runtime.manager.changeTeamColor(team, player, "#12ABEF"));
            runtime.permissions.add("friendlycore.teams.color.custom");
            assertTrue(runtime.manager.changeTeamColor(team, player, "#12ABEF"));
            assertEquals("#12abef", team.getColorKey());
            assertEquals("<color:#12abef>", runtime.manager.getColor(team.getColorKey()).getColorCode());
            assertEquals("§x§1§2§a§b§e§fTest§r", expansion.onRequest(player, "team_name_colored"));
            runtime.manager.saveDirtyTeams().get(5, TimeUnit.SECONDS);
            try (Connection conn = DriverManager.getConnection(runtime.url)) {
                assertEquals("#12abef", value(conn, "SELECT color_key FROM friendlycore_teams"));
            }
            runtime.permissions.clear();
            assertEquals("§x§1§2§a§b§e§fTest§r", expansion.onRequest(player, "team_name_colored"));
            assertFalse(runtime.manager.changeTeamColor(team, player, "#abcdef"));
            runtime.permissions.add("zelteams.color.custom");
            assertTrue(runtime.manager.changeTeamColor(team, player, "#abcdef"));
            org.bukkit.OfflinePlayer offline = proxy(org.bukkit.OfflinePlayer.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> runtime.playerUuid;
                case "getName" -> "Owner";
                case "getPlayer" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            assertEquals("§x§a§b§c§d§e§fTest§r", expansion.onRequest(offline, "team_name_colored"));
        }
    }

    @Test
    void renameRequiresPermissionAndPersistsWithoutChangingTagOrColor() throws Exception {
        try (Runtime runtime = new Runtime()) {
            Team team = team(runtime.playerUuid);
            runtime.manager.registerTeamInMemory(team);
            Player player = runtime.livePlayer();
            assertFalse(runtime.manager.renameTeam(team, player, "Blue Wolves"));
            runtime.permissions.add("friendlycore.teams.color.custom");
            assertTrue(runtime.manager.changeTeamColor(team, player, "#123456"));
            runtime.permissions.add("friendlycore.teams.rename");
            long revision = team.getRevision();
            assertTrue(runtime.manager.renameTeam(team, player, " Blue Wolves "));
            assertEquals(revision + 1, team.getRevision());
            assertEquals("Blue Wolves", team.getName());
            assertEquals("TEST", team.getTag());
            assertEquals("#123456", team.getColorKey());
            assertSame(team, runtime.manager.getTeamByTag("TEST").orElseThrow());
            TeamsPlaceholderExpansion expansion = new TeamsPlaceholderExpansion(runtime.plugin, runtime.manager, null);
            assertEquals("§x§1§2§3§4§5§6Blue Wolves§r", expansion.onRequest(player, "team_name_colored"));
            runtime.manager.saveDirtyTeams().get(5, TimeUnit.SECONDS);
            try (Connection conn = DriverManager.getConnection(runtime.url)) {
                assertEquals("Blue Wolves", value(conn, "SELECT name FROM friendlycore_teams"));
                assertEquals("#123456", value(conn, "SELECT color_key FROM friendlycore_teams"));
            }
            runtime.permissions.remove("friendlycore.teams.rename");
            assertFalse(runtime.manager.renameTeam(team, player, "New Name"));
            assertEquals("Blue Wolves", team.getName());
        }
    }

    @Test
    void renameCommandAcceptsMultiwordNamesAndRequiresTeamRole() throws Exception {
        try (Runtime runtime = new Runtime()) {
            Team team = team(UUID.randomUUID());
            TeamMember member = new TeamMember(runtime.playerUuid, "Member", new TeamRole(1, "1", "Recruit", Set.of()), 1);
            team.addMember(member);
            runtime.manager.registerTeamInMemory(team);
            runtime.permissions.add("friendlycore.teams.rename");
            Player player = runtime.livePlayer();
            Command command = new Command("team") {
                @Override public boolean execute(CommandSender sender, String label, String[] args) { return true; }
            };
            AtomicReference<Consumer<String>> pending = new AtomicReference<>();
            AtomicReference<String> initial = new AtomicReference<>();
            OrderInputManager input = new OrderInputManager(runtime.plugin) {
                @Override public void openTextInput(Player target, String title, String label, String value, int maxLength,
                                                     Consumer<String> success, Runnable cancel) {
                    initial.set(value); pending.set(success);
                }
            };
            TeamCommand executor = new TeamCommand(runtime.plugin, runtime.manager, null, null, null, null, input);
            assertTrue(executor.onCommand(player, command, "team", new String[]{"rename", "Blue", "Wolves"}));
            assertEquals("Test", team.getName());
            assertNull(pending.get());
            member.setRole(new TeamRole(8, "8", "Officer", Set.of(RolePermission.SET_NAME)));
            assertTrue(executor.onCommand(player, command, "team", new String[]{"rename", "Blue", "Wolves"}));
            assertEquals("Blue Wolves", initial.get());
            assertEquals("Test", team.getName());
            pending.get().accept("Blue Wolves");
            assertEquals("Blue Wolves", team.getName());
            assertTrue(executor.onCommand(player, command, "team", new String[]{"rename"}));
            assertEquals("Blue Wolves", initial.get());
            runtime.permissions.clear();
            pending.get().accept("Changed");
            assertEquals("Blue Wolves", team.getName());
            Team otherTeam = team(UUID.randomUUID());
            runtime.manager.registerTeamInMemory(otherTeam);
            assertFalse(runtime.manager.renameTeam(otherTeam, player, "Other Team"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "Name Too Long For Default", "&4Name", "§cName", "#123456Name", "<red>Name", "&lName", "Name;TAG", "Name\n", "Name\u0001"})
    void renameRejectsInvalidNamesWithoutMutatingTeam(String name) throws Exception {
        try (Runtime runtime = new Runtime()) {
            Team team = team(runtime.playerUuid);
            runtime.manager.registerTeamInMemory(team);
            runtime.permissions.add("friendlycore.teams.rename");
            long revision = team.getRevision();
            assertFalse(runtime.manager.renameTeam(team, runtime.livePlayer(), name));
            assertEquals("Test", team.getName());
            assertEquals(revision, team.getRevision());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"#fff", "#12345g", "#1234567", "#123456<red>", "&#123456", "&4", "<red>"})
    void customColorRejectsAnythingExceptSixHexDigits(String input) throws Exception {
        try (Runtime runtime = new Runtime()) {
            Team team = team(runtime.playerUuid);
            runtime.manager.registerTeamInMemory(team);
            runtime.permissions.add("friendlycore.teams.color.*");
            assertNull(TeamColor.fromHex(input));
            assertFalse(runtime.manager.changeTeamColor(team, runtime.livePlayer(), input));
            assertEquals(Team.DEFAULT_COLOR, team.getColorKey());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"&#12ABEF&lWolf", "§cWolf", "<color:#12abef><bold>Wolf", "<gradient:#123456:#abcdef>Wolf</gradient>", "<rainbow>Wolf</rainbow>", "&oWolf"})
    void formattedRenameRequiresBothPermissionsAndPersistsVisibleLength(String name) throws Exception {
        try (Runtime runtime = new Runtime()) {
            Team team = team(runtime.playerUuid);
            runtime.manager.registerTeamInMemory(team);
            Player player = runtime.livePlayer();
            runtime.permissions.add("friendlycore.teams.rename");
            assertFalse(runtime.manager.renameTeam(team, player, name));
            runtime.permissions.add("friendlycore.teams.rename.format");
            assertFalse(runtime.manager.renameTeam(team, player, name));
            runtime.permissions.add("friendlycore.teams.color.custom");
            assertTrue(runtime.manager.renameTeam(team, player, name));
            TeamsPlaceholderExpansion expansion = new TeamsPlaceholderExpansion(runtime.plugin, runtime.manager, null);
            String display = expansion.onRequest(player, "team_name_colored");
            assertEquals("Wolf", PlainTextComponentSerializer.plainText().serialize(LegacyComponentSerializer.legacySection().deserialize(display)));
            assertTrue(display.endsWith("§r"));
            runtime.manager.saveDirtyTeams().get(5, TimeUnit.SECONDS);
            try (Connection conn = DriverManager.getConnection(runtime.url)) {
                assertEquals(name, value(conn, "SELECT name FROM friendlycore_teams"));
            }
            runtime.permissions.remove("friendlycore.teams.rename.format");
            assertEquals(display, expansion.onRequest(player, "team_name_colored"));
            assertFalse(runtime.manager.renameTeam(team, player, name));
            runtime.permissions.add("friendlycore.teams.rename.format");
            runtime.permissions.remove("friendlycore.teams.color.custom");
            assertEquals(display, expansion.onRequest(player, "team_name_colored"));
            assertFalse(runtime.manager.renameTeam(team, player, name));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"<click:run_command:/op>Wolf</click>", "<hover:show_text:hi>Wolf</hover>", "<newline>Wolf", "<font:minecraft:uniform>Wolf</font>", "<red>Long Name Exceeding Limit", "<red>Wolf;TAG"})
    void formattedRenameRejectsActionsAndInvalidVisibleNames(String name) throws Exception {
        try (Runtime runtime = new Runtime()) {
            Team team = team(runtime.playerUuid);
            runtime.manager.registerTeamInMemory(team);
            runtime.permissions.addAll(Set.of("friendlycore.teams.rename", "friendlycore.teams.rename.format", "friendlycore.teams.color.custom"));
            assertFalse(runtime.manager.renameTeam(team, runtime.livePlayer(), name));
            assertEquals("Test", team.getName());
        }
    }

    @Test
    void mysqlNameCapacityUpgradePreservesExistingNames() throws Exception {
        try (Runtime runtime = new Runtime(); Connection conn = DriverManager.getConnection(runtime.url)) {
            Team team = runtime.bank(0);
            conn.createStatement().executeUpdate("ALTER TABLE friendlycore_teams MODIFY COLUMN name VARCHAR(32) NOT NULL");
            TeamsDao.initializeTables(conn, true);
            team.setName("<gradient:#123456:#abcdef>Wolf</gradient>");
            TeamsDao.saveTeams(conn, true, List.of(team));
            assertEquals(team.getName(), value(conn, "SELECT name FROM friendlycore_teams"));
            try (ResultSet result = conn.createStatement().executeQuery("SELECT name FROM friendlycore_teams")) {
                assertEquals(512, result.getMetaData().getPrecision(1));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {770, 771, 774, -1})
    void renameInputRoutesByClientAndConsumesOnlyCurrentSubmission(int protocol) throws Exception {
        try (Runtime runtime = new Runtime()) {
            List<Runnable> timers = new ArrayList<>();
            Player player = runtime.player(callback -> {
                ScheduledTask task = proxy(ScheduledTask.class, (object, method, args) -> null);
                timers.add(() -> callback.accept(task)); return task;
            });
            set(runtime.plugin, JavaPlugin.class, "server", proxy(Server.class, (object, method, args) -> method.getName().equals("getPlayer") ? player : null));
            class TextUi extends OrderInputManager {
                int dialogs, signs;
                Consumer<String> submission;
                TextUi() { super(runtime.plugin); }
                @Override protected int getClientProtocolVersion(Player target) { return protocol; }
                @Override protected void showTextDialog(Player target, String title, String label, String initial, int maxLength,
                                                         Consumer<String> success, Runnable cancel) { dialogs++; submission = success; }
                @Override protected void showTextSign(Player target, List<Component> lines, Consumer<String> success, Runnable cancel) {
                    assertEquals("", PlainTextComponentSerializer.plainText().serialize(lines.getLast()));
                    signs++; submission = success;
                }
            }
            TextUi input = new TextUi();
            List<String> results = new ArrayList<>();
            AtomicInteger cancelled = new AtomicInteger();
            input.openTextInput(player, "Rename", "Name", "Test", 512, results::add, cancelled::incrementAndGet);
            assertEquals(protocol >= 771 ? 1 : 0, input.dialogs);
            assertEquals(protocol >= 771 ? 0 : 1, input.signs);
            Consumer<String> old = input.submission;
            input.openTextInput(player, "Rename", "Name", "Test", 512, results::add, cancelled::incrementAndGet);
            old.accept("Old");
            assertTrue(results.isEmpty());
            input.submission.accept("Wolf");
            input.submission.accept("Duplicate");
            assertEquals(List.of("Wolf"), results);
            input.openTextInput(player, "Rename", "Name", "Test", 512, results::add, cancelled::incrementAndGet);
            timers.getLast().run();
            input.submission.accept("Expired");
            assertEquals(1, cancelled.get());
            assertEquals(List.of("Wolf"), results);
            input.openTextInput(player, "Rename", "Name", "Test", 512, results::add, cancelled::incrementAndGet);
            input.cleanupPlayer(player.getUniqueId());
            input.submission.accept("AfterQuit");
            assertEquals(List.of("Wolf"), results);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"&4Test", "§cTest", "&#12ABEFTest", "#12ABEFTest", "&x&1&2&a&b&e&fTest", "<red>Test", "<gradient:#123456:#abcdef>Test", "&lTest", "&rTest"})
    void creationRejectsEmbeddedFormattingInNamesAndTags(String input) throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.config.set("Team-Settings.team-name.max-length", 100);
            runtime.config.set("Team-Settings.team-tag.max-length", 100);
            runtime.config.set("Team-Settings.team-name.allow-colors", true);
            runtime.config.set("Team-Settings.team-name.allow-special-colors", true);
            runtime.manager.loadConfiguration();
            runtime.permissions.add("friendlycore.teams.color.*");
            assertFalse(runtime.manager.isNameValid(input));
            assertFalse(runtime.manager.isTagValid(input));
            assertNull(runtime.manager.createTeam(runtime.livePlayer(), "VALID", input));
            assertNull(runtime.manager.createTeam(runtime.livePlayer(), input, "Valid Name"));
            assertTrue(runtime.manager.isNameValid("Valid Name"));
            assertTrue(runtime.manager.getTeamByPlayer(runtime.playerUuid).isEmpty());
        }
    }

    @Test
    void retiredWithdrawalCompletesAndPaysOnceOnReconnect() throws Exception {
        try (Runtime runtime = new Runtime()) {
            Team team = runtime.bank(100);
            TeamsManager.BankTransactionResult result = runtime.manager.withdrawToPlayer(runtime.player(callback -> null), team, 60).get(5, TimeUnit.SECONDS);
            assertFalse(result.success()); assertEquals(0, runtime.credits.get());
            try (Connection conn = DriverManager.getConnection(runtime.url)) {
                assertEquals("PENDING", value(conn, "SELECT state FROM friendlycore_team_payments"));
                assertEquals(40, Double.parseDouble(value(conn, "SELECT balance FROM friendlycore_teams")));
            }
            runtime.manager.deliverPendingPayments(runtime.livePlayer()).get(5, TimeUnit.SECONDS);
            runtime.manager.deliverPendingPayments(runtime.livePlayer()).get(5, TimeUnit.SECONDS);
            assertEquals(60, runtime.credits.get());
        }
    }

    @Test
    void failedDepositKeepsRefundWhenPlayerRetires() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.config.set("Team-Settings.team-bank.limit", 1); runtime.manager.loadConfiguration();
            Team team = runtime.bank(0); AtomicInteger schedules = new AtomicInteger();
            Player player = runtime.player(callback -> {
                if (schedules.incrementAndGet() > 1) return null;
                ScheduledTask task = proxy(ScheduledTask.class, (object, method, args) -> null);
                callback.accept(task); return task;
            });
            assertEquals(TeamsManager.BankTransactionStatus.LIMIT_EXCEEDED, runtime.manager.depositFromPlayer(player, team, 10).get(5, TimeUnit.SECONDS).status());
            assertEquals(10, runtime.debits.get()); assertEquals(0, runtime.credits.get());
            runtime.manager.deliverPendingPayments(runtime.livePlayer()).get(5, TimeUnit.SECONDS);
            assertEquals(10, runtime.credits.get());
        }
    }

    @Test
    void promptClaimsInputImmediatelyButExecutesCallbackOnEntityScheduler() throws Exception {
        try (Runtime runtime = new Runtime()) {
            TeamsChatManager chat = new TeamsChatManager(runtime.plugin, runtime.manager);
            AtomicReference<Consumer<ScheduledTask>> queued = new AtomicReference<>(); AtomicInteger callbacks = new AtomicInteger();
            Player player = runtime.player(callback -> { queued.set(callback); return proxy(ScheduledTask.class, (object, method, args) -> null); });
            Class<?> questionType = Class.forName(TeamsChatManager.class.getName() + "$ChatQuestion");
            Constructor<?> constructor = questionType.getDeclaredConstructor(Consumer.class, long.class); constructor.setAccessible(true);
            Field questions = TeamsChatManager.class.getDeclaredField("activeQuestions"); questions.setAccessible(true);
            ((Map<UUID, Object>) questions.get(chat)).put(runtime.playerUuid, constructor.newInstance((Consumer<String>) input -> callbacks.incrementAndGet(), 30L));
            assertTrue(chat.handleChatInput(player, "value")); assertEquals(0, callbacks.get());
            assertFalse(chat.handleChatInput(player, "second")); queued.get().accept(null); assertEquals(1, callbacks.get());
        }
    }

    @Test
    void sharedCreationAndTagChangesEnforceFeesCooldownAndReadiness() throws Exception {
        try (Runtime runtime = new Runtime()) {
            Player player = runtime.livePlayer();
            set(runtime.manager, TeamsManager.class, "ready", false);
            assertNull(runtime.manager.createTeam(player, "ONE", "ONE")); assertEquals(0, runtime.debits.get());
            set(runtime.manager, TeamsManager.class, "ready", true);
            runtime.config.set("Team-Settings.team-creation.requires-payment", true);
            runtime.config.set("Team-Settings.team-creation.payment-amount", 10);
            runtime.config.set("Team-Settings.team-tag.change-cost.enabled", true);
            runtime.config.set("Team-Settings.team-tag.change-cost.amount", 5);
            runtime.manager.loadConfiguration();
            Team team = runtime.manager.createTeam(player, "ONE", "ONE"); assertNotNull(team); assertEquals(10, runtime.debits.get());
            assertTrue(runtime.manager.changeTeamTag(team, player, "TWO")); assertEquals(15, runtime.debits.get());
            assertFalse(runtime.manager.changeTeamTag(team, player, "THREE")); assertEquals(15, runtime.debits.get());
            runtime.manager.saveDirtyTeams().get(5, TimeUnit.SECONDS);
            runtime.manager.unregisterTeamFromMemory(team);
            runtime.manager.getTeamsByUuid().clear();
            Field cooldowns = TeamsManager.class.getDeclaredField("disbandCooldowns"); cooldowns.setAccessible(true);
            ((Map<UUID, Long>) cooldowns.get(runtime.manager)).put(runtime.playerUuid, System.currentTimeMillis() + 60000);
            assertNull(runtime.manager.createTeam(player, "THREE", "THREE")); assertEquals(15, runtime.debits.get());
        }
    }
    @Test
    void disabledFeaturesRejectSharedOperationsBeforeEconomyOrInventoryAccess() throws Exception {
        try (Runtime runtime = new Runtime()) {
            Player player = runtime.livePlayer(); Team team = runtime.bank(100);
            runtime.config.set("Plugin-Settings.toggle-features.bank", false);
            runtime.config.set("Plugin-Settings.toggle-features.home", false);
            runtime.config.set("Plugin-Settings.toggle-features.ally", false);
            runtime.config.set("Plugin-Settings.toggle-features.enderchest", false);
            runtime.manager.loadConfiguration();
            assertFalse(runtime.manager.depositFromPlayer(player, team, 10).get(5, TimeUnit.SECONDS).success());
            assertFalse(runtime.manager.withdrawToPlayer(player, team, 10).get(5, TimeUnit.SECONDS).success());
            assertFalse(runtime.manager.canUseHome(team, player, false));
            assertFalse(runtime.manager.canSetHome(team, player, "home", null, false));
            assertFalse(runtime.manager.deleteTeamHome(team, player, "home"));
            assertFalse(runtime.manager.sendAllyInvite(player, team, team));
            assertFalse(runtime.manager.acceptAllyInvite(team, player, team.getUniqueId()));
            assertNull(runtime.manager.acquireTeamVault(team, player).get(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.debits.get()); assertEquals(0, runtime.credits.get());
        }
    }

    @Test
    void allianceAcceptanceNeedsFreshInviteAndCapacityOnBothTeams() throws Exception {
        try (Runtime runtime = new Runtime()) {
            Player player = runtime.livePlayer(); Team target = runtime.bank(0), source = team(UUID.randomUUID());
            runtime.manager.registerTeamInMemory(source);
            assertFalse(runtime.manager.acceptAllyInvite(target, player, source.getUniqueId()));
            runtime.manager.getOrCreateMailbox(runtime.playerUuid).addInvite(new TeamInvite(
                    source.getOwner().getUniqueId(), "Source", runtime.playerUuid, "Owner", source.getUniqueId(), source.getTag(), TeamInvite.InviteType.ALLY, 60));
            runtime.config.set("Team-Settings.max-allies", 1); runtime.manager.loadConfiguration();
            source.addAlly(UUID.randomUUID());
            assertFalse(runtime.manager.acceptAllyInvite(target, player, source.getUniqueId()));
            source.getAllies().clear();
            assertTrue(runtime.manager.acceptAllyInvite(target, player, source.getUniqueId()));
            assertTrue(target.isAlly(source.getUniqueId())); assertTrue(source.isAlly(target.getUniqueId()));
            assertFalse(runtime.manager.acceptAllyInvite(target, player, source.getUniqueId()));
        }
    }

    @Test
    void redisUpdateCarriesAlliesAndKeepsLocalChangesPending() throws Exception {
        try (Runtime runtime = new Runtime()) {
            Team team = runtime.bank(0); team.setName("local dirty"); UUID ally = UUID.randomUUID();
            var module = new dev.lyy.friendlycore.modules.teams.TeamsModule(runtime.plugin);
            set(module, module.getClass(), "teamsManager", runtime.manager);
            var action = dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.TEAM_UPDATE_SYNC;
            module.handleIncomingCrossServerPacket(new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                    "remote", action, team.getUniqueId(), team.getTag(), 0, "TEST;blue;true;false;Remote;" + ally + ";1234"));
            assertTrue(team.isDirty()); assertEquals(List.of(ally), team.getAllies()); assertEquals(1234, team.getLastTagChange());
            module.handleIncomingCrossServerPacket(new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                    "remote", action, team.getUniqueId(), team.getTag(), 0, "TEST;blue;true;false;Remote;;1234"));
            assertTrue(team.getAllies().isEmpty()); assertTrue(team.isDirty());
        }
    }

}
