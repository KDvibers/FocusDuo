# FocusDuo Java backend

Java 21, Spring Boot 3.5.16, PostgreSQL 17, Flyway и Maven 3.9.11. Maven Wrapper 3.3.4 находится в этой папке; отдельная установка Maven не требуется. Первый запуск wrapper и сборка требуют доступа к Maven Central. SHA-256 дистрибутива Maven закреплён в `.mvn/wrapper/maven-wrapper.properties`.

**Состояние после PR #21:** основной совместный сценарий двух компьютеров через Tailscale прошёл [ручную приёмку](../docs/backend/REMOTE_ACCEPTANCE_2026-10-12.md). Следующий этап — [Windows-упаковка клиента](../docs/backend/WINDOWS_PACKAGING_HANDOFF.md); сервер и БД продолжают работать отдельно. [Инструкция общего подключения](../docs/backend/SERVER_CONNECTION.md) описывает HTTPS/WSS, адрес в интерфейсе и диагностику. Числа тестов, версии и ограничения в исторических отчётах ниже относятся к своим прогонам, а не к новому выполнению на текущем коммите.

Для Linux/WSL дополнительно нужны Bash, `curl` или `wget` и **`unzip`**. Wrapper проверяет закреплённый ZIP-дистрибутив; отсутствие `unzip` нужно исправить установкой утилиты, сохранив checksum. Для Docker-проверок нужны работающий Docker Engine и Compose v2. HTTP smoke использует Python 3.9+ без сторонних пакетов.

## Локальный .env

На Windows из корня репозитория выполните один раз:

```powershell
.\backend\scripts\init-local.ps1
```

Команда создаст `.env` по `.env.example` со случайным паролем PostgreSQL. Пароль не выводится; существующий `.env` сохраняется без изменений. Отдельная установка PowerShell 7 не требуется. Если запуск скриптов запрещён политикой вашего компьютера, при отсутствующем `.env` выполните `Copy-Item .env.example .env`, затем `notepad .env` и замените `DB_PASSWORD` своим локальным паролем. При уже существующем `.env` не копируйте пример поверх него.

Для двух клиентов на одном ПК остальные значения примера подходят без изменений: БД на `127.0.0.1:5434`, backend на `127.0.0.1:8090`, сессия 24 часа. `.env` нужен только серверу и исключён из Git. Другу для клиента передаются адрес сервера и контракт; пароль БД и сам `.env` ему не нужны. Учётные записи FocusDuo создаются через регистрацию и имеют собственные пароли.

Если оба разработчика запускают **свои отдельные серверы**, каждый создаёт собственный `.env` и БД. Для совместной комнаты оба клиента должны обращаться к одному серверу. Настройка двух ПК и последовательность приёмки — в [INTEGRATION.md](../docs/backend/INTEGRATION.md).

## Запуск в PowerShell

Из корня репозитория, с установленными Java 21 и Docker с Linux containers:

```powershell
if (-not (Test-Path -LiteralPath .env -PathType Leaf)) {
    throw 'Сначала создайте .env по разделу «Локальный .env».'
}
Get-Content .env | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
docker compose up -d --wait db
Set-Location backend
.\mvnw.cmd spring-boot:run
```

Повторный запуск: загрузите переменные из уже существующего `.env`, запустите БД, затем `spring-boot:run`. Не копируйте пример поверх изменённого `.env` повторно.

## Запуск в bash

```bash
if [ ! -e .env ]; then cp .env.example .env; fi
# Если файл только что создан из примера, отредактируйте его и замените DB_PASSWORD.
set -a
. <(sed 's/\r$//' .env)
set +a
docker compose up -d --wait db
cd backend
bash ./mvnw spring-boot:run
```

`.env` автоматически читает Docker Compose, но не Java-процесс: поэтому команды выше экспортируют переменные в текущую оболочку. Формат примера использует простые `KEY=value`; если меняете пароль, избегайте синтаксиса оболочки либо задавайте переменные непосредственно в оболочке.

В Bash `sed` убирает окончания CRLF только из читаемого потока, чтобы работал и `.env`, созданный в Windows. Сам файл не изменяется.

Первый запуск применяет Flyway-миграции. Hibernate проверяет схему, а не создаёт её. Compose публикует только `127.0.0.1:5434`, создаёт базу `focusduo` и собственный volume `focusduo_focusduo_data`. Прочие локальные базы не используются. Для остановки: `docker compose stop db` из корня; данные сохраняются. Изменение пароля в `.env` не изменяет пароль в уже инициализированной базе.

## Запуск в WSL при отсутствии Docker в Windows

На текущей машине установлен дистрибутив WSL `Ubuntu` (Ubuntu 26.04), Docker Engine 29.1.3, Compose 2.40.3 и Java 21.0.12.1. Docker доступен под `root`; членство обычного пользователя в группах не менялось. Из PowerShell откройте это окружение:

```powershell
wsl -d Ubuntu -u root
```

Далее в Bash используйте существующий `.env`, подготовленный по примеру выше; не заменяйте его повторным копированием:

```bash
cd /mnt/c/Daniyar/MyProjects/FocusDuo
set -a
. <(sed 's/\r$//' .env)
set +a
docker info
docker compose up -d --wait db
unset FOCUSDUO_TEST_DB_URL FOCUSDUO_TEST_DB_USERNAME FOCUSDUO_TEST_DB_PASSWORD
cd backend
bash ./mvnw --batch-mode --no-transfer-progress verify
java -Djava.net.preferIPv4Stack=true -jar target/focusduo-backend-0.1.0.jar
```

Если Docker-служба после перезапуска WSL остановлена, запустите её командой `service docker start` и повторите `docker info`. Путь `/mnt/c/...` замените на путь своей рабочей копии. Testcontainers в `verify` создаёт отдельную тестовую БД; последующий JAR использует Compose-БД на порту 5434.

Параметр `-Djava.net.preferIPv4Stack=true` нужен в проверенной WSL-конфигурации для доступа из Windows через `http://127.0.0.1:8090`: без него Java открывала IPv6-mapped socket и Windows-клиент получал timeout. HTTP smoke внутри WSL прошёл без этого параметра; из Windows с Python 3.14 — после запуска JAR с ним. Для повторения откройте вторую оболочку и запустите smoke из раздела «Сборка и проверки». Доступ с другого ПК зависит также от сетевого режима WSL и правил Windows; LAN-маршрут пока не подтверждён.

Если JAR или Maven запускаются **нативно в Windows**, а PostgreSQL — в WSL, оставьте WSL-терминал открытым на всё время работы. На этой машине после завершения последнего активного WSL-вызова дистрибутив останавливался вместе с Docker: одной работающей Docker-службы было недостаточно, и Windows получала отказ подключения к 5434. При запуске JAR прямо в WSL активный процесс уже удерживает окружение.

## Переменные окружения

| Переменная | Значение по умолчанию | Назначение |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5434/focusduo` | JDBC PostgreSQL |
| `DB_USERNAME` | `focusduo` | Пользователь БД |
| `DB_PASSWORD` | Обязательна | Пароль БД; не хранить в Git |
| `SERVER_ADDRESS` | `127.0.0.1` | Адрес привязки HTTP/WS |
| `SERVER_PORT` | `8090` | Порт HTTP/WS |
| `FOCUSDUO_SESSION_TTL` | `PT24H` | TTL bearer-сессии, ISO 8601 duration |

Для уже установленного PostgreSQL 17 можно создать **отдельную** базу FocusDuo и роль, а затем задать `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. Docker в таком случае нужен только для стандартного Testcontainers-прогона, не для работы приложения.

## Подключение клиента

- REST: `http://127.0.0.1:8090/api/v1`.
- WebSocket: `ws://127.0.0.1:8090/ws/rooms/{roomId}`.
- Для обоих транспортов: `Authorization: Bearer <accessToken>`.
- Команды комнаты: `Idempotency-Key: UUID`; кроме create/join также `X-Room-Revision: число`.
- Вход: `POST /api/v1/auth/register` или `/auth/login`; после входа `GET /api/v1/rooms/current`.

Тестовые пользователи не создаются автоматически. Для локального сценария зарегистрируйте `alice_demo` и `bob_demo`, например с displayName `Alice Demo` / `Bob Demo` и учебным паролем `focusduo-demo-password` (не используйте его вне локального теста). [requests.http](../docs/contracts/requests.http) содержит полный ручной сценарий. Токен из исходных fixtures искусственный и не даёт доступа к серверу.

Для двух компьютеров запустите сервер с `SERVER_ADDRESS=0.0.0.0` (PowerShell: `$env:SERVER_ADDRESS='0.0.0.0'`, bash: `export SERVER_ADDRESS=0.0.0.0`). Оба клиента используют LAN-адрес компьютера с Java, например `http://192.168.1.20:8090`; разрешите входящий TCP 8090 в локальной сети. БД оставьте на loopback. Для удалённого соединения настройте HTTPS/WSS с reverse proxy, поддерживающим WebSocket Upgrade. Сервер продолжает требовать bearer-авторизацию.

## Сборка и проверки

Из `backend/`:

```powershell
.\mvnw.cmd test             # unit-тесты и контрактные fixtures; Docker не нужен
.\mvnw.cmd verify           # также PostgreSQL Testcontainers integration tests; нужен Docker
.\mvnw.cmd package          # JAR с запуском unit-тестов
java -jar target/focusduo-backend-0.1.0.jar
```

В bash замените `.\mvnw.cmd` на `bash ./mvnw`. Переменные подключения к БД должны быть доступны процессу `java -jar`, как и при `spring-boot:run`.

На этой Windows-машине JDK не смог открыть selector, когда путь временной папки содержал короткое имя вида `THUNDE~1`. Windows-профиль Maven задаёт `jdk.net.unixdomain.tmpdir` для тестов и `spring-boot:run` автоматически. Если тот же сбой появляется при запуске JAR, укажите существующую папку полным путём без коротких имён:

```powershell
java -Djdk.net.unixdomain.tmpdir=C:/Daniyar/MyProjects/FocusDuo/backend/target -jar target/focusduo-backend-0.1.0.jar
```

Путь должен соответствовать вашей рабочей копии; это настройка временной папки JDK, авторизация приложения от неё не меняется.

`verify` запускает `*IT` через Maven Failsafe. По умолчанию тесты поднимают настоящий PostgreSQL 17.11 через Testcontainers. Отсутствие Docker является ошибкой такого прогона, а не тихим пропуском тестов. CI на GitHub исполняет именно `verify` на Ubuntu с Docker. H2 не используется.

Для проверки уже работающего JAR из корня репозитория:

```bash
python3 backend/scripts/smoke.py --base-url http://127.0.0.1:8090 --wait-seconds 60
```

Проверенная команда из PowerShell, также из корня репозитория:

```powershell
py -3 backend/scripts/smoke.py --base-url http://127.0.0.1:8090 --wait-seconds 60
```

Скрипт выполняет полный HTTP-сценарий двух пользователей, повторяет сохранённые запросы, проверяет историю и отзывает обе сессии. Он создаёт тестовые аккаунты и историю в выбранной БД; записи сохраняются. Отдельный `scripts/WebSocketSmoke.java` проверяет два подключения к работающему JAR, пропущенное изменение и reconnect, `409`, отзыв сессии `4401` и закрытый snapshot перед закрытием сокета. Команды — в [scripts/README.md](scripts/README.md). Эти инструменты проверяют сервер без Kotlin; сценарий настоящих desktop-клиентов и фиксация результата — в [INTEGRATION.md](../docs/backend/INTEGRATION.md).

Во втором этапе WSL `verify` с Testcontainers и нативный Windows `verify` с отдельной PostgreSQL через `FOCUSDUO_TEST_DB_*` прошли **по 53 теста: 27 unit + 26 integration, без failures/errors/skips**. [CI объединённого PR #2](https://github.com/KDvibers/FocusDuo/actions/runs/38070089325) также успешен, включая Compose/JAR HTTP smoke.

При подготовке третьего этапа с новым Compose-volume снова прошёл HTTP smoke из Windows (8 `PASS`); новый WebSocket smoke прошёл из Windows и WSL (по 8 `PASS`). Дополнительный локальный сценарий с двумя принудительными остановками и повторными запусками JAR подтвердил сохранение PAUSED/RUNNING, задач, сессий, результата повторного запроса и единственной записи истории (3 `PASS`). Подготовка объединена через PR #4; [CI после merge](https://github.com/KDvibers/FocusDuo/actions/runs/38072287807) прошёл `verify`, Compose и оба HTTP/WS smoke.

После desktop-этапа 6 на Windows прошли `test check backendAuthSmoke backendRoomSmoke`: **143 обычных теста + 2 живых теста**, без failures/errors/skips. Два настоящих Kotlin `RealRepository`/CIO работали через REST/WS с отдельным Java JAR и PostgreSQL в WSL: create/join, задача, FOCUS/pause/resume/finish, неизменяемая история, BREAK/close. Этот прогон использовал два repository в одной JVM и не проверял Compose-окна или LAN. Команды, дополнительные сценарии и границы — в [ROOM_INTEGRATION](../docs/backend/ROOM_INTEGRATION.md), дальнейшая приёмка — в [INTEGRATION](../docs/backend/INTEGRATION.md) и [HANDOFF](../docs/backend/HANDOFF.md).

После desktop-этапа 7 повторный `test check backendAuthSmoke backendRoomSmoke` прошёл **171 обычный тест + 2 живых**, без ошибок и пропусков. Новые opt-in проверки подтвердили потерянные ответы после настоящего commit, точный ручной replay без повторного эффекта, реальный 409 и автоматическое восстановление двух Kotlin repository после двух принудительных остановок Java с той же БД (PAUSED и RUNNING). UI, физический сон и LAN этим не проверяются. Точные команды изолированного стенда, результаты и замечание Kotlin-разработчику — в [RECOVERY_INTEGRATION](../docs/backend/RECOVERY_INTEGRATION.md).

После исправления wake в desktop PR #14 повтор на базе `a1e557f` прошёл **185 обычных тестов + 2 живых smoke** и все четыре расширенных auth/lifecycle/recovery/restart harness. Два принудительных рестарта Java вновь подтвердили автоматическое восстановление, безопасные повторы не создали дублей. Замечание к coordinator закрыто; результаты и команды — в [WAKE_ACCEPTANCE](../docs/backend/WAKE_ACCEPTANCE.md). Полный Maven `verify` в этом повторе не запускался, серверная реализация не менялась.

Затем 11 октября выполнена [ручная приёмка M1–M4](../docs/backend/COMPOSE_ACCEPTANCE.md) на desktop `8fdb392`, рабочей базе `30c8235`: два отдельных native Compose-процесса Windows → Java JAR в WSL на 8090 → обычная сохраняемая PostgreSQL на 5434. Наблюдаемые сценарии прошли: общая комната/задачи, FOCUS, физический сон в PAUSED и RUNNING, ручной итог, неизменяемая история, BREAK и CLOSED. Задержка восстановления не измерялась; foreground-активация окон могла участвовать в восстановлении. `.env` и данные БД сохранены; после logout обоих пользователей сервер и окна оставлены работать. Команды повторения — в [MANUAL_ACCEPTANCE](../docs/backend/MANUAL_ACCEPTANCE.md), границы контрольного PR — в [PR_COMPOSE_ACCEPTANCE](../docs/backend/PR_COMPOSE_ACCEPTANCE.md).

Этот GUI-сеанс не перезапускал 185 + 2 теста или четыре harness. Его отчёт объединён через PR #17. В текущем `main` **`1a6fa94`** также объединены desktop PR #16/#18, клиент **`774378c`**: [этап 8.1](../desktop/docs/STAGE8_1_INTEGRATION.md) подтвердил обычный lifecycle и исправленный logout, [8.2](../desktop/docs/STAGE8_2_RECOVERY.md) — финальные **199 + 2 теста**, GUI recovery/navigation и **BREAK/ELAPSED** после рестарта. Отдельный **TTL PT30S UI/SQL PASS** относится к baseline `0da9c6c` с неизменённым auth-кодом; четыре harness — к версии до navigation-fix.

В `feat/backend-final-integration` отдельный [просроченный FOCUS — F1 PASS](../docs/backend/EXPIRED_FOCUS_ACCEPTANCE.md): оба UI показывали offline 00:00, после рестарта Java автоматически открыли итог 5 минут, а SQL подтвердил одну `COMPLETED/ELAPSED`-историю с исходным deadline и 300000 мс. Поздняя правка задачи не изменила историю; после close/logout нет активных членств и неотозванных сессий. [Новый harness](scripts/kotlin-expired-focus-smoke/README.md) показывает два production `RealApp` с настоящими repository в одном тестовом JVM, используя API bootstrap; `Main`/`AuthScreen`/`RealWorkspace` и физический сон этим не проверяются. Текущие desktop test/check **199/0/0/0**, Maven package **27/0/0/0** и компиляция harness прошли отдельно; полный `verify`, прежние четыре harness, auth/room smoke и TTL/BREAK не перезапускались. Backend-этап 3 остаётся открытым: далее review F1, оставшаяся матрица K1–K7 и LAN/адаптер; у desktop — UI-регрессия (9) и пакет (10).

Дополнительный локальный режим допускает выделенный PostgreSQL 17 без Docker:

```powershell
$env:FOCUSDUO_TEST_DB_URL='jdbc:postgresql://127.0.0.1:55434/focusduo_test'
$env:FOCUSDUO_TEST_DB_USERNAME='focusduo_test'
$env:FOCUSDUO_TEST_DB_PASSWORD='<пароль отдельной тестовой БД>'
.\mvnw.cmd verify
```

В bash задайте те же три переменные через `export`. Этот режим предназначен только для отдельной тестовой БД: тесты применяют миграции и изменяют данные с управляемыми часами. Не указывайте здесь рабочую БД. Удалите эти переменные перед проверкой Testcontainers. Такой прогон проверяет настоящую PostgreSQL-схему и транзакции, но не доказывает работу Docker/Testcontainers.

Результаты текущего выполнения и ограничения фиксируются в [HANDOFF.md](../docs/backend/HANDOFF.md). Отчёты Maven находятся в `target/surefire-reports` и `target/failsafe-reports`.

## Устройство и границы MVP

PostgreSQL хранит пользователей, хеши токенов, комнаты и членства, задачи/цели, временные опоры раундов, историю и успешные результаты идемпотентных запросов. REST отдаёт DTO, не JPA-сущности. Один внедряемый `Clock` позволяет проверять время без ожидания реальных минут. После восстановления процесса просроченные раунды завершаются через общий механизм актуализации.

WebSocket передаёт полные снимки после commit, без STOMP/SockJS и без секундных JSON-тиков. HTTP выполняет команды; WebSocket служит для серверных событий. Подробнее о revision, reconnect, terminal-состояниях и неизменяемой истории — в [HANDOFF.md](../docs/backend/HANDOFF.md).

MVP рассчитан на один процесс приложения. Для нескольких экземпляров потребуется межпроцессная доставка событий WebSocket; БД остаётся источником истины, но локальный реестр соединений не является распределённой шиной. История выдаёт последние 50 элементов без пагинации. Нет смены партнёра/leave, восстановления пароля, очистки старых сессий или гарантии доставки события клиенту после сетевого сбоя; reconnect получает актуальный полный снимок.

Версии и инструменты проверены по [системным требованиям Spring Boot](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [документации Maven Wrapper](https://maven.apache.org/tools/wrapper/), [release notes PostgreSQL 17.11](https://www.postgresql.org/docs/17/release-17-11.html) и [официальным Docker tags PostgreSQL](https://github.com/docker-library/official-images/blob/master/library/postgres). Зафиксированные зависимости находятся в `pom.xml`, wrapper properties и `compose.yaml`.
