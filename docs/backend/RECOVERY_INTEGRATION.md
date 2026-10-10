# Kotlin recovery/retry ↔ Java: desktop-этап 7

База проверки — `main` `67b66c5`: desktop PR #12 (`0fb2b56`) и backend integration PR #11 (`516020e`). Рабочая ветка — `feat/backend-recovery-integration`. Дата: 11 октября 2026, Asia/Qyzylorda. Production-код Java, `desktop/**`, общий контракт и fixtures не изменены.

## Среда и воспроизведение

Native Windows / Temurin JDK 21.0.12.101 / Gradle Wrapper 8.13 → Java JAR в WSL Ubuntu на `127.0.0.1:8090` → отдельная PostgreSQL 17.11 с tmpfs на 55437. JAR использует `-Djava.net.preferIPv4Stack=true`; исходники backend и `pom.xml` не менялись относительно предыдущего проверенного JAR. Пользовательская PostgreSQL на 5434 и `.env` не используются тестовым стендом.

Новый [контроллер стенда](../../backend/scripts/kotlin-restart-smoke/serve.py) создаёт собственный контейнер и Java-процесс, затем управляет только этим процессом через локальные файлы. Пароль генерируется в памяти, Spring/JVM overrides родительского окружения не передаются Java. При остановке удаляется только контейнер с точным созданным именем и ожидаемой меткой. Полная инструкция — [restart README](../../backend/scripts/kotlin-restart-smoke/README.md).

Сначала соберите backend JAR и подготовьте WSL/Docker по [backend README](../../backend/README.md). Для отдельного стенда из корня репозитория в PowerShell запустите и оставьте открытой команду:

```powershell
wsl -d Ubuntu -u root -- python3 /mnt/c/Daniyar/MyProjects/FocusDuo/backend/scripts/kotlin-restart-smoke/serve.py --control-dir .local/recovery-check-1
```

Дождитесь `READY`. Путь рабочей копии замените своим; control-dir должен быть новым при каждом запуске. Не запускайте одновременно обычный backend на 8090. Контроллер сохраняет PostgreSQL при stop/start Java; файл `shutdown` завершает весь тестовый стенд и удаляет его временную БД.

Во второй PowerShell-оболочке из корня:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:PATH=(Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:/Daniyar/MyProjects/FocusDuo/backend/target'
.\desktop\gradlew.bat -p desktop test check backendAuthSmoke backendRoomSmoke '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' --console=plain --no-daemon
.\desktop\gradlew.bat -p desktop -I (Resolve-Path backend/scripts/kotlin-recovery-smoke/init.gradle).Path realRecoverySmoke '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' --console=plain
.\desktop\gradlew.bat -p desktop -I (Resolve-Path backend/scripts/kotlin-restart-smoke/init.gradle).Path realRestartSmoke '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' "-Dfocusduo.smoke.controlDir=$((Resolve-Path .local/recovery-check-1).Path)" --console=plain
```

Выполняйте последовательно и проверяйте exit code каждой команды. Замените пути JDK и `backend/target` для своей машины. После всех сценариев и анализа результата:

```powershell
New-Item -ItemType File -Path .local/recovery-check-1/shutdown | Out-Null
```

Дождитесь `STOPPED` в первой оболочке. Данные именно этой временной БД удаляются. Не используйте контроллер или smoke для рабочей базы. Harness создают собственные аккаунты, комнаты и историю; V1 не содержит удаления аккаунтов. Серверная очистка при недоступности сервера выполняется по возможности, поэтому одноразовая БД обязательна.

## R4: обычные и живые базовые тесты

`test check backendAuthSmoke backendRoomSmoke`: **BUILD SUCCESSFUL, 39 секунд, exit 0**. XML подтвердили **171 обычный тест + 1 auth smoke + 1 room smoke**, 0 failures/errors/skips. Все 10 копий fixtures совпали с общими по SHA-256. Отчёты: `desktop/build/test-results/{test,backendAuthSmoke,backendRoomSmoke}/`.

Дополнительно в одном запуске прошли прежние `realRepositorySmoke realRoomLifecycleSmoke`: **BUILD SUCCESSFUL, 11 секунд, exit 0**. Старый lifecycle harness адаптирован: после reconnect известной CLOSED-комнаты desktop 7 сначала асинхронно перечитывает membership, поэтому тест ждёт увеличения `recoveryVersion` и возврата в NONE, а не проверяет NONE мгновенно. Это изменение ожидания теста, не исправление клиента.

Этот начальный прогон использовал первый отдельный контейнер. До его удаления SQL подтвердил 0 OPEN rooms, 0 active memberships, 0 unrevoked sessions, 2 раунда, 2 истории и 2 миграции. Затем контроллер усилен изоляцией окружения и повторно запущен с новой БД для R5/R6. В окружении первого Java-процесса отдельно подтверждено отсутствие Spring/JVM overrides; результаты R4 сохраняют силу.

## R5: потерянный ответ, безопасный replay и настоящий 409

[Новый recovery harness](../../backend/scripts/kotlin-recovery-smoke/README.md) прошёл: **BUILD SUCCESSFUL, 7 секунд, exit 0**, 4 checkpoints и итог PASS. Он использует настоящий CIO и Java; ответы/DTO не подменяются. Делегат транспорта дочитывает полный настоящий ответ 200 и только затем теряет его для repository.

- Потерян ответ create после commit. Автоматическое чтение восстановило участие; pending запретил второе создание. Ручной повтор отправил тот же method/path/body/key без revision и вернул ту же комнату с revision 1.
- Потерян ответ создания задачи. Пока результат неизвестен, другая команда блокируется без отправки. После изменения партнёром ручной повтор отправил исходные method/path/body/key/revision. Независимый GET подтвердил одну задачу, неизменённую повтором revision и сохранённую новую правку партнёра.
- Подготовленная команда изменения цели задержана до изменения партнёра. Java вернул настоящий `409 REVISION_CONFLICT` и актуальный snapshot. Pending очищен; следующее явное действие использовало новый ключ и текущую revision.
- Собственная комната закрыта, действующие сессии отозваны.

Сценарий 409 относится к задержанной новой команде. Конфликт при replay команды, которая вовсе не была сохранена сервером, отдельно не проверялся. Метаданные запросов и секреты остаются только в RAM.

## R6: автоматическое восстановление при рестарте Java

[Restart harness](../../backend/scripts/kotlin-restart-smoke/README.md): **BUILD SUCCESSFUL, 43 секунды, exit 0**, все 5 checkpoints и итог PASS. Контроллер дважды принудительно завершил только свой Java-процесс; PostgreSQL и оба Kotlin repository продолжали работать.

1. Созданы два пользователя, комната, выполненная задача и FOCUS длительностью 900 секунд. Раунд сразу поставлен на паузу; реального ожидания 15 минут нет.
2. После первого SIGKILL оба клиента потеряли CONNECTED, изменения заблокировались. После старта оба автоматически увеличили `recoveryVersion` и получили свежие WS-снимки без вызова reconnect/bootstrap. PAUSED, roundId, revision, состав, задача, startedAt и замороженные remaining/active сохранились точно.
3. После resume и второго SIGKILL/start оба автоматически восстановили тот же RUNNING с исходными startedAt/endsAt. Временные поля продолжили отсчёт, остальные данные и revision сохранились. Production backoff/jitter не заменялись ускоренной функцией.
4. Явный `recoverAfterResume()` запустил новую REST/WS-синхронизацию обоих клиентов. Счётчики настоящих HTTP-запросов подтвердили отсутствие изменяющих команд при обоих автоматических восстановлениях и этом сигнале.
5. Ручной finish создал одну одинаковую запись истории обоим участникам, с выполненной задачей. Комната закрыта, обе сессии отозваны.

SQL после R5/R6: **0 OPEN rooms, 0 active memberships, 0 unrevoked sessions, 1 раунд, 1 история, 2 миграции**. Контроллер зафиксировал поколения Java 1 → 2 → 3. После проверки временный Java-процесс остановлен, собственный PostgreSQL-контейнер удалён, 8090/55437 проверены свободными; пользовательский `focusduo-db-1` на 5434 остался healthy. `.env` сохранён и исключён из Git.

Это проверка repository и реального перезапуска сервера. Она не запускает Compose-окна, не проходит через UI-защиту от частых wake-сигналов и не подтверждает физический сон Windows.

Отдельный короткий прогон контроллера подтвердил изоляцию от родительских `SPRING_DATASOURCE_URL`, `SPRING_CONFIG_LOCATION` и `JAVA_TOOL_OPTIONS`, намеренно указывавших на недоступную тестовую конфигурацию. Java всё равно запустилась только со своим JDBC на 55437 и classpath-конфигурацией; окружение child проверено без вывода секретов, в собственной БД применены 2 миграции. Повтор `start` подтвердил readiness с тем же поколением 1. Этот дополнительный стенд также удалён; финально 8090/55437 свободны, пользовательская БД healthy. Ошибка запуска Docker после создания контейнера обработана проверкой точного имени/метки, но её отдельная fault-инъекция не выполнялась.

## Замечание Kotlin-разработчику: обработка сигнала пробуждения

**Статус обновлён после этого прогона:** замечание исправлено в desktop PR #14 (`8fdb392`, merge `a1e557f`). Прежний дефект и его вывод ниже сохраняются как история; текущие результаты координатора и повтор Java-интеграции — в [WAKE_ACCEPTANCE.md](WAKE_ACCEPTANCE.md). `STAGE7_*` теперь заполнены, промежуточные GUI-результаты включены в desktop-документацию. Физический сон и два Compose-окна с Java по-прежнему требуют отдельной ручной проверки.

При read-only review найден условный дефект в `desktop/src/main/kotlin/dev/focusduo/presentation/RealWorkspace.kt:58–64`: двухсекундная защита от частых вызовов считает только `System.nanoTime()`. `ResumeDetector` специально умеет обнаруживать сон по календарным часам, если монотонные часы во сне не продвигаются; его baseline обновляется до вызова восстановления.

Точное условие: последнее восстановление и heartbeat при wall=1 000 000 ms / nano=100 000 000 000 ns; затем 60 секунд сна с остановленным nano и 1 секунда активности. На wake wall=1 061 000 ms / nano=101 000 000 000 ns. Detector вернёт true по календарному разрыву 61 секунда, но защита отбросит событие: по nano прошла 1 секунда < 2. Следующий sample уже видит разрыв 1 секунда и не повторит сигнал. До другого триггера/таймаута WS может остаться прежняя привязка таймера.

Рекомендация владельцу `desktop/`: учитывать календарный разрыв и в защите от частых вызовов либо не подавлять обнаруженный heartbeat-gap; добавить проверку объединённого пути с управляемыми часами. Это вывод из кода, **не воспроизведённый физический сон Windows**. Исходники клиента здесь не изменены. Снимки и незаполненные `STAGE7_*` в desktop-документации не подтверждают завершённую UI-приёмку.

## Непроверенные части

Два Compose-окна, полный UI-аудит, физический сон Windows, отключение сетевого адаптера, LAN, истечение TTL и автоматическое ELAPSED после длительного выключения Java в этом заходе не проверяются. Физическое ожидание 15 минут не требуется: live-сценарий завершает раунд вручную, а серверные временные границы ранее проверялись управляемыми часами.

Полный Maven `verify` локально не повторялся: production-сервер не менялся. [Backend CI после PR #11](https://github.com/KDvibers/FocusDuo/actions/runs/38079888693) успешен на `516020e`; это отдельный предыдущий CI. Новые opt-in Kotlin harness в CI не включены. Удалённый CI этой ветки ожидает push владельца.
