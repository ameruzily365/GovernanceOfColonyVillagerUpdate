package org.bukkit.event.block;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

public class BlockPlaceEvent {
    private final Player player;
    private final Block block;
    private final ItemStack itemInHand;
    private boolean cancelled;

    public BlockPlaceEvent(Player player, Block block, ItemStack itemInHand) {
        this.player = player;
        this.block = block;
        this.itemInHand = itemInHand;
    }

    public Player getPlayer() {
        return player;
    }

    public Block getBlockPlaced() {
        return block;
    }

    public ItemStack getItemInHand() {
        return itemInHand;
    }

    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
