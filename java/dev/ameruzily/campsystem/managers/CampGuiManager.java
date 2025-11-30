package dev.ameruzily.campsystem.managers;

import dev.ameruzily.campsystem.CampSystem;
import dev.ameruzily.campsystem.managers.StateManager;
import dev.ameruzily.campsystem.managers.WarManager;
import dev.ameruzily.campsystem.models.Camp;
import dev.ameruzily.campsystem.models.Ideology;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CampGuiManager implements Listener {
    private final CampSystem plugin;
    private GuiLayout mainLayout;
    private GuiLayout ideologyLayout;
    private GuiLayout upgradeLayout;
    private FuelLayout fuelLayout;
    private final Map<UUID, GuiContext> contexts = new HashMap<>();
    private final Map<UUID, PendingInput> pendingInputs = new ConcurrentHashMap<>();
    private final Set<UUID> switching = new HashSet<>();
    private final DecimalFormat format = new DecimalFormat("0.0");
    private org.bukkit.scheduler.BukkitTask refreshTask;

    public CampGuiManager(CampSystem plugin) {
        this.plugin = plugin;
        reload();
        startRefreshTask();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void reload() {
        closeAllContexts();
        loadLayouts();
    }

    public void closeAll() {
        closeAllContexts();
    }

    private void loadLayouts() {
        File file = new File(plugin.getDataFolder(), "camp-gui.yml");
        if (!file.exists()) {
            plugin.saveResource("camp-gui.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        mainLayout = GuiLayout.fromConfig(yaml.getConfigurationSection("gui.main"));
        ideologyLayout = GuiLayout.fromConfig(yaml.getConfigurationSection("gui.ideology"));
        upgradeLayout = GuiLayout.fromConfig(yaml.getConfigurationSection("gui.upgrades"));
        fuelLayout = FuelLayout.fromConfig(yaml.getConfigurationSection("gui.fuel"));
    }

    private void startRefreshTask() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        refreshTask = new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                updateOpenInventories();
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void updateOpenInventories() {
        if (contexts.isEmpty()) {
            return;
        }
        Map<UUID, GuiContext> snapshot = new HashMap<>(contexts);
        for (Map.Entry<UUID, GuiContext> entry : snapshot.entrySet()) {
            UUID uuid = entry.getKey();
            GuiContext context = entry.getValue();
            Player player = plugin.getServer().getPlayer(uuid);
            if (player == null) {
                contexts.remove(uuid);
                continue;
            }
            if (!isViewing(player, context)) {
                contexts.remove(uuid);
                continue;
            }
            Camp camp = plugin.war().getCamp(context.camp.getStateName(), context.camp.getSectorName());
            if (camp == null || context.inventory == null) {
                contexts.remove(uuid);
                continue;
            }
            FuelSnapshot fuel = fuelSnapshot(camp);
            Map<String, String> extras = buildGuiPlaceholders(camp);
            for (Map.Entry<Integer, GuiButton> buttonEntry : context.buttons.entrySet()) {
                GuiButton button = buttonEntry.getValue();
                ItemStack item = renderButton(button, camp, fuel, extras);
                context.inventory.setItem(buttonEntry.getKey(), item);
            }
        }
    }

    private void handleRenameSector(Player player, Camp camp) {
        if (player == null || camp == null) {
            return;
        }
        if (!ensureSameState(player, camp)) {
            return;
        }
        if (!plugin.state().canManageCamp(player, camp)) {
            plugin.lang().send(player, "state.sector-no-permission");
            return;
        }
        beginRename(player, camp, InputType.RENAME_SECTOR);
    }

    private void handleRenameState(Player player, Camp camp) {
        if (player == null || camp == null) {
            return;
        }
        if (!ensureSameState(player, camp)) {
            return;
        }
        if (!plugin.state().isCaptain(player)) {
            plugin.lang().send(player, "war.not-captain");
            return;
        }
        if (!plugin.state().isCapitalSector(camp.getStateName(), camp.getSectorName())) {
            plugin.lang().send(player, "state.gui.rename-state-not-capital");
            return;
        }
        beginRename(player, camp, InputType.RENAME_STATE);
    }

    private void handleMoveButton(Player player, Camp camp) {
        if (player == null || camp == null) {
            return;
        }
        if (!ensureSameState(player, camp)) {
            return;
        }
        if (!plugin.state().canManageCamp(player, camp)) {
            plugin.lang().send(player, "state.sector-no-permission");
            return;
        }
        StateManager.MoveSectorResult result = plugin.state().moveSector(player, camp.getSectorName());
        handleMoveResult(player, result, camp.getSectorName());
    }

    private void handleCollectProduction(Player player, Camp camp) {
        if (player == null || camp == null) {
            return;
        }
        if (!ensureSameState(player, camp)) {
            return;
        }
        WarManager.ProductionClaimResult result = plugin.war().claimProduction(player, camp);
        switch (result.getStatus()) {
            case NO_PERMISSION -> plugin.lang().send(player, "state.sector-no-permission");
            case EMPTY -> plugin.lang().send(player, "state.production-empty");
            case NO_ECONOMY -> plugin.lang().send(player, "state.production-no-economy");
            case SUCCESS -> {
                Map<String, String> vars = new HashMap<>();
                vars.put("money", plugin.state().formatMoney(result.getMoney()));
                vars.put("items", formatProductionItems(result.getItems()));
                plugin.lang().send(player, "state.production-claimed", vars);
            }
        }
    }

    private String formatProductionItems(Map<String, Integer> items) {
        if (items == null || items.isEmpty()) {
            return plugin.lang().messageOrDefault("state.repair-items-none", "无");
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            parts.add(entry.getKey() + " x" + entry.getValue());
        }
        return String.join(", ", parts);
    }

    private void handleMoveResult(Player player, StateManager.MoveSectorResult result, String fallbackSector) {
        if (player == null || result == null) {
            return;
        }
        String sectorName = result.getSector() != null ? result.getSector() : fallbackSector;
        switch (result.getStatus()) {
            case NOT_IN_STATE -> plugin.lang().send(player, "camp.not-found");
            case NO_SUCH_SECTOR -> plugin.lang().send(player, "state.sector-not-found", Map.of("sector", fallbackSector));
            case NOT_AUTHORIZED -> plugin.lang().send(player, "state.sector-no-permission");
            case CAPITAL_SECTOR -> plugin.lang().send(player, "state.sector-capital-protected");
            case PENDING_PLACEMENT -> plugin.lang().send(player, "state.pending-placement");
            case MISSING_LOCATION -> plugin.lang().send(player, "state.sector-move-missing", Map.of(
                    "sector", sectorName
            ));
            case NO_ECONOMY -> plugin.lang().send(player, "state.sector-move-no-economy");
            case INSUFFICIENT_FUNDS -> plugin.lang().send(player, "state.sector-move-no-funds", Map.of(
                    "cost", plugin.state().formatMoney(result.getCost())
            ));
            case PAYMENT_FAILED -> plugin.lang().send(player, "state.sector-move-payment-failed");
            case MISSING_ITEMS -> {
                String requirement = result.getRequirementText();
                if (requirement == null || requirement.isEmpty()) {
                    requirement = plugin.lang().messageOrDefault("state.repair-items-none", "无");
                }
                plugin.lang().send(player, "state.sector-move-missing-items", Map.of("items", requirement));
            }
            case INVENTORY_FULL -> plugin.lang().send(player, "state.sector-move-inventory");
            case SUCCESS -> {
                Map<String, String> vars = new HashMap<>();
                vars.put("sector", sectorName);
                vars.put("cost", plugin.state().formatMoney(result.getCost()));
                String requirement = result.getRequirementText();
                if (requirement == null || requirement.isEmpty()) {
                    requirement = plugin.lang().messageOrDefault("state.repair-items-none", "无");
                }
                vars.put("items", requirement);
                vars.put("source", switch (result.getPaymentSource()) {
                    case BANK -> plugin.lang().messageOrDefault("state.payment-source.bank", "国库");
                    case PLAYER -> plugin.lang().messageOrDefault("state.payment-source.player", "个人");
                    default -> plugin.lang().messageOrDefault("state.payment-source.none", "无");
                });
                vars.put("token", plugin.lang().colorizeText(plugin.getConfig().getString("camp.move.item-name", "&f放置重建")));
                plugin.lang().send(player, "state.sector-move-start", vars);
            }
        }
    }

    private void handleOpenIdeologyButton(Player player, Camp camp) {
        if (player == null || camp == null) {
            return;
        }
        if (ideologyLayout == null) {
            plugin.lang().send(player, "general.unknown-error");
            return;
        }
        if (!ensureSameState(player, camp)) {
            return;
        }
        if (!plugin.state().isCaptain(player)) {
            plugin.lang().send(player, "war.not-captain");
            return;
        }
        if (!plugin.state().isCapitalSector(camp.getStateName(), camp.getSectorName())) {
            plugin.lang().send(player, "state.gui.ideology-not-capital");
            return;
        }
        openIdeology(player, camp);
    }

    private void handleIdeologySelect(Player player, Camp camp, String ideologyId) {
        if (player == null || camp == null || ideologyId == null || ideologyId.isEmpty()) {
            return;
        }
        if (!ensureSameState(player, camp)) {
            return;
        }
        if (!plugin.state().isCaptain(player)) {
            plugin.lang().send(player, "war.not-captain");
            return;
        }
        if (!plugin.state().isCapitalSector(camp.getStateName(), camp.getSectorName())) {
            plugin.lang().send(player, "state.gui.ideology-not-capital");
            return;
        }
        Ideology ideology = plugin.ideology().get(ideologyId);
        if (ideology == null) {
            plugin.lang().send(player, "ideology.invalid-id");
            return;
        }
        StateManager.IdeologyChangeResult result = plugin.state().setIdeology(player, ideology);
        if (!result.allowed()) {
            long remaining = result.remainingMillis();
            if (remaining > 0) {
                plugin.lang().send(player, "ideology.cooldown", Map.of(
                        "time", plugin.war().formatDuration(remaining)
                ));
            } else {
                plugin.lang().send(player, "general.unknown-error");
            }
            return;
        }
        plugin.lang().send(player, "ideology.set-success", Map.of("display", ideology.getDisplayName()));
        openIdeology(player, camp);
    }

    private void handleUpgrade(Player player, Camp camp, String rawType) {
        if (player == null || camp == null || rawType == null) {
            return;
        }
        WarManager.CampUpgradeType type = null;
        for (WarManager.CampUpgradeType value : WarManager.CampUpgradeType.values()) {
            if (value.configKey().equalsIgnoreCase(rawType)) {
                type = value;
                break;
            }
        }
        if (type == null) {
            plugin.lang().send(player, "state.upgrade-invalid");
            return;
        }
        if (!plugin.state().canManageCamp(player, camp)) {
            plugin.lang().send(player, "state.sector-no-permission");
            return;
        }
        StateManager.CampUpgradeOutcome outcome = plugin.state().upgradeCamp(player, camp, type);
        Map<String, String> vars = Map.of(
                "type", plugin.lang().messageOrDefault("state.upgrade.name-" + type.configKey(), type.configKey()),
                "level", outcome.getTier() == null ? "0" : String.valueOf(outcome.getTier().level()),
                "cost", outcome.getDisplayCost(),
                "items", outcome.getRequiredItems()
        );
        switch (outcome.getStatus()) {
            case SUCCESS -> {
                plugin.lang().sendActionBar(player, "state.upgrade-success", vars);
                if (plugin.upgradeSuccessSound() != null) {
                    plugin.upgradeSuccessSound().play(player);
                }
            }
            case NO_PERMISSION -> plugin.lang().send(player, "state.sector-no-permission");
            case DISABLED -> plugin.lang().send(player, "state.upgrade-disabled");
            case MAX_LEVEL -> plugin.lang().send(player, "state.upgrade-max");
            case MISSING_ITEMS -> plugin.lang().send(player, "state.upgrade-missing-items", vars);
            case NO_ECONOMY -> plugin.lang().send(player, "state.capital-move-no-economy");
            case INSUFFICIENT_FUNDS -> plugin.lang().send(player, "state.capital-move-no-funds", vars);
            case PAYMENT_FAILED -> plugin.lang().send(player, "state.capital-move-payment-failed");
            default -> plugin.lang().send(player, "general.unknown-error");
        }
        openUpgrades(player, camp);
    }

    private void handleCapitalButton(Player player, Camp camp) {
        if (player == null || camp == null) {
            return;
        }
        if (!ensureSameState(player, camp)) {
            return;
        }
        if (!plugin.state().isCaptain(player)) {
            plugin.lang().send(player, "war.not-captain");
            return;
        }
        if (plugin.state().isCapitalSector(camp.getStateName(), camp.getSectorName())) {
            plugin.lang().send(player, "state.capital-already", Map.of("sector", camp.getSectorName()));
            return;
        }

        String stateName = camp.getStateName();
        if (plugin.war().isStateAtWar(stateName)) {
            WarManager.EmergencyMoveResult response = plugin.war().requestEmergencyMove(stateName, camp.getSectorName());
            switch (response.getStatus()) {
                case NO_SUCH_SECTOR -> plugin.lang().send(player, "war.move-no-sector", Map.of("sector", camp.getSectorName()));
                case NOT_IN_WAR -> plugin.lang().send(player, "war.move-not-war");
                case ALREADY_USED -> plugin.lang().send(player, "war.move-used");
                case COOLDOWN -> plugin.lang().send(player, "war.move-cooldown", Map.of(
                        "time", plugin.war().formatDuration(response.getRemainingMs())
                ));
                case ALREADY_CAPITAL -> {
                    String sector = response.getResolvedSector() == null ? camp.getSectorName() : response.getResolvedSector();
                    plugin.lang().send(player, "war.move-already", Map.of("sector", sector));
                }
                case SUCCESS -> {
                    String resolved = response.getResolvedSector();
                    StateManager.CapitalMoveResponse move = plugin.state().moveCapital(player, resolved, true);
                    if (move.getStatus() == StateManager.CapitalMoveStatus.SUCCESS) {
                        plugin.lang().send(player, "war.move-success", Map.of("sector", resolved));
                        if (response.getOpponent() != null) {
                            plugin.war().notifyEmergencyMove(stateName, response.getOpponent(), resolved);
                        }
                    } else {
                        sendCapitalMoveFailure(player, move, resolved != null ? resolved : camp.getSectorName(), true);
                    }
                }
            }
            return;
        }

        StateManager.CapitalMoveResponse response = plugin.state().moveCapital(player, camp.getSectorName(), false);
        if (response.getStatus() == StateManager.CapitalMoveStatus.SUCCESS) {
            String resolved = response.getResolvedSector() == null ? camp.getSectorName() : response.getResolvedSector();
        } else {
            sendCapitalMoveFailure(player, response, camp.getSectorName(), false);
        }
    }

    private void sendCapitalMoveFailure(Player player, StateManager.CapitalMoveResponse response, String sectorInput, boolean emergency) {
        if (player == null || response == null) {
            return;
        }
        switch (response.getStatus()) {
            case NOT_IN_STATE -> plugin.lang().send(player, "camp.not-found");
            case NOT_CAPTAIN -> plugin.lang().send(player, "war.not-captain");
            case NO_SUCH_SECTOR -> {
                String key = emergency ? "war.move-no-sector" : "state.sector-not-found";
                plugin.lang().send(player, key, Map.of("sector", sectorInput));
            }
            case ALREADY_CAPITAL -> {
                String key = emergency ? "war.move-already" : "state.capital-already";
                plugin.lang().send(player, key, Map.of("sector", sectorInput));
            }
            case IN_WAR -> plugin.lang().send(player, "state.capital-move-war");
            case COOLDOWN -> {
                long remaining = Math.max(0L, response.getCooldownMs());
                String time = plugin.war().formatDuration(remaining);
                String key = emergency ? "war.move-cooldown" : "state.capital-move-cooldown";
                plugin.lang().send(player, key, Map.of("time", time));
            }
            case NO_ECONOMY -> plugin.lang().send(player, "state.capital-move-no-economy");
            case INSUFFICIENT_FUNDS -> plugin.lang().send(player, "state.capital-move-no-funds", Map.of(
                    "cost", plugin.state().formatMoney(response.getCost())
            ));
            case PAYMENT_FAILED -> plugin.lang().send(player, "state.capital-move-payment-failed");
            case MISSING_ITEMS -> {
                String requirement = response.getRequirementText();
                if (requirement == null || requirement.isEmpty()) {
                    requirement = plugin.lang().messageOrDefault("state.repair-items-none", "无");
                }
                plugin.lang().send(player, "state.capital-move-missing-items", Map.of("items", requirement));
            }
            default -> plugin.lang().send(player, "general.unknown-error");
        }
    }

    private void beginRename(Player player, Camp camp, InputType type) {
        if (player == null || camp == null || type == null) {
            return;
        }
        pendingInputs.put(player.getUniqueId(), new PendingInput(type, camp));
        player.closeInventory();
        Map<String, String> vars = new HashMap<>();
        vars.put("state", camp.getStateName());
        vars.put("sector", camp.getSectorName());
        vars.put("cancel", getCancelWord());
        String key = type == InputType.RENAME_SECTOR ? "state.gui.rename-sector-prompt" : "state.gui.rename-state-prompt";
        plugin.lang().send(player, key, vars);
    }

    private void handleChatInput(Player player, PendingInput pending, String message) {
        if (player == null || pending == null) {
            return;
        }
        PendingInput active = pendingInputs.get(player.getUniqueId());
        if (active == null || active != pending) {
            return;
        }
        String trimmed = message == null ? "" : message.trim();
        String cancelWord = getCancelWord();
        if (trimmed.equalsIgnoreCase(cancelWord)) {
            pendingInputs.remove(player.getUniqueId());
            plugin.lang().send(player, "state.gui.rename-cancelled");
            return;
        }
        if (trimmed.isEmpty()) {
            plugin.lang().send(player, "state.gui.rename-empty");
            return;
        }

        switch (pending.type()) {
            case RENAME_SECTOR -> {
                Camp camp = pending.camp();
                if (camp == null) {
                    pendingInputs.remove(player.getUniqueId());
                    plugin.lang().send(player, "state.rename-sector-failed");
                    return;
                }
                boolean success = plugin.state().renameSector(player, camp, trimmed);
                if (success) {
                    pendingInputs.remove(player.getUniqueId());
                }
            }
            case RENAME_STATE -> {
                boolean success = plugin.state().renameStateName(player, trimmed);
                if (success) {
                    pendingInputs.remove(player.getUniqueId());
                }
            }
        }
    }

    private boolean ensureSameState(Player player, Camp camp) {
        if (player == null || camp == null) {
            return false;
        }
        String playerState = plugin.state().getStateName(player);
        if (playerState == null || !playerState.equalsIgnoreCase(camp.getStateName())) {
            plugin.lang().send(player, "camp.not-found");
            return false;
        }
        return true;
    }

    private String getCancelWord() {
        String value = plugin.lang().messageOrDefault("state.gui.rename-cancel-word", "cancel");
        return value == null ? "cancel" : value;
    }

    public void openMain(Player player, Camp camp) {
        if (player == null || camp == null || mainLayout == null) {
            return;
        }
        FuelSnapshot snapshot = fuelSnapshot(camp);
        Map<String, String> extras = buildGuiPlaceholders(camp);
        Inventory inv = plugin.getServer().createInventory(null, mainLayout.rows * 9, colorize(applyCamp(mainLayout.title, camp, snapshot, extras)));
        Map<Integer, GuiButton> buttons = new HashMap<>();
        String playerState = plugin.state().getStateName(player);
        boolean canOpenIdeology = ideologyLayout != null
                && playerState != null
                && playerState.equalsIgnoreCase(camp.getStateName())
                && plugin.state().isCaptain(player)
                && plugin.state().isCapitalSector(camp.getStateName(), camp.getSectorName());
        for (GuiButton button : mainLayout.buttons.values()) {
            if (button.action != null && button.action.equalsIgnoreCase("open:ideology") && !canOpenIdeology) {
                continue;
            }
            ItemStack item = renderButton(button, camp, snapshot, extras);
            placeButton(inv, buttons, button, item);
        }
        contexts.put(player.getUniqueId(), new GuiContext(camp, GuiPage.MAIN, buttons, inv, extras));
        switching.add(player.getUniqueId());
        player.openInventory(inv);
    }

    private void openFuel(Player player, Camp camp) {
        if (player == null || camp == null || fuelLayout == null) {
            return;
        }
        FuelSnapshot snapshot = fuelSnapshot(camp);
        Map<String, String> extras = buildGuiPlaceholders(camp);
        Inventory inv = plugin.getServer().createInventory(null, fuelLayout.rows * 9, colorize(applyCamp(fuelLayout.title, camp, snapshot, extras)));
        Map<Integer, GuiButton> buttons = new HashMap<>();
        if (fuelLayout.displayButton != null) {
            ItemStack display = renderButton(fuelLayout.displayButton, camp, snapshot, extras);
            placeButton(inv, buttons, fuelLayout.displayButton, display);
        }
        for (FuelOption option : fuelLayout.options.values()) {
            ItemStack item = renderButton(option.button, camp, snapshot, extras);
            placeButton(inv, buttons, option.button, item);
        }
        if (fuelLayout.backButton != null) {
            ItemStack back = renderButton(fuelLayout.backButton, camp, snapshot, extras);
            placeButton(inv, buttons, fuelLayout.backButton, back);
        }
        contexts.put(player.getUniqueId(), new GuiContext(camp, GuiPage.FUEL, buttons, inv, extras));
        switching.add(player.getUniqueId());
        player.openInventory(inv);
    }

    private void openIdeology(Player player, Camp camp) {
        if (player == null || camp == null || ideologyLayout == null) {
            return;
        }
        FuelSnapshot snapshot = fuelSnapshot(camp);
        Map<String, String> extras = buildGuiPlaceholders(camp);
        Inventory inv = plugin.getServer().createInventory(null, ideologyLayout.rows * 9,
                colorize(applyCamp(ideologyLayout.title, camp, snapshot, extras)));
        Map<Integer, GuiButton> buttons = new HashMap<>();
        for (GuiButton button : ideologyLayout.buttons.values()) {
            ItemStack item = renderButton(button, camp, snapshot, extras);
            placeButton(inv, buttons, button, item);
        }
        contexts.put(player.getUniqueId(), new GuiContext(camp, GuiPage.IDEOLOGY, buttons, inv, extras));
        switching.add(player.getUniqueId());
        player.openInventory(inv);
    }

    private void openUpgrades(Player player, Camp camp) {
        if (player == null || camp == null || upgradeLayout == null) {
            return;
        }
        if (!ensureSameState(player, camp)) {
            return;
        }
        if (!plugin.state().canManageCamp(player, camp)) {
            plugin.lang().send(player, "state.sector-no-permission");
            return;
        }
        FuelSnapshot snapshot = fuelSnapshot(camp);
        Map<String, String> extras = buildGuiPlaceholders(camp);
        Inventory inv = plugin.getServer().createInventory(null, upgradeLayout.rows * 9,
                colorize(applyCamp(upgradeLayout.title, camp, snapshot, extras)));
        Map<Integer, GuiButton> buttons = new HashMap<>();
        for (GuiButton button : upgradeLayout.buttons.values()) {
            ItemStack item = renderButton(button, camp, snapshot, extras);
            placeButton(inv, buttons, button, item);
        }
        contexts.put(player.getUniqueId(), new GuiContext(camp, GuiPage.UPGRADES, buttons, inv, extras));
        switching.add(player.getUniqueId());
        player.openInventory(inv);
    }

    private void placeButton(Inventory inv, Map<Integer, GuiButton> buttons, GuiButton button, ItemStack baseItem) {
        if (button == null || inv == null || buttons == null || baseItem == null) {
            return;
        }
        List<Integer> slots = button.slotList();
        if (slots.isEmpty()) {
            return;
        }
        for (int i = 0; i < slots.size(); i++) {
            int slot = slots.get(i);
            if (slot < 0 || slot >= inv.getSize()) {
                continue;
            }
            ItemStack item = i == 0 ? baseItem : duplicateItem(baseItem);
            inv.setItem(slot, item);
            buttons.put(slot, button);
        }
    }

    private ItemStack duplicateItem(ItemStack baseItem) {
        if (baseItem == null) {
            return null;
        }
        try {
            return baseItem.clone();
        } catch (Exception ignored) {
            ItemStack copy = new ItemStack(baseItem.getType(), baseItem.getAmount());
            copy.setItemMeta(baseItem.getItemMeta());
            return copy;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() == null) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        GuiContext context = contexts.get(player.getUniqueId());
        if (context == null) {
            return;
        }
        if (!isViewing(player, context) || event.getInventory() == null
                || !event.getInventory().equals(context.inventory)) {
            contexts.remove(player.getUniqueId());
            return;
        }
        event.setCancelled(true);
        GuiButton button = context.buttons.get(event.getSlot());
        if (button == null) {
            return;
        }
        Camp camp = plugin.war().getCamp(context.camp.getStateName(), context.camp.getSectorName());
        if (camp == null) {
            contexts.remove(player.getUniqueId());
            return;
        }
        handleAction(player, camp, button.action);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() == null) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        if (switching.remove(uuid)) {
            return;
        }
        contexts.remove(uuid);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (event.getPlayer() == null) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        PendingInput pending = pendingInputs.get(uuid);
        if (pending == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        String message = event.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> handleChatInput(player, pending, message));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        contexts.remove(uuid);
        switching.remove(uuid);
        pendingInputs.remove(uuid);
    }

    private boolean isViewing(Player player, GuiContext context) {
        if (player == null || context == null || context.inventory == null) {
            return false;
        }
        InventoryView view = player.getOpenInventory();
        return view != null && context.inventory.equals(view.getTopInventory());
    }

    private void handleAction(Player player, Camp camp, String action) {
        if (action == null) {
            return;
        }
        if (plugin.guiClickSound() != null) {
            plugin.guiClickSound().play(player);
        }
        if (action.equalsIgnoreCase("close")) {
            player.closeInventory();
            contexts.remove(player.getUniqueId());
            return;
        }
        if (action.equalsIgnoreCase("open:fuel")) {
            openFuel(player, camp);
            return;
        }
        if (action.equalsIgnoreCase("open:main")) {
            openMain(player, camp);
            return;
        }
        if (action.equalsIgnoreCase("open:ideology")) {
            handleOpenIdeologyButton(player, camp);
            return;
        }
        if (action.equalsIgnoreCase("open:upgrades")) {
            openUpgrades(player, camp);
            return;
        }
        if (action.equalsIgnoreCase("collect:production")) {
            handleCollectProduction(player, camp);
            return;
        }
        if (action.equalsIgnoreCase("rename:sector")) {
            handleRenameSector(player, camp);
            return;
        }
        if (action.equalsIgnoreCase("rename:state")) {
            handleRenameState(player, camp);
            return;
        }
        if (action.equalsIgnoreCase("move:sector") || action.equalsIgnoreCase("move")) {
            player.closeInventory();
            contexts.remove(player.getUniqueId());
            handleMoveButton(player, camp);
            return;
        }
        if (action.equalsIgnoreCase("capital:set")) {
            handleCapitalButton(player, camp);
            return;
        }
        if (action.toLowerCase(Locale.ROOT).startsWith("ideology:")) {
            String ideologyId = resolveIdeologyId(action);
            handleIdeologySelect(player, camp, ideologyId);
            return;
        }
        if (action.toLowerCase(Locale.ROOT).startsWith("upgrade:")) {
            String raw = action.substring("upgrade:".length());
            handleUpgrade(player, camp, raw);
            return;
        }
        if (action.startsWith("fuel:")) {
            String key = action.substring("fuel:".length());
            FuelOption option = fuelLayout.options.get(key);
            if (option == null) {
                return;
            }
            if (camp.getFuel() >= camp.getMaxFuel()) {
                plugin.lang().sendActionBar(player, "state.fuel-full", Map.of(
                        "sector", camp.getSectorName()
                ));
                if (plugin.fuelFullSound() != null) {
                    plugin.fuelFullSound().play(player);
                }
                return;
            }
            if (!consumeItems(player, option.descriptor, option.cost)) {
                plugin.lang().send(player, "state.fuel-missing-items", Map.of(
                        "items", option.descriptor.display
                ));
                return;
            }
            int before = camp.getFuel();
            int allowed = Math.max(0, camp.getMaxFuel() - before);
            int toAdd = Math.min(option.fuel, allowed);
            if (toAdd <= 0) {
                plugin.lang().sendActionBar(player, "state.fuel-full", Map.of(
                        "sector", camp.getSectorName()
                ));
                if (plugin.fuelFullSound() != null) {
                    plugin.fuelFullSound().play(player);
                }
                return;
            }
            camp.addFuel(toAdd);
            camp.setLastFuelCheckAt(System.currentTimeMillis());
            plugin.lang().sendActionBar(player, "state.fuel-added", Map.of(
                    "sector", camp.getSectorName(),
                    "amount", String.valueOf(toAdd),
                    "new", String.valueOf(camp.getFuel())
            ));
            if (plugin.fuelAddSound() != null) {
                plugin.fuelAddSound().play(player);
            }
            if (camp.getFuel() != before && plugin.holograms() != null) {
                plugin.holograms().update(camp);
            }
            plugin.war().markDirty();
            openFuel(player, camp);
        }
    }

    private boolean consumeItems(Player player, ItemDescriptor descriptor, int amount) {
        if (descriptor == null || player == null) {
            return false;
        }
        ItemStack[] contents = player.getInventory().getContents();
        int found = 0;
        for (ItemStack stack : contents) {
            if (descriptor.matches(plugin, stack)) {
                found += stack.getAmount();
            }
        }
        if (found < amount) {
            return false;
        }
        int remaining = amount;
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || !descriptor.matches(plugin, stack)) {
                continue;
            }
            int used = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - used);
            if (stack.getAmount() <= 0) {
                contents[i] = null;
            }
            remaining -= used;
            if (remaining <= 0) {
                break;
            }
        }
        player.getInventory().setContents(contents);
        return true;
    }

    private FuelSnapshot fuelSnapshot(Camp camp) {
        if (camp == null) {
            return FuelSnapshot.empty(plugin);
        }
        WarManager.CampMaintenanceInfo info = plugin.war().getMaintenanceInfo(camp.getStateName(), camp.getSectorName());
        long remainingMillis = info != null ? Math.max(0L, info.getRemainingMillis()) : plugin.war().estimateFuelDuration(camp);
        long interval = info != null && info.getInterval() > 0L
                ? info.getInterval()
                : Math.max(0L, plugin.war().getFuelIntervalMillis());
        double units;
        if (info != null && info.getInterval() > 0L) {
            units = info.getFuelUnits();
        } else if (interval > 0L) {
            units = (double) remainingMillis / interval;
        } else {
            units = camp.getFuel();
        }
        units = Math.max(0.0, units);
        String amount = format.format(units);
        String totalDuration = plugin.war().formatDuration(remainingMillis);
        String unitDuration = interval > 0L
                ? plugin.war().formatDuration(interval)
                : plugin.lang().messageOrDefault("placeholders.none", "无");
        return new FuelSnapshot(amount, camp.getMaxFuel(), totalDuration, unitDuration);
    }

    private Map<String, String> buildGuiPlaceholders(Camp camp) {
        Map<String, String> map = new HashMap<>();
        if (camp == null) {
            return map;
        }
        map.put("%production_money%", format.format(camp.getStoredMoney()));
        map.put("%production_money_max%", format.format(camp.getMaxStoredMoney()));
        map.put("%production_items%", String.valueOf(camp.getStoredItemTotal()));
        map.put("%production_items_max%", String.valueOf(camp.getMaxStoredItems()));
        map.put("%production_interval%", plugin.war().formatDuration(camp.getProductionIntervalMs()));
        for (WarManager.CampUpgradeType type : WarManager.CampUpgradeType.values()) {
            String key = type.configKey();
            int current = switch (type) {
                case HP -> camp.getHpLevel();
                case FUEL -> camp.getFuelLevel();
                case HEAL -> camp.getHealLevel();
                case FATIGUE -> camp.getFatigueLevel();
                case STORAGE -> camp.getStorageLevel();
                case EFFICIENCY -> camp.getEfficiencyLevel();
                case BOUNDARY -> camp.getBoundaryLevel();
            };
            WarManager.UpgradeTree tree = plugin.war().getUpgradeTree(type);
            WarManager.UpgradeTier next = plugin.war().getNextTier(camp, type);
            String prefix = "%upgrade_" + key + "_";
            map.put(prefix + "level%", String.valueOf(current));
            String disabledText = plugin.lang().messageOrDefault("placeholders.none", "无");
            String display = tree != null && tree.display() != null
                    ? tree.display()
                    : plugin.lang().messageOrDefault("state.upgrade-name-" + key, key.toUpperCase());
            String maxText = plugin.lang().messageOrDefault("state.upgrade-next-max", plugin.lang().messageOrDefault("state.upgrade-max", "已达到最高等级"));
            map.put(prefix + "display%", display);
            if (tree == null || !tree.enabled()) {
                map.put(prefix + "status%", plugin.lang().messageOrDefault("state.upgrade-disabled", "升级未启用"));
                map.put(prefix + "next_level%", disabledText);
                map.put(prefix + "next_maxhp%", disabledText);
                map.put(prefix + "next_maxfuel%", disabledText);
                map.put(prefix + "next_healrate%", disabledText);
                map.put(prefix + "next_fatigue%", disabledText);
                map.put(prefix + "next_storage_money%", disabledText);
                map.put(prefix + "next_storage_items%", disabledText);
                map.put(prefix + "next_interval%", disabledText);
                map.put(prefix + "next_boundary%", disabledText);
                map.put(prefix + "cost%", "0");
                map.put(prefix + "items%", plugin.lang().messageOrDefault("state.repair-items-none", "无"));
                continue;
            }
            if (next == null) {
                map.put(prefix + "status%", plugin.lang().messageOrDefault("state.upgrade-max", "已达到最高等级"));
                map.put(prefix + "next_level%", maxText);
                map.put(prefix + "next_maxhp%", maxText);
                map.put(prefix + "next_maxfuel%", maxText);
                map.put(prefix + "next_healrate%", maxText);
                map.put(prefix + "next_fatigue%", maxText);
                map.put(prefix + "next_storage_money%", maxText);
                map.put(prefix + "next_storage_items%", maxText);
                map.put(prefix + "next_interval%", maxText);
                map.put(prefix + "next_boundary%", maxText);
                map.put(prefix + "cost%", "0");
                map.put(prefix + "items%", plugin.lang().messageOrDefault("state.repair-items-none", "无"));
                continue;
            }
            map.put(prefix + "status%", plugin.lang().messageOrDefault("state.upgrade-available", "可升级"));
            map.put(prefix + "next_level%", String.valueOf(next.level()));
            String costDisplay = next.costDisplay() != null ? next.costDisplay() : plugin.state().formatMoney(next.cost());
            String itemsDisplay = next.itemsDisplay() != null ? next.itemsDisplay() : plugin.state().describeMaterials(next.items());
            map.put(prefix + "cost%", costDisplay);
            map.put(prefix + "items%", itemsDisplay);
            if (next.maxHp() != null) {
                map.put(prefix + "next_maxhp%", format.format(next.maxHp()));
            } else {
                map.put(prefix + "next_maxhp%", disabledText);
            }
            if (next.maxFuel() != null) {
                map.put(prefix + "next_maxfuel%", String.valueOf(next.maxFuel()));
            } else {
                map.put(prefix + "next_maxfuel%", disabledText);
            }
            if (next.healRate() != null) {
                map.put(prefix + "next_healrate%", format.format(next.healRate()));
            } else {
                map.put(prefix + "next_healrate%", disabledText);
            }
            if (next.fatigueAmplifier() != null) {
                map.put(prefix + "next_fatigue%", String.valueOf(next.fatigueAmplifier()));
            } else {
                map.put(prefix + "next_fatigue%", disabledText);
            }
            if (next.storedMoneyCap() != null) {
                map.put(prefix + "next_storage_money%", format.format(next.storedMoneyCap()));
            } else {
                map.put(prefix + "next_storage_money%", disabledText);
            }
            if (next.storedItemCap() != null) {
                map.put(prefix + "next_storage_items%", String.valueOf(next.storedItemCap()));
            } else {
                map.put(prefix + "next_storage_items%", disabledText);
            }
            if (next.productionIntervalSeconds() != null) {
                map.put(prefix + "next_interval%", plugin.war().formatDuration(next.productionIntervalSeconds() * 1000L));
            } else {
                map.put(prefix + "next_interval%", disabledText);
            }
            if (next.boundaryRadiusBonus() != null) {
                double baseRadius = Math.max(1.0, plugin.getConfig().getDouble("camp.radius", 16.0));
                map.put(prefix + "next_boundary%", format.format(baseRadius + next.boundaryRadiusBonus()));
            } else {
                map.put(prefix + "next_boundary%", disabledText);
            }
        }
        return map;
    }

    private void closeAllContexts() {
        Map<UUID, GuiContext> snapshot = new HashMap<>(contexts);
        contexts.clear();
        switching.clear();
        pendingInputs.clear();
        for (UUID uuid : snapshot.keySet()) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.closeInventory();
            }
        }
    }

    private String applyCamp(String input, Camp camp, FuelSnapshot snapshot, Map<String, String> extras) {
        if (input == null) {
            return "";
        }
        FuelSnapshot snapshotValue = snapshot != null ? snapshot : fuelSnapshot(camp);
        double percent = camp.getMaxHp() <= 0 ? 0.0 : (camp.getHp() / camp.getMaxHp()) * 100.0;
        String value = input.replace("%state%", camp.getStateName())
                .replace("%sector%", camp.getSectorName())
                .replace("%hp%", format.format(camp.getHp()))
                .replace("%hp_current%", format.format(camp.getHp()))
                .replace("%maxhp%", format.format(camp.getMaxHp()))
                .replace("%hp_percent%", format.format(percent))
                .replace("%fuel%", snapshotValue.amount())
                .replace("%maxfuel%", String.valueOf(snapshotValue.maxFuel()))
                .replace("%fuel_time%", snapshotValue.totalDuration())
                .replace("%fuel_unit_time%", snapshotValue.unitDuration())
                .replace("%production_money%", format.format(camp.getStoredMoney()))
                .replace("%production_money_max%", format.format(camp.getMaxStoredMoney()))
                .replace("%production_items%", String.valueOf(camp.getStoredItemTotal()))
                .replace("%production_items_max%", String.valueOf(camp.getMaxStoredItems()))
                .replace("%production_interval%", plugin.war().formatDuration(camp.getProductionIntervalMs()));
        if (extras != null) {
            for (Map.Entry<String, String> entry : extras.entrySet()) {
                value = value.replace(entry.getKey(), entry.getValue());
            }
        }
        return colorize(value);
    }

    private String colorize(String input) {
        return plugin.lang().colorizeText(input);
    }

    private ItemStack renderButton(GuiButton button, Camp camp, FuelSnapshot snapshot, Map<String, String> extras) {
        if (button == null) {
            return new ItemStack(Material.BARRIER, 1);
        }
        IdeologyContext context = resolveIdeologyContext(button, camp);
        return button.createItem(plugin, camp, snapshot, context.ideology(), context.id(), context.selected(), extras);
    }

    private IdeologyContext resolveIdeologyContext(GuiButton button, Camp camp) {
        if (button == null || camp == null) {
            return IdeologyContext.empty();
        }
        String ideologyId = resolveIdeologyId(button.action);
        if (ideologyId == null) {
            return IdeologyContext.empty();
        }
        Ideology ideology = plugin.ideology().get(ideologyId);
        String current = plugin.state().getIdeologyId(camp.getStateName());
        boolean selected = ideology != null && current != null && current.equalsIgnoreCase(ideology.getId());
        return new IdeologyContext(ideologyId, ideology, selected);
    }

    private String resolveIdeologyId(String action) {
        if (action == null) {
            return null;
        }
        String[] parts = action.split(":");
        if (parts.length < 2 || !parts[0].equalsIgnoreCase("ideology")) {
            return null;
        }
        String id = parts[parts.length - 1].trim();
        return id.isEmpty() ? null : id;
    }

    private record PendingInput(InputType type, Camp camp) { }

    private enum InputType {
        RENAME_SECTOR,
        RENAME_STATE
    }

    private record IdeologyContext(String id, Ideology ideology, boolean selected) {
        private static IdeologyContext empty() {
            return new IdeologyContext(null, null, false);
        }
    }

    private static class GuiContext {
        private final Camp camp;
        private final GuiPage page;
        private final Map<Integer, GuiButton> buttons;
        private final Inventory inventory;
        private final Map<String, String> placeholders;

        private GuiContext(Camp camp, GuiPage page, Map<Integer, GuiButton> buttons, Inventory inventory,
                           Map<String, String> placeholders) {
            this.camp = camp;
            this.page = page;
            this.buttons = buttons;
            this.inventory = inventory;
            this.placeholders = placeholders;
        }
    }

    private enum GuiPage { MAIN, FUEL, IDEOLOGY, UPGRADES }

    private record GuiLayout(String title, int rows, Map<String, GuiButton> buttons) {
        static GuiLayout fromConfig(ConfigurationSection section) {
            if (section == null) {
                return null;
            }
            String title = section.getString("title", "Camp");
            int rows = Math.max(1, section.getInt("rows", 3));
            Map<String, GuiButton> buttons = new LinkedHashMap<>();
            ConfigurationSection buttonSection = section.getConfigurationSection("buttons");
            if (buttonSection != null) {
                for (String key : buttonSection.getKeys(false)) {
                    GuiButton button = GuiButton.fromConfig(buttonSection.getConfigurationSection(key));
                    if (button != null) {
                        buttons.put(key, button);
                    }
                }
            }
            return new GuiLayout(title, rows, buttons);
        }
    }

    private record FuelLayout(String title, int rows, GuiButton displayButton, GuiButton backButton, Map<String, FuelOption> options) {
        static FuelLayout fromConfig(ConfigurationSection section) {
            if (section == null) {
                return null;
            }
            String title = section.getString("title", "Fuel");
            int rows = Math.max(1, section.getInt("rows", 3));
            GuiButton display = GuiButton.fromConfig(section.getConfigurationSection("display"));
            GuiButton back = GuiButton.fromConfig(section.getConfigurationSection("back"));
            Map<String, FuelOption> options = new LinkedHashMap<>();
            ConfigurationSection fuels = section.getConfigurationSection("options");
            if (fuels != null) {
                for (String key : fuels.getKeys(false)) {
                    FuelOption option = FuelOption.fromConfig(fuels.getConfigurationSection(key));
                    if (option != null) {
                        options.put(key, option);
                    }
                }
            }
            return new FuelLayout(title, rows, display, back, options);
        }
    }

    private record FuelOption(ItemDescriptor descriptor, int fuel, int cost, GuiButton button) {
        static FuelOption fromConfig(ConfigurationSection section) {
            if (section == null) {
                return null;
            }
            String item = section.getString("item", "COAL");
            ItemDescriptor descriptor = ItemDescriptor.from(item);
            if (descriptor == null) {
                return null;
            }
            int fuel = Math.max(1, section.getInt("fuel", 1));
            int cost = Math.max(1, section.getInt("amount", 1));
            GuiButton button = GuiButton.fromConfig(section);
            if (button == null) {
                return null;
            }
            return new FuelOption(descriptor, fuel, cost, button);
        }
    }

    private record GuiButton(List<Integer> slots, Material material, ItemDescriptor descriptor, String name,
                             List<String> lore, String action) {
        ItemStack createItem(CampSystem plugin, Camp camp, FuelSnapshot snapshot, Map<String, String> extras) {
            return createItem(plugin, camp, snapshot, null, null, false, extras);
        }

        ItemStack createItem(CampSystem plugin, Camp camp, FuelSnapshot snapshot,
                             Ideology ideology, String ideologyId, boolean selected, Map<String, String> extras) {
            ItemStack base = descriptor != null && descriptor.material != null
                    ? new ItemStack(descriptor.material, 1)
                    : (material != null ? new ItemStack(material, 1) : descriptor != null ? descriptor.createItem(plugin, 1) : null);
            if (base == null) {
                return new ItemStack(Material.BARRIER, 1);
            }
            ItemMeta meta = base.getItemMeta();
            if (meta != null) {
                if (name != null) {
                    meta.setDisplayName(plugin.lang().colorizeText(apply(plugin, camp, snapshot, name, ideology, ideologyId, selected, extras)));
                }
                if (lore != null && !lore.isEmpty()) {
                    List<String> colored = new ArrayList<>();
                    for (String line : lore) {
                        colored.add(plugin.lang().colorizeText(apply(plugin, camp, snapshot, line, ideology, ideologyId, selected, extras)));
                    }
                    meta.setLore(colored);
                }
                base.setItemMeta(meta);
            }
            return base;
        }

        private String apply(CampSystem plugin, Camp camp, FuelSnapshot snapshot, String input,
                             Ideology ideology, String ideologyId, boolean selected, Map<String, String> extras) {
            if (camp == null || input == null) {
                return input == null ? "" : input;
            }
            FuelSnapshot snapshotValue = snapshot != null ? snapshot : FuelSnapshot.empty(plugin);
            String currentIdeology = ideology != null ? ideology.getDisplayName()
                    : plugin.lang().messageOrDefault("campinfo.ideology-unknown", "待定");
            String ideologyIdentifier = ideology != null ? ideology.getId()
                    : (ideologyId != null ? ideologyId : plugin.lang().messageOrDefault("campinfo.ideology-unknown", "待定"));
            long cooldown = plugin.state().getIdeologyCooldownRemaining(camp.getStateName());
            String status;
            if (selected) {
                status = plugin.lang().messageOrDefault("ideology.gui-selected", "当前选择");
            } else if (cooldown > 0) {
                status = plugin.lang().messageOrDefault("ideology.gui-cooldown", "冷却中：%time%")
                        .replace("%time%", plugin.war().formatDuration(cooldown));
            } else {
                status = plugin.lang().messageOrDefault("ideology.gui-available", "点击切换");
            }
            String value = input.replace("%state%", camp.getStateName())
                    .replace("%sector%", camp.getSectorName())
                    .replace("%fuel%", snapshotValue.amount())
                    .replace("%maxfuel%", String.valueOf(snapshotValue.maxFuel()))
                    .replace("%hp%", String.valueOf(camp.getHp()))
                    .replace("%maxhp%", String.valueOf(camp.getMaxHp()))
                    .replace("%fuel_time%", snapshotValue.totalDuration())
                    .replace("%fuel_unit_time%", snapshotValue.unitDuration())
                    .replace("%ideology_display%", currentIdeology)
                    .replace("%ideology_id%", ideologyIdentifier)
                    .replace("%ideology_status%", status);
            if (extras != null) {
                for (Map.Entry<String, String> entry : extras.entrySet()) {
                    value = value.replace(entry.getKey(), entry.getValue());
                }
            }
            return value;
        }

        List<Integer> slotList() {
            return slots == null ? List.of() : slots;
        }

        int primarySlot() {
            List<Integer> list = slotList();
            return list.isEmpty() ? 0 : list.get(0);
        }

        static GuiButton fromConfig(ConfigurationSection section) {
            if (section == null) {
                return null;
            }
            List<Integer> slots = new ArrayList<>();
            List<Integer> configSlots = section.getIntegerList("slots");
            if (configSlots != null) {
                for (Integer value : configSlots) {
                    if (value == null) {
                        continue;
                    }
                    slots.add(Math.max(0, value));
                }
            }
            if (slots.isEmpty()) {
                slots.add(Math.max(0, section.getInt("slot", 0)));
            }
            String item = section.getString("item", section.getString("material", "STONE"));
            ItemDescriptor descriptor = ItemDescriptor.from(item);
            Material material = descriptor != null ? descriptor.material : Material.matchMaterial(item);
            String name = section.getString("name");
            List<String> lore = section.getStringList("lore");
            String action = section.getString("action", "");
            return new GuiButton(Collections.unmodifiableList(slots), material, descriptor, name, lore, action);
        }
    }

    private record FuelSnapshot(String amount, int maxFuel, String totalDuration, String unitDuration) {
        static FuelSnapshot empty(CampSystem plugin) {
            String none = plugin.lang().messageOrDefault("placeholders.none", "无");
            return new FuelSnapshot("0.0", 0, none, none);
        }
    }

    private record ItemDescriptor(Material material, String customId, String display) {
        static ItemDescriptor from(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return null;
            }
            String trimmed = raw.trim();
            Material material = Material.matchMaterial(trimmed);
            if (material != null) {
                return new ItemDescriptor(material, null, trimmed);
            }
            String normalized = trimmed;
            if (normalized.regionMatches(true, 0, "itemsadder:", 0, 11)) {
                normalized = normalized.substring(11);
            }
            if (normalized.contains(":")) {
                return new ItemDescriptor(null, normalized.toLowerCase(Locale.ROOT), trimmed);
            }
            return null;
        }

        boolean matches(CampSystem plugin, ItemStack stack) {
            if (stack == null) {
                return false;
            }
            if (material != null) {
                return stack.getType() == material;
            }
            if (customId != null && plugin != null
                    && plugin.getServer().getPluginManager().getPlugin("ItemsAdder") != null) {
                dev.lone.itemsadder.api.CustomStack custom = dev.lone.itemsadder.api.CustomStack.byItemStack(stack);
                return custom != null && custom.getNamespacedID() != null && custom.getNamespacedID().equalsIgnoreCase(customId);
            }
            return false;
        }

        ItemStack createItem(CampSystem plugin, int amount) {
            int resolved = Math.max(1, amount);
            if (material != null) {
                return new ItemStack(material, resolved);
            }
            if (customId != null && plugin != null
                    && plugin.getServer().getPluginManager().getPlugin("ItemsAdder") != null) {
                dev.lone.itemsadder.api.CustomStack custom = dev.lone.itemsadder.api.CustomStack.getInstance(customId);
                if (custom != null) {
                    ItemStack stack = custom.getItemStack();
                    if (stack != null) {
                        stack.setAmount(resolved);
                        return stack;
                    }
                }
            }
            return new ItemStack(Material.BARRIER, resolved);
        }
    }
}
