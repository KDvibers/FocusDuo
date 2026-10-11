#!/usr/bin/env python3
"""Read-only SQL evidence for an owned expired-FOCUS controller (Linux/WSL).

Run with --control-dir <new controller directory> --mode empty|offline|recovered|cleanup.
Relative paths resolve from this repository, not the caller's working directory.
Exit 0 means every assertion passed, 1 means evidence disagreed, and 2 means the
probe refused or could not complete its read. No controller command is written.
"""
import argparse
import datetime as dt
import json
from pathlib import Path
import re
import subprocess
import sys
import uuid

DOCKER = ["docker", "--host", "unix:///var/run/docker.sock"]


class ProbeError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise ProbeError(message)


def command(argv, *, data=None):
    result = subprocess.run(argv, input=data, text=True, capture_output=True, timeout=12)
    require(result.returncode == 0, "Bounded Docker/psql read failed; no raw diagnostics emitted")
    return result.stdout


def read_json(path):
    require(path.is_file() and path.stat().st_size <= 1_048_576, "Expected a small controller JSON file")
    return json.loads(path.read_text(encoding="utf-8-sig"))


def controller_state(control):
    value = read_json(control / "state.json")
    # Never echo arbitrary metadata fields: a future controller could add secrets.
    state = {key: value.get(key) for key in ("container", "dbPort", "serverPid", "generation")}
    require(isinstance(state["container"], str) and
            re.fullmatch(r"focusduo-recovery-[0-9a-f]{12}", state["container"]) is not None,
            "Unexpected container name")
    require(type(state["dbPort"]) is int and state["dbPort"] == 55437, "Unexpected controller database port")
    require(type(state["generation"]) is int and state["generation"] >= 1, "Invalid controller generation")
    require(state["serverPid"] is None or (type(state["serverPid"]) is int and state["serverPid"] > 0),
            "Invalid controller Java PID")
    return state


def canonical_uuid(value):
    require(isinstance(value, str) and str(uuid.UUID(value)) == value, "Expected a canonical UUID")
    return value


def main():
    root = Path(__file__).resolve().parents[3]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--control-dir", required=True, help="Owned controller directory under this checkout's .local/")
    parser.add_argument("--mode", choices=("empty", "offline", "recovered", "cleanup"), required=True)
    parser.add_argument("--phase", help="Evidence JSON within the controller directory; default: its expired-focus-evidence.json")
    args = parser.parse_args()
    require(sys.platform.startswith("linux"), "Run this probe in Linux/WSL with the controller's local Docker daemon")
    control = (root / args.control_dir).resolve()
    require(control.is_relative_to(root / ".local") and control != root / ".local", "Invalid control directory")
    state = controller_state(control)
    marker = read_json(control / "marker.json")
    require(marker.get("kind") == "focusduo-isolated-restart-v1" and not marker.get("stopped"), "Controller is not active")
    container = state["container"]
    inspected = command(DOCKER + ["inspect", "--format",
                        "{{json .Config.Labels}}\n{{json .NetworkSettings.Ports}}\n{{json .Config.Image}}\n{{json .State.Running}}\n{{json .HostConfig.Tmpfs}}", container]).splitlines()
    require(len(inspected) == 5, "Unexpected safe container metadata")
    labels, ports, image, running, tmpfs = map(json.loads, inspected)
    require(labels.get("dev.focusduo.check") == "recovery-stage7", "Container ownership label mismatch")
    require(image == "postgres:17.11-bookworm", "Unexpected database image")
    require(running is True and "/var/lib/postgresql/data" in (tmpfs or {}), "Expected the running disposable tmpfs database")
    require(ports.get("5432/tcp") == [{"HostIp": "127.0.0.1", "HostPort": "55437"}], "Unexpected database binding")

    if args.mode == "empty":
        select = """SELECT jsonb_build_object(
          'database', current_database(), 'databaseNow', current_timestamp,
          'users', (SELECT count(*) FROM app_users),
          'rooms', (SELECT count(*) FROM rooms), 'rounds', (SELECT count(*) FROM rounds),
          'history', (SELECT count(*) FROM round_history),
          'migrations', (SELECT count(*) FROM flyway_schema_history WHERE success));"""
        scope = None
    else:
        phase_path = (root / args.phase).resolve() if args.phase else control / "expired-focus-evidence.json"
        phase_path = phase_path.resolve()
        require(phase_path.is_relative_to(control), "Phase JSON must belong to this controller directory")
        document = read_json(phase_path)
        require(document.get("kind") == "focusduo-expired-focus-v1", "Unexpected phase evidence kind")
        ready_events = [event for event in document.get("events", []) if event.get("phase") == "RUNNING_READY"]
        require(len(ready_events) == 1, "Exactly one RUNNING_READY event is required")
        phase = ready_events[0]
        owner, peer = phase.get("ownerUsername", ""), phase.get("peerUsername", "")
        require(all(isinstance(name, str) and re.fullmatch(r"expired[a-z0-9_]{0,25}", name) for name in (owner, peer)) and owner != peer,
                "Only two distinct expired-prefixed test users are allowed")
        room_id, round_id = canonical_uuid(phase["roomId"]), canonical_uuid(phase["roundId"])
        owner_id, peer_id = canonical_uuid(phase["ownerId"]), canonical_uuid(phase["peerId"])
        require(owner_id != peer_id, "Expected two distinct user UUIDs")
        deadline = dt.datetime.fromisoformat(phase["originalEndsAt"].replace("Z", "+00:00"))
        require(deadline.tzinfo is not None and deadline.utcoffset() == dt.timedelta(0), "Deadline must be UTC")
        deadline_sql = deadline.isoformat()
        scope = {"ownerUsername": owner, "peerUsername": peer, "ownerId": owner_id, "peerId": peer_id, "roomId": room_id,
                 "roundId": round_id, "originalEndsAt": deadline_sql}
        # All interpolated values have strict ASCII validation, UUID parsing or datetime serialization above.
        select = f"""WITH scoped_users AS (
          SELECT id, username FROM app_users WHERE (username='{owner}' AND id='{owner_id}'::uuid)
          OR (username='{peer}' AND id='{peer_id}'::uuid)
        ), scoped_room AS (
          SELECT r.* FROM rooms r JOIN app_users u ON u.id = r.owner_id
          WHERE r.id = '{room_id}'::uuid AND u.username = '{owner}'
          AND (SELECT count(*) FROM room_memberships m JOIN scoped_users u ON u.id=m.user_id WHERE m.room_id=r.id)=2
          AND (SELECT count(*) FROM room_memberships m WHERE m.room_id=r.id)=2
        ), scoped_round AS (
          SELECT r.* FROM rounds r JOIN scoped_room room ON room.id=r.room_id WHERE r.id='{round_id}'::uuid
        ), scoped_history AS (
          SELECT h.* FROM round_history h JOIN scoped_round r ON r.id=h.round_id
        ) SELECT jsonb_build_object(
          'database', current_database(), 'databaseNow', current_timestamp,
          'originalDeadlinePassed', current_timestamp > '{deadline_sql}'::timestamptz,
          'users', (SELECT coalesce(jsonb_agg(jsonb_build_object(
            'id', u.id, 'username', u.username,
            'sessions', (SELECT count(*) FROM auth_sessions s WHERE s.user_id=u.id),
            'unrevokedSessions', (SELECT count(*) FROM auth_sessions s WHERE s.user_id=u.id AND s.revoked_at IS NULL),
            'liveSessions', (SELECT count(*) FROM auth_sessions s WHERE s.user_id=u.id AND s.revoked_at IS NULL AND s.expires_at>current_timestamp),
            'activeMemberships', (SELECT count(*) FROM room_memberships m WHERE m.user_id=u.id AND m.active)
          ) ORDER BY u.username), '[]'::jsonb) FROM scoped_users u),
          'room', (SELECT jsonb_build_object('id', r.id, 'code', r.code, 'status', r.status, 'revision', r.revision,
            'ownerId', r.owner_id, 'currentRoundId', r.current_round_id,
            'activeMemberships', (SELECT count(*) FROM room_memberships m WHERE m.room_id=r.id AND m.active),
            'roundCount', (SELECT count(*) FROM rounds x WHERE x.room_id=r.id),
            'historyCount', (SELECT count(*) FROM round_history h WHERE h.room_id=r.id)) FROM scoped_room r),
          'round', (SELECT jsonb_build_object('id', r.id, 'roomId', r.room_id, 'kind', r.kind,
            'status', r.status, 'durationSeconds', r.duration_seconds, 'activeElapsedMs', r.active_elapsed_ms,
            'startedAt', r.started_at, 'endsAt', r.ends_at, 'endedAt', r.ended_at,
            'completionReason', r.completion_reason,
            'endsAtMatchesOriginal', r.ends_at='{deadline_sql}'::timestamptz,
            'endedAtMatchesOriginal', r.ended_at='{deadline_sql}'::timestamptz) FROM scoped_round r),
          'history', (SELECT coalesce(jsonb_agg(jsonb_build_object('roundId', h.round_id, 'roomId', h.room_id,
            'endedAt', h.ended_at, 'endedAtMatchesOriginal', h.ended_at='{deadline_sql}'::timestamptz,
            'snapshotIdsMatch', h.snapshot->>'roundId'=h.round_id::text AND h.snapshot->>'roomId'=h.room_id::text,
            'snapshotDeadlineMatchesOriginal', (h.snapshot->>'endedAt')::timestamptz='{deadline_sql}'::timestamptz,
            'kind', h.snapshot->>'kind', 'status', h.snapshot->>'status',
            'completionReason', h.snapshot->>'completionReason',
            'durationSeconds', (h.snapshot->>'durationSeconds')::int,
            'activeElapsedMs', (h.snapshot->>'activeElapsedMs')::bigint,
            'participantUserIds', (SELECT jsonb_agg(p->>'userId' ORDER BY p->>'userId') FROM jsonb_array_elements(h.snapshot->'participants') p),
            'linkedUserIds', (SELECT jsonb_agg(p.user_id ORDER BY p.user_id) FROM history_participants p WHERE p.round_id=h.round_id)
          )), '[]'::jsonb) FROM scoped_history h));"""

    sql = "BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY; SET LOCAL statement_timeout='5s'; SET LOCAL lock_timeout='2s'; " + select + " COMMIT;"
    raw = command(DOCKER + ["exec", "-i", container, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
                           "-h", "/var/run/postgresql", "-p", "5432", "-U", "focusduo_recovery", "-d", "focusduo_recovery"], data=sql)
    data = json.loads(raw)
    after = controller_state(control)
    require(after == state, "Controller changed during SQL read; retry at the stable gate")
    checks = {"isolatedDatabase": data.get("database") == "focusduo_recovery"}
    if args.mode == "empty":
        checks.update(empty=all(data.get(key) == 0 for key in ("users", "rooms", "rounds", "history")), migrations=data.get("migrations") == 2)
    else:
        users, room, round_data, history = data["users"], data["room"], data["round"], data["history"]
        checks.update(twoScopedUsers=len(users) == 2, ownedRoom=room is not None,
                      sameRound=round_data is not None and round_data["id"] == scope["roundId"])
        if room and round_data:
            checks.update(oneRound=room["roundCount"] == 1, sameCurrentRound=room["currentRoundId"] == scope["roundId"],
                          focus300=round_data["kind"] == "FOCUS" and round_data["durationSeconds"] == 300)
            if args.mode == "offline":
                checks.update(javaStopped=state["serverPid"] is None, deadlinePassed=data["originalDeadlinePassed"],
                              storedRunning=round_data["status"] == "RUNNING", noHistory=room["historyCount"] == 0 and history == [],
                              originalDeadlinePreserved=round_data["endsAtMatchesOriginal"] is True)
            else:
                checks.update(javaRunning=state["serverPid"] is not None,
                              completedElapsed=round_data["status"] == "COMPLETED" and round_data["completionReason"] == "ELAPSED",
                              active300000=round_data["activeElapsedMs"] == 300000,
                              endedAtOriginalDeadline=round_data["endedAtMatchesOriginal"] is True,
                              deadlineCleared=round_data["endsAt"] is None,
                              oneHistory=room["historyCount"] == 1 and len(history) == 1)
                if len(history) == 1:
                    expected_users = sorted((scope["ownerId"], scope["peerId"]))
                    entry = history[0]
                    checks.update(historyElapsed=entry["status"] == "COMPLETED" and entry["completionReason"] == "ELAPSED",
                                  historyFocus300=entry["kind"] == "FOCUS" and entry["durationSeconds"] == 300,
                                  historySnapshotIds=entry["snapshotIdsMatch"] is True,
                                  historyActive300000=entry["activeElapsedMs"] == 300000,
                                  historyDeadline=entry["endedAtMatchesOriginal"] is True and entry["snapshotDeadlineMatchesOriginal"] is True,
                                  bothHistoryParticipants=entry["participantUserIds"] == expected_users,
                                  bothHistoryLinks=entry["linkedUserIds"] == expected_users)
                if args.mode == "cleanup":
                    checks.update(roomClosed=room["status"] == "CLOSED", noActiveMemberships=room["activeMemberships"] == 0,
                                  noUnrevokedSessions=all(user["unrevokedSessions"] == 0 for user in users),
                                  noOtherActiveMemberships=all(user["activeMemberships"] == 0 for user in users))
    result = {"mode": args.mode, "controller": state, "scope": scope, "database": data,
              "checks": checks, "pass": all(checks.values())}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ProbeError as error:
        print("PROBE FAILED: " + str(error), file=sys.stderr)
        sys.exit(2)
    except Exception as error:
        print("PROBE FAILED: " + type(error).__name__ + " (raw details suppressed)", file=sys.stderr)
        sys.exit(2)
