package org.bukkit.inventory;

import org.bukkit.Material;
import org.bukkit.inventory.meta.ItemMeta;

public class ItemStack {
    private final Material type;
    private int amount;
    private ItemMeta meta;

    public ItemStack(Material type, int amount) {
        this.type = type;
        this.amount = amount;
    }

    public Material getType() {
        return type;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public ItemMeta getItemMeta() {
        if (meta == null) {
            meta = new ItemMeta();
        }
        return meta.clone();
    }

    public void setItemMeta(ItemMeta meta) {
        if (meta == null) {
            this.meta = null;
        } else {
            this.meta = meta.clone();
        }
    }

    public String getDisplayName() {
        return meta == null ? null : meta.getDisplayName();
    }

    public ItemStack clone() {
        ItemStack copy = new ItemStack(this.type, this.amount);
        if (this.meta != null) {
            copy.meta = this.meta.clone();
        }
        return copy;
    }
}
