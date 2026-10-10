# docs(integration): record Java checks after wake recovery fix

После desktop PR #14 Java-интеграция ещё не запускалась на обновлённом клиенте. Повторены базовые тесты и четыре существующих живых harness на объединённом `main` `a1e557f`; результаты зафиксированы, прежнее замечание о пропуске wake закрыто. Добавлена инструкция запуска двух Compose-окон с Java и ручной проверки сна Windows. Backend-этап 3 остаётся открытым до совместной приёмки.

Изменены только README и `docs/backend/**`. Реализация Java/Kotlin, контракт, fixtures и harness не менялись.

## Проверки

- `test check backendAuthSmoke backendRoomSmoke`: BUILD SUCCESSFUL за 27 секунд; 185 обычных тестов + 2 живых, без failures/errors/skips. В число 185 входят 13 coordinator-тестов и 1 repository seam-тест. Все 10 fixture SHA-256 совпали.
- `realRepositorySmoke`, `realRoomLifecycleSmoke`, `realRecoverySmoke`, `realRestartSmoke`: каждый exit 0 / PASS, 6/6/6/42 секунды. Проверены auth/WS lifecycle, точный replay потерянных ответов, реальный 409 и автоматическое восстановление двух Kotlin repository после двух принудительных остановок Java.
- SQL после прогона: 0 OPEN rooms, 0 active memberships, 0 unrevoked sessions; 3 раунда / 3 истории, дублей нет. Временные Java и PostgreSQL удалены; пользовательская БД на 5434 и `.env` сохранены.
- Подготовка запуска двух клиентов: один Gradle-экспорт runtime classpath за 6 секунд и прямой `MainKt --help` прошли с exit 0; окна и сон этим не проверялись.
- `git diff --check` и локальные ссылки Markdown проверены.

## Ограничения и следующий шаг

Два Compose-окна с Java, физический сон Windows, LAN/отключение сетевой карты, живое истечение TTL и восстановление просроченного ELAPSED пока NOT_RUN. 27 desktop-скриншотов относятся к прежнему сеансу с демо/тестовым HTTP/WS-сервером; этот повтор проверяет реальные repository с Java без GUI. Прямой `recoverAfterResume()` в harness не заменяет сон ОС.

Полный Maven `verify` локально не повторялся: серверная реализация неизменна. CI объединённого backend PR #13 успешен; CI этой ветки ещё не запускался. Подробности: [WAKE_ACCEPTANCE](WAKE_ACCEPTANCE.md), команды ручной приёмки: [MANUAL_ACCEPTANCE](MANUAL_ACCEPTANCE.md), передача: [HANDOFF](HANDOFF.md).
