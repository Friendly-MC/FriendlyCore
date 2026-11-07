package dev.lyy.friendlycore;

import dev.lyy.friendlycore.nms.NMSLoader;
import dev.lyy.friendlycore.nms.common.NMSBridge;
import dev.lyy.friendlycore.nms.common.fallback.BukkitFallbackNMSBridge;
import dev.lyy.friendlycore.nms.v1_21_R4.NMSBridge_v1_21_R4;
import dev.lyy.friendlycore.nms.v26_2.NMSBridge_v26_2;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class NMSBridgeTest {
    @Test
    void containerBridgeSupportsLegacyAnd26ItemStreamsWithoutDroppingContents() throws Exception {
        ItemStack token = new TestContainerItem();
        // This reflection test needs no live block registry. The supplied material is non-air.
        var blockType = org.bukkit.Material.class.getDeclaredField("blockType");
        blockType.setAccessible(true);
        Object originalBlockType = blockType.get(org.bukkit.Material.SHULKER_BOX);
        blockType.set(org.bukkit.Material.SHULKER_BOX, (java.util.function.Supplier<Object>) () -> null);
        try {
            for (Object contents : List.of(new LegacyContents(token), new ModernContents(token))) {
                var inspector = new NMSBridge_v26_2().getContainerInspector();
                fixture = new FixtureStack(contents);
                var fields = java.util.Map.of(
                    "initialized", true,
                    "asNMSCopyMethod", NMSBridgeTest.class.getMethod("asNmsFixture", ItemStack.class),
                    "asBukkitCopyMethod", NMSBridgeTest.class.getMethod("asBukkitFixture", Object.class),
                    "getComponentMethod", FixtureStack.class.getMethod("get", Object.class),
                    "containerDataComponent", new Object());
                for (var entry : fields.entrySet()) {
                    var field = inspector.getClass().getDeclaredField(entry.getKey());
                    field.setAccessible(true);
                    field.set(inspector, entry.getValue());
                }
                var result = inspector.getContainerContents(token);
                Assertions.assertEquals(1, result.size());
                Assertions.assertSame(token, result.getFirst());
            }
        } finally {
            blockType.set(org.bukkit.Material.SHULKER_BOX, originalBlockType);
            fixture = null;
        }
    }

    private static FixtureStack fixture;
    public static FixtureStack asNmsFixture(ItemStack item) { return fixture; }
    public static ItemStack asBukkitFixture(Object item) { return (ItemStack) item; }
    public record FixtureStack(Object contents) { public Object get(Object type) { return contents; } }
    public record LegacyContents(ItemStack item) { public java.util.stream.Stream<ItemStack> stream() { return java.util.stream.Stream.of(item); } }
    public record ModernContents(ItemStack item) { public java.util.stream.Stream<ItemStack> allItemsCopyStream() { return java.util.stream.Stream.of(item); } }
    private static class TestContainerItem extends ItemStack {
        @Override public org.bukkit.Material getType() { return org.bukkit.Material.SHULKER_BOX; }
        @Override public org.bukkit.inventory.meta.ItemMeta getItemMeta() { throw new AssertionError("Native contents must not silently fall back"); }
    }


    @Test
    public void testVersionImplementations() {
        NMSBridge bridge121 = new NMSBridge_v1_21_R4();
        Assertions.assertEquals("1.21.11 (v1_21_R4 / Java 21)", bridge121.getVersionName());
        Assertions.assertTrue(bridge121.isNative());
        Assertions.assertNotNull(bridge121.getGlowHandler());
        Assertions.assertNotNull(bridge121.getContainerInspector());
        Assertions.assertNotNull(bridge121.getChunkScanner());
        Assertions.assertNotNull(bridge121.getDisplayEntityHandler());
        Assertions.assertNotNull(bridge121.getPacketHandler());
        Assertions.assertNotNull(bridge121.getPetPathfinderBridge());

        NMSBridge bridge26 = new NMSBridge_v26_2();
        Assertions.assertEquals("26.2 (v26_2 / Java 25 / Native Mojang)", bridge26.getVersionName());
        Assertions.assertTrue(bridge26.isNative());
        Assertions.assertNotNull(bridge26.getGlowHandler());
        Assertions.assertNotNull(bridge26.getContainerInspector());
        Assertions.assertNotNull(bridge26.getChunkScanner());
        Assertions.assertNotNull(bridge26.getDisplayEntityHandler());
        Assertions.assertNotNull(bridge26.getPacketHandler());
        Assertions.assertNotNull(bridge26.getPetPathfinderBridge());
    }

    @Test
    public void testFallbackBridge() {
        BukkitFallbackNMSBridge fallback = new BukkitFallbackNMSBridge(null);
        Assertions.assertEquals("Bukkit-Fallback (Universal)", fallback.getVersionName());
        Assertions.assertFalse(fallback.isNative());

        // Container inspector null/empty safety
        List<ItemStack> items = fallback.getContainerInspector().getContainerContents(null);
        Assertions.assertTrue(items.isEmpty());
        Assertions.assertFalse(fallback.getContainerInspector().hasContainerContents(null));

        // Display handler virtual ID contract
        int id = fallback.getDisplayEntityHandler().spawnVirtualTextDisplay(List.of(), null, null, null, true);
        Assertions.assertEquals(-1, id);
    }
}
