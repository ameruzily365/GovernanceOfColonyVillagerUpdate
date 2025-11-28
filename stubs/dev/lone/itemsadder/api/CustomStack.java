package dev.lone.itemsadder.api;

import org.bukkit.inventory.ItemStack;

public class CustomStack {
    private final String namespacedID;
    private final ItemStack itemStack;

    public CustomStack(String namespacedID, ItemStack itemStack) {
        this.namespacedID = namespacedID;
        this.itemStack = itemStack;
    }

    public static CustomStack getInstance(String namespacedID) {
        return null;
    }

    public static CustomStack byItemStack(ItemStack stack) {
        return null;
    }

    public ItemStack getItemStack() {
        return itemStack;
    }

    public String getNamespacedID() {
        return namespacedID;
    }
}
