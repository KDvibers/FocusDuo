# Live Kotlin safe retry (opt-in)

Run against an already running disposable Java backend with JDK 21. The harness creates two uniquely named accounts and one room and uses the production `RealRepository`, real CIO HTTP, and real WebSocket subscriptions. It neither starts services nor changes desktop sources, `.env`, or database configuration. No new production dependency is added.

From the repository root on Windows:

```powershell
# backend/target must exist. Use its full path without an 8.3 short name (for example THUNDE~1).
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$((Resolve-Path backend/target).Path.Replace('\', '/'))"
./desktop/gradlew.bat -p desktop -I (Resolve-Path backend/scripts/kotlin-recovery-smoke/init.gradle).Path realRecoverySmoke "-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090" --console=plain
```

Linux/macOS, from the repository root:

```sh
bash ./desktop/gradlew -p desktop -I "$PWD/backend/scripts/kotlin-recovery-smoke/init.gradle" realRecoverySmoke -Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090 --console=plain
```

Only loopback addresses are accepted. The external Gradle init script reuses existing desktop compiler/dependencies and writes harness classes into `backend/target/kotlin-recovery-smoke`. Each state wait or injected send hold is bounded to 10 seconds; the scenario is bounded to 120 seconds, with separate short cleanup bounds. Command metadata and bearer tokens stay in RAM. Auth request bodies are not recorded, and output never includes bodies, keys, tokens, passwords or exception messages.

The CIO engine delegate has two fault controls. Before sending a chosen command it can hold the prepared request while the partner commits a real change. After a chosen successful command it can read the complete real HTTP body, verify status `200`, and throw an `IOException` before the repository receives that response. It never fabricates, rewrites or replaces a server DTO, status or snapshot. All ordinary responses pass through unchanged.

Checks:

1. Lose the successful create response. Recover the actual membership through the repository, block another create while pending, and manually replay the same method/path/body/key with no revision header. The same room remains at revision 1.
2. Lose a successful task response. Pending state blocks a different command without sending it. After the partner changes the room, manual retry sends the exact original method/path/JSON/key/revision. The database-backed snapshot still contains one task, its revision has not advanced for replay, and the newer partner edit remains intact.
3. Hold a prepared goal update until the partner commits a change. Java must return a real `409 REVISION_CONFLICT` with the authoritative snapshot. Pending state clears; the next explicit action uses a new key and current revision and commits once.

Cleanup disarms fault controls, attempts to close only the room owned by this run, and logs out locally active sessions. It can recover membership even if a create/join response was lost. Cleanup is best effort: an unavailable server or unconfirmed logout can leave a server session active until expiry, and locally `SIGNED_OUT` sessions are not retried with raw logout. V1 has no account-deletion endpoint, so account rows remain. Use a disposable database.

The live scenario passed on Windows with JDK 21: four checkpoints and the final result passed, with Gradle exit code 0. Linux/macOS has not been run.

This covers safe replay and conflicts. Server restart, automatic backoff, UI, timer and history scenarios are covered separately.
