package dev.ameruzily.campsystem.managers;

import dev.ameruzily.campsystem.CampSystem;
import dev.ameruzily.campsystem.models.Camp;
import dev.ameruzily.campsystem.managers.StateManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.entity.Entity;

import java.text.DecimalFormat;
import java.util.*;

public class CampHologramManager {
    private final CampSystem plugin;
    private final Map<String, HologramEntry> holograms = new HashMap<>();
    private final DecimalFormat number = new DecimalFormat("0.0");

    private boolean enabled;
    private double offsetY;
    private double spacing;
    private List<String> templates = List.of();
    private BukkitTask refreshTask;

    public CampHologramManager(CampSystem plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        clearAll();
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        FileConfiguration config = plugin.getConfig();
        String hologramPath = "camp.hologram";
        if (!config.isConfigurationSection(hologramPath) && config.isConfigurationSection("capital.hologram")) {
            hologramPath = "capital.hologram";
        }
        enabled = config.getBoolean(hologramPath + ".enabled", true);
        offsetY = config.getDouble(hologramPath + ".offset-y", 1.8);
        spacing = config.getDouble(hologramPath + ".line-spacing", 0.3);
        templates = plugin.lang().listColored("hologram.lines");
        if (templates.isEmpty()) {
            templates = List.of(
                    plugin.lang().colorizeText("&a%state%"),
                    plugin.lang().colorizeText("&7%sector%"),
                    plugin.lang().colorizeText("&c%hp%&7/&c%max% &7(%percent%%%)"),
                    plugin.lang().colorizeText("&7状态: %status%"),
                    plugin.lang().colorizeText("&7燃料: &f%fuel_amount% &8- %maintenance_status% &8(%maintenance_time%)")
            );
        }
        if (!enabled) {
            return;
        }
        for (StateManager.StateData state : plugin.state().getStates()) {
            for (StateManager.SectorData sector : state.sectors.values()) {
                Location loc = sector.getLocation();
                if (loc != null) {
                    spawnOrUpdate(state.name, sector.getName(), loc);
                }
            }
        }

        long refreshTicks = Math.max(20L, config.getLong(hologramPath + ".refresh-ticks", 20L));
        refreshTask = new BukkitRunnable() {
            @Override
            public void run() {
                refreshAll();
            }
        }.runTaskTimer(plugin, refreshTicks, refreshTicks);
    }

    public void spawnOrUpdate(String state, String sector, Location blockLocation) {
        if (!enabled || blockLocation == null) {
            return;
        }
        purgeNearbyHolograms(blockLocation);
        Camp camp = plugin.war().getCamp(state, sector);
        if (camp == null) {
            camp = plugin.war().registerCamp(state, sector);
            if (camp == null) {
                return;
            }
        }
        String key = key(state, sector);
        HologramEntry entry = holograms.computeIfAbsent(key, k -> new HologramEntry(state, sector));
        entry.blockLocation = blockLocation.clone();
        ensureStands(entry);
        updateEntry(entry, camp);
    }

    public void update(Camp camp) {
        if (!enabled || camp == null) {
            return;
        }
        String key = key(camp.getStateName(), camp.getSectorName());
        HologramEntry entry = holograms.get(key);
        if (entry == null) {
            Location location = plugin.state().getSectorLocation(camp.getStateName(), camp.getSectorName());
            if (location != null) {
                spawnOrUpdate(camp.getStateName(), camp.getSectorName(), location);
            }
            return;
        }
        ensureStands(entry);
        updateEntry(entry, camp);
    }

    public void removeCamp(String state, String sector) {
        HologramEntry entry = holograms.remove(key(state, sector));
        if (entry != null) {
            entry.remove();
        }
    }

    public void removeState(String state) {
        Iterator<Map.Entry<String, HologramEntry>> iterator = holograms.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, HologramEntry> next = iterator.next();
            if (next.getValue().state.equalsIgnoreCase(state)) {
                next.getValue().remove();
                iterator.remove();
            }
        }
    }

    public void renameState(String oldName, String newName) {
        if (!enabled) {
            return;
        }
        removeState(oldName);
        StateManager.StateData data = plugin.state().findState(newName);
        if (data == null) {
            return;
        }
        for (StateManager.SectorData sector : data.sectors.values()) {
            Location location = sector.getLocation();
            if (location != null) {
                spawnOrUpdate(newName, sector.getName(), location);
            }
        }
    }

    public void renameSector(String state, String oldSector, String newSector, Location location) {
        if (!enabled) {
            return;
        }
        removeCamp(state, oldSector);
        Location resolved = location != null ? location.clone() : plugin.state().getSectorLocation(state, newSector);
        spawnOrUpdate(state, newSector, resolved);
    }

    public void shutdown() {
        clearAll();
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    private void refreshAll() {
        if (!enabled) {
            return;
        }
        for (HologramEntry entry : new ArrayList<>(holograms.values())) {
            Camp camp = plugin.war().getCamp(entry.state, entry.sector);
            if (camp != null) {
                updateEntry(entry, camp);
            }
        }
    }

    private void updateEntry(HologramEntry entry, Camp camp) {
        List<String> formatted = formatLines(camp);
        entry.stands.removeIf(Objects::isNull);
        ensureStandCount(entry, formatted.size());
        Location base = entry.blockLocation.clone().add(0.5, offsetY, 0.5);
        for (int i = 0; i < entry.stands.size(); i++) {
            ArmorStand stand = entry.stands.get(i);
            if (stand == null) {
                continue;
            }
            Location lineLoc = base.clone().add(0.0, -spacing * i, 0.0);
            stand.teleport(lineLoc);
            String line = i < formatted.size() ? formatted.get(i) : "";
            stand.setCustomName(line);
            stand.setCustomNameVisible(line != null && !line.isEmpty());
        }
    }

    private void ensureStands(HologramEntry entry) {
        ensureStandCount(entry, templates.size());
    }

    private void purgeNearbyHolograms(Location blockLocation) {
        World world = blockLocation.getWorld();
        if (world == null) {
            return;
        }
        Collection<Entity> nearby;
        try {
            nearby = world.getNearbyEntities(blockLocation, 1.2, 2.0, 1.2);
        } catch (Throwable ignored) {
            return;
        }
        for (Entity entity : nearby) {
            if (entity instanceof ArmorStand stand) {
                try {
                    if (stand.isMarker() && !stand.isVisible()) {
                        stand.remove();
                    }
                } catch (Throwable ignored) {
                    stand.remove();
                }
            }
        }
    }

    private void ensureStandCount(HologramEntry entry, int desired) {
        if (entry.blockLocation == null) {
            return;
        }
        Location base = entry.blockLocation.clone().add(0.5, offsetY, 0.5);
        while (entry.stands.size() < desired) {
            Location spawnLocation = base.clone().add(0.0, -spacing * entry.stands.size(), 0.0);
            ArmorStand stand = spawnArmorStand(spawnLocation);
            if (stand != null) {
                entry.stands.add(stand);
            } else {
                break;
            }
        }
        while (entry.stands.size() > desired) {
            ArmorStand stand = entry.stands.remove(entry.stands.size() - 1);
            if (stand != null) {
                stand.remove();
            }
        }
    }

    private ArmorStand spawnArmorStand(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        ArmorStand stand = world.spawn(location, ArmorStand.class);
        if (stand == null) {
            return null;
        }
        stand.setMarker(true);
        stand.setGravity(false);
        stand.setVisible(false);
        stand.setCustomNameVisible(true);
        return stand;
    }

    private List<String> formatLines(Camp camp) {
        Map<String, String> vars = new HashMap<>();
        vars.put("state", camp.getStateName());
        vars.put("sector", camp.getSectorName());
        vars.put("hp", number.format(camp.getHp()));
        vars.put("max", number.format(camp.getMaxHp()));
        double percent = camp.getMaxHp() <= 0 ? 0.0 : (camp.getHp() / camp.getMaxHp()) * 100.0;
        vars.put("percent", number.format(percent));
        vars.put("status", plugin.lang().messageOrDefault(
                camp.isBroken() ? "hologram.status-broken" : "hologram.status-operational",
                camp.isBroken() ? "破损" : "正常"
        ));
        vars.put("ideology_display", plugin.state().getIdeologyDisplay(camp.getStateName()));
        boolean capital = plugin.state().isCapitalSector(camp.getStateName(), camp.getSectorName());
        vars.put("is_capital", Boolean.toString(capital));
        vars.put("capital_display", capital
                ? plugin.lang().messageOrDefault("hologram.capital-label", "首都")
                : plugin.lang().messageOrDefault("hologram.sector-label", "分区"));
        WarManager.CampMaintenanceInfo maintenance = plugin.war().getMaintenanceInfo(camp.getStateName(), camp.getSectorName());
        String maintenanceTime;
        String maintenanceStatus;
        double fuelUnits;
        if (maintenance == null) {
            maintenanceTime = plugin.lang().messageOrDefault("hologram.fuel-disabled", plugin.lang().messageOrDefault("placeholders.none", "无"));
            maintenanceStatus = plugin.lang().messageOrDefault("hologram.fuel-status-disabled", "未启用");
            fuelUnits = camp.getFuel();
        } else if (maintenance.isOverdue()) {
            String template = plugin.lang().messageOrDefault("hologram.fuel-empty", "燃料耗尽");
            maintenanceTime = template;
            maintenanceStatus = plugin.lang().messageOrDefault("hologram.fuel-status-empty", "耗尽");
            fuelUnits = 0.0;
        } else {
            maintenanceTime = plugin.war().formatDuration(maintenance.getRemainingMillis());
            maintenanceStatus = plugin.lang().messageOrDefault(
                    maintenance.isWarning() ? "hologram.fuel-status-low" : "hologram.fuel-status-ok",
                    maintenance.isWarning() ? "燃料偏低" : "正常"
            );
            fuelUnits = maintenance.getFuelUnits();
        }
        String fuelAmount = number.format(Math.max(0.0, fuelUnits)) + "/" + camp.getMaxFuel();
        vars.put("maintenance_time", maintenanceTime);
        vars.put("maintenance_status", maintenanceStatus);
        vars.put("fuel_amount", fuelAmount);
        List<String> formatted = new ArrayList<>(templates.size());
        for (String template : templates) {
            String line = plugin.placeholders().apply(null, template, vars);
            formatted.add(line);
        }
        return formatted;
    }

    private void clearAll() {
        for (HologramEntry entry : holograms.values()) {
            entry.remove();
        }
        holograms.clear();
    }

    private String key(String state, String sector) {
        return state.toLowerCase(Locale.ROOT) + "|" + sector.toLowerCase(Locale.ROOT);
    }

    private static class HologramEntry {
        private String state;
        private String sector;
        private Location blockLocation;
        private final List<ArmorStand> stands = new ArrayList<>();

        private HologramEntry(String state, String sector) {
            this.state = state;
            this.sector = sector;
        }

        private void remove() {
            for (ArmorStand stand : stands) {
                if (stand != null) {
                    stand.remove();
                }
            }
            stands.clear();
        }
    }
}
