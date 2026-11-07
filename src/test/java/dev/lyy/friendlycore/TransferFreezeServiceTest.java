package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.TransferFreezeService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

public class TransferFreezeServiceTest {

    @Test
    public void testFreezeSetTracksPlayersWithoutBukkit() {
        TransferFreezeService service = new TransferFreezeService(null);
        UUID uuid = UUID.randomUUID();

        Assertions.assertFalse(service.isFrozen(uuid));
        Assertions.assertFalse(service.isFrozen((UUID) null));

        service.freeze(uuid);
        Assertions.assertTrue(service.isFrozen(uuid), "Frozen players must be immune during server transfer");

        service.unfreeze(uuid);
        Assertions.assertFalse(service.isFrozen(uuid), "Cancel or arrival must release the combat freeze");
    }

    @Test
    public void testFrozenSourceResolutionIsNullSafe() {
        Assertions.assertNull(TransferFreezeService.resolvePlayer(null));
        Assertions.assertFalse(TransferFreezeService.isFrozenSource(null, uuid -> true));
    }

    @Test
    public void testCombatLogoutShouldBeSkippedWhileFrozen() {
        UUID uuid = UUID.randomUUID();
        TransferFreezeService service = new TransferFreezeService(null);
        service.freeze(uuid);

        boolean spawnCombatNpc = !service.isFrozen(uuid);
        Assertions.assertFalse(spawnCombatNpc, "Transfer disconnect must not spawn a combat-logger NPC or drop items");
    }
}
