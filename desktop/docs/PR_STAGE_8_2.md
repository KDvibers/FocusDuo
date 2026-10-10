# PR этапа 8.2

## Ветка

`feat/desktop-stage8-2` → `main`. Этап 8.1 объединён PR #16; проверенный `origin/main` — `ad5da2a`, содержащий desktop baseline `0da9c6c`.

## Заголовок

Исправить переход к новому раунду и проверить восстановление Compose-клиентов

## Описание

После завершения FOCUS участник мог остаться на старом автоматически открытом итоге, хотя новый BREAK уже получен с сервера. Клиент теперь следует следующему раунду своей комнаты и открывает его серверный итог, включая BREAK, целиком прошедший offline. Вручную выбранная история сохраняется; формы, pending и conflict не перехватываются. Поздние ответы/ошибки старого результата отбрасываются, retry сохраняет auto/manual происхождение запроса.

Добавлены 12 регрессионных тестов, desktop init-script для существующих integration harness, REST-seed двух пользователей и безопасные UUID-команды start/stop/inspect изолированного native runtime. Протокол, SQL и 21 реальный снимок сохранены внутри desktop. Изменения только в `desktop/**`; production backend и общий контракт не меняются.

## Проверено

- После исправления: **199 обычных + 2 живых теста**, 0 failures/errors/skipped, BUILD SUCCESSFUL за 24 s; targeted 44 PASS. Сохранены JUnit-результаты и SHA-256 исходников.
- **Два свежих Compose JVM:** автоматические переходы FOCUS → итог → BREAK; при выключенной Java оба 00:00/ожидание и изменения недоступны; после start оба автоматически CONNECTED → новый BREAK-итог, без Refresh/reconnect/ручной навигации. SQL: одна ELAPSED-история 60 000 ms, исходный deadline, полные FOCUS-снимки неизменны.
- PAUSED/RUNNING stop/start подтверждены USER_REPORTED обоих окон, исходными снимками и SQL. MANUAL finish: 297 900 ms и ровно одна история.
- **Живой TTL PT30S:** автоматический возврат на экран входа с пустым паролем и сообщением expiry, без logout/Refresh/reconnect и без reconnect-loop; SQL lifetime ровно 30 s. UI TTL проверен на immutable baseline 0da9c6c с тем же auth-кодом; отдельный стенд остановлен, собственная PG data удалена.
- На baseline **до navigation-fix**: четыре harness / 19 checkpoints — потерянные Java-ответы после commit, точный replay без дублей, настоящий 409, два автоматических восстановления repository после рестарта. Native runtime: 8 probe-проверок. Эти harness не заявляются повторно выполненными после правки навигации.

Среда: native Windows / Corretto 21.0.6 / Spring Boot 3.5.16 / PostgreSQL 17.2. Сохранён 21 снимок: 4 исходных PNG пользователя + 17 JPEG агента; bytes/SHA-256/MIME проверены. Fixtures: 10 равных Git blobs и LF-normalized JSON; raw checkout SHA различается из-за CRLF/LF. Доказательства и воспроизведение — [STAGE8_2_RECOVERY.md](STAGE8_2_RECOVERY.md).

## Границы

Основной 8090/55438 и два свежих окна намеренно ALIVE для просмотра пользователем; teardown не выполнялся. Физический сон Windows, отключение адаптера, LAN и отдельный просроченный FOCUS не проверены, поэтому более широкая приёмка 8.2 остаётся открытой. Полный визуальный аудит и Windows-пакет относятся к этапам 9–10.

## Команды PowerShell

```powershell
Set-Location "C:\Users\user\.codex\worktrees\desktop-wake-recovery\focusDuo"
git status --short --branch
git add -- desktop
git diff --cached --stat
git commit -m "fix(desktop): follow live rounds and verify backend recovery"
git push -u origin feat/desktop-stage8-2
```

Выполнять по очереди; после ошибки остановиться. Commit/push/создание PR автоматически не выполняются.
