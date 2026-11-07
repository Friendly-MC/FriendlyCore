package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.tools.CustomToolType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CustomToolTypeTest {

    @Test
    public void testDrillTypes() {
        Assertions.assertTrue(CustomToolType.DRILL_PICKAXE.isDrill());
        Assertions.assertTrue(CustomToolType.DRILL_SHOVEL.isDrill());
        Assertions.assertFalse(CustomToolType.TREE_CHOPPER.isDrill());
    }

    @Test
    public void testChopperTypes() {
        Assertions.assertTrue(CustomToolType.TREE_CHOPPER.isChopper());
        Assertions.assertFalse(CustomToolType.DRILL_PICKAXE.isChopper());
        Assertions.assertFalse(CustomToolType.DRILL_SHOVEL.isChopper());
    }
}
