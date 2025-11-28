package org.bukkit;

import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;

public final class Bukkit {
    private static final Server SERVER = new Server();
    private static final PluginManager PLUGIN_MANAGER = new PluginManager();
    private static final BukkitScheduler SCHEDULER = new BukkitScheduler();

    private Bukkit() {
    }

    public static Server getServer() {
        return SERVER;
    }

    public static PluginManager getPluginManager() {
        return PLUGIN_MANAGER;
    }

    public static BukkitScheduler getScheduler() {
        return SCHEDULER;
    }

    public static Collection<Player> getOnlinePlayers() {
        return Collections.emptyList();
    }

    public static Player getPlayer(String name) {
        return null;
    }

    public static Player getPlayer(UUID uuid) {
        return null;
    }

    public static OfflinePlayer getOfflinePlayer(UUID uuid) {
        return new OfflinePlayer(uuid, null);
    }

    public static World getWorld(String name) {
        return name == null ? null : new World(name);
    }
}
