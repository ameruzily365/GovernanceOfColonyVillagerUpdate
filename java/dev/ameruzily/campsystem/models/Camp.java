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

    private double storedMoney;
    private double maxStoredMoney;
    private int maxStoredItems;
    private long lastProductionAt = 0L;
    private final java.util.Map<String, Integer> storedItems = new java.util.HashMap<>();
    private long productionIntervalMs;

    private double healRate;
    private int fatigueAmplifier;
    private int hpLevel;
    private int fuelLevel;
    private int healLevel;
    private int fatigueLevel;
    private int storageLevel;
    private int efficiencyLevel;
    private int boundaryLevel;
    private CampBoundary boundary;
    private final java.util.Map<String, Boolean> modules = new java.util.HashMap<>();

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
        this.maxStoredMoney = 0.0;
        this.storedMoney = 0.0;
        this.maxStoredItems = 0;
        this.productionIntervalMs = 0L;
        this.lastProductionAt = now;
        this.boundaryLevel = 0;
        this.boundary = new CampBoundary(0.0);
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
    public double getStoredMoney() { return storedMoney; }
    public double getMaxStoredMoney() { return maxStoredMoney; }
    public int getMaxStoredItems() { return maxStoredItems; }
    public long getLastProductionAt() { return lastProductionAt; }
    public long getProductionIntervalMs() { return productionIntervalMs; }
    public java.util.Map<String, Integer> getStoredItems() { return storedItems; }

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

    public void setStoredMoney(double storedMoney) {
        this.storedMoney = Math.max(0.0, Math.min(maxStoredMoney, storedMoney));
    }

    public void setMaxStoredMoney(double maxStoredMoney) {
        this.maxStoredMoney = Math.max(0.0, maxStoredMoney);
        if (storedMoney > this.maxStoredMoney) {
            storedMoney = this.maxStoredMoney;
        }
    }

    public void setMaxStoredItems(int maxStoredItems) {
        this.maxStoredItems = Math.max(0, maxStoredItems);
        clampStoredItems();
    }

    public void setLastProductionAt(long lastProductionAt) {
        this.lastProductionAt = Math.max(0L, lastProductionAt);
    }

    public void setProductionIntervalMs(long productionIntervalMs) {
        this.productionIntervalMs = Math.max(0L, productionIntervalMs);
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

    public int getStorageLevel() { return storageLevel; }

    public void setStorageLevel(int storageLevel) { this.storageLevel = Math.max(0, storageLevel); }

    public int getEfficiencyLevel() { return efficiencyLevel; }

    public void setEfficiencyLevel(int efficiencyLevel) { this.efficiencyLevel = Math.max(0, efficiencyLevel); }

    public int getBoundaryLevel() { return boundaryLevel; }

    public void setBoundaryLevel(int boundaryLevel) { this.boundaryLevel = Math.max(0, boundaryLevel); }

    public CampBoundary getBoundary() { return boundary; }

    public void setBoundary(CampBoundary boundary) { this.boundary = boundary == null ? null : boundary.copy(); }

    public java.util.Map<String, Boolean> getModules() { return java.util.Collections.unmodifiableMap(modules); }

    public boolean hasModule(String key) { return key != null && modules.containsKey(normalizeModuleKey(key)); }

    public boolean isModuleEnabled(String key) {
        if (key == null) {
            return false;
        }
        Boolean value = modules.get(normalizeModuleKey(key));
        return value != null && value;
    }

    public void setModuleState(String key, boolean enabled) {
        if (key == null) {
            return;
        }
        modules.put(normalizeModuleKey(key), enabled);
    }

    public void setModules(java.util.Map<String, Boolean> state) {
        modules.clear();
        if (state == null) {
            return;
        }
        for (java.util.Map.Entry<String, Boolean> entry : state.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            modules.put(normalizeModuleKey(entry.getKey()), entry.getValue() != null && entry.getValue());
        }
    }

    private String normalizeModuleKey(String key) {
        return key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
    }

    public void addStoredMoney(double amount) {
        if (amount <= 0.0) {
            return;
        }
        setStoredMoney(storedMoney + amount);
    }

    public void addStoredItem(String identity, int amount) {
        if (identity == null || identity.isEmpty() || amount <= 0) {
            return;
        }
        int allowed = Math.max(0, maxStoredItems - getStoredItemTotal());
        if (allowed <= 0) {
            return;
        }
        int toStore = Math.min(allowed, amount);
        storedItems.merge(identity, toStore, Integer::sum);
        clampStoredItems();
    }

    public int getStoredItemTotal() {
        return storedItems.values().stream().mapToInt(Integer::intValue).sum();
    }

    public void clearStoredItems() {
        storedItems.clear();
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

    private void clampStoredItems() {
        int total = getStoredItemTotal();
        if (total <= maxStoredItems) {
            return;
        }
        int toRemove = total - maxStoredItems;
        for (java.util.Iterator<java.util.Map.Entry<String, Integer>> iterator = storedItems.entrySet().iterator();
             iterator.hasNext() && toRemove > 0; ) {
            java.util.Map.Entry<String, Integer> entry = iterator.next();
            int reduce = Math.min(entry.getValue(), toRemove);
            int newAmount = entry.getValue() - reduce;
            toRemove -= reduce;
            if (newAmount <= 0) {
                iterator.remove();
            } else {
                entry.setValue(newAmount);
            }
        }
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
