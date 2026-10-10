# test(integration): verify Kotlin rooms against Java backend

После desktop-этапа 6 команды комнаты, история и WebSocket ещё не были проверены с настоящим Java backend. Этот PR фиксирует успешный живой прогон двух Kotlin `RealRepository`/CIO и обновляет инструкции передачи для следующего этапа клиента.

## Изменения

- Адаптирован прежний REST auth harness: realtime явно отключён для доказательства HTTP 401; проверяются отдельно отсутствие текущего членства на сервере и сохранённый CLOSED snapshot клиента.
- Добавлен opt-in Kotlin harness в `backend/scripts/`: ручной reconnect с новым циклом синхронизации, восстановление участия после login без join, отзыв активной WS-сессии и сохранение закрытого итога с запретом изменений.
- Обновлены отчёт интеграции, реестр проверок, HANDOFF и план работ. Автоматическое восстановление, безопасные повторы и Compose UI остаются desktop-этапу 7.
- Production-код Java, реализация `desktop/**`, контракт и fixtures не изменены.

## Проверено

База: `main` `7cee8c4`, desktop `b69d3b1`. Windows / JDK 21 → отдельный Java JAR в WSL → новая PostgreSQL 17.11 на 55436; пользовательская БД и `.env` сохранены.

- `test check backendAuthSmoke backendRoomSmoke`: **143 обычных теста + 1 auth + 1 room**, 0 failures/errors/skips; BUILD SUCCESSFUL, 48 секунд, exit 0.
- Через Kotlin: create/join → два WS → цель/задача → FOCUS/pause/resume/finish → неизменяемая история → BREAK/close. SQL: 2 раунда, 2 истории, 0 открытых комнат/активных членств/действующих сессий после сценария.
- Адаптированный `realRepositorySmoke`: BUILD SUCCESSFUL, 6 секунд, exit 0.
- Финальный `realRoomLifecycleSmoke`: BUILD SUCCESSFUL, 6 секунд, exit 0; все пять checkpoints PASS. После отзыва WS-сессии партнёр подтвердил авторизацию реальным GET `/me`.
- Все 10 копий fixtures совпадают с общими по SHA-256.

После всех прогонов SQL подтвердил нулевые активные комнаты/членства/сессии. Временный JAR остановлен, собственная PostgreSQL с tmpfs удалена, 8090/55436 свободны; пользовательская БД на 5434 healthy. Cleanup при недоступном сервере не гарантируется, поэтому для этих проверок нужна одноразовая БД.

## Границы

Это repository-прогон без Compose-окон. UI, автоматическое восстановление, безопасные повторы при потере ответа, сон/пробуждение, Kotlin после рестарта Java, TTL и LAN пока не приняты. Полный Maven `verify` локально повторно не запускался: серверная реализация не менялась; [CI объединённого backend PR #9](https://github.com/KDvibers/FocusDuo/actions/runs/38078661860) успешен. Opt-in Kotlin smoke не входит в backend CI; текущий CI должен пройти после push.

У desktop `LiveBackendRoomTest` отмечена неполная серверная очистка при assertion/timeout; использовалась одноразовая БД. Исправление клиентского теста передано владельцу `desktop/`.

Точные команды и результаты: [ROOM_INTEGRATION.md](ROOM_INTEGRATION.md). Дальнейшая приёмка: [INTEGRATION.md](INTEGRATION.md), передача: [HANDOFF.md](HANDOFF.md).
