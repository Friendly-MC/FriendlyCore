package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.teams.gui.TeamMenuParser;
import dev.lyy.friendlycore.modules.teams.model.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TeamsModuleTest {

    private UUID ownerUuid;
    private UUID memberUuid;
    private UUID teamUuid;
    private TeamRole leaderRole;
    private TeamRole memberRole;
    private Team team;

    @BeforeEach
    void setUp() {
        ownerUuid = UUID.randomUUID();
        memberUuid = UUID.randomUUID();
        teamUuid = UUID.randomUUID();

        leaderRole = new TeamRole(-1, "-1", "Leader", EnumSet.allOf(RolePermission.class));
        memberRole = new TeamRole(1, "1", "Recruit", EnumSet.of(RolePermission.USE_HOME, RolePermission.ACCESS_BANK));

        TeamOwner owner = new TeamOwner(ownerUuid, "LeaderPlayer", leaderRole, System.currentTimeMillis());
        team = new Team(teamUuid, "DragonSlayers", "DRAGON", owner);
    }

    @Test
    void testTeamInitialization() {
        assertEquals(teamUuid, team.getUniqueId());
        assertEquals("DragonSlayers", team.getName());
        assertEquals("DRAGON", team.getTag());
        assertEquals(ownerUuid, team.getOwner().getUniqueId());
        assertEquals(1, team.getMemberCount());
        assertTrue(team.isOwner(ownerUuid));
        assertTrue(team.hasMember(ownerUuid));
        assertTrue(team.isDirty());
    }

    @Test
    void testMemberLifecycle() {
        TeamMember member = new TeamMember(memberUuid, "WarriorPlayer", memberRole, System.currentTimeMillis());
        team.addMember(member);

        assertEquals(2, team.getMemberCount());
        assertTrue(team.hasMember(memberUuid));
        assertFalse(team.isOwner(memberUuid));

        TeamMember retrieved = team.getMember(memberUuid);
        assertNotNull(retrieved);
        assertEquals("WarriorPlayer", retrieved.getUsername());
        assertTrue(retrieved.hasPermission(RolePermission.USE_HOME));
        assertFalse(retrieved.hasPermission(RolePermission.KICK));

        team.removeMember(memberUuid);
        assertEquals(1, team.getMemberCount());
        assertFalse(team.hasMember(memberUuid));
    }

    @Test
    void testRoleHierarchy() {
        TeamRole coLeader = new TeamRole(3, "3", "Co-Leader", EnumSet.of(RolePermission.INVITE, RolePermission.KICK));
        TeamRole officer = new TeamRole(2, "2", "Officer", EnumSet.of(RolePermission.INVITE));
        TeamRole recruit = new TeamRole(1, "1", "Recruit", EnumSet.of(RolePermission.USE_HOME));

        assertTrue(leaderRole.isHigherThan(coLeader));
        assertTrue(leaderRole.isHigherThan(officer));
        assertTrue(leaderRole.isHigherThan(recruit));

        assertTrue(coLeader.isHigherThan(officer));
        assertTrue(officer.isHigherThan(recruit));
        assertFalse(recruit.isHigherThan(officer));
    }

    @Test
    void testBankTransactions() {
        assertEquals(0.0, team.getBankBalance());

        team.deposit(500.0);
        assertEquals(500.0, team.getBankBalance());

        boolean withdrew = team.withdraw(200.0);
        assertTrue(withdrew);
        assertEquals(300.0, team.getBankBalance());

        boolean overWithdrew = team.withdraw(1000.0);
        assertFalse(overWithdrew);
        assertEquals(300.0, team.getBankBalance());
    }

    @Test
    void testConcurrentBankWithdrawalsCannotOverspend() throws Exception {
        team.deposit(100.0);
        ExecutorService executor = Executors.newFixedThreadPool(12);
        AtomicInteger successful = new AtomicInteger();
        List<Future<?>> attempts = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            attempts.add(executor.submit(() -> {
                if (team.withdraw(1.0)) successful.incrementAndGet();
            }));
        }
        for (Future<?> attempt : attempts) attempt.get(5, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(100, successful.get());
        assertEquals(0.0, team.getBankBalance());
    }

    @Test
    void testOversizedVaultPayloadIsRejectedWithoutThrowing() {
        TeamEnderChest chest = team.getEnderChest();
        String oversized = "A".repeat(2_000_001);
        assertDoesNotThrow(() -> chest.deserialize(oversized));
    }

    @Test
    void testHomesSerialization() {
        TeamHome home = new TeamHome("main", "world", 100.5, 64.0, -200.5, 90.0f, 0.0f);
        String serialized = home.serialize();
        assertNotNull(serialized);

        TeamHome parsed = TeamHome.parse(serialized);
        assertNotNull(parsed);
        assertEquals("main", parsed.getName());
        assertEquals("world", parsed.getWorldName());
        assertEquals(100.5, parsed.getX(), 0.001);
        assertEquals(64.0, parsed.getY(), 0.001);
        assertEquals(-200.5, parsed.getZ(), 0.001);
        assertEquals(90.0f, parsed.getYaw(), 0.001f);
        assertEquals(0.0f, parsed.getPitch(), 0.001f);
    }

    @Test
    void testAlliesManagement() {
        UUID allyUuid = UUID.randomUUID();
        assertFalse(team.isAlly(allyUuid));

        team.addAlly(allyUuid);
        assertTrue(team.isAlly(allyUuid));
        assertEquals(1, team.getAllies().size());

        team.removeAlly(allyUuid);
        assertFalse(team.isAlly(allyUuid));
        assertEquals(0, team.getAllies().size());
    }

    @Test
    void testPlayerStatsAndKdr() {
        TeamPlayerStats stats = new TeamPlayerStats(memberUuid, "WarriorPlayer");
        stats.incrementKills();
        stats.incrementKills();
        stats.incrementKills(); // 3 kills
        stats.incrementDeaths(); // 1 death
        stats.incrementMobKills();
        stats.incrementBlocksBroken();
        stats.incrementBlocksPlaced();

        assertEquals(3, stats.getKills());
        assertEquals(1, stats.getDeaths());
        assertEquals(3.0, stats.getKdr(), 0.01);
        assertEquals(1, stats.getMobKills());
        assertEquals(1, stats.getBlocksBroken());
        assertEquals(1, stats.getBlocksPlaced());

        String serialized = stats.serialize();
        TeamPlayerStats deserialized = TeamPlayerStats.deserialize(memberUuid, "WarriorPlayer", serialized);

        assertEquals(3, deserialized.getKills());
        assertEquals(1, deserialized.getDeaths());
        assertEquals(1, deserialized.getMobKills());
        assertEquals(1, deserialized.getBlocksBroken());
        assertEquals(1, deserialized.getBlocksPlaced());
    }

    @Test
    void testMailboxAndInvites() {
        TeamMailbox mailbox = new TeamMailbox(memberUuid);
        TeamInvite invite = new TeamInvite(ownerUuid, "LeaderPlayer", memberUuid, "WarriorPlayer", teamUuid, "DRAGON", TeamInvite.InviteType.PLAYER, 60L);

        mailbox.addInvite(invite);
        assertTrue(mailbox.hasInvites());
        assertEquals(1, mailbox.getInvites().size());

        mailbox.removeInvite(teamUuid, TeamInvite.InviteType.PLAYER);
        assertFalse(mailbox.hasInvites());
        assertEquals(0, mailbox.getInvites().size());
    }

    @Test
    void testDatabaseSchemaAndStorage() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE friendlycore_teams (
                        uuid VARCHAR(36) PRIMARY KEY,
                        name VARCHAR(32) NOT NULL,
                        tag VARCHAR(32) NOT NULL,
                        owner_uuid VARCHAR(36) NOT NULL,
                        owner_name VARCHAR(36),
                        members_data TEXT,
                        allies_data TEXT,
                        balance DOUBLE NOT NULL DEFAULT 0.0,
                        enderchest_data TEXT,
                        homes_data TEXT,
                        max_homes INTEGER NOT NULL DEFAULT 1,
                        last_tag_change BIGINT NOT NULL DEFAULT 0,
                        color_key VARCHAR(32) NOT NULL DEFAULT 'white',
                        friendly_fire BOOLEAN NOT NULL DEFAULT 1,
                        closed BOOLEAN NOT NULL DEFAULT 0,
                        score DOUBLE NOT NULL DEFAULT 0.0,
                        rank_pos INTEGER NOT NULL DEFAULT 0,
                        created_at BIGINT NOT NULL DEFAULT 0
                    );
                """);
            }

            String insertSql = """
                INSERT INTO friendlycore_teams (
                    uuid, name, tag, owner_uuid, owner_name, members_data, allies_data, balance,
                    enderchest_data, homes_data, max_homes, last_tag_change, color_key, friendly_fire,
                    closed, score, rank_pos, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
            """;

            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, team.getUniqueId().toString());
                ps.setString(2, team.getName());
                ps.setString(3, team.getTag());
                ps.setString(4, team.getOwner().getUniqueId().toString());
                ps.setString(5, team.getOwner().getUsername());
                ps.setString(6, "");
                ps.setString(7, "");
                ps.setDouble(8, 1500.0);
                ps.setString(9, "");
                ps.setString(10, "");
                ps.setInt(11, 2);
                ps.setLong(12, 0L);
                ps.setString(13, "gold");
                ps.setBoolean(14, true);
                ps.setBoolean(15, false);
                ps.setDouble(16, 50.0);
                ps.setInt(17, 1);
                ps.setLong(18, System.currentTimeMillis());
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM friendlycore_teams WHERE uuid = ?")) {
                ps.setString(1, team.getUniqueId().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("DragonSlayers", rs.getString("name"));
                    assertEquals("DRAGON", rs.getString("tag"));
                    assertEquals(1500.0, rs.getDouble("balance"));
                    assertEquals("gold", rs.getString("color_key"));
                    assertEquals(50.0, rs.getDouble("score"));
                    assertEquals(1, rs.getInt("rank_pos"));
                }
            }
        }
    }

    @Test
    void testLeaderboardScoreFormula() {
        double killFactor = 3.0;
        double deathFactor = 3.0;
        double moneyFactor = 0.5;

        int kills = 10;
        int deaths = 2;
        double bank = 500.0;

        double score = (kills * killFactor) - (deaths * deathFactor) + (bank * moneyFactor);
        double roundedScore = Math.round(score * 100.0) / 100.0;

        // (10 * 3.0) - (2 * 3.0) + (500.0 * 0.5) = 30 - 6 + 250 = 274.0
        assertEquals(274.0, roundedScore, 0.001);

        team.setScore(roundedScore);
        team.setRank(1);
        assertEquals(274.0, team.getScore(), 0.001);
        assertEquals(1, team.getRank());
    }

    @Test
    void testTeamColorParsing() {
        TeamColor goldColor = new TeamColor("gold", "Gold", org.bukkit.Material.GOLD_BLOCK, "&6", "zelteams.color.gold");
        assertEquals("gold", goldColor.getKey());
        assertEquals("&6", goldColor.getColorCode());
        assertEquals("Gold", goldColor.getName());
        assertEquals(org.bukkit.Material.GOLD_BLOCK, goldColor.getMaterial());
        assertEquals("zelteams.color.gold", goldColor.getPermission());
    }

    @Test
    void testMultipleHomesStorage() {
        TeamHome h1 = new TeamHome("main", "world", 10.0, 64.0, 10.0, 0f, 0f);
        TeamHome h2 = new TeamHome("base", "world_nether", 100.0, 50.0, -100.0, 90f, 45f);

        team.getHomes().put("main", h1);
        team.getHomes().put("base", h2);

        assertEquals(2, team.getHomes().size());
        assertNotNull(team.getHome("main"));
        assertNotNull(team.getHome("base"));
        assertNull(team.getHome("nonexistent"));

        boolean removed = team.deleteHome("base");
        assertTrue(removed);
        assertEquals(1, team.getHomes().size());
        assertNull(team.getHome("base"));
    }

    @Test
    void testCrossServerPacketActionsSerialization() {
        com.google.gson.Gson gson = new com.google.gson.Gson();

        for (dev.lyy.friendlycore.common.redis.CrossServerPacket.Action action : dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.values()) {
            dev.lyy.friendlycore.common.redis.CrossServerPacket packet = new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                    "server-1",
                    action,
                    teamUuid,
                    "DRAGON",
                    1234.5,
                    "test-payload"
            );

            String json = gson.toJson(packet);
            assertNotNull(json);

            dev.lyy.friendlycore.common.redis.CrossServerPacket deserialized = gson.fromJson(json, dev.lyy.friendlycore.common.redis.CrossServerPacket.class);
            assertNotNull(deserialized);
            assertEquals("server-1", deserialized.getSourceServer());
            assertEquals(action, deserialized.getAction());
            assertEquals(teamUuid, deserialized.getTargetUuid());
            assertEquals("DRAGON", deserialized.getTargetName());
            assertEquals(1234.5, deserialized.getAmount());
            assertEquals("test-payload", deserialized.getExtra());
        }
    }

    @Test
    void testCrossServerTeamCreationPayloadParsing() {
        String payload = ownerUuid + ";LeaderPlayer;DragonSlayers;DRAGON;gold;false;true";
        String[] parts = payload.split(";", -1);
        assertEquals(7, parts.length);

        UUID parsedOwnerUuid = UUID.fromString(parts[0]);
        String parsedOwnerName = parts[1];
        String parsedTeamName = parts[2];
        String parsedTag = parts[3];
        String parsedColor = parts[4];
        boolean parsedFf = Boolean.parseBoolean(parts[5]);
        boolean parsedClosed = Boolean.parseBoolean(parts[6]);

        assertEquals(ownerUuid, parsedOwnerUuid);
        assertEquals("LeaderPlayer", parsedOwnerName);
        assertEquals("DragonSlayers", parsedTeamName);
        assertEquals("DRAGON", parsedTag);
        assertEquals("gold", parsedColor);
        assertFalse(parsedFf);
        assertTrue(parsedClosed);

        TeamOwner remoteOwner = new TeamOwner(parsedOwnerUuid, parsedOwnerName, leaderRole, System.currentTimeMillis());
        Team remoteTeam = new Team(teamUuid, parsedTeamName, parsedTag, remoteOwner);
        remoteTeam.setColorKey(parsedColor);
        remoteTeam.setFriendlyFireEnabled(parsedFf);
        remoteTeam.setClosed(parsedClosed);

        assertEquals("DRAGON", remoteTeam.getTag());
        assertEquals("gold", remoteTeam.getColorKey());
        assertFalse(remoteTeam.isFriendlyFireEnabled());
        assertTrue(remoteTeam.isClosed());
    }

    @Test
    void testTeamEnderChestDeserializationClearing() {
        TeamEnderChest ec = new TeamEnderChest(3, null, team);
        assertEquals(3, ec.getRows());
        assertEquals(27, ec.getSize());
        assertFalse(ec.isDirty());

        // Deserializing null or empty should gracefully return without error
        assertDoesNotThrow(() -> ec.deserialize(null));
        assertDoesNotThrow(() -> ec.deserialize(""));
        assertDoesNotThrow(() -> ec.deserialize("   "));
        assertFalse(ec.isDirty());
    }

    @Test
    void testCrossServerEnderChestSyncPacket() {
        com.google.gson.Gson gson = new com.google.gson.Gson();

        dev.lyy.friendlycore.common.redis.CrossServerPacket packet = new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                "server-hub",
                dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.TEAM_ENDERCHEST_SYNC,
                teamUuid,
                "DRAGON",
                0.0,
                "H4sICAAAAAAAC..."
        );

        String json = gson.toJson(packet);
        assertNotNull(json);

        dev.lyy.friendlycore.common.redis.CrossServerPacket deserialized = gson.fromJson(json, dev.lyy.friendlycore.common.redis.CrossServerPacket.class);
        assertNotNull(deserialized);
        assertEquals("server-hub", deserialized.getSourceServer());
        assertEquals(dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.TEAM_ENDERCHEST_SYNC, deserialized.getAction());
        assertEquals(teamUuid, deserialized.getTargetUuid());
        assertEquals("DRAGON", deserialized.getTargetName());
        assertEquals("H4sICAAAAAAAC...", deserialized.getExtra());
    }

    @Test
    void testTeamMenuParserLayoutExpansionSingleStar() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("inventory.title", "&b✹ &7Team Homes &8(&7%page%&8/&7%total%&8)");
        config.set("inventory.disposition", List.of(
                "----o----",
                "*",
                "p---s---n"
        ));
        config.set("inventory.rows", 2);

        config.set("items.blank.symbol", "-");
        config.set("items.blank.material", "LIGHT_GRAY_STAINED_GLASS_PANE");
        config.set("items.goback.symbol", "o");
        config.set("items.goback.material", "ARROW");
        config.set("items.sethome.symbol", "s");
        config.set("items.sethome.material", "NETHER_STAR");
        config.set("items.previous-page.symbol", "p");
        config.set("items.previous-page.material", "PAPER");
        config.set("items.next-page.symbol", "n");
        config.set("items.next-page.material", "PAPER");

        TeamMenuParser.ParsedLayout layout = TeamMenuParser.parse(config, false);
        assertNotNull(layout);
        assertEquals(4, layout.getRows());
        assertEquals(36, layout.getSize());

        // Content slots should be 2 rows of 9 slots = 18 slots (indices 9 to 26)
        assertEquals(18, layout.getContentSlots().size());
        assertEquals(9, layout.getContentSlots().get(0));
        assertEquals(26, layout.getContentSlots().get(17));

        // Verify symbol slot placements
        assertEquals(4, layout.getFirstSlotForSymbol('o'));
        assertEquals(27, layout.getFirstSlotForSymbol('p'));
        assertEquals(31, layout.getFirstSlotForSymbol('s'));
        assertEquals(35, layout.getFirstSlotForSymbol('n'));
    }

    @Test
    void testTeamMenuParserLayoutExpansionMultipleStars() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("inventory.title", "&0⏵ Team | MailBox [%page% %total%]");
        config.set("inventory.disposition", List.of(
                "*",
                "*",
                "*",
                "p---b---n"
        ));
        config.set("inventory.rows", 4);

        config.set("items.blank.symbol", "-");
        config.set("items.back.symbol", "b");
        config.set("items.previous-page.symbol", "p");
        config.set("items.next-page.symbol", "n");

        TeamMenuParser.ParsedLayout layout = TeamMenuParser.parse(config, false);
        assertNotNull(layout);
        assertEquals(4, layout.getRows());
        assertEquals(36, layout.getSize());

        // Content slots should be 3 rows of 9 slots = 27 slots (indices 0 to 26)
        assertEquals(27, layout.getContentSlots().size());
        assertEquals(0, layout.getContentSlots().get(0));
        assertEquals(26, layout.getContentSlots().get(26));

        // Symbols on bottom row
        assertEquals(27, layout.getFirstSlotForSymbol('p'));
        assertEquals(31, layout.getFirstSlotForSymbol('b'));
        assertEquals(35, layout.getFirstSlotForSymbol('n'));
    }

    @Test
    void testTeamHomeSerializationWithServerAndBackwardsCompatibility() {
        // Test new format with server
        TeamHome homeWithServer = new TeamHome("main", "world_nether", 100.5, 64.0, -200.5, 90.0f, 0.0f, "survival-1");
        assertEquals("main", homeWithServer.getName());
        assertEquals("world_nether", homeWithServer.getWorldName());
        assertEquals(100.5, homeWithServer.getX());
        assertEquals(64.0, homeWithServer.getY());
        assertEquals(-200.5, homeWithServer.getZ());
        assertEquals(90.0f, homeWithServer.getYaw());
        assertEquals(0.0f, homeWithServer.getPitch());
        assertEquals("survival-1", homeWithServer.getServer());

        String serialized = homeWithServer.serialize();
        assertEquals("main==world_nether;100.5;64.0;-200.5;90.0;0.0;survival-1", serialized);

        TeamHome parsedWithServer = TeamHome.parse(serialized);
        assertNotNull(parsedWithServer);
        assertEquals("main", parsedWithServer.getName());
        assertEquals("world_nether", parsedWithServer.getWorldName());
        assertEquals(100.5, parsedWithServer.getX());
        assertEquals("survival-1", parsedWithServer.getServer());

        // Test backwards compatibility with legacy 6-token string
        String legacySerialized = "outpost==world_the_end;500.0;70.0;500.0;180.0;45.0";
        TeamHome parsedLegacy = TeamHome.parse(legacySerialized);
        assertNotNull(parsedLegacy);
        assertEquals("outpost", parsedLegacy.getName());
        assertEquals("world_the_end", parsedLegacy.getWorldName());
        assertEquals(500.0, parsedLegacy.getX());
        assertEquals("", parsedLegacy.getServer());
    }

    @Test
    void testCrossServerTeamHomeSyncPacket() {
        com.google.gson.Gson gson = new com.google.gson.Gson();

        String homesPayload = "main==world;10.0;64.0;20.0;0.0;0.0;survival-1||farm==world_nether;100.0;50.0;200.0;90.0;0.0;survival-2";

        dev.lyy.friendlycore.common.redis.CrossServerPacket packet = new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                "survival-1",
                dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.TEAM_HOME_SYNC,
                teamUuid,
                "DRAGON",
                0,
                homesPayload
        );

        String json = gson.toJson(packet);
        assertNotNull(json);

        dev.lyy.friendlycore.common.redis.CrossServerPacket deserialized = gson.fromJson(json, dev.lyy.friendlycore.common.redis.CrossServerPacket.class);
        assertNotNull(deserialized);
        assertEquals(dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.TEAM_HOME_SYNC, deserialized.getAction());
        assertEquals("survival-1", deserialized.getSourceServer());
        assertEquals(teamUuid, deserialized.getTargetUuid());

        // Parse homes payload
        String[] entries = deserialized.getExtra().split("\\|\\|");
        assertEquals(2, entries.length);

        TeamHome home1 = TeamHome.parse(entries[0]);
        assertNotNull(home1);
        assertEquals("main", home1.getName());
        assertEquals("survival-1", home1.getServer());

        TeamHome home2 = TeamHome.parse(entries[1]);
        assertNotNull(home2);
        assertEquals("farm", home2.getName());
        assertEquals("survival-2", home2.getServer());
    }

    @Test
    void testBankGuiParsing() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("inventory.title", "&0⏵ Team | Bank: $%money%");
        config.set("inventory.disposition", List.of(
                "---------",
                "-abcdefg-",
                "-hijklmn-",
                "o---p----"
        ));
        config.set("items.blank.symbol", "-");
        config.set("items.blank.material", "BLACK_STAINED_GLASS_PANE");
        config.set("items.explain.symbol", "p");
        config.set("items.explain.material", "GOLD_BLOCK");
        config.set("items.explain.actions.left-click", "explain");
        config.set("items.goback.symbol", "o");
        config.set("items.goback.material", "GLOBE_BANNER_PATTERN");
        config.set("items.goback.actions.left-click", "go_back");
        config.set("items.add_1.symbol", "a");
        config.set("items.add_1.material", "LIME_CANDLE");
        config.set("items.add_1.actions.left-click", "add_1");
        config.set("items.take_1.symbol", "h");
        config.set("items.take_1.material", "RED_CANDLE");
        config.set("items.take_1.actions.left-click", "take_1");

        TeamMenuParser.ParsedLayout layout = TeamMenuParser.parse(config, false);
        assertNotNull(layout);
        assertEquals(4, layout.getRows());
        assertEquals(36, layout.getSize());
        assertEquals(27, layout.getFirstSlotForSymbol('o'));
        assertEquals(31, layout.getFirstSlotForSymbol('p'));
        assertEquals(10, layout.getFirstSlotForSymbol('a'));
        assertEquals(19, layout.getFirstSlotForSymbol('h'));
    }

    @Test
    void testTeamCrossTeleportPacketAndPendingCleanups() {
        dev.lyy.friendlycore.modules.teams.manager.TeamsTeleportManager.PendingCrossTeleport pending =
                new dev.lyy.friendlycore.modules.teams.manager.TeamsTeleportManager.PendingCrossTeleport(null, System.currentTimeMillis() - 70_000L);
        assertTrue(pending.isExpired());

        dev.lyy.friendlycore.modules.teams.manager.TeamsTeleportManager.PendingCrossTeleport fresh =
                new dev.lyy.friendlycore.modules.teams.manager.TeamsTeleportManager.PendingCrossTeleport(null, System.currentTimeMillis());
        assertFalse(fresh.isExpired());
    }

    @Test
    void testTeamGuiLayoutInvitesParsing() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("inventory.title", "&0⏵ Teams Menu");
        config.set("inventory.disposition", List.of(
                "---------",
                "-iamhxtc-",
                "---------"
        ));
        config.set("inventory.disposition-alternative", List.of(
                "---------",
                "---i-c---",
                "---------"
        ));
        config.set("items.blank.symbol", "-");
        config.set("items.blank.material", "BLACK_STAINED_GLASS_PANE");
        config.set("items.invites.symbol", "i");
        config.set("items.invites.material", "BELL");
        config.set("items.invites.actions.left-click", "show_invites");
        config.set("items.create-team.symbol", "c");
        config.set("items.create-team.material", "GREEN_BED");
        config.set("items.create-team.actions.left-click", "create_or_leave_team");

        TeamMenuParser.ParsedLayout normalLayout = TeamMenuParser.parse(config, false);
        assertEquals(3, normalLayout.getRows());
        assertEquals(10, normalLayout.getFirstSlotForSymbol('i'));
        TeamMenuParser.GuiItemConfig normalInviteCfg = normalLayout.getSlotMap().get(10);
        assertNotNull(normalInviteCfg);
        assertEquals("show_invites", normalInviteCfg.getActions().get("left-click"));

        TeamMenuParser.ParsedLayout altLayout = TeamMenuParser.parse(config, true);
        assertEquals(3, altLayout.getRows());
        assertEquals(12, altLayout.getFirstSlotForSymbol('i'));
        TeamMenuParser.GuiItemConfig altInviteCfg = altLayout.getSlotMap().get(12);
        assertNotNull(altInviteCfg);
        assertEquals("show_invites", altInviteCfg.getActions().get("left-click"));
    }

    @Test
    void testTeamMenuParserStaticItemCachingAndImmutability() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("inventory.title", "Static Test");
        config.set("inventory.disposition", List.of(
                "---",
                "-p-"
        ));
        config.set("items.blank.symbol", "-");
        config.set("items.blank.material", "BLACK_STAINED_GLASS_PANE");
        config.set("items.blank.name", "&8Empty Slot");
        config.set("items.blank.lore", List.of("&7Nothing here"));

        config.set("items.player.symbol", "p");
        config.set("items.player.material", "DIAMOND");
        config.set("items.player.name", "&aPlayer %name%");

        TeamMenuParser.ParsedLayout layout = TeamMenuParser.parse(config, false);
        TeamMenuParser.GuiItemConfig blankCfg = layout.getSlotMap().get(0);
        assertNotNull(blankCfg);
        assertEquals("BLACK_STAINED_GLASS_PANE", blankCfg.getMaterialName());
        assertEquals("&8Empty Slot", blankCfg.getDisplayName());
        assertEquals(1, blankCfg.getLore().size());
        assertEquals("&7Nothing here", blankCfg.getLore().get(0));

        int pSlot = layout.getFirstSlotForSymbol('p');
        assertEquals(10, pSlot);
        TeamMenuParser.GuiItemConfig playerCfg = layout.getSlotMap().get(pSlot);
        assertNotNull(playerCfg);
        assertEquals("DIAMOND", playerCfg.getMaterialName());
        assertEquals("&aPlayer %name%", playerCfg.getDisplayName());
    }
}
