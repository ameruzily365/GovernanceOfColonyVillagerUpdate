package org.bukkit.scheduler;

import org.bukkit.plugin.java.JavaPlugin;

public class BukkitScheduler {
    public BukkitTask runTaskTimer(JavaPlugin plugin, Runnable task, long delay, long period) {
        task.run();
        return new BukkitTask();
    }

    public BukkitTask runTask(JavaPlugin plugin, Runnable task) {
        task.run();
        return new BukkitTask();
    }

    public BukkitTask runTaskLater(JavaPlugin plugin, Runnable task, long delay) {
        task.run();
        return new BukkitTask();
    }
}
