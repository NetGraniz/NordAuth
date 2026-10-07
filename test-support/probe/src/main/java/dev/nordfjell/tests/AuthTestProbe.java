package dev.nordfjell.tests;

import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Objects;

/** Test fixture only. Never install this helper on production. */
public final class AuthTestProbe extends JavaPlugin {
    @Override
    public void onEnable() {
        getServer().getConsoleSender().addAttachment(this, "nordauth.admin.resetpassword", true);
        Objects.requireNonNull(getCommand("natestgrant")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof ConsoleCommandSender) || args.length != 1) return true;
            var player = getServer().getPlayerExact(args[0]);
            if (player != null) player.getScheduler().execute(this, () -> {
                player.addAttachment(this, "nordauth.admin.resetpassword", true);
                getLogger().info("NORD_AUTH_TEST_PERMISSION_GRANTED " + player.getName());
            }, null, 1L);
            return true;
        });
        Objects.requireNonNull(getCommand("natestdisable")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof ConsoleCommandSender)) return true;
            var auth = Objects.requireNonNull(getServer().getPluginManager().getPlugin("NordAuth"));
            getServer().getPluginManager().disablePlugin(auth);
            return true;
        });
    }
}
