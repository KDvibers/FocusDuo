# Повтор Java-интеграции после исправления wake

Дата: 11 октября 2026, Asia/Qyzylorda. База — `main` `a1e557f`: desktop wake PR #14 (`8fdb392`) и backend recovery PR #13 (`8310e23`). Ветка проверки — `feat/backend-wake-acceptance`. Production Java, тестовые harness, `desktop/**`, общий контракт и fixtures в этом заходе не менялись.

## Исправленное замечание

Прежнее условие из [RECOVERY_INTEGRATION.md](RECOVERY_INTEGRATION.md) закрыто в production-пути: `ResumeRecoveryCoordinator` учитывает максимум календарного и монотонного интервалов; `RealWorkspace` использует его и для heartbeat, и для foreground-сигналов. Сценарий wall +61 секунда / nano +1 секунда больше не отбрасывает восстановление. Mutex и минимальный интервал сохраняют защиту от одновременных и частых вызовов.

Независимый read-only review новых coordinator/seam-тестов и подключения к workspace не выявил новых actionable замечаний. Проверки покрывают точное прежнее условие, оба порядка сигналов, границы времени, отмену/ошибки и путь до блокировки изменений в настоящем `RealRepository`. Их транспорт управляемый; это не физический сон Windows и не Compose-сеанс с Java.

## R7: повтор обычных и базовых живых тестов

Native Windows, Temurin JDK 21.0.12.101, Gradle Wrapper 8.13 → отдельный Java JAR в WSL на `127.0.0.1:8090` → новая PostgreSQL 17.11 с tmpfs на 55437. Контроллер [serve.py](../../backend/scripts/kotlin-restart-smoke/serve.py) использовал новую папку `.local/wake-acceptance-1`, собственные контейнер и Java-процесс. Пользовательская PostgreSQL на 5434 и `.env` не используются этим стендом. JAR и серверные исходники совпадают с ранее проверенной реализацией.

`test check backendAuthSmoke backendRoomSmoke`: **BUILD SUCCESSFUL за 27 секунд, exit 0**. XML подтвердили:

| Набор | Тестов | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Обычные тесты | 185 | 0 | 0 | 0 |
| В том числе `ResumeRecoveryCoordinatorTest` | 13 | 0 | 0 | 0 |
| В том числе `ResumeRecoveryRepositoryTest` | 1 | 0 | 0 | 0 |
| Живой `backendAuthSmoke` | 1 | 0 | 0 | 0 |
| Живой `backendRoomSmoke` | 1 | 0 | 0 | 0 |

Итого 185 + 2, строки «в том числе» повторно не суммируются. Все 10 копий fixtures совпали с общими по SHA-256. XML: `desktop/build/test-results/{test,backendAuthSmoke,backendRoomSmoke}/`; артефакты сборки исключены из Git.

## R8: повтор расширенных живых сценариев

Все четыре существующих opt-in harness завершились **BUILD SUCCESSFUL, exit 0**, каждый с итоговым `PASS`. Они проверяют repository/REST/WS с настоящими ответами Java, а не UI-координатор пробуждения. Тестовые исходники не адаптировались под новое исправление.

| Harness | Время Gradle | Контрольных точек | Подтверждённое поведение |
| --- | ---: | ---: | --- |
| `realRepositorySmoke` | 6 секунд | 5 | Авторизация, заполненная комната, отзыв сессии/401, восстановление участия при login, logout. |
| `realRoomLifecycleSmoke` | 6 секунд | 5 | Ручной reconnect, login без повторного join, отзыв открытой WS-сессии, сохранение CLOSED. |
| `realRecoverySmoke` | 6 секунд | 4 | Потерянные ответы после настоящего commit create/task, точный replay без второго эффекта, сохранение новой правки партнёра, реальный 409. |
| `realRestartSmoke` | 42 секунды | 5 | Два принудительных завершения Java с той же БД; автоматическое восстановление двух Kotlin repository, PAUSED/RUNNING, исходный `endsAt`, одна равная история. |

Всего 19 контрольных точек и 4 итоговых `PASS`; это исполняемые сценарии, не дополнительные JUnit-тесты к числу 185 + 2. В restart harness два Kotlin repository продолжали работать при обоих SIGKILL Java; поколения контроллера — 1 → 2 → 3. Прямой `recoverAfterResume()` подтвердил синхронизацию repository без команд изменения комнаты, но не прохождение цепочки событий физического сна через Compose.

Локальные журналы: `.local/wake-acceptance-1/{auth,room,recovery,restart}-smoke.log`. После всех сценариев SQL подтвердил **0 OPEN rooms, 0 active memberships, 0 unrevoked sessions, 3 раунда, 3 записи истории и 2 миграции**. Три истории соответствуют FOCUS/BREAK базового room smoke и FOCUS restart harness; дублей нет.

После создания marker `shutdown` контроллер завершился с **exit 0 / STOPPED**: собственные Java-процесс и контейнер `focusduo-recovery-7e93180efeda` удалены, порты 8090/55437 свободны. Пользовательский `focusduo-db-1` остался healthy на 5434. `.env` сохранён без изменений; журналы и build-артефакты исключены из Git.

## Команды повторения

Из корня репозитория, при свободных 8090/55437 и уже собранном backend JAR. WSL, Docker и JDK описаны в [backend README](../../backend/README.md). В первой PowerShell-оболочке:

```powershell
wsl -d Ubuntu -u root -- python3 /mnt/c/Daniyar/MyProjects/FocusDuo/backend/scripts/kotlin-restart-smoke/serve.py --control-dir .local/wake-acceptance-example
```

Дождитесь `READY`. Папка контроллера должна быть новой при каждом запуске; путь рабочей копии замените своим. Во второй оболочке из корня:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:PATH=(Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:/Daniyar/MyProjects/FocusDuo/backend/target'
.\desktop\gradlew.bat -p desktop test check backendAuthSmoke backendRoomSmoke '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' --console=plain --no-daemon
.\desktop\gradlew.bat -p desktop -I (Resolve-Path backend/scripts/kotlin-auth-smoke/init.gradle).Path realRepositorySmoke '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' --console=plain
.\desktop\gradlew.bat -p desktop -I (Resolve-Path backend/scripts/kotlin-room-smoke/init.gradle).Path realRoomLifecycleSmoke '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' --console=plain
.\desktop\gradlew.bat -p desktop -I (Resolve-Path backend/scripts/kotlin-recovery-smoke/init.gradle).Path realRecoverySmoke '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' --console=plain
.\desktop\gradlew.bat -p desktop -I (Resolve-Path backend/scripts/kotlin-restart-smoke/init.gradle).Path realRestartSmoke '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' "-Dfocusduo.smoke.controlDir=$((Resolve-Path .local/wake-acceptance-example).Path)" --console=plain --no-daemon
```

Команды выполняются по одной с проверкой exit code; пути JDK и `backend/target` замените для своего ПК. После проверки результатов остановите свой контроллер:

```powershell
New-Item -ItemType File -Path .local/wake-acceptance-example/shutdown
```

Дождитесь `STOPPED`: контроллер удалит свою временную БД и Java-процесс. Этот стенд подходит для автоматических проверок, но не для ручного физического сна: для него используйте обычную сохраняемую Compose-БД по [MANUAL_ACCEPTANCE.md](MANUAL_ACCEPTANCE.md).

## Границы и следующий шаг

В desktop PR #14 включены 27 снимков и заполненные результаты прежнего промежуточного Compose-аудита. По [desktop-разбору](../../desktop/docs/WAKE_RECOVERY_REVIEW.md) тот сеанс использовал демо и явный тестовый HTTP/WS-стенд; это не два Compose-окна с Java и не новый GUI-прогон после clock-исправления. Незаполненные `STAGE7_TEST*`/`STAGE7_COMPOSE*` в проверенной документации больше не найдены.

Ручная часть — [два окна и физический сон Windows](MANUAL_ACCEPTANCE.md). Она остаётся **NOT_RUN**. Также не повторены физическое отключение сетевого адаптера, LAN, живое истечение TTL, просроченный ELAPSED после длительного выключения Java, финальный UI-аудит и Windows-упаковка. Вызов `recoverAfterResume()` из harness не засчитывается как прохождение всей цепочки событий сна ОС и Compose.

Полный Maven `verify` локально не повторялся: серверная реализация не менялась. [CI объединённого backend PR #13](https://github.com/KDvibers/FocusDuo/actions/runs/38082205454) успешен на `8310e23`; это прежнее серверное доказательство. CI новой ветки ожидает push владельца. Backend-этап 3 остаётся открытым до согласованной ручной приёмки и review.
