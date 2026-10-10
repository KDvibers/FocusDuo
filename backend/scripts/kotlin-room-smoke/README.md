# Live Kotlin room lifecycle (opt-in)

Run against a disposable, already running local backend with JDK 21. The harness uses the production `RealRepository`, REST over CIO, and its production CIO WebSocket transport. It creates two uniquely named accounts and one room. V1 cannot delete accounts, so their rows remain. Its `finally` attempts to close only its own room and revoke sessions that are still locally active, including recovery through `/rooms/current` when a room command's response was lost.

Cleanup is best effort. An unavailable server or an unconfirmed logout can leave a server session active until expiry. `RealRepository.logout()` clears its local session even on a network failure; cleanup does not retry raw logout for a repository already marked `SIGNED_OUT`. Use a disposable database, and do not treat `finally` as a guarantee of server-side cleanup.

From the repository root on Windows:

```powershell
# backend/target must exist. Use its full path without an 8.3 short name (for example THUNDE~1).
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$((Resolve-Path backend/target).Path.Replace('\', '/'))"
./desktop/gradlew.bat -p desktop -I (Resolve-Path backend/scripts/kotlin-room-smoke/init.gradle).Path realRoomLifecycleSmoke "-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090" --console=plain
```

Linux/macOS, from the repository root:

```sh
bash ./desktop/gradlew -p desktop -I "$PWD/backend/scripts/kotlin-room-smoke/init.gradle" realRoomLifecycleSmoke -Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090 --console=plain
```

The script accepts loopback addresses only. It does not start services, edit `.env` or desktop files, launch UI, or add production dependencies. The external init script reuses the desktop compiler and dependencies and writes harness classes to `backend/target/kotlin-room-smoke`. Every state wait has a 10-second limit, HTTP setup checks have 10-second timeouts, the scenario has a 120-second limit, and cleanup requests have separate short bounds. Tokens stay in memory; output includes check names and safe exception class names, never credentials, response bodies or stack traces.

The scenario supplements `backendRoomSmoke`:

1. Observe a new `CONNECTING`/`SYNCHRONIZING` cycle during manual reconnect, wait for a full snapshot, then receive the partner's next change.
2. Log out, edit the room through the partner, and log in again. `bootstrap()` must restore the same membership and missed edit without sending `/rooms/join` or changing the room revision.
3. Revoke the restored session through real HTTP. Without a repository REST refresh, the active WebSocket must expire the repository and clear its room/history and ability to mutate. The token must then receive HTTP `401`; the partner stays connected and successfully requests `/me`.
4. Log in again, receive the partner's final `CLOSED` snapshot, and keep that snapshot after refresh and reconnect. The server's current room is `null`, the connection remains `NONE`, and mutations are rejected.

There is no instantaneous assertion that mutation is disabled immediately after `reconnect()`: a loopback handshake may already finish before that assertion runs. A bounded `StateFlow` watcher subscribes first and uses an unconfined coroutine to observe the new synchronization transition before awaiting readiness. This rejects a reconnect implementation that does nothing. The scenario does not inject network loss or hold the first snapshot. Timers, history contents, UI and automatic recovery are outside this scenario.

The live scenario passed on Windows with JDK 21. The Linux/macOS command has not been run.
