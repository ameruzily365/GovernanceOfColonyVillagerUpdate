package org.bukkit.entity;

import org.bukkit.inventory.ItemStack;

public class Item extends Entity {
    private final ItemStack itemStack;

    public Item(ItemStack itemStack) {
        this.itemStack = itemStack;
    }

    public ItemStack getItemStack() {
        return itemStack;
    }
}
