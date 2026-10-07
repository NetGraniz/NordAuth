package dev.nordfjell.auth;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.apache.logging.log4j.LogManager;

import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class NordAuthPlugin extends JavaPlugin {
    private record LoginFailure(int count, long lockedUntilMillis) {
    }

    @FunctionalInterface
    private interface DatabaseOperation<T> {
        T run() throws Exception;
    }

    private final Map<UUID, AuthState> states = new ConcurrentHashMap<>();
    private final AuthSessions<Player> sessions = new AuthSessions<>();
    private final Map<UUID, ScheduledTask> reminderTasks = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> timeoutTasks = new ConcurrentHashMap<>();
    private final Map<String, LoginFailure> failures = new ConcurrentHashMap<>();
    private final Set<UUID> passwordChanges = ConcurrentHashMap.newKeySet();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final PasswordHasher passwordHasher = new PasswordHasher();

    private ExecutorService databaseExecutor;
    private AuthRepository repository;
    private volatile boolean authenticationAvailable;
    private volatile Component unavailableMessage = Component.text("Authentication is temporarily unavailable.");
    private Map<String, String> messageTemplates = Map.of();
    private List<Component> welcomeMessages = List.of();
    private int databaseQueueCapacity;
    private long maximumQueueWaitNanos;
    private int minimumPasswordLength;
    private int maximumPasswordLength;
    private int maximumFailedAttempts;
    private int lockoutSeconds;
    private int loginTimeoutSeconds;
    private int reminderIntervalSeconds;
    private SensitiveCommandFilter commandLogFilter;
    private SensitiveLog4jFilter log4jCommandFilter;

    @Override
    public void onEnable() {
        authenticationAvailable = false;
        try {
            // Install the closed gate before touching configuration or the database.
            getServer().getPluginManager().registerEvents(new AuthRestrictionListener(this), this);
            installCommandLogFilter();
            saveDefaultConfig();
            loadSettings();
            Path databasePath = getServer().getWorldContainer().toPath().toAbsolutePath().normalize()
                .resolve(getConfig().getString("database.file", "plugins/AuthMe/authme.db")).normalize();
            String table = getConfig().getString("database.table", "authme");
            repository = new AuthRepository(databasePath, table);
            repository.initialize(databasePath);
            databaseExecutor = AuthExecutors.database(databaseQueueCapacity);
            AuthCommand authCommand = new AuthCommand(this);
            registerCommand("login", authCommand);
            registerCommand("register", authCommand);
            registerCommand("changepassword", authCommand);
            registerCommand("resetpassword", authCommand);
            authenticationAvailable = true;
            Bukkit.getOnlinePlayers().forEach(player -> runOnOwner(player, () -> beginAuthentication(player)));
            getLogger().info("NordAuth enabled with AuthMe-compatible SQLite storage and no telemetry.");
        } catch (Exception | LinkageError exception) {
            authenticationAvailable = false;
            getLogger().severe("NordAuth initialization failed; stopping server to prevent unauthenticated access. "
                + exception.getClass().getSimpleName());
            // The closed pre-login gate remains active until shutdown; never kick
            // players directly from Folia's startup/global thread.
            getServer().shutdown();
        }
    }

    @Override
    public void onDisable() {
        authenticationAvailable = false;
        sessions.clear();
        // Manual disabling/reloading must not leave an offline-mode server unprotected.
        if (!getServer().isStopping()) {
            getLogger().severe("NordAuth was disabled while the server was running; stopping server for safety.");
            getServer().shutdown();
        }
        reminderTasks.values().forEach(ScheduledTask::cancel);
        timeoutTasks.values().forEach(ScheduledTask::cancel);
        reminderTasks.clear();
        timeoutTasks.clear();
        states.clear();
        passwordChanges.clear();
        restoreCommandLogFilter();

        if (databaseExecutor != null) {
            // Queued requests must not modify accounts after the security gate closes.
            databaseExecutor.shutdownNow();
            try {
                if (!databaseExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    databaseExecutor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                databaseExecutor.shutdownNow();
            }
        }
    }

    void beginAuthentication(Player player) {
        if (!authenticationAvailable) {
            player.kick(unavailableMessage);
            return;
        }
        UUID playerId = player.getUniqueId();
        cancelTask(reminderTasks.remove(playerId));
        cancelTask(timeoutTasks.remove(playerId));
        passwordChanges.remove(playerId);
        sessions.begin(playerId, player);
        states.put(playerId, AuthState.LOADING);
        startTimers(player);
        send(player, "messages.loading");
        String username = player.getName();
        submitDatabase(player, () -> repository.isRegistered(username), registered -> {
            if (!isCurrent(player)) return;
            states.put(playerId, registered ? AuthState.LOGIN_REQUIRED : AuthState.REGISTER_REQUIRED);
            sendPrompt(player);
        }, null);
    }

    void endAuthentication(Player player) {
        endAuthentication(player.getUniqueId(), player);
    }

    private void endAuthentication(UUID playerId, Player player) {
        if (!sessions.end(playerId, player)) return;
        states.remove(playerId);
        passwordChanges.remove(playerId);
        cancelTask(reminderTasks.remove(playerId));
        cancelTask(timeoutTasks.remove(playerId));
    }

    boolean isAuthenticated(Player player) {
        return authenticationAvailable && sessions.find(player.getUniqueId(), player) != null
            && states.get(player.getUniqueId()) == AuthState.AUTHENTICATED;
    }

    boolean isAuthenticationAvailable() {
        return authenticationAvailable;
    }

    Component unavailableMessage() {
        return unavailableMessage;
    }

    void login(Player player, String password) {
        if (!isCurrent(player)) return;
        AuthState state = states.get(player.getUniqueId());
        if (state == AuthState.AUTHENTICATED) {
            send(player, "messages.already-authenticated");
            return;
        }
        if (state == AuthState.REGISTER_REQUIRED) {
            send(player, "messages.not-registered");
            return;
        }
        if (state != AuthState.LOGIN_REQUIRED) {
            send(player, "messages.authentication-in-progress");
            return;
        }

        String name = player.getName().toLowerCase(java.util.Locale.ROOT);
        long lockedSeconds = lockRemainingSeconds(name);
        if (lockedSeconds > 0) {
            send(player, "messages.locked", "<seconds>", String.valueOf(lockedSeconds));
            return;
        }

        states.put(player.getUniqueId(), AuthState.AUTHENTICATING);
        submitDatabase(player, () -> passwordHasher.verify(password, repository.passwordHash(name)), success -> {
            if (!isCurrent(player)) return;
            if (success) {
                failures.remove(name);
                authenticate(player, "messages.login-success");
            } else {
                states.put(player.getUniqueId(), AuthState.LOGIN_REQUIRED);
                recordFailedLogin(player, name);
            }
        }, AuthState.LOGIN_REQUIRED);
    }

    void register(Player player, String password, String confirmation) {
        if (!isCurrent(player)) return;
        AuthState state = states.get(player.getUniqueId());
        if (state == AuthState.AUTHENTICATED) {
            send(player, "messages.already-authenticated");
            return;
        }
        if (state == AuthState.LOGIN_REQUIRED) {
            send(player, "messages.already-registered");
            return;
        }
        if (state != AuthState.REGISTER_REQUIRED) {
            send(player, "messages.authentication-in-progress");
            return;
        }
        if (!password.equals(confirmation)) {
            send(player, "messages.passwords-do-not-match");
            return;
        }
        if (!validatePassword(player, password)) return;

        states.put(player.getUniqueId(), AuthState.AUTHENTICATING);
        String username = player.getName();
        submitDatabase(player,
            () -> repository.register(username, username, passwordHasher.hash(password)), result -> {
                if (!isCurrent(player)) return;
                if (result == AuthRepository.RegistrationResult.CREATED) {
                    authenticate(player, "messages.register-success");
                } else {
                    states.put(player.getUniqueId(), AuthState.LOGIN_REQUIRED);
                    send(player, "messages.already-registered");
                }
            }, AuthState.REGISTER_REQUIRED);
    }

    void changePassword(Player player, String oldPassword, String newPassword) {
        if (!isAuthenticated(player)) {
            send(player, "messages.authentication-required");
            return;
        }
        if (!validatePassword(player, newPassword)) return;
        if (!passwordChanges.add(player.getUniqueId())) {
            send(player, "messages.authentication-in-progress");
            return;
        }

        String username = player.getName();
        submitDatabase(player, () -> {
            String stored = repository.passwordHash(username);
            if (!passwordHasher.verify(oldPassword, stored)) return false;
            return repository.updatePassword(username, passwordHasher.hash(newPassword));
        }, success -> {
            passwordChanges.remove(player.getUniqueId());
            if (!isCurrent(player)) return;
            send(player, success ? "messages.password-changed" : "messages.current-password-incorrect");
        }, AuthState.AUTHENTICATED);
    }

    void resetPassword(CommandSender sender, String username, String newPassword) {
        if (sender instanceof Player player && !isAuthenticated(player)) {
            send(sender, "messages.authentication-required");
            return;
        }
        if (!validatePassword(sender, newPassword)) return;

        submitAdministrativeDatabase(sender,
            () -> repository.updatePassword(username, passwordHasher.hash(newPassword)), success -> {
                if (!success) {
                    send(sender, "messages.account-not-found", "<player>", username);
                    return;
                }
                getLogger().info("Password for account " + username + " was reset by " + sender.getName() + ".");
                send(sender, "messages.password-reset", "<player>", username);
            });
    }

    void send(CommandSender player, String path, String... replacements) {
        if (player instanceof Player owner && !Bukkit.isOwnedByCurrentRegion(owner)) {
            runOnOwner(owner, () -> send(owner, path, replacements));
            return;
        }
        String raw = messageTemplates.getOrDefault(path, "<red>Missing message: " + path + "</red>");
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            raw = raw.replace(replacements[index], replacements[index + 1]);
        }
        player.sendMessage(miniMessage.deserialize(raw));
    }

    private void authenticate(Player player, String successMessage) {
        states.put(player.getUniqueId(), AuthState.AUTHENTICATED);
        cancelTask(reminderTasks.remove(player.getUniqueId()));
        cancelTask(timeoutTasks.remove(player.getUniqueId()));
        player.updateCommands();
        send(player, successMessage);
        for (Component message : welcomeMessages) {
            player.sendMessage(message);
        }
    }

    private void recordFailedLogin(Player player, String name) {
        LoginFailure previous = failures.get(name);
        int count = previous == null ? 1 : previous.count() + 1;
        if (count >= maximumFailedAttempts) {
            long until = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(lockoutSeconds);
            failures.put(name, new LoginFailure(0, until));
            send(player, "messages.locked", "<seconds>", String.valueOf(lockoutSeconds));
            return;
        }
        failures.put(name, new LoginFailure(count, 0));
        send(player, "messages.invalid-password", "<remaining>", String.valueOf(maximumFailedAttempts - count));
    }

    private long lockRemainingSeconds(String name) {
        LoginFailure failure = failures.get(name);
        if (failure == null || failure.lockedUntilMillis() <= System.currentTimeMillis()) {
            if (failure != null && failure.lockedUntilMillis() > 0) failures.remove(name);
            return 0;
        }
        return Math.max(1, (failure.lockedUntilMillis() - System.currentTimeMillis() + 999) / 1000);
    }

    private boolean validatePassword(CommandSender player, String password) {
        if (password.length() < minimumPasswordLength) {
            send(player, "messages.password-too-short", "<minimum>", String.valueOf(minimumPasswordLength));
            return false;
        }
        if (password.length() > maximumPasswordLength) {
            send(player, "messages.password-too-long", "<maximum>", String.valueOf(maximumPasswordLength));
            return false;
        }
        return true;
    }

    private void startTimers(Player player) {
        UUID playerId = player.getUniqueId();
        long reminderTicks = Math.max(1, reminderIntervalSeconds) * 20L;
        Runnable retired = () -> endAuthentication(playerId, player);
        ScheduledTask reminder = player.getScheduler().runAtFixedRate(this, ignored -> {
            if (isCurrent(player) && !isAuthenticated(player)) sendPrompt(player);
        }, retired, reminderTicks, reminderTicks);
        if (reminder == null) {
            endAuthentication(playerId, player);
            return;
        }
        reminderTasks.put(playerId, reminder);
        ScheduledTask timeout = player.getScheduler().runDelayed(this, ignored -> {
            if (isCurrent(player) && !isAuthenticated(player)) {
                player.kick(miniMessage.deserialize(messageTemplates.getOrDefault("messages.login-timeout", "<red>Login timed out.</red>")));
            }
        }, retired, Math.max(1, loginTimeoutSeconds) * 20L);
        if (timeout == null) endAuthentication(playerId, player);
        else timeoutTasks.put(playerId, timeout);
    }

    private void sendPrompt(Player player) {
        AuthState state = states.get(player.getUniqueId());
        if (state == AuthState.LOGIN_REQUIRED) send(player, "messages.login-prompt");
        if (state == AuthState.REGISTER_REQUIRED) send(player, "messages.register-prompt");
    }

    private <T> void submitDatabase(Player player, DatabaseOperation<T> operation, Consumer<T> callback,
                                    AuthState rejectionState) {
        UUID playerId = player.getUniqueId();
        AuthSessions.Session<Player> session = sessions.find(playerId, player);
        AuthState expectedState = states.get(playerId);
        long queuedAt = System.nanoTime();
        try {
            if (!authenticationAvailable || session == null) throw new RejectedExecutionException();
            databaseExecutor.execute(() -> {
                if (!authenticationAvailable || !sessions.isCurrent(session)) return;
                try {
                    if (System.nanoTime() - queuedAt > maximumQueueWaitNanos) {
                        throw new TimeoutException("Authentication request expired in queue");
                    }
                    T result = operation.run();
                    runOnOwner(player, () -> {
                        if (isCurrent(session, expectedState)) callback.accept(result);
                    });
                } catch (Exception exception) {
                    // Do not log exception text: a driver or callback may include credentials.
                    getLogger().severe("Authentication database operation failed: "
                        + exception.getClass().getSimpleName());
                    runOnOwner(player, () -> {
                        if (isCurrent(session, expectedState)) player.kick(unavailableMessage);
                    });
                }
            });
        } catch (RejectedExecutionException exception) {
            if (!isCurrent(session, expectedState)) return;
            passwordChanges.remove(playerId);
            if (rejectionState == null) {
                player.kick(unavailableMessage);
            } else {
                states.put(playerId, rejectionState);
                send(player, "messages.database-error");
            }
        }
    }

    private <T> void submitAdministrativeDatabase(CommandSender sender, DatabaseOperation<T> operation,
                                                    Consumer<T> callback) {
        long queuedAt = System.nanoTime();
        AuthSessions.Session<Player> session = sender instanceof Player player
            ? sessions.find(player.getUniqueId(), player) : null;
        try {
            if (!authenticationAvailable) throw new RejectedExecutionException();
            databaseExecutor.execute(() -> {
                if (!authenticationAvailable || sender instanceof Player && !sessions.isCurrent(session)) return;
                try {
                    if (System.nanoTime() - queuedAt > maximumQueueWaitNanos) {
                        throw new TimeoutException("Administrative request expired in queue");
                    }
                    T result = operation.run();
                    runOnOwner(sender, () -> {
                        if (!(sender instanceof Player) || isCurrent(session, AuthState.AUTHENTICATED)) {
                            callback.accept(result);
                        }
                    });
                } catch (Exception exception) {
                    getLogger().severe("Administrative database operation failed: "
                        + exception.getClass().getSimpleName());
                    runOnOwner(sender, () -> {
                        if (!(sender instanceof Player) || isCurrent(session, AuthState.AUTHENTICATED)) {
                            send(sender, "messages.database-error");
                        }
                    });
                }
            });
        } catch (RejectedExecutionException exception) {
            send(sender, "messages.database-error");
        }
    }

    private void runOnOwner(CommandSender owner, Runnable action) {
        if (authenticationAvailable && isEnabled()) {
            try {
                Runnable guarded = () -> {
                    if (authenticationAvailable) action.run();
                };
                if (owner instanceof Player player) {
                    player.getScheduler().execute(this, guarded, null, 1L);
                } else {
                    Bukkit.getGlobalRegionScheduler().execute(this, guarded);
                }
            } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
                // Shutdown may disable this plugin between the availability check and scheduling.
            }
        }
    }

    private boolean isCurrent(Player player) {
        return authenticationAvailable && player.isOnline()
            && sessions.find(player.getUniqueId(), player) != null;
    }

    private boolean isCurrent(AuthSessions.Session<Player> session, AuthState expectedState) {
        return authenticationAvailable && sessions.isCurrent(session) && session.owner().isOnline()
            && states.get(session.playerId()) == expectedState;
    }

    private void loadSettings() {
        FileConfiguration config = getConfig();
        Map<String, String> templates = new HashMap<>();
        var messages = config.getConfigurationSection("messages");
        if (messages != null) messages.getValues(true).forEach((key, value) -> {
            if (value instanceof String text) templates.put("messages." + key, text);
        });
        messageTemplates = Map.copyOf(templates);
        welcomeMessages = config.getStringList("messages.welcome").stream().map(miniMessage::deserialize).toList();
        databaseQueueCapacity = Math.clamp(config.getInt("database.maximum-queued-requests", 128), 1, 4096);
        maximumQueueWaitNanos = TimeUnit.MILLISECONDS.toNanos(
            Math.clamp(config.getLong("database.maximum-queue-wait-millis", 10000), 1L, 60000L));
        unavailableMessage = miniMessage.deserialize(config.getString("messages.database-error",
            "<red>Authentication is temporarily unavailable.</red>"));
        minimumPasswordLength = Math.max(1, config.getInt("security.minimum-password-length", 5));
        maximumPasswordLength = Math.max(minimumPasswordLength,
            config.getInt("security.maximum-password-length", 64));
        maximumFailedAttempts = Math.max(1, config.getInt("security.maximum-failed-attempts", 5));
        lockoutSeconds = Math.max(1, config.getInt("security.lockout-seconds", 60));
        loginTimeoutSeconds = Math.max(1, config.getInt("security.login-timeout-seconds", 60));
        reminderIntervalSeconds = Math.max(1, config.getInt("security.reminder-interval-seconds", 5));
    }

    private void registerCommand(String name, AuthCommand executor) {
        PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void installCommandLogFilter() {
        org.apache.logging.log4j.core.Logger rootLogger =
            (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
        log4jCommandFilter = new SensitiveLog4jFilter();
        rootLogger.addFilter(log4jCommandFilter);
        Logger serverLogger = Bukkit.getLogger();
        commandLogFilter = new SensitiveCommandFilter(serverLogger.getFilter());
        serverLogger.setFilter(commandLogFilter);
    }

    private void restoreCommandLogFilter() {
        if (log4jCommandFilter != null) {
            // Log4j root filters live for the lifetime of the server process. Paper does not
            // support hot plugin reloads; the JVM discards this filter during normal shutdown.
            log4jCommandFilter = null;
        }
        Logger serverLogger = Bukkit.getLogger();
        if (commandLogFilter != null && serverLogger.getFilter() == commandLogFilter) {
            serverLogger.setFilter(commandLogFilter.previous());
        }
        commandLogFilter = null;
    }

    private static void cancelTask(ScheduledTask task) {
        if (task != null) task.cancel();
    }
}
