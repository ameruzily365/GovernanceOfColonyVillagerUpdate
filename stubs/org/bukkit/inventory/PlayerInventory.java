package org.bukkit.inventory;

public class PlayerInventory {
    private ItemStack[] contents = new ItemStack[36];

    public ItemStack[] getContents() {
        return contents;
    }

    public void setContents(ItemStack[] contents) {
        this.contents = contents;
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

    public java.util.Map<Integer, ItemStack> addItem(ItemStack item) {
        java.util.Map<Integer, ItemStack> leftover = new java.util.HashMap<>();
        if (item == null) {
            return leftover;
        }
        int empty = firstEmpty();
        if (empty == -1) {
            leftover.put(0, item);
            return leftover;
        }
        contents[empty] = item;
        return leftover;
    }

    public int firstEmpty() {
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] == null) {
                return i;
            }
        }
        return -1;
    }

    public ItemStack getItemInHand() {
        return contents.length > 0 ? contents[0] : null;
    }

    public void setItemInHand(ItemStack item) {
        if (contents.length > 0) {
            contents[0] = item;
        }
    }
}
