package org.bukkit.command;

public class PluginCommand extends Command {
    private CommandExecutor executor;

    public PluginCommand(String name) {
        super(name);
    }

    public void setExecutor(CommandExecutor executor) {
        this.executor = executor;
    }

    public CommandExecutor getExecutor() {
        return executor;
    }
}
