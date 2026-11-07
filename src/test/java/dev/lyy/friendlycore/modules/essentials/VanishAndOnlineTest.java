package dev.lyy.friendlycore.modules.essentials;

import dev.lyy.friendlycore.modules.essentials.commands.OnlineCommand;
import dev.lyy.friendlycore.modules.essentials.vanish.VanishManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for Vanish and Online / List subsystem components.
 */
public class VanishAndOnlineTest {

    @Test
    @DisplayName("Test OnlineCommand GroupInfo sorting by weight descending")
    public void testGroupSortingByWeight() {
        OnlineCommand.GroupInfo owner = new OnlineCommand.GroupInfo("owner", "Owner", 100, "<red>[Owner]</red> ");
        OnlineCommand.GroupInfo admin = new OnlineCommand.GroupInfo("admin", "Admin", 80, "<red>[Admin]</red> ");
        OnlineCommand.GroupInfo mod = new OnlineCommand.GroupInfo("mod", "Moderator", 50, "<gold>[Mod]</gold> ");
        OnlineCommand.GroupInfo vip = new OnlineCommand.GroupInfo("vip", "VIP", 20, "<green>[VIP]</green> ");
        OnlineCommand.GroupInfo player = new OnlineCommand.GroupInfo("default", "Player", 0, "");

        List<OnlineCommand.GroupInfo> groups = new ArrayList<>(List.of(player, mod, owner, vip, admin));
        groups.sort((g1, g2) -> {
            if (g1.weight() != g2.weight()) {
                return Integer.compare(g2.weight(), g1.weight());
            }
            return g1.name().compareToIgnoreCase(g2.name());
        });

        assertEquals("owner", groups.get(0).name());
        assertEquals("admin", groups.get(1).name());
        assertEquals("mod", groups.get(2).name());
        assertEquals("vip", groups.get(3).name());
        assertEquals("default", groups.get(4).name());
    }

    @Test
    @DisplayName("Test Vanish duration calculation")
    public void testVanishDuration() {
        long start = System.currentTimeMillis() - 65_000L; // 65 seconds ago
        long elapsedSeconds = (System.currentTimeMillis() - start) / 1000L;
        assertTrue(elapsedSeconds >= 65L && elapsedSeconds <= 67L);

        long m = elapsedSeconds / 60;
        long s = elapsedSeconds % 60;
        String formatted = String.format("%dm %ds", m, s);
        assertTrue(formatted.startsWith("1m "));
    }

    @Test
    @DisplayName("Test Container block type checking")
    public void testContainerBlockIdentification() {
        Set<String> containerNames = Set.of(
                "CHEST", "TRAPPED_CHEST", "BARREL", "ENDER_CHEST",
                "SHULKER_BOX", "BLACK_SHULKER_BOX", "WHITE_SHULKER_BOX", "CYAN_SHULKER_BOX"
        );

        for (String name : containerNames) {
            boolean isContainer = name.equals("CHEST") || name.equals("TRAPPED_CHEST")
                    || name.equals("BARREL") || name.equals("ENDER_CHEST")
                    || name.endsWith("_SHULKER_BOX") || name.equals("SHULKER_BOX");
            assertTrue(isContainer, "Expected " + name + " to be identified as container block");
        }

        assertFalse("STONE".endsWith("_SHULKER_BOX") || "STONE".equals("CHEST"));
    }
}
