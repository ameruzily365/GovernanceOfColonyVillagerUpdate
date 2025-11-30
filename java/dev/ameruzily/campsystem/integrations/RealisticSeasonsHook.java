package dev.ameruzily.campsystem.integrations;

import dev.ameruzily.campsystem.CampSystem;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Optional hook for RealisticSeasons. Uses reflection to avoid a hard dependency.
 */
public class RealisticSeasonsHook {
    private final CampSystem plugin;
    private Object apiInstance;
    private Method applyTemperatureMethod;
    private Method removeEffectMethod;
    private boolean initialized;
    private boolean permanentEffect;
    private final Map<UUID, Object> activeEffects = new HashMap<>();

    public RealisticSeasonsHook(CampSystem plugin) {
        this.plugin = plugin;
        initialize();
    }

    private void initialize() {
        initialized = true;
        if (plugin.getServer().getPluginManager().getPlugin("RealisticSeasons") == null) {
            return;
        }
        String[] possibleApis = new String[] {
                "com.casperseasons.api.SeasonsAPI",
                "me.casperseasons.api.SeasonsAPI",
                "me.casper.realisticseasons.api.SeasonsAPI",
                "me.casper.realisticseasons.api.RealisticSeasonsAPI"
        };
        try {
            Class<?> apiClass = null;
            for (String name : possibleApis) {
                try {
                    apiClass = Class.forName(name);
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }
            if (apiClass == null) {
                throw new ClassNotFoundException("SeasonsAPI class not found");
            }
            Method getter = apiClass.getMethod("getInstance");
            apiInstance = getter.invoke(null);
            try {
                applyTemperatureMethod = apiClass.getMethod("applyPermanentTemperatureEffect", Player.class, int.class);
                Class<?> effectClass = applyTemperatureMethod.getReturnType();
                permanentEffect = true;
                try {
                    removeEffectMethod = effectClass.getMethod("remove");
                } catch (NoSuchMethodException ignored) {
                }
            } catch (NoSuchMethodException ex) {
                applyTemperatureMethod = apiClass.getMethod("setPlayerTemperature", Player.class, double.class);
                permanentEffect = false;
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to initialize RealisticSeasons hook: " + ex.getMessage());
            apiInstance = null;
            applyTemperatureMethod = null;
        }
    }

    public boolean isAvailable() {
        if (!initialized) {
            initialize();
        }
        return apiInstance != null && applyTemperatureMethod != null;
    }

    public boolean applyStableTemperature(Player player, double temperature) {
        if (player == null || !isAvailable()) {
            return false;
        }
        try {
            if (permanentEffect) {
                clearTemperatureEffect(player);
                int modifier = (int) Math.round(temperature);
                Object effect = applyTemperatureMethod.invoke(apiInstance, player, modifier);
                if (effect != null && removeEffectMethod != null) {
                    activeEffects.put(player.getUniqueId(), effect);
                }
            } else {
                applyTemperatureMethod.invoke(apiInstance, player, temperature);
            }
            return true;
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to apply RealisticSeasons temperature: " + ex.getMessage());
            return false;
        }
    }

    public void clearTemperatureEffect(Player player) {
        if (player == null || !permanentEffect) {
            return;
        }
        Object existing = activeEffects.remove(player.getUniqueId());
        if (existing == null || removeEffectMethod == null) {
            return;
        }
        try {
            removeEffectMethod.invoke(existing);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            plugin.getLogger().warning("Failed to remove RealisticSeasons temperature effect: " + ex.getMessage());
        }
    }
}

