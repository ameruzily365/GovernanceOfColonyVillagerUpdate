package dev.ameruzily.campsystem.listeners;

import dev.ameruzily.campsystem.CampSystem;
import dev.ameruzily.campsystem.managers.StateManager;
import dev.ameruzily.campsystem.managers.WarManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.HashSet;
import java.util.Set;

public class GraveXListener implements Listener {

    private final CampSystem plugin;
    private final WarManager warManager;
    private final StateManager stateManager;
    private final boolean graveXEnabled;

    private final Set<String> disabledStates = new HashSet<>();

    public GraveXListener(CampSystem plugin) {
        this.plugin = plugin;
        this.warManager = plugin.war();
        this.stateManager = plugin.state();
        this.graveXEnabled = Bukkit.getPluginManager().getPlugin("GraveX") != null;

        if (graveXEnabled) {
            plugin.getLogger().info("[CampSystem] GraveX detected. War-time drop override active.");
            Bukkit.getPluginManager().registerEvents(this, plugin);
        } else {
            plugin.getLogger().warning("[CampSystem] GraveX not found. War-time override skipped.");
        }
    }

    public void disableForStates(String a, String b) {
        if (!graveXEnabled) return;
        disabledStates.add(a);
        disabledStates.add(b);
        plugin.lang().broadcast("war.gravex-disabled-global", java.util.Map.of("a", a, "b", b));
    }

    public void restoreIfNoWars() {
        if (!graveXEnabled) return;
        boolean hasWar = !warManager.getActiveWars().isEmpty();
        if (!hasWar && !disabledStates.isEmpty()) {
            disabledStates.clear();
            plugin.lang().broadcast("war.gravex-restored");
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!graveXEnabled) return;
        Player p = event.getEntity();
        String state = stateManager.getStateName(p);
        if (state == null) return;

        if (disabledStates.contains(state)) {
            event.setKeepInventory(false);
            event.setKeepLevel(false);
            plugin.lang().send(p, "war.gravex-disabled");
        }
    }
}
