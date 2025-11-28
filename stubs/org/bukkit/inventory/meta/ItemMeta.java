package org.bukkit.inventory.meta;

public class ItemMeta implements Cloneable {
    private String displayName;
    private java.util.List<String> lore;

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setLore(java.util.List<String> lore) {
        this.lore = lore;
    }

    public java.util.List<String> getLore() {
        return lore;
    }

    @Override
    public ItemMeta clone() {
        try {
            ItemMeta copy = (ItemMeta) super.clone();
            copy.displayName = this.displayName;
            copy.lore = this.lore;
            return copy;
        } catch (CloneNotSupportedException ex) {
            ItemMeta copy = new ItemMeta();
            copy.displayName = this.displayName;
            copy.lore = this.lore;
            return copy;
        }
    }
}
