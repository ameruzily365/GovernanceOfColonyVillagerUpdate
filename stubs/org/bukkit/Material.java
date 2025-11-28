package org.bukkit;

public enum Material {
    AIR,
    BEACON,
    WHITE_BANNER,
    ARROW,
    BARRIER,
    COAL,
    FURNACE,
    IRON_INGOT,
    GOLD_INGOT,
    DIAMOND,
    EMERALD,
    STICK,
    LAVA_BUCKET;

    public static Material matchMaterial(String name) {
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
