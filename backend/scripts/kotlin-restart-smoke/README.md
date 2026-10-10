# Live Kotlin recovery across Java restarts (opt-in)

This harness uses two production `RealRepository` instances and CIO against a disposable Java backend and PostgreSQL database. A separate local controller owns that Java process and must already be running. The harness never locates or kills processes, starts Docker, changes `.env`, or edits desktop files. It creates two accounts, one room and one FOCUS history entry; use a disposable database. To match the provided controller, the client accepts only HTTP on port 8090 at `127.0.0.1` or `localhost`, without a URL path, credentials, query or fragment. Validation happens before account creation.

Start the controller from the repository root in Linux/WSL with Docker access, Python 3.9+, Java 21 and the built backend JAR:

```sh
python3 backend/scripts/kotlin-restart-smoke/serve.py --control-dir .local/recovery-stage7-final
```

Wait for `READY` and keep this terminal active. The controller creates its own tmpfs PostgreSQL on port 55437 and its own Java process on 8090. Both ports must be free. The control directory must be new for each controller run; select another name and update both commands when repeating. The controller does not use the project `.env` or existing database.

From the repository root on Windows with JDK 21, after starting the isolated controller:

```powershell
# backend/target must exist; use a full path without an 8.3 short name.
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$((Resolve-Path backend/target).Path.Replace('\', '/'))"
./desktop/gradlew.bat -p desktop -I (Resolve-Path backend/scripts/kotlin-restart-smoke/init.gradle).Path realRestartSmoke "-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090" "-Dfocusduo.smoke.controlDir=$((Resolve-Path .local/recovery-stage7-final).Path)" --console=plain --no-daemon
```

The init script reuses the desktop compiler and dependencies; output is under `backend/target/kotlin-restart-smoke`. It does not add production dependencies or change the desktop build. This is an executable acceptance scenario, not a JUnit test. `focusduo.smoke.controlDir` is required and must be an absolute path to the active controller directory; an empty value prints a usage hint and stops before account creation. The scenario cannot run against an ordinary development server without this controller.

Verified on native Windows / Temurin 21.0.12.101 against the controller's WSL Java process and isolated PostgreSQL 17.11 on port 55437: **BUILD SUCCESSFUL in 43 seconds, exit 0**, with all five checkpoints below passing. Both server stops used the controller's forced termination; the clients recovered through the unchanged production backoff and jitter. Cleanup closed the own room and revoked its sessions. The controller/database remain under the runtime owner's control for independent database checks and teardown; this result does not claim that the external environment was removed.

After inspecting the result, stop the controller from PowerShell by creating a file in the same control directory:

```powershell
New-Item -ItemType File -Path .local/recovery-stage7-final/shutdown | Out-Null
```

Wait for `STOPPED` in the controller terminal. This removes only its own temporary database and Java process. The recorded run's owner separately confirmed database counts, teardown and the existing database's health; see [RECOVERY_INTEGRATION.md](../../../docs/backend/RECOVERY_INTEGRATION.md). A targeted controller run also verified isolation from conflicting Spring/JVM parent environment variables and idempotent `start` readiness with the existing process.

## Controller protocol

Use a dedicated directory such as ignored `.local/recovery-stage7-final/`. The controller must keep the same isolated PostgreSQL database across Java restarts and operate only on its own Java process. Create `marker.json` with `{"kind":"focusduo-isolated-restart-v1"}` only after the initial backend is ready. A marker containing `"stopped":true` is rejected. This marker is an explicit controller precondition, not process discovery or a security boundary.

The harness atomically replaces `commands.json` with `{"id":"<fresh UUID>","action":"stop"}` or `action:"start"`. The controller atomically replaces `result.json` with the same `id` and `status:"ok"` or `status:"error"`. Ignore stale result IDs; process each command ID once. Files contain no credentials. Neither side should replace a JSON file with a partial write. The harness requires atomic rename support and polls the external result at 100 ms intervals with a 60-second timeout.

- `stop`: forcibly terminate only the controller's Java process and wait for it to exit before acknowledging; preserve PostgreSQL and its data.
- `start`: start that Java process if stopped, using the same database and configuration. An already running healthy process makes this operation a no-op. Acknowledge only after unauthenticated `GET /api/v1/me` returns 401. Startup failure returns `error`.

After an uncertain `stop` outcome, harness cleanup may issue `start` again. The controller must handle this idempotently. Keep it running until the harness has finished cleanup. The controller and disposable database are removed by their owner after inspecting the results; the harness does not remove them.

## Checks and limits

1. Two users create/join a room, add and complete a task, start a 900-second FOCUS, and pause it. The longer allowed duration only provides startup headroom; the scenario does not wait for the round to elapse.
2. The controller stops Java. Both repositories must lose CONNECTED and block mutations. After startup, both must increase `recoveryVersion` and receive fresh CONNECTED snapshots through automatic recovery, without calling `reconnect()` or `bootstrap()` again. PAUSED identity, membership, revision, task, `startedAt`, frozen remaining/active time and null deadline must match.
3. Resume, then stop/start Java again. Both clients must automatically recover the same RUNNING round with the original `startedAt` and `endsAt`. Remaining/active time may advance; the deadline must still be safely in the future.
4. Explicit `recoverAfterResume()` on both repositories must cause a newly observed CONNECTING/SYNCHRONIZING cycle and advance recovery versions. In-memory CIO counters verify that this synchronization sends no modifying REST command. This invokes the application signal; it does not suspend Windows or test AWT focus listeners.
5. Manual finish produces exactly one equal history item for both users, preserving the completed task. Close the room and log out both users.

The production reconnect backoff and jitter are unchanged. StateFlow waits are bounded by 60 seconds; file-controller polling is separate. The main scenario has a ten-minute upper bound and cleanup a 90-second bound. Cleanup asks the controller to start Java when its state is uncertain, attempts to close only the harness's own room through bounded HTTP calls, and revokes observed session tokens. Cleanup is best effort when the controller/server is unavailable; inspect database state before removing the disposable environment.

Tokens are observed only in memory for cleanup, with production CIO still making every request. Output contains checkpoint names and safe exception class names, never passwords, tokens, response bodies or stack traces. This scenario does not cover an overdue ELAPSED round, actual OS sleep, network adapter changes, Compose UI, TTL expiry or LAN.
