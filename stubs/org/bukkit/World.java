package org.bukkit;

import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Collections;

public class World {
    private final String name;

    public World(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public <T> T spawn(Location location, Class<T> clazz) {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception ex) {
            return null;
        }
    }

    public void dropItemNaturally(Location location, ItemStack item) {
        // no-op in stub environment
    }

    public <T> void spawnParticle(Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ, double extra, T data) {
        // no-op in stub environment
    }

    public Collection<Entity> getNearbyEntities(Location location, double x, double y, double z) {
        return Collections.emptyList();
    }
}
