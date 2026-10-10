# Живая авторизация Kotlin → Java

Проверенная база: `main` `7acb617`, после merge desktop PR #8; реализация desktop `a53ecde`. Ветка отчёта — `feat/backend-auth-integration`. Проверки выполнены 10–11 октября 2026, Asia/Qyzylorda. Desktop-файлы, production-код backend, общий контракт и fixtures не изменялись.

## Окружение

- Клиентские тесты: Windows, Temurin JDK 21.0.12.101, собственный Gradle Wrapper desktop 8.13; настоящий Ktor/CIO из `RealRepository`.
- Сервер: отдельный процесс `focusduo-backend-0.1.0.jar` в WSL Ubuntu, `http://127.0.0.1:8090`, JVM с `-Djava.net.preferIPv4Stack=true`. Production-исходники и Maven-зависимости совпадают с проверенной серверной версией.
- БД: новый контейнер PostgreSQL `17.11-bookworm`, отдельная база `focusduo_auth_stage5` на `127.0.0.1:55435`, временное хранилище. Миграции применены сервером. Пароль сгенерирован в памяти и передан через окружение без вывода.
- Пользовательская Compose-БД `focusduo-db-1` на 5434 и корневой `.env` сохранены. Тесты создают только свои случайные аккаунты.

## Выполненная проверка A1

Из `desktop/`, с JDK 21 в `JAVA_HOME` и `PATH`, выполнена команда:

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:/Daniyar/MyProjects/FocusDuo/backend/target'
.\gradlew.bat test backendAuthSmoke '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' --console=plain --no-daemon
```

`backend/target` должен существовать; на другой машине замените абсолютный путь. Параметр временного каталога нужен для известной особенности Windows JDK с коротким именем `THUNDE~1` в TEMP. Он задаётся только текущему процессу оболочки и дочерним JVM. Для повтора сначала запустите Java backend по [README](../../backend/README.md), используя сервер разработки: smoke сохраняет созданный аккаунт в выбранной БД.

Результат: **BUILD SUCCESSFUL**, exit 0. Проверено по XML, отдельно для каждой задачи:

| Задача | Тестов | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `test` | 112 | 0 | 0 | 0 |
| `backendAuthSmoke` | 1 | 0 | 0 | 0 |

В обычные 112 тестов входят 17 `ContractFixturesTest`; SHA-256 всех 10 desktop-копий fixtures также отдельно сравнен с `docs/contracts/fixtures` и совпал.

Живой `LiveBackendAuthTest` выполнил регистрацию пользователя с русским displayName и паролем с кириллицей, `/me`, чтение `current` с `room=null`, logout, новый login, восстановление того же userId/профиля, повторное чтение пустой комнаты и logout. Это реальный Java backend и PostgreSQL, без подмены HTTP-сервера.

Локальные отчёты после запуска: `desktop/build/reports/tests/test/index.html`, `desktop/build/reports/tests/backendAuthSmoke/index.html`, XML — в соответствующих подпапках `desktop/build/test-results/`. Эти build-артефакты исключены из Git.

## Выполненная проверка A2

Добавлен [внешний Kotlin harness](../../backend/scripts/kotlin-auth-smoke/README.md). Он компилируется в `backend/target` существующим Kotlin-компилятором desktop и использует его production `RealRepository`/CIO. Maven-сборка backend и desktop-исходники не меняются, новых зависимостей нет. Init script добавляет отдельную opt-in задачу только на время вызова.

Из корня репозитория, с теми же JDK 21 и `JAVA_TOOL_OPTIONS`:

```powershell
.\desktop\gradlew.bat -p desktop -I (Resolve-Path backend/scripts/kotlin-auth-smoke/init.gradle).Path realRepositorySmoke '-Dfocusduo.smoke.baseUrl=http://127.0.0.1:8090' --console=plain
```

Нормальный прогон финальной версии: **BUILD SUCCESSFUL**, 6 секунд, exit 0; пять контрольных сценариев и итоговый `PASS`:

1. Нормализация регистрации, неверный пароль → `UNAUTHORIZED`, повтор username → `USERNAME_TAKEN`.
2. Два независимых `RealRepository` читают одну заполненную комнату: два участника, русская цель и задача; совпадают current и чтение известной комнаты.
3. Внешний logout именно токена первого repository вызывает настоящий HTTP 401 при следующем чтении: статус становится `EXPIRED`, локальная комната очищается. Второй участник остаётся авторизованным.
4. Новый login восстанавливает того же пользователя и комнату. `repository.logout()` устанавливает `SIGNED_OUT`; прежний токен получает 401, второй участник продолжает читать открытую комнату.
5. Явное закрытие тестовой комнаты освобождает членства, current второго участника становится null; сессии отзываются.

Создание/join, цель и задача в этом harness выполняются вспомогательными JDK HTTP-запросами для подготовки данных; команды Kotlin здесь не реализуются и не объявляются проверенными. Delegate над CIO только наблюдает bearer в памяти, чтобы отозвать конкретную сессию, без подмены запросов или ответов. Пароли и токены не выводятся. Проверены только native Windows/JDK 21; команда Linux/macOS из README в этом заходе не запускалась.

## Потеря ответа и очистка

При review harness исправлена очистка случая, когда сервер создал комнату, но ответ `POST /rooms` потерялся до сохранения её ID тестом. После попытки create очистка восстанавливает собственную комнату через `/rooms/current`, проверяет владельца и закрывает её по текущей revision до logout.

Это отдельно проверено временным локальным proxy на 8091: он переслал create на реальный Java backend, получил 200 после commit и оборвал ответ клиенту. Harness **ожидаемо завершился с exit 1 / IOException**. Proxy подтвердил один потерянный ответ, один восстановительный GET current и одно успешное закрытие. SQL в изолированной БД подтвердил **0 OPEN rooms, 0 active memberships, 0 unrevoked sessions**. Это намеренно вызванный сбой и проверка очистки; его нельзя записывать как обычный успешный smoke. Временный proxy не входит в репозиторий или CI.

После проверок остановлены только собственные JAR/proxy, удалён тестовый PostgreSQL-контейнер вместе с tmpfs-данными. Порты 8090, 8091 и 55435 освобождены. Пользовательский `focusduo-db-1` на 5434 остался healthy; `.env` сохранён и исключён из Git.

## Границы результата

Обычные mock/TCP-тесты, A1 и A2 — разные проверки. A1 подтверждает совместимость регистрации, login/logout, профиля и пустого `current`; A2 дополняет существующую комнату, ошибки авторизации и отзыв токена. Ни один из них не проверяет WS, таймер, историю или Compose-окно. Controller и гонки смены сервера проверены обычными тестами; в живом сценарии использовался repository, не UI/controller.

Полные Kotlin-сценарии K1–K7 и LAN остаются открытыми в [INTEGRATION.md](INTEGRATION.md). REST-команды и WS ещё относятся к desktop-этапу 6; визуальная проверка новых экранов отложена владельцем desktop до этапа 7. Истечение TTL и WS 4401 в этом заходе не проверялись. A2 запускается отдельно и не добавлен в backend CI. Полный Maven `verify` в этом прогоне не повторялся: серверная реализация не менялась. Предыдущий [CI серверной подготовки](https://github.com/KDvibers/FocusDuo/actions/runs/38072287807) остаётся отдельным доказательством `verify` и HTTP/WS smoke. Новый CI этой ветки ещё не запускался; push выполняет владелец.
