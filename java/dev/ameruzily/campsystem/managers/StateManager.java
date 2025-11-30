package dev.ameruzily.campsystem.managers;

import dev.ameruzily.campsystem.CampSystem;
import dev.ameruzily.campsystem.models.Camp;
import dev.ameruzily.campsystem.models.CampBoundary;
import dev.ameruzily.campsystem.models.Ideology;
import dev.lone.itemsadder.api.CustomStack;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class StateManager {
    private final CampSystem plugin;

    private final Map<UUID, String> playerState = new ConcurrentHashMap<>();
    private final Map<String, StateData> states = new ConcurrentHashMap<>();
    private final Map<UUID, Long> createCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sectorCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, InviteData> pendingInvites = new ConcurrentHashMap<>();
    private final Map<UUID, JoinRequest> pendingJoinRequests = new ConcurrentHashMap<>();
    private final Map<String, SectorGiftRequest> pendingSectorGifts = new ConcurrentHashMap<>();
    private final Map<UUID, PendingCampData> pendingCampPlacement = new ConcurrentHashMap<>();
    private final Map<UUID, Long> onlineProgress = new ConcurrentHashMap<>();
    private final Map<UUID, TaxRecord> taxRecords = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionAttachment> ideologyAttachments = new ConcurrentHashMap<>();
    private final Map<String, Long> capitalMoveCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, TpaRequest> pendingTeleports = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportCooldowns = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(1);

    private BukkitTask taxTask;
    private long taxSampleTicks = 1200L;

    private static final Set<String> RESERVED_STATE_NAMES = Set.of("accept", "deny");

    public StateManager(CampSystem plugin) { this.plugin = plugin; }

    public void startBankTask() {
        if (taxTask != null) {
            taxTask.cancel();
        }
        taxSampleTicks = Math.max(1L, plugin.getConfig().getLong("bank.tax.sample-period-ticks", 1200L));
        taxTask = Bukkit.getScheduler().runTaskTimer(plugin, this::handleTaxSample, taxSampleTicks, taxSampleTicks);
    }

    public void stopBankTask() {
        if (taxTask != null) {
            taxTask.cancel();
            taxTask = null;
        }
    }

    private void handleTaxSample() {
        if (!plugin.getConfig().getBoolean("bank.enabled", true)) {
            onlineProgress.clear();
            taxRecords.clear();
            return;
        }

        Economy economy = plugin.economy();
        if (economy == null) {
            return;
        }

        long intervalMinutes = Math.max(1L, plugin.getConfig().getLong("bank.tax.interval-minutes", 30));
        long intervalMillis = intervalMinutes * 60_000L;
        if (intervalMillis <= 0) {
            return;
        }

        long deltaMillis = taxSampleTicks * 50L;
        Set<UUID> currentlyOnline = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            currentlyOnline.add(player.getUniqueId());

            String stateName = getStateName(player);
            if (stateName == null) {
                onlineProgress.remove(player.getUniqueId());
                continue;
            }

            StateData state = states.get(stateName);
            if (state == null) {
                onlineProgress.remove(player.getUniqueId());
                continue;
            }

            double taxAmount = Math.max(0.0, state.taxAmount);
            if (taxAmount <= 0) {
                onlineProgress.remove(player.getUniqueId());
                continue;
            }

            long progress = onlineProgress.getOrDefault(player.getUniqueId(), 0L) + deltaMillis;

            while (progress >= intervalMillis) {
                progress -= intervalMillis;
                registerTaxInterval(player, state, taxAmount);
            }

            onlineProgress.put(player.getUniqueId(), progress);
        }

        onlineProgress.keySet().removeIf(uuid -> !currentlyOnline.contains(uuid));
    }

    private void registerTaxInterval(Player player, StateData state, double amount) {
        TaxRecord record = taxRecords.computeIfAbsent(player.getUniqueId(), id -> new TaxRecord());
        record.dueAmount += amount;
        attemptTax(player, state, record);
    }

    private void attemptTax(Player player, StateData state, TaxRecord record) {
        Economy economy = plugin.economy();
        if (economy == null) {
            return;
        }

        double due = Math.max(0.0, record.dueAmount);
        if (due <= 0) {
            taxRecords.remove(player.getUniqueId());
            return;
        }

        boolean success = false;
        if (economy.has(player, due)) {
            EconomyResponse response = economy.withdrawPlayer(player, due);
            success = response != null && response.transactionSuccess();
        }

        if (success) {
            state.bankBalance += due;
            recordTransaction(state, BankTransaction.tax(player.getUniqueId(), due, state.bankBalance));
            taxRecords.remove(player.getUniqueId());
            plugin.lang().send(player, "bank.tax-paid", Map.of(
                    "amount", formatMoney(due),
                    "balance", formatMoney(state.bankBalance),
                    "state", state.name
            ));
            return;
        }

        record.failedAttempts++;
        plugin.lang().send(player, "bank.tax-failed", Map.of(
                "amount", formatMoney(due),
                "attempt", String.valueOf(record.failedAttempts),
                "state", state.name
        ));

        if (record.failedAttempts >= 3) {
            handleTaxRemoval(player, state, due);
            taxRecords.remove(player.getUniqueId());
        } else {
            taxRecords.put(player.getUniqueId(), record);
        }
    }

    private void handleTaxRemoval(Player player, StateData state, double due) {
        UUID playerId = player.getUniqueId();
        boolean wasCaptain = state.captain.equals(playerId);
        state.members.remove(playerId);
        if (!wasCaptain) {
            reassignSectors(state, playerId, state.captain);
        }

        cleanupPlayer(playerId);

        plugin.lang().send(player, "bank.tax-kick", Map.of(
                "state", state.name,
                "amount", formatMoney(due)
        ));

        if (wasCaptain) {
            if (state.members.isEmpty()) {
                states.remove(state.name);
                plugin.war().removeStateCamps(state.name);
                if (plugin.holograms() != null) {
                    plugin.holograms().removeState(state.name);
                }
                return;
            } else {
                UUID newCaptain = state.members.iterator().next();
                state.captain = newCaptain;
                reassignSectors(state, playerId, newCaptain);
                String newName = Bukkit.getOfflinePlayer(newCaptain).getName();
                if (newName == null || newName.isEmpty()) {
                    newName = plugin.lang().messageOrDefault("bank.log-unknown", "未知");
                }
                Map<String, String> vars = Map.of("player", newName);
                for (UUID memberId : state.members) {
                    Player online = Bukkit.getPlayer(memberId);
                    if (online != null) {
                        plugin.lang().send(online, "state.captain-transfer", vars);
                    }
                }
            }
        }

        Player captain = Bukkit.getPlayer(state.captain);
        if (captain != null) {
            plugin.lang().send(captain, "bank.tax-kick-notify", Map.of(
                    "player", player.getName(),
                    "state", state.name
            ));
        }
    }

    private void clearTracking(UUID playerId) {
        onlineProgress.remove(playerId);
        taxRecords.remove(playerId);
    }

    private void cleanupPlayer(UUID playerId) {
        clearTracking(playerId);
        clearIdeologyPermission(playerId);
        pendingCampPlacement.remove(playerId);
        pendingInvites.remove(playerId);
        pendingJoinRequests.remove(playerId);

        String stateName = playerState.remove(playerId);
        if (stateName != null) {
            StateData data = states.get(stateName);
            if (data != null) {
                data.members.remove(playerId);
            }
        }
        markDirty();
    }

    private void clearIdeologyPermission(UUID playerId) {
        if (playerId == null) {
            return;
        }
        PermissionAttachment attachment = ideologyAttachments.remove(playerId);
        if (attachment != null) {
            attachment.remove();
        }
    }

    private void refreshIdeologyPermission(UUID playerId) {
        if (playerId == null) {
            return;
        }

        clearIdeologyPermission(playerId);

        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }

        String stateName = playerState.get(playerId);
        if (stateName == null) {
            return;
        }

        String ideologyId = getIdeologyId(stateName);
        if (ideologyId == null) {
            return;
        }

        Ideology ideology = plugin.ideology().get(ideologyId);
        if (ideology == null) {
            return;
        }

        String permission = ideology.getPermission();
        if (permission == null || permission.isEmpty()) {
            return;
        }

        PermissionAttachment attachment = player.addAttachment(plugin);
        if (attachment != null) {
            attachment.setPermission(permission, true);
            ideologyAttachments.put(playerId, attachment);
        }
    }

    private void refreshIdeologyPermissions(StateData data) {
        if (data == null) {
            return;
        }
        for (UUID member : data.members) {
            refreshIdeologyPermission(member);
        }
        refreshIdeologyPermission(data.captain);
    }

    public void refreshIdeologyPermissionFor(UUID playerId) {
        refreshIdeologyPermission(playerId);
    }

    private void markDirty() {
        CampInfoManager info = plugin.campInfo();
        if (info != null) {
            info.markDirty();
        }
    }

    private boolean isItemsAdderAvailable() {
        return Bukkit.getPluginManager().getPlugin("ItemsAdder") != null;
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void reassignSectors(StateData state, UUID from, UUID to) {
        if (state == null || from == null) {
            return;
        }
        for (SectorData sector : state.sectors.values()) {
            UUID owner = sector.getOwner();
            if (owner != null && owner.equals(from)) {
                sector.setOwner(to);
            }
        }
    }

    private String clearGovernorOwnership(StateData state, UUID governorId, String ignoreSector) {
        if (state == null || governorId == null) {
            return null;
        }
        String removed = null;
        for (SectorData sector : state.sectors.values()) {
            if (sector == null) {
                continue;
            }
            if (ignoreSector != null && ignoreSector.equalsIgnoreCase(sector.getName())) {
                continue;
            }
            UUID owner = sector.getOwner();
            if (owner != null && owner.equals(governorId)) {
                sector.setOwner(null);
                if (removed == null) {
                    removed = sector.getName();
                }
            }
        }
        return removed;
    }

    private String resolveUniqueSectorName(StateData state, String desired) {
        if (state == null || desired == null) {
            return desired;
        }

        String candidate = desired;
        int index = 2;
        while (resolveSectorName(state, candidate) != null) {
            candidate = desired + index++;
        }
        return candidate;
    }

    private boolean hasOwnedSector(StateData state, UUID playerId) {
        if (state == null || playerId == null) {
            return false;
        }
        for (SectorData sector : state.sectors.values()) {
            if (playerId.equals(sector.getOwner())) {
                return true;
            }
        }
        return false;
    }

    // 创建国家
    public void createState(Player player, String stateName, String sectorName) {
        var cfg = plugin.getConfig();
        String world = player.getWorld().getName();

        if (cfg.getStringList("creation.restricted-worlds").contains(world)) {
            plugin.lang().send(player, "state.restricted-world");
            return;
        }

        if (pendingCampPlacement.containsKey(player.getUniqueId())) {
            plugin.lang().send(player, "state.pending-placement");
            return;
        }

        if (hasState(player)) {
            plugin.lang().send(player, "state.already-member");
            return;
        }

        if (!hasRequiredCampItem(player)) {
            plugin.lang().send(player, "state.no-camp-item");
            return;
        }

        Location location = player.getLocation();
        double radius = Math.max(1.0, plugin.getConfig().getDouble("camp.radius", 16.0));
        CampSectorInfo occupied = findCampInRadius(location, radius);
        if (occupied != null) {
            plugin.lang().send(player, "state.create-inside-other", Map.of(
                    "state", occupied.stateName(),
                    "sector", occupied.sectorName()
            ));
            return;
        }

        CreationSettings settings = resolveCreationSettings(true);
        long now = System.currentTimeMillis();
        long remaining = getCreationCooldownRemaining(createCooldown, player.getUniqueId(), settings.cooldownMs());
        if (remaining > 0) {
            plugin.lang().send(player, "state.create-cooldown", Map.of(
                    "time", plugin.war().formatDuration(remaining)
            ));
            return;
        }

        Economy economy = plugin.economy();
        double cost = settings.cost();
        if (cost > 0.0 && economy == null) {
            plugin.lang().send(player, "state.create-no-economy");
            return;
        }
        if (economy != null && cost > 0.0 && !economy.has(player, cost)) {
            plugin.lang().send(player, "state.create-insufficient-funds", Map.of(
                    "cost", formatMoney(cost)
            ));
            return;
        }
        Map<ItemDescriptor, Integer> requiredItems = settings.items();
        if (!requiredItems.isEmpty() && !hasRequiredMaterials(player, requiredItems)) {
            plugin.lang().send(player, "state.create-missing-items", Map.of(
                    "items", describeMaterials(requiredItems)
            ));
            return;
        }

        boolean allowCustom = cfg.getBoolean("creation.allow-custom-names", true);
        String trimmedState = stateName == null ? "" : stateName.trim();
        String trimmedSector = sectorName == null ? "" : sectorName.trim();

        AutoNames autoNames = null;
        if (!allowCustom || trimmedState.isEmpty() || trimmedSector.isEmpty()) {
            autoNames = generateAutoNames();
        }

        String targetStateName = autoNames != null ? autoNames.state() : trimmedState;
        String resolvedSectorName = autoNames != null ? autoNames.sector() : trimmedSector;

        if (states.containsKey(targetStateName)) {
            plugin.lang().send(player, "state.exists", Map.of("state", targetStateName));
            return;
        }

        if (isReservedStateName(targetStateName)) {
            plugin.lang().send(player, "state.name-reserved");
            return;
        }

        if (economy != null && cost > 0.0) {
            EconomyResponse withdrawal = economy.withdrawPlayer(player, cost);
            if (withdrawal == null || !withdrawal.transactionSuccess()) {
                plugin.lang().send(player, "state.create-payment-failed");
                return;
            }
        }
        if (!requiredItems.isEmpty()) {
            consumeMaterials(player, requiredItems);
        }

        StateData data = new StateData(targetStateName, player.getUniqueId());
        data.taxAmount = plugin.getConfig().getDouble("bank.tax.amount", 0.0);
        states.put(targetStateName, data);
        playerState.put(player.getUniqueId(), targetStateName);
        refreshIdeologyPermission(player.getUniqueId());
        clearTracking(player.getUniqueId());
        createCooldown.put(player.getUniqueId(), now);

        PendingCampData pending = new PendingCampData(targetStateName, resolvedSectorName);
        pending.setOwner(player.getUniqueId());
        pendingCampPlacement.put(player.getUniqueId(), pending);

        plugin.lang().sendActionBar(player, "state.create-success", Map.of("state", targetStateName));
        if (plugin.stateCreateSound() != null) {
            plugin.stateCreateSound().play(player);
        }
        plugin.lang().send(player, "state.place-instruction", Map.of("sector", resolvedSectorName));
        markDirty();
    }

    public void prepareNewSector(Player player, String sectorName) {
        var cfg = plugin.getConfig();
        String world = player.getWorld().getName();

        if (cfg.getStringList("creation.restricted-worlds").contains(world)) {
            plugin.lang().send(player, "state.restricted-world");
            return;
        }

        if (pendingCampPlacement.containsKey(player.getUniqueId())) {
            plugin.lang().send(player, "state.pending-placement");
            return;
        }

        if (!hasState(player)) {
            plugin.lang().send(player, "camp.not-found");
            return;
        }

        if (!hasRequiredCampItem(player)) {
            plugin.lang().send(player, "state.no-camp-item");
            return;
        }

        String stateName = getStateName(player);
        if (stateName == null) {
            plugin.lang().send(player, "camp.not-found");
            return;
        }

        StateData data = states.get(stateName);
        if (data == null) {
            plugin.lang().send(player, "camp.not-found");
            return;
        }

        if (!player.getUniqueId().equals(data.captain)) {
            plugin.lang().send(player, "state.sector-not-captain");
            return;
        }

        CreationSettings settings = resolveCreationSettings(false);
        long remaining = getCreationCooldownRemaining(sectorCooldown, player.getUniqueId(), settings.cooldownMs());
        if (remaining > 0) {
            plugin.lang().send(player, "state.sector-cooldown", Map.of(
                    "time", plugin.war().formatDuration(remaining)
            ));
            return;
        }

        Economy economy = plugin.economy();
        double cost = settings.cost();
        if (cost > 0.0 && economy == null) {
            plugin.lang().send(player, "state.sector-no-economy");
            return;
        }
        if (economy != null && cost > 0.0 && !economy.has(player, cost)) {
            plugin.lang().send(player, "state.sector-insufficient-funds", Map.of(
                    "cost", formatMoney(cost)
            ));
            return;
        }
        Map<ItemDescriptor, Integer> requiredItems = settings.items();
        if (!requiredItems.isEmpty() && !hasRequiredMaterials(player, requiredItems)) {
            plugin.lang().send(player, "state.sector-missing-items", Map.of(
                    "items", describeMaterials(requiredItems)
            ));
            return;
        }

        int maxSectors = resolveSectorLimit(data);
        if (data.sectors.size() >= maxSectors) {
            plugin.lang().send(player, "state.sector-limit", Map.of("limit", String.valueOf(maxSectors)));
            return;
        }

        boolean allowCustom = cfg.getBoolean("creation.allow-custom-names", true);
        String trimmedSector = sectorName == null ? "" : sectorName.trim();
        String resolvedSectorName;
        if (!allowCustom || trimmedSector.isEmpty()) {
            resolvedSectorName = generateAutoNames().sector();
        } else {
            resolvedSectorName = trimmedSector;
        }

        String existing = resolveSectorName(data, resolvedSectorName);
        if (existing != null) {
            plugin.lang().send(player, "state.sector-exists", Map.of("sector", existing));
            return;
        }

        if (economy != null && cost > 0.0) {
            EconomyResponse withdrawal = economy.withdrawPlayer(player, cost);
            if (withdrawal == null || !withdrawal.transactionSuccess()) {
                plugin.lang().send(player, "state.sector-payment-failed");
                return;
            }
        }
        if (!requiredItems.isEmpty()) {
            consumeMaterials(player, requiredItems);
        }

        PendingCampData pending = new PendingCampData(stateName, resolvedSectorName);
        pending.setOwner(player.getUniqueId());
        pendingCampPlacement.put(player.getUniqueId(), pending);
        sectorCooldown.put(player.getUniqueId(), System.currentTimeMillis());
        plugin.lang().send(player, "state.sector-create-start", Map.of("sector", resolvedSectorName));
    }

    public MoveSectorResult moveSector(Player player, String sectorName) {
        if (player == null) {
            return MoveSectorResult.of(MoveSectorStatus.NOT_IN_STATE, null, null);
        }

        UUID playerId = player.getUniqueId();
        String stateName = getStateName(player);
        if (stateName == null) {
            return MoveSectorResult.of(MoveSectorStatus.NOT_IN_STATE, null, null);
        }

        StateData data = states.get(stateName);
        if (data == null) {
            return MoveSectorResult.of(MoveSectorStatus.NOT_IN_STATE, null, null);
        }

        String resolved = resolveSectorName(data, sectorName);
        if (resolved == null) {
            return MoveSectorResult.of(MoveSectorStatus.NO_SUCH_SECTOR, stateName, sectorName);
        }

        SectorData sector = data.sectors.get(resolved);
        if (sector == null) {
            return MoveSectorResult.of(MoveSectorStatus.NO_SUCH_SECTOR, stateName, resolved);
        }

        if (!canManageSector(data, playerId, sector)) {
            return MoveSectorResult.of(MoveSectorStatus.NOT_AUTHORIZED, stateName, resolved);
        }

        if (data.capitalSector != null && data.capitalSector.equalsIgnoreCase(resolved)) {
            return MoveSectorResult.of(MoveSectorStatus.CAPITAL_SECTOR, stateName, resolved);
        }

        if (pendingCampPlacement.containsKey(playerId)) {
            return MoveSectorResult.of(MoveSectorStatus.PENDING_PLACEMENT, stateName, resolved);
        }

        Location location = sector.getLocation();
        if (location == null || location.getWorld() == null) {
            return MoveSectorResult.of(MoveSectorStatus.MISSING_LOCATION, stateName, resolved);
        }

        MoveSettings settings = resolveMoveSettings();
        Map<ItemDescriptor, Integer> requirements = settings.materials();
        String requirementText = describeMaterials(requirements);

        if (!hasRequiredMaterials(player, requirements)) {
            return MoveSectorResult.of(MoveSectorStatus.MISSING_ITEMS, stateName, resolved, settings.cost(), PaymentSource.NONE, requirementText);
        }

        double cost = Math.max(0.0, normalizeAmount(settings.cost()));
        PaymentSource source = PaymentSource.NONE;
        Economy economy = plugin.economy();
        boolean bankEnabled = plugin.getConfig().getBoolean("bank.enabled", true);

        if (cost > 0) {
            if (bankEnabled && data.bankBalance + 1e-6 >= cost) {
                source = PaymentSource.BANK;
            } else {
                if (economy == null) {
                    return MoveSectorResult.of(MoveSectorStatus.NO_ECONOMY, stateName, resolved, cost, PaymentSource.NONE, requirementText);
                }
                if (!economy.has(player, cost)) {
                    return MoveSectorResult.of(MoveSectorStatus.INSUFFICIENT_FUNDS, stateName, resolved, cost, PaymentSource.NONE, requirementText);
                }
                source = PaymentSource.PLAYER;
            }
        }

        if (!hasInventorySpaceForToken(player, settings.material())) {
            return MoveSectorResult.of(MoveSectorStatus.INVENTORY_FULL, stateName, resolved, cost, source, requirementText);
        }

        EconomyResponse withdrawal = null;
        if (source == PaymentSource.PLAYER && cost > 0) {
            withdrawal = economy.withdrawPlayer(player, cost);
            if (withdrawal == null || !withdrawal.transactionSuccess()) {
                return MoveSectorResult.of(MoveSectorStatus.PAYMENT_FAILED, stateName, resolved, cost, PaymentSource.NONE, requirementText);
            }
        }

        Camp stored = plugin.war().removeCamp(stateName, resolved);
        if (stored == null && plugin.holograms() != null) {
            plugin.holograms().removeCamp(stateName, resolved);
        }
        if (plugin.protection() != null) {
            plugin.protection().clearCampEffects(stateName, resolved);
        }

        breakCampBlock(location, false);

        sector.setLocation(null);

        if (!requirements.isEmpty()) {
            consumeMaterials(player, requirements);
        }

        if (source == PaymentSource.BANK && cost > 0) {
            data.bankBalance = Math.max(0.0, data.bankBalance - cost);
            recordTransaction(data, BankTransaction.expense(playerId, cost, data.bankBalance));
        }

        PendingCampData pending = new PendingCampData(stateName, resolved);
        UUID owner = sector.getOwner() != null ? sector.getOwner() : playerId;
        pending.setOwner(owner);
        pending.setRelocation(true);
        pending.setRelocationMaterial(settings.material());
        pending.setRelocationTokenName(settings.displayName());
        if (stored != null) {
            pending.setStoredCamp(stored);
        }
        pendingCampPlacement.put(playerId, pending);

        ItemStack token = createRelocationToken(settings);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(token);
        if (!leftovers.isEmpty()) {
            Location drop = player.getLocation();
            if (drop != null && drop.getWorld() != null) {
                drop.getWorld().dropItemNaturally(drop, token);
            }
        }

        markDirty();
        return MoveSectorResult.of(MoveSectorStatus.SUCCESS, stateName, resolved, cost, source, requirementText);
    }

    public AssignGovernorResult assignGovernor(Player player, String targetName, String sectorInput) {
        if (player == null) {
            return AssignGovernorResult.of(AssignGovernorStatus.NOT_IN_STATE, null, null, null, null, null);
        }

        UUID actorId = player.getUniqueId();
        String stateName = getStateName(player);
        if (stateName == null) {
            return AssignGovernorResult.of(AssignGovernorStatus.NOT_IN_STATE, null, null, null, null, null);
        }

        StateData data = states.get(stateName);
        if (data == null) {
            return AssignGovernorResult.of(AssignGovernorStatus.NOT_IN_STATE, null, null, null, null, null);
        }

        if (!data.captain.equals(actorId)) {
            return AssignGovernorResult.of(AssignGovernorStatus.NOT_CAPTAIN, stateName, null, null, null, null);
        }

        String resolved = resolveSectorName(data, sectorInput);
        if (resolved == null) {
            return AssignGovernorResult.of(AssignGovernorStatus.NO_SUCH_SECTOR, stateName, null, null, null, null);
        }

        SectorData sector = data.sectors.get(resolved);
        if (sector == null) {
            return AssignGovernorResult.of(AssignGovernorStatus.NO_SUCH_SECTOR, stateName, resolved, null, null, null);
        }

        Player target = targetName == null ? null : Bukkit.getPlayer(targetName);
        if (target == null) {
            return AssignGovernorResult.of(AssignGovernorStatus.PLAYER_NOT_FOUND, stateName, resolved, targetName, null, null);
        }

        UUID targetId = target.getUniqueId();
        if (!data.members.contains(targetId) || !stateName.equals(playerState.get(targetId))) {
            return AssignGovernorResult.of(AssignGovernorStatus.PLAYER_NOT_MEMBER, stateName, resolved, target.getName(), targetId, null);
        }

        if (targetId.equals(sector.getOwner())) {
            return AssignGovernorResult.of(AssignGovernorStatus.ALREADY_OWNER, stateName, resolved, target.getName(), targetId, null);
        }

        String previous = clearGovernorOwnership(data, targetId, resolved);
        sector.setOwner(targetId);
        markDirty();
        return AssignGovernorResult.of(AssignGovernorStatus.SUCCESS, stateName, resolved, target.getName(), targetId, previous);
    }

    public RemoveGovernorResult removeGovernor(Player player, String targetName) {
        if (player == null) {
            return RemoveGovernorResult.of(RemoveGovernorStatus.NOT_IN_STATE, null, null, null);
        }

        UUID actorId = player.getUniqueId();
        String stateName = getStateName(player);
        if (stateName == null) {
            return RemoveGovernorResult.of(RemoveGovernorStatus.NOT_IN_STATE, null, null, null);
        }

        StateData data = states.get(stateName);
        if (data == null) {
            return RemoveGovernorResult.of(RemoveGovernorStatus.NOT_IN_STATE, null, null, null);
        }

        if (!data.captain.equals(actorId)) {
            return RemoveGovernorResult.of(RemoveGovernorStatus.NOT_CAPTAIN, null, null, null);
        }

        Player target = targetName == null ? null : Bukkit.getPlayer(targetName);
        if (target == null) {
            return RemoveGovernorResult.of(RemoveGovernorStatus.PLAYER_NOT_FOUND, null, targetName, null);
        }

        UUID targetId = target.getUniqueId();
        if (!data.members.contains(targetId) || !stateName.equals(playerState.get(targetId))) {
            return RemoveGovernorResult.of(RemoveGovernorStatus.PLAYER_NOT_MEMBER, null, target.getName(), targetId);
        }

        String removed = clearGovernorOwnership(data, targetId, null);
        if (removed == null) {
            return RemoveGovernorResult.of(RemoveGovernorStatus.NOT_GOVERNOR, null, target.getName(), targetId);
        }

        markDirty();
        return RemoveGovernorResult.of(RemoveGovernorStatus.SUCCESS, stateName, removed, targetId);
    }

    public RemoveSectorResult removeSector(Player player, String sectorName) {
        if (player == null) {
            return RemoveSectorResult.of(RemoveSectorStatus.NOT_IN_STATE, null, null, null, false);
        }

        UUID playerId = player.getUniqueId();
        String stateName = getStateName(player);
        if (stateName == null) {
            return RemoveSectorResult.of(RemoveSectorStatus.NOT_IN_STATE, null, null, null, false);
        }

        StateData data = states.get(stateName);
        if (data == null) {
            return RemoveSectorResult.of(RemoveSectorStatus.NOT_IN_STATE, null, null, null, false);
        }

        String resolved = resolveSectorName(data, sectorName);
        if (resolved == null) {
            return RemoveSectorResult.of(RemoveSectorStatus.NO_SUCH_SECTOR, stateName, sectorName, null, false);
        }

        SectorData sector = data.sectors.get(resolved);
        if (sector == null) {
            return RemoveSectorResult.of(RemoveSectorStatus.NO_SUCH_SECTOR, stateName, resolved, null, false);
        }

        if (!canManageSector(data, playerId, sector)) {
            return RemoveSectorResult.of(RemoveSectorStatus.NOT_AUTHORIZED, stateName, resolved, null, false);
        }

        if (data.capitalSector != null && data.capitalSector.equalsIgnoreCase(resolved)) {
            return RemoveSectorResult.of(RemoveSectorStatus.CAPITAL_SECTOR, stateName, resolved, null, false);
        }

        Location location = sector.getLocation();
        plugin.war().removeCamp(stateName, resolved);
        if (location != null && location.getWorld() != null) {
            dropCampBlock(location);
        }

        data.sectors.remove(resolved);

        String newCapital = null;
        boolean capitalCleared = false;
        if (data.capitalSector != null && data.capitalSector.equalsIgnoreCase(resolved)) {
            if (data.sectors.isEmpty()) {
                data.capitalSector = null;
                capitalCleared = true;
            } else {
                newCapital = data.sectors.keySet().iterator().next();
                data.capitalSector = newCapital;
                plugin.war().refreshMaintenanceSchedule(stateName, newCapital);
            }
        }

        markDirty();
        return RemoveSectorResult.of(RemoveSectorStatus.SUCCESS, stateName, resolved, newCapital, capitalCleared);
    }

    public void disbandState(String stateName, String messageKey, Map<String, String> vars) {
        if (stateName == null) {
            return;
        }
        StateData data = states.remove(stateName);
        if (data == null) {
            return;
        }

        Map<String, String> messageVars = new HashMap<>();
        messageVars.put("state", stateName);
        if (vars != null) {
            messageVars.putAll(vars);
        }

        for (SectorData sector : data.sectors.values()) {
            Location location = sector.getLocation();
            if (location != null) {
                dropCampBlock(location);
                if (plugin.holograms() != null) {
                    plugin.holograms().removeCamp(stateName, sector.getName());
                }
                if (plugin.protection() != null) {
                    plugin.protection().clearCampEffects(stateName, sector.getName());
                }
            }
        }

        Set<UUID> members = new HashSet<>(data.members);
        for (UUID member : members) {
            Player online = Bukkit.getPlayer(member);
            if (online != null) {
                if (messageKey != null) {
                    plugin.lang().send(online, messageKey, messageVars);
                } else {
                    plugin.lang().send(online, "state.disbanded", messageVars);
                }
            }
            cleanupPlayer(member);
        }

        plugin.war().removeStateCamps(stateName);
        if (plugin.holograms() != null) {
            plugin.holograms().removeState(stateName);
        }
        markDirty();
    }

    // 删除国家
    public void deleteState(Player player) {
        String state = playerState.get(player.getUniqueId());
        if (state == null) {
            plugin.lang().send(player, "camp.not-found");
            return;
        }
        StateData data = states.get(state);
        if (data == null || !data.captain.equals(player.getUniqueId())) {
            plugin.lang().send(player, "general.no-permission");
            return;
        }

        if (plugin.war().isStateAtWar(state)) {
            plugin.lang().send(player, "state.delete-war");
            return;
        }

        Set<UUID> members = new HashSet<>(data.members);
        for (UUID member : members) {
            Player target = Bukkit.getPlayer(member);
            if (target != null && !member.equals(player.getUniqueId())) {
                plugin.lang().send(target, "state.disbanded", Map.of("state", state));
            }
            cleanupPlayer(member);
        }

        for (SectorData sector : data.sectors.values()) {
            Location location = sector.getLocation();
            if (plugin.holograms() != null) {
                plugin.holograms().removeCamp(state, sector.getName());
            }
            if (plugin.protection() != null) {
                plugin.protection().clearCampEffects(state, sector.getName());
            }
            if (location != null) {
                dropCampBlock(location);
            }
        }

        states.remove(state);
        plugin.war().removeStateCamps(state);
        if (plugin.holograms() != null) {
            plugin.holograms().removeState(state);
        }
        plugin.lang().send(player, "state.delete-success", Map.of("state", state));
        markDirty();
    }

    public String transferSector(String fromState, String toState, String sectorName) {
        if (fromState == null || toState == null || sectorName == null) {
            return null;
        }

        StateData source = findState(fromState);
        StateData target = findState(toState);
        if (source == null || target == null) {
            return null;
        }

        String resolved = resolveSectorName(source, sectorName);
        if (resolved == null) {
            return null;
        }

        if (source.capitalSector != null && source.capitalSector.equalsIgnoreCase(resolved)) {
            return null;
        }

        SectorData sector = source.sectors.remove(resolved);
        if (sector == null) {
            return null;
        }

        sector.setOwner(target.captain);
        String uniqueName = resolveUniqueSectorName(target, resolved);
        target.sectors.put(uniqueName, sector);
        if (target.capitalSector == null) {
            target.capitalSector = uniqueName;
        }

        markDirty();
        return uniqueName;
    }

    public void leaveState(Player player) {
        String state = playerState.get(player.getUniqueId());
        if (state == null) {
            plugin.lang().send(player, "camp.not-found");
            return;
        }

        StateData data = states.get(state);
        if (data == null) {
            cleanupPlayer(player.getUniqueId());
            plugin.lang().send(player, "camp.not-found");
            return;
        }

        if (data.captain.equals(player.getUniqueId())) {
            plugin.lang().send(player, "state.leave-captain");
            return;
        }

        if (!data.members.contains(player.getUniqueId())) {
            plugin.lang().send(player, "camp.not-found");
            return;
        }

        reassignSectors(data, player.getUniqueId(), data.captain);
        cleanupPlayer(player.getUniqueId());
        plugin.lang().send(player, "state.leave-success", Map.of("state", state));

        String playerName = player.getName();
        if (playerName == null || playerName.isEmpty()) {
            playerName = plugin.lang().messageOrDefault("bank.log-unknown", "未知");
        }
        Map<String, String> vars = Map.of(
                "player", playerName,
                "state", state
        );
        for (UUID member : data.members) {
            Player online = Bukkit.getPlayer(member);
            if (online != null) {
                plugin.lang().send(online, "state.member-left-notify", vars);
            }
        }
    }

    // 重命名国家/地区
    public boolean renameStateName(Player player, String newName) {
        if (player == null || newName == null) {
            return false;
        }

        String trimmed = newName.trim();
        if (trimmed.isEmpty()) {
            plugin.lang().send(player, "camp.not-found");
            return false;
        }

        String old = playerState.get(player.getUniqueId());
        if (old == null) {
            plugin.lang().send(player, "camp.not-found");
            return false;
        }

        StateData data = states.get(old);
        if (data == null || !data.captain.equals(player.getUniqueId())) {
            plugin.lang().send(player, "general.no-permission");
            return false;
        }

        if (isReservedStateName(trimmed)) {
            plugin.lang().send(player, "state.name-reserved");
            return false;
        }

        if (states.containsKey(trimmed)) {
            plugin.lang().send(player, "state.exists", Map.of("state", trimmed));
            return false;
        }

        states.remove(old);
        data.name = trimmed;
        states.put(trimmed, data);
        for (UUID member : data.members) {
            playerState.put(member, trimmed);
        }
        pendingCampPlacement.values().stream()
                .filter(pending -> pending.getState().equals(old))
                .forEach(pending -> pending.setState(trimmed));
        plugin.war().renameState(old, trimmed);
        if (plugin.holograms() != null) {
            plugin.holograms().renameState(old, trimmed);
        }
        plugin.lang().send(player, "state.rename-state-success", Map.of("new", trimmed));
        markDirty();
        return true;
    }

    public boolean renameSectorName(Player player, String newName) {
        if (player == null || newName == null) {
            return false;
        }

        String trimmed = newName.trim();
        if (trimmed.isEmpty()) {
            plugin.lang().send(player, "camp.not-found");
            return false;
        }

        String stateName = playerState.get(player.getUniqueId());
        if (stateName == null) {
            plugin.lang().send(player, "camp.not-found");
            return false;
        }

        StateData data = states.get(stateName);
        if (data == null) {
            plugin.lang().send(player, "camp.not-found");
            return false;
        }

        Location location = player.getLocation();
        if (location == null || location.getWorld() == null) {
            plugin.lang().send(player, "state.rename-sector-failed");
            return false;
        }
        double radius = Math.max(1.0, plugin.getConfig().getDouble("camp.radius", 16.0));
        CampSectorInfo info = findCampInRadius(location, radius);
        if (info == null || !stateName.equalsIgnoreCase(info.stateName())) {
            plugin.lang().send(player, "state.rename-sector-failed");
            return false;
        }

        Camp camp = plugin.war().getCamp(info.stateName(), info.sectorName());
        if (camp == null) {
            plugin.lang().send(player, "state.rename-sector-failed");
            return false;
        }

        return renameSector(player, camp, trimmed);
    }

    public boolean renameSector(Player player, Camp camp, String newName) {
        if (player == null || camp == null || newName == null) {
            return false;
        }

        String trimmed = newName.trim();
        if (trimmed.isEmpty()) {
            plugin.lang().send(player, "camp.not-found");
            return false;
        }

        String stateName = playerState.get(player.getUniqueId());
        if (stateName == null || !stateName.equalsIgnoreCase(camp.getStateName())) {
            plugin.lang().send(player, "camp.not-found");
            return false;
        }

        StateData data = states.get(stateName);
        if (data == null) {
            plugin.lang().send(player, "camp.not-found");
            return false;
        }

        String resolved = resolveSectorName(data, camp.getSectorName());
        if (resolved == null) {
            plugin.lang().send(player, "state.rename-sector-failed");
            return false;
        }

        SectorData sector = data.sectors.get(resolved);
        if (sector == null) {
            plugin.lang().send(player, "state.rename-sector-failed");
            return false;
        }

        if (!canManageSector(data, player.getUniqueId(), sector)) {
            plugin.lang().send(player, "state.sector-no-permission");
            return false;
        }

        if (resolved.equalsIgnoreCase(trimmed)) {
            plugin.lang().send(player, "state.rename-sector-success", Map.of("new", trimmed));
            if (data.capitalSector != null && data.capitalSector.equalsIgnoreCase(resolved)) {
                plugin.lang().sendActionBar(player, "state.capital-set", Map.of("sector", trimmed));
                if (plugin.capitalSetSound() != null) {
                    plugin.capitalSetSound().play(player);
                }
            }
            return true;
        }

        String duplicate = resolveSectorName(data, trimmed);
        if (duplicate != null && !duplicate.equals(resolved)) {
            plugin.lang().send(player, "state.sector-exists", Map.of("sector", trimmed));
            return false;
        }

        Location sectorLocation = sector.getLocation();
        UUID owner = sector.getOwner();

        data.sectors.remove(resolved);
        SectorData renamed = new SectorData(trimmed, sectorLocation, owner);
        data.sectors.put(trimmed, renamed);

        boolean capital = data.capitalSector != null && data.capitalSector.equalsIgnoreCase(resolved);
        if (capital) {
            data.capitalSector = trimmed;
        }

        plugin.war().renameSector(stateName, resolved, trimmed);
        plugin.war().refreshMaintenanceSchedule(stateName, trimmed);
        if (plugin.holograms() != null) {
            plugin.holograms().renameSector(stateName, resolved, trimmed, sectorLocation);
        }

        plugin.lang().send(player, "state.rename-sector-success", Map.of("new", trimmed));
        if (capital) {
            plugin.lang().sendActionBar(player, "state.capital-set", Map.of("sector", trimmed));
            if (plugin.capitalSetSound() != null) {
                plugin.capitalSetSound().play(player);
            }
        }
        markDirty();
        return true;
    }

    public CapitalMoveResponse moveCapital(Player player, String targetSector, boolean emergency) {
        if (player == null) {
            return CapitalMoveResponse.of(CapitalMoveStatus.NOT_IN_STATE, null, 0.0, PaymentSource.NONE, "", 0L);
        }

        String stateName = playerState.get(player.getUniqueId());
        if (stateName == null) {
            return CapitalMoveResponse.of(CapitalMoveStatus.NOT_IN_STATE, null, 0.0, PaymentSource.NONE, "", 0L);
        }

        StateData data = states.get(stateName);
        if (data == null) {
            return CapitalMoveResponse.of(CapitalMoveStatus.NOT_IN_STATE, null, 0.0, PaymentSource.NONE, "", 0L);
        }

        if (!data.captain.equals(player.getUniqueId())) {
            return CapitalMoveResponse.of(CapitalMoveStatus.NOT_CAPTAIN, null, 0.0, PaymentSource.NONE, "", 0L);
        }

        String resolved = resolveSectorName(data, targetSector);
        if (resolved == null) {
            return CapitalMoveResponse.of(CapitalMoveStatus.NO_SUCH_SECTOR, null, 0.0, PaymentSource.NONE, "", 0L);
        }

        if (data.capitalSector != null && data.capitalSector.equalsIgnoreCase(resolved)) {
            return CapitalMoveResponse.of(CapitalMoveStatus.ALREADY_CAPITAL, resolved, 0.0, PaymentSource.NONE, "", 0L);
        }

        if (!emergency && plugin.war().isStateAtWar(stateName)) {
            return CapitalMoveResponse.of(CapitalMoveStatus.IN_WAR, resolved, 0.0, PaymentSource.NONE, "", 0L);
        }

        long cooldownMs = emergency ? 0L : getCapitalMoveCooldownRemaining(stateName);
        if (!emergency && cooldownMs > 0L) {
            return CapitalMoveResponse.of(CapitalMoveStatus.COOLDOWN, resolved, 0.0, PaymentSource.NONE, "", cooldownMs);
        }

        CapitalMoveSettings settings = resolveCapitalMoveSettings(emergency);
        Map<ItemDescriptor, Integer> requirements = settings.materials();
        String requirementText = describeMaterials(requirements);

        if (!hasRequiredMaterials(player, requirements)) {
            return CapitalMoveResponse.of(CapitalMoveStatus.MISSING_ITEMS, resolved, settings.cost(), PaymentSource.NONE, requirementText, 0L);
        }

        double cost = Math.max(0.0, normalizeAmount(settings.cost()));
        PaymentSource source = PaymentSource.NONE;
        Economy economy = plugin.economy();
        boolean bankEnabled = plugin.getConfig().getBoolean("bank.enabled", true);

        if (cost > 0.0) {
            if (bankEnabled && data.bankBalance + 1e-6 >= cost) {
                source = PaymentSource.BANK;
            } else {
                if (economy == null) {
                    return CapitalMoveResponse.of(CapitalMoveStatus.NO_ECONOMY, resolved, cost, PaymentSource.NONE, requirementText, 0L);
                }
                if (!economy.has(player, cost)) {
                    return CapitalMoveResponse.of(CapitalMoveStatus.INSUFFICIENT_FUNDS, resolved, cost, PaymentSource.NONE, requirementText, 0L);
                }
                source = PaymentSource.PLAYER;
            }
        }

        EconomyResponse withdrawal = null;
        if (source == PaymentSource.PLAYER && cost > 0.0) {
            withdrawal = economy.withdrawPlayer(player, cost);
            if (withdrawal == null || !withdrawal.transactionSuccess()) {
                return CapitalMoveResponse.of(CapitalMoveStatus.PAYMENT_FAILED, resolved, cost, PaymentSource.NONE, requirementText, 0L);
            }
        }

        String previous = data.capitalSector;
        data.capitalSector = resolved;

        Map<String, String> vars = Map.of("sector", resolved);
        for (UUID member : data.members) {
            Player online = Bukkit.getPlayer(member);
            if (online != null) {
                plugin.lang().send(online, emergency ? "state.capital-emergency" : "state.capital-moved", vars);
            }
        }

        plugin.war().refreshMaintenanceSchedule(stateName, resolved);
        if (previous != null && !previous.equalsIgnoreCase(resolved)) {
            plugin.war().refreshMaintenanceSchedule(stateName, previous);
        }

        if (!requirements.isEmpty()) {
            consumeMaterials(player, requirements);
        }

        if (source == PaymentSource.BANK && cost > 0.0) {
            data.bankBalance = Math.max(0.0, data.bankBalance - cost);
            recordTransaction(data, BankTransaction.expense(player.getUniqueId(), cost, data.bankBalance));
        }

        if (!emergency) {
            setCapitalMoveCooldown(stateName);
        }

        markDirty();
        return CapitalMoveResponse.of(CapitalMoveStatus.SUCCESS, resolved, cost, source, requirementText, 0L);
    }

    public BankActionResponse deposit(Player player, double amount) {
        if (!plugin.getConfig().getBoolean("bank.enabled", true)) {
            return BankActionResponse.of(BankResult.DISABLED, 0, 0);
        }

        if (player == null) {
            return BankActionResponse.of(BankResult.NO_STATE, 0, 0);
        }

        Economy economy = plugin.economy();
        if (economy == null) {
            return BankActionResponse.of(BankResult.NO_ECONOMY, 0, 0);
        }

        String stateName = getStateName(player);
        if (stateName == null) {
            return BankActionResponse.of(BankResult.NO_STATE, 0, 0);
        }

        StateData state = states.get(stateName);
        if (state == null) {
            return BankActionResponse.of(BankResult.NO_STATE, 0, 0);
        }

        double normalized = normalizeAmount(amount);
        if (normalized <= 0) {
            return BankActionResponse.of(BankResult.INVALID_AMOUNT, 0, state.bankBalance);
        }

        if (!economy.has(player, normalized)) {
            return BankActionResponse.of(BankResult.INSUFFICIENT_PLAYER_FUNDS, normalized, state.bankBalance);
        }

        EconomyResponse response = economy.withdrawPlayer(player, normalized);
        if (response == null || !response.transactionSuccess()) {
            return BankActionResponse.of(BankResult.FAILED_TRANSACTION, normalized, state.bankBalance);
        }

        state.bankBalance += normalized;
        recordTransaction(state, BankTransaction.deposit(player.getUniqueId(), normalized, state.bankBalance));
        return BankActionResponse.of(BankResult.SUCCESS, normalized, state.bankBalance);
    }

    public BankActionResponse withdraw(Player player, double amount) {
        if (!plugin.getConfig().getBoolean("bank.enabled", true)) {
            return BankActionResponse.of(BankResult.DISABLED, 0, 0);
        }

        if (player == null) {
            return BankActionResponse.of(BankResult.NO_STATE, 0, 0);
        }

        Economy economy = plugin.economy();
        if (economy == null) {
            return BankActionResponse.of(BankResult.NO_ECONOMY, 0, 0);
        }

        String stateName = getStateName(player);
        if (stateName == null) {
            return BankActionResponse.of(BankResult.NO_STATE, 0, 0);
        }

        StateData state = states.get(stateName);
        if (state == null) {
            return BankActionResponse.of(BankResult.NO_STATE, 0, 0);
        }

        if (!state.captain.equals(player.getUniqueId())) {
            return BankActionResponse.of(BankResult.NOT_CAPTAIN, 0, state.bankBalance);
        }

        double normalized = normalizeAmount(amount);
        if (normalized <= 0) {
            return BankActionResponse.of(BankResult.INVALID_AMOUNT, 0, state.bankBalance);
        }

        if (state.bankBalance + 1e-6 < normalized) {
            return BankActionResponse.of(BankResult.INSUFFICIENT_BANK_FUNDS, normalized, state.bankBalance);
        }

        EconomyResponse response = economy.depositPlayer(player, normalized);
        if (response == null || !response.transactionSuccess()) {
            return BankActionResponse.of(BankResult.FAILED_TRANSACTION, normalized, state.bankBalance);
        }

        state.bankBalance = Math.max(0.0, state.bankBalance - normalized);
        recordTransaction(state, BankTransaction.withdraw(player.getUniqueId(), normalized, state.bankBalance));
        return BankActionResponse.of(BankResult.SUCCESS, normalized, state.bankBalance);
    }

    public List<BankTransaction> getTransactions(String stateName) {
        StateData state = states.get(stateName);
        if (state == null) {
            return List.of();
        }
        return List.copyOf(state.transactions);
    }

    public double getTaxAmount(String stateName) {
        StateData state = states.get(stateName);
        if (state == null) {
            return 0.0;
        }
        return Math.max(0.0, state.taxAmount);
    }

    public TaxUpdateResponse setTaxAmount(Player player, double amount) {
        if (!plugin.getConfig().getBoolean("bank.enabled", true)) {
            return TaxUpdateResponse.of(TaxUpdateStatus.DISABLED, 0.0);
        }

        if (player == null) {
            return TaxUpdateResponse.of(TaxUpdateStatus.NO_STATE, 0.0);
        }

        String stateName = getStateName(player);
        if (stateName == null) {
            return TaxUpdateResponse.of(TaxUpdateStatus.NO_STATE, 0.0);
        }

        StateData state = states.get(stateName);
        if (state == null) {
            return TaxUpdateResponse.of(TaxUpdateStatus.NO_STATE, 0.0);
        }

        if (!state.captain.equals(player.getUniqueId())) {
            return TaxUpdateResponse.of(TaxUpdateStatus.NOT_CAPTAIN, state.taxAmount);
        }

        double normalized = normalizeAmount(amount);
        if (normalized < 0) {
            return TaxUpdateResponse.of(TaxUpdateStatus.INVALID_AMOUNT, state.taxAmount);
        }

        state.taxAmount = normalized;
        return TaxUpdateResponse.of(TaxUpdateStatus.SUCCESS, state.taxAmount);
    }

    public String formatMoney(double amount) {
        return String.format(Locale.US, "%.2f", amount);
    }

    public double getBankBalance(String stateName) {
        StateData data = states.get(stateName);
        return data == null ? 0.0 : data.bankBalance;
    }

    public BankActionResponse depositToState(String stateName, double amount, UUID actor, BankTransactionType type) {
        if (!plugin.getConfig().getBoolean("bank.enabled", true)) {
            return BankActionResponse.of(BankResult.DISABLED, 0, 0);
        }

        StateData state = states.get(stateName);
        if (state == null) {
            return BankActionResponse.of(BankResult.NO_STATE, 0, 0);
        }

        double normalized = normalizeAmount(amount);
        if (normalized <= 0) {
            return BankActionResponse.of(BankResult.INVALID_AMOUNT, 0, state.bankBalance);
        }

        state.bankBalance += normalized;
        BankTransactionType txType = type == null ? BankTransactionType.DEPOSIT : type;
        BankTransaction tx = switch (txType) {
            case WITHDRAW -> BankTransaction.withdraw(actor, normalized, state.bankBalance);
            case TAX -> BankTransaction.tax(actor, normalized, state.bankBalance);
            case EXPENSE -> BankTransaction.expense(actor, normalized, state.bankBalance);
            default -> BankTransaction.deposit(actor, normalized, state.bankBalance);
        };
        recordTransaction(state, tx);
        return BankActionResponse.of(BankResult.SUCCESS, normalized, state.bankBalance);
    }

    public BankActionResponse withdrawFromState(String stateName, double amount, UUID actor, BankTransactionType type) {
        if (!plugin.getConfig().getBoolean("bank.enabled", true)) {
            return BankActionResponse.of(BankResult.DISABLED, 0, 0);
        }

        StateData state = states.get(stateName);
        if (state == null) {
            return BankActionResponse.of(BankResult.NO_STATE, 0, 0);
        }

        double normalized = normalizeAmount(amount);
        if (normalized <= 0) {
            return BankActionResponse.of(BankResult.INVALID_AMOUNT, 0, state.bankBalance);
        }

        double deducted = Math.min(state.bankBalance, normalized);
        state.bankBalance = Math.max(0.0, state.bankBalance - deducted);
        BankTransactionType txType = type == null ? BankTransactionType.EXPENSE : type;
        BankTransaction tx = switch (txType) {
            case WITHDRAW -> BankTransaction.withdraw(actor, deducted, state.bankBalance);
            case TAX -> BankTransaction.tax(actor, deducted, state.bankBalance);
            case DEPOSIT -> BankTransaction.deposit(actor, deducted, state.bankBalance);
            default -> BankTransaction.expense(actor, deducted, state.bankBalance);
        };
        recordTransaction(state, tx);
        return BankActionResponse.of(BankResult.SUCCESS, deducted, state.bankBalance);
    }

    private double normalizeAmount(double amount) {
        if (!Double.isFinite(amount)) {
            return 0.0;
        }
        return Math.round(amount * 100.0) / 100.0;
    }

    private void recordTransaction(StateData state, BankTransaction transaction) {
        state.transactions.addFirst(transaction);
        int limit = Math.max(1, plugin.getConfig().getInt("bank.log-size", 30));
        while (state.transactions.size() > limit) {
            state.transactions.removeLast();
        }
        markDirty();
    }

    public RepairOutcome repairCamp(Player player, String sectorInput) {
        if (player == null) {
            return RepairOutcome.of(RepairStatus.NO_STATE, null, null, 0.0, PaymentSource.NONE, describeMaterials(Collections.emptyMap()));
        }

        String stateName = getStateName(player);
        if (stateName == null) {
            return RepairOutcome.of(RepairStatus.NO_STATE, null, null, 0.0, PaymentSource.NONE, describeMaterials(Collections.emptyMap()));
        }

        StateData state = states.get(stateName);
        if (state == null) {
            return RepairOutcome.of(RepairStatus.NO_STATE, null, null, 0.0, PaymentSource.NONE, describeMaterials(Collections.emptyMap()));
        }

        String resolved = resolveSectorName(state, sectorInput);
        if (resolved == null) {
            return RepairOutcome.of(RepairStatus.SECTOR_NOT_FOUND, sectorInput, null, 0.0, PaymentSource.NONE, describeMaterials(Collections.emptyMap()));
        }

        Camp camp = plugin.war().getCamp(stateName, resolved);
        if (camp == null) {
            return RepairOutcome.of(RepairStatus.CAMP_NOT_FOUND, resolved, null, 0.0, PaymentSource.NONE, describeMaterials(Collections.emptyMap()));
        }

        RepairSettings settings = resolveRepairSettings(isCapitalSector(stateName, resolved));
        double restoreAmount = settings.restore();
        Map<ItemDescriptor, Integer> requirements = settings.materials();
        String requirementText = describeMaterials(requirements);

        if (restoreAmount <= 0) {
            return RepairOutcome.of(RepairStatus.INVALID_CONFIG, resolved, null, settings.cost(), PaymentSource.NONE, requirementText);
        }

        if (camp.getHp() >= camp.getMaxHp()) {
            return RepairOutcome.of(RepairStatus.ALREADY_FULL, resolved, null, 0.0, PaymentSource.NONE, requirementText);
        }

        if (!hasRequiredMaterials(player, requirements)) {
            return RepairOutcome.of(RepairStatus.MISSING_ITEMS, resolved, null, settings.cost(), PaymentSource.NONE, requirementText);
        }

        double cost = Math.max(0.0, normalizeAmount(settings.cost()));
        PaymentSource source = PaymentSource.NONE;
        Economy economy = plugin.economy();
        boolean bankEnabled = plugin.getConfig().getBoolean("bank.enabled", true);

        if (cost > 0) {
            if (bankEnabled && state.bankBalance + 1e-6 >= cost) {
                source = PaymentSource.BANK;
            } else {
                if (economy == null) {
                    return RepairOutcome.of(RepairStatus.NO_ECONOMY, resolved, null, cost, PaymentSource.NONE, requirementText);
                }
                if (!economy.has(player, cost)) {
                    return RepairOutcome.of(RepairStatus.INSUFFICIENT_FUNDS, resolved, null, cost, PaymentSource.NONE, requirementText);
                }
                source = PaymentSource.PLAYER;
            }
        }

        EconomyResponse withdrawal = null;
        if (source == PaymentSource.PLAYER && cost > 0) {
            withdrawal = economy.withdrawPlayer(player, cost);
            if (withdrawal == null || !withdrawal.transactionSuccess()) {
                return RepairOutcome.of(RepairStatus.PAYMENT_FAILED, resolved, null, cost, PaymentSource.NONE, requirementText);
            }
        }

        WarManager.CampRepairResult result = plugin.war().repairCamp(stateName, resolved, restoreAmount);
        if (result.getStatus() != WarManager.CampRepairStatus.SUCCESS) {
            if (source == PaymentSource.PLAYER && cost > 0 && economy != null) {
                economy.depositPlayer(player, cost);
            }
            RepairStatus status = switch (result.getStatus()) {
                case ALREADY_FULL -> RepairStatus.ALREADY_FULL;
                case NOT_FOUND -> RepairStatus.CAMP_NOT_FOUND;
                default -> RepairStatus.INVALID_CONFIG;
            };
            return RepairOutcome.of(status, resolved, result, cost, PaymentSource.NONE, requirementText);
        }

        if (source == PaymentSource.BANK && cost > 0) {
            state.bankBalance = Math.max(0.0, state.bankBalance - cost);
            recordTransaction(state, BankTransaction.expense(player.getUniqueId(), cost, state.bankBalance));
        }

        if (!requirements.isEmpty()) {
            consumeMaterials(player, requirements);
        }

        return RepairOutcome.success(resolved, result, cost, source, requirementText);
    }

    public MaintenanceOutcome maintainCamp(Player player, String sectorInput) {
        if (player == null) {
            return MaintenanceOutcome.of(MaintenanceStatus.NO_STATE, null, null, 0.0, PaymentSource.NONE, describeMaterials(Collections.emptyMap()));
        }

        String stateName = getStateName(player);
        if (stateName == null) {
            return MaintenanceOutcome.of(MaintenanceStatus.NO_STATE, null, null, 0.0, PaymentSource.NONE, describeMaterials(Collections.emptyMap()));
        }

        StateData state = states.get(stateName);
        if (state == null) {
            return MaintenanceOutcome.of(MaintenanceStatus.NO_STATE, null, null, 0.0, PaymentSource.NONE, describeMaterials(Collections.emptyMap()));
        }

        String resolved = resolveSectorName(state, sectorInput);
        if (resolved == null) {
            return MaintenanceOutcome.of(MaintenanceStatus.SECTOR_NOT_FOUND, sectorInput, null, 0.0, PaymentSource.NONE, describeMaterials(Collections.emptyMap()));
        }

        Camp camp = plugin.war().getCamp(stateName, resolved);
        if (camp == null) {
            return MaintenanceOutcome.of(MaintenanceStatus.CAMP_NOT_FOUND, resolved, null, 0.0, PaymentSource.NONE, describeMaterials(Collections.emptyMap()));
        }

        MaintenanceSettings settings = resolveMaintenanceSettings(isCapitalSector(stateName, resolved));
        Map<ItemDescriptor, Integer> requirements = settings.materials();
        String requirementText = describeMaterials(requirements);

        if (!hasRequiredMaterials(player, requirements)) {
            return MaintenanceOutcome.of(MaintenanceStatus.MISSING_ITEMS, resolved, null, settings.cost(), PaymentSource.NONE, requirementText);
        }

        double cost = Math.max(0.0, normalizeAmount(settings.cost()));
        PaymentSource source = PaymentSource.NONE;
        Economy economy = plugin.economy();
        boolean bankEnabled = plugin.getConfig().getBoolean("bank.enabled", true);

        if (cost > 0) {
            if (bankEnabled && state.bankBalance + 1e-6 >= cost) {
                source = PaymentSource.BANK;
            } else {
                if (economy == null) {
                    return MaintenanceOutcome.of(MaintenanceStatus.NO_ECONOMY, resolved, null, cost, PaymentSource.NONE, requirementText);
                }
                if (!economy.has(player, cost)) {
                    return MaintenanceOutcome.of(MaintenanceStatus.INSUFFICIENT_FUNDS, resolved, null, cost, PaymentSource.NONE, requirementText);
                }
                source = PaymentSource.PLAYER;
            }
        }

        EconomyResponse withdrawal = null;
        if (source == PaymentSource.PLAYER && cost > 0) {
            withdrawal = economy.withdrawPlayer(player, cost);
            if (withdrawal == null || !withdrawal.transactionSuccess()) {
                return MaintenanceOutcome.of(MaintenanceStatus.PAYMENT_FAILED, resolved, null, cost, PaymentSource.NONE, requirementText);
            }
        }

        WarManager.CampMaintenanceResult result = plugin.war().maintainCamp(stateName, resolved);
        if (result.getStatus() != WarManager.CampMaintenanceStatus.SUCCESS) {
            if (source == PaymentSource.PLAYER && cost > 0 && economy != null) {
                economy.depositPlayer(player, cost);
            }
            MaintenanceStatus status = switch (result.getStatus()) {
                case NOT_FOUND -> MaintenanceStatus.CAMP_NOT_FOUND;
                default -> MaintenanceStatus.INVALID_CONFIG;
            };
            return MaintenanceOutcome.of(status, resolved, result, cost, PaymentSource.NONE, requirementText);
        }

        if (source == PaymentSource.BANK && cost > 0) {
            state.bankBalance = Math.max(0.0, state.bankBalance - cost);
            recordTransaction(state, BankTransaction.expense(player.getUniqueId(), cost, state.bankBalance));
        }

        if (!requirements.isEmpty()) {
            consumeMaterials(player, requirements);
        }

        return MaintenanceOutcome.success(resolved, result, cost, source, requirementText);
    }

    public TeleportResult teleport(Player player, String sectorInput) {
        if (player == null) {
            return TeleportResult.of(TeleportStatus.NO_STATE, sectorInput, 0L);
        }

        String stateName = getStateName(player);
        if (stateName == null) {
            return TeleportResult.of(TeleportStatus.NO_STATE, sectorInput, 0L);
        }

        StateData state = states.get(stateName);
        if (state == null) {
            return TeleportResult.of(TeleportStatus.NO_STATE, sectorInput, 0L);
        }

        String resolved = resolveSectorName(state, sectorInput);
        if (resolved == null) {
            return TeleportResult.of(TeleportStatus.SECTOR_NOT_FOUND, sectorInput, 0L);
        }

        Location location = getSectorLocation(stateName, resolved);
        if (location == null) {
            return TeleportResult.of(TeleportStatus.MISSING_LOCATION, resolved, 0L);
        }

        Location target = location.clone().add(0.5, 1.0, 0.5);
        long warmup = getStateTeleportWarmupMillis();
        if (warmup <= 0L) {
            player.teleport(target);
            return TeleportResult.of(TeleportStatus.SUCCESS, resolved, 0L);
        }

        String stateNameFinal = stateName;
        UUID uuid = player.getUniqueId();
        plugin.lang().send(player, "state.teleport-wait", Map.of(
                "sector", resolved,
                "time", plugin.war().formatDuration(warmup)
        ));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player live = Bukkit.getPlayer(uuid);
            if (live == null) {
                return;
            }
            String latestState = getStateName(live);
            if (latestState == null || !latestState.equalsIgnoreCase(stateNameFinal)) {
                plugin.lang().send(live, "state.teleport-cancelled", Map.of(
                        "reason", plugin.lang().messageOrDefault("state.tpa-reason-state", "不在同一政权")
                ));
                return;
            }
            Location latestLocation = getSectorLocation(stateNameFinal, resolved);
            if (latestLocation == null) {
                plugin.lang().send(live, "state.teleport-cancelled", Map.of(
                        "reason", plugin.lang().messageOrDefault("state.sector-not-found", "目标不存在")
                ));
                return;
            }
            live.teleport(latestLocation.clone().add(0.5, 1.0, 0.5));
            plugin.lang().send(live, "state.teleport-success", Map.of("sector", resolved));
        }, Math.max(1L, warmup / 50L));

        return TeleportResult.of(TeleportStatus.SUCCESS, resolved, warmup);
    }

    public CampUpgradeOutcome upgradeCamp(Player player, Camp camp, WarManager.CampUpgradeType type) {
        if (player == null || camp == null || type == null) {
            String displayCost = plugin.state().formatMoney(0.0);
            return CampUpgradeOutcome.of(UpgradeStatus.NO_STATE, type, null, 0.0, displayCost, PaymentSource.NONE,
                    describeMaterials(Collections.emptyMap()));
        }
        String stateName = getStateName(player);
        if (stateName == null || !stateName.equalsIgnoreCase(camp.getStateName())) {
            String displayCost = plugin.state().formatMoney(0.0);
            return CampUpgradeOutcome.of(UpgradeStatus.NO_STATE, type, null, 0.0, displayCost, PaymentSource.NONE,
                    describeMaterials(Collections.emptyMap()));
        }
        StateData state = states.get(stateName);
        if (state == null) {
            String displayCost = plugin.state().formatMoney(0.0);
            return CampUpgradeOutcome.of(UpgradeStatus.NO_STATE, type, null, 0.0, displayCost, PaymentSource.NONE,
                    describeMaterials(Collections.emptyMap()));
        }
        if (!canManageCamp(player, camp)) {
            String displayCost = plugin.state().formatMoney(0.0);
            return CampUpgradeOutcome.of(UpgradeStatus.NO_PERMISSION, type, null, 0.0, displayCost, PaymentSource.NONE,
                    describeMaterials(Collections.emptyMap()));
        }
        WarManager.UpgradeTree tree = plugin.war().getUpgradeTree(type);
        if (tree == null || !tree.enabled()) {
            String displayCost = plugin.state().formatMoney(0.0);
            return CampUpgradeOutcome.of(UpgradeStatus.DISABLED, type, null, 0.0, displayCost, PaymentSource.NONE,
                    describeMaterials(Collections.emptyMap()));
        }
        WarManager.UpgradeTier next = plugin.war().getNextTier(camp, type);
        if (next == null) {
            String displayCost = plugin.state().formatMoney(0.0);
            return CampUpgradeOutcome.of(UpgradeStatus.MAX_LEVEL, type, null, 0.0, displayCost, PaymentSource.NONE,
                    describeMaterials(Collections.emptyMap()));
        }
        Map<ItemDescriptor, Integer> requirements = next.items();
        String requirementText = next.itemsDisplay() != null ? next.itemsDisplay() : describeMaterials(requirements);
        String displayCost = next.costDisplay() != null ? next.costDisplay() : plugin.state().formatMoney(next.cost());
        if (!hasRequiredMaterials(player, requirements)) {
            return CampUpgradeOutcome.of(UpgradeStatus.MISSING_ITEMS, type, next, next.cost(), displayCost, PaymentSource.NONE,
                    requirementText);
        }
        double cost = next.cost();
        PaymentSource source = PaymentSource.NONE;
        Economy economy = plugin.economy();
        if (cost > 0.0) {
            if (state.bankBalance >= cost) {
                state.bankBalance -= cost;
                recordTransaction(state, BankTransaction.withdraw(player.getUniqueId(), cost, state.bankBalance));
                source = PaymentSource.BANK;
            } else {
                if (economy == null) {
                    return CampUpgradeOutcome.of(UpgradeStatus.NO_ECONOMY, type, next, cost, displayCost, PaymentSource.NONE,
                            requirementText);
                }
                EconomyResponse withdrawal = economy.withdrawPlayer(player, cost);
                if (withdrawal == null || !withdrawal.transactionSuccess()) {
                    return CampUpgradeOutcome.of(UpgradeStatus.INSUFFICIENT_FUNDS, type, next, cost, displayCost, PaymentSource.NONE,
                            requirementText);
                }
                source = PaymentSource.PLAYER;
            }
        }

        if (!requirements.isEmpty()) {
            consumeMaterials(player, requirements);
        }

        switch (type) {
            case HP -> camp.setHpLevel(next.level());
            case FUEL -> camp.setFuelLevel(next.level());
            case HEAL -> camp.setHealLevel(next.level());
            case FATIGUE -> camp.setFatigueLevel(next.level());
            case STORAGE -> camp.setStorageLevel(next.level());
            case EFFICIENCY -> camp.setEfficiencyLevel(next.level());
            case BOUNDARY -> camp.setBoundaryLevel(next.level());
        }
        plugin.war().applyCampUpgrades(camp);
        plugin.war().markDirty();
        if (plugin.holograms() != null) {
            plugin.holograms().update(camp);
        }
        return CampUpgradeOutcome.of(UpgradeStatus.SUCCESS, type, next, cost, displayCost, source, requirementText);
    }

    public CampModuleOutcome toggleModule(Player player, Camp camp, String moduleKey) {
        if (player == null || camp == null || moduleKey == null || moduleKey.isEmpty()) {
            return CampModuleOutcome.of(ModuleStatus.NO_STATE, null, false, 0.0, formatMoney(0.0), PaymentSource.NONE, describeMaterials(Collections.emptyMap()));
        }
        String stateName = getStateName(player);
        if (stateName == null || !stateName.equalsIgnoreCase(camp.getStateName())) {
            return CampModuleOutcome.of(ModuleStatus.NO_STATE, null, false, 0.0, formatMoney(0.0), PaymentSource.NONE, describeMaterials(Collections.emptyMap()));
        }
        if (!canManageCamp(player, camp)) {
            return CampModuleOutcome.of(ModuleStatus.NO_PERMISSION, null, false, 0.0, formatMoney(0.0), PaymentSource.NONE, describeMaterials(Collections.emptyMap()));
        }
        WarManager.ModuleDefinition definition = plugin.war().getModuleDefinition(moduleKey);
        if (definition == null || !definition.enabled()) {
            return CampModuleOutcome.of(ModuleStatus.CONFIG_DISABLED, definition, false, 0.0, formatMoney(0.0), PaymentSource.NONE, describeMaterials(Collections.emptyMap()));
        }

        Map<ItemDescriptor, Integer> requirements = definition.items();
        String requirementText = definition.itemsDisplay() != null ? definition.itemsDisplay() : describeMaterials(requirements);
        String displayCost = definition.costDisplay() != null ? definition.costDisplay() : formatMoney(definition.cost());
        boolean unlocked = camp.hasModule(moduleKey);
        if (!unlocked) {
            if (!hasRequiredMaterials(player, requirements)) {
                return CampModuleOutcome.of(ModuleStatus.MISSING_ITEMS, definition, false, definition.cost(), displayCost, PaymentSource.NONE, requirementText);
            }
            double cost = definition.cost();
            PaymentSource source = PaymentSource.NONE;
            Economy economy = plugin.economy();
            if (cost > 0.0) {
                StateData state = states.get(stateName);
                if (state == null) {
                    return CampModuleOutcome.of(ModuleStatus.NO_STATE, definition, false, cost, displayCost, PaymentSource.NONE, requirementText);
                }
                if (state.bankBalance >= cost) {
                    state.bankBalance -= cost;
                    recordTransaction(state, BankTransaction.withdraw(player.getUniqueId(), cost, state.bankBalance));
                    source = PaymentSource.BANK;
                } else {
                    if (economy == null) {
                        return CampModuleOutcome.of(ModuleStatus.NO_ECONOMY, definition, false, cost, displayCost, PaymentSource.NONE, requirementText);
                    }
                    EconomyResponse response = economy.withdrawPlayer(player, cost);
                    if (response == null || !response.transactionSuccess()) {
                        return CampModuleOutcome.of(ModuleStatus.INSUFFICIENT_FUNDS, definition, false, cost, displayCost, PaymentSource.NONE, requirementText);
                    }
                    source = PaymentSource.PLAYER;
                }
            }
            if (!requirements.isEmpty()) {
                consumeMaterials(player, requirements);
            }
            camp.setModuleState(moduleKey, true);
            plugin.war().markDirty();
            return CampModuleOutcome.of(ModuleStatus.PURCHASED, definition, true, definition.cost(), displayCost, source, requirementText);
        }

        boolean enabled = !camp.isModuleEnabled(moduleKey);
        camp.setModuleState(moduleKey, enabled);
        plugin.war().markDirty();
        return CampModuleOutcome.of(enabled ? ModuleStatus.ENABLED : ModuleStatus.DISABLED, definition, enabled, 0.0, displayCost, PaymentSource.NONE, requirementText);
    }

    private RepairSettings resolveRepairSettings(boolean capital) {
        String basePath = capital ? "camp.repair.capital" : "camp.repair.regular";
        double defaultRestore = plugin.getConfig().getDouble("camp.repair-amount", 25.0);
        double restore = plugin.getConfig().getDouble(basePath + ".restore", defaultRestore);
        double cost = plugin.getConfig().getDouble(basePath + ".cost", 0.0);

        Map<ItemDescriptor, Integer> items = new LinkedHashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(basePath + ".items");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ItemDescriptor descriptor = parseItemDescriptor(key, basePath + ".items");
                if (descriptor == null) {
                    continue;
                }
                int amount = section.getInt(key, 0);
                if (amount <= 0) {
                    continue;
                }
                items.put(descriptor, amount);
            }
        }

        return new RepairSettings(restore, cost, Collections.unmodifiableMap(items));
    }

    private MaintenanceSettings resolveMaintenanceSettings(boolean capital) {
        String basePath = capital ? "camp.maintenance.capital" : "camp.maintenance.regular";
        double cost = plugin.getConfig().getDouble(basePath + ".cost",
                plugin.getConfig().getDouble("camp.maintenance.cost", 0.0));

        Map<ItemDescriptor, Integer> items = new LinkedHashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(basePath + ".items");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ItemDescriptor descriptor = parseItemDescriptor(key, basePath + ".items");
                if (descriptor == null) {
                    continue;
                }
                int amount = section.getInt(key, 0);
                if (amount <= 0) {
                    continue;
                }
                items.put(descriptor, amount);
            }
        }

        return new MaintenanceSettings(cost, Collections.unmodifiableMap(items));
    }

    private MoveSettings resolveMoveSettings() {
        String basePath = "camp.move";
        String rawMaterial = plugin.getConfig().getString(basePath + ".item-material", "WHITE_BANNER");
        Material material = Material.matchMaterial(rawMaterial);
        if (material == null) {
            plugin.getLogger().warning("Invalid camp.move.item-material: " + rawMaterial + ". Falling back to WHITE_BANNER.");
            material = Material.WHITE_BANNER;
        }

        String rawName = plugin.getConfig().getString(basePath + ".item-name", "&f放置重建");
        String displayName = plugin.lang().colorizeText(rawName);
        double cost = plugin.getConfig().getDouble(basePath + ".cost", 0.0);

        Map<ItemDescriptor, Integer> items = new LinkedHashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(basePath + ".items");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ItemDescriptor descriptor = parseItemDescriptor(key, basePath + ".items");
                if (descriptor == null) {
                    continue;
                }
                int amount = section.getInt(key, 0);
                if (amount <= 0) {
                    continue;
                }
                items.put(descriptor, amount);
            }
        }

        return new MoveSettings(material, displayName, cost, Collections.unmodifiableMap(items));
    }

    private CapitalMoveSettings resolveCapitalMoveSettings(boolean emergency) {
        String basePath = emergency ? "capital.move.emergency" : "capital.move";
        double fallbackCost = plugin.getConfig().getDouble("capital.move.cost", 0.0);
        double cost = plugin.getConfig().getDouble(basePath + ".cost", fallbackCost);

        Map<ItemDescriptor, Integer> items = loadItemRequirements(basePath + ".items");
        if (items.isEmpty() && emergency) {
            items = loadItemRequirements("capital.move.items");
        }

        return new CapitalMoveSettings(cost, Collections.unmodifiableMap(items));
    }

    private long getCapitalMoveCooldownMs() {
        long seconds = plugin.getConfig().getLong("capital.move.cooldown-seconds", 86400L);
        if (seconds <= 0L) {
            return 0L;
        }
        return seconds * 1000L;
    }

    private long getCapitalMoveCooldownRemaining(String stateName) {
        if (stateName == null) {
            return 0L;
        }
        long cooldown = getCapitalMoveCooldownMs();
        if (cooldown <= 0L) {
            return 0L;
        }
        Long last = capitalMoveCooldowns.get(stateName.toLowerCase(Locale.ROOT));
        if (last == null) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - last;
        long remaining = cooldown - elapsed;
        return Math.max(0L, remaining);
    }

    private void setCapitalMoveCooldown(String stateName) {
        if (stateName == null) {
            return;
        }
        long cooldown = getCapitalMoveCooldownMs();
        if (cooldown <= 0L) {
            capitalMoveCooldowns.remove(stateName.toLowerCase(Locale.ROOT));
            return;
        }
        capitalMoveCooldowns.put(stateName.toLowerCase(Locale.ROOT), System.currentTimeMillis());
    }

    private boolean hasRequiredMaterials(Player player, Map<ItemDescriptor, Integer> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return true;
        }
        if (player == null) {
            return false;
        }
        ItemStack[] contents = player.getInventory().getContents();
        if (contents == null) {
            return false;
        }
        Map<ItemDescriptor, Integer> counts = new HashMap<>();
        for (ItemStack stack : contents) {
            if (stack == null) {
                continue;
            }
            ItemDescriptor descriptor = matchDescriptor(requirements.keySet(), stack);
            if (descriptor == null) {
                continue;
            }
            counts.merge(descriptor, stack.getAmount(), Integer::sum);
        }
        for (Map.Entry<ItemDescriptor, Integer> entry : requirements.entrySet()) {
            int have = counts.getOrDefault(entry.getKey(), 0);
            if (have < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private void consumeMaterials(Player player, Map<ItemDescriptor, Integer> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return;
        }
        ItemStack[] contents = player.getInventory().getContents();
        if (contents == null) {
            return;
        }
        Map<ItemDescriptor, Integer> remaining = new HashMap<>(requirements);
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null) {
                continue;
            }
            ItemDescriptor descriptor = matchDescriptor(remaining.keySet(), stack);
            if (descriptor == null) {
                continue;
            }
            Integer needed = remaining.get(descriptor);
            if (needed == null || needed <= 0) {
                continue;
            }
            int amount = stack.getAmount();
            if (amount <= needed) {
                remaining.put(descriptor, needed - amount);
                contents[i] = null;
            } else {
                stack.setAmount(amount - needed);
                contents[i] = stack;
                remaining.put(descriptor, 0);
            }
        }
        player.getInventory().setContents(contents);
    }

    public String describeMaterials(Map<ItemDescriptor, Integer> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return plugin.lang().messageOrDefault("state.repair-items-none", "无");
        }
        String format = plugin.lang().messageOrDefault("state.repair-items-format", "%item%x%amount%");
        String separator = plugin.lang().messageOrDefault("state.repair-items-separator", ", ");
        StringJoiner joiner = new StringJoiner(separator);
        for (Map.Entry<ItemDescriptor, Integer> entry : requirements.entrySet()) {
            String text = format
                    .replace("%item%", entry.getKey().getDisplay())
                    .replace("%amount%", String.valueOf(entry.getValue()));
            joiner.add(text);
        }
        return joiner.toString();
    }

    private ItemDescriptor matchDescriptor(Collection<ItemDescriptor> descriptors, ItemStack stack) {
        if (descriptors == null || descriptors.isEmpty() || stack == null) {
            return null;
        }
        for (ItemDescriptor descriptor : descriptors) {
            if (descriptor.matches(plugin, stack)) {
                return descriptor;
            }
        }
        return null;
    }

    private ItemDescriptor parseItemDescriptor(String raw, String path) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        Material material = Material.matchMaterial(trimmed);
        if (material != null) {
            return new ItemDescriptor(trimmed, material, null, trimmed);
        }

        String normalized = trimmed;
        if (normalized.regionMatches(true, 0, "itemsadder:", 0, 11)) {
            normalized = normalized.substring(11);
        }
        if (!normalized.contains(":")) {
            plugin.getLogger().warning("Invalid material in " + path + ": " + raw);
            return null;
        }
        if (!isItemsAdderAvailable()) {
            plugin.getLogger().warning("ItemsAdder item " + normalized + " configured in " + path + " but ItemsAdder plugin is not loaded.");
        }
        return new ItemDescriptor(trimmed, null, normalized.toLowerCase(Locale.ROOT), trimmed);
    }

    public Map<ItemDescriptor, Integer> loadItemRequirements(String path) {
        Map<ItemDescriptor, Integer> items = new LinkedHashMap<>();
        if (path == null) {
            return items;
        }
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);
        if (section == null) {
            return items;
        }
        for (String key : section.getKeys(false)) {
            ItemDescriptor descriptor = parseItemDescriptor(key, path);
            if (descriptor == null) {
                continue;
            }
            int amount = section.getInt(key, 0);
            if (amount <= 0) {
                continue;
            }
            items.put(descriptor, amount);
        }
        return items;
    }

    private CreationSettings resolveCreationSettings(boolean stateCreation) {
        String basePath = stateCreation ? "creation.state" : "creation.sector";
        double cost = plugin.getConfig().getDouble(basePath + ".cost", 0.0);
        long cooldownSeconds = plugin.getConfig().getLong(basePath + ".cooldown-seconds", -1L);
        if (cooldownSeconds < 0L && stateCreation) {
            cooldownSeconds = plugin.getConfig().getLong("creation.cooldown-seconds", 0L);
        }
        if (cooldownSeconds < 0L) {
            cooldownSeconds = 0L;
        }
        Map<ItemDescriptor, Integer> items = loadItemRequirements(basePath + ".items");
        return new CreationSettings(cost, items, cooldownSeconds * 1000L);
    }

    private long getCreationCooldownRemaining(Map<UUID, Long> store, UUID playerId, long cooldownMs) {
        if (playerId == null || cooldownMs <= 0L) {
            return 0L;
        }
        Long last = store.get(playerId);
        if (last == null) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - last;
        long remaining = cooldownMs - elapsed;
        return Math.max(0L, remaining);
    }

    // 邀请
    public void invite(Player inviter, Player target) {
        if (inviter == null || target == null) {
            return;
        }

        String state = playerState.get(inviter.getUniqueId());
        if (state == null) {
            plugin.lang().send(inviter, "camp.not-found");
            return;
        }

        StateData data = states.get(state);
        if (data == null) {
            plugin.lang().send(inviter, "camp.not-found");
            return;
        }

        if (!hasCampAuthority(inviter.getUniqueId())) {
            plugin.lang().send(inviter, "general.no-permission");
            return;
        }

        if (target.getUniqueId().equals(inviter.getUniqueId())) {
            plugin.lang().send(inviter, "state.invite-self");
            return;
        }

        String targetState = playerState.get(target.getUniqueId());
        if (state.equals(targetState)) {
            plugin.lang().send(inviter, "state.invite-already-member", Map.of("player", safePlayerName(target.getUniqueId(), target.getName())));
            return;
        }
        if (targetState != null) {
            plugin.lang().send(inviter, "state.invite-other-state", Map.of("player", safePlayerName(target.getUniqueId(), target.getName())));
            return;
        }

        long now = System.currentTimeMillis();
        pendingInvites.put(target.getUniqueId(), new InviteData(state, now));

        String time = formatDuration(getInviteTimeoutMillis());
        String displayTime = time.isEmpty()
                ? plugin.lang().messageOrDefault("state.request-time-unlimited", "不限")
                : time;
        Map<String, String> vars = new HashMap<>();
        vars.put("player", safePlayerName(target.getUniqueId(), target.getName()));
        vars.put("state", state);
        vars.put("time", displayTime);
        plugin.lang().send(inviter, "state.invite-sent", vars);

        Map<String, String> targetVars = new HashMap<>();
        targetVars.put("state", state);
        targetVars.put("captain", safePlayerName(inviter.getUniqueId(), inviter.getName()));
        targetVars.put("time", displayTime);
        plugin.lang().send(target, "state.invite-received", targetVars);
    }

    public void acceptInvite(Player player) {
        if (player == null) {
            return;
        }

        InviteData invite = pendingInvites.get(player.getUniqueId());
        if (invite == null) {
            plugin.lang().send(player, "state.no-invite");
            return;
        }

        if (isInviteExpired(invite)) {
            pendingInvites.remove(player.getUniqueId());
            plugin.lang().send(player, "state.invite-expired");
            return;
        }

        if (hasState(player)) {
            plugin.lang().send(player, "state.already-member");
            return;
        }

        StateData data = states.get(invite.state);
        if (data == null) {
            pendingInvites.remove(player.getUniqueId());
            plugin.lang().send(player, "camp.not-found");
            return;
        }

        pendingInvites.remove(player.getUniqueId());
        pendingJoinRequests.remove(player.getUniqueId());
        data.members.add(player.getUniqueId());
        playerState.put(player.getUniqueId(), invite.state);
        refreshIdeologyPermission(player.getUniqueId());
        clearTracking(player.getUniqueId());
        plugin.lang().send(player, "state.join-success", Map.of("state", invite.state));

        Player captain = Bukkit.getPlayer(data.captain);
        if (captain != null) {
            plugin.lang().send(captain, "state.invite-accepted-notify", Map.of(
                    "player", safePlayerName(player.getUniqueId(), player.getName()),
                    "state", invite.state
            ));
        }
    }

    public void denyInvite(Player player) {
        if (player == null) {
            return;
        }

        InviteData invite = pendingInvites.get(player.getUniqueId());
        if (invite == null) {
            plugin.lang().send(player, "state.no-invite");
            return;
        }

        if (isInviteExpired(invite)) {
            pendingInvites.remove(player.getUniqueId());
            plugin.lang().send(player, "state.invite-expired");
            return;
        }

        pendingInvites.remove(player.getUniqueId());
        plugin.lang().send(player, "state.invite-deny", Map.of("state", invite.state));

        StateData data = states.get(invite.state);
        if (data != null) {
            Player captain = Bukkit.getPlayer(data.captain);
            if (captain != null) {
                plugin.lang().send(captain, "state.invite-deny-notify", Map.of(
                        "player", safePlayerName(player.getUniqueId(), player.getName())
                ));
            }
        }
    }

    public void requestTeleportToPlayer(Player requester, Player target) {
        if (requester == null || target == null) {
            return;
        }

        if (requester.getUniqueId().equals(target.getUniqueId())) {
            plugin.lang().send(requester, "state.tpa-self");
            return;
        }

        String requesterState = getStateName(requester);
        if (requesterState == null) {
            plugin.lang().send(requester, "camp.not-found");
            return;
        }

        String targetState = getStateName(target);
        if (targetState == null || !requesterState.equalsIgnoreCase(targetState)) {
            plugin.lang().send(requester, "state.tpa-other-state", Map.of("player", safePlayerName(target.getUniqueId(), target.getName())));
            return;
        }

        long now = System.currentTimeMillis();
        long cooldown = getTeleportCooldownMillis();
        if (cooldown > 0) {
            long last = teleportCooldowns.getOrDefault(requester.getUniqueId(), 0L);
            long remaining = cooldown - (now - last);
            if (remaining > 0) {
                plugin.lang().send(requester, "state.tpa-cooldown", Map.of(
                        "time", plugin.war().formatDuration(remaining)
                ));
                return;
            }
        }

        TpaRequest existing = pendingTeleports.get(target.getUniqueId());
        if (existing != null) {
            if (!isTeleportRequestExpired(existing)) {
                if (existing.requester.equals(requester.getUniqueId())) {
                    long timeout = getTeleportTimeoutMillis();
                    long remaining = timeout <= 0 ? 0 : Math.max(0L, timeout - (now - existing.createdAt));
                    plugin.lang().send(requester, "state.tpa-pending", Map.of(
                            "player", safePlayerName(target.getUniqueId(), target.getName()),
                            "time", plugin.war().formatDuration(remaining)
                    ));
                } else {
                    plugin.lang().send(requester, "state.tpa-target-busy", Map.of(
                            "player", safePlayerName(target.getUniqueId(), target.getName())
                    ));
                }
                return;
            }
            pendingTeleports.remove(target.getUniqueId());
        }

        String timeoutText = plugin.war().formatDuration(getTeleportTimeoutMillis());
        String displayTime = timeoutText.isEmpty()
                ? plugin.lang().messageOrDefault("state.request-time-unlimited", "不限")
                : timeoutText;

        pendingTeleports.put(target.getUniqueId(), new TpaRequest(requester.getUniqueId(), target.getUniqueId(), now));
        teleportCooldowns.put(requester.getUniqueId(), now);

        plugin.lang().send(requester, "state.tpa-request-sent", Map.of(
                "player", safePlayerName(target.getUniqueId(), target.getName()),
                "time", displayTime
        ));
        plugin.lang().send(target, "state.tpa-request-received", Map.of(
                "player", safePlayerName(requester.getUniqueId(), requester.getName()),
                "time", displayTime
        ));
    }

    public void respondTeleportRequest(Player target, boolean accept) {
        if (target == null) {
            return;
        }

        TpaRequest request = pendingTeleports.get(target.getUniqueId());
        if (request == null) {
            plugin.lang().send(target, "state.tpa-no-request");
            return;
        }

        if (isTeleportRequestExpired(request)) {
            pendingTeleports.remove(target.getUniqueId());
            plugin.lang().send(target, "state.tpa-request-expired");
            return;
        }

        Player requester = Bukkit.getPlayer(request.requester);
        if (requester == null) {
            pendingTeleports.remove(target.getUniqueId());
            plugin.lang().send(target, "state.tpa-requester-offline");
            return;
        }

        String targetState = getStateName(target);
        String requesterState = getStateName(requester);
        if (targetState == null || requesterState == null || !targetState.equalsIgnoreCase(requesterState)) {
            pendingTeleports.remove(target.getUniqueId());
            plugin.lang().send(target, "state.tpa-state-changed");
            plugin.lang().send(requester, "state.tpa-state-changed");
            return;
        }

        pendingTeleports.remove(target.getUniqueId());

        if (!accept) {
            plugin.lang().send(target, "state.tpa-deny-confirm", Map.of(
                    "player", safePlayerName(requester.getUniqueId(), requester.getName())
            ));
            plugin.lang().send(requester, "state.tpa-denied", Map.of(
                    "player", safePlayerName(target.getUniqueId(), target.getName())
            ));
            return;
        }

        long warmupMs = getTeleportWarmupMillis();
        String warmupText = plugin.war().formatDuration(warmupMs);

        plugin.lang().send(target, "state.tpa-accept", Map.of(
                "player", safePlayerName(requester.getUniqueId(), requester.getName()),
                "time", warmupText
        ));
        plugin.lang().send(requester, "state.tpa-wait", Map.of(
                "player", safePlayerName(target.getUniqueId(), target.getName()),
                "time", warmupText
        ));

        Runnable teleportTask = () -> {
            Player liveRequester = Bukkit.getPlayer(request.requester);
            Player liveTarget = Bukkit.getPlayer(request.target);

            if (liveRequester == null || liveTarget == null) {
                Player online = liveRequester != null ? liveRequester : liveTarget;
                if (online != null) {
                    plugin.lang().send(online, "state.tpa-teleport-cancelled", Map.of(
                            "reason", plugin.lang().messageOrDefault("state.tpa-reason-offline", "目标玩家不在线")
                    ));
                }
                return;
            }

            String latestRequesterState = getStateName(liveRequester);
            String latestTargetState = getStateName(liveTarget);
            if (latestRequesterState == null || latestTargetState == null || !latestRequesterState.equalsIgnoreCase(latestTargetState)) {
                plugin.lang().send(liveRequester, "state.tpa-teleport-cancelled", Map.of(
                        "reason", plugin.lang().messageOrDefault("state.tpa-reason-state", "不在同一政权")
                ));
                return;
            }

            teleportCooldowns.put(liveRequester.getUniqueId(), System.currentTimeMillis());
            liveRequester.teleport(liveTarget.getLocation());
            plugin.lang().send(liveRequester, "state.tpa-teleport-success", Map.of(
                    "player", safePlayerName(liveTarget.getUniqueId(), liveTarget.getName())
            ));
        };

        if (warmupMs <= 0) {
            teleportTask.run();
        } else {
            long ticks = Math.max(1L, warmupMs / 50L);
            Bukkit.getScheduler().runTaskLater(plugin, teleportTask, ticks);
        }
    }

    private boolean isTeleportRequestExpired(TpaRequest request) {
        long timeout = getTeleportTimeoutMillis();
        return timeout > 0 && System.currentTimeMillis() - request.createdAt > timeout;
    }

    private boolean isInviteExpired(InviteData invite) {
        if (invite == null) {
            return true;
        }
        long timeout = getInviteTimeoutMillis();
        if (timeout <= 0) {
            return false;
        }
        return System.currentTimeMillis() - invite.time > timeout;
    }

    public void requestJoin(Player player, String targetName) {
        if (player == null) {
            return;
        }

        if (hasState(player)) {
            plugin.lang().send(player, "state.already-member");
            return;
        }

        String query = targetName == null ? "" : targetName.trim();
        if (query.isEmpty()) {
            plugin.lang().send(player, "state.join-usage");
            return;
        }

        StateData target = findState(query);
        if (target == null) {
            plugin.lang().send(player, "state.join-target-not-found", Map.of(
                    "state", query
            ));
            return;
        }

        Player captain = Bukkit.getPlayer(target.captain);
        if (captain == null) {
            plugin.lang().send(player, "state.join-captain-offline", Map.of("state", target.name));
            return;
        }

        JoinRequest existing = pendingJoinRequests.get(player.getUniqueId());
        if (existing != null && !isJoinRequestExpired(existing)) {
            if (existing.state.equals(target.name)) {
                plugin.lang().send(player, "state.join-request-exists", Map.of("state", target.name));
            } else {
                plugin.lang().send(player, "state.join-request-other", Map.of("state", existing.state));
            }
            return;
        }

        pendingJoinRequests.remove(player.getUniqueId());

        long now = System.currentTimeMillis();
        JoinRequest request = new JoinRequest(player.getUniqueId(), safePlayerName(player.getUniqueId(), player.getName()), target.name, now);
        pendingJoinRequests.put(player.getUniqueId(), request);

        String time = formatDuration(getJoinTimeoutMillis());
        String displayTime = time.isEmpty()
                ? plugin.lang().messageOrDefault("state.request-time-unlimited", "不限")
                : time;
        Map<String, String> vars = new HashMap<>();
        vars.put("state", target.name);
        vars.put("time", displayTime);
        plugin.lang().send(player, "state.join-request-sent", vars);

        Map<String, String> notify = new HashMap<>();
        notify.put("player", request.playerName);
        notify.put("state", target.name);
        notify.put("time", displayTime);
        plugin.lang().send(captain, "state.join-request-notify", notify);
    }

    public void respondJoinRequest(Player captain, boolean accept, String targetName) {
        if (captain == null) {
            return;
        }

        String stateName = getStateName(captain);
        if (stateName == null) {
            plugin.lang().send(captain, "camp.not-found");
            return;
        }

        StateData state = states.get(stateName);
        if (state == null || !state.captain.equals(captain.getUniqueId())) {
            plugin.lang().send(captain, "state.join-not-captain");
            return;
        }

        List<JoinRequest> requests = new ArrayList<>();
        for (JoinRequest request : pendingJoinRequests.values()) {
            if (stateName.equals(request.state)) {
                requests.add(request);
            }
        }

        if (requests.isEmpty()) {
            plugin.lang().send(captain, "state.join-no-requests");
            return;
        }

        String cleanedName = targetName == null ? "" : targetName.trim();

        JoinRequest target = null;
        if (!cleanedName.isEmpty()) {
            for (JoinRequest request : requests) {
                if (request.playerName.equalsIgnoreCase(cleanedName)) {
                    target = request;
                    break;
                }
                Player online = Bukkit.getPlayer(request.playerId);
                if (online != null) {
                    String name = online.getName();
                    if (name != null && name.equalsIgnoreCase(cleanedName)) {
                        target = request;
                        break;
                    }
                }
            }
            if (target == null) {
                plugin.lang().send(captain, "state.join-player-not-found", Map.of("player", cleanedName));
                return;
            }
        } else if (requests.size() == 1) {
            target = requests.get(0);
        } else {
            String separator = plugin.lang().messageOrDefault("state.join-request-separator", ", ");
            StringJoiner joiner = new StringJoiner(separator);
            for (JoinRequest request : requests) {
                joiner.add(request.playerName);
            }
            plugin.lang().send(captain, "state.join-multiple", Map.of("players", joiner.toString()));
            return;
        }

        if (target == null) {
            plugin.lang().send(captain, "state.join-no-requests");
            return;
        }

        if (isJoinRequestExpired(target)) {
            pendingJoinRequests.remove(target.playerId);
            plugin.lang().send(captain, "state.join-expired", Map.of("player", target.playerName));
            Player applicant = Bukkit.getPlayer(target.playerId);
            if (applicant != null) {
                plugin.lang().send(applicant, "state.join-expired-notify", Map.of("state", stateName));
            }
            return;
        }

        String applicantState = playerState.get(target.playerId);
        if (applicantState != null) {
            pendingJoinRequests.remove(target.playerId);
            if (applicantState.equals(stateName)) {
                plugin.lang().send(captain, "state.join-already-member", Map.of("player", target.playerName));
            } else {
                plugin.lang().send(captain, "state.join-target-other", Map.of("player", target.playerName));
            }
            return;
        }

        Player applicant = Bukkit.getPlayer(target.playerId);
        if (accept) {
            pendingJoinRequests.remove(target.playerId);
            state.members.add(target.playerId);
            playerState.put(target.playerId, stateName);
            refreshIdeologyPermission(target.playerId);
            clearTracking(target.playerId);
            plugin.lang().send(captain, "state.join-accept-success", Map.of("player", target.playerName));
            if (applicant != null) {
                plugin.lang().send(applicant, "state.join-accepted", Map.of("state", stateName));
            }
        } else {
            pendingJoinRequests.remove(target.playerId);
            plugin.lang().send(captain, "state.join-deny-success", Map.of("player", target.playerName));
            if (applicant != null) {
                plugin.lang().send(applicant, "state.join-denied", Map.of("state", stateName));
            }
        }
    }

    private boolean isJoinRequestExpired(JoinRequest request) {
        if (request == null) {
            return true;
        }
        long timeout = getJoinTimeoutMillis();
        if (timeout <= 0) {
            return false;
        }
        return System.currentTimeMillis() - request.time > timeout;
    }

    public SectorGiftRequestResult requestSectorGift(Player player, String sectorInput, String targetInput) {
        if (player == null) {
            return SectorGiftRequestResult.of(SectorGiftRequestStatus.NO_STATE, null, null, 0L);
        }

        String stateName = getStateName(player);
        if (stateName == null) {
            return SectorGiftRequestResult.of(SectorGiftRequestStatus.NO_STATE, null, null, 0L);
        }

        StateData state = states.get(stateName);
        if (state == null) {
            return SectorGiftRequestResult.of(SectorGiftRequestStatus.NO_STATE, null, null, 0L);
        }

        if (!isCaptain(player)) {
            return SectorGiftRequestResult.of(SectorGiftRequestStatus.NOT_CAPTAIN, null, null, 0L);
        }

        String cleanedTarget = targetInput == null ? "" : targetInput.trim();
        if (cleanedTarget.isEmpty()) {
            return SectorGiftRequestResult.of(SectorGiftRequestStatus.TARGET_NOT_FOUND, null, null, 0L);
        }

        StateData target = findState(cleanedTarget);
        if (target == null) {
            return SectorGiftRequestResult.of(SectorGiftRequestStatus.TARGET_NOT_FOUND, cleanedTarget, null, 0L);
        }
        if (target.name.equalsIgnoreCase(stateName)) {
            return SectorGiftRequestResult.of(SectorGiftRequestStatus.SAME_STATE, target.name, null, 0L);
        }

        if (!isCaptainOnline(target)) {
            return SectorGiftRequestResult.of(SectorGiftRequestStatus.TARGET_OFFLINE, target.name, null, 0L);
        }

        String resolved = resolveSectorName(state, sectorInput);
        if (resolved == null) {
            return SectorGiftRequestResult.of(SectorGiftRequestStatus.NO_SUCH_SECTOR, target.name, sectorInput, 0L);
        }
        if (isCapitalSector(stateName, resolved)) {
            return SectorGiftRequestResult.of(SectorGiftRequestStatus.CAPITAL_SECTOR, target.name, resolved, 0L);
        }

        purgeExpiredGifts();
        String key = buildGiftKey(stateName, resolved);
        SectorGiftRequest existing = pendingSectorGifts.get(key);
        if (existing != null && !isGiftRequestExpired(existing)) {
            long remaining = giftRemaining(existing);
            return SectorGiftRequestResult.of(SectorGiftRequestStatus.ALREADY_PENDING, existing.getTargetState(), resolved, remaining);
        }

        long now = System.currentTimeMillis();
        SectorGiftRequest request = new SectorGiftRequest(state.name, target.name, resolved, now);
        pendingSectorGifts.put(key, request);

        return SectorGiftRequestResult.of(SectorGiftRequestStatus.SUCCESS, target.name, resolved, getGiftTimeoutMillis());
    }

    public SectorGiftResponseResult respondSectorGift(Player player, boolean accept, String sourceStateInput, String sectorInput) {
        if (player == null) {
            return SectorGiftResponseResult.noRequest();
        }

        String stateName = getStateName(player);
        if (stateName == null) {
            return SectorGiftResponseResult.noState();
        }

        StateData state = states.get(stateName);
        if (state == null || !isCaptain(player)) {
            return SectorGiftResponseResult.noState();
        }

        purgeExpiredGifts();

        List<SectorGiftRequest> available = new ArrayList<>();
        for (SectorGiftRequest request : pendingSectorGifts.values()) {
            if (request.getTargetState().equalsIgnoreCase(stateName) && !isGiftRequestExpired(request)) {
                available.add(request);
            }
        }

        if (available.isEmpty()) {
            return SectorGiftResponseResult.noRequest();
        }

        SectorGiftRequest target = null;
        String cleanedSource = sourceStateInput == null ? "" : sourceStateInput.trim();
        String cleanedSector = sectorInput == null ? "" : sectorInput.trim();

        if (!cleanedSource.isEmpty() || !cleanedSector.isEmpty()) {
            for (SectorGiftRequest request : available) {
                boolean sourceMatch = !cleanedSource.isEmpty() && request.getSourceState().equalsIgnoreCase(cleanedSource);
                boolean sectorMatch = !cleanedSector.isEmpty() && request.getSector().equalsIgnoreCase(cleanedSector);
                if (sourceMatch || sectorMatch) {
                    target = request;
                    break;
                }
            }
            if (target == null) {
                return SectorGiftResponseResult.multiple(available);
            }
        } else if (available.size() == 1) {
            target = available.get(0);
        } else {
            return SectorGiftResponseResult.multiple(available);
        }

        if (target == null) {
            return SectorGiftResponseResult.noRequest();
        }

        String key = buildGiftKey(target.getSourceState(), target.getSector());
        pendingSectorGifts.remove(key);
        if (isGiftRequestExpired(target)) {
            return SectorGiftResponseResult.expired(target);
        }

        if (!accept) {
            return SectorGiftResponseResult.denied(target);
        }

        String newSector = transferSector(target.getSourceState(), stateName, target.getSector());
        if (newSector == null) {
            return SectorGiftResponseResult.transferFailed(target);
        }

        plugin.war().applySectorTransfer(target.getSourceState(), target.getSector(), stateName, newSector);
        return SectorGiftResponseResult.accepted(target, newSector);
    }

    private String buildGiftKey(String state, String sector) {
        if (state == null || sector == null) {
            return "";
        }
        return state.toLowerCase(Locale.ROOT) + "|" + sector.toLowerCase(Locale.ROOT);
    }

    private boolean isGiftRequestExpired(SectorGiftRequest request) {
        if (request == null) {
            return true;
        }
        long timeout = getGiftTimeoutMillis();
        if (timeout <= 0) {
            return false;
        }
        return System.currentTimeMillis() - request.getCreatedAt() > timeout;
    }

    private long giftRemaining(SectorGiftRequest request) {
        long timeout = getGiftTimeoutMillis();
        if (timeout <= 0) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - request.getCreatedAt();
        return Math.max(0L, timeout - elapsed);
    }

    private void purgeExpiredGifts() {
        pendingSectorGifts.entrySet().removeIf(entry -> isGiftRequestExpired(entry.getValue()));
    }

    private String formatDuration(long millis) {
        if (millis <= 0) {
            return "";
        }
        WarManager war = plugin.war();
        if (war == null) {
            return "";
        }
        return war.formatDuration(millis);
    }

    private boolean isReservedStateName(String name) {
        if (name == null) {
            return false;
        }
        return RESERVED_STATE_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    private String safePlayerName(UUID playerId, String fallback) {
        String name = fallback;
        if ((name == null || name.isEmpty()) && playerId != null) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(playerId);
            if (offline != null) {
                String offlineName = offline.getName();
                if (offlineName != null && !offlineName.isEmpty()) {
                    name = offlineName;
                }
            }
        }
        if (name == null || name.isEmpty()) {
            name = plugin.lang().messageOrDefault("bank.log-unknown", "未知");
        }
        return name;
    }

    private long getInviteTimeoutMillis() {
        long seconds = plugin.getConfig().getLong("requests.invite-timeout-seconds", 120L);
        if (seconds <= 0) {
            return 0L;
        }
        return seconds * 1000L;
    }

    private long getJoinTimeoutMillis() {
        long seconds = plugin.getConfig().getLong("requests.join-timeout-seconds", 120L);
        if (seconds <= 0) {
            return 0L;
        }
        return seconds * 1000L;
    }

    private long getGiftTimeoutMillis() {
        long seconds = plugin.getConfig().getLong("requests.givesector-timeout-seconds", 120L);
        if (seconds <= 0) {
            return 0L;
        }
        return seconds * 1000L;
    }

    private long getTeleportTimeoutMillis() {
        long seconds = plugin.getConfig().getLong("tpa.response-timeout-seconds", 60L);
        if (seconds <= 0) {
            return 0L;
        }
        return seconds * 1000L;
    }

    private long getTeleportCooldownMillis() {
        long seconds = plugin.getConfig().getLong("tpa.cooldown-seconds", 120L);
        if (seconds <= 0) {
            return 0L;
        }
        return seconds * 1000L;
    }

    private long getTeleportWarmupMillis() {
        long seconds = plugin.getConfig().getLong("tpa.warmup-seconds", 3L);
        if (seconds <= 0) {
            return 0L;
        }
        return seconds * 1000L;
    }

    private long getStateTeleportWarmupMillis() {
        long seconds = plugin.getConfig().getLong("teleport.warmup-seconds", 0L);
        if (seconds <= 0) {
            return 0L;
        }
        return seconds * 1000L;
    }

    public void kickMember(Player captain, Player target) {
        String state = playerState.get(captain.getUniqueId());
        if (state == null) return;
        StateData data = states.get(state);
        if (data == null || !data.captain.equals(captain.getUniqueId())) {
            plugin.lang().send(captain, "general.no-permission");
            return;
        }
        data.members.remove(target.getUniqueId());
        reassignSectors(data, target.getUniqueId(), data.captain);
        cleanupPlayer(target.getUniqueId());
        plugin.lang().send(captain, "state.kick-success", Map.of("player", target.getName()));
        plugin.lang().send(target, "state.kicked", Map.of("state", state));
    }

    public CivilWarResult initiateCivilWar(Player player) {
        String originName = getStateName(player);
        if (originName == null) {
            plugin.lang().send(player, "camp.not-found");
            return CivilWarResult.failure(CivilWarStatus.NOT_IN_STATE);
        }

        StateData origin = states.get(originName);
        if (origin == null) {
            plugin.lang().send(player, "camp.not-found");
            return CivilWarResult.failure(CivilWarStatus.INVALID_STATE);
        }

        if (origin.captain.equals(player.getUniqueId())) {
            plugin.lang().send(player, "war.civilwar-is-captain");
            return CivilWarResult.failure(CivilWarStatus.IS_CAPTAIN);
        }

        if (!isCaptainOnline(origin)) {
            plugin.lang().send(player, "war.target-captain-offline", Map.of("state", origin.name));
            return CivilWarResult.failure(CivilWarStatus.CAPTAIN_OFFLINE);
        }

        if (!hasRequiredCampItem(player)) {
            plugin.lang().send(player, "state.no-camp-item");
            return CivilWarResult.failure(CivilWarStatus.MISSING_CAMP_ITEM);
        }

        origin.members.remove(player.getUniqueId());
        reassignSectors(origin, player.getUniqueId(), origin.captain);
        pendingCampPlacement.remove(player.getUniqueId());

        cleanupPlayer(player.getUniqueId());

        String suffix = plugin.getConfig().getString("civilwar.rebel-suffix", "叛军");
        String baseName = origin.name + suffix;
        String rebelName = resolveUniqueStateName(baseName);

        StateData rebelState = new StateData(rebelName, player.getUniqueId());
        rebelState.taxAmount = plugin.getConfig().getDouble("bank.tax.amount", 0.0);
        states.put(rebelName, rebelState);
        playerState.put(player.getUniqueId(), rebelName);
        refreshIdeologyPermission(player.getUniqueId());
        clearTracking(player.getUniqueId());

        AutoNames autoNames = generateAutoNames();
        String pendingSector = autoNames.sector();
        PendingCampData pending = new PendingCampData(rebelName, pendingSector);
        pending.setOwner(player.getUniqueId());
        pendingCampPlacement.put(player.getUniqueId(), pending);

        plugin.lang().send(player, "war.civilwar-created", Map.of(
                "state", rebelName,
                "origin", origin.name
        ));
        plugin.lang().send(player, "state.sector-create-start", Map.of("sector", pendingSector));
        plugin.lang().send(player, "state.place-instruction", Map.of("sector", pendingSector));

        for (UUID member : origin.members) {
            var online = Bukkit.getPlayer(member);
            if (online != null) {
                plugin.lang().send(online, "war.civilwar-notify", Map.of(
                        "player", player.getName(),
                        "state", origin.name,
                        "rebel", rebelName
                ));
            }
        }

        plugin.war().queueCivilWar(rebelName, origin.name, player.getUniqueId());

        markDirty();

        return CivilWarResult.success(origin.name, rebelName, pendingSector);
    }

    // 清理邀请
    public void cleanupInvites() {
        long now = System.currentTimeMillis();
        long inviteTimeout = getInviteTimeoutMillis();
        if (inviteTimeout > 0) {
            pendingInvites.entrySet().removeIf(entry -> now - entry.getValue().time > inviteTimeout);
        }
        long joinTimeout = getJoinTimeoutMillis();
        if (joinTimeout > 0) {
            pendingJoinRequests.entrySet().removeIf(entry -> now - entry.getValue().time > joinTimeout);
        }
        long teleportTimeout = getTeleportTimeoutMillis();
        if (teleportTimeout > 0) {
            pendingTeleports.entrySet().removeIf(entry -> now - entry.getValue().createdAt > teleportTimeout);
        }
    }

    public void loadFromCampInfo(YamlConfiguration yaml) {
        states.clear();
        playerState.clear();
        pendingCampPlacement.clear();
        pendingInvites.clear();
        pendingJoinRequests.clear();
        pendingTeleports.clear();
        teleportCooldowns.clear();
        onlineProgress.clear();
        taxRecords.clear();
        capitalMoveCooldowns.clear();

        int nextId = yaml == null ? 1 : yaml.getInt("meta.next-auto-id", 1);
        idCounter.set(Math.max(1, nextId));

        if (yaml == null) {
            return;
        }

        ConfigurationSection statesSection = yaml.getConfigurationSection("states");
        if (statesSection == null) {
            return;
        }

        int logLimit = Math.max(1, plugin.getConfig().getInt("bank.log-size", 30));

        for (String stateName : statesSection.getKeys(false)) {
            ConfigurationSection stateSection = statesSection.getConfigurationSection(stateName);
            if (stateSection == null) {
                continue;
            }

            UUID captain = parseUuid(stateSection.getString("captain"));
            if (captain == null) {
                plugin.getLogger().warning("Skipping state " + stateName + " in campinfo.yml due to invalid captain UUID.");
                continue;
            }

            StateData data = new StateData(stateName, captain);
            data.members.clear();
            data.members.add(captain);
            data.bankBalance = Math.max(0.0, stateSection.getDouble("bank", 0.0));
            data.taxAmount = Math.max(0.0, stateSection.getDouble("tax",
                    plugin.getConfig().getDouble("bank.tax.amount", 0.0)));
            data.ideologyId = trimToNull(stateSection.getString("ideology"));
            data.ideologyChangedAt = stateSection.getLong("ideology-changed-at", 0L);
            data.capitalSector = trimToNull(stateSection.getString("capital"));

            for (String memberRaw : stateSection.getStringList("members")) {
                UUID memberId = parseUuid(memberRaw);
                if (memberId != null) {
                    data.members.add(memberId);
                }
            }

            data.transactions.clear();
            for (Map<?, ?> entry : stateSection.getMapList("transactions")) {
                if (entry == null) {
                    continue;
                }
                String typeRaw = entry.get("type") instanceof String str ? str : null;
                BankTransactionType type;
                try {
                    type = typeRaw == null ? null : BankTransactionType.valueOf(typeRaw.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    type = null;
                }
                if (type == null) {
                    continue;
                }
                UUID actor = parseUuid(entry.get("actor") instanceof String s ? s : null);
                double amount = entry.get("amount") instanceof Number num ? num.doubleValue() : 0.0;
                double balance = entry.get("balance") instanceof Number num2 ? num2.doubleValue() : 0.0;
                long timestamp = entry.get("timestamp") instanceof Number num3 ? num3.longValue() : System.currentTimeMillis();
                data.transactions.addLast(BankTransaction.fromData(timestamp, type, actor, amount, balance));
            }
            while (data.transactions.size() > logLimit) {
                data.transactions.removeLast();
            }

            ConfigurationSection sectorsSection = stateSection.getConfigurationSection("sectors");
            if (sectorsSection != null) {
                for (String sectorName : sectorsSection.getKeys(false)) {
                    ConfigurationSection sectorSection = sectorsSection.getConfigurationSection(sectorName);
                    if (sectorSection == null) {
                        continue;
                    }
                    String worldName = trimToNull(sectorSection.getString("world"));
                    World world = worldName == null ? null : Bukkit.getWorld(worldName);
                    int x = sectorSection.getInt("x", 0);
                    int y = sectorSection.getInt("y", 0);
                    int z = sectorSection.getInt("z", 0);
                    Location location = world == null ? null : new Location(world, x, y, z);
                    UUID owner = parseUuid(sectorSection.getString("owner"));
                    SectorData sectorData = new SectorData(sectorName, location, owner);
                    data.sectors.put(sectorName, sectorData);
                }
            }

            states.put(stateName, data);
            for (UUID memberId : data.members) {
                playerState.put(memberId, stateName);
            }
            refreshIdeologyPermissions(data);
        }
    }

    // 辅助
    public boolean hasState(Player p) { return playerState.containsKey(p.getUniqueId()); }
    public String getStateName(Player p) { return playerState.get(p.getUniqueId()); }
    public StateData getState(String name) { return states.get(name); }

    public Collection<StateData> getStates() {
        return Collections.unmodifiableCollection(states.values());
    }

    public int getNextAutoId() {
        return idCounter.get();
    }
    public StateData findState(String name) {
        if (name == null) {
            return null;
        }
        StateData direct = states.get(name);
        if (direct != null) {
            return direct;
        }
        for (StateData data : states.values()) {
            if (data.name.equalsIgnoreCase(name)) {
                return data;
            }
        }
        return null;
    }
    public Collection<StateData> all() { return states.values(); }

    public boolean isCaptain(Player player) {
        String stateName = getStateName(player);
        if (stateName == null) {
            return false;
        }
        StateData data = states.get(stateName);
        return data != null && data.captain.equals(player.getUniqueId());
    }

    public boolean isCaptainOnline(StateData data) {
        if (data == null || data.captain == null) {
            return false;
        }
        Player online = Bukkit.getPlayer(data.captain);
        return online != null;
    }

    public boolean isCaptainOnline(String stateName) {
        return isCaptainOnline(states.get(stateName));
    }

    public StateRole getRole(Player player) {
        if (player == null) {
            return StateRole.NONE;
        }
        return getRole(player.getUniqueId());
    }

    public StateRole getRole(UUID playerId) {
        if (playerId == null) {
            return StateRole.NONE;
        }
        String stateName = playerState.get(playerId);
        if (stateName == null) {
            return StateRole.NONE;
        }
        StateData data = states.get(stateName);
        if (data == null || !data.members.contains(playerId)) {
            return StateRole.NONE;
        }
        if (data.captain.equals(playerId)) {
            return StateRole.CAPTAIN;
        }
        return hasOwnedSector(data, playerId) ? StateRole.GOVERNOR : StateRole.MEMBER;
    }

    public String getRoleDisplay(Player player) {
        return getRoleDisplay(player == null ? null : player.getUniqueId());
    }

    public String getRoleDisplay(UUID playerId) {
        return resolveRoleDisplay(getRole(playerId));
    }

    public String getRoleKey(Player player) {
        return getRoleKey(player == null ? null : player.getUniqueId());
    }

    public String getRoleKey(UUID playerId) {
        return getRole(playerId).name().toLowerCase(Locale.ROOT);
    }

    public boolean isGovernor(Player player) {
        return getRole(player) == StateRole.GOVERNOR;
    }

    public boolean hasCampAuthority(Player player) {
        return player != null && hasCampAuthority(player.getUniqueId());
    }

    public boolean hasCampAuthority(UUID playerId) {
        StateRole role = getRole(playerId);
        return role == StateRole.CAPTAIN || role == StateRole.GOVERNOR;
    }

    public boolean canManageCamp(Player player, Camp camp) {
        return player != null && canManageCamp(player.getUniqueId(), camp);
    }

    public boolean canManageCamp(UUID playerId, Camp camp) {
        if (playerId == null || camp == null) {
            return false;
        }
        String stateName = playerState.get(playerId);
        if (stateName == null || !stateName.equalsIgnoreCase(camp.getStateName())) {
            return false;
        }
        StateData data = states.get(stateName);
        if (data == null) {
            return false;
        }
        String resolved = resolveSectorName(data, camp.getSectorName());
        if (resolved == null) {
            return false;
        }
        SectorData sector = data.sectors.get(resolved);
        if (sector == null) {
            return false;
        }
        return canManageSector(data, playerId, sector);
    }

    private boolean canManageSector(StateData state, UUID actor, SectorData sector) {
        if (state == null || actor == null || sector == null) {
            return false;
        }
        if (state.captain.equals(actor)) {
            return true;
        }
        UUID owner = sector.getOwner();
        return owner != null && owner.equals(actor);
    }

    private void dropCampBlock(Location location) {
        breakCampBlock(location, true);
    }

    private void breakCampBlock(Location location, boolean dropItem) {
        if (location == null) {
            return;
        }
        Block block = location.getBlock();
        if (block != null) {
            block.setType(Material.AIR);
        }
        World world = location.getWorld();
        if (dropItem && world != null) {
            ItemDescriptor descriptor = resolveCampItem();
            ItemStack drop = descriptor == null ? null : descriptor.createItem(plugin, 1);
            if (drop == null) {
                drop = new ItemStack(Material.BEACON, 1);
            }
            Location dropLocation = location.clone().add(0.5, 0.25, 0.5);
            world.dropItemNaturally(dropLocation, drop);
        }
    }

    private void clearRelocationTokens(Player player, PendingCampData data) {
        if (player == null || data == null || !data.isRelocation()) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        if (inventory == null) {
            return;
        }
        ItemStack[] contents = inventory.getContents();
        if (contents == null) {
            return;
        }
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (matchesRelocationToken(stack, data)) {
                contents[i] = null;
            }
        }
        inventory.setContents(contents);
    }

    private boolean matchesRelocationToken(ItemStack stack, PendingCampData data) {
        if (stack == null || data == null || !data.isRelocation()) {
            return false;
        }
        if (data.getRelocationMaterial() != null && stack.getType() != data.getRelocationMaterial()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        String display = meta == null ? null : meta.getDisplayName();
        return display != null && display.equals(data.getRelocationTokenName());
    }

    private String resolveRoleDisplay(StateRole role) {
        return switch (role) {
            case CAPTAIN -> plugin.lang().messageOrDefault("placeholders.roles.captain", "首领");
            case GOVERNOR -> plugin.lang().messageOrDefault("placeholders.roles.governor", "总督");
            case MEMBER -> plugin.lang().messageOrDefault("placeholders.roles.member", "公民");
            default -> plugin.lang().messageOrDefault("placeholders.none", "无");
        };
    }

    public IdeologyChangeResult setIdeology(Player player, Ideology ideology) {
        if (player == null || ideology == null) {
            return IdeologyChangeResult.invalid();
        }
        String stateName = getStateName(player);
        if (stateName == null) {
            return IdeologyChangeResult.invalid();
        }
        StateData data = states.get(stateName);
        if (data == null) {
            return IdeologyChangeResult.invalid();
        }
        if (!data.captain.equals(player.getUniqueId())) {
            return IdeologyChangeResult.invalid();
        }
        long cooldownSeconds = Math.max(0L, plugin.getConfig().getLong("ideology.cooldown-seconds", 0L));
        long cooldownMillis = cooldownSeconds * 1000L;
        long now = System.currentTimeMillis();
        if (cooldownMillis > 0 && data.ideologyChangedAt > 0) {
            long remaining = cooldownMillis - (now - data.ideologyChangedAt);
            if (remaining > 0) {
                return IdeologyChangeResult.cooldown(remaining);
            }
        }
        data.ideologyId = ideology.getId();
        data.ideologyChangedAt = now;
        refreshIdeologyPermissions(data);
        plugin.campInfo().markDirty();
        return IdeologyChangeResult.success();
    }

    public long getIdeologyCooldownRemaining(String stateName) {
        StateData data = findState(stateName);
        if (data == null) {
            return 0L;
        }
        long cooldownSeconds = Math.max(0L, plugin.getConfig().getLong("ideology.cooldown-seconds", 0L));
        long cooldownMillis = cooldownSeconds * 1000L;
        if (cooldownMillis <= 0 || data.ideologyChangedAt <= 0) {
            return 0L;
        }
        long remaining = cooldownMillis - (System.currentTimeMillis() - data.ideologyChangedAt);
        return Math.max(0L, remaining);
    }

    public String getIdeologyId(String stateName) {
        StateData data = findState(stateName);
        if (data == null) {
            return null;
        }
        return data.ideologyId;
    }

    public String getIdeologyDisplay(String stateName) {
        if (stateName == null) {
            return plugin.lang().messageOrDefault("campinfo.ideology-unknown", "待定");
        }
        StateData data = findState(stateName);
        if (data == null || data.ideologyId == null || data.ideologyId.isEmpty()) {
            return plugin.lang().messageOrDefault("campinfo.ideology-unknown", "待定");
        }
        Ideology ideology = plugin.ideology().get(data.ideologyId);
        if (ideology == null) {
            return data.ideologyId;
        }
        return ideology.getDisplayName();
    }

    public String getOfflinePlayerName(UUID playerId) {
        return safePlayerName(playerId, null);
    }

    public Location getSectorLocation(String stateName, String sectorName) {
        StateData data = states.get(stateName);
        if (data == null) {
            return null;
        }
        String resolved = resolveSectorName(data, sectorName);
        if (resolved == null) {
            return null;
        }
        SectorData sector = data.sectors.get(resolved);
        return sector == null ? null : sector.getLocation();
    }

    public Set<String> getSectorNames(String stateName) {
        StateData data = states.get(stateName);
        if (data == null) {
            return Collections.emptySet();
        }
        return new LinkedHashSet<>(data.sectors.keySet());
    }

    public CampSectorInfo findCampByLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }

        for (StateData state : states.values()) {
            for (SectorData sector : state.sectors.values()) {
                Location stored = sector.getLocation();
                if (stored == null || stored.getWorld() == null) {
                    continue;
                }
                if (!stored.getWorld().getName().equals(location.getWorld().getName())) {
                    continue;
                }
                if (stored.getBlockX() == location.getBlockX()
                        && stored.getBlockY() == location.getBlockY()
                        && stored.getBlockZ() == location.getBlockZ()) {
                    return new CampSectorInfo(state.name, sector.getName());
                }
            }
        }
        return null;
    }

    public CampSectorInfo findCampInRadius(Location location, double radius) {
        if (location == null || location.getWorld() == null) {
            return null;
        }

        double fallback = Math.max(1.0, radius);
        String worldName = location.getWorld().getName();

        for (StateData state : states.values()) {
            for (SectorData sector : state.sectors.values()) {
                Location stored = sector.getLocation();
                if (stored == null || stored.getWorld() == null) {
                    continue;
                }
                if (!worldName.equals(stored.getWorld().getName())) {
                    continue;
                }
                CampBoundary boundary = getSectorBoundary(state.name, sector.getName());
                if (boundary == null) {
                    continue;
                }
                CampBoundary effective = new CampBoundary(
                        Math.max(boundary.west(), fallback),
                        Math.max(boundary.east(), fallback),
                        Math.max(boundary.north(), fallback),
                        Math.max(boundary.south(), fallback)
                );
                if (isWithinBoundary(location, stored, effective, 0.0)) {
                    return new CampSectorInfo(state.name, sector.getName());
                }
            }
        }
        return null;
    }

    public CampSectorInfo findCampNearColumn(Location location, int horizontalRadius, int minYOffset, int maxYOffset) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        int radius = Math.max(0, horizontalRadius);
        int min = Math.min(minYOffset, maxYOffset);
        int max = Math.max(minYOffset, maxYOffset);
        String world = location.getWorld().getName();

        for (StateData state : states.values()) {
            for (SectorData sector : state.sectors.values()) {
                Location center = sector.getLocation();
                if (center == null || center.getWorld() == null) {
                    continue;
                }
                if (!world.equals(center.getWorld().getName())) {
                    continue;
                }
                int dx = Math.abs(center.getBlockX() - location.getBlockX());
                int dz = Math.abs(center.getBlockZ() - location.getBlockZ());
                int dy = location.getBlockY() - center.getBlockY();
                if (dx <= radius && dz <= radius && dy >= min && dy <= max) {
                    return new CampSectorInfo(state.name, sector.getName());
                }
            }
        }
        return null;
    }

    public CampBoundary getSectorBoundary(String stateName, String sectorName) {
        Camp camp = plugin.war().getCamp(stateName, sectorName);
        double baseRadius = Math.max(1.0, plugin.getConfig().getDouble("camp.radius", 16.0));
        if (camp == null) {
            return new CampBoundary(baseRadius);
        }
        CampBoundary boundary = camp.getBoundary();
        if (boundary == null) {
            return new CampBoundary(baseRadius);
        }
        return boundary.copy();
    }

    public void recalculateCampBoundary(Camp camp, double baseRadius, double bonusRadius) {
        if (camp == null) {
            return;
        }
        Location center = getSectorLocation(camp.getStateName(), camp.getSectorName());
        if (center == null || center.getWorld() == null) {
            camp.setBoundary(new CampBoundary(baseRadius));
            return;
        }
        double base = Math.max(1.0, baseRadius);
        double bonus = Math.max(0.0, bonusRadius);
        double desiredWest = base + bonus;
        double desiredEast = base + bonus;
        double desiredNorth = base + bonus;
        double desiredSouth = base + bonus;
        double gap = Math.max(0.0, plugin.getConfig().getDouble("sectors.inter-state-gap", 10.0));

        for (Camp other : plugin.war().getCamps()) {
            if (other == null || camp == other) {
                continue;
            }
            Location otherCenter = getSectorLocation(other.getStateName(), other.getSectorName());
            if (otherCenter == null || otherCenter.getWorld() == null) {
                continue;
            }
            if (!center.getWorld().getName().equals(otherCenter.getWorld().getName())) {
                continue;
            }
            CampBoundary otherBoundary = other.getBoundary();
            if (otherBoundary == null) {
                otherBoundary = new CampBoundary(base);
            }
            double requiredGap = camp.getStateName().equalsIgnoreCase(other.getStateName()) ? 0.0 : gap;
            double obMinX = otherCenter.getX() - otherBoundary.west();
            double obMaxX = otherCenter.getX() + otherBoundary.east();
            double obMinZ = otherCenter.getZ() - otherBoundary.north();
            double obMaxZ = otherCenter.getZ() + otherBoundary.south();

            double ourMinZ = center.getZ() - desiredNorth;
            double ourMaxZ = center.getZ() + desiredSouth;
            double ourMinX = center.getX() - desiredWest;
            double ourMaxX = center.getX() + desiredEast;

            if (rangesOverlap(ourMinZ, ourMaxZ, obMinZ - requiredGap, obMaxZ + requiredGap)) {
                if (otherCenter.getX() >= center.getX()) {
                    double limit = (obMinX - requiredGap) - center.getX();
                    desiredEast = Math.min(desiredEast, Math.max(base, limit));
                }
                if (otherCenter.getX() <= center.getX()) {
                    double limit = center.getX() - (obMaxX + requiredGap);
                    desiredWest = Math.min(desiredWest, Math.max(base, limit));
                }
            }

            if (rangesOverlap(ourMinX, ourMaxX, obMinX - requiredGap, obMaxX + requiredGap)) {
                if (otherCenter.getZ() >= center.getZ()) {
                    double limit = (obMinZ - requiredGap) - center.getZ();
                    desiredSouth = Math.min(desiredSouth, Math.max(base, limit));
                }
                if (otherCenter.getZ() <= center.getZ()) {
                    double limit = center.getZ() - (obMaxZ + requiredGap);
                    desiredNorth = Math.min(desiredNorth, Math.max(base, limit));
                }
            }
        }

        camp.setBoundary(new CampBoundary(desiredWest, desiredEast, desiredNorth, desiredSouth));
    }

    private boolean rangesOverlap(double minA, double maxA, double minB, double maxB) {
        return maxA >= minB && maxB >= minA;
    }

    private boolean isWithinBoundary(Location point, Location center, CampBoundary boundary, double buffer) {
        double minX = center.getX() - boundary.west() - buffer;
        double maxX = center.getX() + boundary.east() + buffer;
        double minZ = center.getZ() - boundary.north() - buffer;
        double maxZ = center.getZ() + boundary.south() + buffer;
        return point.getX() >= minX && point.getX() <= maxX && point.getZ() >= minZ && point.getZ() <= maxZ;
    }

    private boolean boundariesIntersect(Location aCenter, CampBoundary a, Location bCenter, CampBoundary b) {
        double aMinX = aCenter.getX() - a.west();
        double aMaxX = aCenter.getX() + a.east();
        double aMinZ = aCenter.getZ() - a.north();
        double aMaxZ = aCenter.getZ() + a.south();

        double bMinX = bCenter.getX() - b.west();
        double bMaxX = bCenter.getX() + b.east();
        double bMinZ = bCenter.getZ() - b.north();
        double bMaxZ = bCenter.getZ() + b.south();

        return aMinX < bMaxX && aMaxX > bMinX && aMinZ < bMaxZ && aMaxZ > bMinZ;
    }

    private double boundarySeparation(Location aCenter, CampBoundary a, Location bCenter, CampBoundary b) {
        double aMinX = aCenter.getX() - a.west();
        double aMaxX = aCenter.getX() + a.east();
        double aMinZ = aCenter.getZ() - a.north();
        double aMaxZ = aCenter.getZ() + a.south();

        double bMinX = bCenter.getX() - b.west();
        double bMaxX = bCenter.getX() + b.east();
        double bMinZ = bCenter.getZ() - b.north();
        double bMaxZ = bCenter.getZ() + b.south();

        double gapX = Math.max(0.0, Math.max(bMinX - aMaxX, aMinX - bMaxX));
        double gapZ = Math.max(0.0, Math.max(bMinZ - aMaxZ, aMinZ - bMaxZ));
        return Math.max(gapX, gapZ);
    }

    public boolean hasPendingPlacement(Player player) {
        return pendingCampPlacement.containsKey(player.getUniqueId());
    }

    public PendingCampData peekPendingPlacement(Player player) {
        return pendingCampPlacement.get(player.getUniqueId());
    }

    public PlacementValidationResult validatePendingPlacement(Player player, Location location) {
        if (player == null || location == null || location.getWorld() == null) {
            return PlacementValidationResult.allowed();
        }

        PendingCampData pending = pendingCampPlacement.get(player.getUniqueId());
        if (pending == null) {
            return PlacementValidationResult.allowed();
        }

        if (plugin.getConfig().getBoolean("protection.camp-core-build.enabled", true)) {
            int radius = Math.max(0, plugin.getConfig().getInt("protection.camp-core-build.radius", 1));
            int diameter = radius * 2 + 1;
            int minY = plugin.getConfig().getInt("protection.camp-core-build.min-y-offset", 0);
            int maxY = plugin.getConfig().getInt("protection.camp-core-build.max-y-offset", 2);
            Location base = location.getBlock().getLocation();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }

                    int maxAbs = Math.max(Math.abs(dx), Math.abs(dz));
                    if (maxAbs > radius) {
                        continue;
                    }

                    for (int dy = minY; dy <= maxY; dy++) {
                        Location check = base.clone().add(dx, dy, dz);
                        Block block = check.getBlock();
                        if (block != null && block.getType() != Material.AIR) {
                            return PlacementValidationResult.denied("state.camp-core-obstructed", Map.of(
                                    "radius", String.valueOf(diameter)
                            ));
                        }
                    }
                }
            }
        }

        double baseRadius = Math.max(1.0, plugin.getConfig().getDouble("camp.radius", 16.0));
        CampBoundary pendingBoundary = new CampBoundary(baseRadius);
        double extraGap = Math.max(0.0, plugin.getConfig().getDouble("sectors.inter-state-gap", 10.0));
        String gapDisplay;
        if (Math.abs(extraGap - Math.rint(extraGap)) < 1e-9) {
            gapDisplay = String.valueOf((long) Math.round(extraGap));
        } else {
            gapDisplay = String.format(Locale.ROOT, "%.2f", extraGap);
        }

        String worldName = location.getWorld().getName();
        for (StateData state : states.values()) {
            for (SectorData sector : state.sectors.values()) {
                Location stored = sector.getLocation();
                if (stored == null || stored.getWorld() == null) {
                    continue;
                }
                if (!worldName.equals(stored.getWorld().getName())) {
                    continue;
                }
                CampBoundary boundary = getSectorBoundary(state.name, sector.getName());
                boolean sameState = pending.getState().equals(state.name);
                if (sameState && sector.getName().equalsIgnoreCase(pending.getSector())) {
                    continue;
                }

                if (boundariesIntersect(location, pendingBoundary, stored, boundary)) {
                    if (sameState) {
                        return PlacementValidationResult.denied("state.sector-overlap-own", Map.of(
                                "sector", sector.getName()
                        ));
                    }
                    return PlacementValidationResult.denied("state.sector-overlap-other", Map.of(
                            "state", state.name,
                            "sector", sector.getName()
                    ));
                }

                double requiredGap = sameState ? 0.0 : extraGap;
                double separation = boundarySeparation(location, pendingBoundary, stored, boundary);
                if (!sameState && separation < requiredGap) {
                    return PlacementValidationResult.denied("state.sector-gap-enemy", Map.of(
                            "state", state.name,
                            "sector", sector.getName(),
                            "gap", gapDisplay
                    ));
                }
            }
        }

        return PlacementValidationResult.allowed();
    }

    public PendingCampData completePendingPlacement(Player player, Location location) {
        PendingCampData data = pendingCampPlacement.remove(player.getUniqueId());
        if (data != null) {
            data.setPlacedLocation(location);
            finalizePlacement(player, data);
        }
        return data;
    }

    private void finalizePlacement(Player player, PendingCampData data) {
        StateData state = states.get(data.getState());
        if (state == null) {
            return;
        }

        Location placement = data.getPlacedLocation();
        if (placement != null) {
            Block block = placement.getBlock();
            ItemDescriptor campItem = resolveCampItem();
            if (block != null && campItem != null && campItem.getMaterial() != null) {
                block.setType(campItem.getMaterial());
            }
        }

        UUID owner = data.getOwner() != null ? data.getOwner() : player.getUniqueId();
        SectorData sector = state.sectors.get(data.getSector());
        if (sector == null) {
            sector = new SectorData(data.getSector(), data.getPlacedLocation(), owner);
            state.sectors.put(data.getSector(), sector);
        } else {
            sector.setLocation(data.getPlacedLocation());
            sector.setOwner(owner);
        }

        if (state.capitalSector == null) {
            state.capitalSector = data.getSector();
        }

        if (data.isRelocation() && data.getStoredCamp() != null) {
            plugin.war().restoreCamp(data.getStoredCamp());
        } else {
            plugin.war().registerCamp(state.name, data.getSector());
        }

        if (plugin.holograms() != null) {
            Location holoLocation = data.getPlacedLocation() == null ? null : data.getPlacedLocation().clone();
            plugin.holograms().spawnOrUpdate(state.name, data.getSector(), holoLocation);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                plugin.holograms().spawnOrUpdate(state.name, data.getSector(), holoLocation);
                plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                        plugin.holograms().spawnOrUpdate(state.name, data.getSector(), holoLocation), 1L);
            });
        }

        if (data.isRelocation()) {
            plugin.lang().send(player, "state.sector-move-complete", Map.of(
                    "state", state.name,
                    "sector", data.getSector()
            ));
        } else {
            plugin.lang().sendActionBar(player, "state.sector-created", Map.of(
                    "state", state.name,
                    "sector", data.getSector()
            ));
            if (plugin.sectorCreateSound() != null) {
                plugin.sectorCreateSound().play(player);
            }

            if (state.capitalSector.equals(data.getSector())) {
                plugin.lang().sendActionBar(player, "state.capital-set", Map.of("sector", data.getSector()));
                if (plugin.capitalSetSound() != null) {
                    plugin.capitalSetSound().play(player);
                }
            }
        }

        plugin.war().handleCampPlacement(state.name, data.getSector());
        if (data.isRelocation()) {
            clearRelocationTokens(player, data);
        }
        if (plugin.protection() != null && player != null) {
            plugin.protection().showBoundary(player, state.name, data.getSector());
        }
        markDirty();
    }

    public String resolveSectorName(String state, String sector) {
        StateData data = states.get(state);
        if (data == null) {
            return null;
        }
        return resolveSectorName(data, sector);
    }

    private String resolveSectorName(StateData data, String sector) {
        if (sector == null) {
            return null;
        }
        for (String key : data.sectors.keySet()) {
            if (key.equalsIgnoreCase(sector)) {
                return key;
            }
        }
        return null;
    }

    public String getCapital(String state) {
        StateData data = states.get(state);
        return data == null ? null : data.capitalSector;
    }

    public boolean isCampItem(ItemStack stack) {
        return isCampItem(stack, null);
    }

    public boolean isCampItem(ItemStack stack, String customId) {
        if (stack == null && customId == null) {
            return false;
        }
        ItemDescriptor descriptor = resolveCampItem();
        return descriptor != null && descriptor.matches(plugin, stack, customId);
    }

    public boolean isRelocationItem(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        MoveSettings settings = resolveMoveSettings();
        if (stack.getType() != settings.material()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        String display = meta == null ? null : meta.getDisplayName();
        return display != null && display.equals(settings.displayName());
    }

    public boolean isValidPlacementItem(PendingCampData pending, ItemStack stack) {
        return isValidPlacementItem(pending, stack, null);
    }

    public boolean isValidPlacementItem(PendingCampData pending, ItemStack stack, String customId) {
        if (pending == null) {
            return false;
        }
        if (pending.isRelocation()) {
            if (stack == null) {
                return false;
            }
            if (pending.getRelocationMaterial() != null && stack.getType() != pending.getRelocationMaterial()) {
                return false;
            }
            String expected = pending.getRelocationTokenName();
            ItemMeta meta = stack.getItemMeta();
            String actual = meta == null ? null : meta.getDisplayName();
            return expected == null || (actual != null && actual.equals(expected));
        }
        return isCampItem(stack, customId);
    }

    public String resolveNamespacedId(ItemStack stack) {
        if (stack == null || !isItemsAdderAvailable()) {
            return null;
        }
        CustomStack custom = CustomStack.byItemStack(stack);
        if (custom == null || custom.getNamespacedID() == null) {
            return null;
        }
        return custom.getNamespacedID();
    }

    private boolean hasInventorySpaceForToken(Player player, Material material) {
        if (player == null) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }
        return inventory.firstEmpty() != -1;
    }

    private ItemStack createRelocationToken(MoveSettings settings) {
        ItemStack token = new ItemStack(settings.material(), 1);
        ItemMeta meta = token.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(settings.displayName());
            token.setItemMeta(meta);
        }
        return token;
    }

    private AutoNames generateAutoNames() {
        var cfg = plugin.getConfig();
        int length = Math.max(1, cfg.getInt("defaults.auto-id-length", 4));
        String id = String.format("%0" + length + "d", idCounter.getAndIncrement());
        String stateFormat = cfg.getString("defaults.state-name-format", "临时政体-%id%");
        String sectorFormat = cfg.getString("defaults.sector-name-format", "第%id%区");
        return new AutoNames(
                stateFormat.replace("%id%", id),
                sectorFormat.replace("%id%", id)
        );
    }

    public boolean hasRequiredCampItem(Player player) {
        ItemDescriptor descriptor = resolveCampItem();
        if (descriptor == null || player == null) {
            return false;
        }
        PlayerInventory inv = player.getInventory();
        for (ItemStack item : inv.getContents()) {
            if (descriptor.matches(plugin, item) && item.getAmount() > 0) {
                return true;
            }
        }
        return false;
    }

    private ItemDescriptor resolveCampItem() {
        String raw = plugin.config().getString("creation.required-item", "BEACON");
        ItemDescriptor descriptor = parseItemDescriptor(raw, "creation.required-item");
        if (descriptor == null) {
            plugin.getLogger().warning("Invalid creation.required-item entry: " + raw + ". Falling back to BEACON.");
            descriptor = new ItemDescriptor("BEACON", Material.BEACON, null, "BEACON");
        }
        return descriptor;
    }

    private int resolveSectorLimit(StateData state) {
        if (state == null || state.members == null) {
            return 1;
        }
        int members = Math.max(0, state.members.size());
        return Math.max(1, members + 1);
    }

    private String resolveUniqueStateName(String baseName) {
        if (!states.containsKey(baseName)) {
            return baseName;
        }
        int index = 2;
        String candidate;
        do {
            candidate = baseName + index++;
        } while (states.containsKey(candidate));
        return candidate;
    }

    private record AutoNames(String state, String sector) { }

    private static class TaxRecord {
        double dueAmount;
        int failedAttempts;
    }

    public static class RepairOutcome {
        private final RepairStatus status;
        private final String sector;
        private final WarManager.CampRepairResult result;
        private final double cost;
        private final PaymentSource source;
        private final String requiredItems;

        private RepairOutcome(RepairStatus status, String sector, WarManager.CampRepairResult result, double cost, PaymentSource source, String requiredItems) {
            this.status = status;
            this.sector = sector;
            this.result = result;
            this.cost = cost;
            this.source = source;
            this.requiredItems = requiredItems;
        }

        public static RepairOutcome of(RepairStatus status, String sector, WarManager.CampRepairResult result, double cost, PaymentSource source, String requiredItems) {
            return new RepairOutcome(status, sector, result, cost, source, requiredItems);
        }

        public static RepairOutcome success(String sector, WarManager.CampRepairResult result, double cost, PaymentSource source, String requiredItems) {
            return new RepairOutcome(RepairStatus.SUCCESS, sector, result, cost, source, requiredItems);
        }

        public RepairStatus getStatus() {
            return status;
        }

        public String getSector() {
            return sector;
        }

        public WarManager.CampRepairResult getResult() {
            return result;
        }

        public double getCost() {
            return cost;
        }

        public PaymentSource getSource() {
            return source;
        }

        public String getRequiredItems() {
            return requiredItems;
        }
    }

    public enum RepairStatus {
        SUCCESS,
        NO_STATE,
        SECTOR_NOT_FOUND,
        CAMP_NOT_FOUND,
        ALREADY_FULL,
        INVALID_CONFIG,
        MISSING_ITEMS,
        NO_ECONOMY,
        INSUFFICIENT_FUNDS,
        PAYMENT_FAILED
    }

    public static class MaintenanceOutcome {
        private final MaintenanceStatus status;
        private final String sector;
        private final WarManager.CampMaintenanceResult result;
        private final double cost;
        private final PaymentSource source;
        private final String requiredItems;

        private MaintenanceOutcome(MaintenanceStatus status, String sector, WarManager.CampMaintenanceResult result, double cost, PaymentSource source, String requiredItems) {
            this.status = status;
            this.sector = sector;
            this.result = result;
            this.cost = cost;
            this.source = source;
            this.requiredItems = requiredItems;
        }

        public static MaintenanceOutcome of(MaintenanceStatus status, String sector, WarManager.CampMaintenanceResult result, double cost, PaymentSource source, String requiredItems) {
            return new MaintenanceOutcome(status, sector, result, cost, source, requiredItems);
        }

        public static MaintenanceOutcome success(String sector, WarManager.CampMaintenanceResult result, double cost, PaymentSource source, String requiredItems) {
            return new MaintenanceOutcome(MaintenanceStatus.SUCCESS, sector, result, cost, source, requiredItems);
        }

        public MaintenanceStatus getStatus() {
            return status;
        }

        public String getSector() {
            return sector;
        }

        public WarManager.CampMaintenanceResult getResult() {
            return result;
        }

        public double getCost() {
            return cost;
        }

        public PaymentSource getSource() {
            return source;
        }

        public String getRequiredItems() {
            return requiredItems;
        }
    }

    public enum MaintenanceStatus {
        SUCCESS,
        NO_STATE,
        SECTOR_NOT_FOUND,
        CAMP_NOT_FOUND,
        INVALID_CONFIG,
        MISSING_ITEMS,
        NO_ECONOMY,
        INSUFFICIENT_FUNDS,
        PAYMENT_FAILED
    }

    public static class PlacementValidationResult {
        private final PlacementStatus status;
        private final String messageKey;
        private final Map<String, String> placeholders;

        private PlacementValidationResult(PlacementStatus status, String messageKey, Map<String, String> placeholders) {
            this.status = status;
            this.messageKey = messageKey;
            this.placeholders = placeholders == null ? Collections.emptyMap() : placeholders;
        }

        public static PlacementValidationResult allowed() {
            return new PlacementValidationResult(PlacementStatus.ALLOWED, null, Collections.emptyMap());
        }

        public static PlacementValidationResult denied(String messageKey, Map<String, String> placeholders) {
            return new PlacementValidationResult(PlacementStatus.DENIED, messageKey, placeholders);
        }

        public PlacementStatus getStatus() {
            return status;
        }

        public boolean isAllowed() {
            return status == PlacementStatus.ALLOWED;
        }

        public String getMessageKey() {
            return messageKey;
        }

        public Map<String, String> getPlaceholders() {
            return placeholders;
        }
    }

    public enum PlacementStatus {
        ALLOWED,
        DENIED
    }

    public static class MoveSectorResult {
        private final MoveSectorStatus status;
        private final String state;
        private final String sector;
        private final double cost;
        private final PaymentSource paymentSource;
        private final String requirementText;

        private MoveSectorResult(MoveSectorStatus status, String state, String sector, double cost, PaymentSource paymentSource, String requirementText) {
            this.status = status;
            this.state = state;
            this.sector = sector;
            this.cost = cost;
            this.paymentSource = paymentSource;
            this.requirementText = requirementText;
        }

        public static MoveSectorResult of(MoveSectorStatus status, String state, String sector) {
            return new MoveSectorResult(status, state, sector, 0.0, PaymentSource.NONE, "");
        }

        public static MoveSectorResult of(MoveSectorStatus status, String state, String sector, double cost, PaymentSource paymentSource, String requirementText) {
            return new MoveSectorResult(status, state, sector, cost, paymentSource, requirementText);
        }

        public MoveSectorStatus getStatus() {
            return status;
        }

        public String getState() {
            return state;
        }

        public String getSector() {
            return sector;
        }

        public double getCost() {
            return cost;
        }

        public PaymentSource getPaymentSource() {
            return paymentSource;
        }

        public String getRequirementText() {
            return requirementText;
        }
    }

    public static class AssignGovernorResult {
        private final AssignGovernorStatus status;
        private final String state;
        private final String sector;
        private final String playerName;
        private final UUID playerId;
        private final String previousSector;

        private AssignGovernorResult(AssignGovernorStatus status, String state, String sector, String playerName, UUID playerId, String previousSector) {
            this.status = status;
            this.state = state;
            this.sector = sector;
            this.playerName = playerName;
            this.playerId = playerId;
            this.previousSector = previousSector;
        }

        public static AssignGovernorResult of(AssignGovernorStatus status, String state, String sector, String playerName, UUID playerId, String previousSector) {
            return new AssignGovernorResult(status, state, sector, playerName, playerId, previousSector);
        }

        public AssignGovernorStatus getStatus() {
            return status;
        }

        public String getState() {
            return state;
        }

        public String getSector() {
            return sector;
        }

        public String getPlayerName() {
            return playerName;
        }

        public UUID getPlayerId() {
            return playerId;
        }

        public String getPreviousSector() {
            return previousSector;
        }
    }

    public static class RemoveGovernorResult {
        private final RemoveGovernorStatus status;
        private final String sector;
        private final String playerName;
        private final UUID playerId;

        private RemoveGovernorResult(RemoveGovernorStatus status, String sector, String playerName, UUID playerId) {
            this.status = status;
            this.sector = sector;
            this.playerName = playerName;
            this.playerId = playerId;
        }

        public static RemoveGovernorResult of(RemoveGovernorStatus status, String sector, String playerName, UUID playerId) {
            return new RemoveGovernorResult(status, sector, playerName, playerId);
        }

        public RemoveGovernorStatus getStatus() {
            return status;
        }

        public String getSector() {
            return sector;
        }

        public String getPlayerName() {
            return playerName;
        }

        public UUID getPlayerId() {
            return playerId;
        }
    }

    public enum MoveSectorStatus {
        SUCCESS,
        NOT_IN_STATE,
        NO_SUCH_SECTOR,
        NOT_AUTHORIZED,
        CAPITAL_SECTOR,
        PENDING_PLACEMENT,
        MISSING_LOCATION,
        NO_ECONOMY,
        INSUFFICIENT_FUNDS,
        PAYMENT_FAILED,
        MISSING_ITEMS,
        INVENTORY_FULL
    }

    public enum AssignGovernorStatus {
        SUCCESS,
        NOT_IN_STATE,
        NOT_CAPTAIN,
        PLAYER_NOT_FOUND,
        PLAYER_NOT_MEMBER,
        NO_SUCH_SECTOR,
        ALREADY_OWNER
    }

    public enum RemoveGovernorStatus {
        SUCCESS,
        NOT_IN_STATE,
        NOT_CAPTAIN,
        PLAYER_NOT_FOUND,
        PLAYER_NOT_MEMBER,
        NOT_GOVERNOR
    }

    public static class RemoveSectorResult {
        private final RemoveSectorStatus status;
        private final String state;
        private final String sector;
        private final String newCapital;
        private final boolean capitalCleared;

        private RemoveSectorResult(RemoveSectorStatus status, String state, String sector, String newCapital, boolean capitalCleared) {
            this.status = status;
            this.state = state;
            this.sector = sector;
            this.newCapital = newCapital;
            this.capitalCleared = capitalCleared;
        }

        public static RemoveSectorResult of(RemoveSectorStatus status, String state, String sector, String newCapital, boolean capitalCleared) {
            return new RemoveSectorResult(status, state, sector, newCapital, capitalCleared);
        }

        public RemoveSectorStatus getStatus() {
            return status;
        }

        public String getState() {
            return state;
        }

        public String getSector() {
            return sector;
        }

        public String getNewCapital() {
            return newCapital;
        }

        public boolean isCapitalCleared() {
            return capitalCleared;
        }
    }

    public enum RemoveSectorStatus {
        SUCCESS,
        NOT_IN_STATE,
        NO_SUCH_SECTOR,
        NOT_AUTHORIZED,
        CAPITAL_SECTOR
    }

    public static class CampUpgradeOutcome {
        private final UpgradeStatus status;
        private final WarManager.CampUpgradeType type;
        private final WarManager.UpgradeTier tier;
        private final double cost;
        private final String displayCost;
        private final PaymentSource source;
        private final String requiredItems;

        private CampUpgradeOutcome(UpgradeStatus status, WarManager.CampUpgradeType type,
                                   WarManager.UpgradeTier tier, double cost, String displayCost, PaymentSource source,
                                   String requiredItems) {
            this.status = status;
            this.type = type;
            this.tier = tier;
            this.cost = cost;
            this.displayCost = displayCost;
            this.source = source;
            this.requiredItems = requiredItems;
        }

        public static CampUpgradeOutcome of(UpgradeStatus status, WarManager.CampUpgradeType type,
                                            WarManager.UpgradeTier tier, double cost, String displayCost, PaymentSource source,
                                            String requiredItems) {
            return new CampUpgradeOutcome(status, type, tier, cost, displayCost, source, requiredItems);
        }

        public UpgradeStatus getStatus() { return status; }

        public WarManager.CampUpgradeType getType() { return type; }

        public WarManager.UpgradeTier getTier() { return tier; }

        public double getCost() { return cost; }

        public String getDisplayCost() { return displayCost; }

        public PaymentSource getSource() { return source; }

        public String getRequiredItems() { return requiredItems; }
    }

    public static class CampModuleOutcome {
        private final ModuleStatus status;
        private final WarManager.ModuleDefinition definition;
        private final boolean enabled;
        private final double cost;
        private final String displayCost;
        private final PaymentSource source;
        private final String requiredItems;

        private CampModuleOutcome(ModuleStatus status, WarManager.ModuleDefinition definition, boolean enabled, double cost,
                                   String displayCost, PaymentSource source, String requiredItems) {
            this.status = status;
            this.definition = definition;
            this.enabled = enabled;
            this.cost = cost;
            this.displayCost = displayCost;
            this.source = source;
            this.requiredItems = requiredItems;
        }

        public static CampModuleOutcome of(ModuleStatus status, WarManager.ModuleDefinition definition, boolean enabled,
                                           double cost, String displayCost, PaymentSource source, String requiredItems) {
            return new CampModuleOutcome(status, definition, enabled, cost, displayCost, source, requiredItems);
        }

        public ModuleStatus getStatus() { return status; }

        public WarManager.ModuleDefinition getDefinition() { return definition; }

        public boolean isEnabled() { return enabled; }

        public double getCost() { return cost; }

        public String getDisplayCost() { return displayCost; }

        public PaymentSource getSource() { return source; }

        public String getRequiredItems() { return requiredItems; }
    }

    public enum UpgradeStatus {
        SUCCESS,
        NO_STATE,
        NO_PERMISSION,
        DISABLED,
        MAX_LEVEL,
        MISSING_ITEMS,
        NO_ECONOMY,
        INSUFFICIENT_FUNDS,
        PAYMENT_FAILED
    }

    public enum ModuleStatus {
        PURCHASED,
        ENABLED,
        DISABLED,
        NO_STATE,
        NO_PERMISSION,
        CONFIG_DISABLED,
        MISSING_ITEMS,
        NO_ECONOMY,
        INSUFFICIENT_FUNDS,
        PAYMENT_FAILED
    }

    public enum PaymentSource {
        NONE,
        BANK,
        PLAYER
    }

    public static class TaxUpdateResponse {
        private final TaxUpdateStatus status;
        private final double amount;

        private TaxUpdateResponse(TaxUpdateStatus status, double amount) {
            this.status = status;
            this.amount = amount;
        }

        public static TaxUpdateResponse of(TaxUpdateStatus status, double amount) {
            return new TaxUpdateResponse(status, amount);
        }

        public TaxUpdateStatus getStatus() {
            return status;
        }

        public double getAmount() {
            return amount;
        }
    }

    public enum TaxUpdateStatus {
        SUCCESS,
        NO_STATE,
        NOT_CAPTAIN,
        INVALID_AMOUNT,
        DISABLED
    }

    private record CreationSettings(double cost, Map<ItemDescriptor, Integer> items, long cooldownMs) { }
    private record RepairSettings(double restore, double cost, Map<ItemDescriptor, Integer> materials) { }
    private record MaintenanceSettings(double cost, Map<ItemDescriptor, Integer> materials) { }
    private record MoveSettings(Material material, String displayName, double cost, Map<ItemDescriptor, Integer> materials) { }
    private record CapitalMoveSettings(double cost, Map<ItemDescriptor, Integer> materials) { }

    public static class ItemDescriptor {
        private final String key;
        private final Material material;
        private final String customId;
        private final String display;
        private final String identity;

        private ItemDescriptor(String key, Material material, String customId, String display) {
            this.key = key;
            this.material = material;
            this.customId = customId;
            this.display = display;
            this.identity = material != null
                    ? material.name()
                    : (customId != null ? customId : key.toLowerCase(Locale.ROOT));
        }

        public Material getMaterial() {
            return material;
        }

        public String getDisplay() {
            return display;
        }

        public String getIdentity() {
            return identity;
        }

        public boolean matches(CampSystem plugin, ItemStack stack, String namespacedId) {
            if (stack == null && namespacedId == null) {
                return false;
            }
            if (material != null && stack != null) {
                return stack.getType() == material;
            }
            if (customId != null) {
                if (namespacedId != null && namespacedId.equalsIgnoreCase(customId)) {
                    return true;
                }
                if (plugin == null || plugin.getServer().getPluginManager().getPlugin("ItemsAdder") == null) {
                    return false;
                }
                CustomStack custom = stack == null ? null : CustomStack.byItemStack(stack);
                return custom != null && custom.getNamespacedID() != null
                        && custom.getNamespacedID().equalsIgnoreCase(customId);
            }
            return false;
        }

        public boolean matches(CampSystem plugin, ItemStack stack) {
            return matches(plugin, stack, null);
        }

        public ItemStack createItem(CampSystem plugin, int amount) {
            int resolved = Math.max(1, amount);
            if (material != null) {
                return new ItemStack(material, resolved);
            }
            if (customId != null && plugin != null
                    && plugin.getServer().getPluginManager().getPlugin("ItemsAdder") != null) {
                CustomStack custom = CustomStack.getInstance(customId);
                if (custom != null) {
                    ItemStack stack = custom.getItemStack();
                    if (stack != null) {
                        stack.setAmount(resolved);
                        return stack;
                    }
                }
            }
            return null;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ItemDescriptor other)) {
                return false;
            }
            return Objects.equals(identity, other.identity);
        }

        @Override
        public int hashCode() {
            return Objects.hash(identity);
        }
    }

    public static class BankTransaction {
        private final long timestamp;
        private final BankTransactionType type;
        private final UUID actor;
        private final double amount;
        private final double balance;

        private BankTransaction(long timestamp, BankTransactionType type, UUID actor, double amount, double balance) {
            this.timestamp = timestamp;
            this.type = type;
            this.actor = actor;
            this.amount = amount;
            this.balance = balance;
        }

        public static BankTransaction deposit(UUID actor, double amount, double balance) {
            return new BankTransaction(System.currentTimeMillis(), BankTransactionType.DEPOSIT, actor, amount, balance);
        }

        public static BankTransaction withdraw(UUID actor, double amount, double balance) {
            return new BankTransaction(System.currentTimeMillis(), BankTransactionType.WITHDRAW, actor, amount, balance);
        }

        public static BankTransaction tax(UUID actor, double amount, double balance) {
            return new BankTransaction(System.currentTimeMillis(), BankTransactionType.TAX, actor, amount, balance);
        }

        public static BankTransaction expense(UUID actor, double amount, double balance) {
            return new BankTransaction(System.currentTimeMillis(), BankTransactionType.EXPENSE, actor, amount, balance);
        }

        public static BankTransaction fromData(long timestamp, BankTransactionType type, UUID actor, double amount, double balance) {
            return new BankTransaction(timestamp, type, actor, amount, balance);
        }

        public long getTimestamp() {
            return timestamp;
        }

        public BankTransactionType getType() {
            return type;
        }

        public UUID getActor() {
            return actor;
        }

        public double getAmount() {
            return amount;
        }

        public double getBalance() {
            return balance;
        }
    }

    public enum BankTransactionType {
        DEPOSIT,
        WITHDRAW,
        TAX,
        EXPENSE
    }

    public static class BankActionResponse {
        private final BankResult result;
        private final double amount;
        private final double newBalance;

        private BankActionResponse(BankResult result, double amount, double newBalance) {
            this.result = result;
            this.amount = amount;
            this.newBalance = newBalance;
        }

        public static BankActionResponse of(BankResult result, double amount, double newBalance) {
            return new BankActionResponse(result, amount, newBalance);
        }

        public BankResult getResult() {
            return result;
        }

        public double getAmount() {
            return amount;
        }

        public double getNewBalance() {
            return newBalance;
        }
    }

    public enum BankResult {
        SUCCESS,
        NO_STATE,
        NO_ECONOMY,
        DISABLED,
        INVALID_AMOUNT,
        INSUFFICIENT_PLAYER_FUNDS,
        INSUFFICIENT_BANK_FUNDS,
        FAILED_TRANSACTION,
        NOT_CAPTAIN
    }

    // 数据结构
    public static class StateData {
        public String name;
        public UUID captain;
        public Set<UUID> members = new HashSet<>();
        public Map<String, SectorData> sectors = new LinkedHashMap<>();
        public String capitalSector;
        public double bankBalance;
        public double taxAmount;
        public Deque<BankTransaction> transactions = new ArrayDeque<>();
        public String ideologyId;
        public long ideologyChangedAt;

        public StateData(String name, UUID captain) {
            this.name = name;
            this.captain = captain;
            this.members.add(captain);
        }
    }

    public record IdeologyChangeResult(boolean allowed, long remainingMillis) {
        public static IdeologyChangeResult success() { return new IdeologyChangeResult(true, 0L); }
        public static IdeologyChangeResult cooldown(long remaining) { return new IdeologyChangeResult(false, Math.max(0L, remaining)); }
        public static IdeologyChangeResult invalid() { return new IdeologyChangeResult(false, 0L); }
    }

    public static class InviteData {
        public final String state;
        public final long time;
        public InviteData(String state, long time) { this.state = state; this.time = time; }
    }

    public static class JoinRequest {
        public final UUID playerId;
        public final String playerName;
        public final String state;
        public final long time;

        public JoinRequest(UUID playerId, String playerName, String state, long time) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.state = state;
            this.time = time;
        }
    }

    public static class TpaRequest {
        private final UUID requester;
        private final UUID target;
        private final long createdAt;

        public TpaRequest(UUID requester, UUID target, long createdAt) {
            this.requester = requester;
            this.target = target;
            this.createdAt = createdAt;
        }
    }

    public static class SectorGiftRequest {
        private final String sourceState;
        private final String targetState;
        private final String sector;
        private final long createdAt;

        public SectorGiftRequest(String sourceState, String targetState, String sector, long createdAt) {
            this.sourceState = sourceState;
            this.targetState = targetState;
            this.sector = sector;
            this.createdAt = createdAt;
        }

        public String getSourceState() {
            return sourceState;
        }

        public String getTargetState() {
            return targetState;
        }

        public String getSector() {
            return sector;
        }

        public long getCreatedAt() {
            return createdAt;
        }
    }

    public enum SectorGiftRequestStatus {
        SUCCESS,
        NO_STATE,
        NOT_CAPTAIN,
        TARGET_NOT_FOUND,
        TARGET_OFFLINE,
        NO_SUCH_SECTOR,
        CAPITAL_SECTOR,
        ALREADY_PENDING,
        SAME_STATE
    }

    public static class SectorGiftRequestResult {
        private final SectorGiftRequestStatus status;
        private final String targetState;
        private final String sector;
        private final long remainingMs;

        private SectorGiftRequestResult(SectorGiftRequestStatus status, String targetState, String sector, long remainingMs) {
            this.status = status;
            this.targetState = targetState;
            this.sector = sector;
            this.remainingMs = remainingMs;
        }

        public static SectorGiftRequestResult of(SectorGiftRequestStatus status, String target, String sector, long remainingMs) {
            return new SectorGiftRequestResult(status, target, sector, remainingMs);
        }

        public SectorGiftRequestStatus getStatus() {
            return status;
        }

        public String getTargetState() {
            return targetState;
        }

        public String getSector() {
            return sector;
        }

        public long getRemainingMs() {
            return remainingMs;
        }
    }

    public enum SectorGiftResponseStatus {
        ACCEPTED,
        DENIED,
        NO_REQUEST,
        NO_STATE,
        MULTIPLE,
        EXPIRED,
        TRANSFER_FAILED
    }

    public static class SectorGiftResponseResult {
        private final SectorGiftResponseStatus status;
        private final SectorGiftRequest request;
        private final String newSector;
        private final List<SectorGiftRequest> pending;

        private SectorGiftResponseResult(SectorGiftResponseStatus status, SectorGiftRequest request, String newSector, List<SectorGiftRequest> pending) {
            this.status = status;
            this.request = request;
            this.newSector = newSector;
            this.pending = pending == null ? List.of() : List.copyOf(pending);
        }

        public static SectorGiftResponseResult accepted(SectorGiftRequest request, String newSector) {
            return new SectorGiftResponseResult(SectorGiftResponseStatus.ACCEPTED, request, newSector, null);
        }

        public static SectorGiftResponseResult denied(SectorGiftRequest request) {
            return new SectorGiftResponseResult(SectorGiftResponseStatus.DENIED, request, null, null);
        }

        public static SectorGiftResponseResult noRequest() {
            return new SectorGiftResponseResult(SectorGiftResponseStatus.NO_REQUEST, null, null, null);
        }

        public static SectorGiftResponseResult noState() {
            return new SectorGiftResponseResult(SectorGiftResponseStatus.NO_STATE, null, null, null);
        }

        public static SectorGiftResponseResult multiple(List<SectorGiftRequest> requests) {
            return new SectorGiftResponseResult(SectorGiftResponseStatus.MULTIPLE, null, null, requests);
        }

        public static SectorGiftResponseResult expired(SectorGiftRequest request) {
            return new SectorGiftResponseResult(SectorGiftResponseStatus.EXPIRED, request, null, null);
        }

        public static SectorGiftResponseResult transferFailed(SectorGiftRequest request) {
            return new SectorGiftResponseResult(SectorGiftResponseStatus.TRANSFER_FAILED, request, null, null);
        }

        public SectorGiftResponseStatus getStatus() {
            return status;
        }

        public SectorGiftRequest getRequest() {
            return request;
        }

        public String getNewSector() {
            return newSector;
        }

        public List<SectorGiftRequest> getPending() {
            return pending;
        }
    }

    public static class PendingCampData {
        private String state;
        private final String sector;
        private Location placedLocation;
        private UUID owner;
        private boolean relocation;
        private Camp storedCamp;
        private Material relocationMaterial;
        private String relocationTokenName;

        public PendingCampData(String state, String sector) {
            this.state = state;
            this.sector = sector;
        }

        public String getState() { return state; }
        public String getSector() { return sector; }
        public Location getPlacedLocation() { return placedLocation; }
        public void setPlacedLocation(Location placedLocation) { this.placedLocation = placedLocation; }
        public void setState(String state) { this.state = state; }
        public UUID getOwner() { return owner; }
        public void setOwner(UUID owner) { this.owner = owner; }
        public boolean isRelocation() { return relocation; }
        public void setRelocation(boolean relocation) { this.relocation = relocation; }
        public Camp getStoredCamp() { return storedCamp; }
        public void setStoredCamp(Camp storedCamp) { this.storedCamp = storedCamp; }
        public Material getRelocationMaterial() { return relocationMaterial; }
        public void setRelocationMaterial(Material relocationMaterial) { this.relocationMaterial = relocationMaterial; }
        public String getRelocationTokenName() { return relocationTokenName; }
        public void setRelocationTokenName(String relocationTokenName) { this.relocationTokenName = relocationTokenName; }
    }

    public static class SectorData {
        private final String name;
        private Location location;
        private UUID owner;

        public SectorData(String name, Location location, UUID owner) {
            this.name = name;
            setLocation(location);
            this.owner = owner;
        }

        public String getName() {
            return name;
        }

        public Location getLocation() {
            return location == null ? null : location.clone();
        }

        public void setLocation(Location location) {
            this.location = location == null ? null : location.clone();
        }

        public UUID getOwner() {
            return owner;
        }

        public void setOwner(UUID owner) {
            this.owner = owner;
        }
    }

    public static class CampSectorInfo {
        private final String stateName;
        private final String sectorName;

        public CampSectorInfo(String stateName, String sectorName) {
            this.stateName = stateName;
            this.sectorName = sectorName;
        }

        public String stateName() {
            return stateName;
        }

        public String sectorName() {
            return sectorName;
        }
    }

    public enum StateRole {
        NONE,
        MEMBER,
        GOVERNOR,
        CAPTAIN
    }

    public boolean isCapitalSector(String state, String sector) {
        StateData data = states.get(state);
        if (data == null || sector == null) {
            return false;
        }
        return data.capitalSector != null && data.capitalSector.equalsIgnoreCase(sector);
    }

    public static class CapitalMoveResponse {
        private final CapitalMoveStatus status;
        private final String resolvedSector;
        private final double cost;
        private final PaymentSource source;
        private final String requirementText;
        private final long cooldownMs;

        private CapitalMoveResponse(CapitalMoveStatus status, String resolvedSector, double cost,
                                    PaymentSource source, String requirementText, long cooldownMs) {
            this.status = status;
            this.resolvedSector = resolvedSector;
            this.cost = cost;
            this.source = source;
            this.requirementText = requirementText;
            this.cooldownMs = cooldownMs;
        }

        public static CapitalMoveResponse of(CapitalMoveStatus status, String resolvedSector, double cost,
                                             PaymentSource source, String requirementText, long cooldownMs) {
            return new CapitalMoveResponse(status, resolvedSector, cost, source, requirementText, cooldownMs);
        }

        public CapitalMoveStatus getStatus() {
            return status;
        }

        public String getResolvedSector() {
            return resolvedSector;
        }

        public double getCost() {
            return cost;
        }

        public PaymentSource getPaymentSource() {
            return source;
        }

        public String getRequirementText() {
            return requirementText;
        }

        public long getCooldownMs() {
            return cooldownMs;
        }
    }

    public enum CapitalMoveStatus {
        SUCCESS,
        NOT_IN_STATE,
        NOT_CAPTAIN,
        NO_SUCH_SECTOR,
        ALREADY_CAPITAL,
        IN_WAR,
        COOLDOWN,
        NO_ECONOMY,
        INSUFFICIENT_FUNDS,
        PAYMENT_FAILED,
        MISSING_ITEMS
    }

    public enum CivilWarStatus {
        SUCCESS,
        NOT_IN_STATE,
        INVALID_STATE,
        IS_CAPTAIN,
        CAPTAIN_OFFLINE,
        MISSING_CAMP_ITEM
    }

    public static class CivilWarResult {
        private final CivilWarStatus status;
        private final String origin;
        private final String rebel;
        private final String sector;

        private CivilWarResult(CivilWarStatus status, String origin, String rebel, String sector) {
            this.status = status;
            this.origin = origin;
            this.rebel = rebel;
            this.sector = sector;
        }

        public static CivilWarResult success(String origin, String rebel, String sector) {
            return new CivilWarResult(CivilWarStatus.SUCCESS, origin, rebel, sector);
        }

        public static CivilWarResult failure(CivilWarStatus status) {
            return new CivilWarResult(status, null, null, null);
        }

        public CivilWarStatus getStatus() {
            return status;
        }

        public String getOrigin() {
            return origin;
        }

        public String getRebel() {
            return rebel;
        }

        public String getSector() {
            return sector;
        }
    }

    public enum TeleportStatus {
        SUCCESS,
        NO_STATE,
        SECTOR_NOT_FOUND,
        MISSING_LOCATION
    }

    public static class TeleportResult {
        private final TeleportStatus status;
        private final String sector;
        private final long warmupMillis;

        private TeleportResult(TeleportStatus status, String sector, long warmupMillis) {
            this.status = status;
            this.sector = sector;
            this.warmupMillis = warmupMillis;
        }

        public static TeleportResult of(TeleportStatus status, String sector, long warmupMillis) {
            return new TeleportResult(status, sector, Math.max(0L, warmupMillis));
        }

        public TeleportStatus getStatus() { return status; }

        public String getSector() { return sector; }

        public long getWarmupMillis() { return warmupMillis; }
    }
}
