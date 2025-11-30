package dev.ameruzily.campsystem.listeners;

import dev.ameruzily.campsystem.CampSystem;
import dev.ameruzily.campsystem.managers.StateManager;
import dev.ameruzily.campsystem.models.Camp;
import dev.ameruzily.campsystem.models.CampBoundary;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import dev.lone.itemsadder.api.Events.FurniturePlaceEvent;
import org.bukkit.Particle;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import org.bukkit.scheduler.BukkitTask;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.lang.reflect.Method;

public class CampProtectionListener implements Listener {

    private static final PotionEffectType FATIGUE_TYPE = PotionEffectType.MINING_FATIGUE;

    private final CampSystem plugin;
    private final Map<UUID, String> fatigueStates = new HashMap<>();
    private final Map<UUID, StateManager.CampSectorInfo> currentZones = new HashMap<>();
    private final Set<String> blockedExact = new HashSet<>();
    private final List<Pattern> blockedPatterns = new ArrayList<>();

    private static final String PROTECTION_BYPASS_PERMISSION = "camprpg.protection.bypass";

    private double protectionRadius;
    private boolean fatigueEnabled;
    private int fatigueDuration;
    private int fatigueAmplifier;
    private boolean notificationsEnabled;
    private int titleFadeIn;
    private int titleStay;
    private int titleFadeOut;
    private boolean particlesEnabled;
    private int particleSteps;
    private int particlePoints;
    private long particleInterval;
    private double particleHeight;
    private float particleSize;
    private Color particleOwnColor;
    private Color particleOtherColor;
    private boolean coreBuildDeny;
    private int coreBuildRadius;
    private int coreBuildMinY;
    private int coreBuildMaxY;

    public CampProtectionListener(CampSystem plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        reloadSettings();
    }

    public void reloadSettings() {
        this.protectionRadius = Math.max(1.0, plugin.config().getDouble("camp.radius", 16.0));
        this.fatigueEnabled = plugin.getConfig().getBoolean("protection.mining-fatigue.enabled", true);
        this.fatigueAmplifier = Math.max(0, plugin.getConfig().getInt("protection.mining-fatigue.amplifier", 1));
        int seconds = Math.max(1, plugin.getConfig().getInt("protection.mining-fatigue.duration-seconds", 10));
        this.fatigueDuration = seconds * 20;
        this.notificationsEnabled = plugin.getConfig().getBoolean("protection.notifications.enabled", true);
        this.titleFadeIn = Math.max(0, plugin.getConfig().getInt("protection.notifications.title.fade-in", 10));
        this.titleStay = Math.max(0, plugin.getConfig().getInt("protection.notifications.title.stay", 60));
        this.titleFadeOut = Math.max(0, plugin.getConfig().getInt("protection.notifications.title.fade-out", 10));

        blockedExact.clear();
        blockedPatterns.clear();
        for (String raw : plugin.getConfig().getStringList("protection.blocked-interactions")) {
            if (raw == null) {
                continue;
            }
            String entry = raw.trim().toUpperCase(Locale.ROOT);
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.contains("*")) {
                String regex = entry.replace("*", ".*");
                blockedPatterns.add(Pattern.compile(regex));
            } else {
                blockedExact.add(entry);
            }
        }

        this.particlesEnabled = plugin.getConfig().getBoolean("protection.particles.enabled", true);
        this.particleSteps = Math.max(1, plugin.getConfig().getInt("protection.particles.steps", 24));
        this.particlePoints = Math.max(1, plugin.getConfig().getInt("protection.particles.points-per-step", 6));
        this.particleInterval = Math.max(1L, plugin.getConfig().getLong("protection.particles.step-delay-ticks", 2L));
        this.particleHeight = plugin.getConfig().getDouble("protection.particles.height", 0.15);
        this.particleSize = (float) Math.max(0.1, plugin.getConfig().getDouble("protection.particles.size", 1.0));
        this.particleOwnColor = parseColor(plugin.getConfig().getString("protection.particles.own-color", "#3CFF6B"), Color.fromRGB(60, 255, 107));
        this.particleOtherColor = parseColor(plugin.getConfig().getString("protection.particles.other-color", "#FF3B3B"), Color.fromRGB(255, 59, 59));

        this.coreBuildDeny = plugin.getConfig().getBoolean("protection.camp-core-build.enabled", true);
        this.coreBuildRadius = Math.max(0, plugin.getConfig().getInt("protection.camp-core-build.radius", 1));
        this.coreBuildMinY = plugin.getConfig().getInt("protection.camp-core-build.min-y-offset", 0);
        this.coreBuildMaxY = plugin.getConfig().getInt("protection.camp-core-build.max-y-offset", 2);

        resetAllFatigue();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (hasProtectionBypass(event.getPlayer())) {
            return;
        }
        if (!coreBuildDeny) {
            return;
        }
        handleCoreBuild(event.getPlayer(), event.getBlockPlaced().getLocation(), () -> event.setCancelled(true));
    }

    @EventHandler(ignoreCancelled = true)
    public void onFurniturePlace(FurniturePlaceEvent event) {
        if (!coreBuildDeny) {
            return;
        }
        Location location = resolveFurnitureLocation(event);
        handleCoreBuild(event.getPlayer(), location, () -> event.setCancelled(true));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (hasProtectionBypass(event.getPlayer())) {
            return;
        }

        var stateManager = plugin.state();
        var info = stateManager.findCampByLocation(event.getBlock().getLocation());
        if (info == null) {
            return;
        }

        var player = event.getPlayer();
        String playerState = stateManager.getStateName(player);
        if (playerState != null && playerState.equalsIgnoreCase(info.stateName())) {
            event.setCancelled(true);
            plugin.lang().send(player, "state.camp-break-own", Map.of(
                    "state", info.stateName(),
                    "sector", info.sectorName()
            ));
            return;
        }

        boolean enemyAtWar = playerState != null
                && !playerState.equalsIgnoreCase(info.stateName())
                && plugin.war().areStatesAtWar(playerState, info.stateName());

        event.setCancelled(true);

        if (playerState == null) {
            plugin.lang().send(player, "war.camp-protected");
            return;
        }

        double damage = plugin.config().getDouble("war.camp-damage-per-hit", 5.0);
        var result = plugin.war().damageCamp(info.stateName(), info.sectorName(), damage, playerState);

        switch (result.getStatus()) {
            case INVALID, NOT_FOUND -> plugin.lang().send(player, "war.camp-missing");
            case SUCCESS -> {
                DecimalFormat format = new DecimalFormat("0.0");
                plugin.lang().sendActionBar(player, "war.camp-damage-self", Map.of(
                        "state", info.stateName(),
                        "sector", info.sectorName(),
                        "hp", format.format(result.getHp()),
                        "max", format.format(result.getMaxHp())
                ));
                if (plugin.campDamageSound() != null) {
                    plugin.campDamageSound().play(player);
                }
            }
            case BROKEN -> plugin.lang().send(player, "war.camp-broken-self", Map.of(
                    "state", info.stateName(),
                    "sector", info.sectorName()
            ));
            case NOT_AT_WAR -> {
                if (!enemyAtWar) {
                    plugin.lang().send(player, "war.not-at-war", Map.of("state", info.stateName()));
                }
            }
            default -> {
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (hasProtectionBypass(event.getPlayer())) {
            clearPlayer(event.getPlayer());
            return;
        }
        updatePlayerZone(event.getPlayer(), to);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clearPlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.state().refreshIdeologyPermissionFor(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (hasProtectionBypass(event.getPlayer())) {
            return;
        }
        try {
            Method handMethod = event.getClass().getMethod("getHand");
            Object hand = handMethod.invoke(event);
            if (hand != null && hand.toString().equalsIgnoreCase("OFF_HAND")) {
                return;
            }
        } catch (Exception ignored) {
        }

        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        StateManager.CampSectorInfo campAtBlock = plugin.state().findCampByLocation(block.getLocation());
        if (campAtBlock != null) {
            Player player = event.getPlayer();
            String playerState = plugin.state().getStateName(player);
            if (playerState == null || !playerState.equalsIgnoreCase(campAtBlock.stateName())) {
                if (playerState != null && !playerState.equalsIgnoreCase(campAtBlock.stateName())
                        && plugin.war().areStatesAtWar(playerState, campAtBlock.stateName())) {
                    return;
                }
                event.setCancelled(true);
                plugin.lang().sendActionBar(player, "state.protected-block", Map.of("state", campAtBlock.stateName()));
                if (plugin.protectedBlockSound() != null) {
                    plugin.protectedBlockSound().play(player);
                }
                return;
            }
            Camp camp = plugin.war().getCamp(campAtBlock.stateName(), campAtBlock.sectorName());
            if (camp != null && plugin.gui() != null) {
                event.setCancelled(true);
                if (plugin.campClickSound() != null) {
                    plugin.campClickSound().play(player);
                }
                plugin.gui().openMain(player, camp);
            }
            return;
        }
        Material type = block.getType();
        if (!shouldProtect(type)) {
            return;
        }

        StateManager.CampSectorInfo info = findActiveCamp(block.getLocation());
        if (info == null) {
            return;
        }

        Player player = event.getPlayer();
        String playerState = plugin.state().getStateName(player);
        if (playerState != null && playerState.equalsIgnoreCase(info.stateName())) {
            return;
        }

        if (playerState != null && plugin.war().areStatesAtWar(playerState, info.stateName())) {
            return;
        }

        event.setCancelled(true);
        plugin.lang().sendActionBar(player, "state.protected-block", Map.of("state", info.stateName()));
        if (plugin.protectedBlockSound() != null) {
            plugin.protectedBlockSound().play(player);
        }
    }

    public void clearCampEffects(String state, String sector) {
        String key = buildKey(state, sector);
        for (UUID id : new HashSet<>(fatigueStates.keySet())) {
            String current = fatigueStates.get(id);
            if (key.equals(current)) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    removeFatigue(player);
                }
                fatigueStates.remove(id);
                currentZones.remove(id);
            }
        }
    }

    private StateManager.CampSectorInfo findActiveCamp(Location location) {
        StateManager.CampSectorInfo info = plugin.state().findCampInRadius(location, protectionRadius);
        if (info == null) {
            return null;
        }
        return plugin.war().getCamp(info.stateName(), info.sectorName()) != null ? info : null;
    }

    private void updatePlayerZone(Player player, Location location) {
        UUID id = player.getUniqueId();
        StateManager.CampSectorInfo previous = currentZones.get(id);
        StateManager.CampSectorInfo current = findActiveCamp(location);

        if (current == null) {
            if (previous != null) {
                sendLeaveNotification(player, previous, null);
                currentZones.remove(id);
            }
            fatigueStates.remove(id);
            removeFatigue(player);
            return;
        }

        boolean changed = previous == null || !sameZone(previous, current);
        if (changed) {
            if (previous != null) {
                sendLeaveNotification(player, previous, current);
                showBoundary(player, previous.stateName(), previous.sectorName());
            }
            sendEnterNotification(player, current);
            if (current != null) {
                showBoundary(player, current.stateName(), current.sectorName());
            }
        }

        currentZones.put(id, current);
        handleFatigue(player, current);
    }

    private void clearPlayer(Player player) {
        UUID id = player.getUniqueId();
        fatigueStates.remove(id);
        currentZones.remove(id);
        removeFatigue(player);
    }

    private void applyFatigue(Player player, int amplifier) {
        if (!fatigueEnabled || fatigueDuration <= 0) {
            return;
        }
        int level = Math.max(0, amplifier);
        PotionEffect effect = new PotionEffect(FATIGUE_TYPE, fatigueDuration, level, true, false, false);
        player.addPotionEffect(effect);
    }

    private void removeFatigue(Player player) {
        player.removePotionEffect(FATIGUE_TYPE);
    }

    private void handleCoreBuild(Player player, Location location, Runnable cancelAction) {
        if (player == null || location == null) {
            return;
        }
        StateManager.CampSectorInfo nearby = plugin.state().findCampNearColumn(location, coreBuildRadius, coreBuildMinY, coreBuildMaxY);
        if (nearby == null) {
            return;
        }
        if (cancelAction != null) {
            cancelAction.run();
        }
        plugin.lang().send(player, "state.camp-core-blocked", Map.of(
                "state", nearby.stateName(),
                "sector", nearby.sectorName()
        ));
    }

    private boolean hasProtectionBypass(Player player) {
        return player != null && player.hasPermission(PROTECTION_BYPASS_PERMISSION);
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

    private void handleFatigue(Player player, StateManager.CampSectorInfo info) {
        UUID id = player.getUniqueId();
        if (!fatigueEnabled) {
            fatigueStates.remove(id);
            removeFatigue(player);
            return;
        }

        String playerState = plugin.state().getStateName(player);
        if (playerState != null && playerState.equalsIgnoreCase(info.stateName())) {
            fatigueStates.remove(id);
            removeFatigue(player);
            return;
        }

        String key = buildKey(info);
        fatigueStates.put(id, key);
        int amplifier = fatigueAmplifier;
        Camp camp = plugin.war().getCamp(info.stateName(), info.sectorName());
        if (camp != null && camp.getFatigueAmplifier() > 0) {
            amplifier = camp.getFatigueAmplifier();
        }
        applyFatigue(player, amplifier);
    }

    private boolean sameZone(StateManager.CampSectorInfo a, StateManager.CampSectorInfo b) {
        if (a == null || b == null) {
            return false;
        }
        return a.stateName().equalsIgnoreCase(b.stateName()) && a.sectorName().equalsIgnoreCase(b.sectorName());
    }

    private void sendEnterNotification(Player player, StateManager.CampSectorInfo info) {
        if (!notificationsEnabled) {
            return;
        }
        Map<String, String> vars = buildZoneVars(player, info, null);
        sendNotification(player,
                "protection.notify.enter.title",
                "protection.notify.enter.subtitle",
                "protection.notify.enter.message",
                "protection.notify.enter.actionbar",
                vars);
    }

    private void sendLeaveNotification(Player player, StateManager.CampSectorInfo previous, StateManager.CampSectorInfo next) {
        if (!notificationsEnabled) {
            return;
        }
        Map<String, String> vars = buildZoneVars(player, previous, next);
        sendNotification(player,
                "protection.notify.leave.title",
                "protection.notify.leave.subtitle",
                "protection.notify.leave.message",
                "protection.notify.leave.actionbar",
                vars);
    }

    public void showBoundary(Player player, String stateName, String sectorName) {
        if (!particlesEnabled || player == null || stateName == null || sectorName == null) {
            return;
        }

        Location center = plugin.state().getSectorLocation(stateName, sectorName);
        if (center == null || center.getWorld() == null) {
            return;
        }

        CampBoundary boundary = plugin.state().getSectorBoundary(stateName, sectorName);
        if (boundary == null) {
            return;
        }
        double west = boundary.west();
        double east = boundary.east();
        double north = boundary.north();
        double south = boundary.south();
        double perimeter = 2 * ((west + east) + (north + south));
        if (perimeter <= 0.0) {
            return;
        }

        String playerState = plugin.state().getStateName(player);
        boolean own = playerState != null && playerState.equalsIgnoreCase(stateName);
        Color color = own ? particleOwnColor : particleOtherColor;
        Particle.DustOptions dust = new Particle.DustOptions(color, particleSize);
        int steps = Math.max(1, particleSteps);
        int points = Math.max(1, particlePoints);
        long delay = Math.max(1L, particleInterval);
        double left = center.getX() - west;
        double right = center.getX() + east;
        double top = center.getZ() - north;
        double bottom = center.getZ() + south;

        AtomicInteger index = new AtomicInteger();
        BukkitTask[] handle = new BukkitTask[1];
        handle[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int step = index.getAndIncrement();
            if (step >= steps) {
                handle[0].cancel();
                return;
            }

            double distance = (perimeter / steps) * step;
            for (int i = 0; i < points; i++) {
                double offset = (double) i / points;
                double progress = distance + offset;
                double pos = progress % perimeter;
                double x;
                double z;
                double width = right - left;
                double height = bottom - top;
                if (pos <= width) {
                    x = left + pos;
                    z = top;
                } else if (pos <= width + height) {
                    x = right;
                    z = top + (pos - width);
                } else if (pos <= width * 2 + height) {
                    x = right - (pos - width - height);
                    z = bottom;
                } else {
                    x = left;
                    z = bottom - (pos - width * 2 - height);
                }

                Location particleLocation = new Location(center.getWorld(), x + 0.5, center.getY() + particleHeight, z + 0.5);
                player.spawnParticle(Particle.DUST, particleLocation, 1, 0.0, 0.0, 0.0, 0.0, dust);
            }
        }, 0L, delay);
    }

    private Map<String, String> buildZoneVars(Player player, StateManager.CampSectorInfo current, StateManager.CampSectorInfo next) {
        Map<String, String> vars = new HashMap<>();
        String none = plugin.lang().messageOrDefault("placeholders.none", "无");
        String playerState = plugin.state().getStateName(player);
        vars.put("player_state", playerState == null ? none : playerState);

        if (current != null) {
            boolean own = playerState != null && playerState.equalsIgnoreCase(current.stateName());
            vars.put("state", current.stateName());
            vars.put("state_display", current.stateName());
            vars.put("sector", current.sectorName());
            vars.put("sector_display", current.sectorName());
            vars.put("ideology_display", plugin.state().getIdeologyDisplay(current.stateName()));
            vars.put("is_own", Boolean.toString(own));
            vars.put("relation", plugin.lang().messageOrDefault(own ? "protection.relation-own" : "protection.relation-foreign", own ? "己方" : "外部"));
            vars.put("capital_display", plugin.state().isCapitalSector(current.stateName(), current.sectorName())
                    ? plugin.lang().messageOrDefault("hologram.capital-label", "首都")
                    : plugin.lang().messageOrDefault("hologram.sector-label", "分区"));
        } else {
            vars.put("state", none);
            vars.put("state_display", none);
            vars.put("sector", none);
            vars.put("sector_display", none);
            vars.put("ideology_display", plugin.lang().messageOrDefault("campinfo.ideology-unknown", "待定"));
            vars.put("is_own", "false");
            vars.put("relation", plugin.lang().messageOrDefault("protection.relation-foreign", "外部"));
            vars.put("capital_display", plugin.lang().messageOrDefault("hologram.sector-label", "分区"));
        }

        if (next != null) {
            boolean own = playerState != null && playerState.equalsIgnoreCase(next.stateName());
            vars.put("next_state", next.stateName());
            vars.put("next_state_display", next.stateName());
            vars.put("next_sector", next.sectorName());
            vars.put("next_sector_display", next.sectorName());
            vars.put("next_ideology_display", plugin.state().getIdeologyDisplay(next.stateName()));
            vars.put("next_is_own", Boolean.toString(own));
            vars.put("next_relation", plugin.lang().messageOrDefault(own ? "protection.relation-own" : "protection.relation-foreign", own ? "己方" : "外部"));
            vars.put("next_capital_display", plugin.state().isCapitalSector(next.stateName(), next.sectorName())
                    ? plugin.lang().messageOrDefault("hologram.capital-label", "首都")
                    : plugin.lang().messageOrDefault("hologram.sector-label", "分区"));
        } else {
            vars.put("next_state", none);
            vars.put("next_state_display", none);
            vars.put("next_sector", none);
            vars.put("next_sector_display", none);
            vars.put("next_ideology_display", plugin.lang().messageOrDefault("campinfo.ideology-unknown", "待定"));
            vars.put("next_is_own", "false");
            vars.put("next_relation", plugin.lang().messageOrDefault("protection.relation-foreign", "外部"));
            vars.put("next_capital_display", plugin.lang().messageOrDefault("hologram.sector-label", "分区"));
        }

        return vars;
    }

    private void sendNotification(Player player, String titlePath, String subtitlePath, String messagePath, String actionbarPath, Map<String, String> vars) {
        var placeholders = plugin.placeholders();

        String title = plugin.lang().messageOrDefault(titlePath, "");
        String subtitle = plugin.lang().messageOrDefault(subtitlePath, "");
        String message = plugin.lang().messageOrDefault(messagePath, "");
        String actionbar = plugin.lang().messageOrDefault(actionbarPath, "");

        if (placeholders != null) {
            title = placeholders.apply(player, title, vars);
            subtitle = placeholders.apply(player, subtitle, vars);
            message = placeholders.apply(player, message, vars);
            actionbar = placeholders.apply(player, actionbar, vars);
        } else if (vars != null) {
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                String token = "%" + entry.getKey() + "%";
                String value = entry.getValue();
                title = title.replace(token, value);
                subtitle = subtitle.replace(token, value);
                message = message.replace(token, value);
                actionbar = actionbar.replace(token, value);
            }
        }

        if (!title.isEmpty() || !subtitle.isEmpty()) {
            player.sendTitle(title, subtitle, titleFadeIn, titleStay, titleFadeOut);
        }

        if (!message.trim().isEmpty()) {
            player.sendMessage(message);
        }

        if (!actionbar.trim().isEmpty()) {
            player.sendActionBar(actionbar);
        }
    }

    private void resetAllFatigue() {
        for (UUID id : new HashSet<>(fatigueStates.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                removeFatigue(player);
            }
        }
        fatigueStates.clear();
        currentZones.clear();
    }

    private Color parseColor(String value, Color fallback) {
        if (value == null) {
            return fallback;
        }
        String hex = value.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (hex.length() != 6) {
            return fallback;
        }
        try {
            int rgb = Integer.parseInt(hex, 16);
            int red = (rgb >> 16) & 0xFF;
            int green = (rgb >> 8) & 0xFF;
            int blue = rgb & 0xFF;
            return Color.fromRGB(red, green, blue);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean shouldProtect(Material type) {
        if (type == null) {
            return false;
        }
        String name = type.name();
        if (blockedExact.contains(name)) {
            return true;
        }
        for (Pattern pattern : blockedPatterns) {
            if (pattern.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    private String buildKey(StateManager.CampSectorInfo info) {
        return buildKey(info.stateName(), info.sectorName());
    }

    private String buildKey(String state, String sector) {
        String left = state == null ? "" : state.toLowerCase(Locale.ROOT);
        String right = sector == null ? "" : sector.toLowerCase(Locale.ROOT);
        return left + "|" + right;
    }
}
