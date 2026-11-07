package dev.lyy.friendlycore.nms.common;

import dev.lyy.friendlycore.nms.common.NMSBridge;
import dev.lyy.friendlycore.nms.common.chunk.ChunkScanner;
import dev.lyy.friendlycore.nms.common.container.ContainerInspector;
import dev.lyy.friendlycore.nms.common.display.DisplayEntityHandler;
import dev.lyy.friendlycore.nms.common.glow.GlowHandler;
import dev.lyy.friendlycore.nms.common.network.PacketHandler;
import dev.lyy.friendlycore.nms.common.pathfinder.PetPathfinderBridge;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Shared bridge for the modern Mojang-mapped server implementations.
 */
public class ModernNMSBridge implements NMSBridge {

    private final String versionName;
    private final GlowHandler glowHandler;
    private final ContainerInspector containerInspector;
    private final ChunkScanner chunkScanner;
    private final DisplayEntityHandler displayEntityHandler;
    private final PacketHandler packetHandler;
    private final PetPathfinderBridge petPathfinderBridge;

    protected ModernNMSBridge(String versionName, String glowPrefix) {
        this.versionName = versionName;
        this.glowHandler = new GlowHandlerModern(glowPrefix);
        this.containerInspector = new ContainerInspectorModern();
        this.chunkScanner = new ChunkScannerModern();
        this.displayEntityHandler = new DisplayEntityHandlerModern();
        this.packetHandler = new PacketHandlerModern();
        this.petPathfinderBridge = new PetPathfinderBridgeModern();
    }

    @Override
    public String getVersionName() {
        return versionName;
    }

    @Override
    public boolean isNative() {
        return true;
    }

    @Override
    public GlowHandler getGlowHandler() {
        return glowHandler;
    }

    @Override
    public ContainerInspector getContainerInspector() {
        return containerInspector;
    }

    @Override
    public ChunkScanner getChunkScanner() {
        return chunkScanner;
    }

    @Override
    public DisplayEntityHandler getDisplayEntityHandler() {
        return displayEntityHandler;
    }

    @Override
    public PacketHandler getPacketHandler() {
        return packetHandler;
    }

    @Override
    public PetPathfinderBridge getPetPathfinderBridge() {
        return petPathfinderBridge;
    }

    private static class GlowHandlerModern implements GlowHandler {
        private final String prefix;

        private GlowHandlerModern(String prefix) {
            this.prefix = prefix;
        }
        @Override
        public void setFakeGlow(Player viewer, Entity target, ChatColor color, boolean glowing) {
            target.setGlowing(glowing);
            Scoreboard board = viewer.getScoreboard();
            String teamName = prefix + color.name().toLowerCase();
            if (teamName.length() > 16) teamName = teamName.substring(0, 16);
            Team team = board.getTeam(teamName);
            if (team == null) {
                team = board.registerNewTeam(teamName);
                team.setColor(color);
            }
            String entry = target instanceof Player ? target.getName() : target.getUniqueId().toString();
            if (glowing) {
                team.addEntry(entry);
            } else {
                team.removeEntry(entry);
            }
        }

        @Override
        public void broadcastFakeGlow(Collection<? extends Player> viewers, Entity target, ChatColor color, boolean glowing) {
            for (Player viewer : viewers) {
                setFakeGlow(viewer, target, color, glowing);
            }
        }

        @Override
        public void clearFakeGlow(Player viewer, Entity target) {
            target.setGlowing(false);
        }
    }

    private static class ContainerInspectorModern implements ContainerInspector {
        private Method asNMSCopyMethod;
        private Method asBukkitCopyMethod;
        private Object containerDataComponent;
        private Method getComponentMethod;
        private Method streamMethod;
        private boolean initialized = false;

        private void initReflection() {
            if (initialized) return;
            initialized = true;
            try {
                Class<?> craftItemStackClass = findCraftClass("inventory.CraftItemStack");
                if (craftItemStackClass != null) {
                    asNMSCopyMethod = craftItemStackClass.getMethod("asNMSCopy", ItemStack.class);
                    asBukkitCopyMethod = craftItemStackClass.getMethod("asBukkitCopy", findNmsClass("world.item.ItemStack"));
                }
                Class<?> dataComponentsClass = findNmsClass("core.component.DataComponents");
                if (dataComponentsClass != null) {
                    Field containerField = dataComponentsClass.getField("CONTAINER");
                    containerDataComponent = containerField.get(null);
                }
                Class<?> nmsItemClass = findNmsClass("world.item.ItemStack");
                if (nmsItemClass != null && containerDataComponent != null) {
                    Class<?> dataComponentTypeClass = findNmsClass("core.component.DataComponentType");
                    getComponentMethod = nmsItemClass.getMethod("get", dataComponentTypeClass);
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        public List<ItemStack> getContainerContents(ItemStack containerItem) {
            if (containerItem == null || containerItem.getType().isAir()) return Collections.emptyList();
            initReflection();
            if (asNMSCopyMethod != null && getComponentMethod != null && containerDataComponent != null) {
                try {
                    Object nmsStack = asNMSCopyMethod.invoke(null, containerItem);
                    if (nmsStack != null) {
                        Object containerContents = getComponentMethod.invoke(nmsStack, containerDataComponent);
                        if (containerContents != null) {
                            if (streamMethod == null) {
                                try {
                                    streamMethod = containerContents.getClass().getMethod("allItemsCopyStream"); // 26.2
                                } catch (NoSuchMethodException legacy) {
                                    streamMethod = containerContents.getClass().getMethod("stream");
                                }
                            }
                            java.util.stream.Stream<?> stream = (java.util.stream.Stream<?>) streamMethod.invoke(containerContents);
                            List<ItemStack> result = new ArrayList<>();
                            stream.forEach(item -> {
                                try {
                                    result.add((ItemStack) asBukkitCopyMethod.invoke(null, item));
                                } catch (Throwable ignored) {}
                            });
                            return result;
                        }
                    }
                } catch (Throwable ignored) {}
            }

            if (containerItem.getItemMeta() instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox box) {
                return Arrays.asList(box.getInventory().getContents());
            }
            return Collections.emptyList();
        }

        @Override
        public boolean hasContainerContents(ItemStack item) {
            if (item == null || item.getType().isAir()) return false;
            initReflection();
            if (asNMSCopyMethod != null && getComponentMethod != null && containerDataComponent != null) {
                try {
                    Object nmsStack = asNMSCopyMethod.invoke(null, item);
                    if (nmsStack != null) {
                        return getComponentMethod.invoke(nmsStack, containerDataComponent) != null;
                    }
                } catch (Throwable ignored) {}
            }
            return item.getItemMeta() instanceof BlockStateMeta bsm && bsm.hasBlockState();
        }
    }

    private static class ChunkScannerModern implements ChunkScanner {
        @Override
        public int findHighestSafeBlock(World world, int x, int z, int minSectionY, int maxSectionY) {
            Block highest = world.getHighestBlockAt(x, z);
            if (highest.getType().isSolid() && !highest.isLiquid()) {
                return highest.getY() + 1;
            }
            return -1;
        }

        @Override
        public boolean isLocationSafe(World world, int x, int y, int z) {
            Block feet = world.getBlockAt(x, y, z);
            Block head = world.getBlockAt(x, y + 1, z);
            Block ground = world.getBlockAt(x, y - 1, z);
            return feet.isEmpty() && head.isEmpty() && ground.getType().isSolid() && !ground.isLiquid();
        }

        @Override
        public void fastSectionScan(Chunk chunk, int sectionIndex, SectionBlockVisitor visitor) {
        }
    }

    private static class DisplayEntityHandlerModern implements DisplayEntityHandler {
        @Override
        public int spawnVirtualTextDisplay(Collection<? extends Player> viewers, Location location, Component text, Vector3f scale, boolean billboard) {
            return -1;
        }

        @Override
        public void updateVirtualTextDisplay(Collection<? extends Player> viewers, int entityId, Component text, Vector3f scale, int interpolationTicks) {
        }

        @Override
        public void moveVirtualDisplay(Collection<? extends Player> viewers, int entityId, Location newLocation) {
        }

        @Override
        public void destroyVirtualEntity(Collection<? extends Player> viewers, int entityId) {
        }
    }

    private static class PacketHandlerModern implements PacketHandler {
        @Override
        public int getPing(Player player) {
            return player.getPing();
        }

        @Override
        public void sendPacket(Player player, Object packet) {
        }

        @Override
        public void injectPlayer(Player player) {
        }

        @Override
        public void uninjectPlayer(Player player) {
        }
    }

    private static class PetPathfinderBridgeModern implements PetPathfinderBridge {
        @Override
        public void setFollowOwnerGoal(Mob mob, Player owner, double speed, float startDistance, float stopDistance) {
            if (mob.getPathfinder() != null) {
                mob.getPathfinder().moveTo(owner.getLocation(), speed);
            }
        }

        @Override
        public void clearVanillaGoals(Mob mob) {
        }

        @Override
        public boolean navigateTo(Mob mob, Location location, double speed) {
            return mob.getPathfinder() != null && mob.getPathfinder().moveTo(location, speed);
        }
    }

    private static Class<?> findCraftClass(String subpackage) {
        try {
            String v = Bukkit.getServer().getClass().getPackage().getName();
            return Class.forName(v + "." + subpackage);
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName("org.bukkit.craftbukkit." + subpackage);
            } catch (ClassNotFoundException ex) {
                return null;
            }
        }
    }

    private static Class<?> findNmsClass(String fullName) {
        try {
            return Class.forName("net.minecraft." + fullName);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
