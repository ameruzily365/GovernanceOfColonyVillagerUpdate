package org.bukkit.event.player;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

public class PlayerDropItemEvent {
    private final Player player;
    private final Item item;
    private boolean cancelled;

    public PlayerDropItemEvent(Player player, Item item) {
        this.player = player;
        this.item = item;
    }

    public Player getPlayer() {
        return player;
    }

    public Item getItemDrop() {
        return item;
    }

    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
