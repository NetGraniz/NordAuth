package dev.nordfjell.auth;

import org.junit.jupiter.api.Test;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.message.SimpleMessage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveCommandFilterTest {
    @Test
    void detectsAuthenticationCommandsInServerCommandLogs() {
        assertTrue(SensitiveCommandFilter.containsSensitivePlayerCommand(
            "Player issued server command: /login secret"));
        assertTrue(SensitiveCommandFilter.containsSensitivePlayerCommand(
            "Player issued server command: /REGISTER secret secret"));
        assertTrue(SensitiveCommandFilter.containsSensitivePlayerCommand(
            "Player issued server command: /changepassword old new"));
        assertTrue(SensitiveCommandFilter.containsSensitivePlayerCommand(
            "Admin issued server command: /resetpassword Player new-secret"));
        assertTrue(SensitiveCommandFilter.containsSensitivePlayerCommand(
            "Admin issued server command: /nordauth:resetpassword Player new-secret"));
    }

    @Test
    void keepsUnrelatedLogMessages() {
        assertFalse(SensitiveCommandFilter.containsSensitivePlayerCommand(
            "Player issued server command: /home"));
        assertFalse(SensitiveCommandFilter.containsSensitivePlayerCommand("Server started"));
    }

    @Test
    void log4jFilterDeniesPasswordsAndKeepsNormalCommands() {
        SensitiveLog4jFilter filter = new SensitiveLog4jFilter();
        assertTrue(filter.filter(null, null, null,
            new SimpleMessage("Player issued server command: /login secret"), null) == Filter.Result.DENY);
        assertTrue(filter.filter(null, null, null,
            new SimpleMessage("Player issued server command: /home"), null) == Filter.Result.NEUTRAL);
    }
}
