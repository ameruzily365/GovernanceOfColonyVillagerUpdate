package dev.ameruzily.campsystem.integrations;

import dev.ameruzily.campsystem.CampSystem;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Optional hook for RealisticSeasons. Uses reflection to avoid a hard dependency.
 */
public class RealisticSeasonsHook {
    private final CampSystem plugin;
    private Object apiInstance;
    private Method setTemperatureMethod;
    private boolean initialized;

    public RealisticSeasonsHook(CampSystem plugin) {
        this.plugin = plugin;
        initialize();
    }

    private void initialize() {
        initialized = true;
        if (plugin.getServer().getPluginManager().getPlugin("RealisticSeasons") == null) {
            return;
        }
        try {
            Class<?> apiClass = Class.forName("me.casper.realisticseasons.api.RealisticSeasonsAPI");
            Method getter = apiClass.getMethod("getInstance");
            apiInstance = getter.invoke(null);
            setTemperatureMethod = apiClass.getMethod("setPlayerTemperature", Player.class, double.class);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to initialize RealisticSeasons hook: " + ex.getMessage());
            apiInstance = null;
            setTemperatureMethod = null;
        }
    }

    public boolean isAvailable() {
        if (!initialized) {
            initialize();
        }
        return apiInstance != null && setTemperatureMethod != null;
    }

    public boolean applyStableTemperature(Player player, double temperature) {
        if (player == null || !isAvailable()) {
            return false;
        }
        try {
            setTemperatureMethod.invoke(apiInstance, player, temperature);
            return true;
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to apply RealisticSeasons temperature: " + ex.getMessage());
            return false;
        }
    }
}

