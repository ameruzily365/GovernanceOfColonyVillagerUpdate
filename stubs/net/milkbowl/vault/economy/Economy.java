package net.milkbowl.vault.economy;

import org.bukkit.entity.Player;

public interface Economy {
    boolean has(Player player, double amount);

    EconomyResponse withdrawPlayer(Player player, double amount);

    EconomyResponse depositPlayer(Player player, double amount);
}
