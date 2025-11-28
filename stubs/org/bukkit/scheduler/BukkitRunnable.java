package org.bukkit.scheduler;

import org.bukkit.plugin.java.JavaPlugin;

public abstract class BukkitRunnable implements Runnable {
    public BukkitTask runTaskTimer(JavaPlugin plugin, long delay, long period) {
        run();
        return new BukkitTask();
    }

    public BukkitTask runTaskLater(JavaPlugin plugin, long delay) {
        run();
        return new BukkitTask();
    }
}
