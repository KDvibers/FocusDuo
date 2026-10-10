# FocusDuo

Приложение для совместных рабочих сессий двух пользователей. Java backend и Kotlin desktop-клиент используют общий контракт v1; у каждой части своя сборка.

- [Запуск Java backend](backend/README.md): Java 21, Spring Boot, PostgreSQL 17, Maven Wrapper.
- [Передача Kotlin-разработчику](docs/backend/HANDOFF.md): подключение, REST/JSON WebSocket, повтор запросов, правила таймера.
- [Совместная приёмка](docs/backend/INTEGRATION.md): локальный `.env`, два клиента, первый сетевой сценарий и проверки восстановления.
- [Согласованный контракт](docs/FOCUSDUO_CONTRACT_V1.txt), [OpenAPI](docs/contracts/openapi.yaml), [примеры запросов](docs/contracts/requests.http), [исходные JSON fixtures](docs/contracts/fixtures/).

Локальный сервер: `http://127.0.0.1:8090`, REST: `/api/v1`, WebSocket: `/ws/rooms/{roomId}`. Для двух компьютеров оба клиента обращаются к одному серверу по его LAN-адресу. Инструкция настройки bind-address находится в backend README.

Области ответственности: `backend/`, `docs/backend/`, `docs/contracts/`, корневые инфраструктурные файлы и backend CI — Java-разработчик; `desktop/` — Kotlin-разработчик в отдельной рабочей копии. Общий протокол меняется только согласованно. Сборку и CI desktop добавляет её владелец отдельно.

Репозиторий: [KDvibers/FocusDuo](https://github.com/KDvibers/FocusDuo). Работа backend разделена на [три этапа с отдельными PR](docs/backend/ROADMAP.md). Первые два этапа и подготовка третьего объединены; третий продолжается совместной приёмкой Kotlin. Desktop имеет собственный [план](desktop/docs/ROADMAP.md): объединены этапы 1–7. [Основной сетевой сценарий](docs/backend/ROOM_INTEGRATION.md) и [восстановление с безопасными повторами](docs/backend/RECOVERY_INTEGRATION.md) проверены через настоящие Kotlin repository, Java и PostgreSQL. После исправления wake в PR #14 [автоматическая Java-проверка](docs/backend/WAKE_ACCEPTANCE.md) прошла: 185 обычных тестов, 2 живых smoke и четыре расширенных harness.

11 октября выполнена [ручная приёмка M1–M4](docs/backend/COMPOSE_ACCEPTANCE.md): два независимых Compose-процесса с Java/PostgreSQL, задачи и общий таймер, физический сон Windows в PAUSED и RUNNING, неизменяемая история и закрытие комнаты. Наблюдаемые сценарии прошли; это не закрывает полный набор K1–K7. LAN, TTL, просроченный ELAPSED и полная UI-приёмка остаются открытыми. Следующее для Kotlin-разработчика — продолжить интеграцию (этап 8), затем UI-приёмку (9) и Windows-пакет (10). [Инструкция повторения](docs/backend/MANUAL_ACCEPTANCE.md) и [описание контрольного PR](docs/backend/PR_COMPOSE_ACCEPTANCE.md) сохраняют границы доказательств; прежние автоматические тесты в GUI-сеансе не перезапускались.

Изменения фиксируются локально, push выполняет владелец проекта, затем открывается PR для review и проверок. Новые backend-ветки используют префикс `feat/`. Автоматические push и merge не выполняются.
