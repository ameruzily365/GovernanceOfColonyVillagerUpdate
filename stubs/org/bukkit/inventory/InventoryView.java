package org.bukkit.inventory;

public class InventoryView {
    private final Inventory top;
    private final Inventory bottom;

    public InventoryView(Inventory top, Inventory bottom) {
        this.top = top;
        this.bottom = bottom;
    }

    public Inventory getTopInventory() {
        return top;
    }

    public Inventory getBottomInventory() {
        return bottom;
    }
}
