package org.bukkit.plugin;

import org.bukkit.Server;

import java.io.File;
import java.util.logging.Logger;

public interface Plugin {
    default String getName() {
        return "Plugin";
    }

    default Server getServer() {
        return org.bukkit.Bukkit.getServer();
    }

    default Logger getLogger() {
        return Logger.getLogger(getName());
    }

    default File getDataFolder() {
        return new File(".");
    }
}
