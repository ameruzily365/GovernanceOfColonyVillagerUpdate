package org.bukkit.event.player;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public class PlayerInteractEvent {
    private final Player player;
    private final Block clickedBlock;
    private boolean cancelled;

    public PlayerInteractEvent(Player player, Block clickedBlock) {
        this.player = player;
        this.clickedBlock = clickedBlock;
    }

    public Player getPlayer() {
        return player;
    }

    public Block getClickedBlock() {
        return clickedBlock;
    }

    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
