package org.bukkit.block;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

public class Block {
    private final Location location;
    private Material type = Material.BEACON;

    public Block(World world, int x, int y, int z) {
        this.location = new Location(world, x, y, z);
    }

    public Location getLocation() {
        return location;
    }

    public World getWorld() {
        return location.getWorld();
    }

    public Material getType() {
        return type;
    }

    public void setType(Material type) {
        this.type = type;
    }
}
