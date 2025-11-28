package org.bukkit.event.player;

import org.bukkit.Location;
import org.bukkit.entity.Player;

public class PlayerMoveEvent {
    private final Player player;
    private final Location from;
    private final Location to;
    private boolean cancelled;

    public PlayerMoveEvent(Player player, Location from, Location to) {
        this.player = player;
        this.from = from;
        this.to = to;
    }

    public Player getPlayer() {
        return player;
    }

    public Location getFrom() {
        return from;
    }

    public Location getTo() {
        return to;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
