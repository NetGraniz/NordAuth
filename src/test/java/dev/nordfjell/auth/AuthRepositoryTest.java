package dev.nordfjell.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthRepositoryTest {
    @TempDir
    Path directory;

    @Test
    void registersAndUpdatesAnAuthMeCompatibleAccount() throws Exception {
        Path database = directory.resolve("authme.db");
        AuthRepository repository = new AuthRepository(database, "authme");
        repository.initialize(database);

        assertFalse(repository.isRegistered("PlayerOne"));
        assertEquals(AuthRepository.RegistrationResult.CREATED,
            repository.register("PlayerOne", "PlayerOne", "$SHA$0123456789abcdef$first"));
        assertTrue(repository.isRegistered("playerone"));
        assertEquals("$SHA$0123456789abcdef$first", repository.passwordHash("PLAYERONE"));
        assertEquals(AuthRepository.RegistrationResult.ALREADY_EXISTS,
            repository.register("playerone", "playerone", "$SHA$0123456789abcdef$other"));

        assertTrue(repository.updatePassword("PlayerOne", "$SHA$fedcba9876543210$second"));
        assertEquals("$SHA$fedcba9876543210$second", repository.passwordHash("playerone"));
    }
}
