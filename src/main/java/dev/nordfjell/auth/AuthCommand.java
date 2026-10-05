package dev.nordfjell.auth;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

final class AuthCommand implements CommandExecutor, TabCompleter {
    private final NordAuthPlugin plugin;

    AuthCommand(NordAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("resetpassword")) {
            return resetPassword(sender, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        return switch (command.getName().toLowerCase()) {
            case "login" -> login(player, args);
            case "register" -> register(player, args);
            case "changepassword" -> changePassword(player, args);
            default -> false;
        };
    }

    private boolean login(Player player, String[] args) {
        if (args.length != 1) {
            plugin.send(player, "messages.login-usage");
            return true;
        }
        plugin.login(player, args[0]);
        return true;
    }

    private boolean register(Player player, String[] args) {
        if (args.length != 2) {
            plugin.send(player, "messages.register-usage");
            return true;
        }
        plugin.register(player, args[0], args[1]);
        return true;
    }

    private boolean changePassword(Player player, String[] args) {
        if (args.length != 2) {
            plugin.send(player, "messages.change-password-usage");
            return true;
        }
        plugin.changePassword(player, args[0], args[1]);
        return true;
    }

    private boolean resetPassword(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nordauth.admin.resetpassword")) {
            plugin.send(sender, "messages.no-permission");
            return true;
        }
        if (args.length != 2) {
            plugin.send(sender, "messages.reset-password-usage");
            return true;
        }
        plugin.resetPassword(sender, args[0], args[1]);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
