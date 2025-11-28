package dev.ameruzily.campsystem.managers;

import dev.ameruzily.campsystem.CampSystem;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class LangManager {
    private final CampSystem plugin;
    private YamlConfiguration lang;
    private YamlConfiguration defaults;

    public LangManager(CampSystem plugin) {
        this.plugin = plugin;
        load();
    }

    private String translateColorCodes(String input) {
        return input == null ? "" : input.replace("&", "§");
    }

    private void load() {
        File file = new File(plugin.getDataFolder(), "lang.yml");
        if (!file.exists()) plugin.saveResource("lang.yml", false);
        lang = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("lang.yml")) {
            if (in != null) {
                defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load default lang.yml from jar: " + e.getMessage());
        }
    }

    public void reload() { load(); }

    public String raw(String path) {
        String message = lang.getString(path);
        if (message == null && defaults != null) {
            message = defaults.getString(path);
        }
        if (message == null) return translateColorCodes("&cMissing lang: " + path);
        return translateColorCodes(message);
    }

    public List<String> list(String path) {
        if (lang.contains(path)) {
            return lang.getStringList(path);
        }
        if (defaults != null && defaults.contains(path)) {
            return defaults.getStringList(path);
        }
        return Collections.emptyList();
    }

    public List<String> listColored(String path) {
        List<String> raw = list(path);
        List<String> colored = new java.util.ArrayList<>(raw.size());
        for (String line : raw) {
            colored.add(translateColorCodes(line));
        }
        return colored;
    }

    public void send(Player p, String path) {
        send(p, path, java.util.Collections.emptyMap());
    }

    public void send(Player p, String path, Map<String, String> vars) {
        String msg = applyPlaceholders(p, raw(path), vars);
        String prefixed = withPrefix(msg);
        if (p == null) {
            plugin.getLogger().info("[Lang] " + prefixed.replace("§", ""));
        } else {
            p.sendMessage(prefixed);
        }
    }

    public void sendActionBar(Player p, String path, Map<String, String> vars) {
        if (p == null) {
            return;
        }
        String msg = applyPlaceholders(p, raw(path), vars);
        p.sendActionBar(msg);
    }

    public void sendActionBar(Player p, String path) {
        sendActionBar(p, path, java.util.Collections.emptyMap());
    }

    public String messageOrDefault(String path, String def) {
        String value = lang.getString(path);
        if (value == null) {
            return def;
        }
        return translateColorCodes(value);
    }

    public void broadcast(String path) {
        broadcast(path, java.util.Collections.emptyMap());
    }

    public void broadcast(String path, Map<String, String> vars) {
        String msg = applyPlaceholders(null, raw(path), vars);
        Bukkit.getServer().broadcastMessage(withPrefix(msg));
    }

    private String applyPlaceholders(Player player, String message, Map<String, String> vars) {
        PlaceholderManager placeholders = plugin.placeholders();
        if (placeholders != null) {
            return placeholders.apply(player, message, vars);
        }
        String result = message;
        if (vars != null) {
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                result = result.replace("%" + entry.getKey() + "%", entry.getValue());
            }
        }
        return result;
    }

    public String colorizeText(String input) {
        return translateColorCodes(input);
    }

    private String withPrefix(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String prefix = translateColorCodes(lang.getString("general.prefix", ""));
        return prefix + message;
    }
}
