package org.bukkit.plugin.java;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class JavaPlugin implements Plugin {
    private final Logger logger = Logger.getLogger(getClass().getSimpleName());
    private final Map<String, PluginCommand> commands = new HashMap<>();
    private FileConfiguration config = new FileConfiguration();

    @Override
    public Logger getLogger() {
        return logger;
    }

    @Override
    public File getDataFolder() {
        return new File(".");
    }

    public void onEnable() {
    }

    public void onDisable() {
    }

    public void saveDefaultConfig() {
        // no-op
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public void reloadConfig() {
        config = new FileConfiguration();
    }

    public void saveResource(String resourcePath, boolean replace) {
        // no-op
    }

    public PluginCommand getCommand(String name) {
        return commands.computeIfAbsent(name, PluginCommand::new);
    }

    public Server getServer() {
        return Bukkit.getServer();
    }

    public void reload() {
        // no-op
    }
}
