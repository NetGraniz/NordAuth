package dev.nordfjell.auth;

import java.util.Locale;
import java.util.Set;
import java.util.logging.Filter;
import java.util.logging.LogRecord;

/** Prevents passwords in authentication commands from being written to the server log. */
final class SensitiveCommandFilter implements Filter {
    private static final Set<String> SENSITIVE_COMMANDS = Set.of(
        "login", "register", "changepassword", "resetpassword");
    private final Filter previous;

    SensitiveCommandFilter(Filter previous) {
        this.previous = previous;
    }

    @Override
    public boolean isLoggable(LogRecord record) {
        if (containsSensitivePlayerCommand(record.getMessage())) {
            return false;
        }
        return previous == null || previous.isLoggable(record);
    }

    Filter previous() {
        return previous;
    }

    static boolean containsSensitivePlayerCommand(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        int marker = lower.indexOf("issued server command:");
        if (marker < 0) return false;
        int slash = lower.indexOf('/', marker);
        if (slash < 0 || slash + 1 >= lower.length()) return false;
        String[] arguments = lower.substring(slash + 1).trim().split("\\s+");
        String command = withoutNamespace(arguments[0]);
        return SENSITIVE_COMMANDS.contains(command);
    }

    private static String withoutNamespace(String command) {
        int separator = command.lastIndexOf(':');
        return separator < 0 ? command : command.substring(separator + 1);
    }
}
