package dev.lyy.friendlycore.modules.orders.enums;

import org.bukkit.Material;

public enum OrderFilter {
    ALL("All"),
    BLOCKS("Blocks"),
    TOOLS("Tools"),
    FOOD("Food"),
    COMBAT("Combat"),
    POTIONS("Potions"),
    BOOKS("Books"),
    INGREDIENTS("Ingredients"),
    UTILITIES("Utilities");

    private final String displayName;

    OrderFilter(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean matches(Material material) {
        if (this == ALL || material == null) return true;
        String name = material.name();

        return switch (this) {
            case BLOCKS -> material.isBlock() || material.isSolid();
            case TOOLS -> name.endsWith("_PICKAXE") || name.endsWith("_AXE") || name.endsWith("_SHOVEL")
                    || name.endsWith("_HOE") || name.equals("SHEARS") || name.equals("FISHING_ROD")
                    || name.equals("FLINT_AND_STEEL") || name.equals("BRUSH");
            case FOOD -> material.isEdible() || name.contains("APPLE") || name.contains("BERRIES")
                    || name.contains("CARROT") || name.contains("POTATO") || name.contains("BREAD")
                    || name.contains("BEEF") || name.contains("PORKCHOP") || name.contains("CHICKEN")
                    || name.contains("MUTTON") || name.contains("FISH") || name.contains("SALMON")
                    || name.contains("STEW") || name.contains("SOUP");
            case COMBAT -> name.endsWith("_SWORD") || name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                    || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS") || name.equals("BOW")
                    || name.equals("CROSSBOW") || name.equals("SHIELD") || name.equals("TRIDENT")
                    || name.equals("MACE") || name.contains("ARROW") || name.equals("TOTEM_OF_UNDYING");
            case POTIONS -> name.contains("POTION") || name.contains("TIPPED_ARROW");
            case BOOKS -> name.contains("BOOK");
            case INGREDIENTS -> name.endsWith("_INGOT") || name.endsWith("_NUGGET") || name.endsWith("_GEM")
                    || name.equals("DIAMOND") || name.equals("EMERALD") || name.equals("REDSTONE")
                    || name.equals("LAPIS_LAZULI") || name.equals("QUARTZ") || name.equals("AMETHYST_SHARD")
                    || name.equals("COAL") || name.equals("CHARCOAL") || name.equals("COPPER_INGOT")
                    || name.equals("NETHERITE_SCRAP") || name.equals("NETHERITE_INGOT")
                    || name.equals("STRING") || name.equals("FEATHER") || name.equals("GUNPOWDER")
                    || name.equals("BLAZE_ROD") || name.equals("BLAZE_POWDER") || name.equals("ENDER_PEARL")
                    || name.equals("EYE_OF_ENDER") || name.equals("GHAST_TEAR") || name.equals("SLIME_BALL")
                    || name.equals("MAGMA_CREAM") || name.equals("GLOWSTONE_DUST") || name.equals("PRISMARINE_SHARD")
                    || name.equals("PRISMARINE_CRYSTALS") || name.equals("DRAGON_BREATH") || name.equals("HONEYCOMB");
            case UTILITIES -> !material.isBlock() && !name.endsWith("_SWORD") && !name.endsWith("_PICKAXE");
            default -> true;
        };
    }
}
