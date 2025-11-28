package org.bukkit.event.player;

import org.bukkit.entity.Player;

public class AsyncPlayerChatEvent {
    private final Player player;
    private String message;
    private boolean cancelled;

    public AsyncPlayerChatEvent(Player player, String message) {
        this.player = player;
        this.message = message;
    }

    public Player getPlayer() {
        return player;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
