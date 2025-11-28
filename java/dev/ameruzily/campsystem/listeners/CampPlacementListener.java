package dev.ameruzily.campsystem.listeners;

import dev.ameruzily.campsystem.CampSystem;
import dev.ameruzily.campsystem.managers.StateManager;
import dev.lone.itemsadder.api.Events.FurniturePlaceEvent;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.Map;

public class CampPlacementListener implements Listener {

    private final CampSystem plugin;

    public CampPlacementListener(CampSystem plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCampPlace(BlockPlaceEvent event) {
        handleCampPlacement(event.getPlayer(), event.getItemInHand(), event.getBlockPlaced().getLocation(), null, () -> event.setCancelled(true));
    }

    @EventHandler(ignoreCancelled = true)
    public void onFurniturePlace(FurniturePlaceEvent event) {
        Location location = resolveFurnitureLocation(event);
        ItemStack stack = resolveFurnitureItem(event);
        handleCampPlacement(event.getPlayer(), stack, location, resolveNamespacedId(event), () -> event.setCancelled(true));
    }

    @EventHandler(ignoreCancelled = true)
    public void onRelocationDrop(PlayerDropItemEvent event) {
        var drop = event.getItemDrop();
        if (drop == null) {
            return;
        }
        var stack = drop.getItemStack();
        if (plugin.state().isRelocationItem(stack)) {
            event.setCancelled(true);
            plugin.lang().send(event.getPlayer(), "state.relocation-drop-blocked");
        }
    }

    private void handleCampPlacement(org.bukkit.entity.Player player, ItemStack item, Location location, String customId, Runnable cancelAction) {
        if (player == null) {
            return;
        }

        if (item == null) {
            item = player.getItemInHand();
        }

        StateManager state = plugin.state();
        String namespacedId = customId != null ? customId : state.resolveNamespacedId(item);

        StateManager.PendingCampData pending = state.peekPendingPlacement(player);

        boolean isCampItem = state.isCampItem(item, namespacedId);
        boolean isRelocationItem = state.isRelocationItem(item);

        if (pending == null) {
            if (isCampItem || isRelocationItem) {
                if (state.hasState(player)) {
                    plugin.lang().send(player, "state.placement-sector-required");
                } else {
                    plugin.lang().send(player, "state.placement-not-ready");
                }
                cancelAction.run();
            }
            return;
        }

        if (!state.isValidPlacementItem(pending, item, namespacedId)) {
            cancelAction.run();
            plugin.lang().send(player, pending.isRelocation()
                    ? "state.placement-requires-token"
                    : "state.placement-requires-camp");
            return;
        }

        if (location == null) {
            cancelAction.run();
            plugin.lang().send(player, "state.placement-not-ready");
            return;
        }

        StateManager.PlacementValidationResult validation = state.validatePendingPlacement(player, location);
        if (!validation.isAllowed()) {
            cancelAction.run();
            String messageKey = validation.getMessageKey();
            if (messageKey != null) {
                plugin.lang().send(player, messageKey, validation.getPlaceholders());
            }
            return;
        }

        StateManager.PendingCampData data = state.completePendingPlacement(player, location);
        if (data != null) {
            plugin.lang().send(player, "state.camp-placed", Map.of(
                    "state", data.getState(),
                    "sector", data.getSector()
            ));
            if (plugin.holograms() != null) {
                Location holoLocation = location == null ? null : location.clone();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.holograms().spawnOrUpdate(data.getState(), data.getSector(), holoLocation);
                    plugin.getServer().getScheduler().runTaskLater(plugin,
                            () -> plugin.holograms().spawnOrUpdate(data.getState(), data.getSector(), holoLocation), 1L);
                });
            }
        }
    }

    private Location resolveFurnitureLocation(FurniturePlaceEvent event) {
        Location direct = invokeLocation(event, "getLocation");
        if (direct != null) {
            return direct;
        }
        Location entityLocation = invokeEntityLocation(event);
        if (entityLocation != null) {
            return entityLocation;
        }
        return event.getPlayer() == null ? null : event.getPlayer().getLocation();
    }

    private Location invokeLocation(FurniturePlaceEvent event, String methodName) {
        try {
            Method method = event.getClass().getMethod(methodName);
            Object value = method.invoke(event);
            if (value instanceof Location loc) {
                return loc;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private Location invokeEntityLocation(FurniturePlaceEvent event) {
        try {
            Method method = event.getClass().getMethod("getBukkitEntity");
            Object entity = method.invoke(event);
            if (entity instanceof org.bukkit.entity.Entity bukkitEntity) {
                return bukkitEntity.getLocation();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private ItemStack resolveFurnitureItem(FurniturePlaceEvent event) {
        ItemStack stack = invokeItem(event, "getItemInHand");
        if (stack != null) {
            return stack;
        }
        stack = invokeItem(event, "getItemStack");
        if (stack != null) {
            return stack;
        }
        return null;
    }

    private ItemStack invokeItem(FurniturePlaceEvent event, String methodName) {
        try {
            Method method = event.getClass().getMethod(methodName);
            Object value = method.invoke(event);
            if (value instanceof ItemStack itemStack) {
                return itemStack;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private String resolveNamespacedId(FurniturePlaceEvent event) {
        try {
            Method method = event.getClass().getMethod("getNamespacedID");
            Object value = method.invoke(event);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
