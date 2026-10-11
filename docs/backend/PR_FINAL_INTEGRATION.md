# test(integration): verify overdue FOCUS after backend restart

Проверяет ранее открытый сценарий: FOCUS истёк, пока Java выключена. Новый opt-in harness с двумя production Kotlin `RealRepository`/CIO ждёт реальные 300 секунд, перезапускает отдельную Java с той же PostgreSQL и проверяет точный ELAPSED, автоматическое восстановление и единственную неизменяемую историю. Исправления production-кода не потребовались.

Добавлены ограниченный компонентный Compose-вход с двумя `RealApp` в одном JVM, независимый read-only SQL probe, команды воспроизведения, [отчёт](EXPIRED_FOCUS_ACCEPTANCE.md), 7 настоящих снимков и безопасный JSON evidence. Сводки HANDOFF/INTEGRATION/ROADMAP учитывают актуальную базу `1a6fa94` / desktop `774378c` и отдельно сохраняют прежние доказательства TTL/BREAK и сна.

## Проверено

- `mvnw package`: **27 passed**; Gradle `test check`: **199 passed**, без failures/errors/skips. CLI и GUI harness скомпилированы.
- Один живой 300-секундный прогон с Java/WSL и disposable PostgreSQL 17.11: **PASS, exit 0**. При выключенной Java оба окна показали 00:00 и ожидание серверного завершения; БД ещё RUNNING без history.
- После рестарта оба repository автоматически восстановились, оба окна сами открыли итог 5 мин. COMPLETED/ELAPSED, `endedAt` равен исходному deadline, `activeElapsedMs=300000`, revision 8 → 9. Без ручного refresh/reconnect и без modifying REST-команд при recovery.
- Одна история для обоих пользователей, неизменная после поздней правки задачи и закрытия комнаты. Три SQL probe — PASS; финально CLOSED/revision 11, 1 round/1 history, 0 active memberships и 0 неотозванных сессий.
- Временный стенд удалён, 8090/55437 свободны; обычная БД 5434 healthy и `.env` сохранён. `desktop/**`, production backend и контракт не менялись.

## Границы

GUI-проверка использует API bootstrap и два неизменённых `RealApp` в **одном тестовом JVM**: это не повтор обычного входа через `Main/AuthScreen/RealWorkspace` и не два самостоятельных клиента. Гейты сами не являются UI-ассертом; приложены реальные наблюдения и снимки.

Полный Maven `verify`, прежние четыре harness, отдельные auth/room smoke и CI ветки не запускались. LAN/адаптер, полная UI-регрессия и упаковка остаются открытыми; backend-этап 3 этим PR не закрывается. Push выполняет владелец, автоматического merge нет.
