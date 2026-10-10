# Live Kotlin auth integration (opt-in)

Run against a disposable, already running local backend. The script does not start services or change `.env`, the database configuration, or desktop sources. It creates two uniquely named accounts; V1 has no account-deletion endpoint, so their rows remain. It closes its own room and revokes its sessions in `finally`.

From the repository root, with JDK 21 configured:

```powershell
# backend/target must exist. Use its full path without an 8.3 short name (for example THUNDE~1).
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$((Resolve-Path backend/target).Path.Replace('\', '/'))"
./desktop/gradlew.bat -p desktop -I (Resolve-Path backend/scripts/kotlin-auth-smoke/init.gradle).Path realRepositorySmoke "-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090" --console=plain
```

Linux/macOS, from the repository root:

```sh
./desktop/gradlew -p desktop -I "$PWD/backend/scripts/kotlin-auth-smoke/init.gradle" realRepositorySmoke -Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090 --console=plain
```

The live run was verified on Windows with JDK 21. The Linux/macOS command has not been run.

The external Gradle init script reuses the desktop Kotlin compiler and existing main dependencies. The harness compiles into `backend/target/kotlin-auth-smoke`; it adds no production dependency and does not run Compose UI. Loopback addresses only are accepted. Java HTTP setup requests have 10-second timeouts, the Kotlin scenario has a 120-second limit, and cleanup requests are bounded separately.

Checks use the production `RealRepository` over a real CIO engine: normalized registration, wrong password, duplicate username, two-member current/known room with a goal and task, actual server `401` after external revocation, local `EXPIRED` state, login restoring membership, and logout yielding `SIGNED_OUT` plus rejection of the revoked token. The peer remains authorized and the room remains open until explicit cleanup. An engine delegate observes the bearer only in memory to revoke exactly the repository's session; it does not simulate responses or modify transport. Output contains only check names and safe exception class names, never response bodies, passwords or tokens.

This supplements `backendAuthSmoke`; it does not cover WebSocket, timer, room commands implemented by the Kotlin client, or UI.
