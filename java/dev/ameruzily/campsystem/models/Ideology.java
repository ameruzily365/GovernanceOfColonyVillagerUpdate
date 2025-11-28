package dev.ameruzily.campsystem.models;

public class Ideology {
    private final String id;
    private final String displayName;
    private final String permission;

    public Ideology(String id, String displayName, String permission) {
        this.id = id;
        this.displayName = displayName;
        this.permission = permission;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getPermission() { return permission; }
}
