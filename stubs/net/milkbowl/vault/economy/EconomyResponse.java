package net.milkbowl.vault.economy;

public class EconomyResponse {
    private final boolean success;

    public EconomyResponse(boolean success) {
        this.success = success;
    }

    public boolean transactionSuccess() {
        return success;
    }
}
