# Real FOCUS expiry while Java is stopped (opt-in)

Two production Kotlin `RealRepository` instances with CIO create a **300-second FOCUS** against the real Java backend. The existing [isolated controller](../kotlin-restart-smoke/README.md) stops only its own Java process, keeps PostgreSQL running, and starts Java again **after the original deadline plus two real seconds**. No OS clock, database timestamp, timer implementation, production reconnect delay or response is replaced. Expect roughly six minutes; this scenario is intentionally longer than the other smoke checks.

The headless entry is a repository acceptance scenario rather than a Compose UI or JUnit test. It adds no production dependency and changes no tracked `desktop/**` file. Compile outputs go to `backend/target/kotlin-expired-focus-smoke/`.

The optional component GUI adapter below runs the same core assertions and opens two unchanged production `RealApp` windows in **one JVM**. Its API setup bypasses the authentication screen and `Main`/`RealWorkspace`; it does not represent the full normal startup flow. Choose either the ordinary CLI or this adapter for a given fresh controller/evidence directory.

**Verified: one live adapter/core run completed with PASS and exit 0 at `2026-10-11T20:40:14.720Z` (12 October, Asia/Qyzylorda).** Both windows were observed at offline `00:00` waiting for the server, then automatically opened the 5-minute ELAPSED result after Java restarted. Core assertions confirmed the original deadline, exactly one immutable history result per user and successful own-resource cleanup. The independent SQL probes passed in `offline`, `recovered` and `cleanup` modes. The headless CLI was compiled but was **not** executed as a separate five-minute live run. The [acceptance report](../../../docs/backend/EXPIRED_FOCUS_ACCEPTANCE.md) records the precise scope, [seven screenshots](../../../docs/backend/screenshots/expired-focus-2026-10-12/) and [phase/SQL evidence](../../../docs/backend/evidence/expired-focus-2026-10-12/). Runtime teardown is reported separately by its owner; core cleanup alone does not claim that the controller/container was removed.

## Prepare and compile

Use Java 21 and the existing desktop Gradle wrapper. From the repository root in PowerShell:

```powershell
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
# backend/target must exist; use a full path without an 8.3 short name.
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$((Resolve-Path backend/target).Path.Replace('\', '/'))"
$focusExpiredInit = (Resolve-Path backend/scripts/kotlin-expired-focus-smoke/init.gradle).Path
.\desktop\gradlew.bat -p desktop -I $focusExpiredInit compileRealExpiredFocusSmoke --console=plain --no-daemon
if ($LASTEXITCODE -ne 0) { throw 'Expired FOCUS harness compilation failed' }
```

Adjust the JDK and repository paths for your machine. The compile task does not contact or control a server.

## Start the isolated runtime and run

The runtime owner first builds the backend JAR, ensures ports 8090 and 55437 are free, and starts the existing controller in Linux/WSL from the repository root:

```sh
python3 backend/scripts/kotlin-restart-smoke/serve.py --control-dir .local/expired-focus-check-1
```

Wait for `READY` and keep that terminal open. The controller owns a disposable tmpfs PostgreSQL database on 55437 and Java on 8090; it does not read or modify `.env` or the regular database. Choose a new control directory for each run. The harness creates its own two accounts, room and history; V1 has no account deletion. Do not point it at a development or user database.

In the prepared PowerShell terminal:

```powershell
.\desktop\gradlew.bat -p desktop -I $focusExpiredInit realExpiredFocusSmoke `
  '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' `
  "-Dfocusduo.smoke.controlDir=$((Resolve-Path .local/expired-focus-check-1).Path)" `
  --console=plain --no-daemon
if ($LASTEXITCODE -ne 0) { throw 'Expired FOCUS acceptance failed; inspect safe phase evidence' }
```

Only HTTP origins `http://127.0.0.1:8090` and `http://localhost:8090` are accepted, without credentials, path, query or fragment. The absolute control directory must contain the active `focusduo-isolated-restart-v1` marker. All validation occurs before account creation. `expired-focus-evidence.json` must not already exist; existing evidence is never replaced by a new run.

**The runtime owner coordinates the entire run.** Once invoked, this harness writes UUID `stop`/`start` commands using the existing controller protocol; it is the only command writer until cleanup finishes. Do not run the restart harness or manually replace `commands.json` concurrently. If separate GUI clients are being observed, prepare their own room before invoking this command and record their evidence separately. This harness never changes that room, but stopping the shared Java process affects all clients connected to this isolated runtime. Do not suspend the PC during this scenario.

## Assertions and evidence

1. Two users join one room, set distinct goals, add one task each and complete the owner's task. Both WS connections must be ready. The history starts empty. The harness retains the FOCUS `roundId`, `startedAt`, original `endsAt`, revision and full participant snapshot.
2. Java is acknowledged stopped **before** the original deadline. Both clients lose CONNECTED and disable mutations. Until `endsAt + 2 seconds`, observers check emitted repository states for a still-RUNNING round and empty local history; the production timer projection is sampled each second. At the final offline observation both projected countdowns must be zero, but neither client may invent a terminal status, completion reason, end time or history item.
3. Java starts with the same PostgreSQL. Both clients must automatically increase `recoveryVersion` and regain ready WS connections using normal production backoff/jitter. The harness calls no refresh, reconnect, bootstrap or wake recovery after setup. The restored snapshots must retain the room, participants, goals, tasks, round identity and start time, with exactly one new revision: `COMPLETED/ELAPSED`, `endsAt=null`, `endedAt=original endsAt`, `remainingMs=0`, `activeElapsedMs=300000`. In-memory counters prove recovery sent no modifying REST commands.
4. Each user's real history list contains exactly one result, and both detail endpoints return the same full expected snapshot with task counts. A later partner task edit must not change that result. Closing the room must not rewrite or duplicate the already completed history.
5. The harness closes only its own room and logs out its own users. Final cleanup also attempts raw revocation of its observed tokens, including after a failed logout.

The control directory receives **`expired-focus-evidence.json`** with UTC phase timestamps, synthetic room/user/round IDs, deadline/revision and named assertion results. The harness refuses an existing file on startup, then atomically updates only its own evidence file. `RUNNING_READY`, `JAVA_STOPPED`, `CLIENTS_OFFLINE`, periodic `WAITING_WITH_JAVA_OFF`, `OFFLINE_DEADLINE_PASSED`, `JAVA_START_REQUESTED`, `JAVA_READY`, `AUTO_RECOVERY_COMPLETED`, history and cleanup phases also appear on stdout. These phases let the runtime owner align separate GUI observations or read-only SQL checks; a recorded phase is not a claim that all later assertions passed. Success requires process exit 0 and the final `PASS FocusDuo RealRepository live expired FOCUS integration` line.

No password, bearer token, Authorization header, HTTP response body or exception message/stack is written to evidence or stdout. Tokens stay in memory only for cleanup. State waits are bounded (90 seconds for ready, 60 for disconnect/controller); the main scenario is bounded by 18 minutes, cleanup by 90 seconds and the Gradle execution task by 20 minutes. The longer outer bound also accommodates optional bounded GUI observation gates. Cleanup runs even when the calling coroutine is cancelled; it can ask the controller to start Java if the stop/start result is unknown, then attempts to close only the room owned by these users using its current revision. It checks the owner ID and the known room ID, and can recover the own room after a lost create response. HTTP cleanup calls each have a three-second timeout. Cleanup is best effort if the controller/server is unavailable; inspect the disposable database before removing it.

The public `runExpiredFocusSmoke(ExpiredFocusHooks(...))` entry permits a separate test adapter to observe the same two production repositories. `onReady(owner, peer)` runs after room/goals/tasks setup, **after starting FOCUS and receiving both ready WS snapshots**, but before stopping Java; `onExpiredOffline()` runs after the real deadline/zero-display assertions and **before** restarting Java; `onRecovered()` runs after authoritative ELAPSED and history assertions and **before** later edits/cleanup. The ordinary CLI uses no-op hooks. An adapter must bound its observation gates and must not write controller commands or change the repositories' room during observation. The first gate consumes real FOCUS time; at least 60 seconds must still remain when it returns. Such an adapter proves its specific UI entry, not the unchanged full authentication/startup flow; record that scope separately. One successful adapter run executes all core assertions, so a second five-minute headless run is not necessary solely to claim the same core result.

## Optional two-window component observation

Core plus GUI adapter compilation was checked on native Windows / Temurin 21.0.12.101: **BUILD SUCCESSFUL, exit 0**; the subsequent single live run is documented in the [acceptance report](../../../docs/backend/EXPIRED_FOCUS_ACCEPTANCE.md). Compilation itself opened no windows and contacted no server. Use the prepared JDK environment above, and compile from the repository root:

```powershell
.\desktop\gradlew.bat -p desktop -I (Resolve-Path backend/scripts/kotlin-expired-focus-smoke/ui.gradle).Path `
  compileExpiredFocusUi --console=plain --no-daemon
if ($LASTEXITCODE -ne 0) { throw 'Expired FOCUS component adapter compilation failed' }
```

The init script copies the production desktop JAR and builds the adapter into a unique directory under `backend/target/kotlin-expired-focus-smoke/ui/`. The generated runtime classpath uses that copy rather than mutable `desktop/build/classes`. No tracked desktop source or build configuration is edited. After the isolated controller is ready, launch exactly one adapter process:

```powershell
$focusExpiredControl = (Resolve-Path .local/expired-focus-check-1).Path
$focusExpiredCp = (Get-Content -Raw backend/target/kotlin-expired-focus-smoke/ui/latest-classpath.txt).Trim()
if ([string]::IsNullOrWhiteSpace($focusExpiredCp)) { throw 'Compile the component adapter first' }
& "$env:JAVA_HOME\bin\java.exe" `
  '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' `
  "-Dfocusduo.smoke.controlDir=$focusExpiredControl" `
  -cp $focusExpiredCp dev.focusduo.smoke.expiredfocus.ExpiredFocusUiKt
if ($LASTEXITCODE -ne 0) { throw 'Expired FOCUS component acceptance failed' }
```

Keep that terminal open; use another PowerShell terminal for inspection gates. Both windows initially show setup text, then each displays the unchanged `RealApp` and theme using one of the core's two repositories. Open **Комната** through the normal UI in each window. The core waits at three gates, each for at most **120 seconds**:

| `ui-phase.json` → `waitingFor` | Inspect before releasing the gate |
| --- | --- |
| `ui-ready` | Both windows show the same RUNNING FOCUS, goals/tasks and ready connection. The original 300-second deadline is already running. |
| `ui-expired-inspected` | Java is still stopped and the real deadline has passed. Both show zero remaining with waiting/disconnected state, disabled commands and no fabricated result. |
| `ui-recovered-inspected` | Java has restarted and both repositories passed ELAPSED/history assertions. Observe automatic result navigation, 5 minutes of active time, original goals/tasks and matching history. |

Read the phase from the control directory; **create only the currently requested file, after the actual inspection**, for example:

```powershell
Get-Content -Raw .local/expired-focus-check-1/ui-phase.json
New-Item -ItemType File -Path .local/expired-focus-check-1/ui-ready | Out-Null
# Later, only when waitingFor equals that gate and inspection is complete:
New-Item -ItemType File -Path .local/expired-focus-check-1/ui-expired-inspected | Out-Null
# Later, after observing the recovered result:
New-Item -ItemType File -Path .local/expired-focus-check-1/ui-recovered-inspected | Out-Null
```

All three gate files must be absent at adapter startup. Do not precreate them or issue controller stop/start commands yourself. Avoid refresh/reconnect or changing the room during observation; those actions would invalidate the intended recovery evidence. After the last gate, core assertions check immutable history and close/logout its users; both windows close automatically after cleanup. Closing either window early cancels the core and still allows bounded cleanup. UI errors log only safe exception class names. Take and document screenshots separately; the adapter does not assert rendered pixels or automatically count a released gate as a UI PASS. This path does not test AuthScreen, preferences on disk, separate JVMs or the `RealWorkspace` OS-wake wiring.

## Independent read-only database probe

[probe.py](probe.py) reads only the active controller's disposable PostgreSQL through the explicit local Docker socket. It checks the container name/label, PostgreSQL image, tmpfs storage and loopback port 55437 before opening a bounded, read-only SQL transaction. Scoped modes use the canonical room/round/user IDs and generated usernames from `RUNNING_READY`; no session token, password or full row dump is returned. The probe writes no controller command, gate or database change.

With Linux/WSL Python 3.9+ and access to the controller's local Docker daemon, run from PowerShell as follows, replacing the checkout/control-directory paths for your run:

```powershell
wsl.exe -d Ubuntu -u root -- python3 /mnt/c/Daniyar/MyProjects/FocusDuo/backend/scripts/kotlin-expired-focus-smoke/probe.py `
  --control-dir .local/expired-focus-check-1 --mode empty
if ($LASTEXITCODE -ne 0) { throw 'Empty runtime probe failed' }
```

Select the mode at the corresponding stable phase; these are sequential observations, not commands to run together:

| Mode | When to run it | Required observations |
| --- | --- | --- |
| `empty` | After controller READY, before starting the core/adapter | No users, rooms, rounds or history; two migrations. |
| `offline` | At `ui-expired-inspected`, **before releasing** that gate | Controller Java PID absent, stored RUNNING with original deadline already passed, zero history. |
| `recovered` | At `ui-recovered-inspected`, **before releasing** that gate | Same FOCUS, COMPLETED/ELAPSED, original deadline as endedAt, 300000 ms, one history with both participants. |
| `cleanup` | After core cleanup, before controller shutdown | Same terminal round/history, CLOSED room, zero active memberships and unrevoked own sessions. |

For later observations repeat the same command with `--mode offline`, `--mode recovered` or `--mode cleanup`. The default evidence file is `expired-focus-evidence.json` inside the control directory. An optional `--phase` accepts a repository-relative or absolute evidence path only inside that same directory. Output is safe JSON on stdout: exit **0** means all checks passed, **1** means an assertion disagreed, and **2** means the probe refused or could not finish its read. The probe may fail if the controller changes state during the query; repeat only while the appropriate gate is still waiting. Preserve actual output and exit status separately as evidence; the presence of this tool does not claim a successful SQL check.

After independent checks, the runtime owner stops the controller:

```powershell
New-Item -ItemType File -Path .local/expired-focus-check-1/shutdown | Out-Null
```

Wait for `STOPPED`; only the controller's Java process and temporary PostgreSQL are removed. The harness itself leaves the runtime alive for verification. Headless/core assertions establish the authoritative final deadline after automatic repository recovery. Claims about rendered UI require separate inspection evidence from the optional adapter; released gates alone are not a UI PASS, and the normal authentication/startup flow remains excluded. Neither entry establishes physical sleep, LAN, session TTL, manual retry/idempotency or the exact time the backend background scheduler processes expiry.
