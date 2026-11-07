package dev.lyy.friendlycore.modules.orders.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderPriceInputTest {
    @Test
    void acceptsFormattedPricesButRejectsNonFiniteAndNonPositiveValues() {
        assertEquals(1234.5, OrderInputManager.parsePrice(" $1,234.50 "));
        assertEquals(0.01, OrderInputManager.parsePrice("0.01"));
        for (String input : new String[]{"Infinity", "$Infinity", "-Infinity", "NaN", "1e309", "0", "-0.0", "-1", "abc", ""}) {
            assertThrows(NumberFormatException.class, () -> OrderInputManager.parsePrice(input), input);
        }
    }
}
