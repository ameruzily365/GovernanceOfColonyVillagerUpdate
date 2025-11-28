package dev.lone.itemsadder.api.Events;

import dev.lone.itemsadder.api.CustomStack;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;

public class FurniturePlaceEvent extends PlayerEvent implements Cancellable {
    private final Entity furnitureEntity;
    private final ItemStack itemInHand;
    private final CustomStack customStack;
    private boolean cancelled;

    public FurniturePlaceEvent(Player who, Entity entity, ItemStack item, CustomStack stack) {
        super(who);
        this.furnitureEntity = entity;
        this.itemInHand = item;
        this.customStack = stack;
    }

    public Entity getBukkitEntity() {
        return furnitureEntity;
    }

    public ItemStack getItemInHand() {
        return itemInHand;
    }

    public String getNamespacedID() {
        return customStack == null ? null : customStack.getNamespacedID();
    }

    public Location getLocation() {
        return furnitureEntity == null ? null : furnitureEntity.getLocation();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
