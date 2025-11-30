package dev.ameruzily.campsystem.managers;

import dev.ameruzily.campsystem.CampSystem;
import dev.ameruzily.campsystem.models.Camp;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.text.DecimalFormat;
import java.util.*;

/**
 * Overview GUI for quick access to state information and sector teleports.
 */
public class StateOverviewGuiManager implements Listener {

    private final CampSystem plugin;
    private final Map<UUID, OverviewContext> contexts = new HashMap<>();
    private OverviewLayout layout;
    private final DecimalFormat decimal = new DecimalFormat("0.0");

    public StateOverviewGuiManager(CampSystem plugin) {
        this.plugin = plugin;
        reload();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void reload() {
        closeAll();
        loadLayout();
    }

    public void closeAll() {
        if (contexts.isEmpty()) {
            return;
        }
        for (UUID uuid : new HashSet<>(contexts.keySet())) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.getOpenInventory() != null) {
                player.closeInventory();
            }
        }
        contexts.clear();
    }

    private void loadLayout() {
        File file = new File(plugin.getDataFolder(), "state-gui.yml");
        if (!file.exists()) {
            plugin.saveResource("state-gui.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("gui");
        layout = OverviewLayout.fromConfig(section, plugin);
    }

    public void open(Player player, int page) {
        if (layout == null || player == null) {
            return;
        }

        String stateName = plugin.state().getStateName(player);
        if (stateName == null) {
            plugin.lang().send(player, "camp.not-found");
            return;
        }

        StateManager.StateData state = plugin.state().getState(stateName);
        if (state == null) {
            plugin.lang().send(player, "camp.not-found");
            return;
        }

        List<String> sectors = new ArrayList<>(state.sectors.keySet());
        int perPage = Math.max(1, layout.sectorSlots.size());
        int maxPage = Math.max(1, (int) Math.ceil((double) sectors.size() / perPage));
        int targetPage = Math.min(Math.max(1, page), maxPage);
        int startIndex = (targetPage - 1) * perPage;
        int endIndex = Math.min(startIndex + perPage, sectors.size());

        Map<String, String> statePlaceholders = buildStatePlaceholders(state);
        String title = plugin.lang().colorizeText(apply(statePlaceholders, layout.title)
                .replace("%page%", String.valueOf(targetPage))
                .replace("%pages%", String.valueOf(maxPage)));
        Inventory inv = plugin.getServer().createInventory(null, layout.rows * 9, title);

        OverviewContext context = new OverviewContext(stateName, targetPage, maxPage, inv);
        fillBackground(inv);
        renderInfo(inv, state, statePlaceholders);

        for (int i = startIndex, slotIndex = 0; i < endIndex && slotIndex < layout.sectorSlots.size(); i++, slotIndex++) {
            String sectorName = sectors.get(i);
            int slot = layout.sectorSlots.get(slotIndex);
            ItemStack icon = createSectorIcon(stateName, sectorName, statePlaceholders);
            inv.setItem(slot, icon);
            context.sectorSlots.put(slot, sectorName);
        }

        renderButtons(inv, context);

        contexts.put(player.getUniqueId(), context);
        player.openInventory(inv);
    }

    public void open(Player player) {
        open(player, 1);
    }

    private void fillBackground(Inventory inv) {
        if (layout.filler == null || inv == null) {
            return;
        }
        ItemStack filler = layout.filler.createItem(plugin, Collections.emptyMap());
        if (filler == null) {
            return;
        }
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
    }

    private void renderInfo(Inventory inv, StateManager.StateData state, Map<String, String> placeholders) {
        if (layout.infoButton == null || inv == null || state == null) {
            return;
        }
        ItemStack info = layout.infoButton.createItem(plugin, placeholders);
        if (info != null && layout.infoButton.slot >= 0 && layout.infoButton.slot < inv.getSize()) {
            inv.setItem(layout.infoButton.slot, info);
        }
    }

    private void renderButtons(Inventory inv, OverviewContext context) {
        if (inv == null || context == null) {
            return;
        }
        for (GuiButton button : layout.buttons.values()) {
            ItemStack item = button.createItem(plugin, Map.of(
                    "%page%", String.valueOf(context.page),
                    "%pages%", String.valueOf(context.totalPages)
            ));
            if (item == null) {
                continue;
            }
            for (int slot : button.slots) {
                if (slot < 0 || slot >= inv.getSize()) {
                    continue;
                }
                inv.setItem(slot, item.clone());
                context.buttonSlots.put(slot, button.action);
            }
        }
    }

    private ItemStack createSectorIcon(String stateName, String sectorName, Map<String, String> basePlaceholders) {
        Map<String, String> placeholders = new HashMap<>(basePlaceholders);
        Camp camp = plugin.war().getCamp(stateName, sectorName);
        placeholders.put("%sector%", sectorName);
        if (camp != null) {
            placeholders.put("%hp%", decimal.format(camp.getHp()));
            placeholders.put("%hp_max%", decimal.format(camp.getMaxHp()));
            placeholders.put("%fuel%", String.valueOf(camp.getFuel()));
            placeholders.put("%fuel_max%", String.valueOf(camp.getMaxFuel()));
            placeholders.put("%money%", decimal.format(camp.getStoredMoney()));
            placeholders.put("%money_max%", decimal.format(camp.getMaxStoredMoney()));
            placeholders.put("%items%", String.valueOf(camp.getStoredItemTotal()));
            placeholders.put("%items_max%", String.valueOf(camp.getMaxStoredItems()));
        } else {
            placeholders.put("%hp%", decimal.format(0));
            placeholders.put("%hp_max%", decimal.format(0));
            placeholders.put("%fuel%", "0");
            placeholders.put("%fuel_max%", "0");
            placeholders.put("%money%", decimal.format(0));
            placeholders.put("%money_max%", decimal.format(0));
            placeholders.put("%items%", "0");
            placeholders.put("%items_max%", "0");
        }
        placeholders.put("%capital_icon%", sectorName.equalsIgnoreCase(plugin.state().getCapital(stateName))
                ? plugin.lang().messageOrDefault("placeholders.capital", "首都") : "");
        return layout.sectorButton.createItem(plugin, placeholders);
    }

    private Map<String, String> buildStatePlaceholders(StateManager.StateData state) {
        Map<String, String> map = new HashMap<>();
        if (state == null) {
            return map;
        }
        String ideology = plugin.state().getIdeologyDisplay(state.name);
        String captain = Optional.ofNullable(Bukkit.getOfflinePlayer(state.captain).getName())
                .orElse(plugin.lang().messageOrDefault("bank.log-unknown", "未知"));
        String capital = state.capitalSector != null
                ? state.capitalSector
                : plugin.lang().messageOrDefault("campinfo.no-sector", "无");
        map.put("%state%", state.name);
        map.put("%ideology%", ideology);
        map.put("%captain%", captain);
        map.put("%members%", String.valueOf(state.members.size()));
        map.put("%sector_count%", String.valueOf(state.sectors.size()));
        map.put("%capital%", capital);
        return map;
    }

    private static String apply(Map<String, String> placeholders, String text) {
        if (text == null) {
            return "";
        }
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        OverviewContext context = contexts.get(player.getUniqueId());
        if (context == null || event.getClickedInventory() == null || !event.getClickedInventory().equals(context.inventory)) {
            return;
        }
        event.setCancelled(true);
        int slot = event.getSlot();
        if (context.buttonSlots.containsKey(slot)) {
            handleButton(player, context, context.buttonSlots.get(slot));
            return;
        }
        if (context.sectorSlots.containsKey(slot)) {
            handleSectorClick(player, context.sectorSlots.get(slot));
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() == null) {
            return;
        }
        OverviewContext context = contexts.get(event.getPlayer().getUniqueId());
        if (context != null && event.getInventory() != null && event.getInventory().equals(context.inventory)) {
            contexts.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        contexts.remove(event.getPlayer().getUniqueId());
    }

    private void handleButton(Player player, OverviewContext context, String action) {
        if (action == null) {
            return;
        }
        if (plugin.guiClickSound() != null) {
            plugin.guiClickSound().play(player);
        }
        switch (action.toLowerCase(Locale.ROOT)) {
            case "close" -> player.closeInventory();
            case "previous" -> open(player, Math.max(1, context.page - 1));
            case "next" -> open(player, Math.min(context.totalPages, context.page + 1));
            default -> {
            }
        }
    }

    private void handleSectorClick(Player player, String sectorName) {
        if (player == null || sectorName == null) {
            return;
        }
        StateManager.TeleportResult result = plugin.state().teleport(player, sectorName);
        switch (result.getStatus()) {
            case NO_STATE -> plugin.lang().send(player, "camp.not-found");
            case SECTOR_NOT_FOUND -> plugin.lang().send(player, "state.sector-not-found", Map.of("sector", sectorName));
            case MISSING_LOCATION -> plugin.lang().send(player, "state.sector-move-missing", Map.of("sector", sectorName));
            case SUCCESS -> {
                if (result.getWarmupMillis() <= 0) {
                    plugin.lang().send(player, "state.teleport-success", Map.of("sector", result.getSector()));
                }
                player.closeInventory();
            }
        }
    }

    private record OverviewLayout(int rows, String title, GuiButton infoButton,
                                  GuiButton sectorButton, GuiButton filler,
                                  Map<String, GuiButton> buttons, List<Integer> sectorSlots) {
        static OverviewLayout fromConfig(ConfigurationSection section, CampSystem plugin) {
            if (section == null) {
                return null;
            }
            int rows = Math.max(1, Math.min(6, section.getInt("rows", 4)));
            String title = section.getString("title", "&a政权总览");
            GuiButton filler = GuiButton.fromConfig(section.getConfigurationSection("filler"));
            GuiButton info = GuiButton.fromConfig(section.getConfigurationSection("info"));
            GuiButton sector = GuiButton.fromConfig(section.getConfigurationSection("sector"));
            Map<String, GuiButton> buttons = new HashMap<>();
            ConfigurationSection buttonSection = section.getConfigurationSection("buttons");
            if (buttonSection != null) {
                for (String key : buttonSection.getKeys(false)) {
                    GuiButton button = GuiButton.fromConfig(buttonSection.getConfigurationSection(key));
                    if (button != null) {
                        buttons.put(key.toLowerCase(Locale.ROOT), button);
                    }
                }
            }
            List<Integer> slots = new ArrayList<>();
            for (Object obj : section.getList("sector-slots", List.of(12, 13, 14, 15, 16, 21, 22, 23, 24, 25))) {
                if (obj instanceof Number number) {
                    slots.add(number.intValue());
                }
            }
            return new OverviewLayout(rows, title, info, sector, filler, Collections.unmodifiableMap(buttons), slots);
        }
    }

    private record GuiButton(List<Integer> slots, Material material, ItemDescriptor descriptor,
                              String name, List<String> lore, String action, int slot) {
        ItemStack createItem(CampSystem plugin, Map<String, String> placeholders) {
            ItemStack base = descriptor != null && descriptor.material != null
                    ? new ItemStack(descriptor.material, 1)
                    : (material != null ? new ItemStack(material, 1) : descriptor != null ? descriptor.createItem(plugin, 1) : null);
            if (base == null) {
                return null;
            }
            ItemMeta meta = base.getItemMeta();
            if (meta != null) {
                if (name != null && !name.isEmpty()) {
                    meta.setDisplayName(plugin.lang().colorizeText(apply(placeholders, name)));
                }
                if (!lore.isEmpty()) {
                    List<String> applied = new ArrayList<>();
                    for (String line : lore) {
                        applied.add(plugin.lang().colorizeText(apply(placeholders, line)));
                    }
                    meta.setLore(applied);
                }
                meta.addItemFlags(ItemFlag.values());
                base.setItemMeta(meta);
            }
            return base;
        }

        static GuiButton fromConfig(ConfigurationSection section) {
            if (section == null) {
                return null;
            }
            List<Integer> slots = new ArrayList<>();
            int slot = section.getInt("slot", -1);
            if (section.contains("slots")) {
                for (Object obj : section.getList("slots")) {
                    if (obj instanceof Number number) {
                        slots.add(number.intValue());
                    }
                }
            }
            if (slot >= 0) {
                slots.add(slot);
            }
            String item = section.getString("item", section.getString("material", "STONE"));
            ItemDescriptor descriptor = ItemDescriptor.parse(item);
            Material material = descriptor != null ? descriptor.material : Material.matchMaterial(item);
            String name = section.getString("name", "");
            List<String> lore = section.getStringList("lore");
            String action = section.getString("action", "");
            return new GuiButton(Collections.unmodifiableList(slots), material, descriptor, name, lore, action, slot);
        }
    }

    private record ItemDescriptor(Material material, String customId, String display) {
        ItemStack createItem(CampSystem plugin, int amount) {
            if (customId == null || customId.isEmpty()) {
                return null;
            }
            if (plugin == null || plugin.getServer().getPluginManager().getPlugin("ItemsAdder") == null) {
                return null;
            }
            try {
                Class<?> ia = Class.forName("dev.lone.itemsadder.api.CustomStack");
                Object stack = ia.getMethod("getInstance", String.class).invoke(null, customId);
                if (stack == null) {
                    plugin.getLogger().warning("ItemsAdder item not found: " + customId);
                    return null;
                }
                ItemStack built = (ItemStack) ia.getMethod("getItemStack").invoke(stack);
                if (built != null) {
                    built.setAmount(Math.max(1, amount));
                }
                return built;
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to create ItemsAdder item " + customId + ": " + ex.getMessage());
                return null;
            }
        }

        static ItemDescriptor parse(String raw) {
            String trimmed = raw == null ? "" : raw.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            if (trimmed.startsWith("itemsadder:")) {
                String id = trimmed.substring("itemsadder:".length());
                String display = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                return new ItemDescriptor(null, id, display);
            }
            Material material = Material.matchMaterial(trimmed);
            if (material != null) {
                return new ItemDescriptor(material, null, trimmed);
            }
            return null;
        }
    }

    private static class OverviewContext {
        private final String stateName;
        private final int page;
        private final int totalPages;
        private final Inventory inventory;
        private final Map<Integer, String> sectorSlots = new HashMap<>();
        private final Map<Integer, String> buttonSlots = new HashMap<>();

        private OverviewContext(String stateName, int page, int totalPages, Inventory inventory) {
            this.stateName = stateName;
            this.page = page;
            this.totalPages = totalPages;
            this.inventory = inventory;
        }
    }
}
