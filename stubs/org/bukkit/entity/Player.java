package org.bukkit.entity;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Player extends CommandSender {
    private final UUID uuid = UUID.randomUUID();
    private final PlayerInventory inventory = new PlayerInventory();
    private InventoryView openInventoryView;
    private final ConcurrentHashMap<PotionEffectType, PotionEffect> effects = new ConcurrentHashMap<>();
    private String name = "Player";
    private World world = new World("world");
    private Player killer;

    public UUID getUniqueId() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public PlayerInventory getInventory() {
        return inventory;
    }

    public void sendMessage(String message) {
        // no-op
    }

    public boolean hasPermission(String permission) {
        return false;
    }

    public World getWorld() {
        return world;
    }

    public void setWorld(World world) {
        this.world = world;
    }

    public PermissionAttachment addAttachment(Plugin plugin) {
        return new PermissionAttachment(plugin, this);
    }

    public Location getLocation() {
        return new Location(world, 0, 0, 0);
    }

    public Player getKiller() {
        return killer;
    }

    public void setKiller(Player killer) {
        this.killer = killer;
    }

    public void addPotionEffect(PotionEffect effect) {
        if (effect != null) {
            effects.put(effect.getType(), effect);
        }
    }

    public void removePotionEffect(PotionEffectType type) {
        if (type != null) {
            effects.remove(type);
        }
    }

    public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        // no-op
    }

    public void sendActionBar(String message) {
        // no-op
    }

    public void openInventory(Inventory inventory) {
        this.openInventoryView = new InventoryView(inventory, null);
    }

    public org.bukkit.inventory.ItemStack getItemInHand() {
        return inventory.getItemInHand();
    }

    public void closeInventory() {
        this.openInventoryView = null;
    }

    public InventoryView getOpenInventory() {
        return openInventoryView;
    }

    public <T> void spawnParticle(Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ, double extra, T data) {
        // no-op
    }

    public void playSound(Location location, org.bukkit.Sound sound, float volume, float pitch) {
        // no-op
    }

    public void teleport(Location location) {
        // no-op
    }
}
