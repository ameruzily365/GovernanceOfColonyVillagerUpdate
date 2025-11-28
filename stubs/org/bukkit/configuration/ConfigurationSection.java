package org.bukkit.configuration;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConfigurationSection {
    public Set<String> getKeys(boolean deep) {
        return Collections.emptySet();
    }

    public ConfigurationSection getConfigurationSection(String path) {
        return new ConfigurationSection();
    }

    public ConfigurationSection createSection(String path) {
        return new ConfigurationSection();
    }

    public int getInt(String path, int def) { return def; }

    public int getInt(String path) { return 0; }

    public boolean isInt(String path) { return false; }

    public long getLong(String path, long def) { return def; }

    public double getDouble(String path, double def) { return def; }

    public double getDouble(String path) { return 0.0; }

    public boolean isDouble(String path) { return false; }

    public boolean getBoolean(String path, boolean def) { return def; }

    public String getString(String path) { return null; }

    public String getString(String path, String def) { return def; }

    public List<String> getStringList(String path) { return Collections.emptyList(); }

    public List<Integer> getIntegerList(String path) { return Collections.emptyList(); }

    public List<Map<?, ?>> getMapList(String path) { return Collections.emptyList(); }

    public void set(String path, Object value) { }
}
