package dev.lyy.friendlycore.modules.pvp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NewbieProtectionManagerTest {

    @Test
    void bossBarFormatsDurationsAndSupportsExistingSecondsSuffix() {
        String template = "<white>%time% remaining</white>";
        assertEquals("<white>2d 23h 58m 27s remaining</white>",
                NewbieProtectionManager.formatBossBarTitle(template, 259107));
        assertEquals("<white>2m remaining</white>",
                NewbieProtectionManager.formatBossBarTitle(template, 120));
        assertEquals("1h remaining", NewbieProtectionManager.formatBossBarTitle("%time%s remaining", 3600));
        assertEquals("1m 1s remaining", NewbieProtectionManager.formatBossBarTitle("%time%s remaining", 61));
        assertEquals("1s remaining", NewbieProtectionManager.formatBossBarTitle("%time%s remaining", 1));
        assertEquals("0s remaining", NewbieProtectionManager.formatBossBarTitle("%time%s remaining", 0));
        assertEquals("0s remaining", NewbieProtectionManager.formatBossBarTitle("%time% remaining", -1));
    }
}
