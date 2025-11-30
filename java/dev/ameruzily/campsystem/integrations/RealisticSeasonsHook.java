package dev.ameruzily.campsystem.integrations;

import dev.ameruzily.campsystem.CampSystem;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
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
                "com.casperseasons.seasons.api.SeasonsAPI",
                "com.casperseasons.realisticseasons.api.SeasonsAPI",
                "com.casperseasons.realisticseasons.api.RealisticSeasonsAPI",
                "me.casperseasons.api.SeasonsAPI",
                "me.casperseasons.seasonsapi.SeasonsAPI",
                "me.casperseasons.seasonsapi.RealisticSeasonsAPI",
                "me.casper.realisticseasons.api.SeasonsAPI",
                "me.casper.realisticseasons.api.RealisticSeasonsAPI",
                "SeasonsAPI"
        };
        try {
            Class<?> apiClass = null;
            ClassLoader loader = plugin.getServer().getPluginManager()
                    .getPlugin("RealisticSeasons")
                    .getClass()
                    .getClassLoader();
            for (String name : possibleApis) {
                try {
                    apiClass = Class.forName(name, true, loader);
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }
            if (apiClass == null) {
                throw new ClassNotFoundException("SeasonsAPI class not found");
            }
            apiInstance = resolveInstance(apiClass);
            applyTemperatureMethod = resolveApplyMethod(apiClass);
            if (applyTemperatureMethod == null) {
                throw new NoSuchMethodException("No suitable temperature method");
            }
            Class<?> effectClass = applyTemperatureMethod.getReturnType();
            permanentEffect = !void.class.equals(effectClass) && !Void.class.equals(effectClass)
                    || applyTemperatureMethod.getName().toLowerCase().contains("permanent");
            if (permanentEffect && !void.class.equals(effectClass) && !Void.class.equals(effectClass)) {
                removeEffectMethod = resolveRemoveMethod(effectClass);
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
                Object[] args = buildArgs(player, temperature);
                Object effect = applyTemperatureMethod.invoke(apiInstance, args);
                if (effect != null && removeEffectMethod != null) {
                    activeEffects.put(player.getUniqueId(), effect);
                }
            } else {
                applyTemperatureMethod.invoke(apiInstance, buildArgs(player, temperature));
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

    private Object resolveInstance(Class<?> apiClass) throws InvocationTargetException, IllegalAccessException {
        for (String name : Arrays.asList("getInstance", "getAPI", "api", "instance")) {
            try {
                Method getter = apiClass.getMethod(name);
                if ((getter.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) {
                    return getter.invoke(null);
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private Method resolveApplyMethod(Class<?> apiClass) {
        for (Method method : apiClass.getMethods()) {
            String name = method.getName().toLowerCase();
            if (!name.contains("temperature")) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 2) {
                continue;
            }
            if (!Player.class.isAssignableFrom(params[0])) {
                continue;
            }
            if (!Number.class.isAssignableFrom(params[1]) && !params[1].isPrimitive()) {
                continue;
            }
            if (name.contains("permanent") || name.contains("apply") || name.contains("set")) {
                return method;
            }
        }
        return null;
    }

    private Method resolveRemoveMethod(Class<?> effectClass) {
        for (String name : Arrays.asList("remove", "cancel", "end")) {
            try {
                return effectClass.getMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private Object[] buildArgs(Player player, double temperature) {
        Class<?>[] params = applyTemperatureMethod.getParameterTypes();
        Object value;
        if (params[1] == int.class || params[1] == Integer.class) {
            value = (int) Math.round(temperature);
        } else if (params[1] == float.class || params[1] == Float.class) {
            value = (float) temperature;
        } else {
            value = temperature;
        }
        return new Object[] { player, value };
    }
}

