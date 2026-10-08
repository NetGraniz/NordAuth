# NordAuth

Password authentication for Paper 26.2 and Folia 26.2. One Java 25 JAR supports both platforms; development stays on `main`. Build and installation requirements are in [BUILDING.md](BUILDING.md).

## Commands

- `/register <password> <password>`
- `/login <password>`
- `/changepassword <oldPassword> <newPassword>`
- `/resetpassword <player> <newPassword>`

NordAuth also sends login and registration prompts and a welcome message.

## Permissions

| Permission | Allows | Default |
| --- | --- | --- |
| `nordauth.admin.resetpassword` | `/resetpassword <player> <newPassword>` | Nobody, including operators; requires an explicit grant |

Registration, login and self-service password changes have no separate permission node. NordAuth checks the account's authentication state.

Check permission before entering a password in a console command. Minecraft may echo rejected or unknown commands in parser diagnostics.

## Password storage and attribution

NordAuth reads and writes the SQLite `authme` table and the compatible `$SHA$<salt>$<hash>` password format. It does not collect IP addresses, locations, email addresses, analytics or session-login data.

The SHA-256 compatibility implementation derives from AuthMeReloaded, licensed under GPL-3.0: https://github.com/AuthMe/AuthMeReloaded

## Safe migration

1. Build and test NordAuth away from production.
2. Stop the server before replacing its authentication plugin.
3. Back up `plugins/AuthMe/authme.db` and the complete `plugins/AuthMe` directory.
4. Remove the AuthMe JAR, install NordAuth and copy the database to `plugins/NordAuth/authme.db`.
5. Start the server and test an existing account before admitting players.

Never run AuthMe and NordAuth against the same SQLite database at the same time.

## 1.3.0 Paper and Folia support

Database results, authentication reminders and timeout kicks use the player's entity scheduler. Console callbacks use the global scheduler. Chat restriction replies return to the player's owning region.

Callbacks check connection identity and expected authentication state. Startup copies message templates so different regions do not read mutable configuration.

The database schema and password format are unchanged. Keep the existing configuration and database; this update needs no data migration or password reset. Do not disable or hot reload NordAuth on a live server.

## 1.2.2 security fixes

Initialization failure keeps the login gate closed and requests a full server shutdown. Disabling NordAuth while the server runs also stops the server.

Each database result belongs to a connection token, player instance and expected authentication state. A stale result or error cannot change a replacement connection.

The database worker has a bounded queue: 128 waiting requests by default. A full queue rejects work rather than running SQL on a game thread. Players can retry rejected login, registration and password-change commands; a rejected initial lookup disconnects the player.

Requests waiting longer than 10 seconds expire before execution. Existing configuration uses these defaults without an edit. Optional settings are:

| Setting | Accepted range |
| --- | --- |
| `database.maximum-queued-requests` | 1..4096 |
| `database.maximum-queue-wait-millis` | 1..60000 |

Player names are captured on the game thread. Shutdown discards waiting requests, but an SQL operation already running may not be reversible after a disconnect.

Existing hashes remain unchanged. This update does not migrate SHA-256 passwords to a stronger password-storage algorithm.

Fail-closed handling depends on NordAuth loading and running its lifecycle. A missing JAR, removal of the plugin or rejection before `onEnable` cannot be protected by the plugin itself. Check startup and login restrictions before admitting players. Fix startup errors before allowing automated restarts.

## Reproducing the isolated security tests

Run `mvn package`. Tests gated by the `authme.db` system property are skipped by default. Use temporary synthetic databases, never a production database path.

Integration sources are in `test-support`. Create a fresh local fixture, historically named `C:\Users\artyo\Documents\Codex\nordauth-test-...`, with a `server` subdirectory. Copy only the selected platform's executable, cache and an accepted EULA. Do not copy production worlds, accounts or operational configuration.

Bind `server.properties` to `127.0.0.1:25585`, use offline mode, disable RCON/query and create a separate small world. The harness writes synthetic accounts and local configuration. A queue of one waiting request makes overload tests deterministic.

Build `test-support/probe/pom.xml` separately and install its JAR only on the isolated server alongside NordAuth. `NordAuthTestProbe` has a console-only command for testing manual plugin disable. Never install it on production.

Client dependencies use the pinned NordLoadTest package and lock file with prepared 26.2 metadata. Set `NODE_PATH` to that installation's `node_modules`. Pass the fixture, Java executable, Python executable and platform:

```powershell
node ./test-support/integration.cjs $FixturePath $JavaExecutable $PythonExecutable Folia
# Use a fresh Paper fixture and Paper as the last argument for a separate run.
# Run the administrative checks separately on each isolated fixture:
node ./test-support/integration.cjs $FixturePath $JavaExecutable $PythonExecutable Folia admin-only
```

The harness starts hidden child processes, enforces a local fixture path, creates no external listener, stops its servers and writes a JSON result in the fixture. These are functional and security regressions, not proof of capacity for 1000 connections.

The probe grants password-reset permission to the fixture console; `natestgrant` grants it to an isolated bot. This does not change production permissions.
