# Два Compose-окна, Java и физический сон Windows

Короткая ручная приёмка после desktop wakefix `8fdb392` (PR #14). **11 октября 2026 M1–M4 пройдены с двумя настоящими Compose-окнами, Java и постоянной PostgreSQL.** Запуск выполнен на `30c8235`; отчёт подготовлен в `feat/backend-compose-acceptance` от `main` `9287c91` после merge PR #15, без изменений исполняемого кода. [Результаты и 29 снимков](COMPOSE_ACCEPTANCE.md) отдельно фиксируют Modern Standby, последовательное наблюдение окон и неизмеренное время восстановления. Полный набор проверок, включая retry, TTL и LAN, — в [INTEGRATION.md](INTEGRATION.md).

27 снимков из [desktop HANDOFF](../../desktop/docs/HANDOFF.md#проверки-этапа-7) относятся к предыдущему GUI-сеансу: 13 — `FakeRepository`/demo, 14 — обычный Compose/`RealRepository` с тестовым HTTP/WS-сервером на `18090`. Java и PostgreSQL в том GUI-сеансе не участвовали. Новые снимки реальной Java-приёмки находятся отдельно в [backend/screenshots/manual-2026-10-11](screenshots/manual-2026-10-11/). Разбор исправления и границ прежних тестов — в [WAKE_RECOVERY_REVIEW.md](../../desktop/docs/WAKE_RECOVERY_REVIEW.md).

## 1. Обычный сервер с сохранением БД

Нужны три открытых терминала: WSL с Java и два PowerShell для клиентов. Используем существующие корневой `.env` и Compose-БД на `5434` с постоянным volume `focusduo_focusduo_data`. Не заменяйте `.env` и не запускайте для сна disposable/tmpfs-стенд `serve.py`: при остановке WSL его БД может исчезнуть. Тестовые аккаунты и история останутся в обычной БД; удаления аккаунтов в V1 нет. Работайте только с новой комнатой этих двух аккаунтов.

Если на `8090` уже работает нужный обычный сервер, используйте его после проверки версии и БД. Если порт занят неизвестным процессом, сначала выясните его назначение; второй сервер не запускайте. Подробности окружения — в [backend README](../../backend/README.md).

В PowerShell из корня репозитория:

```powershell
Set-Location C:\Daniyar\MyProjects\FocusDuo
wsl -d Ubuntu -u root
```

Далее в открывшемся Bash. Это команды для уже установленной WSL Ubuntu с Java 21, Docker/Compose, `curl` или `wget` и `unzip`:

```bash
cd /mnt/c/Daniyar/MyProjects/FocusDuo || exit 1
test -f .env || { echo 'Нужен существующий .env'; exit 1; }
set -a
. <(sed 's/\r$//' .env) || exit 1
set +a
docker info || exit 1
docker compose up -d --wait db || exit 1
unset FOCUSDUO_TEST_DB_URL FOCUSDUO_TEST_DB_USERNAME FOCUSDUO_TEST_DB_PASSWORD
cd backend || exit 1
bash ./mvnw --batch-mode --no-transfer-progress package || exit 1
java -Djava.net.preferIPv4Stack=true -jar target/focusduo-backend-0.1.0.jar
```

Команды завершают Bash при ошибке; JAR запускается только после успешной сборки. `package` выполняет unit-тесты, но не PostgreSQL integration tests из `verify`. Если Docker не запущен, снова откройте `wsl -d Ubuntu -u root`, выполните `service docker start`, затем повторите блок выше. Java использует экспортированный `.env`; IPv4-параметр нужен для проверенного маршрута Windows → WSL через localhost. Держите этот терминал открытым. Для данной проверки не нужны изменения firewall, portproxy или публикация БД в LAN.

Во втором PowerShell проверьте доступность API:

```powershell
curl.exe -sS -i http://127.0.0.1:8090/api/v1/me
```

Ожидается `401` и JSON `code=UNAUTHORIZED`: API доступен, запрос намеренно без сессии. Timeout/connection refused сначала разберите по серверному терминалу; к оценке UI ещё не переходите.

## 2. Два независимых клиента

Сначала **один раз** выполните короткую сборку и экспорт runtime classpath. Два долгоживущих `gradlew run` в одной копии могут конкурировать за project cache lock, поэтому окна ниже запускаются напрямую через Java. Временный init-скрипт и classpath лежат только в игнорируемой `.local/`; файлы `desktop/**` менять не требуется.

```powershell
$ErrorActionPreference='Stop'
Set-Location C:\Daniyar\MyProjects\FocusDuo
$env:FOCUSDUO_JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:JAVA_HOME=$env:FOCUSDUO_JAVA_HOME
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:/Daniyar/MyProjects/FocusDuo/backend/target'
New-Item -ItemType Directory -Force .local/manual-desktop | Out-Null
@'
gradle.projectsEvaluated { evaluated ->
    def desktopProject = evaluated.rootProject
    desktopProject.tasks.register('exportManualRuntimeClasspath') {
        dependsOn desktopProject.tasks.named('classes')
        doLast {
            def output = new File(desktopProject.projectDir, '../.local/manual-desktop/runtime-classpath.txt')
            output.parentFile.mkdirs()
            output.setText(desktopProject.extensions.getByName('sourceSets').getByName('main').runtimeClasspath.asPath, 'UTF-8')
        }
    }
}
'@ | Set-Content -Encoding Ascii .local/manual-desktop/init.gradle
$focusInitPath=(Resolve-Path -LiteralPath '.local/manual-desktop/init.gradle').Path
& .\desktop\gradlew.bat -p .\desktop -I $focusInitPath exportManualRuntimeClasspath --console=plain --no-daemon
if ($LASTEXITCODE -ne 0) { throw 'Runtime classpath export failed' }
$focusClassPath=(Get-Content -Raw .local/manual-desktop/runtime-classpath.txt).Trim()
if ([string]::IsNullOrWhiteSpace($focusClassPath)) { throw 'Runtime classpath is empty' }
& "$env:FOCUSDUO_JAVA_HOME\bin\java.exe" -cp $focusClassPath dev.focusduo.MainKt --help
if ($LASTEXITCODE -ne 0) { throw 'MainKt --help failed' }
```

Эта подготовка проверена 11 октября 2026 на Windows / Temurin 21.0.12.101: Gradle `BUILD SUCCESSFUL` за 6 секунд, затем `MainKt --help` с exit 0. Окно не открывалось, Java backend для `--help` не нужен. После смены коммита или `clean` повторите экспорт; не пересобирайте клиент, пока оба окна этого прогона открыты.

Теперь в **каждом из двух отдельных PowerShell-терминалов** выполните одинаковый блок. Последняя команда остаётся работать на переднем плане до закрытия соответствующего окна:

```powershell
$ErrorActionPreference='Stop'
Set-Location C:\Daniyar\MyProjects\FocusDuo
$env:FOCUSDUO_JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:/Daniyar/MyProjects/FocusDuo/backend/target'
$env:FOCUSDUO_BASE_URL='http://127.0.0.1:8090'
$focusClassPath=(Get-Content -Raw .local/manual-desktop/runtime-classpath.txt).Trim()
if ([string]::IsNullOrWhiteSpace($focusClassPath)) { throw 'First export the runtime classpath' }
& "$env:FOCUSDUO_JAVA_HOME\bin\java.exe" -cp $focusClassPath dev.focusduo.MainKt --width=1280 --height=800
if ($LASTEXITCODE -ne 0) { throw 'FocusDuo exited with an error' }
```

Указаны установленные на этой машине JDK и рабочая копия; на другом ПК замените пути. Каталог `backend/target` создаётся серверной сборкой; полный путь устраняет обнаруженную проблему Windows JDK с коротким TEMP-путём. Для более компактного второго окна замените последние аргументы на `--width=1000 --height=700`.

Это тот же `MainKt`, который запускает [run.ps1](../../desktop/run.ps1); аргументы сверены с [LaunchOptions](../../desktop/src/main/kotlin/dev/focusduo/LaunchOptions.kt): **без `--demo`**, URL задаётся переменной окружения, без `/api/v1`. В обоих окнах проверьте адрес `http://127.0.0.1:8090` и отсутствие пометки демо. Каждое окно хранит свою сессию в памяти; сохранённые адрес и последний username могут быть общими. Явно зарегистрируйте разных пользователей A и B, например `manual_a_1011_01` и `manual_b_1011_01` с уникальным суффиксом. Используйте собственные тестовые пароли длиной 10–128 символов; не включайте их в отчёт.

## 3. Ручной прогон

| Шаг | Действия и ожидаемое наблюдение | Статус этой версии |
| --- | --- | --- |
| M1 — быстрый K1 | A создаёт комнату с FOCUS 25 минут и BREAK 5 минут; B входит по её коду. Оба видят двух участников и готовое соединение. A и B задают свои цели и добавляют по задаче; B меняет свою задачу. Изменения появляются в другом окне без ручного обновления; чужие задачи не доступны для редактирования. A запускает фокус — оба видят один работающий раунд. | `PASS` — реальный режим, FD-U663EE, два пользователя, цели/задачи и ограничения UI подтверждены. |
| M2 — сон в PAUSED | A нажимает «Пауза». Зафиксируйте оставшееся время, обе задачи и время Windows; сделайте снимки обоих окон. Через меню Windows «Пуск → Питание → Сон» переведите **сам компьютер** в сон на 60–90 секунд. После пробуждения сначала ничего не обновляйте вручную. Оба клиента должны автоматически синхронизироваться, сохранить комнату, задачи и PAUSED с тем же остатком; отсчёт не идёт. | `PASS` — Modern Standby 73,505 с; до/после у обоих 24:45. Следующая правка A видна у B, revision 9 → 10. |
| M3 — сон в RUNNING | После готовности обоих окон A нажимает «Продолжить». Убедитесь, что до конца больше 5 минут; запишите остаток и время Windows. Повторите физический сон на 60–90 секунд. После автоматической синхронизации таймер должен учесть прошедшее календарное время, а не продолжиться с прежнего остатка; оба окна показывают тот же раунд и согласованный отсчёт. Задачи и членство сохраняются, новая история ещё не появляется. | `PASS` — фактический сон 117,050 с. A: 24:40 → 21:12 за 207,603 с между наблюдениями; B: 24:33 → 21:06 за 207,235 с. Разница включает время вне сна. |
| M4 — итог, история и CLOSED | После M3 A нажимает «Завершить». В обоих клиентах проверьте один одинаковый итог FOCUS с причиной MANUAL и фактическим активным временем без паузы. Измените задачу после завершения и повторно откройте итог: сохранённый текст/статус задачи не изменился. A запускает BREAK; B закрывает комнату. Оба видят CLOSED, команды комнаты отключены, восстановление не крутится бесконечно. История содержит прежний FOCUS и один BREAK со статусом CANCELLED / причиной ROOM_CLOSED. Выйдите из обеих сессий. | `PASS` — FOCUS 4 мин 31 с без паузы; история не изменилась после правки B. BREAK 37 с прерван закрытием B; оба видят 2 результата, команды отключены, logout обоих успешен. |

`PASS` здесь относится к перечисленному наблюдаемому поведению. После сна окна последовательно активировались для съёмки: foreground мог запустить восстановление. Точное время от пробуждения до готовности не измерено; отдельный heartbeat-only путь не доказан. Ручные refresh, повторный вход и перезапуск Java не потребовались. Полные K1–K7 этим коротким прогоном не закрыты.

Для M2/M3 запишите время перед сном и после пробуждения, остатки в обоих окнах и длительность до готового соединения. Сверните/верните оба окна, если этого требует доступ к ним, но отметьте действия: возврат фокуса тоже является триггером восстановления. Не подменяйте сон блокировкой экрана, сменой часов или вызовом `recoverAfterResume()`. Обратный отсчёт округляется для отображения; запишите фактическую разницу, а не только субъективное «синхронно».

Сначала дайте автоматическому восстановлению до 60 секунд; это окно наблюдения, не обещание срока при любой сети. Если оно не завершилось, зафиксируйте состояние до ручного обновления. Проверьте, остался ли Java-процесс доступен и отвечает ли `/me` кодом 401. Если WSL/Java остановились, отдельно запишите инфраструктурную причину и последующий ручной запуск; он не превращает исходное автоматическое восстановление в `PASS`. Кнопкой обновления можно пользоваться после фиксации сбоя.

Полезный отдельный повтор M2 или M3 — сон вскоре после завершённого восстановления. Запишите фактический интервал между готовностью и сном: именно соседство сигналов связано с исправленным барьером. Физический прогон не гарантирует остановку `nanoTime`; этот вариант часов проверяется детерминированными тестами, а здесь наблюдается реальное поведение Windows.

Если раунд всё же успел истечь во время сна, отметьте это отдельно: такой прогон проверяет ELAPSED и не является чистым M3 с сохранённым RUNNING. TTL, LAN, отключение сетевого адаптера, точный retry/409 и упаковка остаются за пределами короткой приёмки; продолжение — по [INTEGRATION.md](INTEGRATION.md).

## 4. Запись результата и остановка

Для следующего прогона используйте шаблон ниже; результат 11 октября уже сохранён в [COMPOSE_ACCEPTANCE.md](COMPOSE_ACCEPTANCE.md). Успешный запуск приложения или старые снимки не заменяют новые наблюдения:

```text
Дата/часовой пояс, исполнитель:
Backend commit / desktop commit / git status (без содержимого .env):
Windows / WSL / JDK, адрес API:
БД: PostgreSQL 17.11, постоянный Compose-volume, порт 5434:
Два отдельных Compose-процесса, пользователи A/B (без паролей):
M1: PASS | FAIL | NOT_RUN — наблюдения:
M2: PASS | FAIL | NOT_RUN — до/после сна, остатки A/B, время восстановления:
M3: PASS | FAIL | NOT_RUN — до/после сна, остатки A/B, время восстановления:
M4: PASS | FAIL | NOT_RUN — число итогов, неизменность задачи, CLOSED:
Ручные refresh/relogin/restart, если потребовались:
Пути новых снимков, безопасный текст ошибки / requestId при наличии:
Оставшиеся ограничения:
```

Не прикладывайте `.env`, пароли, bearer-токены, заголовок Authorization или дампы сессий. При ошибке достаточно времени, действия, безопасного кода/сообщения и `requestId`, если клиент его показывает; запросите подробности отдельно без секретов.

В этом прогоне оба logout выполнены; окна оставлены на экране входа, Java и постоянная БД продолжают работать. Тестовая комната закрыта, аккаунты и два результата сохранены. Когда сервер больше не нужен, закройте окна, остановите Java через Ctrl+C, затем при необходимости в WSL из корня репозитория выполните `docker compose stop db`. Это сохраняет данные; `down -v`, удаление volume и пересоздание `.env` для завершения проверки не нужны.
