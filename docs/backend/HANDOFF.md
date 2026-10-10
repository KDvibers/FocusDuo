# Передача backend Kotlin-разработчику

Согласованный протокол: [FOCUSDUO_CONTRACT_V1.txt](../FOCUSDUO_CONTRACT_V1.txt). JSON fixtures в [contracts/fixtures](../contracts/fixtures/) сохранены без изменений. Машиночитаемая REST-спецификация: [openapi.yaml](../contracts/openapi.yaml). Ручные запросы: [requests.http](../contracts/requests.http). Запуск и переменные окружения: [backend/README.md](../../backend/README.md).

## Адреса и авторизация

Локально REST работает по `http://127.0.0.1:8090/api/v1`, raw JSON WebSocket — по `ws://127.0.0.1:8090/ws/rooms/{roomId}`. Для работы на двух ПК используйте один сервер: задайте ему `SERVER_ADDRESS=0.0.0.0`, обоим клиентам — его LAN-адрес. Порт 8090 должен быть доступен в локальной сети. Удалённый доступ — HTTPS/WSS.

Зарегистрируйте `alice_demo` и `bob_demo` через `/auth/register`; для локального теста можно использовать пароль `focusduo-demo-password`. Входящие поля: `{username,displayName,password}`. Реальные пользователи и токены не зашиты в код. Токен из fixture является только примером.

`AuthSession` содержит `{user,accessToken,expiresAt}`. Токен непрозрачный, не JWT. Передавайте `Authorization: Bearer <accessToken>` и в REST, и в HTTP-handshake WebSocket. Kotlin HTTP/WS-клиент должен поддерживать собственные заголовки handshake. Cookie, токен в URL, STOMP и SockJS не используются. Пароль и токен клиент хранит только в памяти. После перезапуска — новый login, затем `GET /rooms/current`; logout отзывает текущую сессию, но не закрывает комнату.

## Команды, повторы и revision

Все команды изменения комнаты возвращают `200 RoomSnapshot`. Отправляйте UUID в `Idempotency-Key`, а для существующей комнаты — целочисленный `X-Room-Revision` последнего принятого snapshot. Create/join не требуют revision. GET/auth/history не требуют ни одного из этих заголовков. Не добавляйте `expectedRevision` в JSON или `If-Match`.

После неоднозначного сетевого сбоя повторяйте тот же method/path/body, тот же ключ и **исходную** revision. Успешный результат хранится в БД вместе с изменением; повтор возвращает его без второго эффекта, включая одновременный повтор. Иной запрос с этим ключом даёт `409 IDEMPOTENCY_KEY_REUSED`. Сохранённый ответ может иметь старую revision — применяйте обычные правила сравнения, не откатывайте UI.

`409 REVISION_CONFLICT` содержит свежий snapshot для участника комнаты. Примените его и предложите пользователю повторить действие. Это новое действие получает новый ключ. Остальные ошибки имеют `snapshot=null`; формат всегда `{code,message,requestId,fieldErrors,snapshot}`. Необходимость повторного входа определяется `401`, а не разбором текста `message`.

## WebSocket и восстановление

Первое сообщение после соединения — полный снимок:

```json
{"type":"ROOM_SNAPSHOT","data":{"roomId":"...","revision":1,"serverNow":"..."}}
```

Это сокращённая иллюстрация envelope; полный JSON — [room-event.json](../contracts/fixtures/room-event.json). Последующие события используют тот же envelope и полный DTO после commit. Команды отправляйте через REST, не через JSON WebSocket. Секундных событий нет; ping/pong — стандартные control frames.

Сравнивайте revision внутри одного roomId: меньшую игнорировать, равная не откатывает данные, более свежий `serverNow` при равной версии может уточнить отсчёт. Игнорируйте поздние кадры предыдущего соединения/комнаты. При reconnect дождитесь первого полного snapshot, прежде чем разрешать изменения. Несколько сокетов пользователя не создают новых членств.

Сокет истёкшей или отозванной сессии закрывается с кодом `4401`; выполните login снова. Logout/потеря сети/закрытие клиента не закрывают комнату. Команда close от любого участника освобождает обоих, сохраняет закрытый snapshot и историю, отправляет закрытый snapshot и завершает подключения. Сохранённое членство позволяет читать CLOSED-комнату, но изменения запрещены.

## Таймер и история

Владелец управляет настройками и таймером; каждый участник — только своей целью и задачами. FOCUS требует двух участников. BREAK запускается вручную после последнего `COMPLETED FOCUS`. Таймер адресуется конкретным roundId; поздняя команда старого раунда получает `ROUND_NOT_CURRENT`.

RUNNING: отсчёт из `serverNow`/`endsAt` и монотонных часов клиента. PAUSED: `endsAt=null`, `remainingMs` и `activeElapsedMs` заморожены. После resume `startedAt` сохраняется. На wake/reconnect обновите snapshot; достижение нуля на клиенте не создаёт историю.

COMPLETED/CANCELLED: `endsAt=null`, `remainingMs=0`, `endedAt` заполнен. `activeElapsedMs` не включает паузы; при досрочном finish меньше полной длительности. Автозавершение: `COMPLETED/ELAPSED`, `endedAt` равен сроку даже после позднего восстановления сервера. Ручное: `COMPLETED/MANUAL`. Close активного раунда: `CANCELLED/ROOM_CLOSED`. Отмена не считается успешным фокусом. Уже завершённый раунд при close не переписывается.

История фиксирует копии целей и задач на момент конца, неизменяемые последующими правками. В `HistoryParticipant` есть total/completed counts. `/history` возвращает максимум 50 собственных результатов по `endedAt DESC` и стабильному roundId. `/history/{roundId}` доступен только участнику этого раунда.

## Проверки и ограничения передачи

В первом этапе на Windows с Java 21 выполнен `mvnw.cmd verify`: **40 тестов прошли, 0 failures, 0 errors, 0 skipped**. Из них 27 unit/auth/fixture/timer-тестов и 13 интеграционных: `BackendIT` — 6, `DomainPersistenceIT` — 6, `RestartIT` — 1. Интеграционные тесты использовали отдельный нативный PostgreSQL 17.11 на `127.0.0.1:55434` через `FOCUSDUO_TEST_DB_*`. Maven Wrapper 3.3.4 запускает Maven 3.9.11 с закреплённым SHA-256 дистрибутива.

Проверены HTTP/WS-сценарий двух пользователей, pause/resume/finish/BREAK/close, права доступа, logout/истечение сессии, reconnect, неизменяемая история, повторы ключей и конкурентные команды. Отдельные тесты проверяют ограничения PostgreSQL, гонки pause/close/истечения, сохранение автозавершения при конфликте и восстановление просроченного/приостановленного раунда после закрытия и повторного создания Spring-контекста с той же БД. Временные сценарии используют управляемые часы. Отчёты: `backend/target/surefire-reports` и `backend/target/failsafe-reports`.

OpenAPI проверен по официальной JSON Schema OpenAPI 3.1: 20 уникальных операций, все 199 внутренних ссылок разрешаются, все 10 исходных fixtures соответствуют описанным JSON Schema DTO. YAML Compose и backend CI синтаксически разобран; это не заменяет фактический запуск Docker/CI.

Отдельно запущен собранный `focusduo-backend-0.1.0.jar` на `127.0.0.1:8090` с этой изолированной PostgreSQL. HTTP-проверка прошла: регистрация двух пользователей → create/join → FOCUS → создание и отметка задачи → pause/resume → finish → сохранённая история → close → logout. После проверки процесс остановлен. В PowerShell-запросах явно задавался `Content-Type: application/json; charset=utf-8`.

Первый PR объединён в `main` (`462b827`); [GitHub CI первого этапа](https://github.com/KDvibers/FocusDuo/actions/runs/37818507362) успешно выполнил стандартный Testcontainers-прогон. Это подтверждение первой версии backend, отдельно от проверок текущего второго этапа.

В Windows Docker-команды отсутствуют, но для второго этапа подготовлен WSL `Ubuntu`: Ubuntu 26.04, Docker 29.1.3, Compose 2.40.3, Java 21.0.12.1. Docker запускается под `root`; группы пользователя не менялись. Linux-wrapper требует `curl`/`wget` и `unzip` для закреплённого ZIP с SHA-256. Порядок запуска с существующим `.env` описан в [backend README](../../backend/README.md).

В ветке `feat/backend-stage-2` подтверждён запуск Compose-проекта `focusduo-stage2` с новым volume: PostgreSQL 17.11 прошёл healthcheck. Собранный Linux JAR и [Python HTTP smoke](../../backend/scripts/README.md) прошли все 8 этапов `PASS`. Отдельно все 8 этапов прошли из Windows с Python 3.14 через `http://127.0.0.1:8090`; WSL JAR запущен с `-Djava.net.preferIPv4Stack=true`, устраняющим timeout Windows-клиента на IPv6-mapped socket. Проверены полный сценарий двух пользователей, идемпотентные повторы, история после последующих правок, BREAK/close и отзыв сессий. Доступ с другого ПК этим прогоном не подтверждён.

После проверок тестовые серверы остановлены; временные контейнер, сеть и volume Compose-проекта `focusduo-stage2` удалены.

Полный WSL `verify` завершился `BUILD SUCCESS`: **53 теста — 27 unit + 26 integration, 0 failures, 0 errors, 0 skipped**. PostgreSQL запущен через Testcontainers. Нативный Windows `mvnw.cmd verify` с Java 21 также прошёл **все 53 теста, без failures/errors/skips**; он использовал отдельную БД `focusduo_stage2_test` на PostgreSQL 17.11 через WSL Compose, порт 5434 и `FOCUSDUO_TEST_DB_*`. Testcontainers запускался в Linux-прогоне; Windows-прогон использовал внешнюю БД. Активный WSL-вызов удерживал БД доступной на время Windows-проверки.

Mockito agent подключается при старте тестовых JVM. Исправлен обход строгой UUID-проверки при привязке Spring MVC: сокращённые UUID теперь возвращают `400 VALIDATION_ERROR`. WS-регрессия ожидает завершения отправки pong, исключая гонку самого тестового клиента. Согласованные контракт и fixtures, а также `desktop/` не изменены. [PR #2](https://github.com/KDvibers/FocusDuo/pull/2) проверен и объединён (`e7e9db0`); [CI после merge](https://github.com/KDvibers/FocusDuo/actions/runs/38070089325) успешно выполнил Testcontainers и Compose/JAR HTTP smoke. Второй этап закрыт.

Подготовка третьего этапа выполнена от общего `main` `a508020` в `codex/backend-stage-3`. На тот момент в `desktop/` был только начальный Compose-демоэкран (PR #3). [INTEGRATION.md](INTEGRATION.md) задаёт первый сетевой сценарий, полную приёмку и таблицу ещё не выполненных проверок. Новый Java CLI проверяет два логических клиента с независимыми WS-подключениями; результат настоящего Kotlin-клиента фиксируется отдельно. Межкомпьютерная интеграция пока не выполнена.

В этой подготовке на новом volume Compose-проекта `focusduo-stage3` PostgreSQL 17.11 прошёл healthcheck. Против отдельного JAR с неизменённым production-кодом выполнены HTTP smoke из Windows — **8 `PASS`**, и новый Java WebSocket smoke из Windows и WSL — **по 8 `PASS`**. Подтверждены bearer handshake, доставка снимков обоим участникам, `409` без изменения состояния, пропущенная правка и reconnect, logout → `4401` с сохранением второй сессии, новый login/current и CLOSED snapshot перед close code 1000. Подготовка объединена через [PR #4](https://github.com/KDvibers/FocusDuo/pull/4), commit `6915291`. [CI после merge](https://github.com/KDvibers/FocusDuo/actions/runs/38072287807) успешно выполнил `verify`, Compose и оба HTTP/WS smoke.

Дополнительный локальный сценарий дважды принудительно завершил процесс JAR и запустил новый процесс с той же БД: **3 `PASS`**. После первого перезапуска сохранились PAUSED, задачи, сессии, членства, revision и точный сохранённый ответ повторяемой команды start. После resume и второго перезапуска сохранились RUNNING и исходный `endsAt`; ручное завершение создало одну одинаковую запись истории для обоих пользователей. Этот локальный сценарий не входит в CI; просроченное автозавершение и reconnect Kotlin после рестарта этим запуском не проверялись. Полный Maven `verify` здесь не повторялся; 53 теста выше относятся ко второму этапу. Временные процессы, контейнер, сеть и собственный volume `focusduo-stage3` после проверки удалены.

Локальный `.env` создаётся командой `backend/scripts/init-local.ps1`: генерируется пароль БД, существующий файл не перезаписывается. Проверены PowerShell 5.1 и 7, сохранение пользовательских изменений и кодировки, некорректный шаблон и одновременный запуск. В текущей рабочей копии `.env` уже создан, исключён из Git и сохранён после проверок; для двух клиентов на одном ПК дополнительных значений не требуется. Файл остаётся только на машине, где запущен сервер. Клиенту нужны base URL и собственный login, не пароль PostgreSQL. Команды запуска и проверки — в [backend README](../../backend/README.md) и [scripts README](../../backend/scripts/README.md).

## Авторизация Kotlin после desktop-этапа 5

В `main` `7acb617` объединён desktop PR #8 (`a53ecde`): настоящий `RealRepository` с Ktor/CIO, авторизацией, профилем и чтением комнаты. Ветка совместной проверки — `feat/backend-auth-integration`; новые ветки используют `feat/` по указанию владельца.

На Windows с Temurin JDK 21.0.12.101 выполнены `test` и `backendAuthSmoke` собственного Gradle Wrapper desktop: **112 обычных тестов + 1 живой auth smoke, 0 failures/errors/skips**, `BUILD SUCCESSFUL`, exit 0. Клиент обращался к отдельному JAR в WSL на `127.0.0.1:8090`, с новой изолированной PostgreSQL 17.11 на 55435. Пользовательская Compose-БД на 5434 и `.env` не менялись. Все 10 desktop-копий fixtures совпали с общими по SHA-256; в 112 тестов входят 17 fixture-проверок.

Живой тест подтвердил регистрацию с кириллицей, профиль, пустую текущую комнату, logout, новый login и восстановление того же пользователя. Он использует настоящий `RealRepository`/CIO и Java backend, но не запускает Compose-окно. Отчёт, точная команда и ограничения — в [AUTH_INTEGRATION.md](AUTH_INTEGRATION.md). Команды комнаты и WS ожидаются на desktop-этапе 6; UI отложен до этапа 7. Полные K1–K7 и LAN остаются открытыми; локальный Maven `verify` в этой проверке не повторялся, поскольку серверная реализация не менялась.

Дополнительный [opt-in Kotlin harness](../../backend/scripts/kotlin-auth-smoke/README.md) прошёл на том же Java backend: два `RealRepository` читают заполненную комнату, неверный пароль/дубликат username дают контрактные ошибки, внешний отзыв токена вызывает 401 → `EXPIRED` и очистку, повторный login восстанавливает комнату, logout даёт `SIGNED_OUT` и отзывает токен, не закрывая комнату партнёру. Подготовительные команды комнаты выполняет JDK HTTP helper; Kotlin-команды и WS этим не проверяются. Нормальный прогон финальной версии — `BUILD SUCCESSFUL`, exit 0.

Отдельный намеренный обрыв ответа уже закоммиченного create завершил harness с ожидаемым exit 1; восстановительная очистка нашла комнату через current и закрыла её. SQL подтвердил отсутствие открытых комнат, активных членств и неотозванных сессий в тестовой БД. После проверки временные JAR/proxy и PostgreSQL-контейнер удалены, пользовательская БД на 5434 осталась healthy, `.env` сохранён. Подтверждённых несовместимостей авторизации не найдено. Этот harness не включён в CI; новый удалённый прогон ветки ожидает push владельца.

Один процесс backend обслуживает WS-соединения. Горизонтальное масштабирование событий потребует общей шины доставки. Пагинация истории, отдельное presence/измерение внимания, смена партнёра, password reset и production TLS deployment не входят в v1.

Репозиторий: [KDvibers/FocusDuo](https://github.com/KDvibers/FocusDuo). Первые два этапа объединены. Третий этап закрывается после совместной приёмки с Kotlin и review отдельного PR. Push выполняет владелец проекта. Подробности: [ROADMAP.md](ROADMAP.md). Автоматические push и merge не выполняются.
