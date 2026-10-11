# Этап 8.2 — восстановление двух Compose-клиентов

## Объём и состояние проверки

**Дополнение 12 октября 2026:** этот документ сохраняет результаты собственного прогона 8.2. В `main` позднее объединён [отдельный backend-отчёт физического Modern Standby](../../docs/backend/COMPOSE_ACCEPTANCE.md): PAUSED/RUNNING **PASS на desktop `8fdb392`**, 29 JPG; сна на последней сборке повторно не было, задержка восстановления и поведение без активации окон не измерены. Поэтому приведённые ниже NOT_RUN для сна означают «не выполнялся в этом прогоне 8.2», а не отсутствие физической проверки вообще. Отдельный просроченный FOCUS на актуальном `main` `1a6fa94` **PASS**: оба свежих Compose JVM автоматически получили 5-минутный ELAPSED-итог после start Java на той же PostgreSQL, SQL подтвердил одну историю 300000 ms с исходным deadline. [Новый протокол и доказательства](FOCUS_EXPIRY_ACCEPTANCE.md).

Дата: 11 октября 2026, Asia/Qyzylorda. Ветка `feat/desktop-stage8-2` от desktop-коммита этапа 8.1 `0da9c6c`. Этот протокол отделяет новые проверки на текущем клиенте от прежних backend-результатов и от ручных условий, которые ещё не воспроизводились.

**Статус текущего блока: PASS.** После исправления навигации прошли **199 обычных + 2 живых теста** и повтор в **двух свежих Compose JVM**: автоматические переходы к новым FOCUS/BREAK, оба окна на offline 00:00/ожидании и оба автоматически на единственном BREAK/ELAPSED-итоге после рестарта Java. Живая UI TTL PT30S также PASS. **Весь этап 8.2 остаётся открытым:** физический сон Windows, адаптер, LAN и отдельный просроченный FOCUS не проверены. Основной стенд Java generation 8 / PostgreSQL и два свежих окна оставлены работающими для просмотра пользователем; отдельный TTL-стенд полностью остановлен.

В этом заходе прошли тесты, существующие recovery/restart harness с настоящими Java/PostgreSQL и два независимых Compose-окна при принудительном завершении/повторном запуске Java с той же БД. PAUSED/RUNNING проверены пользователем и SQL; после обнаружения UI-дефекта агент проверил окончательное исправление в свежих JVM. Репозиторный baseline, первоначальный GUI-дефект и финальный повтор описаны отдельно, чтобы не переносить PASS между разными версиями приложения.

Физический сон Windows, отключение сетевого адаптера и LAN не считаются выполненными по результату серверного рестарта. Живой TTL отдельно проверен с PT30S и настоящим Compose — PASS, как описано ниже. Просроченный BREAK/ELAPSED проверяется отдельно ниже; отдельный просроченный FOCUS пока не прогонялся. Текущий заход не переводит пользовательский компьютер в сон и не меняет настройки сети.

## Прежнее доказательство после wake-исправления

Backend-разработчик отдельно повторил проверку на `main` `a1e557f` после desktop PR #14. В [WAKE_ACCEPTANCE.md на backend-коммите `9287c91`](https://github.com/KDvibers/FocusDuo/blob/9287c91/docs/backend/WAKE_ACCEPTANCE.md) записаны **185 обычных + 2 живых теста**, четыре harness / **19 контрольных точек** и четыре итоговых `PASS`. Среда той проверки — native Windows / Temurin 21.0.12.101 → WSL Java `8090` → PostgreSQL 17.11 `55437`. Она подтвердила replay после потерянного ответа, настоящий 409 и автоматическое восстановление двух repository после двух SIGKILL.

Эти цифры не являются новым прогоном в desktop-ветке этапа 8.2 и не включают два logout-теста этапа 8.1. Вызов `recoverAfterResume()` из harness не доказывает путь физического сна через Windows/AWT/Compose. Новые фактические результаты этого захода записаны отдельно ниже.

## Изолированный стенд

Используется native Windows-контроллер `desktop/scripts/stage8_runtime.py`: отдельные Java/PostgreSQL, новая временная БД внутри `desktop/build/`, случайный пароль в памяти, явная серверная конфигурация. `.env` и пользовательская БД не используются. Контроллер останавливает только собственный Java-процесс; рестарты сохраняют PostgreSQL и её данные. Новая папка runtime обязательна при каждом запуске.

Неизменяемый application JAR на профиль, создаваемый `run-integration-client.ps1`, исключает смесь версий классов при последующей Gradle-сборке. Профили сохраняют отдельно только username/URL, пароли и токены остаются в памяти.

Фактический стенд: native Windows / Corretto **21.0.6** / Spring Boot **3.5.16** / PostgreSQL **17.2**. Java `http://127.0.0.1:8090`, отдельная PostgreSQL `127.0.0.1:55438`, каталог `desktop/build/stage8-2-20261011/`. Повторно сверенный SHA-256 production-JAR: `A01FE560A161E6B89E7E653E8D034662DC8A205315A145F5F4CDBFB47E148A11`; это тот же серверный артефакт, что использовался в 8.1. Новая Maven-проверка не заявляется.

Контроллер расширен протоколом `focusduo-isolated-restart-v1`: атомарные UUID-команды `start` / `stop` / `inspect` и ответы с совпадающим id. Принудительный `stop` завершает только сохранённый `Popen` Java; PostgreSQL остаётся запущенной. Повторный `start` при готовом сервере не создаёт новый процесс. Read-only inspect доступен и при остановленной Java.

До клиентских сценариев пройдены **8 runtime-probe проверок**, [результат](evidence/stage-8-2-runtime-probe.json): start без изменения PID/generation, отклонение неизвестной команды, принудительный stop, повторный stop, SQL при остановленной Java, restart с новой generation, повторный start без дубликата процесса и сохранение той же БД с двумя миграциями. Это проверки контроллера, не восемь JUnit-тестов. [Стартовый SQL](evidence/stage-8-2-baseline.json): все domain-счётчики 0, миграции 2, generation после probe 2.

## Новый прогон тестов и harness

На исходном desktop baseline `0da9c6c`, до navigation-fix, выполнено `test check backendAuthSmoke backendRoomSmoke --rerun-tasks`: **BUILD SUCCESSFUL за 24 секунды**, wall 24.748 s, exit 0. Обычные JUnit — **187**, `backendAuthSmoke` — **1**, `backendRoomSmoke` — **1**; во всех трёх наборах 0 failures/errors/skipped. Итого **187 + 2**, а не 189 обычных тестов. Corretto 21.0.6, настоящий CIO → native Java/PostgreSQL описанного стенда; полный набор не использовал `JAVA_TOOL_OPTIONS`. Окончательные 199 + 2 после исправления записаны отдельно ниже.

[Машиночитаемый результат тестов, harness и fixtures](evidence/stage-8-2-harness.json) содержит точные safe checkpoints, версии, время и команды; пароли, токены и тела auth-ответов не сохраняются.

Затем по одному выполнены четыре существующих opt-in harness через desktop init-script. Все завершились exit 0 / BUILD SUCCESSFUL и итоговым `PASS`:

| Harness | Время Gradle / wall | Checkpoints | Фактический результат |
| --- | --- | ---: | --- |
| `realRepositorySmoke` | 8 s / 8.952 s | 5 | Авторизация, заполненная комната, настоящий 401 после отзыва сессии, восстановление членства и logout |
| `realRoomLifecycleSmoke` | 9 s / 9.313 s | 5 | REST/WS lifecycle, ручной reconnect, повторный login без join, отзыв открытой WS-сессии, CLOSED |
| `realRecoverySmoke` | 10 s / 10.254 s | 4 | Потерянные успешные Java-ответы после commit create/task, точный ручной replay без дубликата и отката нового snapshot, настоящий 409 |
| `realRestartSmoke` | 24 s / 24.376 s | 5 | Два принудительных stop/start Java с той же БД; автоматическое восстановление двух repository, PAUSED/RUNNING, прежний `endsAt`, одна равная история |

Это **19 контрольных точек / 4 итоговых PASS**, не дополнительные JUnit-тесты. Production backoff/jitter не заменялись. Harness напрямую вызывает `recoverAfterResume()` и проверяет безопасные чтения без mutation; это отдельная repository-проверка, не физический сон или GUI-событие пробуждения.

Для opt-in процессов задан только локальный `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<worktree>/desktop/build/ipc`: существующая папка с полным путём исключает проблему короткого Windows TEMP/8.3 имени. Серверная конфигурация и постоянное окружение не менялись.

В этой Windows worktree общие fixtures извлечены с CRLF, desktop-копии с LF, поэтому SHA-256 сырых checkout-файлов различается. Все **10 Git blobs HEAD побайтно совпадают**; дополнительно совпадают LF-normalized bytes и разобранный JSON. Результат не описывается как совпадение raw checkout SHA.

Read-only SQL после всех harness, до отдельного GUI-сценария: [stage-8-2-harness-db.json](evidence/stage-8-2-harness-db.json). **11 пользователей, 15 сессий / 0 неотозванных, 5 CLOSED-комнат / 0 активных членств, 4 задачи, 3 раунда / 3 истории / 6 history participants, 39 idempotency results, 2 миграции**. Java generation 4: начальный запуск, runtime-probe restart и два harness-рестарта. Три истории соответствуют FOCUS/BREAK базового room smoke и FOCUS restart harness; отдельный GUI seed добавляется уже после этого checkpoint.

## Подготовленный ручной GUI-сеанс

Тестовая подготовка выполнена обычными REST-вызовами настоящего Java backend, без FakeRepository: два аккаунта, их общая комната, цели и по одной задаче, FOCUS с паузой. Эти API-вызовы не считаются нажатием форм регистрации/create/join в Compose; соответствующий полный UI lifecycle уже отдельно проверен в 8.1.

| Поле | Подготовленное значение |
| --- | --- |
| Сервер | `http://127.0.0.1:8090`; текущий BREAK start: Java/PostgreSQL/контроллер ALIVE |
| Владелец / участник | `gui82_a_877ea4a7b3de` / `gui82_b_877ea4a7b3de` |
| Код / roomId | `FD-MHYWYU` / `2bee0ae1-cce4-4422-a503-3a7e0c60c754` |
| RoundId | `d8f043e0-4807-4657-ac0b-2bde0eff27c9` |
| Состояние / revision | FOCUS PAUSED / 9 |
| Длительности | FOCUS 900 s / BREAK 60 s |
| Остаток / активное время | 899 986 ms / 14 ms |

Пароли и bearer-токены не публикуются в Git. На момент передачи управления GUI login ещё не был выполнен; затем пользователь самостоятельно вошёл и сообщил **«оба окна готовы»**. Источник этого результата — USER_REPORTED, без нового изображения. SQL после harness выше относится к состоянию до создания этих двух аккаунтов и OPEN-комнаты, поэтому не используется как итоговый SQL всего будущего ручного сценария.

| Ручной checkpoint | Что зафиксировать | Статус |
| --- | --- | --- |
| Вход в оба окна | Пользователь сообщил «оба окна готовы» после ручного входа и открытия PAUSED-комнаты; собственного снимка агента нет | USER_REPORTED |
| PAUSED offline | Пользователь подтвердил reconnect в обоих окнах, сохранённые задачи, паузу 15:00 и заблокированные кнопки; снимок участника подтверждает reconnect и недоступность собственных add/edit | PASS — оба USER_REPORTED, участник SCREENSHOT |
| PAUSED start/recovery | Пользователь подтвердил автоматическое восстановление обоих окон, те же комнату/задачи/15:00, доступную владельцу «Продолжить»; снимок участника показывает CONNECTED и доступные собственные add/edit; SQL подтвердил прежние roomId/roundId/revision/PAUSED | PASS — оба USER_REPORTED, участник SCREENSHOT, SERVER_SIDE SQL |
| RUNNING baseline | Пользователь нажал Continue владельцем и сообщил, что таймер идёт в обоих окнах, задачи сохранились; SQL подтвердил RUNNING / revision 10 | PASS — USER_REPORTED + SERVER_SIDE SQL, без нового снимка |
| RUNNING offline | Пользователь подтвердил reconnect обоих окон, сохранённые задачи, заблокированные кнопки и одинаковый продолжающийся отсчёт | PASS — USER_REPORTED обоих окон, без PNG |
| RUNNING start/recovery | Пользователь подтвердил автоматическое подключение обоих окон, одинаковый отсчёт и доступные владельцу Pause/Finish; снимок владельца показывает CONNECTED, 11:13, собственные 1/1 и чужие 0/1 задачи; SQL подтвердил исходный deadline | PASS — оба USER_REPORTED, владелец SCREENSHOT, 11 SERVER_SIDE SQL invariants |
| Завершение и история | Пользователь завершил FOCUS, оба окна показали итог 4 мин 57 сек, владелец 1/1 и участник 0/1; SQL подтвердил COMPLETED/MANUAL, 297 900 ms и ровно одну историю этого раунда | PASS — оба USER_REPORTED, владелец SCREENSHOT, 11 SERVER_SIDE SQL invariants |
| BREAK baseline | Пользователь сообщил «перерыв пошёл», SQL подтвердил отдельный BREAK RUNNING 60 s, revision 12 | PASS — USER_REPORTED + SERVER_SIDE SQL, без нового PNG |
| BREAK offline 00:00 | Пользователь подтвердил 00:00 и ожидание сервера только во втором окне; в первом отсчёт не был виден. Итог не появился, задачи сохранились, кнопки заблокированы, ручного refresh не было. SQL после deadline подтвердил отсутствие BREAK history при остановленной Java | PARTIAL USER_REPORTED baseline; свежий повтор обоих PASS ниже; SERVER_SIDE PASS |
| Просроченный BREAK recovery, baseline | SQL подтвердил единственную ELAPSED-историю; агент увидел автоматический BREAK-итог владельца. Участник CONNECTED, но остался на прежнем FOCUS; BREAK доступен через sidebar «История» без Refresh | Первоначальный navigation DEFECT; исправлен и повторно проверен ниже |
| Исправленный BREAK recovery, свежие JVM | Оба окна автоматически показали новый активный BREAK, offline 00:00/ожидание и новый итог после start; SQL подтвердил единственную ELAPSED-историю | PASS — оба ROOT_OBSERVED + SCREENSHOT + SQL |
| TTL отдельного тестового стенда | PT30S, SQL lifetime ровно 30 s, настоящий Compose сам вернулся ко входу с пустым паролем и подписью expiry, без reconnect-loop | PASS — ROOT_OBSERVED + SCREENSHOT + SQL; auth baseline 0da9c6c |

Runtime-команды выполняются по контрольным точкам, без управления UI агентом. После фактического stop пользователь сообщил: «В обоих окнах появилось переподключение, задачи сохранились, таймер на паузе 15:00, кнопки заблокированы. Запускай сервер». Это USER_REPORTED наблюдение обоих окон и прямое указание запустить Java; до него start не выполнялся. Результат восстановления записывается только после следующего фактического подтверждения.

Получен [исходный снимок окна участника во время offline](screenshots/stage-8-2-paused-offline-peer.png), скопирован без редактирования. На нём `gui82_b_877ea4a7b3de`, комната `FD-MHYWYU`, 2/2, «Восстанавливаем соединение…», попытка 5, следующий повтор через 9 s, PAUSED 15:00, сохранённые цели/задачи; недоступны собственные add/edit. Кнопки таймера у участника также disabled, но они недоступны этому пользователю и по роли, поэтому отдельным доказательством offline-блокировки таймера служит отчёт пользователя об обоих окнах. Это SCREENSHOT одного окна, не общий снимок двух окон.

PNG **1919×1189**, SHA-256 оригинала и репозиторной копии одинаков: `328ACCC00ADAF88DCCE185131A1B01C7F98E2BC11071E967102ADDB758199605`. Число новых скриншотов этапа 8.2 на этом checkpoint — **1**. Автоматического управления интерфейсом для получения снимка не было.

После start пользователь сообщил: «Оба окна восстановились автоматически, комната и задачи сохранились, таймер на паузе 15:00. У владельца кнопка Продолжить доступна». Это USER_REPORTED результат обоих окон, включая право владельца продолжить раунд. [Исходный снимок восстановленного участника](screenshots/stage-8-2-paused-recovered-peer.png), без редактирования, подтверждает `gui82_b_877ea4a7b3de`, `FD-MHYWYU`, 2/2, «Соединение установлено», PAUSED 15:00 и доступные собственные edit/add. Таймерные кнопки участника остаются disabled согласно роли. PNG **1919×1192**, SHA-256 исходника и копии: `C80C67907DF2E85B19F17F46A3B88D2F135810897DA9CE440B94A0401182B086`. Итого новых PNG на PAUSED recovery checkpoint — **2**.

Перед остановкой сохранён [read-only SQL baseline ручного сеанса](evidence/stage-8-2-ui-paused-before.json): room `FD-MHYWYU`, revision 9, тот же PAUSED round с activeElapsed 14 ms; 2 активных участника и 2 неотозванные сессии. Общие счётчики после harness + seed + двух ручных login: 13 пользователей, 19 сессий, 6 комнат / 1 OPEN, 6 задач, 4 раунда / 3 истории, 48 idempotency results, 2 миграции. Результата текущего PAUSED FOCUS ещё нет.

[Серверная остановка](evidence/stage-8-2-ui-paused-stop.json) подтверждена **11 октября 2026, 02:02:16 +05:00**: generation 4, `javaPid=null`, слушателя `8090` нет. Изолированная PostgreSQL `55438`, PID `6124`, и пользовательская PostgreSQL `5432`, PID `10172`, продолжают работать. Поле `uiOfflineObservation` этого прежнего checkpoint — `AWAITING_USER_REPORT`; его не переписывали задним числом. Последующее UI-подтверждение пользователя и снимок записаны отдельно выше.

После команды «Запускай сервер» [Java успешно запущена](evidence/stage-8-2-ui-paused-start.json): PID `7316`, generation 5, `8090` LISTEN; PostgreSQL сохранила PID `6124`. **9 server-side invariants PASS**: тот же roomId, revision 9, roundId, PAUSED, activeElapsed 14 ms, history 3, tasks 6, sessions 19 и unrevoked sessions 2. SQL доказывает серверную готовность/сохранность; последующее автоматическое восстановление UI подтверждено отдельно USER_REPORTED и снимком участника выше.

### RUNNING baseline и второй stop

Пользователь вручную нажал «Продолжить» у владельца и сообщил: «Таймер идёт в обоих окнах, задачи сохранились. Отключай сервер, проверим восстановление». Это USER_REPORTED обоих окон; нового RUNNING-снимка ещё нет.

Перед следующим stop снят [SQL RUNNING baseline](evidence/stage-8-2-ui-running-before.json): прежний roundId `d8f043e0-4807-4657-ac0b-2bde0eff27c9`, room revision **10**, статус RUNNING, исходный `endsAt` **2026-10-11T02:33:18.373+05:00**. Остаток, вычисленный по SQL `inspectedAt`, **860 935 ms** (округлён вниз); сохранённое поле `activeElapsedMs` — 14 ms (накопленное до продолжения, не всё уже прошедшее RUNNING-время). Этот deadline служит эталоном после нового старта Java.

[Вторая остановка Java](evidence/stage-8-2-ui-running-stop.json) подтверждена в **02:19:09.671 +05:00**: generation 5, `javaPid=null`, слушателя `8090` нет. PostgreSQL `55438` PID 6124 и пользовательская `5432` PID 10172 сохранены.

Затем пользователь сообщил: «В обоих окнах появилось переподключение, задачи сохранились, кнопки заблокированы. Таймеры продолжают отсчёт и показывают одинаковое время. Запускай сервер». Это **RUNNING offline PASS — USER_REPORTED обоих окон**, без нового PNG. Продолжающийся локальный отсчёт во время сетевого разрыва не является новым серверным раундом или результатом. Пользователь дал команду запустить Java; следующие серверные и GUI-наблюдения фиксируются отдельно ниже.

После этой команды [Java повторно запущена](evidence/stage-8-2-ui-running-start.json): PID **4552**, generation **6**, PostgreSQL сохранила PID 6124. **11 server-side invariants PASS**: тот же roomId/revision 10/roundId, RUNNING, исходные `startedAt` и `endsAt`, накопленное activeElapsed, прежние история/задачи/число сессий и две неотозванные сессии. `endsAt` остался **2026-10-11T02:33:18.373+05:00**. Остаток по SQL `inspectedAt` уменьшился **860 935 → 759 249 ms**, разница **101 686 ms** между чтениями; целые миллисекунды округлены вниз, исходные timestamps не изменены. Это вычисление по времени SQL-чтения, не REST `serverNow`. Длительность не сбросилась к 15 минутам; это server-side восстановление и сохранность.

Затем пользователь подтвердил: оба окна подключились автоматически, таймеры идут одинаково, у владельца доступны «Пауза» и «Завершить». **RUNNING GUI recovery PASS — USER_REPORTED обоих окон**. [Исходный снимок владельца после восстановления](screenshots/stage-8-2-running-recovered-owner.png), без редактирования, показывает `gui82_a_877ea4a7b3de`, `FD-MHYWYU` 2/2, «Соединение установлено», остаток **11:13**, собственные задачи 1/1 и задачи партнёра 0/1, доступные Pause/Finish. PNG **1919×1193**, SHA-256 оригинала и копии: `E1BF4ACE84C11140D4293BFEB429F13F5165943AED6D0FDACC9F49BB04AB50A8`. Всего сохранено **3 исходных PNG** этапа 8.2. Одновременный снимок обоих окон и отдельное измерение чистой WS-задержки не заявляются.

### Ручное завершение FOCUS и уникальная история

Пользователь вручную завершил FOCUS и сообщил, что оба окна показали итог: у владельца 1/1, у участника 0/1; BREAK ещё не запускал. [Исходный PNG итога владельца](screenshots/stage-8-2-focus-summary-owner.png) подтверждает «Фокус завершён досрочно», **4 мин 57 сек**, MANUAL, план 15 мин, те же счётчики задач и отдельную доступную кнопку «Начать перерыв · 1 мин». PNG **1919×1199**, SHA-256 оригинала и неизменённой копии: `7F271D67E1F3654E1503C21AD15220BB4B023598A986CE6273983E8B4A900FE4`. Итого на этом checkpoint — **4 исходных PNG**.

[Read-only SQL после finish](evidence/stage-8-2-ui-focus-finished.json): **11 invariants PASS**, прежний roundId, COMPLETED/MANUAL, room revision **11**, activeElapsed **297 900 ms**, ровно **одна** история данного раунда. Общая история выросла 3 → 4, раундов осталось 4: BREAK не создан. История совпадает с round по elapsed, владелец 1/1 и участник 0/1. По точным ISO `endsAt` и `endedAt` вычисляется то же активное время `900 000 − (endsAt − endedAt) = 297 900 ms`; пауза не входит. SQL timestamps сохраняют миллисекунды, производные значения считаются без преобразования ISO-строк в локализованную строку даты.

### BREAK 60 s и stop до deadline

Пользователь отдельно нажал начало перерыва и сообщил **«перерыв пошёл»** — USER_REPORTED, без нового PNG. [SQL перед остановкой BREAK](evidence/stage-8-2-ui-break-before.json) подтвердил room revision **12**, BREAK `74076061-2a74-4b20-b162-a3576074c218`, RUNNING, duration 60 s, `startedAt` **2026-10-11T02:26:06.549+05:00**, `endsAt` **02:27:06.549+05:00**. При SQL-чтении оставалось **24 147 ms**; история BREAK ещё отсутствовала, общая история 4, сохранённый FOCUS snapshot присутствовал отдельно.

[Java остановлена](evidence/stage-8-2-ui-break-stop.json) в **02:26:43.659+05:00**, до исходного deadline: generation **6**, `javaPid=null`, слушателя `8090` нет. PostgreSQL `55438` PID 6124 и пользовательская `5432` PID 10172 не изменились. После stop ожидали пользовательский отчёт об offline-нуле; start не выполнялся до него. На этом раннем checkpoint серверный COMPLETED/ELAPSED ещё не был зафиксирован; последующие SQL и GUI-наблюдения записаны ниже.

Пользователь затем сообщил: **во втором окне 00:00 и ожидание завершения от сервера; в первом не был виден отсчёт**. Итог BREAK не появился, задачи сохранились, кнопки были заблокированы; вручную ничего не обновлял. Это частичное USER_REPORTED наблюдение, без нового PNG: оба окна на 00:00 не считаются проверенными. В этом первом наблюдении видимость отсчёта одного окна не подтверждена; последующий свежий повтор обоих окон приведён ниже. Пользователь дал команду запустить сервер и прямо попросил агента снова проверять Compose-окна; с этого checkpoint дальнейшая UI-проверка возвращена агенту.

Перед start снят [read-only SQL уже после deadline при выключенной Java](evidence/stage-8-2-ui-break-expired-offline.json), **02:29:18.586965+05:00**: BREAK всё ещё RUNNING, результата BREAK нет, общая история остаётся 4, FOCUS snapshot неизменён. Три server-side checks PASS: deadline прошёл, клиент не создал серверное завершение, FOCUS сохранён. Этот SQL не заменяет отсутствующее визуальное наблюдение первого окна.

После команды пользователя [Java запущена generation 7 / PID 17132](evidence/stage-8-2-ui-break-start.json). **7 server-side checks PASS:** ровно одна история BREAK, COMPLETED/ELAPSED, `endedAt` равен исходному deadline **02:27:06.549+05:00**, activeElapsed **60 000 ms**, FOCUS history не изменилась, число раундов осталось 5, idempotency results осталось 51. Общая история выросла **4 → 5**, room revision **13**. Серверная часть просроченного ELAPSED подтверждена; последующее UI-наблюдение описано ниже, отдельно от SQL.

### Осмотр двух окон агентом: owner PASS, peer navigation DEFECT

После прямой просьбы пользователя агент снова проверил настоящие окна. Владелец, HWND `7997420`, при первом осмотре **после запуска Java** показывал [00:00 и ожидание завершения от сервера](screenshots/stage-8-2-break-zero-waiting-owner.jpg), изменения недоступны. Затем без ручного Refresh автоматически открыл [«Перерыв завершён · 1 мин»](screenshots/stage-8-2-break-summary-owner.jpg). Это owner UI PASS. Снимок ожидания сделан после start, поэтому не выдаётся за наблюдение владельца на нуле при выключенной Java.

Участник, HWND `919906`, подключился, но остался на старом FOCUS-итоге 4 мин 57 сек с уведомлением «Раунд завершён. Сохранённый итог доступен в истории»: [первичный осмотр](screenshots/stage-8-2-break-recovery-peer-initial.jpg), [повторная проверка](screenshots/stage-8-2-break-peer-stale-focus.jpg). Через sidebar «История», без нажатия Refresh, доступны [две записи FOCUS и BREAK](screenshots/stage-8-2-break-history-peer.jpg). Агент вручную открыл BREAK из списка: [«Перерыв завершён · 1 мин», владелец 1/1, участник 0/1](screenshots/stage-8-2-break-summary-peer-manual.jpg). Это **MANUAL_NAVIGATION**, не автоматический переход. Восстановление связи и доступность серверных данных — PASS, потери результата нет; автоматическое переключение текущего результата на BREAK — **navigation DEFECT**.

Пользовательские «первое» и «второе» окна не были идентифицированы по username, поэтому прежнюю невидимость отсчёта первого окна не приписываем владельцу или участнику. Новые наблюдения агента привязаны к конкретным HWND/аккаунтам и не подменяют прежний частичный USER_REPORTED checkpoint.

На этом baseline сохранено **10 реальных снимков** этапа 8.2: **4 PNG** пользователя и **6 JPEG** текущих окон агентом. Native sky вернул JPEG payload; его ошибочные первоначальные `.png`-расширения исправлены на `.jpg` простым переименованием, без изменения bytes. Все шесть SHA-256 до/после совпали. [Метаданные, размеры и хеши изображений](evidence/stage-8-2-screenshots.json).

### Исправление live-result navigation и регрессионные тесты

Причина воспроизведённого дефекта — экран RESULT не следовал смене раунда своей комнаты: старый автоматически открытый FOCUS-итог оставался выбранным после нового BREAK. В `RealApp.kt` и `LiveResultNavigation.kt` добавлено различие между автоматически открытым текущим результатом и вручную выбранной историей. Автоматический итог переходит к активному новому раунду той же комнаты; если активный BREAK целиком прошёл offline, после восстановления открывается его новый серверный результат. Ручной просмотр истории сохраняется. Открытые формы, pending replay и conflict временно блокируют перенаправление. Запоздалый ответ истории проверяется по текущим roomId/roundId, чтобы старый FOCUS не заменил новый BREAK. При смене roundId сбрасывается прокрутка комнаты; обычный tick/revision/history её не сбрасывает.

Добавлены **9 сценарных тестов** `LiveResultNavigationTest`: переход от автоматического FOCUS к BREAK, пропущенный целиком offline BREAK, PAUSED next round, CANCELLED при закрытии, защита вручную открытой истории, посторонние страницы/комнаты, блокирующие формы/pending/conflict, прежнее завершение HOME/ROOM и запоздалый ответ старого результата. На извлечённом старом HOME/ROOM-правиле **5 тестов упали / 4 прошли**; после исправления **9/9 PASS**. Targeted-набор: **41 тест** — 9 новых, 4 history, 13 room loopback, 15 recovery loopback; 0 failures/errors/skipped, Gradle 7 s. Это предварительная проверка исправления, не финальный полный прогон.

После предварительного targeted-прогона независимый review выявил ещё два краевых случая: сохранение происхождения результата при retry и запоздалая ошибка автоматического GET. Они исправлены: неуспешная загрузка запоминает auto/manual происхождение, поздний success/error старого FOCUS не заменяет новый BREAK и его retry, а ошибка вручную открытой истории сохраняется при фоновом чтении нового результата. Итоговый targeted-набор расширен до **44 тестов: 12 новых + 4 history + 13 room loopback + 15 recovery loopback**, 0 failures/errors/skipped, Gradle **8 s**. Набор 9/41 выше относится только к первому варианту исправления; первоначальные RED 5/9 не являются red-прогоном всех 12 финальных сценариев.

После review выполнен окончательный `test check backendAuthSmoke backendRoomSmoke --rerun-tasks`: **199 обычных + 1 auth + 1 room**, 0 failures/errors/skipped, BUILD SUCCESSFUL за **24 s**, exit 0. [Final test proof](evidence/stage-8-2-final-tests.json) содержит числа разобранного JUnit XML и SHA-256 всех трёх изменённых production/test-файлов. Добавленные 12 тестов входят в 199, а targeted 44 не прибавляются повторно. Затем окончательный код проверен в двух свежих JVM, как описано ниже. Прежние **187 + 2 / четыре harness** выполнены до этой новой production-правки; четыре harness после исправления повторно не заявляются.

Оба старых клиента успешно вышли из аккаунтов через UI без ошибки UNAUTHORIZED и были закрыты через Alt+F4. Для свежего повтора подготовлены отдельные профили `fixed-recovery-a` / `fixed-recovery-b` и `ttl-client`; сохраняются только baseUrl/username, тестовые секреты не записываются в preferences. Серверные стенды пока работают.

## Свежий GUI-повтор после navigation-fix

После окончательного fullcheck запущены два новых JVM с отдельными неизменяемыми application JAR: владелец HWND **1051124** / профиль `fixed-recovery-a`, участник HWND **10488298** / `fixed-recovery-b`. Оба вошли в исходные синтетические аккаунты. Эти процессы используют окончательный navigation-fix; старые окна закрыты заранее. Агент проверяет UI напрямую.

При входе участник автоматически открыл [существующий BREAK-итог](screenshots/stage-8-2-fixed-initial-break-peer.jpg). Владелец начал следующий FOCUS; участник без ручной навигации показал [ROOM с 14:56](screenshots/stage-8-2-fixed-focus-running-peer.jpg). Владелец завершил FOCUS через подтверждение; оба окна автоматически показали итог, [снимок участника — 25 секунд](screenshots/stage-8-2-fixed-focus-summary-peer.jpg). Затем владелец начал BREAK: [его ROOM 01:00](screenshots/stage-8-2-fixed-break-running-owner.jpg), [участник сам перешёл к ROOM 00:55](screenshots/stage-8-2-fixed-break-running-peer.jpg), без sidebar navigation/Refresh/reconnect. **Переход от автоматического результата к активному следующему раунду PASS — ROOT_OBSERVED + SCREENSHOT.**

Для повторения expired offline BREAK [SQL baseline](evidence/stage-8-2-fixed-break-before.json) зафиксировал новый BREAK `681d704e-74ac-4dbc-8087-d1b1cd9b31be`, room revision **16**, историю **8**, `startedAt` **2026-10-11T02:46:33.375+05:00**, `endsAt` **02:47:33.375+05:00**, при чтении оставалось **24 348 ms**. [Java принудительно остановлена](evidence/stage-8-2-fixed-break-stop.json) в **02:47:10.505+05:00**, до deadline: generation 7, PostgreSQL сохранена. [SQL после deadline +8.438 s при выключенной Java](evidence/stage-8-2-fixed-break-expired-offline.json) подтвердил BREAK RUNNING без истории, revision 16, неизменные общие history 8 / rounds 9 / idempotency 67 и оба FOCUS snapshots.

**Оба свежих окна на нуле offline PASS — ROOT_OBSERVED + SCREENSHOT:** [участник](screenshots/stage-8-2-fixed-break-zero-peer.jpg), **2026-10-10T21:49:49.538Z**, и [владелец](screenshots/stage-8-2-fixed-break-zero-owner.jpg), **21:49:54.887Z**, показали reconnect, BREAK **00:00**, сохранённые задачи, недоступные изменения и «Ждём завершения от сервера». После запуска этого BREAK агент не нажимал Refresh/reconnect/ручную навигацию.

[Java повторно стартовала](evidence/stage-8-2-fixed-break-start.json), generation **8**, PID **32440**, с прежней PG `55438` PID 6124. **8 SQL invariants PASS:** ровно одна BREAK history, COMPLETED/ELAPSED, activeElapsed 60 000 ms, endedAt равен исходному deadline **02:47:33.375+05:00**, оба FOCUS snapshots неизменны, round count 9 и idempotency 67 прежние, revision вырос ровно 16 → 17. Общая история 8 → 9. Это серверный результат того же BREAK, без нового client-side завершения.

После start оба окна без ручного вмешательства восстановили CONNECTED и показали **«Перерыв завершён / 1 мин»**: [владелец](screenshots/stage-8-2-fixed-break-summary-owner.jpg), **2026-10-10T21:50:32.496Z**; [участник](screenshots/stage-8-2-fixed-break-summary-peer.jpg), **21:50:37.498Z**. В обоих сохранены задачи владельца 1/1 и участника 0/1. **Свежий GUI-повтор navigation-fix PASS — оба ROOT_OBSERVED + SCREENSHOT + SQL.** Он закрывает найденный дефект автоматического перехода и неполноту первого offline-наблюдения в границах нового BREAK; прежнее частичное USER_REPORTED наблюдение не переименовывается в PASS задним числом.

Итого этап 8.2 сохраняет **21 реальный снимок: 4 исходных PNG пользователя + 17 JPEG агента**, bytes/SHA-256/MIME проверены в [manifest](evidence/stage-8-2-screenshots.json). Основные два свежих окна и recovery-стенд оставлены запущенными для просмотра пользователем.

## Отдельный короткий TTL-стенд: SQL и UI PASS

Параллельно поднят отдельный native runtime **Java `8091` / PostgreSQL `55439`**, каталог `desktop/build/stage8-2-ttl-20261011/`, session TTL **PT30S**. Это тестовый override только этого процесса, обычный сервер `8090` и default PT24H не меняются. Corretto 21.0.6 / PG 17.2 / Spring Boot 3.5.16, тот же SHA-256 JAR. [Безопасный setup proof](evidence/stage-8-2-ttl-setup.json): readiness 401/UNAUTHORIZED, lifetime обеих seed-сессий по SQL **30.0 s**, seed finally-logout выполнен.

Подготовлена PAUSED-комната `FD-VP26SM`, revision 9, два синтетических аккаунта; пароли/токены не публикуются. SQL baseline: 2 пользователя, 1 OPEN-комната, 1 PAUSED FOCUS, 2 задачи, 0 истории, 2 seed-сессии обе отозваны.

В настоящий Compose-клиент выполнен вход. [SQL UI-сессии до и после expiry](evidence/stage-8-2-ttl-expiry.json) подтвердил переход `expired: false → true`: createdAt **2026-10-10T21:41:11.978172Z**, expiresAt **21:41:41.978172Z**, lifetime ровно **30 s**. PAUSED-комната/revision 9/round сохранились, история 0; сессий 3 — две seed revoked, одна UI истекшая и не отозванная вручную.

Агент зафиксировал [подключённую ROOM с PAUSED 15:00](screenshots/stage-8-2-ttl-connected.jpg) в **21:41:27.117Z**, затем не нажимал logout/reconnect/refresh. В **21:41:59.638Z** клиент автоматически перешёл к [форме входа](screenshots/stage-8-2-ttl-expired.jpg) с сообщением «Сессия истекла или отозвана. Войди снова» и пустым паролем. Повторный осмотр в **21:42:43.510Z** подтвердил ту же форму без цикла reconnect. **UI TTL PASS**, ROOT_OBSERVED + SCREENSHOT + SERVER_SIDE SQL. Клиент использует неизменяемый application JAR baseline `0da9c6c`; auth-код новой навигационной правкой не меняется. Этот результат не выдаётся за fresh GUI-проверку нового navigation-fix.

TTL-окно закрыто агентом. [TTL cleanup](evidence/stage-8-2-ttl-cleanup.json) завершился exit **0**: собственные Java/PG/контроллер остановлены, слушателей `8091` / `55439` нет, временный PG data удалён. Основной recovery `8090`/`55438` и пользовательский `5432` сохранили прежние listeners/PID. Cleanup TTL не является завершением основного стенда. После TTL добавлены ещё два native JPEG; всего **12 снимков: 4 PNG + 8 JPEG**, размеры/хеши в общем [manifest](evidence/stage-8-2-screenshots.json).

## Текущее состояние runtime

Основной Java `8090` **ALIVE / PID 32440 / generation 8**, PG `55438` PID 6124 и контроллер PID 18168 ALIVE. Два свежих Compose-окна оставлены для просмотра пользователем. [Последний read-only SQL](evidence/stage-8-2-final-db.json): 16 пользователей, 25 сессий / 2 неотозванные, 7 комнат / 1 OPEN, 2 активных членства, 7 задач, 9 раундов / 9 историй / 18 history participants, 67 idempotency results, 2 миграции; это ожидаемое состояние живого стенда, не teardown. Отдельный TTL `8091`/`55439` **STOPPED/exit 0**, его собственная PG data удалена. Пользовательская PostgreSQL `5432` сохраняет PID 10172. Исправление navigation DEFECT прошло final 199 + 2 и свежий GUI-повтор, включая expired offline BREAK. [Фактический статус обоих стендов](evidence/stage-8-2-final-stand-status.json) сохранён. Основной runtime намеренно ещё не остановлен; освобождение `8090`/`55438` не заявляется. Cleanup сейчас не требуется для просмотра результата. Пользовательская PostgreSQL и `.env` не используются. После окончания просмотра закрыть свои окна, снять итоговый read-only SQL и отправить `shutdown` своему контроллеру; только после подтверждённого `STOPPED` можно записать фактическое завершение стенда.

## Воспроизведение

Из корня репозитория при свободных `8090` / `55438`, Python 3.9+, JDK 21, PostgreSQL 17 binaries и уже собранном `backend/target/focusduo-backend-0.1.0.jar`. В первой PowerShell-оболочке:

```powershell
python .\desktop\scripts\stage8_runtime.py --java-home "C:\Users\user\.jdks\corretto-21.0.6" --server-port 8090 --db-port 55438 --run-dir desktop/build/stage8-2-local
```

Путь JDK заменить своим; `stage8-2-local` должен быть новым каталогом. Дождаться `READY` и оставить контроллер работающим. Во второй оболочке:

```powershell
$env:JAVA_HOME='C:\Users\user\.jdks\corretto-21.0.6'
$env:PATH=(Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
.\desktop\gradlew.bat -p desktop test check backendAuthSmoke backendRoomSmoke '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' --rerun-tasks --console=plain
$stage8Ipc=Join-Path (Get-Location).Path 'desktop/build/ipc'
New-Item -ItemType Directory -Path $stage8Ipc -Force | Out-Null
$env:JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=$($stage8Ipc.Replace('\', '/'))"
.\desktop\gradlew.bat -p desktop -I (Resolve-Path desktop/scripts/stage8-2-smoke.gradle).Path realRepositorySmoke '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' --console=plain
.\desktop\gradlew.bat -p desktop -I (Resolve-Path desktop/scripts/stage8-2-smoke.gradle).Path realRoomLifecycleSmoke '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' --console=plain
.\desktop\gradlew.bat -p desktop -I (Resolve-Path desktop/scripts/stage8-2-smoke.gradle).Path realRecoverySmoke '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' --console=plain
.\desktop\gradlew.bat -p desktop -I (Resolve-Path desktop/scripts/stage8-2-smoke.gradle).Path realRestartSmoke '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' "-Dfocusduo.smoke.controlDir=$((Resolve-Path desktop/build/stage8-2-local).Path)" --console=plain
```

Новый desktop init-script компилирует существующие backend harness без изменения их исходников и сохраняет outputs в `desktop/build/stage8-2-smoke/`.

Для нового пустого стенда перед GUI можно повторить подготовку `stage8-2-seed.py`. Она создаёт новые синтетические аккаунты, цели, по одной задаче и PAUSED FOCUS, отзывает обе собственные seed-сессии и сохраняет только usernames/room snapshot. Требует временный пароль 10–128 символов в переменной процесса `FOCUSDUO_QA_PASSWORD`; пароль не печатается и не сохраняется скриптом. В уже подготовленном текущем runtime **повторять seed не нужно**. После задания переменной на новом стенде:

```powershell
python .\desktop\scripts\stage8-2-seed.py --base-url http://127.0.0.1:8090 --output desktop/build/stage8-2-local/ui-seed.json
```

Для двух клиентов, по одной команде в отдельной оболочке:

```powershell
.\desktop\scripts\run-integration-client.ps1 -Profile stage82a -BaseUrl http://127.0.0.1:8090
.\desktop\scripts\run-integration-client.ps1 -Profile stage82b -BaseUrl http://127.0.0.1:8090
```

Для ручного GUI-сценария после окончания harness отправить контроллеру атомарную команду. `stop` оставляет БД работающей, `start` запускает Java на той же БД; между ними наблюдать оба окна. Менять только `$stage8Action`, повторяя блок с новым UUID:

```powershell
$stage8Action='stop' # При восстановлении заменить на 'start'.
$stage8Run=(Resolve-Path desktop/build/stage8-2-local).Path
$stage8Id=[guid]::NewGuid().ToString()
$stage8Temp=Join-Path $stage8Run 'commands.tmp'
$stage8Command=Join-Path $stage8Run 'commands.json'
@{id=$stage8Id; action=$stage8Action} | ConvertTo-Json | Set-Content -LiteralPath $stage8Temp -Encoding UTF8
[System.IO.File]::Move($stage8Temp, $stage8Command, $true)
```

Дождаться `result.json` с тем же `$stage8Id` и `status: "ok"`; старый ответ с другим id не подтверждает новую команду. Для безопасной SQL-сверки использовать `action='inspect'`: контроллер сохранит `sql-inspection.json` без секретов. Не отправлять команды одновременно из нескольких оболочек.

Этот вариант проверен в PowerShell 7.6.5. `File.Replace(..., $null)` не использовать: PowerShell передавал пустой backup path и замена завершалась ошибкой.

После проверки закрыть оба окна, снять итоговый read-only SQL, затем остановить именно свой контроллер:

```powershell
New-Item -ItemType File -Path .\desktop\build\stage8-2-local\shutdown
```

Дождаться `STOPPED`: контроллер завершает свои Java/PostgreSQL, удаляет только свой временный кластер и сохраняет логи. Для одновременной работы с GUI сначала дождаться окончания harness: restart harness сам останавливает сервер.

## Ручная приёмка сна и сети — версионный реестр

| Проверка | Действия и ожидаемый результат | Статус |
| --- | --- | --- |
| Физический сон, PAUSED | Отдельный backend GUI-прогон: Modern Standby 73.5048001 s, оба клиента до/после 24:45, комната и задачи сохранены. При осмотре окна активировали | PASS на `8fdb392`; в собственном прогоне 8.2 NOT_RUN; повтор на последней сборке NOT_RUN |
| Физический сон, RUNNING | Отдельный backend GUI-прогон: Modern Standby 117.0503097 s, отсчёт учёл прошедшее время, без refresh/relogin/restart. Задержка восстановления не измерена | PASS на `8fdb392`; в собственном прогоне 8.2 NOT_RUN; повтор на последней сборке NOT_RUN |
| Возвращение в окно | Убрать фокус минимум на 5 секунд, изменить состояние вторым клиентом, вернуться. Ожидается автоматическая актуализация; короткое переключение не должно создавать повторные команды | NOT_RUN |
| Отключение адаптера | При существующей комнате отключить именно используемый адаптер, дождаться блокировки изменений, восстановить его. Оба клиента должны восстановить REST/WS автоматически, без перехода в демо | NOT_RUN |
| LAN | Запустить второй клиент на другом ПК с доступным адресом backend. Повторить room/task/pause/reconnect/finish; записать реальные адреса, правила firewall и результаты | NOT_RUN |
| TTL | Отдельный PT30S runtime: SQL lifetime 30 s и expiry подтверждены. Настоящий Compose автоматически показал форму входа с пустым паролем и подписью expiry, повторный осмотр без reconnect-loop; logout/refresh/reconnect не нажимались | PASS — ROOT_OBSERVED + SCREENSHOT + SQL; immutable baseline 0da9c6c |
| Просроченный ELAPSED | BREAK 60 s на окончательном navigation-fix: оба свежих окна offline 00:00/ожидание, затем оба автоматически на серверном результате после restart. Отдельный FOCUS 300 s позднее проверен на `1a6fa94`: оба auto-итога и одна history с исходным deadline | BREAK PASS в этом прогоне; FOCUS PASS в [отдельном актуальном протоколе](FOCUS_EXPIRY_ACCEPTANCE.md) |

Физический сон подтверждается только [отдельным отчётом](../../docs/backend/COMPOSE_ACCEPTANCE.md) и его 29 оригинальными JPG. Автоматический ephemeral runtime не подменяет ручную приёмку; строки с NOT_RUN остаются условиями будущего наблюдения.

## Публикация результата

Изменения только в `desktop/**`. Этап 8.1 объединён в `main` через PR #16. Проверенный `origin/main` — `ad5da2a`; `git merge-base --is-ancestor 0da9c6c origin/main` завершился успешно. PR этапа 8.2 направляется прямо в `main`; merge/rebase рабочего дерева для смены базы не выполнялись.

```powershell
Set-Location "C:\Users\user\.codex\worktrees\desktop-wake-recovery\focusDuo"
git status --short --branch
git add -- desktop
git diff --cached --stat
git commit -m "fix(desktop): follow live rounds and verify backend recovery"
git push -u origin feat/desktop-stage8-2
```

Команды предназначены пользователю для выполнения по очереди с проверкой ошибок. [Заголовок и описание PR](PR_STAGE_8_2.md). Их наличие не означает, что commit/push/PR уже выполнены.
