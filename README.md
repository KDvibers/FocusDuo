# FocusDuo

Приложение для совместных рабочих сессий двух пользователей. Java backend и Kotlin desktop-клиент используют общий контракт v1; у каждой части своя сборка.

- [Запуск Java backend](backend/README.md): Java 21, Spring Boot, PostgreSQL 17, Maven Wrapper.
- [Передача Kotlin-разработчику](docs/backend/HANDOFF.md): подключение, REST/JSON WebSocket, повтор запросов, правила таймера.
- [Совместная приёмка](docs/backend/INTEGRATION.md): локальный `.env`, два клиента, первый сетевой сценарий и проверки восстановления.
- [Согласованный контракт](docs/FOCUSDUO_CONTRACT_V1.txt), [OpenAPI](docs/contracts/openapi.yaml), [примеры запросов](docs/contracts/requests.http), [исходные JSON fixtures](docs/contracts/fixtures/).

Локальный сервер: `http://127.0.0.1:8090`, REST: `/api/v1`, WebSocket: `/ws/rooms/{roomId}`. Для двух компьютеров оба клиента обращаются к одному серверу по его LAN-адресу. Инструкция настройки bind-address находится в backend README.

Области ответственности: `backend/`, `docs/backend/`, `docs/contracts/`, корневые инфраструктурные файлы и backend CI — Java-разработчик; `desktop/` — Kotlin-разработчик в отдельной рабочей копии. Общий протокол меняется только согласованно. Сборку и CI desktop добавляет её владелец отдельно.

Репозиторий: [KDvibers/FocusDuo](https://github.com/KDvibers/FocusDuo). Работа backend разделена на [три этапа с отдельными PR](docs/backend/ROADMAP.md). Первые два этапа объединены; третий продолжается совместной приёмкой Kotlin. В `main` `1a6fa94` объединены результаты desktop 8.1 и блока recovery/navigation этапа 8.2 (PR #16/#18, клиент `774378c`), а также backend PR #17. [Основной сетевой сценарий](docs/backend/ROOM_INTEGRATION.md), [восстановление с безопасными повторами](docs/backend/RECOVERY_INTEGRATION.md) и [повтор после wake-fix](docs/backend/WAKE_ACCEPTANCE.md) сохраняют результаты на своих версиях.

11 октября [M1–M4](docs/backend/COMPOSE_ACCEPTANCE.md) подтвердили два независимых Compose-процесса с Java/PostgreSQL, Modern Standby в PAUSED/RUNNING, неизменяемую историю и CLOSED на desktop `8fdb392`. Затем [desktop 8.2](desktop/docs/STAGE8_2_RECOVERY.md) подтвердил **199 + 2 теста**, исправленную навигацию, автоматический **BREAK/ELAPSED** после рестарта и отдельный **TTL PT30S UI/SQL**. 12 октября [FOCUS-прогон F1](docs/backend/EXPIRED_FOCUS_ACCEPTANCE.md) также прошёл: оба UI дождались сервера после offline 00:00 и автоматически показали итог 5 минут, SQL подтвердил одну историю с исходным deadline. Это два production `RealApp` в одном тестовом JVM с API bootstrap; штатный Main/вход и сон этим не проверялись. LAN/адаптер, оставшаяся матрица [K1–K7](docs/backend/INTEGRATION.md), финальная UI-регрессия (desktop 9) и упаковка (10) остаются открытыми. Backend-этап 3 пока не закрыт.

Изменения фиксируются локально, push выполняет владелец проекта, затем открывается PR для review и проверок. Новые backend-ветки используют префикс `feat/`. Автоматические push и merge не выполняются.
