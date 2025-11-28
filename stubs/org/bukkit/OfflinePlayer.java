package org.bukkit;

import java.util.UUID;

public class OfflinePlayer {
    private final UUID uuid;
    private final String name;

    public OfflinePlayer(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID getUniqueId() {
        return uuid;
    }

    public String getName() {
        return name;
    }
}
