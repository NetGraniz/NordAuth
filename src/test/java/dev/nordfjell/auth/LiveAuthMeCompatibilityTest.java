package dev.nordfjell.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveAuthMeCompatibilityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @EnabledIfSystemProperty(named = "authme.db", matches = ".+")
    void currentAuthMeDatabaseHasCompatibleSchemaAndHashes() throws Exception {
        Path path = Path.of(System.getProperty("authme.db")).toAbsolutePath().normalize();
        String jdbcUrl = "jdbc:sqlite:file:" + path.toString().replace('\\', '/') + "?mode=ro";

        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            Set<String> columns = new HashSet<>();
            try (ResultSet result = statement.executeQuery("PRAGMA table_info(authme)")) {
                while (result.next()) columns.add(result.getString("name"));
            }
            assertTrue(columns.containsAll(Set.of("id", "username", "realname", "password", "regdate")));

            int accounts;
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM authme")) {
                assertTrue(result.next());
                accounts = result.getInt(1);
            }
            assertTrue(accounts > 0, "The production database should contain at least one account");

            int incompatibleHashes;
            try (ResultSet result = statement.executeQuery(
                "SELECT COUNT(*) FROM authme WHERE length(password) <> 86 OR password NOT LIKE '$SHA$%'")) {
                assertTrue(result.next());
                incompatibleHashes = result.getInt(1);
            }
            assertEquals(0, incompatibleHashes, "Every current password must use AuthMe SHA256 format");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "authme.db", matches = ".+")
    void canRegisterAndChangePasswordInCopyOfCurrentAuthMeDatabase() throws Exception {
        Path source = Path.of(System.getProperty("authme.db")).toAbsolutePath().normalize();
        Path copy = temporaryDirectory.resolve("authme-copy.db");
        Files.copy(source, copy);

        AuthRepository repository = new AuthRepository(copy, "authme");
        PasswordHasher hasher = new PasswordHasher();
        String username = "NordAuthMigrationTest";
        String initialPassword = "temporary-initial-password";
        String replacementPassword = "temporary-replacement-password";

        assertEquals(AuthRepository.RegistrationResult.CREATED,
            repository.register(username, username, hasher.hash(initialPassword)));
        assertTrue(hasher.verify(initialPassword, repository.passwordHash(username)));
        assertTrue(repository.updatePassword(username, hasher.hash(replacementPassword)));
        assertTrue(hasher.verify(replacementPassword, repository.passwordHash(username)));
        assertFalse(hasher.verify(initialPassword, repository.passwordHash(username)));
    }
}
