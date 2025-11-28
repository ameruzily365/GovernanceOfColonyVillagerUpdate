package dev.ameruzily.campsystem.models;

public class Camp {
    private static final double BROKEN_RECOVERY_THRESHOLD = 10.0;
    private final String id;
    private String stateName;
    private String sectorName;
    private double maxHp;

    private double hp;
    private long brokenSince = -1L;
    private long lastDamagedAt = 0L;
    private long lastMaintainedAt = 0L;
    private long nextMaintenanceAt = 0L;
    private boolean maintenanceWarningIssued;
    private boolean maintenanceOverdueNotified;
    private long lastMaintenanceDecayAt = 0L;

    private int fuel;
    private int maxFuel;
    private long lastFuelCheckAt = 0L;

    private double healRate;
    private int fatigueAmplifier;
    private int hpLevel;
    private int fuelLevel;
    private int healLevel;
    private int fatigueLevel;

    public Camp(String id, String stateName, String sectorName, double maxHp) {
        this.id = id;
        this.stateName = stateName;
        this.sectorName = sectorName;
        this.maxHp = maxHp;
        this.hp = maxHp;
        long now = System.currentTimeMillis();
        this.lastMaintainedAt = now;
        this.nextMaintenanceAt = now;
        this.maxFuel = 0;
        this.fuel = 0;
        this.lastFuelCheckAt = now;
        this.healRate = 0.0;
        this.fatigueAmplifier = 0;
    }

    public String getId() { return id; }
    public String getStateName() { return stateName; }
    public String getSectorName() { return sectorName; }
    public double getHp() { return hp; }
    public double getMaxHp() { return maxHp; }
    public long getBrokenSince() { return brokenSince; }
    public long getLastDamagedAt() { return lastDamagedAt; }
    public long getLastMaintainedAt() { return lastMaintainedAt; }
    public long getNextMaintenanceAt() { return nextMaintenanceAt; }
    public boolean isMaintenanceWarningIssued() { return maintenanceWarningIssued; }
    public boolean isMaintenanceOverdueNotified() { return maintenanceOverdueNotified; }
    public long getLastMaintenanceDecayAt() { return lastMaintenanceDecayAt; }

    public int getFuel() { return fuel; }
    public int getMaxFuel() { return maxFuel; }
    public long getLastFuelCheckAt() { return lastFuelCheckAt; }

    /**
     * 造成伤害，并在血量降至 0 时返回 true。
     */
    public boolean damage(double amount) {
        lastDamagedAt = System.currentTimeMillis();
        hp = Math.max(0.0, hp - amount);
        boolean nowBroken = hp <= 0.0;
        normalizeBrokenState();
        return nowBroken;
    }

    /**
     * 直接设置当前血量。
     */
    public void setHp(double hp) {
        this.hp = Math.min(maxHp, Math.max(0.0, hp));
        normalizeBrokenState();
    }

    public void setMaxHp(double maxHp) {
        this.maxHp = Math.max(1.0, maxHp);
        if (hp > this.maxHp) {
            hp = this.maxHp;
        }
    }

    public void heal(double amount) {
        if (isBroken()) {
            return;
        }
        this.hp = Math.min(maxHp, hp + amount);
    }

    public void repair(double amount) {
        this.hp = Math.min(maxHp, Math.max(0.0, hp + amount));
        normalizeBrokenState();
    }

    public void restoreFull() {
        this.hp = maxHp;
        this.brokenSince = -1L;
    }

    public boolean isBroken() { return brokenSince >= 0L; }

    public void setStateName(String stateName) {
        this.stateName = stateName;
    }

    public void setSectorName(String sectorName) {
        this.sectorName = sectorName;
    }

    public void setLastMaintainedAt(long lastMaintainedAt) {
        this.lastMaintainedAt = lastMaintainedAt;
    }

    public void setNextMaintenanceAt(long nextMaintenanceAt) {
        this.nextMaintenanceAt = nextMaintenanceAt;
    }

    public void setMaintenanceWarningIssued(boolean maintenanceWarningIssued) {
        this.maintenanceWarningIssued = maintenanceWarningIssued;
    }

    public void setMaintenanceOverdueNotified(boolean maintenanceOverdueNotified) {
        this.maintenanceOverdueNotified = maintenanceOverdueNotified;
    }

    public void setLastMaintenanceDecayAt(long lastMaintenanceDecayAt) {
        this.lastMaintenanceDecayAt = lastMaintenanceDecayAt;
    }

    public void setFuel(int fuel) {
        this.fuel = Math.max(0, Math.min(maxFuel, fuel));
    }

    public void setMaxFuel(int maxFuel) {
        this.maxFuel = Math.max(0, maxFuel);
        if (fuel > this.maxFuel) {
            fuel = this.maxFuel;
        }
    }

    public void setLastFuelCheckAt(long lastFuelCheckAt) {
        this.lastFuelCheckAt = lastFuelCheckAt;
    }

    public void addFuel(int amount) {
        if (amount <= 0) {
            return;
        }
        setFuel(fuel + amount);
    }

    public double getHealRate() {
        return healRate;
    }

    public void setHealRate(double healRate) {
        this.healRate = Math.max(0.0, healRate);
    }

    public int getFatigueAmplifier() {
        return fatigueAmplifier;
    }

    public void setFatigueAmplifier(int fatigueAmplifier) {
        this.fatigueAmplifier = Math.max(0, fatigueAmplifier);
    }

    public int getHpLevel() {
        return hpLevel;
    }

    public void setHpLevel(int hpLevel) {
        this.hpLevel = Math.max(0, hpLevel);
    }

    public int getFuelLevel() {
        return fuelLevel;
    }

    public void setFuelLevel(int fuelLevel) {
        this.fuelLevel = Math.max(0, fuelLevel);
    }

    public int getHealLevel() {
        return healLevel;
    }

    public void setHealLevel(int healLevel) {
        this.healLevel = Math.max(0, healLevel);
    }

    public int getFatigueLevel() {
        return fatigueLevel;
    }

    public void setFatigueLevel(int fatigueLevel) {
        this.fatigueLevel = Math.max(0, fatigueLevel);
    }

    public void resetMaintenance(long now, long nextDue) {
        this.lastMaintainedAt = now;
        this.nextMaintenanceAt = nextDue;
        this.maintenanceWarningIssued = false;
        this.maintenanceOverdueNotified = false;
        this.lastMaintenanceDecayAt = 0L;
    }

    public void setBrokenSince(long brokenSince) {
        this.brokenSince = brokenSince;
        normalizeBrokenState();
    }

    public void setLastDamagedAt(long lastDamagedAt) {
        this.lastDamagedAt = lastDamagedAt;
    }

    private void normalizeBrokenState() {
        if (hp <= 0.0) {
            if (brokenSince < 0) {
                brokenSince = System.currentTimeMillis();
            }
            return;
        }

        if (brokenSince >= 0 && hp >= BROKEN_RECOVERY_THRESHOLD) {
            brokenSince = -1L;
        }
    }
}
