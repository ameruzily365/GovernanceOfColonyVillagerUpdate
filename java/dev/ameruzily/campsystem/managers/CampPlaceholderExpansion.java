package dev.ameruzily.campsystem.managers;

import dev.ameruzily.campsystem.CampSystem;
import dev.ameruzily.campsystem.models.Camp;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.util.Locale;

public class CampPlaceholderExpansion extends PlaceholderExpansion {
    private final CampSystem plugin;
    private final DecimalFormat number = new DecimalFormat("0.0");

    public CampPlaceholderExpansion(CampSystem plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "goc";
    }

    @Override
    public String getAuthor() {
        return "CampSystem";
    }

    @Override
    public String getVersion() {
        return "2.0.0";
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        if (player == null) {
            return "";
        }
        if (identifier == null) {
            return "";
        }
        String id = identifier.toLowerCase(Locale.ROOT);
        String stateName = plugin.state().getStateName(player);
        if (id.equals("state") || id.equals("state_name")) {
            return stateName == null ? plugin.lang().messageOrDefault("placeholders.none", "无") : stateName;
        }
        if (id.equals("state_ideology")) {
            return plugin.state().getIdeologyDisplay(stateName);
        }
        if (id.equals("state_role")) {
            return plugin.state().getRoleDisplay(player);
        }
        if (id.equals("state_role_key")) {
            return plugin.state().getRoleKey(player);
        }
        if (id.equals("state_captain")) {
            if (stateName == null) {
                return plugin.lang().messageOrDefault("placeholders.none", "无");
            }
            var data = plugin.state().getState(stateName);
            if (data == null) {
                return plugin.lang().messageOrDefault("placeholders.none", "无");
            }
            OfflinePlayer captain = Bukkit.getOfflinePlayer(data.captain);
            String name = captain.getName();
            return name == null ? plugin.lang().messageOrDefault("placeholders.none", "无") : name;
        }
        if (id.equals("state_sector_count")) {
            if (stateName == null) {
                return "0";
            }
            var data = plugin.state().getState(stateName);
            return data == null ? "0" : String.valueOf(data.sectors.size());
        }
        if (id.equals("state_capital")) {
            if (stateName == null) {
                return plugin.lang().messageOrDefault("placeholders.none", "无");
            }
            String capital = plugin.state().getCapital(stateName);
            return capital == null ? plugin.lang().messageOrDefault("placeholders.none", "无") : capital;
        }
        if (id.equals("state_condemn_target")) {
            if (stateName == null) {
                return plugin.lang().messageOrDefault("placeholders.none", "无");
            }
            if (plugin.war().isCivilWarPending(stateName)) {
                String target = plugin.war().getPendingCivilWarTarget(stateName);
                return target == null ? plugin.lang().messageOrDefault("placeholders.none", "无") : target;
            }
            String target = plugin.war().getCondemnationTarget(stateName);
            return target == null ? plugin.lang().messageOrDefault("placeholders.none", "无") : target;
        }
        if (id.equals("state_condemn_timer")) {
            if (stateName == null) {
                return plugin.lang().messageOrDefault("placeholders.none", "无");
            }
            if (plugin.war().isCivilWarPending(stateName)) {
                return plugin.lang().messageOrDefault("war.civilwar-pending-placeholder", "Pending");
            }
            long remaining = plugin.war().getCondemnationRemaining(stateName);
            if (remaining < 0L) {
                return plugin.lang().messageOrDefault("placeholders.none", "无");
            }
            if (remaining <= 0L) {
                return plugin.lang().messageOrDefault("war.condemn-ready-placeholder", "READY");
            }
            return plugin.war().formatDuration(remaining);
        }
        if (id.startsWith("camp_fuel_unit_")) {
            return handleFuelTimerPlaceholder(id.substring("camp_fuel_unit_".length()), true);
        }
        if (id.startsWith("camp_fuel_total_")) {
            return handleFuelTimerPlaceholder(id.substring("camp_fuel_total_".length()), false);
        }
        if (id.startsWith("sector_hp_") || id.startsWith("sector_hpmax_") || id.startsWith("sector_hppercent_")) {
            return handleSectorCampPlaceholder(stateName, id);
        }
        if (id.startsWith("sector_maintain_timer_") || id.startsWith("sector_maintain_status_")) {
            return handleSectorMaintenancePlaceholder(stateName, id);
        }
        if (id.startsWith("sector_fuel_unit_")) {
            String suffix = id.substring("sector_fuel_unit_".length());
            return handleSectorFuelPlaceholder(stateName, suffix, true);
        }
        if (id.startsWith("sector_fuel_total_")) {
            String suffix = id.substring("sector_fuel_total_".length());
            return handleSectorFuelPlaceholder(stateName, suffix, false);
        }
        if (id.startsWith("camp_hp_") || id.startsWith("camp_hpmax_") || id.startsWith("camp_hppercent_")) {
            return handleArbitraryCampPlaceholder(id);
        }
        if (id.startsWith("camp_maintain_timer_") || id.startsWith("camp_maintain_status_")) {
            return handleArbitraryMaintenancePlaceholder(id);
        }
        if (id.startsWith("state_capital_hp")) {
            return handleCapitalPlaceholder(stateName, id);
        }
        return null;
    }

    private String handleCapitalPlaceholder(String stateName, String id) {
        if (stateName == null) {
            return "0";
        }
        String capital = plugin.state().getCapital(stateName);
        if (capital == null) {
            return "0";
        }
        Camp camp = plugin.war().getCamp(stateName, capital);
        if (camp == null) {
            return "0";
        }
        if (id.equals("state_capital_hpmax")) {
            return number.format(camp.getMaxHp());
        }
        if (id.equals("state_capital_hppercent")) {
            return formatPercent(camp);
        }
        return number.format(camp.getHp());
    }

    private String handleArbitraryCampPlaceholder(String id) {
        String suffix;
        boolean percent = false;
        boolean max = false;
        if (id.startsWith("camp_hppercent_")) {
            suffix = id.substring("camp_hppercent_".length());
            percent = true;
        } else if (id.startsWith("camp_hpmax_")) {
            suffix = id.substring("camp_hpmax_".length());
            max = true;
        } else {
            suffix = id.substring("camp_hp_".length());
        }
        if (suffix.isEmpty()) {
            return "";
        }
        String[] parts = suffix.split(":", 2);
        if (parts.length < 2) {
            return "";
        }
        Camp camp = plugin.war().getCamp(parts[0], parts[1]);
        if (camp == null) {
            return "";
        }
        if (percent) {
            return formatPercent(camp);
        }
        if (max) {
            return number.format(camp.getMaxHp());
        }
        return number.format(camp.getHp());
    }

    private String handleSectorCampPlaceholder(String stateName, String id) {
        if (stateName == null) {
            return "";
        }
        boolean percent = false;
        boolean max = false;
        String sector;
        if (id.startsWith("sector_hppercent_")) {
            sector = id.substring("sector_hppercent_".length());
            percent = true;
        } else if (id.startsWith("sector_hpmax_")) {
            sector = id.substring("sector_hpmax_".length());
            max = true;
        } else {
            sector = id.substring("sector_hp_".length());
        }
        String resolved = plugin.state().resolveSectorName(stateName, sector);
        if (resolved == null) {
            return "";
        }
        Camp camp = plugin.war().getCamp(stateName, resolved);
        if (camp == null) {
            return "";
        }
        if (percent) {
            return formatPercent(camp);
        }
        if (max) {
            return number.format(camp.getMaxHp());
        }
        return number.format(camp.getHp());
    }

    private String handleSectorMaintenancePlaceholder(String stateName, String id) {
        if (stateName == null) {
            return "";
        }
        boolean status = id.startsWith("sector_maintain_status_");
        String sector = id.substring(status ? "sector_maintain_status_".length() : "sector_maintain_timer_".length());
        String resolved = plugin.state().resolveSectorName(stateName, sector);
        if (resolved == null) {
            return "";
        }
        WarManager.CampMaintenanceInfo info = plugin.war().getMaintenanceInfo(stateName, resolved);
        return status ? formatMaintenanceStatus(info) : formatMaintenanceTimer(info);
    }

    private String handleSectorFuelPlaceholder(String stateName, String sector, boolean unit) {
        if (stateName == null) {
            return "";
        }
        String resolved = plugin.state().resolveSectorName(stateName, sector);
        if (resolved == null) {
            return "";
        }
        WarManager.CampMaintenanceInfo info = plugin.war().getMaintenanceInfo(stateName, resolved);
        return unit ? formatFuelUnit(info) : formatFuelTotal(info);
    }

    private String handleArbitraryMaintenancePlaceholder(String id) {
        boolean status = id.startsWith("camp_maintain_status_");
        String suffix = id.substring(status ? "camp_maintain_status_".length() : "camp_maintain_timer_".length());
        if (suffix.isEmpty()) {
            return "";
        }
        String[] parts = suffix.split(":", 2);
        if (parts.length < 2) {
            return "";
        }
        WarManager.CampMaintenanceInfo info = plugin.war().getMaintenanceInfo(parts[0], parts[1]);
        return status ? formatMaintenanceStatus(info) : formatMaintenanceTimer(info);
    }

    private String handleFuelTimerPlaceholder(String suffix, boolean unit) {
        if (suffix.isEmpty()) {
            return "";
        }
        String[] parts = suffix.split(":", 2);
        if (parts.length < 2) {
            return "";
        }
        WarManager.CampMaintenanceInfo info = plugin.war().getMaintenanceInfo(parts[0], parts[1]);
        return unit ? formatFuelUnit(info) : formatFuelTotal(info);
    }

    private String formatMaintenanceTimer(WarManager.CampMaintenanceInfo info) {
        if (info == null) {
            return plugin.lang().messageOrDefault("placeholders.fuel-disabled", plugin.lang().messageOrDefault("placeholders.none", "无"));
        }
        if (info.isOverdue()) {
            return plugin.lang().messageOrDefault("placeholders.fuel-empty", "EMPTY");
        }
        return plugin.war().formatDuration(info.getRemainingMillis());
    }

    private String formatFuelUnit(WarManager.CampMaintenanceInfo info) {
        if (info == null || info.getInterval() <= 0L) {
            return plugin.lang().messageOrDefault("placeholders.fuel-disabled", plugin.lang().messageOrDefault("placeholders.none", "无"));
        }
        return plugin.war().formatDuration(info.getInterval());
    }

    private String formatFuelTotal(WarManager.CampMaintenanceInfo info) {
        if (info == null) {
            return plugin.lang().messageOrDefault("placeholders.fuel-disabled", plugin.lang().messageOrDefault("placeholders.none", "无"));
        }
        if (info.isOverdue()) {
            return plugin.lang().messageOrDefault("placeholders.fuel-empty", "EMPTY");
        }
        return plugin.war().formatDuration(info.getRemainingMillis());
    }

    private String formatMaintenanceStatus(WarManager.CampMaintenanceInfo info) {
        if (info == null) {
            return plugin.lang().messageOrDefault("placeholders.fuel-disabled", plugin.lang().messageOrDefault("placeholders.none", "无"));
        }
        if (info.isOverdue()) {
            return plugin.lang().messageOrDefault("placeholders.fuel-empty", "EMPTY");
        }
        if (info.isWarning()) {
            return plugin.lang().messageOrDefault("placeholders.fuel-low", "LOW");
        }
        return plugin.lang().messageOrDefault("placeholders.fuel-ok", "OK");
    }

    private String formatPercent(Camp camp) {
        if (camp.getMaxHp() <= 0) {
            return "0";
        }
        double percent = (camp.getHp() / camp.getMaxHp()) * 100.0;
        return number.format(percent);
    }
}
