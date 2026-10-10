# Заголовок PR

Исправить потерю сигнала пробуждения при остановке монотонных часов

# Описание PR

После сна `ResumeDetector` мог обнаружить большой календарный разрыв, но двухсекундный барьер в Compose workspace отбрасывал сигнал, если `nanoTime` продвинулся меньше чем на две секунды. Поскольку baseline детектора уже обновлялся, следующий heartbeat не повторял обнаруженное пробуждение.

Добавлен общий `ResumeRecoveryCoordinator` для heartbeat и foreground-сигналов. Ограничение частоты теперь учитывает оба источника времени, сохраняя подавление коротких дублей и защиту от одновременных вызовов. Тесты с управляемыми часами проходят production-путь «детектор → барьер → восстановление».

Включены ранее выполненная Compose-проверка этапа 7, 27 реальных скриншотов и заполненная документация. Результаты Java/PostgreSQL-интеграции из PR #13 указаны отдельно с источником. Подробный разбор и план совместной приёмки — `desktop/docs/WAKE_RECOVERY_REVIEW.md`.

Проверки:

- На прежнем nano-only алгоритме новые регрессионные тесты воспроизвели дефект: 5 coordinator-тестов и 1 repository seam-тест упали.
- После исправления `gradlew.bat check --console=plain`: BUILD SUCCESSFUL за 8 секунд, 185 тестов, 0 failures/errors/skipped по XML.
- Добавлены 13 coordinator-тестов с управляемыми часами и 1 проверка полного пути до немедленной блокировки изменений `RealRepository`.

Границы: Java harness после исправления не перезапускались; новая GUI-проверка и физический сон Windows не заявляются. LAN, TTL, просроченный ELAPSED, два Compose-окна с Java и Windows-упаковка остаются условиями следующих проверок. Изменения только в `desktop/**`.

# Команды PowerShell

Рабочая копия исправления отделена от основной, чтобы сохранить её незакоммиченные результаты. Выполнять команды по одной, проверяя результат:

```powershell
Set-Location "C:\Users\user\.codex\worktrees\desktop-wake-recovery\focusDuo"
git status --short --branch
git add -- desktop
git diff --cached --stat
git commit -m "fix(desktop): preserve wake recovery when monotonic clock pauses"
git push -u origin feat/desktop-wake-recovery
```

Создать PR `feat/desktop-wake-recovery` → `main`. Агент не выполнял commit, push или создание PR.
