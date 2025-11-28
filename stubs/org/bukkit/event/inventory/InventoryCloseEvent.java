package org.bukkit.event.inventory;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.Inventory;

public class InventoryCloseEvent extends PlayerEvent {
    private final Inventory inventory;

    public InventoryCloseEvent(Player player, Inventory inventory) {
        super(player);
        this.inventory = inventory;
    }

    public Inventory getInventory() {
        return inventory;
    }
}
