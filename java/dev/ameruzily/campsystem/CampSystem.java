package dev.ameruzily.campsystem;

import dev.ameruzily.campsystem.commands.CampCommand;
import dev.ameruzily.campsystem.listeners.CampPlacementListener;
import dev.ameruzily.campsystem.listeners.CampProtectionListener;
import dev.ameruzily.campsystem.listeners.GraveXListener;
import dev.ameruzily.campsystem.listeners.WarListener;
import dev.ameruzily.campsystem.managers.*;
import dev.ameruzily.campsystem.models.SoundSettings;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class CampSystem extends JavaPlugin {

    private ConfigManager configManager;
    private LangManager langManager;
    private IdeologyManager ideologyManager;
    private StateManager stateManager;
    private WarManager warManager;
    private PlaceholderManager placeholderManager;
    private CampHologramManager hologramManager;
    private CampGuiManager guiManager;
    private StateOverviewGuiManager overviewGuiManager;
    private CampProtectionListener protectionListener;
    private CampInfoManager campInfoManager;
    private SoundSettings campClickSound;
    private SoundSettings guiClickSound;
    private SoundSettings stateCreateSound;
    private SoundSettings sectorCreateSound;
    private SoundSettings capitalSetSound;
    private SoundSettings fuelAddSound;
    private SoundSettings fuelFullSound;
    private SoundSettings campDamageSound;
    private SoundSettings upgradeSuccessSound;
    private SoundSettings protectedBlockSound;
    private SoundSettings warStartSound;
    private SoundSettings warEndSound;

    // 可选监听：GraveX 联动
    private GraveXListener graveXListener;

    private Economy vaultEconomy;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.configManager = new ConfigManager(this);
        this.langManager = new LangManager(this);
        this.ideologyManager = new IdeologyManager(this);
        this.stateManager = new StateManager(this);
        this.warManager = new WarManager(this);
        this.placeholderManager = new PlaceholderManager(this);
        this.hologramManager = new CampHologramManager(this);
        this.campInfoManager = new CampInfoManager(this);
        this.guiManager = new CampGuiManager(this);
        this.overviewGuiManager = new StateOverviewGuiManager(this);
        loadSounds();

        setupEconomy();
        campInfoManager.reload();
        stateManager.startBankTask();

        if (hologramManager != null) {
            hologramManager.reload();
        }

        // 指令
        new CampCommand(this);
        new CampPlacementListener(this);
        this.protectionListener = new CampProtectionListener(this);
        new WarListener(this);

        // GraveX 兼容（可选）
        if (Bukkit.getPluginManager().getPlugin("GraveX") != null) {
            this.graveXListener = new GraveXListener(this);
        }

        // 定时清理邀请
        Bukkit.getScheduler().runTaskTimer(this, () -> stateManager.cleanupInvites(), 6000L, 6000L);

        getLogger().info("GovernanceOfColony 1.1.0 Enabled.");
    }

    @Override
    public void onDisable() {
        if (stateManager != null) {
            stateManager.stopBankTask();
        }
        if (hologramManager != null) {
            hologramManager.shutdown();
        }
        if (guiManager != null) {
            guiManager.closeAll();
        }
        if (overviewGuiManager != null) {
            overviewGuiManager.closeAll();
        }
        if (campInfoManager != null) {
            campInfoManager.saveNow();
        }
        getLogger().info("GovernanceOfColony disabled.");
    }

    // Getters
    public ConfigManager config() { return configManager; }
    public LangManager lang() { return langManager; }
    public IdeologyManager ideology() { return ideologyManager; }
    public StateManager state() { return stateManager; }
    public WarManager war() { return warManager; }
    public PlaceholderManager placeholders() { return placeholderManager; }
    public CampHologramManager holograms() { return hologramManager; }
    public CampGuiManager gui() { return guiManager; }
    public StateOverviewGuiManager overviewGui() { return overviewGuiManager; }
    public CampProtectionListener protection() { return protectionListener; }
    public CampInfoManager campInfo() { return campInfoManager; }
    public GraveXListener getGraveXListener() { return graveXListener; }
    public Economy economy() { return vaultEconomy; }
    public SoundSettings campClickSound() { return campClickSound; }
    public SoundSettings guiClickSound() { return guiClickSound; }
    public SoundSettings stateCreateSound() { return stateCreateSound; }
    public SoundSettings sectorCreateSound() { return sectorCreateSound; }
    public SoundSettings capitalSetSound() { return capitalSetSound; }
    public SoundSettings fuelAddSound() { return fuelAddSound; }
    public SoundSettings fuelFullSound() { return fuelFullSound; }
    public SoundSettings campDamageSound() { return campDamageSound; }
    public SoundSettings upgradeSuccessSound() { return upgradeSuccessSound; }
    public SoundSettings protectedBlockSound() { return protectedBlockSound; }
    public SoundSettings warStartSound() { return warStartSound; }
    public SoundSettings warEndSound() { return warEndSound; }

    public void refreshEconomy() {
        setupEconomy();
    }

    public void loadSounds() {
        this.campClickSound = SoundSettings.fromConfig(getConfig(), "sounds.camp-click");
        this.guiClickSound = SoundSettings.fromConfig(getConfig(), "sounds.gui-click");
        this.stateCreateSound = SoundSettings.fromConfig(getConfig(), "sounds.state-create");
        this.sectorCreateSound = SoundSettings.fromConfig(getConfig(), "sounds.sector-create");
        this.capitalSetSound = SoundSettings.fromConfig(getConfig(), "sounds.capital-set");
        this.fuelAddSound = SoundSettings.fromConfig(getConfig(), "sounds.fuel-add");
        this.fuelFullSound = SoundSettings.fromConfig(getConfig(), "sounds.fuel-full");
        this.campDamageSound = SoundSettings.fromConfig(getConfig(), "sounds.camp-damage");
        this.upgradeSuccessSound = SoundSettings.fromConfig(getConfig(), "sounds.upgrade-success");
        this.protectedBlockSound = SoundSettings.fromConfig(getConfig(), "sounds.protected-block");
        this.warStartSound = SoundSettings.fromConfig(getConfig(), "sounds.war-start");
        this.warEndSound = SoundSettings.fromConfig(getConfig(), "sounds.war-end");
    }

    private void setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found. Economy features are disabled.");
            vaultEconomy = null;
            return;
        }

        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            getLogger().warning("No Vault economy provider detected. Economy features are disabled.");
            vaultEconomy = null;
            return;
        }

        vaultEconomy = provider.getProvider();
        if (vaultEconomy == null) {
            getLogger().warning("Failed to hook into Vault economy provider.");
        }
    }
}
