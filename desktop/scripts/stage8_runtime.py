#!/usr/bin/env python3
"""Isolated native-Windows Java/PostgreSQL runtime for manual Compose acceptance.

Requires Python 3.9+, JDK 21, PostgreSQL 17 binaries and the packaged backend JAR.
Never reads .env or connects to an existing database. Stop with Ctrl+C or create
the run directory's shutdown file. Runtime data and logs stay under desktop/build.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid


INSPECT_SQL = """
SELECT jsonb_build_object(
  'serverVersion', current_setting('server_version'),
  'counts', jsonb_build_object(
    'users', (SELECT count(*) FROM app_users),
    'sessions', (SELECT count(*) FROM auth_sessions),
    'unrevokedSessions', (SELECT count(*) FROM auth_sessions WHERE revoked_at IS NULL),
    'rooms', (SELECT count(*) FROM rooms),
    'openRooms', (SELECT count(*) FROM rooms WHERE status = 'OPEN'),
    'activeMemberships', (SELECT count(*) FROM room_memberships WHERE active),
    'tasks', (SELECT count(*) FROM room_tasks),
    'rounds', (SELECT count(*) FROM rounds),
    'history', (SELECT count(*) FROM round_history),
    'historyParticipants', (SELECT count(*) FROM history_participants),
    'idempotencyResults', (SELECT count(*) FROM idempotency_results),
    'migrations', (SELECT count(*) FROM flyway_schema_history WHERE success)),
  'users', (SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id', id, 'username', username, 'displayName', display_name) ORDER BY username), '[]'::jsonb) FROM app_users),
  'sessions', (SELECT coalesce(jsonb_agg(jsonb_build_object(
    'userId', user_id, 'revoked', revoked_at IS NOT NULL, 'expired', expires_at <= current_timestamp)
    ORDER BY created_at), '[]'::jsonb) FROM auth_sessions),
  'rooms', (SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id', id, 'code', code, 'status', status, 'revision', revision,
    'focusDurationSeconds', focus_duration_seconds, 'breakDurationSeconds', break_duration_seconds)
    ORDER BY created_at), '[]'::jsonb) FROM rooms),
  'members', (SELECT coalesce(jsonb_agg(jsonb_build_object(
    'userId', user_id, 'slot', slot, 'active', active, 'displayName', display_name, 'goal', goal)
    ORDER BY slot), '[]'::jsonb) FROM room_memberships),
  'tasks', (SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id', id, 'userId', user_id, 'title', title, 'completed', completed)
    ORDER BY created_at, id), '[]'::jsonb) FROM room_tasks),
  'rounds', (SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id', id, 'kind', kind, 'status', status, 'durationSeconds', duration_seconds,
    'activeElapsedMs', active_elapsed_ms, 'completionReason', completion_reason)
    ORDER BY started_at), '[]'::jsonb) FROM rounds),
  'history', (SELECT coalesce(jsonb_agg(snapshot ORDER BY ended_at), '[]'::jsonb) FROM round_history)
);
"""


def write_json(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(path)


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--pg-bin', default=r'C:\Program Files\PostgreSQL\17\bin')
    parser.add_argument('--server-port', type=int, default=18091)
    parser.add_argument('--db-port', type=int, default=55438)
    parser.add_argument('--run-dir', default='desktop/build/stage8-runtime-' + time.strftime('%Y%m%d-%H%M%S'))
    args = parser.parse_args()
    if os.name != 'nt':
        parser.error('This acceptance controller is for native Windows')
    if args.server_port == args.db_port or not all(1024 <= port <= 65535 for port in (args.server_port, args.db_port)):
        parser.error('Use two different unprivileged ports')
    run = (root / args.run_dir).resolve()
    build_root = (root / 'desktop/build').resolve()
    if not run.is_relative_to(build_root) or run == build_root:
        parser.error('run-dir must be a new directory within desktop/build/')
    java = Path(args.java_home).resolve() / 'bin/java.exe'
    pg_bin = Path(args.pg_bin).resolve()
    jar = root / 'backend/target/focusduo-backend-0.1.0.jar'
    for executable in (java, pg_bin / 'initdb.exe', pg_bin / 'pg_ctl.exe', pg_bin / 'psql.exe', jar):
        if not executable.is_file():
            parser.error('Required file missing: ' + str(executable))
    for port in (args.server_port, args.db_port):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    run.mkdir(parents=True, exist_ok=False)
    data = run / 'postgres-data'
    password_file = run / 'initdb-password.tmp'
    password = secrets.token_hex(32)
    name = 'focusduo_stage8'
    base_url = f'http://127.0.0.1:{args.server_port}'
    java_process = None
    pg_start_attempted = False
    stopping = False
    creationflags = subprocess.CREATE_NO_WINDOW
    # Explicit configuration prevents parent Spring/JVM overrides selecting another DB.
    environment = {key: os.environ[key] for key in (
        'PATH', 'SystemRoot', 'SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP', 'USERPROFILE', 'APPDATA', 'LOCALAPPDATA')
        if key in os.environ}
    environment.update(DB_URL=f'jdbc:postgresql://127.0.0.1:{args.db_port}/postgres',
                       DB_USERNAME=name, DB_PASSWORD=password,
                       SERVER_ADDRESS='127.0.0.1', SERVER_PORT=str(args.server_port),
                       FOCUSDUO_SESSION_TTL='PT24H', SPRING_CONFIG_LOCATION='classpath:/application.yaml')

    def signal_stop(_number, _frame):
        nonlocal stopping
        stopping = True

    def save_state(stopped=False):
        write_json(run / 'state.json', {
            'kind': 'focusduo-isolated-compose-stage8-v1', 'stopped': stopped,
            'baseUrl': base_url, 'dbPort': args.db_port,
            'javaPid': java_process.pid if java_process and java_process.poll() is None else None,
            'controllerPid': os.getpid(), 'postgresData': str(data),
            'runDirectory': str(run)})

    signal.signal(signal.SIGINT, signal_stop)
    signal.signal(signal.SIGTERM, signal_stop)
    save_state()
    try:
        password_file.write_text(password + '\n', encoding='ascii')
        with (run / 'initdb.log').open('w', encoding='utf-8') as log:
            subprocess.run([str(pg_bin / 'initdb.exe'), '-D', str(data), '--encoding=UTF8', '--no-locale',
                            '--auth=scram-sha-256', '--username=' + name, '--pwfile=' + str(password_file)],
                           stdout=log, stderr=subprocess.STDOUT, check=True, creationflags=creationflags)
        password_file.unlink()
        pg_start_attempted = True
        subprocess.run([str(pg_bin / 'pg_ctl.exe'), '-D', str(data), '-l', str(run / 'postgres.log'),
                        '-o', f'-h 127.0.0.1 -p {args.db_port}', '-w', 'start'],
                       check=True, creationflags=creationflags)
        with (run / 'java.log').open('w', encoding='utf-8') as log:
            java_process = subprocess.Popen([
                str(java), '-Djava.net.preferIPv4Stack=true', '-Djdk.net.unixdomain.tmpdir=' + str(run),
                '-jar', str(jar)], cwd=root, env=environment, stdout=log,
                stderr=subprocess.STDOUT, creationflags=creationflags)
        save_state()
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline and not stopping:
            if java_process.poll() is not None:
                raise RuntimeError('Java exited during startup; inspect java.log')
            try:
                urllib.request.urlopen(base_url + '/api/v1/me', timeout=1).close()
            except urllib.error.HTTPError as error:
                with error:
                    if error.code == 401 and json.load(error).get('code') == 'UNAUTHORIZED':
                        break
            except (urllib.error.URLError, TimeoutError):
                pass
            time.sleep(0.2)
        else:
            raise TimeoutError('Java readiness deadline expired')
        write_json(run / 'ready.json', {'baseUrl': base_url, 'dbPort': args.db_port})
        print(f'READY {base_url} / isolated PostgreSQL :{args.db_port}; no existing DB or .env used', flush=True)
        last_id = None
        while not stopping and not (run / 'shutdown').exists():
            if java_process.poll() is not None:
                raise RuntimeError('Java exited; inspect java.log')
            command_file = run / 'commands.json'
            if command_file.exists():
                command = json.loads(command_file.read_text(encoding='utf-8-sig'))
                request_id = str(uuid.UUID(command['id']))
                if request_id != last_id:
                    last_id = request_id
                    try:
                        if command.get('action') != 'inspect':
                            raise ValueError('Only read-only inspect is supported')
                        sql = subprocess.run([
                            str(pg_bin / 'psql.exe'), '-X', '-qAt', '-v', 'ON_ERROR_STOP=1',
                            '-h', '127.0.0.1', '-p', str(args.db_port), '-U', name, '-d', 'postgres',
                            '-c', INSPECT_SQL], env=dict(environment, PGPASSWORD=password,
                            PGCLIENTENCODING='UTF8', PGOPTIONS='-c default_transaction_read_only=on'),
                            capture_output=True, text=True, encoding='utf-8', check=True,
                            creationflags=creationflags)
                        proof = json.loads(sql.stdout)
                        write_json(run / 'sql-inspection.json', proof)
                        write_json(run / 'result.json', {'id': request_id, 'status': 'ok'})
                        print('INSPECT read-only SQL saved', flush=True)
                    except Exception as error:
                        write_json(run / 'result.json', {'id': request_id, 'status': 'error',
                                                       'errorType': type(error).__name__})
                        print('INSPECT failed: ' + type(error).__name__, flush=True)
            time.sleep(0.2)
    finally:
        password_file.unlink(missing_ok=True)
        if java_process and java_process.poll() is None:
            java_process.terminate()
            try:
                java_process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                java_process.kill()
                java_process.wait(timeout=5)
        if pg_start_attempted:
            # A failed pg_ctl start can still leave its server running. Query only
            # this fresh cluster before cleanup; never discover or stop other PGs.
            status = subprocess.run([str(pg_bin / 'pg_ctl.exe'), '-D', str(data), 'status'],
                                    capture_output=True, creationflags=creationflags)
            if status.returncode == 0:
                subprocess.run([str(pg_bin / 'pg_ctl.exe'), '-D', str(data), '-m', 'fast', '-w', 'stop'],
                               check=True, creationflags=creationflags)
            elif status.returncode != 3:
                raise RuntimeError('Cannot confirm own PostgreSQL is stopped; data preserved at ' + str(data))
        # Delete only this freshly created cluster; preserve logs and SQL evidence.
        if data.is_relative_to(run) and data.name == 'postgres-data' and data.exists():
            shutil.rmtree(data)
        save_state(stopped=True)
        print('STOPPED own Java and PostgreSQL; disposable cluster removed, logs preserved', flush=True)


if __name__ == '__main__':
    main()
