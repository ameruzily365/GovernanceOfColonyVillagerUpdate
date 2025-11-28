package dev.ameruzily.campsystem.models;

import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public class SoundSettings {

    private final boolean enabled;
    private final Sound sound;
    private final float volume;
    private final float pitch;

    private SoundSettings(boolean enabled, Sound sound, float volume, float pitch) {
        this.enabled = enabled;
        this.sound = sound;
        this.volume = volume;
        this.pitch = pitch;
    }

    public static SoundSettings fromConfig(FileConfiguration config, String path) {
        if (config == null || path == null) {
            return new SoundSettings(false, null, 1.0f, 1.0f);
        }
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return new SoundSettings(false, null, 1.0f, 1.0f);
        }
        boolean enabled = section.getBoolean("enabled", false);
        String name = section.getString("name", "");
        Sound sound = null;
        if (name != null && !name.isBlank()) {
            try {
                sound = Sound.valueOf(name.toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }
        float volume = (float) section.getDouble("volume", 1.0);
        float pitch = (float) section.getDouble("pitch", 1.0);
        return new SoundSettings(enabled, sound, volume, pitch);
    }

    public void play(Player player) {
        if (!enabled || player == null || sound == null) {
            return;
        }
        player.playSound(player.getLocation(), sound, volume, pitch);
    }
}
