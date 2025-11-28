package dev.ameruzily.campsystem.models;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class WarData {
    private String attacker;
    private String defender;
    private final long startTime;

    private final Set<String> emergencyMovesUsed = new HashSet<>();
    private final Map<String, Long> capitalBrokenAt = new HashMap<>();
    private final Set<String> attackerSide = new HashSet<>();
    private final Set<String> defenderSide = new HashSet<>();

    public WarData(String attacker, String defender, long startTime) {
        this.attacker = attacker;
        this.defender = defender;
        this.startTime = startTime;

        attackerSide.add(attacker);
        defenderSide.add(defender);
    }

    public String getAttacker() { return attacker; }
    public String getDefender() { return defender; }
    public long getStartTime() { return startTime; }

    public boolean involves(String state) {
        return attacker.equalsIgnoreCase(state) || defender.equalsIgnoreCase(state);
    }

    public Set<String> getAttackerSide() { return attackerSide; }
    public Set<String> getDefenderSide() { return defenderSide; }

    public Set<String> getSideStates(String state) {
        if (state == null) {
            return Set.of();
        }
        if (attacker.equalsIgnoreCase(state)) {
            return attackerSide;
        }
        if (defender.equalsIgnoreCase(state)) {
            return defenderSide;
        }
        return Set.of();
    }

    public void renameState(String oldName, String newName) {
        if (attacker.equalsIgnoreCase(oldName)) {
            attacker = newName;
        }
        if (defender.equalsIgnoreCase(oldName)) {
            defender = newName;
        }

        if (attackerSide.remove(oldName)) {
            attackerSide.add(newName);
        }
        if (defenderSide.remove(oldName)) {
            defenderSide.add(newName);
        }

        // 更新已记录的状态键
        if (emergencyMovesUsed.remove(oldName.toLowerCase())) {
            emergencyMovesUsed.add(newName.toLowerCase());
        }
        Long broken = capitalBrokenAt.remove(oldName.toLowerCase());
        if (broken != null) {
            capitalBrokenAt.put(newName.toLowerCase(), broken);
        }
    }

    public boolean hasUsedEmergencyMove(String state) {
        return emergencyMovesUsed.contains(state.toLowerCase());
    }

    public void markEmergencyMove(String state) {
        emergencyMovesUsed.add(state.toLowerCase());
    }

    public void markCapitalBroken(String state, long timestamp) {
        capitalBrokenAt.put(state.toLowerCase(), timestamp);
    }

    public Long getCapitalBrokenAt(String state) {
        return capitalBrokenAt.get(state.toLowerCase());
    }

    public void clearCapitalBroken(String state) {
        capitalBrokenAt.remove(state.toLowerCase());
    }
}
