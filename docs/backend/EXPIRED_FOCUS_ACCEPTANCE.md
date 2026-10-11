# FOCUS, истёкший при выключенном backend

**PASS:** настоящий 300-секундный FOCUS истёк при остановленной Java. После запуска с той же PostgreSQL оба production Kotlin repository автоматически восстановились, а оба наблюдаемых окна `RealApp` сами открыли итог **«5 мин фокуса»**. Сервер сохранил один `COMPLETED/ELAPSED` с исходным deadline и неизменяемой историей. Исправления production-кода не потребовались.

Дата: **12 октября 2026, Asia/Qyzylorda (UTC+05:00)**; все точные метки ниже — UTC за **11 октября**. Ветка `feat/backend-final-integration`, база `origin/main` **`1a6fa94`**, desktop **`774378c`** после PR #18. Этот прогон дополняет предыдущие проверки TTL/BREAK и сна, не повторяет и не заменяет их.

## Проверяемый вход и среда

- Windows 11 Pro build 26200, native Temurin 21.0.12.101; два неизменённых production `RealApp` в **одном тестовом JVM**, каждый со своим настоящим `RealRepository` и CIO/REST/raw JSON WS. Окна 1280×800, без demo/mock транспорта.
- [Отдельный компонентный вход](../../backend/scripts/kotlin-expired-focus-smoke/ExpiredFocusUi.kt) создаёт синтетические аккаунты/комнату через API и передаёт repository в `RealApp`. **`Main`, `AuthScreen`, `RealWorkspace`, вход через UI и два независимых клиентских JVM здесь не проверяются.** Обёртка не устанавливает обработчики foreground/сна: recovery выполняется обычным backoff самого repository.
- Java 21.0.12.1 / Spring Boot 3.5.16 в WSL Ubuntu 26.04, `http://127.0.0.1:8090`. PostgreSQL **17.11-bookworm**, отдельный tmpfs-контейнер на loopback **55437**. Контейнер и данные сохранялись между остановкой и запуском Java; обычная БД на 5434 не использовалась.
- Backend JAR SHA-256: `C58D0611A596DE4F2F740980BE52F8EBAFF193510C4BA9BA5A6BD60D47422267`.
- Копия desktop JAR, использованная адаптером: `EBF032645CDB77D001757A63517FE2BCE547B21C1EEE942BFD35FC7C3ECB5ADA`. Classpath ссылается на эту отдельную копию, а не изменяемые `desktop/build/classes`.

Контроллер остановил только собственный Java-процесс принудительно; БД продолжала работать. Часы ОС, timestamp БД, длительность раунда, реализация таймера и reconnect delay не подменялись. Пока сценарий выполнялся, только core harness записывал команды контроллеру.

## Наблюдаемый результат

Комната **FD-8NKE85**, `roomId=9bf53ee0-2d4c-4058-bbf2-eba13400b387`, `roundId=81d97422-85ce-4405-a792-2b509e69b8f3`. Два новых пользователя имеют отдельные цели и по одной задаче: A — выполненная, B — исходная невыполненная. На старте истории нет.

| Событие | UTC | Подтверждение |
| --- | --- | --- |
| FOCUS начат | `20:33:25.835Z` | 300 с, исходный `endsAt=20:38:25.835Z`, revision 8; оба WS готовы. |
| Java остановлена | `20:34:12.0758228Z` | До исходного deadline; оба клиента теряют CONNECTED и блокируют изменения. |
| Истёк срок при выключенной Java | `20:38:27.8472023Z` | Проекции обоих таймеров 0; авторитетный статус остаётся RUNNING, локальная история пуста. |
| Read-only SQL до рестарта | `20:38:42.107745Z` | PID Java отсутствует, deadline уже прошёл, в БД RUNNING/revision 8 и 0 history. |
| Запрошен запуск Java | `20:39:07.9270983Z` | Тот же контейнер и БД. |
| Java готова | `20:39:16.6267041Z` | Новый PID 4643, generation 2. |
| Оба repository готовы после auto recovery | `20:39:35.2071038Z` | У каждого recoveryVersion 0 → 1, CONNECTED и актуальный snapshot; revision 8 → 9. |
| История обоих подтверждена | `20:39:35.2921278Z` | Одна равная запись в каждом list/detail, полный исходный снимок участников и задач. |
| Проверка неизменяемости | `20:40:14.5549678Z` | B изменил текст/статус живой задачи; сохранённый результат остался прежним. |
| Core завершён | `20:40:14.720011Z` | Close/logout/cleanup подтверждены, финальный PASS, процесс exit 0. |

Наблюдаемый интервал от готовности Java до готовности **обоих** repository — около **18,58 с** при обычном backoff. Это один замер данного прогона, не предел recovery latency. Момент фоновой обработки expiry на сервере отдельно не измерялся: проверяется авторитетный итог после восстановления клиента.

После рестарта совпали room/round IDs, `startedAt`, участники, цели, задачи и полный ожидаемый snapshot. Итог: **COMPLETED / ELAPSED**, `endsAt=null`, `remainingMs=0`, **`activeElapsedMs=300000`**, **`endedAt=2026-10-11T20:38:25.835Z`** — исходный срок, а не время рестарта. Счётчики запросов не обнаружили modifying REST-команд во время offline/auto recovery. Нажатия «Обновить с сервера» / «Попробовать сейчас», reconnect, relogin и wake recovery не использовались.

## Фактическое наблюдение Compose

В начальном гейте в каждом окне открыта «Комната». Далее выполнялись только активация окон, снимки и прокрутка: переход на итог после рестарта произошёл автоматически. Три ограниченных гейта позволили осмотреть UI и БД до продолжения сценария; сам факт освобождения гейта не считается доказательством UI.

| Снимок | Записано, UTC | Наблюдение |
| --- | --- | --- |
| [01 — A RUNNING](screenshots/expired-focus-2026-10-12/01-a-running.jpg) | `20:33:56.898Z` | 04:30, обе цели/задачи, A 1/1 и B 0/1, команды владельца. |
| [02 — B RUNNING](screenshots/expired-focus-2026-10-12/02-b-running.jpg) | `20:34:11.370Z` | 04:15, та же комната, управление таймером доступно только владельцу. |
| [03 — A offline 00:00](screenshots/expired-focus-2026-10-12/03-a-offline-zero.jpg) | `20:38:48.276Z` | Восстанавливаем соединение, «Ждём завершения от сервера», итог не выдуман. |
| [04 — A disabled](screenshots/expired-focus-2026-10-12/04-a-offline-disabled.jpg) | `20:39:00.987Z` | 00:00, revision 8, пауза/завершение/правки отключены. |
| [05 — B offline 00:00](screenshots/expired-focus-2026-10-12/05-b-offline-zero.jpg) | `20:39:07.402Z` | Такое же ожидание авторитетного завершения при выключенной Java. |
| [06 — B auto result](screenshots/expired-focus-2026-10-12/06-b-auto-result.jpg) | `20:40:03.945Z` | Соединение установлено, «Сессия завершена», 5 мин, исходные цели/задачи, A 1/1, B 0/1. |
| [07 — A auto result](screenshots/expired-focus-2026-10-12/07-a-auto-result.jpg) | `20:40:09.318Z` | Тот же итог; владельцу доступен запуск перерыва, он не нажат. |

Метки относятся к регистрации наблюдения/сохранению кадра и не задают точную latency отрисовки. Снимки последовательные, поэтому разные RUNNING-остатки не означают рассинхронизацию. Все 7 JPG сохранены без изменения пикселей.

## История, SQL и очистка

[Журнал core](evidence/expired-focus-2026-10-12/repository.json) содержит фазы и проверенные значения без паролей/токенов. Независимый [read-only probe](../../backend/scripts/kotlin-expired-focus-smoke/probe.py) выполнил ограниченные SQL-транзакции в трёх стабильных фазах; каждая завершилась exit 0 и `pass=true`:

- [Offline](evidence/expired-focus-2026-10-12/sql-offline.json): исходный deadline прошёл, Java отсутствует, RUNNING, одна round и ни одной history.
- [Recovered](evidence/expired-focus-2026-10-12/sql-recovered.json): одна COMPLETED/ELAPSED history, 300000 мс, точный deadline в round и snapshot, по одной связи каждого пользователя с итогом.
- [Cleanup](evidence/expired-focus-2026-10-12/sql-cleanup.json), `20:40:24.548431Z`: CLOSED/revision 11, **1 round / 1 history**, **0 active memberships**, **0 неотозванных сессий** обоих тестовых пользователей. Поздняя правка задачи и закрытие комнаты не создали второй итог.

После архивирования доказательств контроллер получил `shutdown`, завершился exit 0 с `STOPPED`, удалил свой tmpfs-контейнер и остановил свою Java. Порты **8090 и 55437 свободны**. Обычный `focusduo-db-1` на **5434 healthy**, `.env` и его данные не менялись. Оба тестовых окна закрылись штатно; обычный backend после этого прогона не запущен.

## Команды и реально выполненные проверки

Полный порядок запуска, точные команды CLI/GUI, гейты и SQL — в [README сценария](../../backend/scripts/kotlin-expired-focus-smoke/README.md). Для повторения нужен новый каталог контроллера под `.local/`; существующий журнал никогда не перезаписывается новым прогоном. Стандартные команды backend остаются в [HANDOFF](HANDOFF.md).

В этом прогоне выполнены:

1. WSL `cd backend && bash ./mvnw --batch-mode --no-transfer-progress package` — **27 тестов**, 0 failures/errors/skipped, BUILD SUCCESS (15,238 с).
2. Native Windows `desktop/gradlew.bat -p desktop -I <абсолютный backend/scripts/kotlin-expired-focus-smoke/init.gradle> test check compileRealExpiredFocusSmoke --console=plain --no-daemon` — **199 тестов**, 0 failures/errors/skipped, BUILD SUCCESSFUL (20 с).
3. `compileExpiredFocusUi` через `ui.gradle` — core + Compose adapter скомпилированы, exit 0 (финальная компиляция 11 с).
4. Один прямой запуск `ExpiredFocusUiKt` по экспортированному classpath — **весь живой core-сценарий + фактическое наблюдение двух окон PASS**. Отдельного второго пятиминутного headless-прогона не было; обычный CLI только скомпилирован.
5. SQL probe offline/recovered/cleanup — PASS; копии evidence/снимков сверены с оригиналами по SHA-256, Markdown-ссылки и `git diff --check` проверены.

**Не запускались:** полный Maven `verify`/Testcontainers, предыдущие четыре harness, отдельные auth/room smoke, CI этой ветки, новый TTL/BREAK/физический сон. Этот прогон не проверяет LAN, отключение адаптера, обычный UI-вход, полный UI-аудит или установщик. Все утверждения про UI относятся к компонентному входу выше. **Backend-этап 3 остаётся открыт** до согласованной оставшейся приёмки; tracked `desktop/**`, production backend и общий контракт не изменены.
