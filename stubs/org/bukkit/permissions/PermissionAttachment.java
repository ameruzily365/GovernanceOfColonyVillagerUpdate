package org.bukkit.permissions;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class PermissionAttachment {
    private final Plugin plugin;
    private final Player permissible;

    public PermissionAttachment(Plugin plugin, Player permissible) {
        this.plugin = plugin;
        this.permissible = permissible;
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public Player getPermissible() {
        return permissible;
    }

    public void setPermission(String permission, boolean value) {
        // no-op stub
    }

    public void remove() {
        // no-op stub
    }
}
