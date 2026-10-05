package dev.nordfjell.auth;

import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.message.SimpleMessage;

public final class FilterSmokeTest {
    private FilterSmokeTest() {
    }

    public static void main(String[] args) {
        SensitiveLog4jFilter filter = new SensitiveLog4jFilter();
        assert filter.filter(null, null, null,
            new SimpleMessage("Player issued server command: /login secret"), null) == Filter.Result.DENY;
        assert filter.filter(null, null, null,
            new SimpleMessage("Player issued server command: /register secret secret"), null) == Filter.Result.DENY;
        assert filter.filter(null, null, null,
            new SimpleMessage("Admin issued server command: /resetpassword Player secret"), null) == Filter.Result.DENY;
        assert filter.filter(null, null, null,
            new SimpleMessage("Player issued server command: /home"), null) == Filter.Result.NEUTRAL;
    }
}
