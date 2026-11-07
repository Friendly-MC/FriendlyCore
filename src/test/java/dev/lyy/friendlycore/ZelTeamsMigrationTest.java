package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.teams.migration.ZelTeamsMigrator;
import dev.lyy.friendlycore.modules.teams.model.TeamHome;
import dev.lyy.friendlycore.modules.teams.model.TeamPlayerStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ZelTeamsMigrationTest {

    @Test
    @DisplayName("Verify reading and parsing real ZelTeams SQLite database (teamdata & playerdata)")
    void testZelTeamsSqliteReading() throws Exception {
        File dbFile = new File("plugin-config/ZelTeams/storage.db");
        if (!dbFile.exists()) return;

        int teamCount = 0;
        int playerCount = 0;

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath())) {
            // Check teamdata
            try (PreparedStatement ps = conn.prepareStatement("SELECT owner, ownername, tag, bank, enderchest, homes, home, members, allies, ff, color FROM teamdata");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String owner = rs.getString("owner");
                    assertNotNull(owner);
                    UUID ownerUuid = UUID.fromString(owner.trim());
                    assertNotNull(ownerUuid);
                    teamCount++;
                }
            }

            // Check playerdata
            try (PreparedStatement ps = conn.prepareStatement("SELECT player, username, team, stats FROM playerdata");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String player = rs.getString("player");
                    assertNotNull(player);
                    UUID playerUuid = UUID.fromString(player.trim());
                    assertNotNull(playerUuid);
                    playerCount++;
                }
            }
        }

        assertTrue(teamCount > 0, "ZelTeams storage.db must contain teams");
        assertTrue(playerCount > 0, "ZelTeams storage.db must contain player records");
    }

    @Test
    @DisplayName("Test ZelTeams home parsing with colons and semicolons")
    void testZelTeamsHomeParsing() {
        String colonHome = "home==world_nether:3286.5:64.0:-6551.8:114.6:1.9";
        TeamHome parsed = ZelTeamsMigrator.parseZelTeamsHome(colonHome);
        assertNotNull(parsed);
        assertEquals("home", parsed.getName());
        assertEquals("world_nether", parsed.getWorldName());
        assertEquals(3286.5, parsed.getX(), 0.01);
        assertEquals(64.0, parsed.getY(), 0.01);
        assertEquals(-6551.8, parsed.getZ(), 0.01);
        assertEquals(114.6f, parsed.getYaw(), 0.01f);
        assertEquals(1.9f, parsed.getPitch(), 0.01f);

        String semiHome = "base==world;100;70;-200;90;0";
        TeamHome parsed2 = ZelTeamsMigrator.parseZelTeamsHome(semiHome);
        assertNotNull(parsed2);
        assertEquals("base", parsed2.getName());
        assertEquals("world", parsed2.getWorldName());
        assertEquals(100.0, parsed2.getX(), 0.01);
    }

    @Test
    @DisplayName("Test ZelTeams player stats string parsing")
    void testZelTeamsStatsParsing() {
        UUID uuid = UUID.randomUUID();
        String raw = "BLOCKS_BROKEN:6791,MOBS_KILLED:437,LEVEL:59,KILLS:10,DEATHS:15,BLOCKS_PLACED:7412";
        TeamPlayerStats stats = ZelTeamsMigrator.parseZelTeamsStats(uuid, "Steve", raw);

        assertNotNull(stats);
        assertEquals(10, stats.getKills());
        assertEquals(15, stats.getDeaths());
        assertEquals(437, stats.getMobKills());
        assertEquals(6791, stats.getBlocksBroken());
        assertEquals(7412, stats.getBlocksPlaced());
        assertEquals(59, stats.getLevel());
        assertEquals("Steve", stats.getUsername());
        assertEquals(uuid, stats.getPlayerUuid());
    }

    @Test
    @DisplayName("Verify deserializing legacy ZelTeams NBT enderchests without error")
    void testZelTeamsEnderChestDeserialization() throws Exception {
        File dbFile = new File("plugin-config/ZelTeams/storage.db");
        if (!dbFile.exists()) return;

        dev.lyy.friendlycore.modules.teams.model.TeamEnderChest chest = new dev.lyy.friendlycore.modules.teams.model.TeamEnderChest(6, null);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             PreparedStatement ps = conn.prepareStatement("SELECT enderchest FROM teamdata WHERE enderchest IS NOT NULL AND enderchest != ''");
             ResultSet rs = ps.executeQuery()) {

            int count = 0;
            while (rs.next()) {
                String raw = rs.getString("enderchest");
                assertDoesNotThrow(() -> chest.deserialize(raw));
                count++;
            }
            assertTrue(count > 0, "Should have verified non-empty enderchest records");
        }
    }
}
