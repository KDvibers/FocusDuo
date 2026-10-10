# Live Kotlin auth integration (opt-in)

Run against a disposable, already running local backend. The script does not start services or change `.env`, the database configuration, or desktop sources. It creates two uniquely named accounts; V1 has no account-deletion endpoint, so their rows remain. In `finally`, it attempts to close its own room and revoke sessions that are still locally active.

Cleanup is best effort. An unavailable server or an unconfirmed logout can leave a server session active until expiry. `RealRepository.logout()` clears its local session even on a network failure; cleanup does not retry raw logout for a repository already marked `SIGNED_OUT`. Use a disposable database, and do not treat `finally` as a guarantee of server-side cleanup.

From the repository root, with JDK 21 configured:

```powershell
# backend/target must exist. Use its full path without an 8.3 short name (for example THUNDE~1).
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$((Resolve-Path backend/target).Path.Replace('\', '/'))"
./desktop/gradlew.bat -p desktop -I (Resolve-Path backend/scripts/kotlin-auth-smoke/init.gradle).Path realRepositorySmoke "-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090" --console=plain
```

Linux/macOS, from the repository root:

```sh
bash ./desktop/gradlew -p desktop -I "$PWD/backend/scripts/kotlin-auth-smoke/init.gradle" realRepositorySmoke -Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090 --console=plain
```

The live run was verified on Windows with JDK 21. The Linux/macOS command has not been run.

The external Gradle init script reuses the desktop Kotlin compiler and existing main dependencies. The harness compiles into `backend/target/kotlin-auth-smoke`; it adds no production dependency and does not run Compose UI. Loopback addresses only are accepted. Java HTTP setup requests have 10-second timeouts, the Kotlin scenario has a 120-second limit, and cleanup requests are bounded separately.

Checks use the production `RealRepository` over a real CIO engine: normalized registration, wrong password, duplicate username, two-member current/known room with a goal and task, actual server `401` after external revocation, local `EXPIRED` state, login restoring membership, and logout yielding `SIGNED_OUT` plus rejection of the revoked token. The peer remains authorized and the room remains open until explicit cleanup. An engine delegate observes the bearer only in memory to revoke exactly the repository's session; it does not simulate responses or modify transport. Output contains only check names and safe exception class names, never response bodies, passwords or tokens.

With desktop stage 6, every repository in this harness explicitly uses `realtimeEnabled = false`. This keeps the auth check focused on REST: after external logout, the next authenticated HTTP request must receive `401`; a WebSocket `4401` cannot clear the session first. Room setup and closure still use separate real HTTP requests. This harness does not exercise repository mutation gating or WebSocket readiness.

Desktop stage 6 also retains a known final `CLOSED` snapshot for display. Server membership and repository display state now have separate assertions: the actual `GET /api/v1/rooms/current` response must be `{"room":null}` after closure, while `RealRepository.refreshCurrentRoom()` reads the known room by ID and keeps its final `CLOSED` snapshot. Retaining that snapshot does not restore membership or reopen the room. A fresh authenticated repository that has never loaded the room still receives no current room. The previous stage 5 assertion that `refreshCurrentRoom()` itself returns `null` after closing a known room is no longer valid.

This supplements `backendAuthSmoke`; it does not cover WebSocket, timer, room commands implemented by the Kotlin client, or UI.
