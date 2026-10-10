# test(integration): verify Kotlin recovery and safe retries

После desktop-этапа 7 восстановление соединения и безопасные повторы ещё не были проверены с Java backend. Этот PR добавляет воспроизводимые opt-in проверки с настоящими Kotlin repository, CIO, Java и PostgreSQL, а также фиксирует результаты и оставшиеся условия приёмки.

## Изменения

- Recovery harness теряет полный ответ Java 200 после commit, проверяет pending и точный ручной replay create/task без второго эффекта или отката свежего snapshot. Задержанная команда проверяет настоящий 409 и новое действие с новым ключом.
- Restart harness и контроллер изолированного стенда проверяют два SIGKILL/start Java с той же PostgreSQL, автоматическую REST/WS-синхронизацию двух клиентов, PAUSED/RUNNING и единственную историю.
- Старый lifecycle harness ждёт асинхронного завершения восстановления CLOSED-комнаты вместо мгновенного NONE.
- Обновлены отчёт, реестр приёмки, HANDOFF и план. Production Java, `desktop/**`, общий контракт и fixtures не изменены.

## Проверено

База `67b66c5`, desktop `0fb2b56`; native Windows / JDK 21 → WSL Java:8090 → одноразовая PostgreSQL 17.11:55437.

- `test check backendAuthSmoke backendRoomSmoke`: **171 обычный тест + 2 живых**, 0 failures/errors/skips, BUILD SUCCESSFUL за 39 секунд.
- Прежние auth/lifecycle harness вместе: PASS, 11 секунд.
- `realRecoverySmoke`: 4 checkpoints и итог PASS, 7 секунд.
- `realRestartSmoke`: 5 checkpoints и итог PASS, 43 секунды; production backoff/jitter без замены, автоматическое восстановление без ручных reconnect/bootstrap.
- 10 SHA-256 fixtures совпали с общими. SQL после новых сценариев: 0 OPEN rooms / active memberships / unrevoked sessions, 1 раунд / 1 история, 2 миграции.
- Тестовый Java и PostgreSQL удалены; пользовательская БД на 5434 и `.env` сохранены.
- Контроллер отдельно запущен с конфликтующими Spring/JVM overrides родительского окружения: Java использовала только свою временную БД и classpath-конфигурацию. Повтор start подтвердил readiness без создания нового процесса.

## Границы и замечание владельцу desktop

UI, физический сон Windows, LAN, TTL и просроченный ELAPSED в Kotlin не проверены. По коду выявлен условный пропуск wake-сигнала: debounce в `RealWorkspace` использует только nanoTime и может подавить обнаруженный календарный разрыв после сна. Условие и рекомендация описаны в HANDOFF; desktop здесь не исправлялся. Полная приёмка backend-этапа 3 остаётся открытой.

Полный Maven `verify` локально не повторялся: production-сервер не менялся; [CI после PR #11](https://github.com/KDvibers/FocusDuo/actions/runs/38079888693) успешен. Opt-in Kotlin harness не добавлены в CI; новый CI ожидается после push.

Команды и доказательства: [RECOVERY_INTEGRATION.md](RECOVERY_INTEGRATION.md). Для друга: [HANDOFF.md](HANDOFF.md). Полная матрица: [INTEGRATION.md](INTEGRATION.md).
