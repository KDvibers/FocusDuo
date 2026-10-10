#!/usr/bin/env python3
"""Disposable local Java/PostgreSQL runtime with an opt-in file restart controller.

Run in Linux/WSL with Docker and Java 21. Never uses the project's .env or DB.
The Kotlin restart smoke uses the same control directory through its Windows path.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def write_json(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value), encoding='utf-8')
    temporary.replace(path)


def main():
    root = Path(__file__).resolve().parents[3]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--control-dir', default='.local/recovery-stage7-control')
    args = parser.parse_args()
    control = (root / args.control_dir).resolve()
    if not control.is_relative_to(root / '.local') or control == root / '.local':
        parser.error('control-dir must be a new directory within this checkout .local/')
    jar = root / 'backend/target/focusduo-backend-0.1.0.jar'
    if not jar.is_file():
        parser.error('Build the backend JAR first')
    for port in (8090, 55437):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    control.mkdir(parents=True, exist_ok=False)
    name = 'focusduo-recovery-' + uuid.uuid4().hex[:12]
    password = secrets.token_hex(32)
    # Do not inherit Spring or injected JVM options that could override the isolated database.
    environment = {key: os.environ[key] for key in ('PATH', 'HOME', 'JAVA_HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'TZ')
                   if key in os.environ}
    environment.update(DB_URL='jdbc:postgresql://127.0.0.1:55437/focusduo_recovery',
                       DB_USERNAME='focusduo_recovery', DB_PASSWORD=password,
                       SERVER_ADDRESS='127.0.0.1', SERVER_PORT='8090', FOCUSDUO_SESSION_TTL='PT24H',
                       SPRING_CONFIG_LOCATION='classpath:/application.yaml')
    process = None
    created = False
    generation = 0
    stopping = False

    def signal_stop(_number, _frame):
        nonlocal stopping
        stopping = True

    def save_state():
        write_json(control / 'state.json', {'container': name, 'dbPort': 55437,
                   'serverPid': process.pid if process and process.poll() is None else None,
                   'generation': generation})

    def stop_server(force):
        nonlocal process
        if process and process.poll() is None:
            process.kill() if force else process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        process = None
        save_state()

    def start_server():
        nonlocal process, generation
        if process is None or process.poll() is not None:
            generation += 1
            with (control / f'server-{generation}.log').open('w') as log:
                process = subprocess.Popen(['java', '-Djava.net.preferIPv4Stack=true', '-jar', str(jar)],
                                           cwd=root, env=environment, stdout=log, stderr=subprocess.STDOUT)
            save_state()
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError('Test JAR exited during startup; inspect its local log')
            try:
                urllib.request.urlopen('http://127.0.0.1:8090/api/v1/me', timeout=1).close()
            except urllib.error.HTTPError as error:
                with error:
                    if error.code == 401 and json.load(error).get('code') == 'UNAUTHORIZED':
                        return
            except (urllib.error.URLError, TimeoutError):
                pass
            time.sleep(0.2)
        raise TimeoutError('Test JAR readiness deadline expired')

    signal.signal(signal.SIGINT, signal_stop)
    signal.signal(signal.SIGTERM, signal_stop)
    try:
        subprocess.run(['docker', 'run', '--name', name, '--label', 'dev.focusduo.check=recovery-stage7',
                        '--detach', '--publish', '127.0.0.1:55437:5432',
                        '--env', 'POSTGRES_DB=focusduo_recovery', '--env', 'POSTGRES_USER=focusduo_recovery',
                        '--env', 'POSTGRES_PASSWORD', '--tmpfs', '/var/lib/postgresql/data:rw',
                        'postgres:17.11-bookworm'], env=dict(os.environ, POSTGRES_PASSWORD=password),
                       check=True, stdout=subprocess.DEVNULL)
        created = True
        for _ in range(120):
            ready = subprocess.run(['docker', 'exec', name, 'pg_isready', '-U', 'focusduo_recovery',
                                    '-d', 'focusduo_recovery'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if ready.returncode == 0:
                break
            time.sleep(0.25)
        else:
            raise TimeoutError('Test PostgreSQL readiness deadline expired')
        start_server()
        write_json(control / 'marker.json', {'kind': 'focusduo-isolated-restart-v1'})
        print('READY own JAR :8090 / disposable PostgreSQL :55437; .env and existing DB preserved', flush=True)
        last_id = None
        while not stopping and not (control / 'shutdown').exists():
            command_file = control / 'commands.json'
            if command_file.exists():
                command = json.loads(command_file.read_text(encoding='utf-8'))
                request_id = str(uuid.UUID(command['id']))
                if request_id != last_id:
                    last_id = request_id
                    try:
                        if command['action'] == 'stop':
                            stop_server(force=True)
                        elif command['action'] == 'start':
                            start_server()
                        else:
                            raise ValueError('Only stop/start actions are allowed')
                        write_json(control / 'result.json', {'id': request_id, 'status': 'ok'})
                        print(f'CONTROL {command["action"]}: ok, generation {generation}', flush=True)
                    except Exception as error:
                        write_json(control / 'result.json', {'id': request_id, 'status': 'error',
                                   'errorType': type(error).__name__})
            time.sleep(0.1)
    finally:
        stop_server(force=False)
        # docker run can create a container and then fail to start it. Inspect our exact name
        # even when run failed; never enumerate or remove other containers.
        inspected = subprocess.run(['docker', 'inspect', '--format',
                                    '{{index .Config.Labels "dev.focusduo.check"}}', name],
                                   text=True, capture_output=True)
        if inspected.returncode == 0:
            if inspected.stdout.strip() != 'recovery-stage7':
                raise RuntimeError('Cleanup refused an unexpected container label')
            subprocess.run(['docker', 'rm', '--force', name], check=True, stdout=subprocess.DEVNULL)
        elif created:
            raise RuntimeError('Cannot confirm cleanup of own test container')
        write_json(control / 'marker.json', {'kind': 'focusduo-isolated-restart-v1', 'stopped': True})
        print('STOPPED own Java process and removed own PostgreSQL; existing DB and .env preserved', flush=True)


if __name__ == '__main__':
    main()
