package dev.lyy.friendlycore.modules.essentials;

import dev.lyy.friendlycore.modules.essentials.util.InvseeLayout;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class InvseeLayoutTest {

    private static int merge(String[] base, String[] edited, String[] target) {
        return InvseeLayout.mergeEdits(base, edited, i -> target[i], (i, item) -> target[i] = item, Objects::equals);
    }

    @Test
    void untouchedStaleSlotNeverRestoresAnItemTheTargetAlreadyDropped() {
        // Admin view still shows the diamond the target just dropped; the admin edits a different slot.
        String[] base = {"diamond", null};
        String[] edited = {"diamond", "stone"};
        String[] target = {null, null};

        assertEquals(0, merge(base, edited, target));
        assertArrayEquals(new String[]{null, "stone"}, target, "only the slot the admin changed is written");
    }

    @Test
    void editIsSkippedWhereTheTargetChangedTheSlotMeanwhile() {
        String[] base = {"diamond", "dirt"};
        String[] edited = {null, null};
        String[] target = {"apple", "dirt"};

        assertEquals(1, merge(base, edited, target));
        assertArrayEquals(new String[]{"apple", null}, target);
    }

    @Test
    void noEditsMeansNoWrites() {
        String[] base = {"a", "b"};
        String[] target = {"x", "y"};
        assertEquals(0, merge(base, base.clone(), target));
        assertArrayEquals(new String[]{"x", "y"}, target, "target-side changes survive a no-op save");
    }

    @Test
    void guiSlotsMatchTheDocumentedLayout() {
        assertEquals(0, InvseeLayout.guiSlot(0));
        assertEquals(35, InvseeLayout.guiSlot(35));
        assertEquals(45, InvseeLayout.guiSlot(36)); // helmet
        assertEquals(48, InvseeLayout.guiSlot(39)); // boots
        assertEquals(50, InvseeLayout.guiSlot(40)); // offhand
        for (int i = 0; i < InvseeLayout.SIZE; i++) {
            assertFalse(InvseeLayout.isLockedGuiSlot(InvseeLayout.guiSlot(i)), "editable slot " + i + " must not be locked");
        }
        for (int slot : new int[]{36, 44, 49, 51, 52, 53}) {
            assertTrue(InvseeLayout.isLockedGuiSlot(slot));
        }
    }
}
