package org.bukkit.inventory;

public class Inventory {
    private final ItemStack[] contents;
    private final String title;

    public Inventory(int size, String title) {
        this.contents = new ItemStack[Math.max(1, size)];
        this.title = title == null ? "" : title;
    }

    public int getSize() {
        return contents.length;
    }

    public ItemStack getItem(int slot) {
        if (slot < 0 || slot >= contents.length) {
            return null;
        }
        return contents[slot];
    }

    public void setItem(int slot, ItemStack item) {
        if (slot < 0 || slot >= contents.length) {
            return;
        }
        contents[slot] = item;
    }

    public String getTitle() {
        return title;
    }
}
