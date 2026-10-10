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

Auth-проверка объединена через [PR #9](https://github.com/KDvibers/FocusDuo/pull/9), commit `a71c34d`; [backend CI](https://github.com/KDvibers/FocusDuo/actions/runs/38078661860) успешен. Desktop-этап 6 объединён через [PR #10](https://github.com/KDvibers/FocusDuo/pull/10), общий `main` — `7cee8c4`. Ветка текущей проверки — `feat/backend-room-integration`.

На этой базе прошли **143 обычных теста, `check`, 1 auth smoke и 1 room smoke**. Два production Kotlin repository через CIO/REST/WS выполнили create/join, изменение цели и задачи, FOCUS/pause/resume/finish, чтение неизменяемой истории, BREAK и close против отдельного Java JAR и PostgreSQL. Прежний auth harness адаптирован к состояниям клиента этапа 6 и повторно прошёл. Подробные результаты и ограничения — в [ROOM_INTEGRATION.md](ROOM_INTEGRATION.md).

Дополнительный lifecycle harness подтвердил ручной reconnect с новым циклом синхронизации, login с восстановлением участия без join, отзыв активной WS-сессии и сохранение CLOSED без возможности изменений. [Описание контрольного PR](PR_ROOM_INTEGRATION.md) подготовлено для push владельца; backend-этап 3 остаётся открытым до полной приёмки.

Следующая зависимость — desktop-этап 7: автоматическое восстановление и безопасные повторы, затем совместная проверка Compose-окон, сетевых сбоев и LAN. Успешный repository-прогон не закрывает всю UI-приёмку. Реализация и оформление `desktop/` остаются у друга.

Контрольная точка: отдельный PR с необходимыми backend-изменениями и документированной приёмкой; локальный коммит → push владельца → PR/review/проверки. Подготовка может быть передана отдельным коммитом или draft PR, но третий этап не закрывается до реального прогона с Kotlin. Непроверенные окружения перечисляются явно.

Исходные `PROMPT_BACKEND_JAVA.txt` и `PROMPT_DESKTOP_KOTLIN.txt` остаются локальными untracked-материалами и не входят в первый PR. Общий контракт и исходные fixtures сохраняются как согласованные материалы проекта.
