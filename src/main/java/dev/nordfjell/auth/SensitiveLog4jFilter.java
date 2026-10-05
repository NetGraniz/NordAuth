package dev.nordfjell.auth;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

/** Filters authentication passwords at Paper's actual Log4j logging layer. */
final class SensitiveLog4jFilter extends AbstractFilter {
    private static Result validate(Message message) {
        return message != null && SensitiveCommandFilter.containsSensitivePlayerCommand(message.getFormattedMessage())
            ? Result.DENY : Result.NEUTRAL;
    }

    private static Result validate(String message) {
        return SensitiveCommandFilter.containsSensitivePlayerCommand(message) ? Result.DENY : Result.NEUTRAL;
    }

    @Override
    public Result filter(LogEvent event) {
        return validate(event == null ? null : event.getMessage());
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message message, Throwable throwable) {
        return validate(message);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object... parameters) {
        return validate(message);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object message, Throwable throwable) {
        return validate(message == null ? null : message.toString());
    }
}
