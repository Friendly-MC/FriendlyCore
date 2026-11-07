package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.NumberFormatter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class NumberFormatterTest {

    private NumberFormatter formatter;

    @BeforeEach
    public void setup() {
        formatter = new NumberFormatter();
    }

    @Test
    public void testStandardFormatting() {
        Assertions.assertEquals("0.00", formatter.format(0.0));
        Assertions.assertEquals("10.50", formatter.format(10.5));
        Assertions.assertEquals("234.56", formatter.format(234.56));
        Assertions.assertEquals("-50.25", formatter.format(-50.25));
    }

    @Test
    public void testSuffixFormatting() {
        Assertions.assertEquals("1K", formatter.format(1_000.0));
        Assertions.assertEquals("10K", formatter.format(10_000.0));
        Assertions.assertEquals("100.5K", formatter.format(100_500.0));
        Assertions.assertEquals("2.5M", formatter.format(2_500_000.0));
        Assertions.assertEquals("10B", formatter.format(10_000_000_000.0));
        Assertions.assertEquals("1.2T", formatter.format(1_200_000_000_000.0));
        Assertions.assertEquals("5Q", formatter.format(5_000_000_000_000_000.0));
        Assertions.assertEquals("9QT", formatter.format(9_000_000_000_000_000_000.0));
    }

    @Test
    public void testNegativeSuffixFormatting() {
        Assertions.assertEquals("-1.5K", formatter.format(-1500.0));
        Assertions.assertEquals("-10M", formatter.format(-10_000_000.0));
    }

    @Test
    public void testShortFormatting() {
        Assertions.assertEquals("0", formatter.formatShort(0.0));
        Assertions.assertEquals("10.5", formatter.formatShort(10.5));
        Assertions.assertEquals("100", formatter.formatShort(100.0));
        Assertions.assertEquals("234.56", formatter.formatShort(234.56));
        Assertions.assertEquals("1K", formatter.formatShort(1_000.0));
        Assertions.assertEquals("1.5K", formatter.formatShort(1_500.0));
        Assertions.assertEquals("10K", formatter.formatShort(10_000.0));
        Assertions.assertEquals("100.5K", formatter.formatShort(100_500.0));
        Assertions.assertEquals("2.5M", formatter.formatShort(2_500_000.0));
        Assertions.assertEquals("-50", formatter.formatShort(-50.0));
        Assertions.assertEquals("-1.5K", formatter.formatShort(-1500.0));
        Assertions.assertEquals("0", formatter.formatShort(Double.NaN));
        Assertions.assertEquals("0", formatter.formatShort(Double.POSITIVE_INFINITY));
    }

    @Test
    public void testExactFormatting() {
        Assertions.assertEquals("0.00", formatter.formatExact(0.0));
        Assertions.assertEquals("1,000.00", formatter.formatExact(1_000.0));
        Assertions.assertEquals("2,500,000.00", formatter.formatExact(2_500_000.0));
        Assertions.assertEquals("0.00", formatter.formatExact(Double.NaN));
    }

    @Test
    public void testSpecialDoubleValues() {
        Assertions.assertEquals("0.00", formatter.format(Double.NaN));
        Assertions.assertEquals("0.00", formatter.format(Double.POSITIVE_INFINITY));
        Assertions.assertEquals("0.00", formatter.format(Double.NEGATIVE_INFINITY));
    }

    @Test
    public void testParseAmountStandard() {
        Assertions.assertEquals(0.0, NumberFormatter.parseAmount("0"));
        Assertions.assertEquals(100.0, NumberFormatter.parseAmount("100"));
        Assertions.assertEquals(1234.56, NumberFormatter.parseAmount("1234.56"));
        Assertions.assertEquals(-500.0, NumberFormatter.parseAmount("-500"));
        Assertions.assertEquals(75.5, NumberFormatter.parseAmount("+75.5"));
    }

    @Test
    public void testParseAmountSuffixes() {
        Assertions.assertEquals(1_000.0, NumberFormatter.parseAmount("1k"));
        Assertions.assertEquals(1_000.0, NumberFormatter.parseAmount("1K"));
        Assertions.assertEquals(2_500_000.0, NumberFormatter.parseAmount("2.5m"));
        Assertions.assertEquals(2_500_000.0, NumberFormatter.parseAmount("2.5M"));
        Assertions.assertEquals(10_000_000_000.0, NumberFormatter.parseAmount("10b"));
        Assertions.assertEquals(1_000_000_000_000.0, NumberFormatter.parseAmount("1t"));
        Assertions.assertEquals(5_000_000_000_000_000.0, NumberFormatter.parseAmount("5q"));
        Assertions.assertEquals(2_000_000_000_000_000_000.0, NumberFormatter.parseAmount("2qt"));
    }

    @Test
    public void testParseAmountInvalid() {
        Assertions.assertThrows(NumberFormatException.class, () -> NumberFormatter.parseAmount(null));
        Assertions.assertThrows(NumberFormatException.class, () -> NumberFormatter.parseAmount(""));
        Assertions.assertThrows(NumberFormatException.class, () -> NumberFormatter.parseAmount("   "));
        Assertions.assertThrows(NumberFormatException.class, () -> NumberFormatter.parseAmount("k"));
        Assertions.assertThrows(NumberFormatException.class, () -> NumberFormatter.parseAmount("qt"));
        Assertions.assertThrows(NumberFormatException.class, () -> NumberFormatter.parseAmount("invalid_amount"));
        Assertions.assertThrows(NumberFormatException.class, () -> NumberFormatter.parseAmount("12.34.56"));
    }
}
