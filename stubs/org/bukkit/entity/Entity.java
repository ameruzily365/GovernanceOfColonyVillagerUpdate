package org.bukkit.entity;

import org.bukkit.Location;

public class Entity {
    private Location location;

    public void remove() {
        // no-op
    }

    public void teleport(Location location) {
        this.location = location;
    }

    public Location getLocation() {
        return location;
    }
}
