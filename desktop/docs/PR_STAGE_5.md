# PR этапа 5

## Заголовок

Подключить Ktor и авторизацию FocusDuo Desktop через backend

## Описание

Обычный режим теперь использует `RealRepository` и backend V1: пользователь может зарегистрироваться, войти, загрузить профиль и восстановить текущую комнату. Явный деморежим продолжает использовать отдельный `FakeRepository`; сетевые ошибки не подменяются локальными данными.

- Ktor Client с CIO, kotlinx.serialization, DTO и mappings по общему контракту V1.
- REST register/login/logout, `/me`, `/rooms/current` и ручное обновление комнаты для чтения. Повтор загрузки после сетевого сбоя не повторяет POST авторизации.
- Bearer-токен только в памяти, очистка сессии при `401`, локальный выход даже при ошибке logout, отмена старых запросов при смене сервера.
- Формы и настройки подключены к реальному режиму; пароль не сохраняется и не изменяется перед отправкой. Команды комнаты, история, живой таймер и WebSocket остаются отдельным этапом 6.
- Автономные копии всех 10 JSON-fixtures, контрактные и HTTP/controller-тесты, TCP-проверка production CIO. Добавлена явная задача `backendAuthSmoke` для работающего Java backend.
- Обновлены README, ROADMAP и HANDOFF. Изменения ограничены `desktop/**`.

## Проверки и границы

Windows / JDK 21: `gradlew.bat test --console=plain` — **BUILD SUCCESSFUL, 112 passed, 0 failures, 0 errors, 0 skipped**. `gradlew.bat check --console=plain` также успешен. Все 10 исходных fixtures проверены; подробности в [HANDOFF.md](HANDOFF.md).

TCP-тест CIO использует локальный тестовый HTTP-сервер и не заменяет совместную проверку Java backend. Backend на `127.0.0.1:8090` не был запущен, поэтому `backendAuthSmoke` не выполнялся. Проверка Compose-окна и новые скриншоты по указанию пользователя отложены до этапа 7. Команды комнаты, raw WebSocket, reconnect, конфликты revision и Windows-пакет не входят в этот PR.

## Коммит и push

Выполнить из PowerShell последовательно, останавливаясь при ошибке:

```powershell
Set-Location "C:\Users\user\Desktop\focusDuo"
git add -- desktop
git diff --cached --stat
git commit -m "feat(desktop): connect Ktor backend authentication"
git push -u origin feat/desktop-mvp
```

Эти команды передаются пользователю. Агент не выполняет commit, push или merge.
