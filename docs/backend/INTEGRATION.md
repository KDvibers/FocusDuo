# Приёмка FocusDuo: Kotlin desktop ↔ Java backend

Этот документ задаёт порядок совместной проверки и фиксирует её доказательства. Текущая база — `main` **`1a6fa94`**, desktop **`774378c`**, ветка `feat/backend-final-integration`: объединены desktop PR #16/#18 и backend PR #17. M1–M4 запускались отдельно на desktop `8fdb392` / рабочем `30c8235`. Начальная серверная подготовка выполнялась на `a508020`, auth-проверка — на `7acb617`, проверка комнаты desktop 6 — на `7cee8c4`, recovery-проверка desktop 7 — на `67b66c5`, повтор wake — на `a1e557f`; их доказательства сохраняют исходные версии и условия.

Desktop этапа 7 добавил автоматический reconnect, повторные безопасные чтения, синхронизацию по сигналам пробуждения/окна и явный replay сохранённой команды с исходными method/path/body/key/revision. REST/WS, история и серверный таймер сохранены. Предыдущие K0/A1–A3 и R1–R6 проведены 10–11 октября 2026, Asia/Qyzylorda; R4–R6 подтвердили работу repository с настоящим Java backend после merge desktop 7. PR #14 устранил найденный пропуск wake-сигнала в пути Compose: новый coordinator учитывает календарные и монотонные часы.

PR #14 включил документацию и 27 снимков прежней Compose-проверки этапа 7 с демо/тестовым HTTP/WS-стендом. **11 октября отдельно выполнены M1–M4: два настоящих Compose-окна с Java и PostgreSQL, сон Windows в PAUSED и RUNNING, ручной итог, неизменяемая история, BREAK/close и logout.** [Новый отчёт с 29 снимками](COMPOSE_ACCEPTANCE.md) фиксирует результаты и ограничения наблюдения. По [desktop-плану](../../desktop/docs/ROADMAP.md) этап 8 посвящён живой Java-интеграции, 9 — заключительной проверке UI, 10 — Windows-пакету. Полные K1–K7/UI и итоговая приёмка backend этапа 3 остаются открытыми.

После этого [desktop 8.1](../../desktop/docs/STAGE8_1_INTEGRATION.md) подтвердил обычный GUI lifecycle и исправление logout. [Desktop 8.2](../../desktop/docs/STAGE8_2_RECOVERY.md) добавил GUI recovery, исправление live-result navigation, **199 + 2 теста**, свежий двухпроцессный **BREAK/ELAPSED** и отдельный **TTL PT30S UI/SQL PASS**. TTL относится к immutable baseline `0da9c6c` с неизменённой авторизацией, BREAK — к финальной навигационной правке; четыре расширенных harness запускались до неё. **12 октября отдельный FOCUS-прогон F1 завершился PASS:** при выключенной Java оба UI дождались нуля, после рестарта автоматически показали итог с одной `COMPLETED/ELAPSED`-историей. Это два production `RealApp`/repository в одном тестовом JVM с API-подготовкой вместо форм входа, без `Main`/`RealWorkspace` wake-пути. [Отчёт F1](EXPIRED_FOCUS_ACCEPTANCE.md) содержит журнал, SQL и 7 снимков; он не заменяет полный штатный UI-сценарий.

## Материалы и подготовка

- Источник протокола: [общий контракт v1](../FOCUSDUO_CONTRACT_V1.txt), точные DTO/пути — [OpenAPI](../contracts/openapi.yaml), примеры сериализации — [исходные fixtures](../contracts/fixtures/).
- Запуск сервера, локальный `.env`, Java/PostgreSQL/WSL и ограничения среды: [backend README](../../backend/README.md). `.env` относится к backend; пароли БД не нужны Kotlin-клиенту и не передаются другу.
- Ручные запросы: [requests.http](../contracts/requests.http). Проверки работающего JAR без desktop: [HTTP smoke](../../backend/scripts/smoke.py), [WebSocket CLI](../../backend/scripts/WebSocketSmoke.java) и [команды их запуска](../../backend/scripts/README.md). Они создают тестовые аккаунты; HTTP smoke также сохраняет историю. Используйте отдельную тестовую БД.
- Состояние сервера и выполненные проверки: [backend HANDOFF](HANDOFF.md). Сборка и запуск клиента: [desktop README](../../desktop/README.md); план сетевой части — [desktop ROADMAP](../../desktop/docs/ROADMAP.md).
- Повтор автоматических проверок wake: [WAKE_ACCEPTANCE.md](WAKE_ACCEPTANCE.md). Реальные Compose-окна и физический сон: [COMPOSE_ACCEPTANCE.md](COMPOSE_ACCEPTANCE.md); команды запуска и повторения M1–M4: [MANUAL_ACCEPTANCE.md](MANUAL_ACCEPTANCE.md).

Перед совместным прогоном запишите commit backend, commit desktop, ОС/JDK, способ запуска БД и адрес сервера. Для автоматических A1/R1 нужен **один** backend; R1 создаёт два независимых repository в одном тестовом JVM-процессе. Для полного K1 нужны два независимых процесса клиента с разными тестовыми пользователями A и B; сессии и настройки подключения каждого экземпляра должны быть независимыми. Оба клиента используют один адрес сервера. Не запускайте `--demo` и не принимайте fake-данные за сетевой ответ.

На одном ПК адрес сервера — `http://127.0.0.1:8090`; REST использует `/api/v1`, raw JSON WS — `ws://127.0.0.1:8090/ws/rooms/{roomId}`. Для серверного JAR в WSL используйте проверенную команду с `-Djava.net.preferIPv4Stack=true` из backend README при подключении из Windows. Если Java-клиент или тесты работают нативно в Windows, а БД — в WSL, держите WSL-терминал активным.

Для изоляции сетевой ошибки можно проверить `GET /api/v1/me` без токена: ожидается HTTP 401 с `code=UNAUTHORIZED`. Это признак доступного API, а не успешная авторизация. Затем при необходимости выполните HTTP smoke. Его успех доказывает доступность сервера и HTTP-сценарий; для отметки Kotlin `PASS` нужны действия и наблюдения настоящих desktop-клиентов.

## Проверки fixtures и HTTP-авторизации: сохранённые результаты desktop 5

K0 проверяет десериализацию и mappings всех 10 общих fixtures без работающего backend. В предыдущем auth-заходе повторно прошли 17 `ContractFixturesTest` в составе 112 обычных desktop-тестов; SHA-256 всех 10 копий совпали с общими fixtures. Это подтверждает и ранее записанный результат автора в desktop HANDOFF. Для отдельного повторения из `desktop/` на Windows / JDK 21:

```powershell
.\gradlew.bat test --tests "dev.focusduo.data.remote.ContractFixturesTest" --console=plain
```

A1 запускает production `RealRepository`/CIO против доступного Java backend: register → `/me` → current `null` → logout → login → тот же userId и current `null` → logout. Из `desktop/`:

```powershell
.\gradlew.bat backendAuthSmoke "-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090" --console=plain
```

Задача исключена из обычного `test`, не запускает Compose-окно и создаёт один случайный тестовый аккаунт; пароль генерируется в памяти и не выводится. Аккаунт остаётся в тестовой БД, удаления аккаунта в V1 нет. Результат подтверждайте XML в `desktop/build/test-results/backendAuthSmoke/`, записав commit и среду запуска сервера. Успех A1 подтверждает только перечисленные HTTP-вызовы: существующая комната, два клиента, команды/WS, проверка отозванного токена и истечения сессии в него не входят. Эта проверка не включает UI-приёмку.

Проверенный совместный прогон K0/A1: база `7acb617`, ветка `feat/backend-auth-integration`, Kotlin на Windows / Temurin `21.0.12.101` → Java JAR в WSL с IPv4 на `127.0.0.1:8090` → отдельный PostgreSQL 17.11 на порту 55435 с временной tmpfs-БД `focusduo_auth_stage5`. Пользовательский `focusduo-db-1` на 5434 остался healthy; его данные и `.env` не менялись. Команда из `desktop/` завершилась `BUILD SUCCESSFUL` за 2 минуты 3 секунды, exit 0:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:/Daniyar/MyProjects/FocusDuo/backend/target'
.\gradlew.bat test backendAuthSmoke "-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090" --console=plain --no-daemon
```

Пути выше относятся к проверенной машине; `jdk.net.unixdomain.tmpdir` указывает на уже существующий полный путь, обходя наблюдавшуюся проблему Windows с коротким именем TEMP. XML подтверждают отдельно `test`: **112 тестов**, включая 17 fixtures, и `backendAuthSmoke`: **1 тест**; в обоих наборах 0 failures/errors/skips. Отчёты находятся в `desktop/build/test-results/test/` и `desktop/build/test-results/backendAuthSmoke/`.

A2 — отдельный [opt-in harness `realRepositorySmoke`](../../backend/scripts/kotlin-auth-smoke/README.md) с двумя независимыми production `RealRepository` и настоящим CIO. В той же среде он завершился `BUILD SUCCESSFUL` за 6 секунд, exit 0; после исправления очистки обычный прогон повторно дал тот же успешный результат. Проверены нормализация регистрации, неверный пароль, занятый username, чтение current/известной комнаты с двумя участниками, целью и задачей. После внешнего отзыва точного токена реальный HTTP 401 переводит repository в `EXPIRED` и очищает сессию; второй участник остаётся авторизован. Новый login восстанавливает членство; logout даёт `SIGNED_OUT`, отозванный токен получает 401, а комната остаётся OPEN до явной очистки harness.

Комнату, цель и задачу для A2 подготавливают вспомогательные запросы JDK HTTP; это не Kotlin-команды комнаты. Делегат CIO только наблюдает bearer в памяти для точного внешнего отзыва, не подменяя ответы. Секреты не выводятся. Команды повторения и границы находятся в README harness, подробный отчёт — [AUTH_INTEGRATION.md](AUTH_INTEGRATION.md). A2 не проверяет WS, TTL-истечение или UI. Дополнительный harness запускается только явно и не добавлен в CI.

11 октября отдельно проверена очистка после потери ответа (A3): локальный proxy пропустил `POST /api/v1/rooms` до настоящего backend, получил 200 после сохранения комнаты и оборвал ответ. Harness ожидаемо завершился с `IOException`, exit 1; очистка сделала один GET current и один успешный close (200). Прямой SQL после неё показал `open_rooms=0`, `active_memberships=0`, `unrevoked_sessions=0`. Это успешная целевая проверка обработки ошибки с ожидаемым падением основного сценария; её нельзя засчитывать как обычный успешный smoke или Kotlin retry из K4.

После проверок временные proxy, Java-сервер и PostgreSQL-контейнер с tmpfs удалены; порты 8090, 8091 и 55435 проверены свободными. Пользовательский `focusduo-db-1` на 5434 остался healthy, корневой `.env` сохранён без изменений и исключён из Git.

## R1. Проверка комнаты через Kotlin repository: desktop 6

`LiveBackendRoomTest` запускает два независимых production `RealRepository`/CIO против одного Java backend. Все команды этого сценария выполняет сам Kotlin repository. Из `desktop/` на JDK 21 при работающем сервере разработки:

```powershell
.\gradlew.bat test check backendAuthSmoke backendRoomSmoke "-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090" --console=plain --no-daemon
```

Обе live-задачи исключены из обычного `test`; `backendRoomSmoke` можно вызвать отдельно той же командой без `test check backendAuthSmoke`. Используйте отдельную тестовую БД: сценарий комнаты создаёт два аккаунта и сохраняет комнату/историю. Успешный сценарий закрывает свою комнату и выполняет logout обоих; закрытие HTTP/WS-ресурсов само по себе не удаляет записи из БД.

| Часть R1 | Проверяемое поведение repository |
| --- | --- |
| Комната и WS | Регистрация двух пользователей, bootstrap, create 300/60, join по code в нижнем регистре, готовность обоих WS и полные снимки. Повтор join сохраняет двух участников и ту же revision. |
| Цель и задача | Владелец меняет цель, добавляет задачу и отмечает её выполненной; второй repository получает соответствующие изменения через WS. |
| FOCUS и история | FOCUS → pause → resume → manual finish; снимки второго repository обновляются, startedAt сохраняется. Оба читают одинаковый итог и находят его в истории; дальнейшая правка задачи не меняет сохранённый итог. |
| BREAK и закрытие | Владелец запускает BREAK, второй участник закрывает комнату. Оба получают CLOSED, BREAK отменён с ROOM_CLOSED, изменения заблокированы; затем logout. |

Подтверждённый прогон на native Windows / Temurin `21.0.12.101` завершился `BUILD SUCCESSFUL` за 48 секунд, exit 0. XML проверены отдельно: `test` — **143**, `backendAuthSmoke` — **1**, `backendRoomSmoke` — **1**; в каждом наборе 0 failures/errors/skips. SHA-256 всех 10 копий fixtures повторно совпали с общими образцами. Это два repository в одной тестовой JVM, без Compose-окон.

Окружение R1: Java JAR в WSL на 8090 → новый PostgreSQL 17.11 на 55436, отдельная tmpfs-БД `focusduo_room_stage6`; backend production-исходники и `pom.xml` не изменялись относительно `980a31f`. После smoke SQL подтвердил `open_rooms=0`, `active_memberships=0`, `unrevoked_sessions=0`, а также 2 раунда, 2 записи истории и 2 миграции. То же окружение затем использовалось для R2/R3 и очищено после их завершения, как описано ниже. Подробный отчёт — [ROOM_INTEGRATION.md](ROOM_INTEGRATION.md).

R1 не проверяет Compose-окно, TTL-истечение/WS 4401, автоматический reconnect, восстановление после рестарта, потерянный ответ/replay, LAN или все ограничения прав. Нормальное завершение WS в нём не проверяется отдельным assertion кода close. Результат сохранён в `desktop/build/test-results/backendRoomSmoke/`, отдельно от XML обычных `test` и `backendAuthSmoke`. Ручной reconnect, повторный login и отзыв сессии при открытом WS проверены отдельно в R3.

## R2. Повтор REST-авторизации на desktop 6

Адаптированный [`realRepositorySmoke`](../../backend/scripts/kotlin-auth-smoke/README.md) прошёл против того же изолированного Java backend: `BUILD SUCCESSFUL` за 6 секунд, exit 0. Все repository этого harness используют `realtimeEnabled=false`, чтобы после внешнего отзыва токена проверить именно следующий HTTP 401 и переход в `EXPIRED`; WS 4401 не может очистить сессию раньше этого запроса. Сохраняются проверки нормализации, ошибок входа/регистрации, чтения подготовленной комнаты, повторного login и logout.

После закрытия комнаты отдельно проверены серверное членство и локальный снимок для отображения: сырой `GET /api/v1/rooms/current` возвращает `{"room":null}`, а repository сохраняет известный финальный `CLOSED` после чтения комнаты по ID. Это не восстанавливает членство и не открывает комнату. Подготовка/закрытие комнаты в R2 используют вспомогательный JDK HTTP; R2 не заменяет WS-проверки R1 или будущую приёмку UI. Команды — в README harness, результат — [ROOM_INTEGRATION.md](ROOM_INTEGRATION.md).

## R3. Ручной reconnect, восстановление входа и завершение WS-сессии

[`realRoomLifecycleSmoke`](../../backend/scripts/kotlin-room-smoke/README.md) прошёл на том же Java backend: первый и усиленный финальный прогоны завершились `BUILD SUCCESSFUL` за 6 секунд, exit 0. Финальная версия заранее подписывается на новый переход `CONNECTING`/`SYNCHRONIZING` при reconnect и после отзыва сессии отдельно проверяет `/me` второго участника. Это отдельная opt-in исполняемая проверка с пятью контрольными точками; она не увеличивает количество JUnit-тестов:

1. Ручной reconnect запускает новый цикл синхронизации, получает полный снимок и последующее изменение партнёра.
2. После logout партнёр меняет комнату; новый login и bootstrap восстанавливают прежнее членство и пропущенное изменение без нового join или изменения revision.
3. Внешний отзыв токена переводит repository в `EXPIRED` через уже открытый WS без REST-refresh. Комната/история очищаются, изменения блокируются; токен получает HTTP 401, второй участник остаётся подключён и успешно читает `/me`.
4. Финальный CLOSED сохраняется после refresh и ручного reconnect; соединение остаётся `NONE`, серверный current равен `null`, команды запрещены.
5. Собственная комната закрыта, активные сессии завершены.

R3 проверяет реакцию repository на WS-отзыв; сырой close-код отдельно не сравнивается. Принудительный сетевой разрыв, автоматическое восстановление, сон/пробуждение, TTL-истечение, UI и LAN в этот сценарий не входят. Команды повторения — в README harness; [полный отчёт](ROOM_INTEGRATION.md) сохраняет его границы отдельно от R1/R2.

После всех R1–R3 SQL снова подтвердил `open_rooms=0`, `active_memberships=0`, `unrevoked_sessions=0`, 2 раунда, 2 записи истории и 2 миграции. Временный серверный helper остановлен с exit 0, собственный PostgreSQL-контейнер с tmpfs удалён; 8090 и 55436 проверены свободными. В Docker остался пользовательский `focusduo-db-1`, healthy на 5434. Корневой `.env` не изменён и остаётся исключённым из Git.

## R4–R6. Восстановление и безопасный повтор: desktop 7

11 октября на базе `67b66c5` повторён `test check backendAuthSmoke backendRoomSmoke`: **BUILD SUCCESSFUL, 39 секунд, exit 0**. R4 подтверждён XML отдельно: 171 обычный тест, 1 auth smoke и 1 room smoke, везде 0 failures/errors/skips; все 10 fixture SHA-256 совпали. Прежние `realRepositorySmoke realRoomLifecycleSmoke` дополнительно прошли вместе за 11 секунд, exit 0. Lifecycle harness теперь ожидает новую `recoveryVersion` и возврат CLOSED-соединения в NONE после асинхронного перечитывания membership.

R5 — [`realRecoverySmoke`](../../backend/scripts/kotlin-recovery-smoke/README.md), **PASS за 7 секунд, exit 0**. Настоящие ответы 200 на create/task дочитаны транспортом после сохранения на Java и потеряны только для repository. Повтор сохранил точные method/path/body/key/revision, второе действие не возникло, новая правка партнёра не откатилась. Отдельная новая команда задержана до правки партнёра и получила настоящий 409 со snapshot; следующее явное действие использовало новый ключ и актуальную revision. Конфликт replay запроса, который не был сохранён сервером, в этот прогон не входит.

R6 — [`realRestartSmoke`](../../backend/scripts/kotlin-restart-smoke/README.md), **PASS за 43 секунды, exit 0**. После двух принудительных остановок Java оба repository автоматически прошли REST/WS-восстановление с новой `recoveryVersion`, без ручного reconnect/bootstrap. Сохранились PAUSED с задачей, membership, revision и замороженными временными полями; после resume и второго рестарта сохранились RUNNING и исходный endsAt. Явный `recoverAfterResume()` дал новый цикл синхронизации без изменяющих запросов; manual finish сохранил одну одинаковую историю обоим. Реальный сон ОС и просроченный ELAPSED этим сценарием не проверялись.

Среда: native Windows / Temurin 21.0.12.101 → WSL JAR:8090 → отдельный PostgreSQL 17.11 с tmpfs:55437. Для R5/R6 использован новый контроллер с изоляцией Spring/JVM overrides; пользовательская БД и `.env` в стенде не используются. Подробности, точные команды и различие первоначального R4-окружения и последующего R5/R6 — в [RECOVERY_INTEGRATION.md](RECOVERY_INTEGRATION.md). R5/R6 — отдельные opt-in исполняемые проверки; они не увеличивают число JUnit-тестов и не включены в CI.

После R5/R6 SQL подтвердил 0 OPEN rooms, 0 active memberships, 0 unrevoked sessions, 1 раунд, 1 запись истории и 2 миграции. Поколение контроллера 3 подтверждает два рестарта после первого запуска. Helper остановлен с exit 0, его контейнер удалён, marker содержит `stopped:true`; 8090 и 55437 проверены свободными. Пользовательский `focusduo-db-1` остался healthy на 5434, `.env` не изменён и исключён из Git.

На базе R4–R6 оставалось замечание из [read-only review](RECOVERY_INTEGRATION.md#замечание-kotlin-разработчику-обработка-сигнала-пробуждения): при остановленном во сне nanoTime двухсекундный барьер в RealWorkspace мог отбросить уже обнаруженный календарный разрыв. Оно исправлено desktop PR #14 (`8fdb392`): production coordinator допускает восстановление при достаточном интервале по любому из двух часов. Исторический отчёт сохраняет исходное условие; это исправление кода не подтверждает физический сон Windows. Вызов `recoverAfterResume()` в R6 сам по себе не проходил через detector и барьер UI.

## R7–R8. Приёмка исправления wake: desktop PR #14

На базе `a1e557f` повторный `test check backendAuthSmoke backendRoomSmoke` завершился **BUILD SUCCESSFUL за 27 секунд, exit 0**. XML подтвердили отдельно **185 обычных тестов, 1 auth smoke и 1 room smoke**, во всех наборах 0 failures/errors/skips; все 10 fixture SHA-256 совпали с общими. Новый набор включает 13 `ResumeRecoveryCoordinatorTest` и 1 `ResumeRecoveryRepositoryTest`: точное прежнее условие wall +61 секунда / nano +1 секунда, оба порядка heartbeat/foreground, границы ограничения частоты и путь до немедленного запрета новых команд repository. Coordinator в тестах совпадает с используемым RealWorkspace; repository-проверка использует управляемый HTTP, без Java и физического сна. Удаление незаполненных `STAGE7_TEST_RESULT` / `STAGE7_COMPOSE_RESULT` в desktop-документации проверено отдельно; оно не является новой GUI-проверкой.

R8 — все четыре неизменённых opt-in harness повторно прошли на новом desktop-коммите, каждый с exit 0 и итоговым PASS: `realRepositorySmoke` — **6 секунд, 5 checkpoints**; `realRoomLifecycleSmoke` — **6 секунд, 5 checkpoints**; `realRecoverySmoke` — **6 секунд, 4 checkpoints**; `realRestartSmoke` — **42 секунды, 5 checkpoints**. Подтверждены REST-auth, lifecycle WS, точный replay и реальный 409, автоматическое восстановление PAUSED/RUNNING после двух SIGKILL Java. Поколения контроллера: 1 → 2 → 3. Явный `recoverAfterResume()` в restart harness по-прежнему обходит coordinator Compose и не является проверкой физического сна; production coordinator отдельно покрыт R7.

После R7/R8 SQL подтвердил **0 OPEN rooms, 0 active memberships, 0 unrevoked sessions, 3 раунда, 3 записи истории и 2 миграции**: FOCUS/BREAK базового room smoke и FOCUS restart harness. Контроллер завершился с exit 0 и `STOPPED`, собственный Java-процесс остановлен, PostgreSQL-контейнер удалён, 8090/55437 свободны. Пользовательский `focusduo-db-1` остался healthy на 5434; `.env` не изменён и исключён из Git. Локальные журналы находятся в `.local/wake-acceptance-1/{auth,room,recovery,restart}-smoke.log`; команды, результаты и границы — в [WAKE_ACCEPTANCE.md](WAKE_ACCEPTANCE.md). Прежние R1–R6 сохраняют собственные commits и условия проверки; R7/R8 не закрывают полную UI/OS-приёмку.

## Первый сетевой сценарий: вход → комната → два WS → задача

Эта проверка даёт контрольную точку для сетевого режима. Основной путь регистрации, create/join и целей/задач пройден в двух Compose-окнах в M1. Перезапуск клиента с восстановлением current и отдельная фиксация wire-полей в этом GUI-прогоне не выполнялись; результаты M1 и repository-проверок не заменяют все шаги K1.

| Шаг | Действие | Что должно наблюдаться |
| --- | --- | --- |
| K1.1 | Зарегистрировать A и B с разными username через реальный клиент; проверить `/me` и `/rooms/current`. | Два разных userId; после регистрации `201 AuthSession`, текущая комната каждого — `null`. Пароль и токен хранятся только в памяти. |
| K1.2 | A создаёт комнату. | HTTP 200, `revision=1`, владелец A, один участник, `round=null`. Клиент хранит roomId и полученный непрозрачный code. |
| K1.3 | A открывает WS этой комнаты с bearer-заголовком handshake. | Первое сообщение — полный `ROOM_SNAPSHOT`. Нет JSON-команды subscribe, STOMP или SockJS. |
| K1.4 | B присоединяется по code и открывает свой WS. | В комнате ровно A и B. A получает изменение через WS; B получает полный первоначальный snapshot своего соединения. Повторный join B не добавляет третьего участника. |
| K1.5 | B меняет свою цель, создаёт задачу и отмечает её выполненной. | REST-команды возвращают полный snapshot. Оба клиента сходятся на одной revision и содержимом, без ручного обновления A. B может править свою карточку; A не получает право менять задачи B. |
| K1.6 | Перезапустить только клиент A, снова войти тем же пользователем и запросить current. | A восстанавливает ту же комнату. Новый WS получает полный snapshot до разрешения изменений. Закрытие окна не освобождает членство и не закрывает комнату. |

У всех изменяющих комнату команд есть новый `Idempotency-Key: UUID`. У команд существующей комнаты дополнительно есть `X-Room-Revision`; create/join этого заголовка не требуют. Токен передаётся в `Authorization: Bearer …` и для HTTP, и в WS-handshake. Auth/GET/history не требуют ключа и revision. Не добавляйте токен в URL, `expectedRevision` в JSON, `If-Match` или cookie-авторизацию.

Снимки сравниваются внутри roomId: меньшая revision игнорируется; равная не откатывает данные, хотя более свежий `serverNow` может уточнить часы. Ответ REST и одноимённое WS-событие могут прийти в разном порядке. Не требуйте секундных событий от сервера и не выводите из этого протокола присутствие друга онлайн.

## Остальные сценарии приёмки

Для каждого случая сохраните исходные roomId/revision/roundId и ожидаемый результат. После закрытия комнаты создавайте новую; отдельного leave в v1 нет. Успех серверного теста или ручного HTTP-запроса не заменяет проверку реакции Kotlin UI.

### K2. Таймер, результат и история

В комнате с двумя участниками A запускает FOCUS. Для живого прогона можно выбрать минимальные договорённые длительности 300/60 секунд; API не допускает раунд на несколько секунд. Только владелец управляет таймером и настройками; изменение настроек запрещено в RUNNING/PAUSED. Отказ проверяется также на сервере, а не только disabled-кнопкой.

Во время FOCUS измените задачу B, затем A ставит паузу. В PAUSED `endsAt=null`, остаток и активное время заморожены. После resume `startedAt` остаётся прежним; оба UI считают время от `serverNow`/`endsAt` с монотонными локальными часами. Запишите фактическое расхождение отображений и задержку сети; не вводите неоговорённый тайминговый SLA.

Завершите раунд вручную до срока. Оба клиента показывают `COMPLETED/MANUAL`, `remainingMs=0`, заполненный `endedAt` и фактический `activeElapsedMs` без пауз. В истории у обоих одна и та же запись по roundId. Затем поменяйте цель/задачу в комнате: старая история, включая counts, не меняется. Запустите BREAK вручную после COMPLETED FOCUS; его результат имеет отдельный kind. Попытка BREAK до завершённого FOCUS или после последнего BREAK отклоняется по контракту.

### K3. Разрыв соединения и поздние сообщения

Во время активного раунда разорвите WS только у B через отладчик или тестовый механизм клиента. A продолжает работать. У B отображается реальное состояние сети; ошибка не включает деморежим. После reconnect B ждёт нового полного snapshot, затем разрешает изменения. Комната и таймер сохраняются, лишнего членства не возникает.

Проверьте задержанное событие старого соединения и, после перехода в новую комнату, событие старого roomId: они не должны откатывать UI. Отдельно выполните сон/пробуждение ПК и повторную синхронизацию. Если клиент ещё не позволяет воспроизвести задержку/разрыв, соответствующая строка остаётся `NOT_RUN`; проверка только нормального подключения не заменяет её. Не меняйте firewall или сетевые службы автоматически ради этого сценария.

### K4. Потерянный ответ, replay и 409

До отправки команды зафиксируйте method/path/body, ключ и исходную revision. В тестовом механизме `RealRepository` дайте серверу выполнить команду, но отбросьте её ответ для клиента. Это должна быть потеря ответа, а не отказ до отправки. Повторите **ровно тот же запрос** с тем же ключом и исходной revision: задача/раунд создаётся один раз. Сделайте между выполнением и повтором другое изменение комнаты; более старый сохранённый ответ не должен откатить UI. Иное тело с прежним ключом даёт `409 IDEMPOTENCY_KEY_REUSED`, `snapshot=null`.

Для `REVISION_CONFLICT` временно задержите применение WS-снимка B: A изменяет комнату, B отправляет новое действие со своей устаревшей revision. Ожидаются 409 и актуальный snapshot для B. Клиент применяет его и предлагает повторить действие. Только новое действие получает новый ключ; автоматический цикл с подменой revision недопустим. Проверка этой логики через `requests.http` подтверждает backend, но для Kotlin нужны те же наблюдения в реальном repository/UI.

### K5. Logout, 401 и закрытие WS 4401

A выполняет logout текущей сессии. Связанный WS закрывается кодом `4401`, старый токен получает HTTP 401. Клиент очищает авторизацию и предлагает вход; B остаётся в OPEN-комнате. После нового login A восстанавливает current и WS. Logout не является close.

Истечение сессии проверяйте отдельным прогоном: перезапустите тестовый backend с коротким `FOCUSDUO_SESSION_TTL`, например `PT30S`, **до** выдачи новых токенов; запишите `expiresAt`. После срока ожидаются 401 и закрытие открытого WS с `4401` при серверной проверке сессии. Зафиксируйте фактическое время события. Верните обычный TTL после проверки. Существующий токен сохраняет записанный срок; менять часы ОС не нужно. Управляемые часы серверных тестов не являются публичным API клиента.

Живой TTL PT30S уже пройден в desktop 8.2: SQL подтвердил срок, Compose сам показал вход с пустым паролем и сообщением expiry, без logout/refresh/reconnect. Это сохранённый PASS; отдельное wire-наблюдение кода закрытия и сохранение второй сессии относятся к более широкому K5.

### K6. Перезапуск backend с той же БД

Проверьте отдельно PAUSED и просроченный RUNNING. Остановите только Java-процесс, сохраните PostgreSQL и volume. После запуска с той же БД и действующей сессией клиент проходит reconnect и получает актуальное состояние. Для PAUSED сохраняются roundId, startedAt, остаток и активное время; дальнейший resume не начинает раунд заново.

Для RUNNING запишите `endsAt` и оставьте backend выключенным до истечения срока; в живом прогоне используйте допустимые 300 секунд. После восстановления ожидаются `COMPLETED/ELAPSED`, `endedAt` равный прежнему сроку, `remainingMs=0` и ровно одна история у обоих. UI не создаёт историю при достижении собственного нуля. Старая изменяющая команда может получить `REVISION_CONFLICT` с уже зафиксированным завершением; после нового чтения результат сохраняется. Не удаляйте БД и не сокращайте ограничения контракта для ускорения проверки.

Desktop 8.2 подтвердил это для BREAK 60 секунд, включая offline 00:00 в обоих свежих окнах и автоматические итоги после start Java. F1 отдельно подтвердил FOCUS 300 секунд в компонентном GUI-harness: до рестарта SQL сохранял RUNNING и отсутствие истории, после рестарта — единственный `COMPLETED/ELAPSED`, исходный deadline и 300000 мс активного времени. Оба `RealApp` автоматически открыли исходный результат; поздняя правка задачи историю не изменила. Штатный `Main` и wake-путь в F1 не запускались.

### K7. CLOSED и новая комната

B явно закрывает комнату во время RUNNING или PAUSED. Оба получают CLOSED snapshot, активный раунд — `CANCELLED/ROOM_CLOSED`; затем WS штатно закрываются (в текущей реализации код 1000). UI показывает отмену, прекращает reconnect в закрытую комнату, current становится `null`. Закрытие уже COMPLETED-раунда сохраняет прежний результат.

Бывшие участники читают старый snapshot и историю; новые изменения с актуальной revision отклоняются как `ROOM_CLOSED`. Повтор ранее успешного запроса с исходным ключом может вернуть сохранённый OPEN snapshot: он не должен заново открыть закрытую комнату в UI. Оба пользователя теперь могут создать/занять новую комнату. Поздние кадры предыдущего roomId игнорируются. После завершения проверки выполните logout; учётные записи и история сохраняются в тестовой БД.

## Два компьютера и LAN

Сначала подтвердите K1 на одном ПК. Затем выберите один серверный компьютер; оба клиента обращаются к **его** LAN-адресу. `127.0.0.1` на компьютере друга указывает на компьютер друга.

Для backend, работающего нативно в Windows, явно задайте `SERVER_ADDRESS=0.0.0.0`, оставьте порт 8090 и используйте его реальный LAN-IP в обоих клиентах. PostgreSQL не требуется публиковать в LAN. С компьютера друга сначала проверьте ответ 401 от `/api/v1/me` без токена, затем выполните K1 с двумя клиентами и оба WS. HTTP-доступность не доказывает успешный WS-handshake.

Для backend в WSL успешный Windows → localhost smoke подтверждает только доступ на одном ПК. LAN-доступ может потребовать настройки сетевого режима WSL, маршрута или перенаправления и правил Windows. IPv4-параметр JVM сам по себе не публикует сервер в LAN. Запишите фактический вариант и проверьте его отдельно; не добавляйте port proxy и firewall-правила автоматически. Если соединение блокируется, зафиксируйте адрес, время и наблюдаемую ошибку, затем согласуйте необходимое изменение сети с владельцем компьютера. Для удалённого соединения вне локальной разработки используйте HTTPS/WSS.

## Реестр доказательств

`PASS` требует фактического прогона и ссылки на доказательство; `FAIL` — воспроизведённой ошибки; `PARTIAL` — часть требуемого GUI-сценария подтверждена, оставшиеся действия перечислены; `NOT_RUN` — GUI-сценарий ещё не пройден. S1–S3 сохраняют результаты второго этапа; P1–P4 — серверной подготовки третьего этапа, C1–C4 — прежних CI. K0/A1–A3 и R1–R3 относятся к предыдущим auth/room-проверкам; R4–R6 — к базе `67b66c5`; R7–R8 — к `a1e557f`; M1–M4 — к `30c8235` с desktop `8fdb392`. Именно в историческом GUI-прогоне M1–M4 полный Maven verify, Gradle check и harness не повторялись. D1/D2/D3 и F1 — отдельные прогоны на версиях и в объёме, указанных в строках ниже; их результаты не переносятся автоматически между версиями. CI новой ветки `feat/backend-final-integration` пока не запускался; push выполняет владелец.

| ID | Проверка | Статус / этап доказательства | Основание / что ещё требуется |
| --- | --- | --- | --- |
| S1 | Backend Linux/WSL `verify` | PASS — этап 2 | 53 теста: 27 unit + 26 IT, 0 failures/errors/skips; PostgreSQL Testcontainers. [HANDOFF](HANDOFF.md). |
| S2 | Backend Windows `verify` | PASS — этап 2 | Те же 53 теста; отдельная PostgreSQL через WSL Compose и `FOCUSDUO_TEST_DB_*`. Это внешний DB-режим Windows, не Windows Testcontainers. |
| S3 | JAR + Python HTTP smoke | PASS — этап 2 | По 8 PASS из Linux и Windows; Windows → WSL localhost с IPv4-параметром. Kotlin в этом прогоне не участвовал. |
| P1 | Локальная конфигурация и чистый запуск Compose | PASS — подготовка этапа 3 | `init-local.ps1` создал исключённый из Git `.env`; свежий Compose-проект `focusduo-stage3` запустил PostgreSQL 17.11. Использован JAR с неизменённой production-реализацией. |
| P2 | HTTP smoke из Windows | PASS — подготовка этапа 3 | 8 PASS против этого JAR и отдельной Compose-БД; сценарий выполнял Python-клиент. [Команды](../../backend/scripts/README.md). |
| P3 | Два WS через отдельный CLI | PASS — подготовка этапа 3 | `WebSocketSmoke.java` отдельно запущен в Windows и WSL: по 8 PASS. В каждом прогоне реальные два WS, начальные снимки/изменения, reconnect, 409, logout → 4401 и CLOSED snapshot перед close 1000. Это два логических клиента CLI, без Kotlin UI. [Команды](../../backend/scripts/README.md). |
| P4 | Два принудительных завершения JAR с той же БД | PASS — подготовка этапа 3 | Локальный harness дал 3 PASS: после первого SIGKILL сохранились PAUSED, задачи, сессии, членства, revision и исходный идемпотентный ответ запуска; после resume и второго SIGKILL сохранился RUNNING с первоначальным `endsAt`; ручной finish дал ровно одну одинаковую историю обоим пользователям. Harness не коммитится и не входит в CI. |
| C1 | Backend CI после PR #4 | PASS — подготовка этапа 3 | [`38072287807`](https://github.com/KDvibers/FocusDuo/actions/runs/38072287807): успешны `verify`, HTTP и WS smoke. Это прежний серверный CI, без живой Kotlin-проверки A1. |
| C2 | Backend CI после PR #9 | PASS — предыдущая auth-интеграция | [`38078661860`](https://github.com/KDvibers/FocusDuo/actions/runs/38078661860) для `a71c34d` завершён успешно. Это отдельный исторический серверный CI; текущие R1/R2 выполнены локально. |
| C3 | Backend CI после PR #11 | PASS — предыдущая room-интеграция | [`38079888693`](https://github.com/KDvibers/FocusDuo/actions/runs/38079888693) для `516020e` завершён успешно. Текущие R4–R6 выполнены локально и не подменяют CI новой ветки. |
| C4 | Backend CI после PR #13 | PASS — предыдущая recovery-интеграция | [`38082205454`](https://github.com/KDvibers/FocusDuo/actions/runs/38082205454) для `8310e23` завершён успешно. Это отдельный CI объединённой recovery-ветки, не результат новой wake-приёмки. |
| K0 | Десериализация shared fixtures Kotlin-клиентом | PASS — повтор после PR #8 | 17 `ContractFixturesTest` в составе 112 обычных тестов, 0 failures/errors/skips; все 10 SHA-256 совпали с общими fixtures. Windows / Temurin 21.0.12.101, база `7acb617`; XML и команда указаны выше. |
| A1 | Kotlin `RealRepository`/CIO: register/me/current/logout/login | PASS — живой Java backend | `backendAuthSmoke`: 1 тест, 0 failures/errors/skips; Windows Kotlin → WSL JAR:8090 → отдельный PostgreSQL 17.11:55435. Проверены профиль, current `null`, повторный login с тем же userId и logout; без UI, команд комнаты и WS. |
| A2 | Kotlin: существующая комната, внешний отзыв токена, 401 и logout | PASS — живой Java backend | [`realRepositorySmoke`](../../backend/scripts/kotlin-auth-smoke/README.md): BUILD SUCCESSFUL, 6 секунд, exit 0, включая повтор после исправления очистки. Два production repository/CIO, current/known room, `EXPIRED` после 401, восстановление членства, `SIGNED_OUT` и отзыв при logout; второй участник и OPEN-комната сохраняются до очистки. Команды подготовки комнаты выполнял JDK HTTP. [Подробный отчёт](AUTH_INTEGRATION.md). |
| A3 | Очистка harness после потерянного ответа создания комнаты | PASS — целевая проверка ошибки | 11 октября: backend сохранил комнату и ответил 200, proxy оборвал ответ; ожидаемый `IOException`, exit 1. Очистка: GET current ×1, close 200 ×1; SQL `open_rooms=0`, `active_memberships=0`, `unrevoked_sessions=0`. Это проверка очистки вспомогательного HTTP setup, без Kotlin retry/WS/UI. [Отчёт](AUTH_INTEGRATION.md). |
| R1 | Kotlin repository: команды комнаты, два WS, раунды, история, CLOSED | PASS — живой Java backend | `backendRoomSmoke`: 1 тест, 0 failures/errors/skips. Вместе с ним прошли 143 обычных теста, `check` и 1 auth smoke; общий запуск 48 секунд, exit 0. Два repository в одной Windows JVM → WSL JAR:8090 → PostgreSQL 17.11:55436; без Compose-окон. [Отчёт](ROOM_INTEGRATION.md). |
| R2 | Адаптированный REST-auth smoke desktop 6 | PASS — живой Java backend | `realRepositorySmoke`: BUILD SUCCESSFUL, 6 секунд, exit 0. `realtimeEnabled=false` гарантирует проверку именно HTTP 401; серверный current `null` и сохранённый repository CLOSED проверены отдельно. Вспомогательные команды комнаты — JDK HTTP, без WS/UI. [Отчёт](ROOM_INTEGRATION.md). |
| R3 | Ручной reconnect, relogin, WS-отзыв и сохранение CLOSED | PASS — живой Java backend | [`realRoomLifecycleSmoke`](../../backend/scripts/kotlin-room-smoke/README.md): финальный усиленный повтор BUILD SUCCESSFUL, 6 секунд, exit 0. Пять контрольных точек отдельной исполняемой проверки, без UI: новый цикл sync, восстановление без join, `EXPIRED` через WS/peer `/me`, CLOSED/NONE и очистка. [Отчёт](ROOM_INTEGRATION.md). |
| R4 | Desktop 7: обычные тесты и повтор живых auth/room проверок | PASS — живой Java backend | `test check backendAuthSmoke backendRoomSmoke`: 39 секунд, exit 0; XML 171 + 1 + 1, 0 failures/errors/skips. Прежние auth/lifecycle harness вместе PASS за 11 секунд. [Отчёт](RECOVERY_INTEGRATION.md). |
| R5 | Потерянные ответы create/task, точный replay и 409 | PASS — живой Java backend | `realRecoverySmoke`: 7 секунд, exit 0. Исходные запрос/ключ/revision сохранены; второй эффект и откат правки партнёра отсутствуют; новая задержанная команда получила 409, осознанное новое действие — новый ключ. [Отчёт](RECOVERY_INTEGRATION.md). |
| R6 | Два рестарта Java и сигнал пробуждения repository | PASS — живой Java backend | `realRestartSmoke`: 43 секунды, exit 0. Автоматическое восстановление обоих клиентов, PAUSED/RUNNING с исходными полями, явный resume-сигнал без команд и одна равная история. Без OS suspend/UI/ELAPSED. [Отчёт](RECOVERY_INTEGRATION.md). |
| R7 | Исправление wake: обычные тесты и живые auth/room smoke | PASS — живой Java backend и clock-тесты | `test check backendAuthSmoke backendRoomSmoke`: BUILD SUCCESSFUL, 27 секунд, exit 0 на `a1e557f`. XML: 185 + 1 + 1, 0 failures/errors/skips; в 185 входят 13 coordinator-тестов и 1 repository seam-тест с управляемым HTTP. Все 10 fixture SHA-256 совпали. [Отчёт](WAKE_ACCEPTANCE.md). |
| R8 | Повтор четырёх opt-in harness после wake-fix | PASS — живой Java backend | Неизменённые auth/lifecycle/recovery/restart harness: 6/6/6/42 секунды, 5/5/4/5 checkpoints, каждый exit 0 и итог PASS на `a1e557f`. Два SIGKILL, автоматическое восстановление, одна история restart-раунда. SQL 0 OPEN/active/unrevoked; собственный стенд удалён, пользовательская БД и `.env` сохранены. Без физического сна и Compose с Java. [Отчёт](WAKE_ACCEPTANCE.md). |
| D1 | Desktop 8.1: GUI lifecycle и voluntary logout | **PASS — PR #16** | Два штатных Compose JVM с Java/PostgreSQL, обычный сценарий и отдельный свежий повтор исправленного logout; 187 обычных тестов. [Отчёт](../../desktop/docs/STAGE8_1_INTEGRATION.md). |
| D2 | Desktop 8.2: recovery/navigation и просроченный BREAK | **PASS — PR #18** | Финальные 199 обычных + 2 живых теста, два свежих Compose JVM: offline 00:00 → автоматический BREAK/ELAPSED, одна история 60000 мс и исходный deadline. Четыре harness — прежний baseline до navigation-fix. [Отчёт](../../desktop/docs/STAGE8_2_RECOVERY.md). |
| D3 | Desktop 8.2: живой TTL PT30S | **PASS** | Immutable `0da9c6c`, auth-код не менялся: SQL lifetime 30 секунд, автоматический вход с пустым паролем и expiry-сообщением без ручного logout/refresh, без reconnect-loop. [Отчёт](../../desktop/docs/STAGE8_2_RECOVERY.md#отдельный-короткий-ttl-стенд-sql-и-ui-pass). |
| F1 | Просроченный FOCUS: Java выключена до deadline | **PASS — компонентная приёмка** | Два production `RealApp`/repository в одном JVM, API bootstrap. Оба UI: offline 00:00/ожидание → автоматический итог 5 минут; revision 8 → 9, одна `COMPLETED/ELAPSED`-история, исходный endedAt, 300000 мс. Поздняя правка задачи не изменила историю. SQL после close/logout: CLOSED/revision 11, 1 round/1 history, 0 active memberships/0 unrevoked sessions; core/UI exit 0. Отдельно прошли текущие test/check 199/0/0/0, Maven package 27/0/0/0 и компиляция harness. Full verify, прежние 4 harness, auth/room smoke, TTL/BREAK и сон не перезапускались. [Отчёт и доказательства](EXPIRED_FOCUS_ACCEPTANCE.md). |
| M1 | Два реальных Compose-окна: комната, цели/задачи, запуск FOCUS | **PASS** | Пользователь зарегистрировал A/B и соединил комнату; GUI подтвердил участников, роли, собственные цели/задачи, правки в другом окне и FOCUS. Отдельный WS-only маршрут доставки не изолировался. [Отчёт](COMPOSE_ACCEPTANCE.md). |
| M2 | Физический сон Windows в PAUSED | **PASS** | Modern Standby 73,505 с по событиям 506/507; у обоих до/после 24:45. Следующая правка A видна B, revision 9 → 10. Foreground переключался, точное время готовности не измерено. [Отчёт](COMPOSE_ACCEPTANCE.md). |
| M3 | Физический сон Windows в RUNNING | **PASS** | Modern Standby 117,050 с. A: −208 с за 207,603 с между наблюдениями; B: −207 с за 207,235 с. Контекст/задачи сохранены, Java не перезапускался. Разница учитывает также время вне сна. [Отчёт](COMPOSE_ACCEPTANCE.md). |
| M4 | Ручной итог, неизменная история, BREAK, close B, logout | **PASS** | FOCUS 4 мин 31 с без пауз; правка B не изменила старый итог. BREAK 37 с прерван закрытием B; оба видят два результата, CLOSED и отключённые команды, logout обоих выполнен. [Отчёт](COMPOSE_ACCEPTANCE.md). |
| K1 | Реальный вход/current/create/join, два WS, задача | **PARTIAL** | M1 подтвердил основной GUI-путь и ограничения роли; R1 — два WS на уровне repository. Перезапуск клиента с восстановлением current (K1.6), повторный join и отдельная фиксация wire-полей в GUI ещё не выполнены. |
| K2 | Kotlin: таймер, BREAK, результат, история | **PARTIAL** | M1–M4/D1 подтвердили обычный GUI lifecycle; D2 — автоматический BREAK/ELAPSED и навигацию к свежему результату; F1 — автоматический FOCUS/ELAPSED в двух компонентных `RealApp`. Остаются отказ неправильных переходов/настроек и прав на сервере в совместном сценарии. |
| K3 | Kotlin: reconnect, старые события, сон/пробуждение | **PARTIAL** | M2/M3 подтвердили Modern Standby на `8fdb392`; foreground мог запустить восстановление, latency не измерена. R3/R6/R8 — repository reconnect/restart, D2 — GUI recovery после остановки Java. Разрыв сети одного клиента и задержанные события старого соединения/roomId в UI ещё не приняты. |
| K4 | Kotlin: потерянный ответ, replay, 409 | **NOT_RUN** | Repository-часть подтверждена R5: точный replay create/task, один эффект, защита от отката и 409 новой команды. Реакция UI, сохранение/проверка черновика и конфликт replay несохранённой команды ещё не приняты. |
| K5 | Kotlin: logout/expiry, 401/4401 | **PARTIAL** | M4/D1 — GUI logout, включая исправление гонки добровольного выхода; D3 — живой TTL и автоматический возврат ко входу. A2/R2/R3 — HTTP 401 и WS expiry repository. Отдельная GUI/wire-фиксация 4401 с сохранением второй сессии не закрыта общим TTL-сценарием. |
| K6 | Kotlin при рестарте backend | **PARTIAL** | R6/D2 подтвердили PAUSED/RUNNING recovery, свежие два Compose JVM — просроченный BREAK. F1 подтвердил просроченный FOCUS, автоматический итог в обоих `RealApp`, одну ELAPSED-историю с исходным endedAt. Граница F1 — один тестовый JVM с API bootstrap, без штатного `Main`/`RealWorkspace`; всю матрицу штатного UI и восстановление после физического сна на новом desktop-коммите это не закрывает. |
| K7 | Kotlin: CLOSED и переход в новую комнату | **PARTIAL** | M4 подтвердил GUI CLOSED, отмену BREAK, два результата и отключённые команды без бесконечного восстановления. R3/R4 — NONE/current `null` на уровне repository. Переход UI в новую комнату и поздние события старой ещё не приняты. |
| L1 | Два Kotlin-клиента на разных ПК | **NOT_RUN** | LAN и выбранная топология Windows/WSL не проверялись. |

В P4 сервер восстанавливался до истечения RUNNING и завершался вручную. Восстановление уже просроченного раунда как `ELAPSED` и Kotlin reconnect после рестарта этим прогоном не проверялись. Серверный `ELAPSED` покрывался unit/RestartIT второго этапа; это сохранённое доказательство, а не новый запуск. После подготовки временные серверы и Compose-контейнер с собственным volume удалены; корневой `.env` сохранён локально и остаётся исключённым из Git.

Для каждого нового прогона добавьте запись, сохраняя предыдущие результаты:

```text
Дата/время и часовой пояс:
Backend commit / desktop commit / dirty-изменения:
ОС, JDK, способ запуска backend и БД:
Клиенты A/B: ОС, один ПК или два, native Windows или WSL:
Адрес сервера, схема HTTP(S)/WS(S), длительности и TTL без секретов:
ID сценария и точные шаги:
Факт: HTTP status + code; requestId / X-Request-Id; WS close code:
roomId / roundId; revision до/после; serverNow/endsAt/endedAt:
PASS / FAIL / NOT_RUN и краткое обоснование:
Отчёт теста, обезличенный фрагмент события или скриншот; ссылка на исправление:
```

Не сохраняйте пароль, `accessToken`, Authorization header, `.env` или полный `AuthSession` в отчёте, HAR, скриншоте или репозитории. Для ошибки достаточно requestId, статуса/code и очищенных диагностических данных. Idempotency-Key и исходная revision могут быть записаны для воспроизведения; они не заменяют bearer-авторизацию. Секреты тестового пользователя также не нужны в отчёте.

## Критерий передачи третьего этапа

Подготовленные скрипты, этот план и успешные серверные тесты не закрывают этап. Нужны фактические результаты двух настоящих Kotlin-клиентов как минимум для первого сетевого сценария, затем зафиксированная приёмка остальных согласованных случаев и явный список `FAIL`/`NOT_RUN`. Если LAN ещё не проверена, её статус сохраняется отдельно; локальную проверку нельзя объявлять работой двух компьютеров. Итоговый HANDOFF и PR должны ссылаться на реальные commits и результаты. Push выполняет владелец проекта; review и решение о merge остаются отдельной контрольной точкой.
