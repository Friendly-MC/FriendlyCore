package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.kits.Kit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class KitModelTest {

    @Test
    public void testKitCreationAndDefaults() {
        Kit kit = new Kit("starter", List.of());

        Assertions.assertEquals("starter", kit.getName());
        Assertions.assertEquals("friendlycore.kits.starter", kit.getPermission());
        Assertions.assertEquals(0, kit.getCooldownSeconds());
        Assertions.assertTrue(kit.getItems().isEmpty());
    }

    @Test
    public void testKitCustomPermissionAndCooldown() {
        Kit kit = new Kit("vip", List.of(), "custom.vip.kit", 3600);

        Assertions.assertEquals("vip", kit.getName());
        Assertions.assertEquals("custom.vip.kit", kit.getPermission());
        Assertions.assertEquals(3600, kit.getCooldownSeconds());
    }

    @Test
    public void testKitImmutability() {
        Kit kit = new Kit("pvp", List.of());
        Assertions.assertThrows(UnsupportedOperationException.class, () -> kit.getItems().add(null));
    }
}
