package org.bukkit.event.player;

import org.bukkit.entity.Player;

public class PlayerQuitEvent {
    private final Player player;

    public PlayerQuitEvent(Player player) {
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }
}
