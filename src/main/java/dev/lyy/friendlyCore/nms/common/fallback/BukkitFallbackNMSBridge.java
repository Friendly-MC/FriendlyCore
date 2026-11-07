package dev.lyy.friendlycore.nms.common.fallback;

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
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.joml.Vector3f;

import java.util.*;

/**
 * Universal Bukkit-only fallback bridge providing safe degradation when running on unsupported versions or mock environments.
 */
public class BukkitFallbackNMSBridge implements NMSBridge {

    private final Plugin plugin;
    private final GlowHandler glowHandler;
    private final ContainerInspector containerInspector;
    private final ChunkScanner chunkScanner;
    private final DisplayEntityHandler displayEntityHandler;
    private final PacketHandler packetHandler;
    private final PetPathfinderBridge petPathfinderBridge;

    public BukkitFallbackNMSBridge(Plugin plugin) {
        this.plugin = plugin;
        this.glowHandler = createFallbackGlowHandler();
        this.containerInspector = createFallbackContainerInspector();
        this.chunkScanner = createFallbackChunkScanner();
        this.displayEntityHandler = createFallbackDisplayHandler();
        this.packetHandler = createFallbackPacketHandler();
        this.petPathfinderBridge = createFallbackPathfinderBridge();
    }

    @Override
    public String getVersionName() {
        return "Bukkit-Fallback (Universal)";
    }

    @Override
    public boolean isNative() {
        return false;
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

    private GlowHandler createFallbackGlowHandler() {
        return new GlowHandler() {
            @Override
            public void setFakeGlow(Player viewer, Entity target, ChatColor color, boolean glowing) {
                target.setGlowing(glowing);
                Scoreboard board = viewer.getScoreboard();
                String teamName = "fc_g_" + color.name().toLowerCase();
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
        };
    }

    private ContainerInspector createFallbackContainerInspector() {
        return new ContainerInspector() {
            @Override
            public List<ItemStack> getContainerContents(ItemStack containerItem) {
                if (containerItem == null || !(containerItem.getItemMeta() instanceof BlockStateMeta bsm)) {
                    return Collections.emptyList();
                }
                BlockState state = bsm.getBlockState();
                if (state instanceof ShulkerBox box) {
                    return Arrays.asList(box.getInventory().getContents());
                }
                return Collections.emptyList();
            }

            @Override
            public boolean hasContainerContents(ItemStack item) {
                return item != null && item.getItemMeta() instanceof BlockStateMeta bsm && bsm.hasBlockState();
            }
        };
    }

    private ChunkScanner createFallbackChunkScanner() {
        return new ChunkScanner() {
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
            public void fastSectionScan(org.bukkit.Chunk chunk, int sectionIndex, SectionBlockVisitor visitor) {
            }
        };
    }

    private DisplayEntityHandler createFallbackDisplayHandler() {
        return new DisplayEntityHandler() {
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
        };
    }

    private PacketHandler createFallbackPacketHandler() {
        return new PacketHandler() {
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
        };
    }

    private PetPathfinderBridge createFallbackPathfinderBridge() {
        return new PetPathfinderBridge() {
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
        };
    }
}
