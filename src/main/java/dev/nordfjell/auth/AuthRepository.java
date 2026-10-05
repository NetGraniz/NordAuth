package dev.nordfjell.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

final class AuthRepository {
    enum RegistrationResult {
        CREATED,
        ALREADY_EXISTS
    }

    private final String jdbcUrl;
    private final String table;

    AuthRepository(Path databasePath, String table) {
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath().normalize();
        if (!table.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid database table name");
        }
        this.table = table;
    }

    void initialize(Path databasePath) throws SQLException, IOException {
        Path parent = databasePath.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "username VARCHAR(255) NOT NULL UNIQUE, "
                + "realname VARCHAR(255) NOT NULL DEFAULT 'Player', "
                + "password VARCHAR(255) NOT NULL DEFAULT '', "
                + "regdate BIGINT NOT NULL DEFAULT 0"
                + ")");
        }
    }

    boolean isRegistered(String username) throws SQLException {
        String sql = "SELECT 1 FROM " + table + " WHERE username = ? COLLATE NOCASE LIMIT 1";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalize(username));
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    String passwordHash(String username) throws SQLException {
        String sql = "SELECT password FROM " + table + " WHERE username = ? COLLATE NOCASE LIMIT 1";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalize(username));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    RegistrationResult register(String username, String realName, String passwordHash) throws SQLException {
        String normalized = normalize(username);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                if (exists(connection, normalized)) {
                    connection.rollback();
                    return RegistrationResult.ALREADY_EXISTS;
                }
                String sql = "INSERT INTO " + table
                    + " (username, realname, password) VALUES (?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, normalized);
                    statement.setString(2, realName);
                    statement.setString(3, passwordHash);
                    statement.executeUpdate();
                }
                connection.commit();
                return RegistrationResult.CREATED;
            } catch (SQLException exception) {
                connection.rollback();
                if (isUniqueConstraint(exception)) {
                    return RegistrationResult.ALREADY_EXISTS;
                }
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    boolean updatePassword(String username, String passwordHash) throws SQLException {
        String sql = "UPDATE " + table + " SET password = ? WHERE username = ? COLLATE NOCASE";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, passwordHash);
            statement.setString(2, normalize(username));
            return statement.executeUpdate() == 1;
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    private boolean exists(Connection connection, String username) throws SQLException {
        String sql = "SELECT 1 FROM " + table + " WHERE username = ? COLLATE NOCASE LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static String normalize(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private static boolean isUniqueConstraint(SQLException exception) {
        String message = exception.getMessage();
        return exception.getErrorCode() == 19
            || message != null && message.toLowerCase(Locale.ROOT).contains("unique");
    }
}
