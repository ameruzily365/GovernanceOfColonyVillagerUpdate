package org.bukkit.event.entity;

import org.bukkit.entity.Player;

public class PlayerDeathEvent {
    private final Player entity;
    private boolean keepInventory;
    private boolean keepLevel;

    public PlayerDeathEvent(Player entity) {
        this.entity = entity;
    }

    public Player getEntity() {
        return entity;
    }

    public void setKeepInventory(boolean keepInventory) {
        this.keepInventory = keepInventory;
    }

    public void setKeepLevel(boolean keepLevel) {
        this.keepLevel = keepLevel;
    }
}
