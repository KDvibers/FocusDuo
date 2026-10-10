# PR этапа 6

## Заголовок

Подключить комнаты, раунды, историю и WebSocket FocusDuo Desktop

## Описание

Обычный режим теперь позволяет работать с серверной комнатой: создать её или войти по коду, менять свои цель и задачи, управлять FOCUS/BREAK от имени владельца и читать сохранённый итог. Изменения приходят полными снимками по REST и raw JSON WebSocket.

- REST-команды V1 для комнаты, настроек, целей, задач и раундов; UUID Idempotency-Key и X-Room-Revision передаются по контракту.
- Серверная история и загрузка отдельного результата, включая фактическое время и замороженные задачи.
- Ktor/CIO WebSocket с bearer в handshake, реальными состояниями соединения и блокировкой изменений до первого полного снимка.
- Общий reducer защищает от старых REST/WS-снимков; равная revision уточняет время, сохраняя бизнес-данные. Snapshot конфликта применяется без автоматического повторения команды.
- Неоднозначный исход блокирует новые изменения до синхронизации; для create/join требуется GET текущей комнаты. Устаревший первый WS-снимок не разрешает команды.
- Монотонный отсчёт serverNow/endsAt: PAUSED заморожен, ноль ожидает backend, terminal сохраняет фактическое активное время.
- Автономные HTTP/WS/TCP, lifecycle, reducer и timer-тесты; opt-in backendRoomSmoke для сценария двух Kotlin-репозиториев с Java backend.
- Обновлены README, ROADMAP и HANDOFF. Изменения ограничены desktop/**.

## Проверки и границы

Windows / JDK 21: `gradlew.bat test --console=plain` и `gradlew.bat check --console=plain` — **BUILD SUCCESSFUL, 143 passed, 0 failures, 0 errors, 0 skipped**. Включены 13 transport/lifecycle-сценариев production CIO и 18 reducer/timer-проверок; подробности в [HANDOFF.md](HANDOFF.md).

Тестовые TCP/WS-серверы проверяют production CIO, но не заменяют совместную Java-интеграцию. Backend на 127.0.0.1:8090 не запущен; backendAuthSmoke и backendRoomSmoke пока не выполнялись. Проверка Compose-окна и новые скриншоты по указанию пользователя отложены до этапа 7. Автоматический reconnect/backoff, восстановление после сна и безопасные повторы неоднозначных команд остаются этапом 7; Windows-пакет ещё не собран.

## Коммит и push

Выполнить в PowerShell последовательно, останавливаясь при ошибке:

```powershell
Set-Location "C:\Users\user\Desktop\focusDuo"
git add -- desktop
git diff --cached --stat
git commit -m "feat(desktop): connect rooms rounds history and WebSocket"
git push -u origin feat/desktop-mvp
```

Команды передаются пользователю. Агент не выполняет commit, push или merge.
