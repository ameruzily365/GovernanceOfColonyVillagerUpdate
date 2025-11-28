package org.bukkit.event.block;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public class BlockBreakEvent {
    private final Player player;
    private final Block block;
    private boolean cancelled;

    public BlockBreakEvent(Player player, Block block) {
        this.player = player;
        this.block = block;
    }

    public Player getPlayer() {
        return player;
    }

    public Block getBlock() {
        return block;
    }

    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
