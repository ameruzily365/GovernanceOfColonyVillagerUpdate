package dev.ameruzily.campsystem.managers;

import dev.ameruzily.campsystem.CampSystem;
import dev.ameruzily.campsystem.models.Ideology;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class IdeologyManager {
    private final CampSystem plugin;
    private final Map<String, Ideology> ideologies = new HashMap<>();

    public IdeologyManager(CampSystem plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        ideologies.clear();
        File file = new File(plugin.getDataFolder(), "ideologies.yml");
        if (!file.exists()) plugin.saveResource("ideologies.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        if (!cfg.isConfigurationSection("ideologies")) {
            plugin.getLogger().warning("ideologies.yml is empty!");
            return;
        }

        for (String id : cfg.getConfigurationSection("ideologies").getKeys(false)) {
            String disp = cfg.getString("ideologies." + id + ".display_name", id);
            String permission = cfg.getString("ideologies." + id + ".permission", "");
            ideologies.put(id.toLowerCase(), new Ideology(id, disp, permission));
        }
        plugin.getLogger().info("Loaded " + ideologies.size() + " ideologies.");
    }

    public Ideology get(String id) { return ideologies.get(id.toLowerCase()); }
    public Collection<Ideology> all() { return ideologies.values(); }
}
