# PR: приёмка просроченного FOCUS

## Ветка

`feat/desktop-focus-expiry` → `main`, база **`1a6fa94`** после merge PR #17 и #18.

## Заголовок

Проверить завершение просроченного FOCUS в двух Compose-клиентах

## Описание

Проверено завершение FOCUS, срок которого прошёл при остановленном Java backend. Два свежих Compose-клиента достигли 00:00/ожидания и после запуска сервера автоматически показали итог 5 минут. PostgreSQL сохранила ровно один COMPLETED/ELAPSED-результат 300000 ms с исходным deadline, целями и задачами.

В REST-seed добавлен `--focus-seconds 300` для воспроизводимой проверки на 5 минут; default 900 s и BREAK 60 s сохранены. Добавлены протокол, SQL-доказательства и оригинальные JPEG. Production-код desktop/backend не изменён.

Документация учитывает уже выполненный физический Modern Standby на desktop `8fdb392`, сохраняя ограничения версии, активации окон и неизмеренной задержки. LAN и обрыв сети остаются отдельными условиями приёмки. Изменения только в `desktop/**`.

## Проверено

- Два свежих Compose JVM от `main` `1a6fa94`, native Java/PostgreSQL: offline 00:00 и автоматический FOCUS-итог у обоих без refresh/reconnect/relogin.
- SQL: 11 инвариантов до stop, 12 после deadline с Java offline, 18 после start; один раунд/одна история, исходный `endedAt`, задачи 1/1 и 0/1.
- Повторный SQL: 8 инвариантов стабильности. После logout все 4 сессии отозваны; собственные окна и изолированный runtime завершены exit 0, пользовательская PostgreSQL сохранена.
- Seed CLI: 8 сценариев разбора, help и синтаксис PASS. Запуски свежих клиентов успешно собраны.
- Полный JUnit/backend smoke не повторялся: прежние **199 + 2** относятся к `774378c`; `desktop/src` и `backend/src` актуальной базы ему равны.

Сохранены **13 оригинальных JPEG** с проверенными MIME/размерами/SHA-256. При осмотре активировали окна; точная задержка и независимый фоновый путь не измерены. LAN и обрыв сети остаются открытыми. Подробности — [FOCUS_EXPIRY_ACCEPTANCE.md](FOCUS_EXPIRY_ACCEPTANCE.md).

## Команды PowerShell

После просмотра готового результата выполнить по очереди; при ошибке остановиться:

```powershell
Set-Location "C:\Users\user\.codex\worktrees\desktop-wake-recovery\focusDuo"
git status --short --branch
git add -- desktop
git diff --cached --stat
git commit -m "test(desktop): verify expired focus recovery in Compose"
git push -u origin feat/desktop-focus-expiry
```

Commit, push и создание PR автоматически не выполняются.
