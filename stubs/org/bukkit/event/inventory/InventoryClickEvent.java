package org.bukkit.event.inventory;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class InventoryClickEvent extends PlayerEvent {
    private final Inventory inventory;
    private final int slot;
    private final ItemStack currentItem;
    private boolean cancelled;

    public InventoryClickEvent(Player player, Inventory inventory, int slot, ItemStack currentItem) {
        super(player);
        this.inventory = inventory;
        this.slot = slot;
        this.currentItem = currentItem;
    }

    public Inventory getInventory() {
        return inventory;
    }

    public Player getWhoClicked() {
        return getPlayer();
    }

    public int getSlot() {
        return slot;
    }

    public ItemStack getCurrentItem() {
        return currentItem;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
