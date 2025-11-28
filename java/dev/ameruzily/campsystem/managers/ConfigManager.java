package dev.ameruzily.campsystem.managers;

import dev.ameruzily.campsystem.CampSystem;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {
    private final CampSystem plugin;
    private FileConfiguration config;

    public ConfigManager(CampSystem plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
    }

    public void reload() {
        plugin.reloadConfig();
        config = plugin.getConfig();
    }

    public FileConfiguration raw() { return config; }

    public int getInt(String path, int def) { return config.getInt(path, def); }
    public long getLong(String path, long def) { return config.getLong(path, def); }
    public double getDouble(String path, double def) { return config.getDouble(path, def); }
    public boolean getBool(String path, boolean def) { return config.getBoolean(path, def); }
    public String getString(String path, String def) { return config.getString(path, def); }
}
