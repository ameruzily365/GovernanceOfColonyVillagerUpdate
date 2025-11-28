package dev.ameruzily.campsystem.managers;

import dev.ameruzily.campsystem.CampSystem;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;

public class PlaceholderManager {
    private final CampSystem plugin;
    private boolean hooked;
    private CampPlaceholderExpansion expansion;

    public PlaceholderManager(CampSystem plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        if (expansion != null) {
            expansion.unregister();
            expansion = null;
        }
        hooked = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (!hooked) {
            return;
        }
        expansion = new CampPlaceholderExpansion(plugin);
        hooked = expansion.register();
    }

    public boolean isHooked() {
        return hooked;
    }

    public String apply(Player player, String message, Map<String, String> vars) {
        if (message == null) {
            return "";
        }
        String result = message;
        if (vars != null) {
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                String key = Objects.toString(entry.getKey(), "");
                String value = Objects.toString(entry.getValue(), "");
                result = result.replace("%" + key + "%", value);
            }
        }
        if (hooked) {
            result = PlaceholderAPI.setPlaceholders(player, result);
        }
        return result;
    }

    public String apply(Player player, String message) {
        return apply(player, message, Map.of());
    }

    public String apply(String message, Map<String, String> vars) {
        return apply(null, message, vars);
    }

    public String apply(String message) {
        return apply(null, message, Map.of());
    }
}
