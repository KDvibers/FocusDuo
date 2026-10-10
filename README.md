# FocusDuo

Приложение для совместных рабочих сессий двух пользователей. Java backend и Kotlin desktop-клиент используют общий контракт v1; у каждой части своя сборка.

- [Запуск Java backend](backend/README.md): Java 21, Spring Boot, PostgreSQL 17, Maven Wrapper.
- [Передача Kotlin-разработчику](docs/backend/HANDOFF.md): подключение, REST/JSON WebSocket, повтор запросов, правила таймера.
- [Совместная приёмка](docs/backend/INTEGRATION.md): локальный `.env`, два клиента, первый сетевой сценарий и проверки восстановления.
- [Согласованный контракт](docs/FOCUSDUO_CONTRACT_V1.txt), [OpenAPI](docs/contracts/openapi.yaml), [примеры запросов](docs/contracts/requests.http), [исходные JSON fixtures](docs/contracts/fixtures/).

Локальный сервер: `http://127.0.0.1:8090`, REST: `/api/v1`, WebSocket: `/ws/rooms/{roomId}`. Для двух компьютеров оба клиента обращаются к одному серверу по его LAN-адресу. Инструкция настройки bind-address находится в backend README.

Области ответственности: `backend/`, `docs/backend/`, `docs/contracts/`, корневые инфраструктурные файлы и backend CI — Java-разработчик; `desktop/` — Kotlin-разработчик в отдельной рабочей копии. Общий протокол меняется только согласованно. Сборку и CI desktop добавляет её владелец отдельно.

Репозиторий: [KDvibers/FocusDuo](https://github.com/KDvibers/FocusDuo). Работа backend разделена на [три этапа с отдельными PR](docs/backend/ROADMAP.md). Первые два этапа и подготовка третьего объединены; третий продолжается совместной приёмкой Kotlin. Desktop имеет собственный [план](desktop/docs/ROADMAP.md): объединены этапы 1–5, включая демо и HTTP-авторизацию через `RealRepository`. Команды комнаты, реальная история и WebSocket остаются desktop-этапу 6. Изменения фиксируются локально, push выполняет владелец проекта, затем открывается PR для review и проверок. Новые backend-ветки используют префикс `feat/`. Автоматические push и merge не выполняются.
