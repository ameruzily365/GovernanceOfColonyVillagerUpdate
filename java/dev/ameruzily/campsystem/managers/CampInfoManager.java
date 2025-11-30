package dev.ameruzily.campsystem.managers;

import dev.ameruzily.campsystem.CampSystem;
import dev.ameruzily.campsystem.models.Camp;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class CampInfoManager {
    private static final long SAVE_INTERVAL_MS = 5000L;

    private final CampSystem plugin;
    private final File file;

    private boolean dirty;
    private long lastSave;
    private BukkitTask pendingTask;

    public CampInfoManager(CampSystem plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "campinfo.yml");
    }

    public synchronized void reload() {
        ensureFile();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (pendingTask != null) {
            pendingTask.cancel();
            pendingTask = null;
        }
        dirty = false;
        lastSave = System.currentTimeMillis();

        plugin.state().loadFromCampInfo(yaml);
        plugin.war().loadFromCampInfo(yaml);
    }

    public synchronized void markDirty() {
        dirty = true;
        long now = System.currentTimeMillis();
        if (now - lastSave >= SAVE_INTERVAL_MS) {
            saveInternal();
            return;
        }
        if (pendingTask != null) {
            return;
        }
        long delayMs = SAVE_INTERVAL_MS - (now - lastSave);
        long delayTicks = Math.max(1L, delayMs / 50L);
        pendingTask = new BukkitRunnable() {
            @Override
            public void run() {
                synchronized (CampInfoManager.this) {
                    pendingTask = null;
                    if (dirty) {
                        saveInternal();
                    }
                }
            }
        }.runTaskLater(plugin, delayTicks);
    }

    public synchronized void saveNow() {
        dirty = true;
        saveInternal();
    }

    private void ensureFile() {
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            plugin.getLogger().warning("Failed to create data folder for campinfo.yml");
        }
        if (!file.exists()) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("meta.next-auto-id", plugin.state().getNextAutoId());
            yaml.createSection("states");
            try {
                yaml.save(file);
            } catch (IOException ex) {
                plugin.getLogger().warning("Unable to create default campinfo.yml: " + ex.getMessage());
            }
        }
    }

    private void saveInternal() {
        ensureFile();
        YamlConfiguration yaml = buildSnapshot();
        try {
            yaml.save(file);
            dirty = false;
            lastSave = System.currentTimeMillis();
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save campinfo.yml: " + ex.getMessage());
        }
    }

    private YamlConfiguration buildSnapshot() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("meta.next-auto-id", plugin.state().getNextAutoId());

        ConfigurationSection statesSection = yaml.createSection("states");
        List<StateManager.StateData> states = new ArrayList<>(plugin.state().getStates());
        states.sort(Comparator.comparing(s -> s.name, String.CASE_INSENSITIVE_ORDER));

        for (StateManager.StateData state : states) {
            ConfigurationSection stateSection = statesSection.createSection(state.name);
            if (state.captain != null) {
                stateSection.set("captain", state.captain.toString());
            }
            stateSection.set("bank", state.bankBalance);
            stateSection.set("tax", state.taxAmount);
            if (state.ideologyId != null && !state.ideologyId.isEmpty()) {
                stateSection.set("ideology", state.ideologyId);
            }
            if (state.ideologyChangedAt > 0L) {
                stateSection.set("ideology-changed-at", state.ideologyChangedAt);
            }
            if (state.capitalSector != null && !state.capitalSector.isEmpty()) {
                stateSection.set("capital", state.capitalSector);
            }

            List<String> members = state.members.stream()
                    .map(UUID::toString)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
            stateSection.set("members", members);

            Set<UUID> governorIds = state.sectors.values().stream()
                    .map(StateManager.SectorData::getOwner)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (state.captain != null) {
                governorIds.remove(state.captain);
            }
            List<String> governorList = governorIds.stream()
                    .map(UUID::toString)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
            Set<UUID> memberRoles = new LinkedHashSet<>(state.members);
            if (state.captain != null) {
                memberRoles.remove(state.captain);
            }
            memberRoles.removeAll(governorIds);
            List<String> memberRoleList = memberRoles.stream()
                    .map(UUID::toString)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());

            ConfigurationSection rolesSection = stateSection.createSection("roles");
            if (state.captain != null) {
                rolesSection.set("captain", state.captain.toString());
            }
            rolesSection.set("governors", governorList);
            rolesSection.set("members", memberRoleList);

            List<Map<String, Object>> transactions = new ArrayList<>();
            for (StateManager.BankTransaction tx : state.transactions) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("timestamp", tx.getTimestamp());
                entry.put("type", tx.getType().name());
                entry.put("amount", tx.getAmount());
                entry.put("balance", tx.getBalance());
                if (tx.getActor() != null) {
                    entry.put("actor", tx.getActor().toString());
                }
                transactions.add(entry);
            }
            stateSection.set("transactions", transactions);

            ConfigurationSection sectorsSection = stateSection.createSection("sectors");
            for (StateManager.SectorData sector : state.sectors.values()) {
                ConfigurationSection sectorSection = sectorsSection.createSection(sector.getName());
                Location location = sector.getLocation();
                if (location != null) {
                    World world = location.getWorld();
                    if (world != null) {
                        sectorSection.set("world", world.getName());
                    }
                    sectorSection.set("x", location.getBlockX());
                    sectorSection.set("y", location.getBlockY());
                    sectorSection.set("z", location.getBlockZ());
                }
                if (sector.getOwner() != null) {
                    sectorSection.set("owner", sector.getOwner().toString());
                }

                Camp camp = plugin.war().getCamp(state.name, sector.getName());
                if (camp != null) {
                    ConfigurationSection campSection = sectorSection.createSection("camp");
                    campSection.set("hp", camp.getHp());
                    campSection.set("max-hp", camp.getMaxHp());
                    campSection.set("broken-since", camp.getBrokenSince());
                    campSection.set("last-damaged", camp.getLastDamagedAt());
                    campSection.set("last-maintained", camp.getLastMaintainedAt());
                    campSection.set("next-maintenance", camp.getNextMaintenanceAt());
                    campSection.set("maintenance-warning", camp.isMaintenanceWarningIssued());
                    campSection.set("maintenance-overdue", camp.isMaintenanceOverdueNotified());
                    campSection.set("last-maintenance-decay", camp.getLastMaintenanceDecayAt());
                    campSection.set("fuel", camp.getFuel());
                    campSection.set("max-fuel", camp.getMaxFuel());
                    campSection.set("last-fuel-check", camp.getLastFuelCheckAt());
                    campSection.set("heal-rate", camp.getHealRate());
                    campSection.set("fatigue-amplifier", camp.getFatigueAmplifier());
                    campSection.set("hp-level", camp.getHpLevel());
                    campSection.set("fuel-level", camp.getFuelLevel());
                    campSection.set("heal-level", camp.getHealLevel());
                    campSection.set("fatigue-level", camp.getFatigueLevel());
                    campSection.set("storage-level", camp.getStorageLevel());
                    campSection.set("efficiency-level", camp.getEfficiencyLevel());
                    if (!camp.getModules().isEmpty()) {
                        campSection.createSection("modules", camp.getModules());
                    }
                    campSection.set("stored-money", camp.getStoredMoney());
                    campSection.set("max-stored-money", camp.getMaxStoredMoney());
                    campSection.set("max-stored-items", camp.getMaxStoredItems());
                    campSection.set("last-production", camp.getLastProductionAt());
                    if (!camp.getStoredItems().isEmpty()) {
                        campSection.createSection("stored-items", camp.getStoredItems());
                    }
                }
            }
        }

        return yaml;
    }
}
