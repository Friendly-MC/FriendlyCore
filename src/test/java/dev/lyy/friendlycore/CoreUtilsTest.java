package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.CooldownManager;
import dev.lyy.friendlycore.common.NumberFormatter;
import dev.lyy.friendlycore.common.TextService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

public class CoreUtilsTest {

    @Test
    public void testNumberParsing() {
        Assertions.assertEquals(100.0, NumberFormatter.parseAmount("100"));
        Assertions.assertEquals(1_000.0, NumberFormatter.parseAmount("1k"));
        Assertions.assertEquals(2_500_000.0, NumberFormatter.parseAmount("2.5m"));
        Assertions.assertEquals(10_000_000_000.0, NumberFormatter.parseAmount("10b"));
        Assertions.assertEquals(1_000_000_000_000.0, NumberFormatter.parseAmount("1t"));
        Assertions.assertEquals(5_000_000_000_000_000.0, NumberFormatter.parseAmount("5q"));
    }

    @Test
    public void testCooldownManager() throws InterruptedException {
        CooldownManager cd = new CooldownManager();
        UUID player = UUID.randomUUID();

        Assertions.assertFalse(cd.isOnCooldown("test", player, 500));
        cd.setCooldown("test", player);
        Assertions.assertTrue(cd.isOnCooldown("test", player, 500));
        Assertions.assertTrue(cd.getRemainingMillis("test", player, 500) > 0);

        cd.resetCooldown("test", player);
        Assertions.assertFalse(cd.isOnCooldown("test", player, 500));
    }

    @Test
    public void testTextServiceConversion() {
        String legacy = "&aHello &lWorld &c! &#ff5555Hex";
        String converted = TextService.convertLegacyToMiniMessage(legacy);
        Assertions.assertTrue(converted.contains("<green>"));
        Assertions.assertTrue(converted.contains("<bold>"));
        Assertions.assertTrue(converted.contains("<red>"));
        Assertions.assertTrue(converted.contains("<#ff5555>"));
    }

    @Test
    public void testEssentialsRegexMatching() {
        java.util.regex.Pattern moneyPattern = java.util.regex.Pattern.compile("^\\s*money:\\s*['\"]?([0-9]+(?:\\.[0-9]+)?)['\"]?", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Pattern namePattern = java.util.regex.Pattern.compile("^\\s*lastAccountName:\\s*['\"]?([^'\"\\r\\n]+)['\"]?", java.util.regex.Pattern.CASE_INSENSITIVE);

        String sample1 = "money: '12345.67'";
        java.util.regex.Matcher m1 = moneyPattern.matcher(sample1);
        Assertions.assertTrue(m1.find());
        Assertions.assertEquals(12345.67, Double.parseDouble(m1.group(1)));

        String sample2 = "money: 500";
        java.util.regex.Matcher m2 = moneyPattern.matcher(sample2);
        Assertions.assertTrue(m2.find());
        Assertions.assertEquals(500.0, Double.parseDouble(m2.group(1)));

        String nameSample = "lastAccountName: 'Steve_Craft'";
        java.util.regex.Matcher nm = namePattern.matcher(nameSample);
        Assertions.assertTrue(nm.find());
        Assertions.assertEquals("Steve_Craft", nm.group(1));
    }

    @Test
    public void testSpeedUtilCalculationAndEdgeCases() {
        float bukkitWalk = dev.lyy.friendlycore.modules.essentials.util.SpeedUtil.calculateBukkitSpeed(1.0f, false, 1.0f);
        Assertions.assertEquals(0.2f, bukkitWalk, 0.001f);

        float userWalk = dev.lyy.friendlycore.modules.essentials.util.SpeedUtil.calculateUserSpeed(0.2f, false, 1.0f);
        Assertions.assertEquals(1.0f, userWalk, 0.001f);

        // Guard against division by zero when maxSpeed == defaultSpeed
        float userZeroDelta = dev.lyy.friendlycore.modules.essentials.util.SpeedUtil.calculateUserSpeed(0.5f, false, 0.2f);
        Assertions.assertFalse(Float.isNaN(userZeroDelta));
        Assertions.assertFalse(Float.isInfinite(userZeroDelta));
        Assertions.assertEquals(10.0f, userZeroDelta);
    }
}
