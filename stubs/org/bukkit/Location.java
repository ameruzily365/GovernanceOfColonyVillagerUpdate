package org.bukkit;

import org.bukkit.block.Block;

public class Location implements Cloneable {
    private World world;
    private double x;
    private double y;
    private double z;

    public Location(World world, double x, double y, double z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public World getWorld() {
        return world;
    }

    public int getBlockX() {
        return (int) x;
    }

    public int getBlockY() {
        return (int) y;
    }

    public int getBlockZ() {
        return (int) z;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public Location add(double x, double y, double z) {
        this.x += x;
        this.y += y;
        this.z += z;
        return this;
    }

    public double distanceSquared(Location other) {
        if (other == null || other.getWorld() == null || getWorld() == null) {
            return Double.MAX_VALUE;
        }
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public Location clone() {
        return new Location(world, x, y, z);
    }

    public Block getBlock() {
        if (world == null) {
            return null;
        }
        return new Block(world, getBlockX(), getBlockY(), getBlockZ());
    }
}
