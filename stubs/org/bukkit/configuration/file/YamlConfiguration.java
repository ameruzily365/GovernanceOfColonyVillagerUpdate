package org.bukkit.configuration.file;

import java.io.File;
import java.io.IOException;

public class YamlConfiguration extends FileConfiguration {
    public static YamlConfiguration loadConfiguration(File file) {
        return new YamlConfiguration();
    }

    public void save(File file) throws IOException {
        // no-op
    }
}
