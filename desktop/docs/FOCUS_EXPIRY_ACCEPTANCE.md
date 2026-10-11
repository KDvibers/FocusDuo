# Просроченный FOCUS после остановки backend

Дата: **12 октября 2026, Asia/Qyzylorda (UTC+05:00)**. Ветка `feat/desktop-focus-expiry` от объединённого `main` **`1a6fa94`**; PR #17 с физическим сном и PR #18 с desktop 8.2 уже входят в базу.

**PASS — ROOT_OBSERVED + SCREENSHOT + SQL.** Два свежих Compose-процесса автоматически достигли offline 00:00/ожидания, затем после запуска Java на той же PostgreSQL показали FOCUS-итог **5 минут**, задачи владельца **1/1**, участника **0/1**. SQL подтвердил один COMPLETED/ELAPSED-раунд и одну историю, **300000 ms** активного времени и `endedAt`, точно равный исходному `endsAt`. Refresh/reconnect/relogin и повтор изменяющей команды не использовались. Сохранены **13 оригинальных JPEG**; оба пользователя вышли, собственные окна и изолированный стенд завершены. Это не повтор физического сна Windows или LAN.

## Проверяемые условия

| Условие | Ожидаемое доказательство | Статус |
| --- | --- | --- |
| Актуальная сборка | Два новых JVM с immutable application JAR от `1a6fa94`, профили `focus-expiry-a` / `focus-expiry-b`, обычный режим `RealRepository`/CIO | PASS — ROOT_OBSERVED |
| Исходный FOCUS | Одна комната, два участника, собственные цели/задачи, один RUNNING FOCUS 300 s; сохранены `roundId`, `startedAt`, исходный `endsAt` | PASS — ROOT_OBSERVED + SCREENSHOT + SQL |
| Java остановлена до срока | Owned runtime stop в 01:43:51.366+05 оставил PostgreSQL работающей; исходный deadline 01:48:01.440+05 | PASS — runtime + SQL |
| Offline до нуля | Оба окна показывают переподключение и сохранённые задачи/цели; UI-прокруткой подтверждены отключённые Pause/Finish владельца | PASS — ROOT_OBSERVED + SCREENSHOT |
| Ноль без сервера | Оба окна показывают 00:00/ожидание серверного завершения, задачи сохранены, изменения заблокированы; read-only SQL: RUNNING, история ещё отсутствует | PASS — ROOT_OBSERVED + SCREENSHOT + SQL |
| Восстановление | После start с той же БД оба окна без refresh/relogin/reconnect открыли FOCUS/ELAPSED и показали 5 минут | PASS — ROOT_OBSERVED + SCREENSHOT |
| Единственный итог | SQL: один FOCUS и одна история, COMPLETED/ELAPSED, `active_elapsed_ms=300000`, `endedAt` равен исходному `endsAt`, цели/задачи сохранены | PASS — 18 SQL-инвариантов |
| Стабильность результата | Второй read-only SQL сохраняет один раунд/историю, revision, задачи, цели и idempotency results | PASS — 8 SQL-инвариантов |
| Итоговое состояние стенда | Обе GUI-сессии отозваны, собственные окна/Java/PG/controller завершены, временная PG data удалена, пользовательская PG 5432 сохранена | PASS — GUI + SQL + cleanup |

## Среда и исходное состояние

Native Windows / **Corretto 21.0.6**, production Java backend **Spring Boot 3.5.16**, native PostgreSQL **17.2**. HTTP `http://127.0.0.1:8090`, отдельная PostgreSQL `127.0.0.1:55438`. Runtime — `desktop/build/focus-expiry-20261012`; controller PID **39024**, PostgreSQL PID **484**, исходная Java PID **19540**, generation **1**. Пользовательская PostgreSQL `5432` PID **10172** сохранена. Docker/WSL и demo не использовались.

Production backend не изменён и не пересобирался: исходники соответствуют `origin/main`, последний production-коммит `9153da6b5d58e1bfa2df3e9495741f293fac74a4`; использован существующий JAR SHA-256 **`A01FE560A161E6B89E7E653E8D034662DC8A205315A145F5F4CDBFB47E148A11`**. [Baseline и пустая БД](evidence/focus-expiry-baseline.json) содержат только безопасные поля.

REST-инструмент `stage8-2-seed.py --focus-seconds 300` подготовил два синтетических аккаунта, комнату **FD-JN2XEK** (`7b2f692d-a523-48a4-930f-9a323211025f`), длинные русские цели, по одной задаче (владелец **1/1**, участник **0/1**) и PAUSED FOCUS; две собственные seed-сессии отозваны. Это подготовка через REST, а не повтор регистрации/create/join в GUI. В инструмент добавлен параметр длительности: default 900 s сохранён, допустимы 300–10800 s кратно 60, BREAK по-прежнему 60 s.

[Исходный SQL](evidence/focus-expiry-seeded-paused.json): room revision **9**, FOCUS **`e99b023c-c5cc-480b-93b4-6a21bd041f36`**, `startedAt=2026-10-12T01:41:02.701+05:00`, накоплено **49 ms** до паузы, `endsAt=null`, история **0**. Два новых JVM вошли через реальные формы и показали PAUSED 05:00: [владелец](screenshots/focus-expiry-paused-owner.jpg), [участник](screenshots/focus-expiry-paused-peer.jpg).

## Продолжение и остановка Java

Владелец нажал «Продолжить» через UI. ROOT_OBSERVED: [владелец 04:54](screenshots/focus-expiry-running-owner.jpg), затем [участник 04:48](screenshots/focus-expiry-running-peer.jpg). Снимки последовательные; разные значения на них не являются одновременным измерением рассинхронизации. Ранние UTC-метки сохранения файлов не равны времени получения кадра и не используются для измерения точности таймера. После продолжения refresh/relogin/reconnect и изменяющие команды не выполнялись.

[SQL перед stop](evidence/focus-expiry-running-before-stop.json): **11 инвариантов PASS**, room revision **10**, тот же FOCUS RUNNING, исходный `endsAt=2026-10-12T01:48:01.440+05:00`, история по-прежнему **0**, две задачи/членства и две действующие GUI-сессии. Накопленные seed **49 ms** являются частью общих 300 s, а не добавочным временем после deadline.

[Stop](evidence/focus-expiry-java-stop.json) **01:43:51.366+05** завершил только собственную Java до исходного срока, порт 8090 освобождён, прежняя PostgreSQL `55438` PID 484 и пользовательская `5432` PID 10172 продолжают работать. Значение `runtime.stopped=false` означает, что весь контроллер ещё работает, а не то, что Java жива; `javaPid=null` подтверждает её остановку.

Оба окна показали «Восстанавливаем соединение…», сохранённые задачи/цели и отсчёт по ранее полученному сроку: [владелец offline](screenshots/focus-expiry-offline-owner.jpg), [участник offline](screenshots/focus-expiry-offline-peer.jpg). Прокрутка UI владельца открыла [недоступные Pause/Finish](screenshots/focus-expiry-offline-disabled-owner.jpg); это только изменение видимой области, а не ручная синхронизация или команда раунда. Снимки сделаны последовательно и не используются для точного сравнения одновременных показаний.

Оба окна показали FOCUS **00:00**, «Ждём завершения от сервера», сохранённые задачи и недоступные изменения; итог сам клиент не создал. Свежие capture вызовы имеют границы **12 октября, 01:48:07.076–01:48:07.210+05** для [владельца](screenshots/focus-expiry-zero-owner.jpg) и **01:48:18.421–01:48:18.527+05** для [участника](screenshots/focus-expiry-zero-peer.jpg). Это время получения кадров, а не измеренная задержка восстановления. Окна активировали и читали их состояние, refresh/reconnect/relogin не нажимали.

[Read-only SQL после исходного deadline](evidence/focus-expiry-expired-offline.json): **12 инвариантов PASS**. В **01:48:26.412+05**, спустя **24.972 s** после deadline, Java по-прежнему остановлена, PostgreSQL та же; раунд остаётся RUNNING с исходным `endsAt`, история **0**, revision **10**, задачи/цели/сессии неизменны. Клиентский ноль не создал ни завершения, ни записи истории без сервера.

## Запуск на той же PostgreSQL и серверный итог

[Start и SQL](evidence/focus-expiry-java-start.json): запрос start **01:48:54.395+05**, readiness наблюдалась **01:49:02.044+05**. Запущена Java PID **10952**, generation **2**; PostgreSQL осталась PID **484**. Время start/readiness не является временем восстановления клиентов.

**18 SQL-инвариантов PASS:** тот же единственный FOCUS **COMPLETED/ELAPSED**, `active_elapsed_ms=300000`; `endedAt=2026-10-12T01:48:01.440+05:00` точно равен сохранённому deadline, а не моменту позднего запуска Java. `startedAt` не изменился; одна запись истории по этому `roundId` и одна во всём новом изолированном кластере, room revision выросла ровно **10 → 11**. В снимке истории сохранены исходные цели и задачи, владелец **1/1**, участник **0/1**; живые задачи/цели/сессии неизменны. Число idempotency results не выросло — повтор изменяющей команды для завершения не использовался.

**Оба GUI автоматически перешли CONNECTED → новый FOCUS-итог: PASS.** [Участник](screenshots/focus-expiry-result-peer.jpg), capture **01:49:22.246–01:49:22.365+05**; [владелец](screenshots/focus-expiry-result-owner.jpg), capture **01:49:38.486–01:49:38.600+05**. Оба показывают **5 минут фокуса**, сохранённые цели/задачи, владелец 1/1 и участник 0/1. После Resume выполнялись только осмотр, активация окон и прокрутка видимой области: ручные refresh/reconnect/relogin, переходы по навигации и изменяющие команды для получения результата не нужны.

При осмотре окна активировали; этот прогон не выделяет фоновый heartbeat-only/WS-only путь и не измеряет точную задержку восстановления. Границы capture подтверждают наблюдение результата, а не момент его первого появления.

## Стабильность, выход и cleanup

[Повторный read-only SQL](evidence/focus-expiry-stability.json): **8 доменных инвариантов PASS**. По-прежнему один раунд и одна неизменённая история, та же revision **11**, цели/задачи и idempotency results. Во время этого checkpoint владелец уже нажал logout: неотозванные сессии изменились **2 → 1**, это ожидаемый выход, а не изменение раунда. Задачи после итога в этом заходе не редактировались; этот checkpoint проверяет стабильность, а не повтор прежнего теста неизменяемости при редактировании.

Оба пользователя через UI нажали «Выйти», увидели «Ты вышел из аккаунта» и пустой пароль: [владелец](screenshots/focus-expiry-logout-owner.jpg), [участник](screenshots/focus-expiry-logout-peer.jpg). [Финальный SQL](evidence/focus-expiry-final-db.json): **10 инвариантов PASS**, все **4** сессии (2 seed + 2 GUI) отозваны, неотозванных **0**, один раунд/одна история сохранены. Комната перед teardown осталась **OPEN**, активных членств **2**: logout не закрывает комнату. CLOSED и ноль членств здесь не заявляются.

Оба собственных окна закрыты штатным Alt+F4, launcher-процессы завершились **exit 0**, нативный список больше не содержит этих Compose-окон. [Cleanup собственного runtime](evidence/focus-expiry-cleanup.json) **PASS**: controller exit **0**, Java PID 10952 / PostgreSQL PID 484 / controller PID 39024 завершены, порты **8090/55438** освобождены, только собственный `desktop/build/focus-expiry-20261012/postgres-data` удалён. Логи сохранены. Пользовательская PostgreSQL **5432 PID 10172** продолжает работать; `.env` и пользовательская БД не использовались и не менялись. **Стенд и окна этого прогона сейчас не работают.**

## Физический сон уже проверен отдельно

[Backend-отчёт двух Compose-клиентов](../../docs/backend/COMPOSE_ACCEPTANCE.md) подтверждает Modern Standby на desktop **`8fdb392`**: PAUSED **73.5048001 s**, оба остатка до/после **24:45**; RUNNING **117.0503097 s**, отсчёт после пробуждения учёл прошедшее календарное время. Ручные refresh/relogin/restart не использовались. Kernel-Power 506/507 подтверждают физические переходы.

Это **PASS на `8fdb392`**, не повтор сна на `1a6fa94`. При осмотре активировали окна; задержка от пробуждения до готовности не измерена, независимый heartbeat-only/WS-only путь не выделен. Классический S3, остановка `nanoTime`, сон внутри двухсекундного барьера и остальные режимы питания не проверены. Код восстановления сна после `8fdb392` не менялся, но это не заменяет наблюдение на последней сборке.

## Воспроизведение

Для повторения использовать **новый** run directory и свободные порты. Из корня репозитория, в первой оболочке:

```powershell
py -3 .\desktop\scripts\stage8_runtime.py --java-home C:\Users\user\.jdks\corretto-21.0.6 --server-port 8090 --db-port 55438 --run-dir desktop/build/focus-expiry-local
```

Дождаться READY. Задать временный пароль только в переменной процесса `FOCUSDUO_QA_PASSWORD` (10–128 символов), затем во второй оболочке:

```powershell
py -3 .\desktop\scripts\stage8-2-seed.py --base-url http://127.0.0.1:8090 --focus-seconds 300 --output desktop/build/focus-expiry-local/ui-seed.json
```

Открыть по одному клиенту в двух отдельных оболочках:

```powershell
.\desktop\scripts\run-integration-client.ps1 -Profile focus-expiry-a -BaseUrl http://127.0.0.1:8090
.\desktop\scripts\run-integration-client.ps1 -Profile focus-expiry-b -BaseUrl http://127.0.0.1:8090
```

Войти подготовленными аккаунтами, открыть комнату и продолжить FOCUS владельцем. Сохранить read-only `inspect` до stop, остановить Java до deadline, дождаться обоих UI 00:00/ожидания и SQL RUNNING без истории, затем start с той же PostgreSQL. Не нажимать refresh/reconnect/relogin и не создавать новое действие во время этого ожидания.

Контроллеру отправлять UUID-команды последовательно из одной оболочки; в PowerShell 7 атомарная замена существующего `commands.json` выполняется `[System.IO.File]::Move($temp, $path, $true)`. Дождаться результата с тем же UUID перед следующей командой. `File.Replace(..., $null)` здесь не используется: при передаче через PowerShell пустой backup path оказался несовместимым.

```powershell
$focusExpiryAction='inspect' # Затем stop до deadline; inspect после него; start после обоих UI 00:00.
$focusExpiryRun=(Resolve-Path desktop/build/focus-expiry-local).Path
$focusExpiryId=[guid]::NewGuid().ToString()
$focusExpiryTemp=Join-Path $focusExpiryRun 'commands.tmp'
$focusExpiryCommand=Join-Path $focusExpiryRun 'commands.json'
@{id=$focusExpiryId; action=$focusExpiryAction} | ConvertTo-Json | Set-Content -LiteralPath $focusExpiryTemp -Encoding UTF8
[System.IO.File]::Move($focusExpiryTemp, $focusExpiryCommand, $true)
```

Дождаться `result.json` с тем же UUID и `status: "ok"`; `inspect` создаёт безопасный `sql-inspection.json`. После итогов повторить inspect, выйти из обоих клиентов и закрыть окна; затем завершить свой runtime:

```powershell
New-Item -ItemType File -Path .\desktop\build\focus-expiry-local\shutdown
```

Дождаться STOPPED/exit 0. Контроллер удаляет только свой одноразовый кластер и сохраняет логи. В приведённых командах используется новый примерный `focus-expiry-local`; фактический run directory этого прогона — `focus-expiry-20261012`, уже очищенный.

## Сборка, проверки инструмента и снимки

Оба свежих запуска `run-integration-client.ps1` успешно собрали и открыли актуальный immutable application JAR от `1a6fa94`: [доказательство сборки и выхода](evidence/focus-expiry-build.json), одинаковый SHA-256 **`3C1D3AE6F53649BF72D3777E094785747A1A83A5F6A7E4F2EFADDA70C32982FA`**. Production-код desktop/backend в этом заходе не изменяется. `git diff --name-only 774378c..HEAD -- desktop/src backend/src` пустой. Полный JUnit/backend smoke повторно не запускался: **199 обычных + 2 живых** относятся к предыдущему прогону desktop `774378c`, вошедшему в текущий `main` через PR #18.

Для нового CLI-параметра seed проверены **8 сценариев разбора**, `--help`, синтаксис Python и `git diff --check`: PASS; [CLI-доказательство](evidence/focus-expiry-seed-cli.json). Эти проверки не выполняли сеть и не использовали пароли, не являются JUnit-тестами приложения. Фактическая REST-подготовка 300 s отдельно подтверждена живым SQL этого прогона.

[Реестр 13 новых снимков](evidence/focus-expiry-screenshots.json) содержит actual MIME **image/jpeg**, размеры **1267×794**, bytes и SHA-256. Сохранены исходные native sky JPEG с расширением `.jpg`; пиксели не редактировались.

## Границы

- LAN и обрыв реального сетевого пути/адаптера остаются NOT_RUN. Остановка Java на loopback проверяет недоступность сервера, а не отключение сети между двумя ПК.
- Физический сон в этом заходе не повторяется; принимается отдельное версионное доказательство выше.
- Полная визуальная регрессия относится к этапу 9, Windows-упаковка/установка/подпись — к этапу 10.
- Счётчики прежних тестов 199 + 2 и opt-in harness относятся к [этапу 8.2](STAGE8_2_RECOVERY.md). Новый прогон полного JUnit/backend-smoke/opt-in harness в этом шаге не выполнялся; новый GUI/SQL-прогон описан выше.
- Изменения только в `desktop/**`. Пароли, bearer-токены и секретные поля БД в отчёт и доказательства не включаются.

[Заголовок и описание PR](PR_FOCUS_EXPIRY.md).
