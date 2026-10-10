# PR этапа 8.1

## Ветка

`feat/desktop-stage8-1` → `main`.

## Заголовок

Проверить два Compose-клиента с Java и исправить гонку выхода

## Описание

При добровольном выходе Java могла закрыть WebSocket кодом `4401` раньше ответа HTTP logout. Клиент ошибочно показывал «сессия истекла» и мог закрыть транспорт до завершения запроса. Контроллер теперь распознаёт logout своего repository, дожидается ответа и показывает штатное сообщение; настоящее истечение сессии вне logout сохраняет прежнее поведение.

Этап 8.1 также проверяет обычный сценарий двух настоящих Compose-окон с production Java backend и отдельной PostgreSQL на одном Windows-ПК: регистрация → create/join → собственные цели/задачи и права → FOCUS → pause/resume → MANUAL finish → неизменяемая история → BREAK → закрытие участником. Read-only SQL подтвердил активное время FOCUS 64 245 ms, BREAK 18 309 ms и по одному результату на раунд. Оба окна получали состояние автоматически без ручного обновления; измерение чистой WS-доставки отдельно не заявляется.

Добавлены воспроизводимый изолированный native runtime, launcher двух профилей с неизменяемым application JAR для каждого запуска, 34 реальных скриншота, SQL checkpoints и [протокол проверки](STAGE8_1_INTEGRATION.md). Изменения только в `desktop/**`, production backend и общий контракт не менялись.

## Проверено

- Windows / Corretto 21.0.6 / Spring Boot 3.5.16 / PostgreSQL 17.2, Java `127.0.0.1:18091`, новая временная PostgreSQL `55438`; пользовательская БД и `.env` не используются.
- `gradlew.bat check --console=plain`: **187 тестов, 0 failures/errors/skipped, BUILD SUCCESSFUL за 9 секунд**.
- Два новых CIO/WS-регрессионных теста проверяют logout с WS close до HTTP `204` и настоящее expiry. До исправления logout-тест падал; после исправления оба и прежние controller-тесты прошли.
- Основной двухпользовательский lifecycle прошёл на baseline `a1e557f`; исправленный logout отдельно повторён в новом Compose-процессе с активным WS: штатное зелёное сообщение, очищенные поля, без ошибки авторизации. Повторный вход восстановил вспомогательную комнату без join; она закрыта без создания раунда.
- Backend JAR собран `mvnw.cmd --batch-mode --no-transfer-progress package -DskipTests`: BUILD SUCCESS за 26.113 секунды. Maven-тесты этой командой не запускались.
- Финальный `run-integration-client.ps1 -Profile a -PrepareOnly`: exit 0, BUILD SUCCESSFUL за 1 секунду; runtime использует отдельный application JAR, без ссылок на изменяемый `build/classes`.
- Финальный SQL: две CLOSED-комнаты, 0 активных членств, 2 раунда/2 истории. Четыре из пяти сессий отозваны; одна старая сессия прерванного первого JVM-сеанса осталась неотозванной, отдельно объяснена в протоколе и удалена вместе с одноразовым кластером. Новые logout подтверждены как revoked.
- Основной runtime и отдельный повтор start/inspect/stop завершились exit 0. Собственные Java/PostgreSQL остановлены, временные кластеры удалены, тестовые порты освобождены; пользовательская PostgreSQL на `5432` продолжила работать.

## Оставшиеся проверки

Recovery/restart harness после clock-исправления, рестарт/сетевые переходы двумя Compose-окнами, физический сон Windows и LAN остаются этапом 8.2. TTL сессии и просроченный ELAPSED через Kotlin не проверены. Заключительная визуальная регрессия и Windows-упаковка остаются этапами 9–10. Прежние backend harness не выдаются за повтор на текущем desktop-коде.

## Команды PowerShell

Выполнять по очереди; после ошибки не продолжать следующую команду:

```powershell
Set-Location "C:\Users\user\.codex\worktrees\desktop-wake-recovery\focusDuo"
git status --short --branch
git add -- desktop
git diff --cached --stat
git commit -m "test(desktop): verify two Compose clients against Java backend"
git push -u origin feat/desktop-stage8-1
```

Команды передаются пользователю для публикации результата; их наличие не означает, что commit, push или создание PR уже выполнены.
