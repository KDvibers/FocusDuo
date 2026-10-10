# Kotlin REST/WebSocket ↔ Java: этап 6

Проверки выполнялись 11 октября 2026, Asia/Qyzylorda, на базе `main` `7cee8c4`: desktop PR #10 (`b69d3b1`) и backend auth PR #9 (`a71c34d`). Рабочая ветка — `feat/backend-room-integration`. Production-код Java, `desktop/**`, общий контракт и fixtures не изменяются этим заходом.

## Среда и воспроизведение

Клиент: native Windows, Temurin JDK 21.0.12.101, Gradle Wrapper desktop 8.13. Сервер: отдельный собранный JAR в WSL Ubuntu, `http://127.0.0.1:8090`, JVM с `-Djava.net.preferIPv4Stack=true`. Серверные исходники и `pom.xml` совпадают с ранее проверенной версией. В новой изолированной PostgreSQL 17.11 на 55436 создана tmpfs-БД `focusduo_room_stage6`; обе Flyway-миграции применены. Пользовательский контейнер `focusduo-db-1` на 5434 и `.env` не менялись.

Для повтора сначала запустите отдельный сервер разработки по [backend README](../../backend/README.md). Проверки создают аккаунты и историю; V1 не содержит их удаления. Рекомендуется одноразовая БД: `LiveBackendRoomTest` в текущем desktop при ошибке вызывает только локальный `close()` и не гарантирует серверные close/logout. В нашем прогоне использовалась отдельная уничтожаемая БД.

Из `desktop/`:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:PATH=(Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:/Daniyar/MyProjects/FocusDuo/backend/target'
.\gradlew.bat test check backendAuthSmoke backendRoomSmoke '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' --console=plain --no-daemon
```

Пути JDK и существующего `backend/target` замените для своей машины. Полный путь временной папки обходит известную проблему Windows JDK с коротким именем TEMP. Это настройка текущей оболочки, без изменения системных переменных.

## R1: обычные тесты и живой сценарий комнаты

Команда завершилась **BUILD SUCCESSFUL за 48 секунд**, exit 0. По XML:

| Задача | Тестов | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `test` | 143 | 0 | 0 | 0 |
| `backendAuthSmoke` | 1 | 0 | 0 | 0 |
| `backendRoomSmoke` | 1 | 0 | 0 | 0 |

`check` завершился успешно в том же запуске. Все 10 клиентских копий fixtures отдельно сравнены с общими по SHA-256 и совпали. XML находятся в `desktop/build/test-results/{test,backendAuthSmoke,backendRoomSmoke}/`, HTML — в соответствующих подпапках `desktop/build/reports/tests/`; build-артефакты исключены из Git.

`LiveBackendRoomTest` использовал два независимых production `RealRepository` в одном тестовом JVM, настоящий CIO, два raw JSON WebSocket и Java backend с PostgreSQL. Через Kotlin-команды выполнены:

1. Register/bootstrap → create/join → полные снимки и готовность обоих WS. Повторный join не добавил участника и не изменил revision.
2. Цель, создание и отметка задачи. Второй repository получил изменения через WS.
3. FOCUS → pause → resume → manual finish; `startedAt` сохранился после паузы и продолжения.
4. Чтение списка и деталей истории обоими пользователями; итог совпал, задача попала в счётчик выполненных. Последующая правка задачи не изменила сохранённый результат.
5. Отдельный BREAK → close вторым участником. Первый получил CLOSED, перерыв сохранён как `CANCELLED/ROOM_CLOSED`, команды обоих заблокированы, выполнен logout.

После этого сценария SQL подтвердил **2 раунда / 2 записи истории**, **0 OPEN rooms / 0 active memberships / 0 unrevoked sessions**. Это отдельная проверка результата в БД, не подмена действий Kotlin прямыми SQL-командами.

## R2: совместимость прежнего auth harness

[REST auth harness](../../backend/scripts/kotlin-auth-smoke/README.md) адаптирован к desktop-этапу 6 и повторно прошёл: `BUILD SUCCESSFUL`, 6 секунд, exit 0. Login/register теперь явно используют `realtimeEnabled=false`, чтобы проверка HTTP 401 не могла случайно пройти из-за более раннего WS 4401. Состояние до GET проверяется как ACTIVE.

Также разделены разные результаты закрытия: API `/rooms/current` возвращает `{room:null}`, но repository сохраняет последний известный CLOSED snapshot для показа результата. Прежнее ожидание `repository.refreshCurrentRoom() == null` больше не соответствовало этому поведению. Production-код клиента или сервера для этого не менялся.

Команда из корня с теми же JDK 21 и `JAVA_TOOL_OPTIONS`:

```powershell
.\desktop\gradlew.bat -p desktop -I (Resolve-Path backend/scripts/kotlin-auth-smoke/init.gradle).Path realRepositorySmoke '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' --console=plain
```

## R3: соединение, повторный вход и отзыв активного WS

Новый [lifecycle harness](../../backend/scripts/kotlin-room-smoke/README.md) использует production `RealRepository`, настоящий CIO/REST/WS и тот же отдельный Java backend. Финальный прогон после review: **BUILD SUCCESSFUL, 6 секунд, exit 0**, все пять checkpoints и итог `PASS`. Это отдельный executable, его проверки не входят в число 145 JUnit-тестов R1.

1. До ручного `reconnect()` подписывается наблюдатель нового CONNECTING/SYNCHRONIZING. Затем ожидаются CONNECTED и полный snapshot, после чего клиент получает следующую правку партнёра. Вызов reconnect без действия не удовлетворит ожидание нового цикла. Мгновенная проверка `canMutate=false` не используется: локальный handshake может завершиться очень быстро.
2. Logout владельца → изменение цели партнёром → новый login/bootstrap. Восстановлены прежние участники, revision и пропущенная правка; `/rooms/join` при восстановлении не отправлялся.
3. Внешний HTTP logout отзывает точную сессию владельца. Уже подключённый WS переводит repository в EXPIRED без REST-refresh; комната и история очищены, команды недоступны. Отозванный токен отдельно получает 401; партнёр сохраняет CONNECTED и успешно выполняет настоящий GET `/me`.
4. Ещё один login/bootstrap → close партнёром. Получены CLOSED и NONE, `/rooms/current` возвращает `room:null`. Refresh и ручной reconnect сохраняют закрытый итог, попытка изменения отклоняется.
5. Собственная комната закрыта, действующие сессии отозваны. Вспомогательные токены существуют только в памяти; секреты не выводятся.

Команда из корня с теми же JDK 21 и `JAVA_TOOL_OPTIONS`:

```powershell
.\desktop\gradlew.bat -p desktop -I (Resolve-Path backend/scripts/kotlin-room-smoke/init.gradle).Path realRoomLifecycleSmoke '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' --console=plain
```

Проверка наблюдает состояние production repository после реального WS-отзыва; числовой close frame отдельно не перехватывается. Новое соединение проверяется через переход синхронизации и получение данных. Сетевой разрыв, задержка первого snapshot, автоматический reconnect и безопасный replay этим harness не моделируются. Linux/macOS-команда не запускалась.

После всех прогонов SQL снова подтвердил 0 OPEN rooms, 0 active memberships, 0 unrevoked sessions, 2 раунда, 2 истории и 2 применённые миграции. Временный JAR остановлен, собственный PostgreSQL-контейнер с tmpfs удалён; порты 8090 и 55436 свободны. Пользовательский `focusduo-db-1` на 5434 остался healthy. `.env` сохранён и исключён из Git. Cleanup harness выполняется с ограниченными ожиданиями, но недоступность сервера или потерянный ответ logout может оставить сессию; для повторения нужна одноразовая БД.

## Границы и передача другу

R1–R3 подтверждают совместную работу repository/REST/WS, но не два Compose-окна. UI новых экранов, визуальное поведение таймера и работа с клавиатурой остаются desktop-этапом 7. Автоматический reconnect, безопасный повтор потерянной команды, сон/пробуждение, живой конфликт 409, TTL-истечение, Kotlin после рестарта Java и LAN в этом заходе не проверялись. Временные пограничные случаи покрыты обычными тестами с управляемыми часами; ожидания реальных пяти минут здесь нет.

Найдено ограничение самого desktop smoke: `finally { repository.close() }` не отзывает серверный токен и не закрывает комнату при assertion/timeout до штатной очистки. Другу следует добавить ограниченную по времени очистку только собственных аккаунтов/комнаты либо сохранить обязательное использование одноразовой БД. `desktop/**` в этой ветке не исправлялся.

Полный Maven `verify` локально не повторялся: серверная реализация не менялась. [CI после backend auth PR #9](https://github.com/KDvibers/FocusDuo/actions/runs/38078661860) завершён успешно на `a71c34d`; это отдельное серверное доказательство. Текущие Kotlin opt-in задачи не включены в backend CI, новый удалённый CI этой ветки ожидает push владельца.
