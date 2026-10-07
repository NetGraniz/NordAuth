# NordAuth

> Release build and installation requirements: see [BUILDING.md](BUILDING.md).
> Older local paths below describe historical test fixtures, not the release build.

NordAuth is a Paper/Folia password authentication plugin for Nord Fjell.
The same release JAR supports both platforms; development stays on `main`.

It intentionally supports only:

- `/register <password> <password>`
- `/login <password>`
- `/changepassword <oldPassword> <newPassword>`
- `/resetpassword <player> <newPassword>` for administrators with
  `nordauth.admin.resetpassword`
- login/register prompts and a welcome message

The plugin reads and writes the existing AuthMe SQLite `authme` table and uses AuthMe's compatible
`$SHA$<salt>$<hash>` password format. It does not collect IP addresses, locations, email addresses,
analytics, or session-login data.

The SHA-256 compatibility implementation is derived from AuthMeReloaded, licensed under GPL-3.0:
https://github.com/AuthMe/AuthMeReloaded

## Safe migration

1. Build and test NordAuth away from the production server.
2. Stop the production server before replacing authentication plugins.
3. Back up `plugins/AuthMe/authme.db` and the complete `plugins/AuthMe` directory.
4. Remove the AuthMe jar, add the NordAuth jar, and copy the database to
   `plugins/NordAuth/authme.db`.
5. Start the server and test an existing account before opening it to players.

Never run AuthMe and NordAuth against the same SQLite database at the same time.

## 1.3.0 Paper and Folia support

- Database results, authentication reminders and timeout kicks use the player's
  entity scheduler. Console callbacks use the global scheduler.
- Chat restriction responses are routed back to the player's owning region.
- Connection identity and expected-state checks still reject stale database callbacks.
- Message templates are copied at startup instead of reading mutable configuration
  from different regions. The database schema and password format are unchanged.
- Keep the existing configuration and database. No migration or password reset is
  required by this update. Do not disable or hot-reload NordAuth on a live server.

## 1.2.2 security fixes

- Initialization failures keep the login gate closed and request a full Paper shutdown.
  Disabling NordAuth while Paper is running also stops Paper. Do not hot-reload this plugin.
- Database results are bound to a connection token, player instance and expected authentication
  state. A result or error for a disconnected player cannot change a replacement connection.
- The database worker now has a bounded queue (128 waiting requests by default). Full queues
  reject work rather than execute SQL on the game thread. Rejected login/registration/password
  change commands can be retried; initial account lookups fail closed by disconnecting the player.
- Requests waiting more than 10 seconds expire before database execution. Existing configuration
  files automatically use these defaults; optional keys are `database.maximum-queued-requests`
  (1..4096) and `database.maximum-queue-wait-millis` (1..60000).
- Player names are captured on the game thread, and shutdown discards waiting database requests.
  Already executing SQL operations are not guaranteed to be reversible after a disconnect.
- Existing AuthMe hashes and database schema remain unchanged. This patch does not migrate
  SHA-256 passwords to a stronger password-storage algorithm.

Fail-closed handling applies when NordAuth is loaded and its lifecycle executes. A missing JAR,
an incompatible plugin rejected before `onEnable`, or removal of the plugin is not something this
plugin can protect against. Before opening production to players, verify that NordAuth actually
loaded and that login restrictions work. Fix startup errors before allowing automated restarts.

## Reproducing the isolated security tests

Run `mvn package` in this project. The tests gated by the `authme.db` system property are skipped
by default; do not pass a production database path. Tests use temporary synthetic databases.

Integration sources are in `test-support` in the working Git repository. Use a new,
isolated local `C:\Users\artyo\Documents\Codex\nordauth-test-...` fixture with a `server`
subdirectory. Copy only the selected platform's server executable, cache and an
already accepted EULA; never copy production worlds, accounts or operational configuration.

The local `server.properties` must bind to `127.0.0.1:25585`, use offline mode, disable RCON/query,
and use its own small world. The harness deliberately uses a queue of one waiting request to
exercise overload deterministically. It writes local fixture configuration and synthetic accounts.

Build `test-support/probe/pom.xml` separately and put its JAR **only on the local test server**,
alongside NordAuth. The probe has a console-only command to exercise manual plugin disabling.
Never install `NordAuthTestProbe` on production.

The test client's dependencies match the pinned `NordLoadTest` package and lock file.
Use its installed dependencies with prepared 26.2 metadata; set `NODE_PATH` to that
`node_modules`. Pass the fixture path, Java executable, Python executable and platform:

```powershell
node ./test-support/integration.cjs $FixturePath $JavaExecutable $PythonExecutable Folia
# Run separately with a fresh Paper fixture and the last argument Paper.
# Afterwards, run the additional administrative tests on each isolated fixture:
node ./test-support/integration.cjs $FixturePath $JavaExecutable $PythonExecutable Folia admin-only
```

The harness uses hidden child processes, enforces a local fixture path, creates no external
listener, stops its servers on completion, and leaves a JSON result in the local fixture.
It is a functional/security regression suite, not proof of capacity for 1000 connections.

The test-only probe grants the password-reset permission to the fixture console;
`natestgrant` grants that same permission to an isolated bot. Production permissions
are unchanged (`nordauth.admin.resetpassword` still requires an explicit grant).
Verify that permission before entering a password in a console command: Minecraft
may echo rejected/unknown console commands in parser diagnostics.
