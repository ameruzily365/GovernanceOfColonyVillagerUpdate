package org.bukkit.potion;

public class PotionEffectType {
    public static final PotionEffectType SLOW_DIGGING = new PotionEffectType("SLOW_DIGGING");
    public static final PotionEffectType MINING_FATIGUE = new PotionEffectType("MINING_FATIGUE");

    private final String name;

    private PotionEffectType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
