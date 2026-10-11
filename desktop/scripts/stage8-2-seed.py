#!/usr/bin/env python3
"""Prepare two synthetic accounts and a PAUSED room for real Compose recovery QA.

Setup uses REST, not UI. Password is required in FOCUSDUO_QA_PASSWORD and is never
printed or saved. Every acquired seed session is logged out before this exits.
Only safe usernames and the room snapshot are written under desktop/build.
"""
import argparse
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    desktop = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8090')
    parser.add_argument('--output', default='desktop/build/stage8-2-seed.json')
    parser.add_argument('--focus-seconds', type=int, default=900,
                        help='FOCUS duration: 300-10800 seconds, in whole minutes (default: 900)')
    args = parser.parse_args()
    if not 300 <= args.focus_seconds <= 10800 or args.focus_seconds % 60:
        parser.error('--focus-seconds must be 300-10800 and a multiple of 60')
    base = args.base_url.rstrip('/')
    uri = urllib.parse.urlsplit(base)
    if (uri.scheme != 'http' or uri.hostname not in ('127.0.0.1', 'localhost', '::1')
            or uri.username is not None or uri.password is not None
            or uri.path or uri.query or uri.fragment):
        parser.error('--base-url must be a loopback HTTP origin without credentials or path')
    output = Path(args.output)
    if not output.is_absolute():
        output = desktop.parent / output
    output = output.resolve()
    build = (desktop / 'build').resolve()
    if build not in output.parents:
        parser.error('--output must stay under desktop/build')
    password = os.environ.get('FOCUSDUO_QA_PASSWORD', '')
    if not 10 <= len(password) <= 128:
        parser.error('FOCUSDUO_QA_PASSWORD is required (10-128 characters)')
    suffix = uuid.uuid4().hex[:12]
    usernames = [f'gui82_a_{suffix}', f'gui82_b_{suffix}']
    names = ['Владелец — восстановление', 'Участник — восстановление']
    sessions = []
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect)

    def request(method, path, body=None, token=None, revision=None, mutation=False):
        headers = {'Accept': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if mutation:
            headers['Idempotency-Key'] = str(uuid.uuid4())
        if revision is not None:
            headers['X-Room-Revision'] = str(revision)
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode('utf-8')
            headers['Content-Type'] = 'application/json'
        req = urllib.request.Request(base + '/api/v1' + path, data=data,
                                     headers=headers, method=method)
        with opener.open(req, timeout=10) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    snapshot = None
    failed = False
    try:
        for username, name in zip(usernames, names):
            sessions.append(request('POST', '/auth/register', {
                'username': username, 'displayName': name, 'password': password}))
        owner, peer = (session['accessToken'] for session in sessions)
        snapshot = request('POST', '/rooms', {
            'focusDurationSeconds': args.focus_seconds, 'breakDurationSeconds': 60}, owner, mutation=True)
        snapshot = request('POST', '/rooms/join', {'code': snapshot['code']}, peer, mutation=True)

        def command(token, method, suffix, body=None):
            nonlocal snapshot
            snapshot = request(method, '/rooms/' + snapshot['roomId'] + suffix,
                               body, token, snapshot['revision'], mutation=True)

        goals = ['Сохранить комнату, задачи и паузу после рестарта Java без ручного обновления',
                 'Проверить восстановление второго Compose-клиента и согласованный итог']
        titles = ['Проверить восстановление паузы и длинной русской задачи после двух перезапусков сервера',
                  'Убедиться, что общий таймер продолжает работу по исходному серверному сроку завершения']
        for index, token in enumerate((owner, peer)):
            command(token, 'PATCH', '/me', {'goal': goals[index]})
            command(token, 'POST', '/tasks', {'title': titles[index]})
        own = next(p for p in snapshot['participants'] if p['userId'] == sessions[0]['user']['id'])
        command(owner, 'PATCH', '/tasks/' + own['tasks'][0]['id'], {'completed': True})
        command(owner, 'POST', '/rounds', {'kind': 'FOCUS'})
        command(owner, 'POST', '/rounds/' + snapshot['round']['id'] + '/pause')
        if snapshot['round']['status'] != 'PAUSED' or len(snapshot['participants']) != 2:
            raise ValueError('Unexpected final seed state')
    except Exception as failure:
        print('FAIL seed: ' + type(failure).__name__, file=sys.stderr)
        failed = True
    finally:
        for session in sessions:
            try:
                request('POST', '/auth/logout', token=session['accessToken'])
            except Exception as failure:
                print('FAIL seed logout: ' + type(failure).__name__, file=sys.stderr)
                failed = True
        sessions.clear()
        password = ''
    if failed:
        return 1
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + '.tmp')
    temporary.write_text(json.dumps({'usernames': usernames, 'room': snapshot},
                                   ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(output)
    print(json.dumps({'usernames': usernames, 'code': snapshot['code'],
                      'roundId': snapshot['round']['id'], 'status': snapshot['round']['status'],
                      'revision': snapshot['revision']}, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
