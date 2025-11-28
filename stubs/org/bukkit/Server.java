package org.bukkit;

import org.bukkit.plugin.PluginManager;
import org.bukkit.entity.Player;

import java.util.UUID;

public class Server {
    private final PluginManager pluginManager = new PluginManager();
    private final ServicesManager servicesManager = new ServicesManager();
    private final org.bukkit.scheduler.BukkitScheduler scheduler = new org.bukkit.scheduler.BukkitScheduler();

    public PluginManager getPluginManager() {
        return pluginManager;
    }

    public ServicesManager getServicesManager() {
        return servicesManager;
    }

    public org.bukkit.scheduler.BukkitScheduler getScheduler() {
        return scheduler;
    }

    public void broadcastMessage(String message) {
        // no-op
    }

    public Player getPlayer(UUID uuid) {
        return uuid == null ? null : new Player();
    }

    public org.bukkit.inventory.Inventory createInventory(Object holder, int size, String title) {
        return new org.bukkit.inventory.Inventory(size, title);
    }
}
