package dev.nordfjell.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {
    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void verifiesAuthMeSha256Hash() {
        String stored = hasher.hash("correct horse battery staple", "0123456789abcdef");

        assertTrue(hasher.verify("correct horse battery staple", stored));
        assertFalse(hasher.verify("wrong password", stored));
    }

    @Test
    void rejectsMalformedHashes() {
        assertFalse(hasher.verify("password", null));
        assertFalse(hasher.verify("password", "plain text"));
        assertFalse(hasher.verify("password", "$SHA$short$deadbeef"));
    }
}
