package org.bukkit.event.player;

import org.bukkit.entity.Player;

public class PlayerEvent {
    private final Player player;

    public PlayerEvent(Player player) {
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }
}
