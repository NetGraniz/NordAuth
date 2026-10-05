package dev.nordfjell.tests;

import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Objects;

/** Test fixture only. Never install this helper on production. */
public final class AuthTestProbe extends JavaPlugin {
    @Override
    public void onEnable() {
        Objects.requireNonNull(getCommand("natestdisable")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof ConsoleCommandSender)) return true;
            var auth = Objects.requireNonNull(getServer().getPluginManager().getPlugin("NordAuth"));
            getServer().getPluginManager().disablePlugin(auth);
            return true;
        });
    }
}
