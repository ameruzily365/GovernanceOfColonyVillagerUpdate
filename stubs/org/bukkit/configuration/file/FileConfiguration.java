package org.bukkit.configuration.file;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.List;

public class FileConfiguration extends ConfigurationSection {
    public int getInt(String path, int def) {
        return def;
    }

    public long getLong(String path, long def) {
        return def;
    }

    public double getDouble(String path, double def) {
        return def;
    }

    public boolean getBoolean(String path, boolean def) {
        return def;
    }

    public String getString(String path, String def) {
        return def;
    }

    public String getString(String path) {
        return null;
    }

    public List<String> getStringList(String path) {
        return Collections.emptyList();
    }

    public org.bukkit.configuration.ConfigurationSection getConfigurationSection(String path) {
        return new org.bukkit.configuration.ConfigurationSection();
    }

    public boolean isConfigurationSection(String path) {
        return false;
    }
}
