package dev.ameruzily.campsystem.managers;

import dev.ameruzily.campsystem.CampSystem;
import dev.ameruzily.campsystem.models.Camp;
import dev.ameruzily.campsystem.models.SoundSettings;
import dev.ameruzily.campsystem.models.WarData;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class WarManager {
    private final CampSystem plugin;

    private static final Set<String> MAINTENANCE_ACTIONBAR_KEYS = Set.of(
            "state.maintenance-warning",
            "state.maintenance-overdue",
            "state.maintenance-damage"
    );

    private final Map<String, Camp> camps = new HashMap<>();
    private final Map<String, WarData> wars = new HashMap<>(); // key: a:b
    private final Map<String, Long> lastWarCommand = new HashMap<>();
    private final Map<String, Long> lastCondemnCommand = new HashMap<>();
    private final Map<String, Long> lastMoveCommand = new HashMap<>();
    private final Map<String, CondemnationData> condemnations = new HashMap<>();
    private final Map<String, SurrenderRequest> surrenderRequests = new HashMap<>();
    private final Map<String, BukkitTask> capitalHoldTasks = new HashMap<>();
    private final Map<String, String> capitalHoldAttackers = new HashMap<>();
    private final Map<String, PendingCivilWar> pendingCivilWars = new HashMap<>();
    private final Map<String, BukkitTask> raiderTasks = new HashMap<>();
    private BukkitTask maintenanceTask;
    private BukkitTask autoHealTask;

    private final Map<CampUpgradeType, UpgradeTree> upgradeTrees = new EnumMap<>(CampUpgradeType.class);
    private final Map<String, ModuleDefinition> moduleDefinitions = new LinkedHashMap<>();
    private double baseMaxHp;
    private int baseMaxFuel;
    private double baseHealRate;
    private int baseFatigueAmplifier;

    private boolean productionEnabled;
    private long baseProductionIntervalMs;
    private double productionMoney;
    private Map<StateManager.ItemDescriptor, Integer> productionItems = new HashMap<>();
    private double baseStoredMoneyCap;
    private int baseStoredItemCap;

    public WarManager(CampSystem plugin) {
        this.plugin = plugin;
        reloadUpgradeSettings();
        loadModuleSettings();
        loadProductionSettings();
        startAutoHealTask();
        startMaintenanceTask();
    }

    public void reloadSettings() {
        reloadUpgradeSettings();
        loadModuleSettings();
        loadProductionSettings();
        reapplyUpgrades();
        startAutoHealTask();
        startMaintenanceTask();
    }

    private String warKey(String a, String b) { return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a; }
    private String campKey(String state, String sector) { return state.toLowerCase() + "|" + sector.toLowerCase(); }

    public void markDirty() {
        CampInfoManager info = plugin.campInfo();
        if (info != null) {
            info.markDirty();
        }
    }

    private void updateDynmap(Camp camp) { }

    public Collection<WarData> getActiveWars() { return wars.values(); }

    private String normalizeState(String state) {
        return state == null ? null : state.toLowerCase(Locale.ROOT);
    }

    private Set<String> mergeWarStates(WarData data) {
        if (data == null) {
            return Collections.emptySet();
        }
        Set<String> states = new HashSet<>(data.getAttackerSide());
        states.addAll(data.getDefenderSide());
        return states;
    }

    private Set<UUID> collectOnlineMembers(String stateName) {
        if (stateName == null) {
            return Collections.emptySet();
        }
        StateManager.StateData data = plugin.state().getState(stateName);
        if (data == null) {
            return Collections.emptySet();
        }
        Set<UUID> ids = new HashSet<>(data.members);
        if (data.captain != null) {
            ids.add(data.captain);
        }
        ids.removeIf(id -> Bukkit.getPlayer(id) == null);
        return ids;
    }

    private void sendStateMessage(String stateName, String path, Map<String, String> vars) {
        for (UUID id : collectOnlineMembers(stateName)) {
            Player online = Bukkit.getPlayer(id);
            if (online != null) {
                plugin.lang().send(online, path, vars);
            }
        }
    }

    private void sendStateActionBar(String stateName, String path, Map<String, String> vars, SoundSettings sound) {
        for (UUID id : collectOnlineMembers(stateName)) {
            Player online = Bukkit.getPlayer(id);
            if (online != null) {
                plugin.lang().sendActionBar(online, path, vars);
                if (sound != null) {
                    sound.play(online);
                }
            }
        }
    }

    private void sendWarSideMessage(WarData data, String path, Map<String, String> vars) {
        for (String state : mergeWarStates(data)) {
            sendStateMessage(state, path, vars);
        }
    }

    private void sendWarSideActionBar(WarData data, String path, Map<String, String> vars, SoundSettings sound) {
        for (String state : mergeWarStates(data)) {
            sendStateActionBar(state, path, vars, sound);
        }
    }

    private void playGlobalSound(SoundSettings sound) {
        if (sound == null) {
            return;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            sound.play(online);
        }
    }

    private long clampToMillis(long seconds) {
        if (seconds <= 0) {
            return 0L;
        }
        return seconds * 1000L;
    }

    private long getWarCooldownMs() {
        long declareSeconds = plugin.config().getLong("war.cooldowns.declare-seconds", -1L);
        if (declareSeconds < 0L) {
            long minutes = plugin.config().getLong("war.cooldown-minutes", 60L);
            declareSeconds = Math.max(0L, minutes * 60L);
        }
        return clampToMillis(declareSeconds);
    }

    private long getCondemnCooldownMs() {
        long seconds = plugin.config().getLong("war.cooldowns.condemn-seconds", 600L);
        return clampToMillis(seconds);
    }

    private long getMoveCooldownMs() {
        long seconds = plugin.config().getLong("war.cooldowns.movecapital-seconds", 1800L);
        return clampToMillis(seconds);
    }

    private int getMinimumWarMembers() {
        return Math.max(0, plugin.config().getInt("war.requirements.minimum-members", 0));
    }

    private int getMinimumWarSectors() {
        return Math.max(0, plugin.config().getInt("war.requirements.minimum-sectors", 0));
    }

    public int getRequiredMembersForWar() {
        return getMinimumWarMembers();
    }

    public int getRequiredSectorsForWar() {
        return getMinimumWarSectors();
    }

    private long getCondemnDelayMs() {
        long seconds = plugin.config().getLong("war.condemn-delay-seconds", 604800L);
        return clampToMillis(seconds);
    }

    private void reloadUpgradeSettings() {
        this.baseMaxHp = plugin.config().getDouble("camp.max-hp", 100.0);
        this.baseMaxFuel = Math.max(0, plugin.config().getInt("camp.fuel.max", 25));
        this.baseHealRate = Math.max(0.0, plugin.config().getDouble("camp.heal-rate", 0.5));
        this.baseFatigueAmplifier = Math.max(0, plugin.getConfig().getInt("protection.mining-fatigue.amplifier", 1));

        upgradeTrees.clear();
        for (CampUpgradeType type : CampUpgradeType.values()) {
            UpgradeTree tree = loadUpgradeTree(type);
            if (tree != null) {
                upgradeTrees.put(type, tree);
            }
        }
    }

    private void loadProductionSettings() {
        this.productionEnabled = plugin.getConfig().getBoolean("camp.production.enabled", false);
        long seconds = Math.max(0L, plugin.getConfig().getLong("camp.production.interval-seconds", 300L));
        this.baseProductionIntervalMs = seconds * 1000L;
        this.productionMoney = Math.max(0.0, plugin.getConfig().getDouble("camp.production.money", 0.0));
        this.baseStoredMoneyCap = Math.max(0.0, plugin.getConfig().getDouble("camp.production.storage.money", 0.0));
        this.baseStoredItemCap = Math.max(0, plugin.getConfig().getInt("camp.production.storage.items", 0));
        this.productionItems = plugin.state().loadItemRequirements("camp.production.items");
    }

    private UpgradeTree loadUpgradeTree(CampUpgradeType type) {
        String basePath = "camp.upgrades." + type.configKey();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(basePath);
        if (section == null) {
            return new UpgradeTree(type, false, Map.of(), plugin.lang().messageOrDefault("state.upgrade-name-" + type.configKey(), type.configKey().toUpperCase(Locale.ROOT)));
        }
        boolean enabled = section.getBoolean("enabled", true);
        String display = section.getString("display", plugin.lang().messageOrDefault("state.upgrade-name-" + type.configKey(), type.configKey().toUpperCase(Locale.ROOT)));
        Map<Integer, UpgradeTier> tiers = new LinkedHashMap<>();
        ConfigurationSection levelSection = section.getConfigurationSection("levels");
        if (levelSection != null) {
            for (String key : levelSection.getKeys(false)) {
                int level;
                try {
                    level = Integer.parseInt(key);
                } catch (NumberFormatException ex) {
                    continue;
                }
                ConfigurationSection entry = levelSection.getConfigurationSection(key);
                if (entry == null) {
                    continue;
                }
                double cost = entry.getDouble("cost", 0.0);
                String costDisplay = entry.getString("cost-display", null);
                Map<StateManager.ItemDescriptor, Integer> materials = plugin.state().loadItemRequirements(basePath + ".levels." + key + ".items");
                String itemsDisplay = entry.getString("items-display", null);
                Double maxHp = (entry.isDouble("max-hp") || entry.isInt("max-hp")) ? entry.getDouble("max-hp") : null;
                Integer maxFuel = entry.isInt("max-fuel") ? entry.getInt("max-fuel") : null;
                Double healRate = (entry.isDouble("heal-rate") || entry.isInt("heal-rate")) ? entry.getDouble("heal-rate") : null;
                Integer fatigue = entry.isInt("fatigue-amplifier") ? entry.getInt("fatigue-amplifier") : null;
                Double storageMoney = (entry.isDouble("storage-money") || entry.isInt("storage-money")) ? entry.getDouble("storage-money") : null;
                Integer storageItems = entry.isInt("storage-items") ? entry.getInt("storage-items") : null;
                Long productionInterval = entry.isLong("production-interval-seconds") || entry.isInt("production-interval-seconds")
                        ? entry.getLong("production-interval-seconds") : null;
                Double boundaryRadius = (entry.isDouble("boundary-radius") || entry.isInt("boundary-radius"))
                        ? entry.getDouble("boundary-radius") : null;
                tiers.put(level, new UpgradeTier(level, cost, costDisplay, itemsDisplay, materials, maxHp, maxFuel, healRate, fatigue,
                        storageMoney, storageItems, productionInterval, boundaryRadius));
            }
        }
        return new UpgradeTree(type, enabled, tiers, display);
    }

    private void loadModuleSettings() {
        moduleDefinitions.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("camp.modules");
        if (section == null) {
            return;
        }
        for (String rawKey : section.getKeys(false)) {
            String key = normalizeModuleKey(rawKey);
            ConfigurationSection entry = section.getConfigurationSection(rawKey);
            if (entry == null) {
                continue;
            }
            boolean enabled = entry.getBoolean("enabled", true);
            String display = entry.getString("display", rawKey);
            double cost = Math.max(0.0, entry.getDouble("cost", 0.0));
            String costDisplay = entry.getString("cost-display", null);
            Map<StateManager.ItemDescriptor, Integer> materials = plugin.state().loadItemRequirements("camp.modules." + rawKey + ".items");
            String itemsDisplay = entry.getString("items-display", plugin.state().describeMaterials(materials));
            ConfigurationSection effectSection = entry.getConfigurationSection("effect");
            String type = effectSection != null ? effectSection.getString("type", "") : "";
            double value = effectSection != null ? effectSection.getDouble("value", 0.0)
                    : effectSection != null ? effectSection.getDouble("temperature", 0.0) : 0.0;
            ModuleEffect effect = new ModuleEffect(type, value);
            moduleDefinitions.put(key, new ModuleDefinition(key, enabled, display, cost, costDisplay, itemsDisplay, materials, effect));
        }
    }

    private void reapplyUpgrades() {
        for (Camp camp : camps.values()) {
            applyCampUpgrades(camp);
        }
    }

    private long getSurrenderTimeoutMs() {
        long seconds = plugin.getConfig().getLong("requests.surrender-timeout-seconds", 120L);
        return clampToMillis(seconds);
    }

    private long getMaintenanceIntervalMs(boolean capital) {
        String basePath = capital ? "camp.maintenance.capital.interval-seconds" : "camp.maintenance.regular.interval-seconds";
        long seconds = plugin.config().getLong(basePath, -1L);
        if (seconds <= 0L) {
            seconds = plugin.config().getLong("camp.maintenance.interval-seconds", 86400L);
        }
        if (seconds <= 0L) {
            return -1L;
        }
        return seconds * 1000L;
    }

    private long getMaintenanceWarningMs() {
        long seconds = plugin.config().getLong("camp.maintenance.warning-seconds", 3600L);
        if (seconds <= 0L) {
            return 0L;
        }
        return seconds * 1000L;
    }

    private long getMaintenanceDecayIntervalMs() {
        long seconds = plugin.config().getLong("camp.maintenance.decay-interval-seconds", 600L);
        if (seconds <= 0L) {
            return 1000L;
        }
        return seconds * 1000L;
    }

    private double getMaintenanceDecayAmount() {
        double amount = plugin.config().getDouble("camp.maintenance.decay-amount", 0.0);
        return Math.max(0.0, amount);
    }

    private long getMaintenanceCheckIntervalTicks() {
        long ticks = plugin.config().getLong("camp.fuel.check-interval-ticks", 600L);
        if (ticks < 20L) {
            ticks = 20L;
        }
        return ticks;
    }

    private int getMaxFuel() {
        return baseMaxFuel;
    }

    public long getFuelIntervalMillis() {
        return getFuelIntervalMs();
    }

    private long getFuelIntervalMs() {
        long seconds = plugin.getConfig().getLong("camp.fuel.interval-seconds", 3600L);
        return seconds <= 0 ? -1L : seconds * 1000L;
    }

    private int getFuelDrainAmount() {
        return Math.max(0, plugin.getConfig().getInt("camp.fuel.drain-amount", 1));
    }

    private long remaining(Map<String, Long> map, String key, long cooldownMs) {
        if (key == null || cooldownMs <= 0L) {
            return 0L;
        }
        Long last = map.get(key);
        if (last == null) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        long diff = now - last;
        long remaining = cooldownMs - diff;
        return Math.max(0L, remaining);
    }

    private <T> void moveKey(Map<String, T> map, String oldKey, String newKey) {
        if (oldKey == null || newKey == null || oldKey.equals(newKey)) {
            return;
        }
        T value = map.remove(oldKey);
        if (value != null) {
            map.put(newKey, value);
        }
    }

    public long getWarCooldownRemaining(String state) {
        return remaining(lastWarCommand, normalizeState(state), getWarCooldownMs());
    }

    public long getCondemnCooldownRemaining(String state) {
        return remaining(lastCondemnCommand, normalizeState(state), getCondemnCooldownMs());
    }

    public long getMoveCooldownRemaining(String state) {
        return remaining(lastMoveCommand, normalizeState(state), getMoveCooldownMs());
    }

    public String formatDuration(long millis) {
        if (millis <= 0L) {
            return "0s";
        }
        long totalSeconds = (millis + 999L) / 1000L;
        long days = totalSeconds / 86400L;
        totalSeconds %= 86400L;
        long hours = totalSeconds / 3600L;
        totalSeconds %= 3600L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        StringBuilder builder = new StringBuilder();
        if (days > 0L) {
            builder.append(days).append('d');
        }
        if (hours > 0L) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(hours).append('h');
        }
        if (minutes > 0L) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(minutes).append('m');
        }
        if (seconds > 0L || builder.length() == 0) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(seconds).append('s');
        }
        return builder.toString();
    }

    private void startRaiderTask(String warKey, WarData data) {
        if (!plugin.getConfig().getBoolean("war.raiders.enabled", false)) {
            return;
        }

        long intervalSeconds = plugin.getConfig().getLong("war.raiders.interval-seconds", 600L);
        if (intervalSeconds <= 0L) {
            return;
        }

        long intervalTicks = Math.max(20L, intervalSeconds * 20L);
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                spawnRaiderWave(data);
            }
        }.runTaskTimer(plugin, intervalTicks, intervalTicks);
        raiderTasks.put(warKey, task);
    }

    private void cancelRaiderTask(String warKey) {
        BukkitTask task = raiderTasks.remove(warKey);
        if (task != null) {
            task.cancel();
        }
    }

    private void spawnRaiderWave(WarData data) {
        if (data == null) {
            return;
        }

        List<StateManager.CampSectorInfo> sectors = collectWarSectors(data);
        if (sectors.isEmpty()) {
            return;
        }

        int count = Math.max(1, plugin.getConfig().getInt("war.raiders.count", 3));
        double radius = Math.max(0.0, plugin.getConfig().getDouble("war.raiders.radius", 6.0));
        double heightOffset = plugin.getConfig().getDouble("war.raiders.height-offset", 0.0);
        Random random = new Random();
        int spawned = 0;

        for (int i = 0; i < count; i++) {
            StateManager.CampSectorInfo info = sectors.get(random.nextInt(sectors.size()));
            Location base = plugin.state().getSectorLocation(info.stateName(), info.sectorName());
            if (base == null) {
                continue;
            }

            double angle = random.nextDouble() * Math.PI * 2;
            double distance = radius * random.nextDouble();
            Location spawnLoc = base.clone().add(Math.cos(angle) * distance, heightOffset, Math.sin(angle) * distance);
            World world = spawnLoc.getWorld();
            if (world == null) {
                continue;
            }

            if (world.spawn(spawnLoc, Pillager.class) != null) {
                spawned++;
            }
        }

        if (spawned > 0) {
            sendWarSideMessage(data, "war.raiders-spawned", Map.of(
                    "count", String.valueOf(spawned),
                    "attacker", String.join(", ", data.getAttackerSide()),
                    "defender", String.join(", ", data.getDefenderSide())
            ));
        }
    }

    private List<StateManager.CampSectorInfo> collectWarSectors(WarData data) {
        List<StateManager.CampSectorInfo> sectors = new ArrayList<>();
        for (String state : data.getAttackerSide()) {
            for (String sector : plugin.state().getSectorNames(state)) {
                sectors.add(new StateManager.CampSectorInfo(state, sector));
            }
        }
        for (String state : data.getDefenderSide()) {
            for (String sector : plugin.state().getSectorNames(state)) {
                sectors.add(new StateManager.CampSectorInfo(state, sector));
            }
        }
        return sectors;
    }

    public String getCondemnationTarget(String attacker) {
        CondemnationData data = condemnations.get(normalizeState(attacker));
        return data == null ? null : data.getTarget();
    }

    public long getCondemnationRemaining(String attacker) {
        CondemnationData data = condemnations.get(normalizeState(attacker));
        if (data == null) {
            return -1L;
        }
        long elapsed = System.currentTimeMillis() - data.getStartTime();
        long remaining = getCondemnDelayMs() - elapsed;
        return Math.max(0L, remaining);
    }

    // 开战
    public WarStartResult startWar(String attacker, String defender, boolean bypassCooldown) {
        if (attacker == null || defender == null) {
            return WarStartResult.INVALID_STATE;
        }

        if (attacker.equalsIgnoreCase(defender)) {
            return WarStartResult.SAME_SIDE;
        }

        StateManager.StateData attackerData = plugin.state().findState(attacker);
        StateManager.StateData defenderData = plugin.state().findState(defender);
        if (attackerData == null || defenderData == null) {
            return WarStartResult.INVALID_STATE;
        }

        String attackerName = attackerData.name;
        String defenderName = defenderData.name;
        String k = warKey(attackerName, defenderName);
        if (wars.containsKey(k)) {
            return WarStartResult.ALREADY_AT_WAR;
        }

        if (!bypassCooldown && findWar(attackerName).isPresent()) {
            return WarStartResult.ATTACKER_BUSY;
        }

        String attackerKey = normalizeState(attackerName);

        if (!bypassCooldown && pendingCivilWars.containsKey(attackerKey)) {
            return WarStartResult.CIVIL_WAR_PENDING;
        }

        if (!bypassCooldown) {
            int minMembers = getMinimumWarMembers();
            int minSectors = getMinimumWarSectors();
            if ((minMembers > 0 && attackerData.members.size() < minMembers)
                    || (minSectors > 0 && attackerData.sectors.size() < minSectors)) {
                return WarStartResult.REQUIREMENTS;
            }

            CondemnationData condemnation = condemnations.get(attackerKey);
            if (condemnation == null) {
                return WarStartResult.NO_CONDEMNATION;
            }
            if (!condemnation.getTarget().equalsIgnoreCase(defenderName)) {
                return WarStartResult.CONDEMNATION_WRONG_TARGET;
            }
            if (getCondemnationRemaining(attackerName) > 0L) {
                return WarStartResult.CONDEMNATION_PENDING;
            }
            if (getWarCooldownRemaining(attackerName) > 0L) {
                return WarStartResult.COOLDOWN;
            }
        }

        long now = System.currentTimeMillis();
        WarData data = new WarData(attackerName, defenderName, now);
        wars.put(k, data);
        if (!bypassCooldown) {
            lastWarCommand.put(attackerKey, now);
        }
        condemnations.remove(attackerKey);
        pendingCivilWars.remove(attackerKey);

        plugin.lang().broadcast("war.declare-success", Map.of("attacker", attackerName, "defender", defenderName));
        playGlobalSound(plugin.warStartSound());
        revealCampLocations(attackerData, defenderData);
        revealCampLocations(defenderData, attackerData);

        if (plugin.getGraveXListener() != null) {
            plugin.getGraveXListener().disableForStates(attackerName, defenderName);
        }

        startRaiderTask(k, data);
        Bukkit.getScheduler().runTask(plugin, () -> resolvePreBrokenCapitals(attackerName, defenderName));
        return WarStartResult.SUCCESS;
    }

    public void queueCivilWar(String rebel, String origin, UUID captain) {
        if (rebel == null || origin == null || captain == null) {
            return;
        }
        String key = normalizeState(rebel);
        pendingCivilWars.put(key, new PendingCivilWar(rebel, origin, captain, System.currentTimeMillis()));
        condemnations.put(key, new CondemnationData(origin, System.currentTimeMillis()));
        lastCondemnCommand.put(key, System.currentTimeMillis());
    }

    public boolean isCivilWarPending(String state) {
        if (state == null) {
            return false;
        }
        return pendingCivilWars.containsKey(normalizeState(state));
    }

    public String getPendingCivilWarTarget(String state) {
        if (state == null) {
            return null;
        }
        PendingCivilWar data = pendingCivilWars.get(normalizeState(state));
        return data == null ? null : data.getOrigin();
    }

    public void handleCampPlacement(String state, String sector) {
        if (state == null) {
            return;
        }
        String key = normalizeState(state);
        PendingCivilWar pending = pendingCivilWars.remove(key);
        if (pending == null) {
            return;
        }
        condemnations.remove(key);
        WarStartResult start = startWar(pending.getRebel(), pending.getOrigin(), true);
        if (start == WarStartResult.SUCCESS) {
            notifyCivilWarStart(pending.getRebel(), pending.getOrigin());
        }
    }

    public void handlePlayerDeath(Player victim, Player killer) {
        if (victim == null) {
            return;
        }
        String state = plugin.state().getStateName(victim);
        if (state == null) {
            return;
        }
        PendingCivilWar pending = pendingCivilWars.get(normalizeState(state));
        if (pending == null || !pending.getCaptain().equals(victim.getUniqueId())) {
            return;
        }
        if (killer == null) {
            return;
        }
        String killerState = plugin.state().getStateName(killer);
        if (killerState == null || !killerState.equalsIgnoreCase(pending.getOrigin())) {
            return;
        }
        resolvePendingCivilWarFailure(pending, killer);
    }

    private void resolvePendingCivilWarFailure(PendingCivilWar pending, Player killer) {
        String key = normalizeState(pending.getRebel());
        pendingCivilWars.remove(key);
        condemnations.remove(key);

        Map<String, String> vars = new HashMap<>();
        vars.put("state", pending.getRebel());
        vars.put("origin", pending.getOrigin());
        vars.put("killer", killer != null ? killer.getName() : plugin.lang().messageOrDefault("bank.log-unknown", "未知"));

        plugin.state().disbandState(pending.getRebel(), "war.civilwar-failed-rebel", vars);

        StateManager.StateData origin = plugin.state().getState(pending.getOrigin());
        if (origin != null) {
            for (UUID member : new HashSet<>(origin.members)) {
                Player online = Bukkit.getPlayer(member);
                if (online != null) {
                    plugin.lang().send(online, "war.civilwar-failed-origin", vars);
                }
            }
        }
    }

    private void notifyCivilWarStart(String rebel, String origin) {
        Map<String, String> rebelVars = Map.of("enemy", origin);
        Map<String, String> originVars = Map.of("enemy", rebel);

        StateManager.StateData rebelData = plugin.state().getState(rebel);
        if (rebelData != null) {
            for (UUID member : new HashSet<>(rebelData.members)) {
                Player online = Bukkit.getPlayer(member);
                if (online != null) {
                    plugin.lang().send(online, "war.civilwar-war-start", rebelVars);
                }
            }
        }

        StateManager.StateData originData = plugin.state().getState(origin);
        if (originData != null) {
            for (UUID member : new HashSet<>(originData.members)) {
                Player online = Bukkit.getPlayer(member);
                if (online != null) {
                    plugin.lang().send(online, "war.civilwar-war-start", originVars);
                }
            }
        }
    }

    public CondemnationResult condemnState(String attacker, String target) {
        if (attacker == null || target == null) {
            return CondemnationResult.invalid();
        }

        StateManager.StateData attackerData = plugin.state().findState(attacker);
        StateManager.StateData targetData = plugin.state().findState(target);
        if (attackerData == null || targetData == null) {
            return CondemnationResult.invalid();
        }

        String attackerName = attackerData.name;
        String targetName = targetData.name;

        if (attackerName.equalsIgnoreCase(targetName)) {
            return CondemnationResult.sameSide();
        }

        if (findWar(attackerName).isPresent()) {
            return CondemnationResult.alreadyAtWar();
        }

        if (isCivilWarPending(attackerName)) {
            return CondemnationResult.civilWarPending(targetName);
        }

        long cooldown = getCondemnCooldownRemaining(attackerName);
        if (cooldown > 0L) {
            return CondemnationResult.cooldown(targetName, cooldown);
        }

        String attackerKey = normalizeState(attackerName);
        CondemnationData existing = condemnations.get(attackerKey);
        if (existing != null) {
            if (existing.getTarget().equalsIgnoreCase(targetName)) {
                return CondemnationResult.alreadyCondemned(targetName);
            }
            return CondemnationResult.alreadyCondemnedOther(targetName, existing.getTarget());
        }

        long now = System.currentTimeMillis();
        condemnations.put(attackerKey, new CondemnationData(targetName, now));
        lastCondemnCommand.put(attackerKey, now);
        return CondemnationResult.success(targetName, getCondemnationRemaining(attackerName));
    }

    private void resolvePreBrokenCapitals(String attacker, String defender) {
        Camp attackerCapital = getCapitalCamp(attacker);
        Camp defenderCapital = getCapitalCamp(defender);

        if (attackerCapital != null && attackerCapital.isBroken()) {
            Map<String, String> vars = Map.of(
                    "state", attacker,
                    "enemy", defender
            );
            sendStateMessage(attacker, "war.capital-prebroken", vars);
            sendStateMessage(defender, "war.capital-prebroken", vars);
            endWar(attacker, defender, defender);
            return;
        }

        if (defenderCapital != null && defenderCapital.isBroken()) {
            Map<String, String> vars = Map.of(
                    "state", defender,
                    "enemy", attacker
            );
            sendStateMessage(attacker, "war.capital-prebroken", vars);
            sendStateMessage(defender, "war.capital-prebroken", vars);
            endWar(attacker, defender, attacker);
        }
    }

    public void endWar(String a, String b, String winner) {
        String k = warKey(a, b);
        WarData data = wars.remove(k);
        if (data == null) {
            return;
        }

        cancelRaiderTask(k);
        surrenderRequests.remove(k);

        clearCapitalHoldForState(a);
        clearCapitalHoldForState(b);

        plugin.lang().broadcast("war.end-win", Map.of("winner", winner, "loser", winner.equals(a) ? b : a));
        playGlobalSound(plugin.warEndSound());

        transferBrokenSectors(data, winner);
        distributeRewards(data, winner);

        if (plugin.getGraveXListener() != null) {
            plugin.getGraveXListener().restoreIfNoWars();
        }
    }

    private void transferBrokenSectors(WarData data, String winner) {
        if (data == null || winner == null) {
            return;
        }

        String loser = data.getAttacker().equalsIgnoreCase(winner) ? data.getDefender() : data.getAttacker();
        if (loser == null) {
            return;
        }

        StateManager.StateData source = plugin.state().findState(loser);
        StateManager.StateData target = plugin.state().findState(winner);
        if (source == null || target == null) {
            return;
        }

        List<Camp> captured = new ArrayList<>();
        for (Camp camp : new ArrayList<>(camps.values())) {
            if (!camp.getStateName().equalsIgnoreCase(loser)) {
                continue;
            }
            if (plugin.state().isCapitalSector(loser, camp.getSectorName())) {
                continue;
            }
            if (!camp.isBroken()) {
                continue;
            }
            captured.add(camp);
        }

        List<String> transferred = new ArrayList<>();

        for (Camp camp : captured) {
            String oldSector = camp.getSectorName();
            String newSector = plugin.state().transferSector(loser, winner, oldSector);
            if (newSector == null) {
                continue;
            }

            camps.remove(campKey(loser, oldSector));
            camp.setStateName(winner);
            camp.setSectorName(newSector);
            camp.restoreFull();
            initializeMaintenance(camp, plugin.state().isCapitalSector(winner, newSector));
            camps.put(campKey(winner, newSector), camp);

            if (plugin.protection() != null) {
                plugin.protection().clearCampEffects(loser, oldSector);
            }
            if (plugin.holograms() != null) {
                plugin.holograms().removeCamp(loser, oldSector);
            }
            if (plugin.holograms() != null) {
                plugin.holograms().update(camp);
            }
            updateDynmap(camp);
            transferred.add(newSector);
        }

        if (!transferred.isEmpty()) {
            Map<String, String> vars = Map.of(
                    "winner", winner,
                    "loser", loser,
                    "sectors", String.join(", ", transferred)
            );
            sendStateMessage(winner, "war.sectors-captured", vars);
            sendStateMessage(loser, "war.sectors-captured", vars);
            markDirty();
        }
    }

    private void distributeRewards(WarData data, String winner) {
        if (data == null || winner == null) {
            return;
        }

        boolean attackerWon = data.getAttacker().equalsIgnoreCase(winner);
        Set<String> winners = new LinkedHashSet<>(attackerWon ? data.getAttackerSide() : data.getDefenderSide());
        Set<String> losers = new LinkedHashSet<>(attackerWon ? data.getDefenderSide() : data.getAttackerSide());

        if (winners.isEmpty() || losers.isEmpty()) {
            return;
        }

        double serverReward = Math.max(0.0, plugin.getConfig().getDouble("war.rewards.server-money", 0.0));
        double bankPercent = Math.max(0.0, plugin.getConfig().getDouble("war.rewards.bank-percent", 0.0));

        double totalBankLooted = 0.0;
        if (bankPercent > 0.0) {
            for (String loser : losers) {
                double available = plugin.state().getBankBalance(loser);
                double portion = Math.max(0.0, available * bankPercent);
                if (portion <= 0) {
                    continue;
                }
                StateManager.BankActionResponse response = plugin.state().withdrawFromState(
                        loser,
                        portion,
                        null,
                        StateManager.BankTransactionType.EXPENSE
                );
                totalBankLooted += response.getAmount();
            }
        }

        double shareCount = winners.size();
        double perWinnerServer = shareCount <= 0 ? 0.0 : serverReward / shareCount;
        double perWinnerLoot = shareCount <= 0 ? 0.0 : totalBankLooted / shareCount;

        if (perWinnerServer > 0.0 || perWinnerLoot > 0.0) {
            for (String winnerState : winners) {
                double total = perWinnerServer + perWinnerLoot;
                if (total <= 0) {
                    continue;
                }
                plugin.state().depositToState(
                        winnerState,
                        total,
                        null,
                        StateManager.BankTransactionType.DEPOSIT
                );
            }
        }

        if (serverReward > 0.0 || totalBankLooted > 0.0) {
            Map<String, String> vars = Map.of(
                    "winners", String.join(", ", winners),
                    "losers", String.join(", ", losers),
                    "server", plugin.state().formatMoney(serverReward),
                    "loot", plugin.state().formatMoney(totalBankLooted),
                    "share", plugin.state().formatMoney(perWinnerServer + perWinnerLoot)
            );
            Set<String> notifyStates = new HashSet<>(winners);
            notifyStates.addAll(losers);
            for (String state : notifyStates) {
                sendStateMessage(state, "war.rewards-distributed", vars);
            }
        }
    }

    public boolean areStatesAtWar(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return wars.containsKey(warKey(a, b));
    }

    public boolean isStateAtWar(String state) {
        if (state == null) {
            return false;
        }
        for (WarData data : wars.values()) {
            if (data.getAttacker().equalsIgnoreCase(state) || data.getDefender().equalsIgnoreCase(state)) {
                return true;
            }
        }
        return false;
    }

    public WarData getWar(String a, String b) {
        return wars.get(warKey(a, b));
    }

    public WarData adminStopWar(String a, String b) {
        String key = findWarKeyBetween(a, b);
        if (key == null) {
            return null;
        }

        WarData data = wars.remove(key);
        if (data == null) {
            return null;
        }

        cancelRaiderTask(key);
        surrenderRequests.remove(key);

        for (String state : data.getAttackerSide()) {
            clearCapitalHoldForState(state);
        }
        for (String state : data.getDefenderSide()) {
            clearCapitalHoldForState(state);
        }

        if (plugin.getGraveXListener() != null) {
            plugin.getGraveXListener().restoreIfNoWars();
        }
        return data;
    }

    private boolean warContainsState(WarData war, String state) {
        if (war == null || state == null) {
            return false;
        }
        for (String attacker : war.getAttackerSide()) {
            if (attacker.equalsIgnoreCase(state)) {
                return true;
            }
        }
        for (String defender : war.getDefenderSide()) {
            if (defender.equalsIgnoreCase(state)) {
                return true;
            }
        }
        return false;
    }

    private String findWarKeyBetween(String a, String b) {
        if (a == null || b == null) {
            return null;
        }
        for (Map.Entry<String, WarData> entry : wars.entrySet()) {
            WarData data = entry.getValue();
            if (warContainsState(data, a) && warContainsState(data, b)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private Optional<WarData> findWar(String state) {
        return wars.values().stream().filter(w -> w.involves(state)).findFirst();
    }

    public Optional<WarData> getWarForState(String state) {
        return findWar(state);
    }

    public Camp getCamp(String state, String sector) {
        if (state == null || sector == null) {
            return null;
        }
        return camps.get(campKey(state, sector));
    }

    public java.util.Collection<Camp> getCamps() {
        return java.util.Collections.unmodifiableCollection(camps.values());
    }

    public boolean capitalHasFuel(String state) {
        String capital = plugin.state().getCapital(state);
        if (capital == null) {
            return false;
        }
        Camp camp = getCamp(state, capital);
        return camp != null && camp.getFuel() > 0;
    }

    private void initializeMaintenance(Camp camp, boolean capital) {
        long now = System.currentTimeMillis();
        if (camp.getLastMaintainedAt() <= 0L) {
            camp.setLastMaintainedAt(now);
        }
        camp.setMaxFuel(Math.max(camp.getMaxFuel(), baseMaxFuel));
        if (camp.getFuel() <= 0) {
            camp.setFuel(camp.getMaxFuel());
        }
        camp.setLastFuelCheckAt(now);
        long interval = getMaintenanceIntervalMs(capital);
        if (interval > 0L) {
            camp.setNextMaintenanceAt(camp.getLastMaintainedAt() + interval);
        } else {
            camp.setNextMaintenanceAt(-1L);
        }
        camp.setMaintenanceWarningIssued(false);
        camp.setMaintenanceOverdueNotified(false);
        camp.setLastMaintenanceDecayAt(0L);
    }

    public Camp registerCamp(String state, String sector) {
        if (state == null || sector == null) {
            return null;
        }
        String key = campKey(state, sector);
        double maxHp = baseMaxHp;
        Camp existing = camps.get(key);
        if (existing != null) {
            existing.setMaxHp(maxHp);
            existing.restoreFull();
            existing.setStateName(state);
            existing.setSectorName(sector);
            applyCampUpgrades(existing);
            initializeMaintenance(existing, plugin.state().isCapitalSector(state, sector));
            if (plugin.holograms() != null) {
                plugin.holograms().update(existing);
            }
            updateDynmap(existing);
            return existing;
        }
        Camp created = new Camp(key, state, sector, maxHp);
        created.setHealRate(baseHealRate);
        created.setFatigueAmplifier(baseFatigueAmplifier);
        applyCampUpgrades(created);
        initializeMaintenance(created, plugin.state().isCapitalSector(state, sector));
        camps.put(key, created);
        if (plugin.holograms() != null) {
            plugin.holograms().update(created);
        }
        updateDynmap(created);
        markDirty();
        return created;
    }

    public Camp removeCamp(String state, String sector) {
        if (state == null || sector == null) {
            return null;
        }
        String key = campKey(state, sector);
        Camp camp = camps.remove(key);
        if (camp == null) {
            return null;
        }
        cancelHold(key);
        if (plugin.protection() != null) {
            plugin.protection().clearCampEffects(camp.getStateName(), camp.getSectorName());
        }
        if (plugin.holograms() != null) {
            plugin.holograms().removeCamp(camp.getStateName(), camp.getSectorName());
        }
        markDirty();
        return camp;
    }

    public void applyCampUpgrades(Camp camp) {
        if (camp == null) {
            return;
        }
        double maxHp = baseMaxHp;
        int maxFuel = baseMaxFuel;
        double healRate = baseHealRate;
        int fatigue = baseFatigueAmplifier;
        double storageMoney = baseStoredMoneyCap;
        int storageItems = baseStoredItemCap;
        long productionInterval = productionEnabled ? baseProductionIntervalMs : 0L;
        double baseRadius = Math.max(1.0, plugin.getConfig().getDouble("camp.radius", 16.0));
        double boundaryBonus = 0.0;

        UpgradeTier hpTier = getTier(CampUpgradeType.HP, camp.getHpLevel());
        if (hpTier != null && hpTier.maxHp() != null) {
            maxHp = hpTier.maxHp();
        }
        UpgradeTier fuelTier = getTier(CampUpgradeType.FUEL, camp.getFuelLevel());
        if (fuelTier != null && fuelTier.maxFuel() != null) {
            maxFuel = fuelTier.maxFuel();
        }
        UpgradeTier healTier = getTier(CampUpgradeType.HEAL, camp.getHealLevel());
        if (healTier != null && healTier.healRate() != null) {
            healRate = healTier.healRate();
        }
        UpgradeTier fatigueTier = getTier(CampUpgradeType.FATIGUE, camp.getFatigueLevel());
        if (fatigueTier != null && fatigueTier.fatigueAmplifier() != null) {
            fatigue = fatigueTier.fatigueAmplifier();
        }
        UpgradeTier storageTier = getTier(CampUpgradeType.STORAGE, camp.getStorageLevel());
        if (storageTier != null) {
            if (storageTier.storedMoneyCap() != null) {
                storageMoney = storageTier.storedMoneyCap();
            }
            if (storageTier.storedItemCap() != null) {
                storageItems = storageTier.storedItemCap();
            }
        }
        UpgradeTier efficiencyTier = getTier(CampUpgradeType.EFFICIENCY, camp.getEfficiencyLevel());
        UpgradeTier boundaryTier = getTier(CampUpgradeType.BOUNDARY, camp.getBoundaryLevel());
        if (efficiencyTier != null && efficiencyTier.productionIntervalSeconds() != null) {
            productionInterval = efficiencyTier.productionIntervalSeconds() * 1000L;
        }
        if (boundaryTier != null && boundaryTier.boundaryRadiusBonus() != null) {
            boundaryBonus = boundaryTier.boundaryRadiusBonus();
        }

        camp.setMaxHp(maxHp);
        camp.setMaxFuel(maxFuel);
        camp.setHealRate(healRate);
        camp.setFatigueAmplifier(fatigue);
        camp.setMaxStoredMoney(storageMoney);
        camp.setMaxStoredItems(storageItems);
        camp.setProductionIntervalMs(productionInterval);
        plugin.state().recalculateCampBoundary(camp, baseRadius, boundaryBonus);
    }

    public void applyModuleEffects(Player player, StateManager.CampSectorInfo info) {
        // No-op placeholder for future player-applied module effects.
    }

    public void clearModuleEffects(Player player) {
        // No-op placeholder for future player-applied module effects.
    }

    private void applyModuleEffect(Player player, ModuleDefinition definition) {
        // No-op placeholder for future player-applied module effects.
    }

    public void applySectorTransfer(String fromState, String fromSector, String toState, String toSector) {
        if (fromState == null || fromSector == null || toState == null || toSector == null) {
            return;
        }

        String oldKey = campKey(fromState, fromSector);
        Camp camp = camps.remove(oldKey);
        cancelHold(oldKey);
        if (plugin.holograms() != null) {
            plugin.holograms().removeCamp(fromState, fromSector);
        }

        if (camp != null) {
            camp.setStateName(toState);
            camp.setSectorName(toSector);
            initializeMaintenance(camp, plugin.state().isCapitalSector(toState, toSector));
            camps.put(campKey(toState, toSector), camp);
            if (plugin.protection() != null) {
                plugin.protection().clearCampEffects(fromState, fromSector);
            }
            if (plugin.holograms() != null) {
                plugin.holograms().update(camp);
            }
            updateDynmap(camp);
        } else {
        }
        markDirty();
    }

    public void restoreCamp(Camp camp) {
        if (camp == null) {
            return;
        }
        camps.put(campKey(camp.getStateName(), camp.getSectorName()), camp);
        if (plugin.holograms() != null) {
            plugin.holograms().update(camp);
        }
        updateDynmap(camp);
        markDirty();
    }

    public boolean isCampOperational(String state, String sector) {
        if (state == null || sector == null) {
            return false;
        }
        Camp camp = camps.get(campKey(state, sector));
        return camp != null && !camp.isBroken() && camp.getFuel() > 0;
    }

    public long estimateFuelDuration(Camp camp) {
        if (camp == null) {
            return 0L;
        }
        long interval = getFuelIntervalMs();
        if (interval <= 0) {
            return 0L;
        }
        return computeFuelRemainingMillis(camp, interval);
    }

    public CampMaintenanceInfo getMaintenanceInfo(String state, String sector) {
        if (state == null || sector == null) {
            return null;
        }
        Camp camp = camps.get(campKey(state, sector));
        if (camp == null) {
            return null;
        }
        long interval = getFuelIntervalMs();
        if (interval <= 0L) {
            return null;
        }
        long remainingMillis = computeFuelRemainingMillis(camp, interval);
        long now = System.currentTimeMillis();
        int fuel = Math.max(0, camp.getFuel());
        boolean overdue = fuel <= 0 || remainingMillis <= 0L;
        long overdueMillis = overdue ? Math.max(0L, now - camp.getLastFuelCheckAt()) : 0L;
        boolean warning = !overdue && fuel <= Math.max(1, camp.getMaxFuel() / 4);
        long elapsed = Math.max(0L, now - camp.getLastFuelCheckAt());
        long intervalProgress = interval <= 0 ? 0 : (elapsed % interval);
        long nextDue = overdue ? now : (camp.getLastFuelCheckAt() + interval - intervalProgress);
        return new CampMaintenanceInfo(camp, remainingMillis, overdueMillis, warning, overdue, nextDue, interval);
    }

    public void loadFromCampInfo(YamlConfiguration yaml) {
        camps.clear();
        for (BukkitTask task : capitalHoldTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        capitalHoldTasks.clear();
        capitalHoldAttackers.clear();

        if (yaml == null) {
            return;
        }

        ConfigurationSection statesSection = yaml.getConfigurationSection("states");
        if (statesSection == null) {
            return;
        }

        double defaultMaxHp = plugin.config().getDouble("camp.max-hp", 100.0);

        for (String stateName : statesSection.getKeys(false)) {
            ConfigurationSection stateSection = statesSection.getConfigurationSection(stateName);
            if (stateSection == null) {
                continue;
            }
            ConfigurationSection sectorsSection = stateSection.getConfigurationSection("sectors");
            if (sectorsSection == null) {
                continue;
            }
            for (String sectorName : sectorsSection.getKeys(false)) {
                ConfigurationSection sectorSection = sectorsSection.getConfigurationSection(sectorName);
                if (sectorSection == null) {
                    continue;
                }
                ConfigurationSection campSection = sectorSection.getConfigurationSection("camp");
                if (campSection == null) {
                    continue;
                }

                double maxHp = campSection.getDouble("max-hp", defaultMaxHp);
                Camp camp = new Camp(campKey(stateName, sectorName), stateName, sectorName, maxHp);
                double hp = campSection.getDouble("hp", maxHp);
                camp.setHp(hp);
                long brokenSince = campSection.getLong("broken-since", camp.isBroken() ? System.currentTimeMillis() : -1L);
                camp.setBrokenSince(brokenSince);
                camp.setLastDamagedAt(campSection.getLong("last-damaged", 0L));

                long lastMaintained = campSection.getLong("last-maintained", System.currentTimeMillis());
                camp.setLastMaintainedAt(lastMaintained);
                camp.setNextMaintenanceAt(campSection.getLong("next-maintenance", lastMaintained));
                camp.setMaintenanceWarningIssued(campSection.getBoolean("maintenance-warning", false));
                camp.setMaintenanceOverdueNotified(campSection.getBoolean("maintenance-overdue", false));
                camp.setLastMaintenanceDecayAt(campSection.getLong("last-maintenance-decay", 0L));
                camp.setMaxFuel(campSection.getInt("max-fuel", getMaxFuel()));
                camp.setFuel(campSection.getInt("fuel", camp.getMaxFuel()));
                camp.setLastFuelCheckAt(campSection.getLong("last-fuel-check", System.currentTimeMillis()));
                camp.setHealRate(campSection.getDouble("heal-rate", baseHealRate));
                camp.setFatigueAmplifier(campSection.getInt("fatigue-amplifier", baseFatigueAmplifier));
                camp.setHpLevel(campSection.getInt("hp-level", 0));
                camp.setFuelLevel(campSection.getInt("fuel-level", 0));
                camp.setHealLevel(campSection.getInt("heal-level", 0));
                camp.setFatigueLevel(campSection.getInt("fatigue-level", 0));
                camp.setStorageLevel(campSection.getInt("storage-level", 0));
                camp.setEfficiencyLevel(campSection.getInt("efficiency-level", 0));
                camp.setBoundaryLevel(campSection.getInt("boundary-level", 0));
                ConfigurationSection moduleSection = campSection.getConfigurationSection("modules");
                if (moduleSection != null) {
                    Map<String, Boolean> moduleStates = new HashMap<>();
                    for (String moduleKey : moduleSection.getKeys(false)) {
                        moduleStates.put(moduleKey, moduleSection.getBoolean(moduleKey, false));
                    }
                    camp.setModules(moduleStates);
                }
                applyCampUpgrades(camp);

                camp.setStoredMoney(campSection.getDouble("stored-money", 0.0));
                ConfigurationSection stored = campSection.getConfigurationSection("stored-items");
                if (stored != null) {
                    for (String itemKey : stored.getKeys(false)) {
                        int amount = stored.getInt(itemKey, 0);
                        if (amount > 0) {
                            camp.addStoredItem(itemKey, amount);
                        }
                    }
                }
                camp.setLastProductionAt(campSection.getLong("last-production", System.currentTimeMillis()));

                camps.put(campKey(stateName, sectorName), camp);
                if (plugin.holograms() != null) {
                    plugin.holograms().update(camp);
                }
                updateDynmap(camp);
            }
        }
    }

    public void removeStateCamps(String state) {
        if (state == null) {
            return;
        }
        camps.entrySet().removeIf(entry -> {
            Camp camp = entry.getValue();
            if (camp.getStateName().equalsIgnoreCase(state)) {
                cancelHold(entry.getKey());
                if (plugin.protection() != null) {
                    plugin.protection().clearCampEffects(camp.getStateName(), camp.getSectorName());
                }
                if (plugin.holograms() != null) {
                    plugin.holograms().removeCamp(camp.getStateName(), camp.getSectorName());
                }
                return true;
            }
            return false;
        });
        if (plugin.holograms() != null) {
            plugin.holograms().removeState(state);
        }
        String normalized = normalizeState(state);
        condemnations.remove(normalized);
        lastWarCommand.remove(normalized);
        lastCondemnCommand.remove(normalized);
        lastMoveCommand.remove(normalized);
        pendingCivilWars.entrySet().removeIf(entry -> entry.getValue().getRebel().equalsIgnoreCase(state)
                || entry.getValue().getOrigin().equalsIgnoreCase(state));
        condemnations.entrySet().removeIf(entry -> entry.getValue().getTarget().equalsIgnoreCase(state));
        markDirty();
    }

    public void renameState(String oldName, String newName) {
        if (oldName == null || newName == null || oldName.equalsIgnoreCase(newName)) {
            return;
        }
        Map<String, Camp> snapshot = new HashMap<>(camps);
        camps.clear();
        snapshot.forEach((key, camp) -> {
            if (camp.getStateName().equalsIgnoreCase(oldName)) {
                camp.setStateName(newName);
                camps.put(campKey(newName, camp.getSectorName()), camp);
            } else {
                camps.put(campKey(camp.getStateName(), camp.getSectorName()), camp);
            }
        });
        Map<String, PendingCivilWar> pendingSnapshot = new HashMap<>(pendingCivilWars);
        pendingCivilWars.clear();
        for (PendingCivilWar pending : pendingSnapshot.values()) {
            String rebel = pending.getRebel();
            String origin = pending.getOrigin();
            if (rebel.equalsIgnoreCase(oldName)) {
                rebel = newName;
            }
            if (origin.equalsIgnoreCase(oldName)) {
                origin = newName;
            }
            pendingCivilWars.put(normalizeState(rebel), new PendingCivilWar(rebel, origin, pending.getCaptain(), pending.getStartTime()));
        }
        String lowerOld = oldName.toLowerCase(Locale.ROOT);
        List<String> toRemove = new ArrayList<>();
        for (String key : capitalHoldTasks.keySet()) {
            if (key.startsWith(lowerOld + "|")) {
                BukkitTask task = capitalHoldTasks.get(key);
                if (task != null) {
                    task.cancel();
                }
                toRemove.add(key);
            }
        }
        toRemove.forEach(k -> {
            capitalHoldTasks.remove(k);
            capitalHoldAttackers.remove(k);
        });

        wars.replaceAll((k, data) -> {
            data.renameState(oldName, newName);
            return data;
        });
        Map<String, WarData> rebuilt = new HashMap<>();
        wars.values().forEach(data -> rebuilt.put(warKey(data.getAttacker(), data.getDefender()), data));
        wars.clear();
        wars.putAll(rebuilt);

        String oldKey = normalizeState(oldName);
        String newKey = normalizeState(newName);
        moveKey(lastWarCommand, oldKey, newKey);
        moveKey(lastCondemnCommand, oldKey, newKey);
        moveKey(lastMoveCommand, oldKey, newKey);

        Map<String, CondemnationData> updatedCondemnations = new HashMap<>();
        condemnations.forEach((key, data) -> {
            String mappedKey = key.equals(oldKey) ? newKey : key;
            String target = data.getTarget();
            if (target.equalsIgnoreCase(oldName)) {
                target = newName;
            }
            updatedCondemnations.put(mappedKey, new CondemnationData(target, data.getStartTime()));
        });
        condemnations.clear();
        condemnations.putAll(updatedCondemnations);

        Map<String, SurrenderRequest> updatedSurrenders = new HashMap<>();
        surrenderRequests.forEach((key, request) -> {
            String updatedAttacker = request.getAttacker();
            String updatedDefender = request.getDefender();
            String updatedFrom = request.getFromState();
            if (request.getAttacker().equalsIgnoreCase(oldName)) {
                updatedAttacker = newName;
            }
            if (request.getDefender().equalsIgnoreCase(oldName)) {
                updatedDefender = newName;
            }
            if (request.getFromState().equalsIgnoreCase(oldName)) {
                updatedFrom = newName;
            }
            updatedSurrenders.put(warKey(updatedAttacker, updatedDefender), new SurrenderRequest(updatedAttacker, updatedDefender, updatedFrom, request.getCreated()));
        });
        surrenderRequests.clear();
        surrenderRequests.putAll(updatedSurrenders);
        markDirty();
    }

    public void renameSector(String state, String oldSector, String newSector) {
        if (state == null || oldSector == null || newSector == null) {
            return;
        }
        String oldKey = campKey(state, oldSector);
        Camp camp = camps.remove(oldKey);
        if (camp == null) {
            return;
        }
        cancelHold(oldKey);
        camp.setSectorName(newSector);
        camps.put(campKey(state, newSector), camp);
        markDirty();
    }

    public EmergencyMoveResult requestEmergencyMove(String stateName, String sector) {
        if (stateName == null || sector == null) {
            return EmergencyMoveResult.noSuchSector();
        }
        String resolved = plugin.state().resolveSectorName(stateName, sector);
        if (resolved == null) {
            return EmergencyMoveResult.noSuchSector();
        }

        Optional<WarData> warOpt = findWar(stateName);
        if (warOpt.isEmpty()) {
            return EmergencyMoveResult.notInWar();
        }

        WarData war = warOpt.get();
        long remaining = getMoveCooldownRemaining(stateName);
        if (remaining > 0L) {
            return EmergencyMoveResult.cooldown(remaining);
        }
        if (war.hasUsedEmergencyMove(stateName)) {
            return EmergencyMoveResult.alreadyUsed();
        }

        String currentCapital = plugin.state().getCapital(stateName);
        if (currentCapital != null) {
            if (currentCapital.equalsIgnoreCase(resolved)) {
                return EmergencyMoveResult.alreadyCapital(resolved);
            }
            cancelHold(campKey(stateName, currentCapital));
        }

        war.markEmergencyMove(stateName);
        war.clearCapitalBroken(stateName);
        lastMoveCommand.put(normalizeState(stateName), System.currentTimeMillis());
        String opponent = war.getAttacker().equalsIgnoreCase(stateName) ? war.getDefender() : war.getAttacker();
        return EmergencyMoveResult.success(opponent, resolved);
    }

    public CampDamageResult damageCamp(String defenderState, String sector, double dmg, String attackerState) {
        if (defenderState == null || sector == null || attackerState == null) {
            return CampDamageResult.invalid();
        }

        if (!areStatesAtWar(defenderState, attackerState)) {
            return CampDamageResult.notAtWar();
        }

        Camp camp = camps.get(campKey(defenderState, sector));
        if (camp == null) {
            return CampDamageResult.notFound();
        }

        if (camp.isBroken()) {
            if (plugin.protection() != null) {
                plugin.protection().clearCampEffects(defenderState, sector);
            }
            if (plugin.holograms() != null) {
                plugin.holograms().update(camp);
            }
            updateDynmap(camp);
            boolean capital = plugin.state().isCapitalSector(defenderState, sector);
            long hold = plugin.config().getLong("war.capital-hold-seconds", 120L);
            return CampDamageResult.broken(camp, capital, hold);
        }

        boolean broken = camp.damage(dmg);
        double hp = camp.getHp();
        double maxHp = camp.getMaxHp();

        if (plugin.holograms() != null) {
            plugin.holograms().update(camp);
        }
        updateDynmap(camp);

        Map<String, String> damageVars = Map.of(
                "state", defenderState,
                "sector", sector,
                "hp", String.format(Locale.CHINA, "%.1f", hp),
                "max", String.format(Locale.CHINA, "%.1f", maxHp)
        );
        sendStateActionBar(defenderState, "war.camp-damage", damageVars, plugin.campDamageSound());
        sendStateActionBar(attackerState, "war.camp-damage", damageVars, plugin.campDamageSound());

        markDirty();
        if (broken) {
            camp.setFuel(0);
            handleCampBroken(camp, attackerState);
            markDirty();
            return CampDamageResult.broken(camp, plugin.state().isCapitalSector(defenderState, sector),
                    plugin.config().getLong("war.capital-hold-seconds", 120L));
        }

        return CampDamageResult.success(hp, maxHp);
    }

    public void notifyEmergencyMove(String state, String opponent, String sector) {
        Map<String, String> vars = Map.of(
                "state", state,
                "sector", sector,
                "enemy", opponent
        );
        sendStateMessage(state, "war.move-broadcast", vars);
        sendStateMessage(opponent, "war.move-broadcast", vars);
    }

    private void handleCampBroken(Camp camp, String attackerState) {
            Map<String, String> brokenVars = Map.of(
                    "state", camp.getStateName(),
                    "sector", camp.getSectorName(),
                    "attacker", attackerState
            );
            sendStateMessage(camp.getStateName(), "war.camp-broken", brokenVars);
            sendStateMessage(attackerState, "war.camp-broken", brokenVars);

        if (plugin.protection() != null) {
            plugin.protection().clearCampEffects(camp.getStateName(), camp.getSectorName());
        }
        if (plugin.holograms() != null) {
            plugin.holograms().update(camp);
        }

        findWar(camp.getStateName()).ifPresent(war -> war.markCapitalBroken(camp.getStateName(), System.currentTimeMillis()));

        if (plugin.state().isCapitalSector(camp.getStateName(), camp.getSectorName())) {
            scheduleCapitalHold(camp, attackerState);
        }
    }

    private void scheduleCapitalHold(Camp camp, String attackerState) {
        long holdSeconds = Math.max(5L, plugin.config().getLong("war.capital-hold-seconds", 120L));
        String key = campKey(camp.getStateName(), camp.getSectorName());
        cancelHold(key);
        capitalHoldAttackers.put(key, attackerState);
        Map<String, String> vars = Map.of(
                "state", camp.getStateName(),
                "sector", camp.getSectorName(),
                "attacker", attackerState,
                "timer", String.valueOf(holdSeconds)
        );
        sendStateMessage(camp.getStateName(), "war.capital-broken", vars);
        sendStateMessage(attackerState, "war.capital-broken", vars);

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                Camp stored = camps.get(key);
                if (stored == null || !stored.isBroken()) {
                    return;
                }
                String challenger = capitalHoldAttackers.get(key);
                if (challenger == null) {
                    return;
                }
                if (!areStatesAtWar(stored.getStateName(), challenger)) {
                    return;
                }
                endWar(stored.getStateName(), challenger, challenger);
            }
        }.runTaskLater(plugin, holdSeconds * 20L);
        capitalHoldTasks.put(key, task);
    }

    private void cancelHold(String key) {
        BukkitTask task = capitalHoldTasks.remove(key);
        if (task != null) {
            task.cancel();
        }
        capitalHoldAttackers.remove(key);
    }

    public CampRepairResult repairCamp(String state, String sector, double amount) {
        if (state == null || sector == null) {
            return CampRepairResult.invalidAmount();
        }
        if (amount <= 0) {
            return CampRepairResult.invalidAmount();
        }
        Camp camp = camps.get(campKey(state, sector));
        if (camp == null) {
            return CampRepairResult.notFound();
        }
        double before = camp.getHp();
        if (before >= camp.getMaxHp()) {
            return CampRepairResult.alreadyFull(camp);
        }
        boolean brokenBefore = camp.isBroken();
        camp.repair(amount);
        if (!camp.isBroken()) {
            cancelHold(campKey(state, sector));
            findWar(state).ifPresent(war -> war.clearCapitalBroken(state));
        }
        if (plugin.holograms() != null) {
            plugin.holograms().update(camp);
        }
        updateDynmap(camp);
        markDirty();
        return CampRepairResult.success(camp, Math.max(0.0, camp.getHp() - before), brokenBefore);
    }

    public CampMaintenanceResult maintainCamp(String state, String sector) {
        if (state == null || sector == null) {
            return CampMaintenanceResult.invalid();
        }
        Camp camp = camps.get(campKey(state, sector));
        if (camp == null) {
            return CampMaintenanceResult.notFound();
        }
        boolean capital = plugin.state().isCapitalSector(state, sector);
        long interval = getMaintenanceIntervalMs(capital);
        if (interval <= 0L) {
            return CampMaintenanceResult.invalid();
        }
        long now = System.currentTimeMillis();
        camp.resetMaintenance(now, now + interval);
        if (plugin.holograms() != null) {
            plugin.holograms().update(camp);
        }
        updateDynmap(camp);
        markDirty();
        return CampMaintenanceResult.success(camp, now + interval, interval);
    }

    public void refreshMaintenanceSchedule(String state, String sector) {
        if (state == null || sector == null) {
            return;
        }
        Camp camp = camps.get(campKey(state, sector));
        if (camp == null) {
            return;
        }
        boolean capital = plugin.state().isCapitalSector(state, sector);
        long interval = getMaintenanceIntervalMs(capital);
        if (interval <= 0L) {
            camp.setNextMaintenanceAt(-1L);
            camp.setMaintenanceWarningIssued(false);
            camp.setMaintenanceOverdueNotified(false);
            camp.setLastMaintenanceDecayAt(0L);
        } else {
            long last = camp.getLastMaintainedAt();
            if (last <= 0L) {
                last = System.currentTimeMillis();
                camp.setLastMaintainedAt(last);
            }
            camp.setNextMaintenanceAt(last + interval);
            camp.setMaintenanceWarningIssued(false);
            camp.setMaintenanceOverdueNotified(false);
            camp.setLastMaintenanceDecayAt(0L);
        }
        if (plugin.holograms() != null) {
            plugin.holograms().update(camp);
        }
        updateDynmap(camp);
        markDirty();
    }

    // 自动回血
    private void startAutoHealTask() {
        if (autoHealTask != null) {
            autoHealTask.cancel();
        }
        long interval = Math.max(1L, plugin.config().getLong("camp.heal-interval", 600L));

        autoHealTask = new BukkitRunnable() {
            @Override public void run() {
                boolean changed = false;
                for (Camp c : camps.values()) {
                    if (c.getFuel() <= 0) continue;
                    double rate = c.getHealRate() > 0 ? c.getHealRate() : baseHealRate;
                    double before = c.getHp();
                    if (c.isBroken()) {
                        c.repair(rate);
                    } else {
                        c.heal(rate);
                    }
                    if (before <= 0 && c.getHp() > 0) {
                        cancelHold(campKey(c.getStateName(), c.getSectorName()));
                    }
                    if (c.getHp() != before) {
                        changed = true;
                        if (plugin.holograms() != null) {
                            plugin.holograms().update(c);
                        }
                        updateDynmap(c);
                    }
                }
                if (changed) {
                    markDirty();
                }
            }
        }.runTaskTimer(plugin, interval, interval);
    }

    private void startMaintenanceTask() {
        if (maintenanceTask != null) {
            maintenanceTask.cancel();
        }
        long interval = getMaintenanceCheckIntervalTicks();
        maintenanceTask = new BukkitRunnable() {
            @Override
            public void run() {
                tickMaintenance();
            }
        }.runTaskTimer(plugin, interval, interval);
    }

    private void tickMaintenance() {
        long fuelIntervalMs = getFuelIntervalMs();
        int fuelDrain = getFuelDrainAmount();
        double zeroFuelDamage = Math.max(0.0, plugin.getConfig().getDouble("camp.fuel.zero-damage", 0.0));
        long now = System.currentTimeMillis();

        boolean changed = false;
        for (Camp camp : camps.values()) {
            long fuelLast = camp.getLastFuelCheckAt();
            if (fuelLast <= 0L) {
                fuelLast = now;
            }
            long fuelElapsed = now - fuelLast;
            if (fuelIntervalMs > 0 && fuelDrain > 0 && fuelElapsed >= fuelIntervalMs) {
                long steps = Math.max(1L, fuelElapsed / fuelIntervalMs);
                int drained = (int) Math.min(Integer.MAX_VALUE, steps * (long) fuelDrain);
                int beforeFuel = camp.getFuel();
                camp.setFuel(beforeFuel - drained);
                camp.setLastFuelCheckAt(fuelLast + steps * fuelIntervalMs);
                if (camp.getFuel() != beforeFuel) {
                    changed = true;
                    if (camp.getFuel() <= 0 && plugin.protection() != null) {
                        plugin.protection().clearCampEffects(camp.getStateName(), camp.getSectorName());
                    }
                    if (plugin.holograms() != null) {
                        plugin.holograms().update(camp);
                    }
                }
            }

            if (handleProduction(camp, now)) {
                changed = true;
            }

            if (zeroFuelDamage > 0 && camp.getFuel() <= 0) {
                double beforeHp = camp.getHp();
                double maxDamage = Math.max(0.0, beforeHp - 1.0);
                double toApply = Math.min(zeroFuelDamage, maxDamage);
                if (toApply > 0.0) {
                    boolean broken = camp.damage(toApply);
                    changed = true;
                    handleMaintenanceDamage(camp, toApply);
                    if (plugin.holograms() != null) {
                        plugin.holograms().update(camp);
                    }
                    updateDynmap(camp);
                    if (broken) {
                        handleMaintenanceBreak(camp);
                    }
                }
            }
        }
        if (changed) {
            markDirty();
        }
    }

    private long computeFuelRemainingMillis(Camp camp, long interval) {
        if (camp == null || interval <= 0L) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        long elapsed = Math.max(0L, now - camp.getLastFuelCheckAt());
        long total = interval * Math.max(0L, (long) camp.getFuel());
        return Math.max(0L, total - elapsed);
    }

    private boolean handleProduction(Camp camp, long now) {
        if (!productionEnabled || camp == null) {
            return false;
        }
        if (camp.getFuel() <= 0 || camp.getProductionIntervalMs() <= 0L) {
            camp.setLastProductionAt(now);
            return false;
        }
        long last = camp.getLastProductionAt();
        if (last <= 0L) {
            camp.setLastProductionAt(now);
            return false;
        }
        long elapsed = now - last;
        long interval = camp.getProductionIntervalMs();
        if (elapsed < interval) {
            return false;
        }
        long steps = Math.max(1L, elapsed / interval);
        camp.setLastProductionAt(last + steps * interval);

        boolean changed = false;
        double moneyToAdd = productionMoney * steps;
        if (moneyToAdd > 0.0 && camp.getMaxStoredMoney() > 0.0 && camp.getStoredMoney() < camp.getMaxStoredMoney()) {
            double before = camp.getStoredMoney();
            camp.addStoredMoney(moneyToAdd);
            changed |= Math.abs(before - camp.getStoredMoney()) > 1e-6;
        }

        if (!productionItems.isEmpty() && camp.getMaxStoredItems() > 0) {
            int totalAvailable = Math.max(0, camp.getMaxStoredItems() - camp.getStoredItemTotal());
            if (totalAvailable > 0) {
                for (Map.Entry<StateManager.ItemDescriptor, Integer> entry : productionItems.entrySet()) {
                    int amount = (int) Math.min(Integer.MAX_VALUE, entry.getValue() * steps);
                    if (amount <= 0) {
                        continue;
                    }
                    int allowed = Math.max(0, camp.getMaxStoredItems() - camp.getStoredItemTotal());
                    if (allowed <= 0) {
                        break;
                    }
                    int toStore = Math.min(allowed, amount);
                    int before = camp.getStoredItemTotal();
                    camp.addStoredItem(entry.getKey().getIdentity(), toStore);
                    changed |= camp.getStoredItemTotal() != before;
                }
            }
        }
        return changed;
    }

    private StateManager.ItemDescriptor findProductionDescriptor(String identity) {
        if (identity == null) {
            return null;
        }
        for (StateManager.ItemDescriptor descriptor : productionItems.keySet()) {
            if (descriptor.getIdentity().equalsIgnoreCase(identity)) {
                return descriptor;
            }
        }
        return null;
    }

    private void handleMaintenanceWarning(Camp camp, long remaining) {
        StateManager.StateData state = plugin.state().getState(camp.getStateName());
        if (state == null) {
            return;
        }
        Map<String, String> vars = new HashMap<>();
        vars.put("state", state.name);
        vars.put("sector", camp.getSectorName());
        vars.put("time", formatDuration(remaining));
        notifyMaintenanceRecipients(state, camp.getSectorName(), "state.maintenance-warning", vars);
    }

    private void handleMaintenanceOverdue(Camp camp, long overdue) {
        StateManager.StateData state = plugin.state().getState(camp.getStateName());
        if (state == null) {
            return;
        }
        Map<String, String> vars = new HashMap<>();
        vars.put("state", state.name);
        vars.put("sector", camp.getSectorName());
        vars.put("time", formatDuration(overdue));
        notifyMaintenanceRecipients(state, camp.getSectorName(), "state.maintenance-overdue", vars);
    }

    private void handleMaintenanceDamage(Camp camp, double amount) {
        StateManager.StateData state = plugin.state().getState(camp.getStateName());
        if (state == null) {
            return;
        }
        Map<String, String> vars = new HashMap<>();
        vars.put("state", state.name);
        vars.put("sector", camp.getSectorName());
        vars.put("damage", String.format(Locale.US, "%.1f", amount));
        vars.put("hp", String.format(Locale.US, "%.1f", camp.getHp()));
        vars.put("max", String.format(Locale.US, "%.1f", camp.getMaxHp()));
        notifyMaintenanceRecipients(state, camp.getSectorName(), "state.maintenance-damage", vars);
    }

    private void handleMaintenanceBreak(Camp camp) {
        if (plugin.protection() != null) {
            plugin.protection().clearCampEffects(camp.getStateName(), camp.getSectorName());
        }
        if (plugin.holograms() != null) {
            plugin.holograms().update(camp);
        }
        updateDynmap(camp);
        StateManager.StateData state = plugin.state().getState(camp.getStateName());
        if (state == null) {
            return;
        }
        Map<String, String> vars = new HashMap<>();
        vars.put("state", state.name);
        vars.put("sector", camp.getSectorName());
        notifyMaintenanceRecipients(state, camp.getSectorName(), "state.maintenance-broken", vars);
    }

    private void notifyMaintenanceRecipients(StateManager.StateData state, String sector, String messageKey, Map<String, String> vars) {
        if (state == null) {
            return;
        }
        StateManager.SectorData sectorData = state.sectors.get(sector);
        Set<UUID> recipients = new HashSet<>();
        if (state.captain != null) {
            recipients.add(state.captain);
        }
        if (sectorData != null && sectorData.getOwner() != null) {
            recipients.add(sectorData.getOwner());
        }
        for (UUID id : recipients) {
            Player target = Bukkit.getPlayer(id);
            if (target != null) {
                if (MAINTENANCE_ACTIONBAR_KEYS.contains(messageKey)) {
                    plugin.lang().sendActionBar(target, messageKey, vars);
                } else {
                    plugin.lang().send(target, messageKey, vars);
                }
            }
        }
    }

    public ProductionClaimResult claimProduction(Player player, Camp camp) {
        if (player == null || camp == null) {
            return ProductionClaimResult.empty();
        }
        if (!plugin.state().canManageCamp(player, camp)) {
            return ProductionClaimResult.noPermission();
        }
        double storedMoney = camp.getStoredMoney();
        int storedItems = camp.getStoredItemTotal();
        if (storedItems <= 0 && storedMoney <= 1e-6) {
            return ProductionClaimResult.empty();
        }

        Map<String, Integer> deliveredItems = new LinkedHashMap<>();
        boolean itemsDelivered = false;
        for (Map.Entry<String, Integer> entry : new HashMap<>(camp.getStoredItems()).entrySet()) {
            String key = entry.getKey();
            int amount = entry.getValue();
            if (amount <= 0) {
                continue;
            }
            StateManager.ItemDescriptor descriptor = findProductionDescriptor(key);
            ItemStack baseItem = descriptor != null ? descriptor.createItem(plugin, 1) : null;
            Material material = baseItem != null ? baseItem.getType() : Material.matchMaterial(key);
            while (amount > 0 && material != null) {
                int batch = Math.min(amount, baseItem != null ? baseItem.getMaxStackSize() : material.getMaxStackSize());
                ItemStack stack = descriptor != null ? descriptor.createItem(plugin, batch) : new ItemStack(material, batch);
                if (stack == null) {
                    break;
                }
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
                if (!leftovers.isEmpty()) {
                    leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                }
                amount -= batch;
                itemsDelivered = true;
                String display = descriptor != null ? descriptor.getDisplay() : material.name();
                deliveredItems.merge(display, batch, Integer::sum);
            }
        }
        camp.clearStoredItems();

        double moneyClaimed = 0.0;
        Economy economy = plugin.economy();
        if (storedMoney > 0.0) {
            if (economy == null) {
                if (itemsDelivered) {
                    markDirty();
                }
                return ProductionClaimResult.noEconomy(deliveredItems, itemsDelivered);
            }
            economy.depositPlayer(player, storedMoney);
            moneyClaimed = storedMoney;
            camp.setStoredMoney(0.0);
        }

        markDirty();
        return ProductionClaimResult.success(moneyClaimed, deliveredItems, itemsDelivered);
    }

    public boolean isInWar(Player p) {
        String state = plugin.state().getStateName(p);
        if (state == null) {
            return false;
        }
        return findWar(state).isPresent();
    }

    public SurrenderRequestResult requestSurrender(String requesterState) {
        if (requesterState == null) {
            return SurrenderRequestResult.invalid();
        }

        Optional<WarData> warOpt = findWar(requesterState);
        if (warOpt.isEmpty()) {
            return SurrenderRequestResult.notAtWar();
        }

        WarData war = warOpt.get();
        boolean attacker = war.getAttacker().equalsIgnoreCase(requesterState);
        boolean defender = war.getDefender().equalsIgnoreCase(requesterState);
        if (!attacker && !defender) {
            return SurrenderRequestResult.notPrimary();
        }

        String opponent = attacker ? war.getDefender() : war.getAttacker();
        StateManager.StateData target = plugin.state().findState(opponent);
        if (target == null) {
            return SurrenderRequestResult.invalid();
        }
        if (!plugin.state().isCaptainOnline(target)) {
            return SurrenderRequestResult.captainOffline(opponent);
        }

        String key = warKey(war.getAttacker(), war.getDefender());
        purgeExpiredSurrenders();
        SurrenderRequest existing = surrenderRequests.get(key);
        if (existing != null) {
            if (existing.getFromState().equalsIgnoreCase(requesterState)) {
                return SurrenderRequestResult.alreadyRequested(opponent, getSurrenderRemaining(existing));
            }
            return SurrenderRequestResult.otherPending(existing.getFromState());
        }

        SurrenderRequest request = new SurrenderRequest(war.getAttacker(), war.getDefender(), requesterState, System.currentTimeMillis());
        surrenderRequests.put(key, request);
        return SurrenderRequestResult.requested(opponent, getSurrenderTimeoutMs());
    }

    public SurrenderResponseResult respondSurrender(String responderState, boolean accept) {
        if (responderState == null) {
            return SurrenderResponseResult.noRequest();
        }

        Optional<WarData> warOpt = findWar(responderState);
        if (warOpt.isEmpty()) {
            return SurrenderResponseResult.noRequest();
        }

        WarData war = warOpt.get();
        String key = warKey(war.getAttacker(), war.getDefender());
        purgeExpiredSurrenders();
        SurrenderRequest request = surrenderRequests.get(key);
        if (request == null) {
            return SurrenderResponseResult.noRequest();
        }

        String target = request.getAttacker().equalsIgnoreCase(request.getFromState()) ? request.getDefender() : request.getAttacker();
        if (!responderState.equalsIgnoreCase(target)) {
            return SurrenderResponseResult.notTarget(target);
        }

        if (isSurrenderExpired(request)) {
            surrenderRequests.remove(key);
            return SurrenderResponseResult.expired();
        }

        surrenderRequests.remove(key);
        if (accept) {
            endWar(war.getAttacker(), war.getDefender(), responderState);
            return SurrenderResponseResult.accepted(request.getFromState());
        }
        return SurrenderResponseResult.denied(request.getFromState());
    }

    private void purgeExpiredSurrenders() {
        long timeout = getSurrenderTimeoutMs();
        if (timeout <= 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        surrenderRequests.entrySet().removeIf(entry -> now - entry.getValue().getCreated() > timeout);
    }

    private boolean isSurrenderExpired(SurrenderRequest request) {
        if (request == null) {
            return true;
        }
        long timeout = getSurrenderTimeoutMs();
        if (timeout <= 0L) {
            return false;
        }
        return System.currentTimeMillis() - request.getCreated() > timeout;
    }

    private long getSurrenderRemaining(SurrenderRequest request) {
        if (request == null) {
            return 0L;
        }
        long timeout = getSurrenderTimeoutMs();
        if (timeout <= 0L) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - request.getCreated();
        return Math.max(0L, timeout - elapsed);
    }

    private void clearCapitalHoldForState(String state) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Camp> entry : camps.entrySet()) {
            if (entry.getValue().getStateName().equalsIgnoreCase(state)) {
                keys.add(entry.getKey());
            }
        }
        keys.forEach(this::cancelHold);
    }

    private void revealCampLocations(StateManager.StateData recipients, StateManager.StateData enemy) {
        if (recipients == null || enemy == null) {
            return;
        }

        Map<String, String> headerVars = Map.of("enemy", enemy.name);
        for (UUID member : recipients.members) {
            Player player = Bukkit.getPlayer(member);
            if (player == null) {
                continue;
            }
            plugin.lang().send(player, "war.reveal-header", headerVars);
            if (enemy.sectors.isEmpty()) {
                plugin.lang().send(player, "war.reveal-empty");
                continue;
            }
            for (StateManager.SectorData sector : enemy.sectors.values()) {
                Location location = sector.getLocation();
                if (location == null) {
                    continue;
                }
                plugin.lang().send(player, "war.reveal-line", Map.of(
                        "sector", sector.getName(),
                        "x", String.valueOf(location.getBlockX()),
                        "y", String.valueOf(location.getBlockY()),
                        "z", String.valueOf(location.getBlockZ()),
                        "world", location.getWorld() == null ? "" : location.getWorld().getName()
                ));
            }
        }
    }

    private Camp getCapitalCamp(String state) {
        StateManager.StateData data = plugin.state().findState(state);
        if (data == null || data.capitalSector == null) {
            return null;
        }
        return camps.get(campKey(data.name, data.capitalSector));
    }

    public static class CondemnationData {
        private final String target;
        private final long startTime;

        public CondemnationData(String target, long startTime) {
            this.target = target;
            this.startTime = startTime;
        }

        public String getTarget() {
            return target;
        }

        public String getTargetState() {
            return target;
        }

        public long getStartTime() {
            return startTime;
        }
    }

    public enum CondemnationStatus {
        SUCCESS,
        INVALID_STATE,
        SAME_SIDE,
        ALREADY_AT_WAR,
        ALREADY_CONDEMNED,
        ALREADY_CONDEMNED_OTHER,
        COOLDOWN,
        CIVIL_WAR_PENDING
    }

    public static class CondemnationResult {
        private final CondemnationStatus status;
        private final String target;
        private final String existingTarget;
        private final long remainingMs;

        private CondemnationResult(CondemnationStatus status, String target, String existingTarget, long remainingMs) {
            this.status = status;
            this.target = target;
            this.existingTarget = existingTarget;
            this.remainingMs = remainingMs;
        }

        public static CondemnationResult success(String target, long remainingMs) {
            return new CondemnationResult(CondemnationStatus.SUCCESS, target, null, remainingMs);
        }

        public static CondemnationResult invalid() {
            return new CondemnationResult(CondemnationStatus.INVALID_STATE, null, null, 0L);
        }

        public static CondemnationResult sameSide() {
            return new CondemnationResult(CondemnationStatus.SAME_SIDE, null, null, 0L);
        }

        public static CondemnationResult alreadyAtWar() {
            return new CondemnationResult(CondemnationStatus.ALREADY_AT_WAR, null, null, 0L);
        }

        public static CondemnationResult alreadyCondemned(String target) {
            return new CondemnationResult(CondemnationStatus.ALREADY_CONDEMNED, target, target, 0L);
        }

        public static CondemnationResult alreadyCondemnedOther(String requested, String existing) {
            return new CondemnationResult(CondemnationStatus.ALREADY_CONDEMNED_OTHER, requested, existing, 0L);
        }

        public static CondemnationResult cooldown(String target, long remainingMs) {
            return new CondemnationResult(CondemnationStatus.COOLDOWN, target, null, remainingMs);
        }

        public static CondemnationResult civilWarPending(String target) {
            return new CondemnationResult(CondemnationStatus.CIVIL_WAR_PENDING, target, null, 0L);
        }

        public CondemnationStatus getStatus() {
            return status;
        }

        public String getTarget() {
            return target;
        }

        public String getExistingTarget() {
            return existingTarget;
        }

        public long getRemainingMs() {
            return remainingMs;
        }
    }

    public enum SurrenderRequestStatus {
        SUCCESS,
        INVALID,
        NOT_AT_WAR,
        NOT_PRIMARY,
        CAPTAIN_OFFLINE,
        PENDING_SELF,
        PENDING_OTHER
    }

    public static class SurrenderRequestResult {
        private final SurrenderRequestStatus status;
        private final String target;
        private final long remainingMs;

        private SurrenderRequestResult(SurrenderRequestStatus status, String target, long remainingMs) {
            this.status = status;
            this.target = target;
            this.remainingMs = remainingMs;
        }

        public static SurrenderRequestResult requested(String target, long remainingMs) {
            return new SurrenderRequestResult(SurrenderRequestStatus.SUCCESS, target, remainingMs);
        }

        public static SurrenderRequestResult invalid() {
            return new SurrenderRequestResult(SurrenderRequestStatus.INVALID, null, 0L);
        }

        public static SurrenderRequestResult notAtWar() {
            return new SurrenderRequestResult(SurrenderRequestStatus.NOT_AT_WAR, null, 0L);
        }

        public static SurrenderRequestResult notPrimary() {
            return new SurrenderRequestResult(SurrenderRequestStatus.NOT_PRIMARY, null, 0L);
        }

        public static SurrenderRequestResult captainOffline(String target) {
            return new SurrenderRequestResult(SurrenderRequestStatus.CAPTAIN_OFFLINE, target, 0L);
        }

        public static SurrenderRequestResult alreadyRequested(String target, long remainingMs) {
            return new SurrenderRequestResult(SurrenderRequestStatus.PENDING_SELF, target, remainingMs);
        }

        public static SurrenderRequestResult otherPending(String target) {
            return new SurrenderRequestResult(SurrenderRequestStatus.PENDING_OTHER, target, 0L);
        }

        public SurrenderRequestStatus getStatus() {
            return status;
        }

        public String getTarget() {
            return target;
        }

        public long getRemainingMs() {
            return remainingMs;
        }
    }

    public enum SurrenderResponseStatus {
        ACCEPTED,
        DENIED,
        NO_REQUEST,
        NOT_TARGET,
        EXPIRED
    }

    public static class SurrenderResponseResult {
        private final SurrenderResponseStatus status;
        private final String opponent;

        private SurrenderResponseResult(SurrenderResponseStatus status, String opponent) {
            this.status = status;
            this.opponent = opponent;
        }

        public static SurrenderResponseResult accepted(String opponent) {
            return new SurrenderResponseResult(SurrenderResponseStatus.ACCEPTED, opponent);
        }

        public static SurrenderResponseResult denied(String opponent) {
            return new SurrenderResponseResult(SurrenderResponseStatus.DENIED, opponent);
        }

        public static SurrenderResponseResult noRequest() {
            return new SurrenderResponseResult(SurrenderResponseStatus.NO_REQUEST, null);
        }

        public static SurrenderResponseResult notTarget(String expected) {
            return new SurrenderResponseResult(SurrenderResponseStatus.NOT_TARGET, expected);
        }

        public static SurrenderResponseResult expired() {
            return new SurrenderResponseResult(SurrenderResponseStatus.EXPIRED, null);
        }

        public SurrenderResponseStatus getStatus() {
            return status;
        }

        public String getOpponent() {
            return opponent;
        }
    }

    public static class SurrenderRequest {
        private final String attacker;
        private final String defender;
        private final String fromState;
        private final long created;

        public SurrenderRequest(String attacker, String defender, String fromState, long created) {
            this.attacker = attacker;
            this.defender = defender;
            this.fromState = fromState;
            this.created = created;
        }

        public String getAttacker() {
            return attacker;
        }

        public String getDefender() {
            return defender;
        }

        public String getFromState() {
            return fromState;
        }

        public long getCreated() {
            return created;
        }
    }

    public enum EmergencyMoveStatus {
        SUCCESS,
        NOT_IN_WAR,
        ALREADY_USED,
        NO_SUCH_SECTOR,
        ALREADY_CAPITAL,
        COOLDOWN
    }

    public static class EmergencyMoveResult {
        private final EmergencyMoveStatus status;
        private final String opponent;
        private final String resolvedSector;
        private final long remainingMs;

        private EmergencyMoveResult(EmergencyMoveStatus status, String opponent, String resolvedSector, long remainingMs) {
            this.status = status;
            this.opponent = opponent;
            this.resolvedSector = resolvedSector;
            this.remainingMs = remainingMs;
        }

        public static EmergencyMoveResult success(String opponent, String resolvedSector) {
            return new EmergencyMoveResult(EmergencyMoveStatus.SUCCESS, opponent, resolvedSector, 0L);
        }

        public static EmergencyMoveResult notInWar() {
            return new EmergencyMoveResult(EmergencyMoveStatus.NOT_IN_WAR, null, null, 0L);
        }

        public static EmergencyMoveResult alreadyUsed() {
            return new EmergencyMoveResult(EmergencyMoveStatus.ALREADY_USED, null, null, 0L);
        }

        public static EmergencyMoveResult noSuchSector() {
            return new EmergencyMoveResult(EmergencyMoveStatus.NO_SUCH_SECTOR, null, null, 0L);
        }

        public static EmergencyMoveResult alreadyCapital(String resolvedSector) {
            return new EmergencyMoveResult(EmergencyMoveStatus.ALREADY_CAPITAL, null, resolvedSector, 0L);
        }

        public static EmergencyMoveResult cooldown(long remainingMs) {
            return new EmergencyMoveResult(EmergencyMoveStatus.COOLDOWN, null, null, remainingMs);
        }

        public EmergencyMoveStatus getStatus() {
            return status;
        }

        public String getOpponent() {
            return opponent;
        }

        public String getResolvedSector() {
            return resolvedSector;
        }

        public long getRemainingMs() {
            return remainingMs;
        }
    }

    private static class PendingCivilWar {
        private final String rebel;
        private final String origin;
        private final UUID captain;
        private final long startTime;

        private PendingCivilWar(String rebel, String origin, UUID captain, long startTime) {
            this.rebel = rebel;
            this.origin = origin;
            this.captain = captain;
            this.startTime = startTime;
        }

        public String getRebel() {
            return rebel;
        }

        public String getOrigin() {
            return origin;
        }

        public UUID getCaptain() {
            return captain;
        }

        public long getStartTime() {
            return startTime;
        }
    }

    public enum WarStartResult {
        SUCCESS,
        INVALID_STATE,
        SAME_SIDE,
        ALREADY_AT_WAR,
        ATTACKER_BUSY,
        NO_CONDEMNATION,
        CONDEMNATION_PENDING,
        CONDEMNATION_WRONG_TARGET,
        COOLDOWN,
        REQUIREMENTS,
        CIVIL_WAR_PENDING
    }

    public enum CampRepairStatus {
        SUCCESS,
        NOT_FOUND,
        INVALID_AMOUNT,
        ALREADY_FULL
    }

    public static class CampRepairResult {
        private final CampRepairStatus status;
        private final double hp;
        private final double maxHp;
        private final double restored;
        private final boolean brokenAfter;
        private final boolean revived;

        private CampRepairResult(CampRepairStatus status, double hp, double maxHp, double restored, boolean brokenAfter, boolean revived) {
            this.status = status;
            this.hp = hp;
            this.maxHp = maxHp;
            this.restored = restored;
            this.brokenAfter = brokenAfter;
            this.revived = revived;
        }

        public static CampRepairResult success(Camp camp, double restored, boolean brokenBefore) {
            boolean brokenAfter = camp.isBroken();
            boolean revived = brokenBefore && !brokenAfter;
            return new CampRepairResult(CampRepairStatus.SUCCESS, camp.getHp(), camp.getMaxHp(), restored, brokenAfter, revived);
        }

        public static CampRepairResult notFound() {
            return new CampRepairResult(CampRepairStatus.NOT_FOUND, 0.0, 0.0, 0.0, true, false);
        }

        public static CampRepairResult invalidAmount() {
            return new CampRepairResult(CampRepairStatus.INVALID_AMOUNT, 0.0, 0.0, 0.0, true, false);
        }

        public static CampRepairResult alreadyFull(Camp camp) {
            return new CampRepairResult(CampRepairStatus.ALREADY_FULL, camp.getHp(), camp.getMaxHp(), 0.0, camp.isBroken(), false);
        }

        public CampRepairStatus getStatus() {
            return status;
        }

        public double getHp() {
            return hp;
        }

        public double getMaxHp() {
            return maxHp;
        }

        public double getRestored() {
            return restored;
        }

        public boolean isBrokenAfter() {
            return brokenAfter;
        }

        public boolean wasRevived() {
            return revived;
        }
    }

    public enum CampMaintenanceStatus {
        SUCCESS,
        NOT_FOUND,
        INVALID
    }

    public static class CampMaintenanceResult {
        private final CampMaintenanceStatus status;
        private final Camp camp;
        private final long nextDue;
        private final long interval;

        private CampMaintenanceResult(CampMaintenanceStatus status, Camp camp, long nextDue, long interval) {
            this.status = status;
            this.camp = camp;
            this.nextDue = nextDue;
            this.interval = interval;
        }

        public static CampMaintenanceResult success(Camp camp, long nextDue, long interval) {
            return new CampMaintenanceResult(CampMaintenanceStatus.SUCCESS, camp, nextDue, interval);
        }

        public static CampMaintenanceResult notFound() {
            return new CampMaintenanceResult(CampMaintenanceStatus.NOT_FOUND, null, 0L, 0L);
        }

        public static CampMaintenanceResult invalid() {
            return new CampMaintenanceResult(CampMaintenanceStatus.INVALID, null, 0L, 0L);
        }

        public CampMaintenanceStatus getStatus() {
            return status;
        }

        public Camp getCamp() {
            return camp;
        }

        public long getNextDue() {
            return nextDue;
        }

        public long getInterval() {
            return interval;
        }
    }

    public static class CampMaintenanceInfo {
        private final Camp camp;
        private final long remainingMillis;
        private final long overdueMillis;
        private final boolean warning;
        private final boolean overdue;
        private final long nextDue;
        private final long interval;

        public CampMaintenanceInfo(Camp camp, long remainingMillis, long overdueMillis, boolean warning, boolean overdue, long nextDue, long interval) {
            this.camp = camp;
            this.remainingMillis = remainingMillis;
            this.overdueMillis = overdueMillis;
            this.warning = warning;
            this.overdue = overdue;
            this.nextDue = nextDue;
            this.interval = interval;
        }

        public Camp getCamp() {
            return camp;
        }

        public long getRemainingMillis() {
            return remainingMillis;
        }

        public long getOverdueMillis() {
            return overdueMillis;
        }

        public boolean isWarning() {
            return warning;
        }

        public boolean isOverdue() {
            return overdue;
        }

        public long getNextDue() {
            return nextDue;
        }

        public long getInterval() {
            return interval;
        }

        public double getFuelUnits() {
            if (interval <= 0L) {
                return camp == null ? 0.0 : camp.getFuel();
            }
            return Math.max(0.0, remainingMillis / (double) interval);
        }
    }

    public enum ProductionClaimStatus {
        SUCCESS,
        NO_PERMISSION,
        EMPTY,
        NO_ECONOMY
    }

    public static class ProductionClaimResult {
        private final ProductionClaimStatus status;
        private final double money;
        private final Map<String, Integer> items;
        private final boolean itemsDelivered;

        private ProductionClaimResult(ProductionClaimStatus status, double money, Map<String, Integer> items, boolean itemsDelivered) {
            this.status = status;
            this.money = money;
            this.items = items;
            this.itemsDelivered = itemsDelivered;
        }

        public static ProductionClaimResult success(double money, Map<String, Integer> items, boolean itemsDelivered) {
            return new ProductionClaimResult(ProductionClaimStatus.SUCCESS, money, items, itemsDelivered);
        }

        public static ProductionClaimResult empty() {
            return new ProductionClaimResult(ProductionClaimStatus.EMPTY, 0.0, Map.of(), false);
        }

        public static ProductionClaimResult noPermission() {
            return new ProductionClaimResult(ProductionClaimStatus.NO_PERMISSION, 0.0, Map.of(), false);
        }

        public static ProductionClaimResult noEconomy(Map<String, Integer> delivered, boolean itemsDelivered) {
            return new ProductionClaimResult(ProductionClaimStatus.NO_ECONOMY, 0.0, delivered, itemsDelivered);
        }

        public ProductionClaimStatus getStatus() { return status; }
        public double getMoney() { return money; }
        public Map<String, Integer> getItems() { return items; }
        public boolean isItemsDelivered() { return itemsDelivered; }
    }

    public enum CampDamageStatus {
        SUCCESS,
        BROKEN,
        NOT_FOUND,
        NOT_AT_WAR,
        INVALID
    }

    public Map<String, ModuleDefinition> getModuleDefinitions() {
        return Collections.unmodifiableMap(moduleDefinitions);
    }

    public ModuleDefinition getModuleDefinition(String key) {
        return key == null ? null : moduleDefinitions.get(normalizeModuleKey(key));
    }

    public boolean isModuleSupported(ModuleDefinition definition) {
        return definition != null;
    }

    public boolean isModuleActive(String moduleKey, StateManager.CampSectorInfo info) {
        if (moduleKey == null || info == null) {
            return false;
        }
        ModuleDefinition definition = getModuleDefinition(moduleKey);
        if (definition == null || !definition.enabled() || !isModuleSupported(definition)) {
            return false;
        }
        Camp camp = getCamp(info.stateName(), info.sectorName());
        return camp != null && camp.isModuleEnabled(moduleKey);
    }

    private String normalizeModuleKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT);
    }

    public record ModuleDefinition(String key, boolean enabled, String display, double cost, String costDisplay,
                                   String itemsDisplay, Map<StateManager.ItemDescriptor, Integer> items,
                                   ModuleEffect effect) { }

    public record ModuleEffect(String type, double value) { }

    public UpgradeTree getUpgradeTree(CampUpgradeType type) {
        return upgradeTrees.get(type);
    }

    public UpgradeTier getNextTier(Camp camp, CampUpgradeType type) {
        if (camp == null || type == null) {
            return null;
        }
        int current = getLevel(camp, type);
        return getTier(type, current + 1);
    }

    public UpgradeTier getTier(CampUpgradeType type, int level) {
        UpgradeTree tree = upgradeTrees.get(type);
        if (tree == null || !tree.enabled() || level <= 0) {
            return null;
        }
        return tree.tiers().get(level);
    }

    private int getLevel(Camp camp, CampUpgradeType type) {
        return switch (type) {
            case HP -> camp.getHpLevel();
            case FUEL -> camp.getFuelLevel();
            case HEAL -> camp.getHealLevel();
            case FATIGUE -> camp.getFatigueLevel();
            case STORAGE -> camp.getStorageLevel();
            case EFFICIENCY -> camp.getEfficiencyLevel();
            case BOUNDARY -> camp.getBoundaryLevel();
        };
    }

    public enum CampUpgradeType {
        HP("hp"),
        FUEL("fuel"),
        HEAL("heal"),
        FATIGUE("fatigue"),
        STORAGE("storage"),
        EFFICIENCY("efficiency"),
        BOUNDARY("boundary");

        private final String key;

        CampUpgradeType(String key) {
            this.key = key;
        }

        public String configKey() {
            return key;
        }
    }

    public record UpgradeTier(int level, double cost, String costDisplay, String itemsDisplay,
                              Map<StateManager.ItemDescriptor, Integer> items,
                              Double maxHp, Integer maxFuel, Double healRate, Integer fatigueAmplifier,
                              Double storedMoneyCap, Integer storedItemCap, Long productionIntervalSeconds,
                              Double boundaryRadiusBonus) { }

    public record UpgradeTree(CampUpgradeType type, boolean enabled, Map<Integer, UpgradeTier> tiers, String display) { }

    public static class CampDamageResult {
        private final CampDamageStatus status;
        private final double hp;
        private final double maxHp;
        private final boolean capital;
        private final long holdSeconds;

        private CampDamageResult(CampDamageStatus status, double hp, double maxHp, boolean capital, long holdSeconds) {
            this.status = status;
            this.hp = hp;
            this.maxHp = maxHp;
            this.capital = capital;
            this.holdSeconds = holdSeconds;
        }

        public static CampDamageResult success(double hp, double maxHp) {
            return new CampDamageResult(CampDamageStatus.SUCCESS, hp, maxHp, false, 0L);
        }

        public static CampDamageResult broken(Camp camp, boolean capital, long holdSeconds) {
            long timer = capital ? holdSeconds : 0L;
            return new CampDamageResult(CampDamageStatus.BROKEN, camp.getHp(), camp.getMaxHp(), capital, timer);
        }

        public static CampDamageResult notFound() {
            return new CampDamageResult(CampDamageStatus.NOT_FOUND, 0, 0, false, 0L);
        }

        public static CampDamageResult notAtWar() {
            return new CampDamageResult(CampDamageStatus.NOT_AT_WAR, 0, 0, false, 0L);
        }

        public static CampDamageResult invalid() {
            return new CampDamageResult(CampDamageStatus.INVALID, 0, 0, false, 0L);
        }

        public CampDamageStatus getStatus() {
            return status;
        }

        public double getHp() {
            return hp;
        }

        public double getMaxHp() {
            return maxHp;
        }

        public boolean isCapital() {
            return capital;
        }

        public long getHoldSeconds() {
            return holdSeconds;
        }
    }
}
