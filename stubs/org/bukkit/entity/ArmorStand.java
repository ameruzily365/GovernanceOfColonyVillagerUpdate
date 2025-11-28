package org.bukkit.entity;

public class ArmorStand extends Entity {
    private String customName;
    private boolean customNameVisible;
    private boolean gravity;
    private boolean marker;
    private boolean visible = true;

    public void setCustomName(String name) {
        this.customName = name;
    }

    public String getCustomName() {
        return customName;
    }

    public void setCustomNameVisible(boolean visible) {
        this.customNameVisible = visible;
    }

    public boolean isCustomNameVisible() {
        return customNameVisible;
    }

    public void setGravity(boolean gravity) {
        this.gravity = gravity;
    }

    public boolean hasGravity() {
        return gravity;
    }

    public void setMarker(boolean marker) {
        this.marker = marker;
    }

    public boolean isMarker() {
        return marker;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean isVisible() {
        return visible;
    }
}
