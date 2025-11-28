package dev.ameruzily.campsystem.listeners;

import dev.ameruzily.campsystem.CampSystem;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class WarListener implements Listener {

    private final CampSystem plugin;

    public WarListener(CampSystem plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim == null ? null : victim.getKiller();
        plugin.war().handlePlayerDeath(victim, killer);
    }
}

