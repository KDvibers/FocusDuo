# Этапы backend и контрольные PR

Репозиторий: [KDvibers/FocusDuo](https://github.com/KDvibers/FocusDuo). Для каждого этапа: подготовить изменения и проверки → создать локальный коммит → **владелец выполняет push** → открыть PR → пройти review и проверки. PR не объединяется автоматически; решение о merge принимает владелец. Новые backend-ветки используют `feat/`, без префикса `codex/`; названия прежних веток ниже сохранены как история. Существующие изменения сохраняются, `desktop/` изменяет только Kotlin-разработчик.

## 1. Серверный MVP по контракту v1

Объём: весь реализованный Java backend — авторизация, комнаты на двоих, цели/задачи, таймер с паузой и перерывом, история, raw JSON WebSocket, идемпотентность, revision и транзакционные ограничения. Включает Flyway, Maven Wrapper, Compose, backend CI, необходимые тесты и документацию запуска/контракта.

Состояние: первый PR `feat/backend-mvp` → `main` объединён, базовый commit `main` — `462b827`. Локально прошли 40 тестов с нативным PostgreSQL 17.11 и HTTP-проверка собранного JAR. [GitHub CI](https://github.com/KDvibers/FocusDuo/actions/runs/37818507362) успешно выполнил Testcontainers-прогон. [Описание первого PR](PR_STAGE_1.md) сохранено как запись этого этапа.

Контрольная точка пройдена: первый PR принят владельцем и объединён. Kotlin-интеграция и дополнительные проверки воспроизводимости переходят в следующие этапы.

## 2. Проверка Docker/CI и воспроизводимого запуска

Проверить чистый запуск PostgreSQL через Compose, стандартный Testcontainers-прогон `mvnw verify`, backend CI и команды запуска из документации. Добавить HTTP smoke собранного JAR, регрессии ограничений/состояний/валидации/прав доступа и исправления по результатам проверок, сохраняя согласованный протокол.

Состояние: [PR #2](https://github.com/KDvibers/FocusDuo/pull/2) проверен и объединён в `main` (`e7e9db0`). [GitHub CI после merge](https://github.com/KDvibers/FocusDuo/actions/runs/38070089325) завершился успешно, включая Testcontainers и Compose/JAR HTTP smoke. Локально каждый `verify` прошёл 53 теста (27 unit + 26 integration), без failures/errors/skips: Linux использовал Testcontainers, Windows — отдельную PostgreSQL 17.11 через WSL Compose и `FOCUSDUO_TEST_DB_*`. Исправлены строгая привязка UUID в MVC и запуск Mockito agent. [Описание второго PR](PR_STAGE_2.md) сохранено как отчёт этапа.

Контрольная точка пройдена: владелец выполнил push, друг проверил PR и объединил его; CI успешен. Второй этап закрыт.

## 3. Интеграция с Kotlin и итоговая передача

Согласовать подключение двух desktop-клиентов к одному серверу и пройти сценарий: register/login → create/join → FOCUS → правки задач → pause/resume → reconnect → finish/history → BREAK → close. Проверить обработку stale revision, повтор после потери ответа, `4401`, закрытого snapshot и восстановления состояния. Зафиксировать результаты приёмки и окончательные сведения в HANDOFF.

Состояние: подготовка выполнена в `codex/backend-stage-3` от общего `main` `a508020`, включающего backend PR #2 и desktop PR #3. Добавлены создание локального `.env`, отдельная WebSocket-проверка собранного JAR, её запуск в CI и [инструкция совместной приёмки](INTEGRATION.md). Локально прошли новый Compose-volume, HTTP smoke (8 `PASS`), WS smoke из Windows и WSL (по 8 `PASS`) и восстановление после двух принудительных остановок JAR (3 `PASS`). [Описание PR подготовки](PR_STAGE_3.md) перечисляет границы этих результатов.

Подготовка объединена через [PR #4](https://github.com/KDvibers/FocusDuo/pull/4), commit `6915291`; [CI после merge](https://github.com/KDvibers/FocusDuo/actions/runs/38072287807) прошёл `verify`, Compose и HTTP/WS smoke. В общем `main` `7acb617` также объединены desktop-этапы 1–5, включая `RealRepository`, HTTP-авторизацию и чтение текущей комнаты (PR #8, реализация `a53ecde`). Авторизация проверена в ветке `feat/backend-auth-integration`.

Проверка авторизации уже выполнена: Windows `test` — 112 тестов, отдельный `backendAuthSmoke` — 1 живой тест против Java/WSL и изолированной PostgreSQL; без failures/errors/skips. Дополнительный opt-in harness подтвердил заполненную комнату, ошибки авторизации, HTTP 401 после отзыва токена, очистку состояния и восстановление участия после login. Проверена очистка после намеренно потерянного ответа создания комнаты. Команды и границы результата — в [AUTH_INTEGRATION.md](AUTH_INTEGRATION.md), описание контрольного PR — в [PR_AUTH_INTEGRATION.md](PR_AUTH_INTEGRATION.md).

Auth-проверка объединена через [PR #9](https://github.com/KDvibers/FocusDuo/pull/9), commit `a71c34d`; [backend CI](https://github.com/KDvibers/FocusDuo/actions/runs/38078661860) успешен. Desktop-этап 6 объединён через [PR #10](https://github.com/KDvibers/FocusDuo/pull/10), общий `main` — `7cee8c4`. Ветка room-проверки — `feat/backend-room-integration`.

На этой базе прошли **143 обычных теста, `check`, 1 auth smoke и 1 room smoke**. Два production Kotlin repository через CIO/REST/WS выполнили create/join, изменение цели и задачи, FOCUS/pause/resume/finish, чтение неизменяемой истории, BREAK и close против отдельного Java JAR и PostgreSQL. Прежний auth harness адаптирован к состояниям клиента этапа 6 и повторно прошёл. Подробные результаты и ограничения — в [ROOM_INTEGRATION.md](ROOM_INTEGRATION.md).

Дополнительный lifecycle harness подтвердил ручной reconnect с новым циклом синхронизации, login с восстановлением участия без join, отзыв активной WS-сессии и сохранение CLOSED без возможности изменений. [Контрольный PR #11](https://github.com/KDvibers/FocusDuo/pull/11) объединён в `516020e`; [backend CI](https://github.com/KDvibers/FocusDuo/actions/runs/38079888693) успешен. [Описание PR](PR_ROOM_INTEGRATION.md) сохранено как отчёт.

Desktop-этап 7 объединён через PR #12 (`67b66c5`, реализация `0fb2b56`). Ветка `feat/backend-recovery-integration` проверила его с Java: **171 обычный тест + 2 живых**, прежние auth/lifecycle harness, потерянные ответы и точный replay, реальный 409, два принудительных рестарта с автоматическим восстановлением Kotlin и единственной историей. [Отчёт](RECOVERY_INTEGRATION.md) и [описание PR](PR_RECOVERY_INTEGRATION.md) отделяют эти repository-результаты от UI. Проверка объединена через [PR #13](https://github.com/KDvibers/FocusDuo/pull/13), commit `8310e23`; [backend CI](https://github.com/KDvibers/FocusDuo/actions/runs/38082205454) успешен.

Условный пропуск wake исправлен в desktop PR #14 (`8fdb392`), объединённом в `main` `a1e557f`. Общий coordinator учитывает оба источника времени; добавлены 13 coordinator-тестов и 1 repository seam-тест. Ветка `feat/backend-wake-acceptance` повторно проверила Java-интеграцию: **185 обычных тестов + 2 живых smoke и все четыре расширенных harness прошли**, включая два принудительных рестарта Java, безопасные повторы и историю без дублей. Подробные результаты и границы — в [WAKE_ACCEPTANCE.md](WAKE_ACCEPTANCE.md), описание контрольного PR — в [PR_WAKE_ACCEPTANCE.md](PR_WAKE_ACCEPTANCE.md).

Документация wake-приёмки объединена через [PR #15](https://github.com/KDvibers/FocusDuo/pull/15), `main` `9287c91`. Затем выполнен [ручной прогон M1–M4](COMPOSE_ACCEPTANCE.md): два независимых native Compose-процесса на desktop `8fdb392` / рабочей базе `30c8235` с настоящими Java и сохраняемой PostgreSQL. Проверяемый код в `9287c91` тот же. Пройдены общая комната/цели/задачи, FOCUS и пауза, физический сон Windows в PAUSED (**73,5048 с**, остаток **24:45** в обоих окнах) и RUNNING (**117,0503 с**, календарный отсчёт), ручной итог **4 мин 31 с**, неизменяемая история, отменённый BREAK **37 с** и CLOSED. Оба клиента показали две записи истории; оба logout выполнены. Отчёт оформляется в `feat/backend-compose-acceptance`, [описание контрольного PR](PR_COMPOSE_ACCEPTANCE.md).

M1–M4 имеют **PASS в наблюдаемом объёме**, отдельно от прежних 27 demo/синтетических снимков. Recovery latency не измерялась; foreground мог быть триггером, восстановление только по heartbeat и остановка `nanoTime` этим прогоном не доказаны. Прежние 185 + 2 теста и четыре harness в GUI-сеансе не перезапускались. Push и CI новой ветки ещё не выполнялись; `.env` и данные БД сохранены.

Следующее для Kotlin-разработчика — продолжить интеграцию **этапа 8** по оставшимся пунктам [K1–K7](INTEGRATION.md). LAN/сетевой адаптер, TTL и просроченный ELAPSED остаются открытыми; далее — полная UI-проверка **этапа 9** и Windows-пакет **этапа 10**. Короткая ручная приёмка не закрывает всю матрицу или финальный UI-аудит; **backend-этап 3 пока открыт**. Реализация и оформление `desktop/` остаются у друга. [Инструкция M1–M4](MANUAL_ACCEPTANCE.md) доступна для повторения.

Контрольная точка: отдельный PR с необходимыми backend-изменениями и документированной приёмкой; локальный коммит → push владельца → PR/review/проверки. Пройденные части передаются отдельными PR, но третий этап закрывается только после согласованного объёма итоговой приёмки и review. Непроверенные окружения перечисляются явно.

Исходные `PROMPT_BACKEND_JAVA.txt` и `PROMPT_DESKTOP_KOTLIN.txt` остаются локальными untracked-материалами и не входят в первый PR. Общий контракт и исходные fixtures сохраняются как согласованные материалы проекта.
