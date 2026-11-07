package dev.lyy.friendlycore.modules.rtp;

import org.bukkit.Material;
import org.bukkit.World;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * World-specific RTP configuration holding radius bounds, geometry shapes,
 * distribution algorithms, and biome filters.
 */
public class RtpWorldConfig {

    public enum Shape {
        CIRCLE,
        SQUARE
    }

    public enum WorldType {
        NORMAL,
        NETHER,
        THE_END,
        VOID
    }

    private final String worldName;
    private final boolean enabled;
    private final WorldType worldType;
    private final Shape shape;
    private final int minRadius;
    private final int maxRadius;
    private final int centerX;
    private final int centerZ;
    private final Set<String> blacklistedBiomes;
    private final Set<String> whitelistedBiomes;
    private final RtpSpreadingManager.DistributionAlgorithm distributionAlgorithm;
    private final boolean landingPool;
    private final Set<Material> allowedGround;

    public RtpWorldConfig(String worldName, boolean enabled, WorldType worldType, Shape shape,
                          int minRadius, int maxRadius, int centerX, int centerZ,
                          List<String> blacklistedBiomes, List<String> whitelistedBiomes) {
        this(worldName, enabled, worldType, shape, minRadius, maxRadius, centerX, centerZ, blacklistedBiomes, whitelistedBiomes, RtpSpreadingManager.DistributionAlgorithm.SECTOR_ROUND_ROBIN);
    }

    public RtpWorldConfig(String worldName, boolean enabled, WorldType worldType, Shape shape,
                          int minRadius, int maxRadius, int centerX, int centerZ,
                          List<String> blacklistedBiomes, List<String> whitelistedBiomes,
                          RtpSpreadingManager.DistributionAlgorithm distributionAlgorithm) {
        this(worldName, enabled, worldType, shape, minRadius, maxRadius, centerX, centerZ, blacklistedBiomes,
                whitelistedBiomes, distributionAlgorithm, false, Set.of());
    }

    public RtpWorldConfig(String worldName, boolean enabled, WorldType worldType, Shape shape,
                          int minRadius, int maxRadius, int centerX, int centerZ,
                          List<String> blacklistedBiomes, List<String> whitelistedBiomes,
                          RtpSpreadingManager.DistributionAlgorithm distributionAlgorithm,
                          boolean landingPool, Set<Material> allowedGround) {
        this.landingPool = landingPool;
        this.allowedGround = Set.copyOf(allowedGround);
        this.worldName = worldName;
        this.enabled = enabled;
        this.worldType = worldType;
        this.shape = shape != null ? shape : Shape.CIRCLE;
        this.minRadius = Math.max(0, Math.min(minRadius, maxRadius));
        this.maxRadius = Math.max(0, Math.max(minRadius, maxRadius));
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.distributionAlgorithm = distributionAlgorithm != null ? distributionAlgorithm : RtpSpreadingManager.DistributionAlgorithm.SECTOR_ROUND_ROBIN;

        Set<String> blackBiomes = new HashSet<>();
        if (blacklistedBiomes != null) {
            for (String b : blacklistedBiomes) {
                if (b != null && !b.isBlank()) {
                    blackBiomes.add(b.trim().toUpperCase());
                }
            }
        }
        this.blacklistedBiomes = Collections.unmodifiableSet(blackBiomes);

        Set<String> whiteBiomes = new HashSet<>();
        if (whitelistedBiomes != null) {
            for (String b : whitelistedBiomes) {
                if (b != null && !b.isBlank()) {
                    whiteBiomes.add(b.trim().toUpperCase());
                }
            }
        }
        this.whitelistedBiomes = Collections.unmodifiableSet(whiteBiomes);
    }

    public static WorldType detectWorldType(World world) {
        if (world == null) return WorldType.NORMAL;
        World.Environment env = world.getEnvironment();
        return switch (env) {
            case NETHER -> WorldType.NETHER;
            case THE_END -> WorldType.THE_END;
            default -> WorldType.NORMAL;
        };
    }

    public String getWorldName() {
        return worldName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public WorldType getWorldType() {
        return worldType;
    }

    public Shape getShape() {
        return shape;
    }

    public int getMinRadius() {
        return minRadius;
    }

    public int getMaxRadius() {
        return maxRadius;
    }

    public int getCenterX() {
        return centerX;
    }

    public int getCenterZ() {
        return centerZ;
    }

    public Set<String> getBlacklistedBiomes() {
        return blacklistedBiomes;
    }

    public Set<String> getWhitelistedBiomes() {
        return whitelistedBiomes;
    }

    public RtpSpreadingManager.DistributionAlgorithm getDistributionAlgorithm() {
        return distributionAlgorithm;
    }

    /** Lands from a pre-scanned pool instead of searching; spreading rules are skipped. */
    public boolean isLandingPool() {
        return landingPool;
    }

    public Set<Material> getAllowedGround() {
        return allowedGround;
    }

    public boolean isBiomeAllowed(String biomeName) {
        if (biomeName == null) return true;
        String clean = biomeName.trim().toUpperCase();
        String withoutNamespace = clean.contains(":") ? clean.substring(clean.indexOf(':') + 1) : clean;
        if (!whitelistedBiomes.isEmpty()) {
            return whitelistedBiomes.contains(clean) || whitelistedBiomes.contains(withoutNamespace);
        }
        return !blacklistedBiomes.contains(clean) && !blacklistedBiomes.contains(withoutNamespace);
    }
}
