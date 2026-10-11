# FocusDuo — Windows MSI и протокол приёмки

## Статус и область

На 12 октября 2026 подготовка ведётся в `feat/desktop-windows-package` от `main` `467ed63`. Все изменения ограничены `desktop/**`. Бинарник остаётся локальным результатом сборки; в Git входят конфигурация, ресурсы, скрипт и проверяемые свидетельства. Commit, push, GitHub Release и публикация автоматически не выполняются.

**Текущий статус: MSI собран; локальные установка, оба ярлыка, встроенный runtime, удаление Windows Installer и повторная установка PASS. Полная приёмка открыта:** установленный HTTPS/WSS и второй физический ПК ожидают пользовательского прогона. Чистая VM, удаление через Параметры Windows и обновление на следующую версию не выполнены. Сборка сама по себе эти пункты не закрывает.

Ранее два участника на разных ПК подтвердили совместную работу через Tailscale Serve, включая восстановление после выключения Tailscale, FOCUS/BREAK, историю и повторный вход. Это ручной `USER_REPORTED`-прогон клиентов через `desktop/run.ps1` на `af3af62` и `22c07b9` с локальными изменениями, без зафиксированных хешей запущенных сборок. Он **не является проверкой установленного MSI**. Источник: [REMOTE_ACCEPTANCE_2026-10-12.md](../../docs/backend/REMOTE_ACCEPTANCE_2026-10-12.md).

## Что получает пользователь

Пакет устанавливает Kotlin/Compose-клиент FocusDuo и его Java runtime. Backend и PostgreSQL работают отдельно на одном общем сервере. На конечном ПК не должны требоваться Java, Gradle, WiX или исходники.

Первый формат — MSI, версия `0.1.0`, имя `FocusDuo`, vendor `KDvibers`. Постоянный `upgradeUuid=10aee174-af1c-4dc4-9705-bec80b54639c` не меняется при выпуске следующей версии. Установка предназначена текущему пользователю, позволяет выбрать каталог, создаёт ярлыки на рабочем столе и в меню «Пуск» → FocusDuo. Собственная иконка включена в ресурсы пакета, launcher, ярлыки и Compose-окно; мастер MSI сохраняет стандартное оформление WiX. EXE этим этапом не заявляется.

## Сборка разработчиком

Требуется Windows x64 и JDK 21 x64. `jvmToolchain(21)` выбирает компилятор; Gradle и `jpackage` тоже нужно запускать выбранным JDK 21. Не собирать Windows MSI через WSL/Linux.

Предпочтительный путь из `desktop/`, **PowerShell 7 x64**:

```powershell
.\package-windows.ps1 -Task version
.\package-windows.ps1 -Task check
.\package-windows.ps1 -Task createDistributable
.\package-windows.ps1 -Task runDistributable
# Закрыть окно образа перед упаковкой.
.\package-windows.ps1 -Task packageMsi
```

Без `-Task` скрипт выполняет `packageMsi`. Он проверяет native Windows x64, JDK 21 x64 и наличие `java`, `javac`, `jlink`, `jpackage`; ищет JDK в `FOCUSDUO_JAVA_HOME`, `JAVA_HOME`, `%USERPROFILE%\.jdks`, затем по `java` из `PATH`. Выбранный путь передаётся Gradle как `-Pfocusduo.javaHome`; изменения `JAVA_HOME`/`PATH` локальны и восстанавливаются в конце. Поддержана также задача `suggestRuntimeModules`.

Прямой эквивалент из корня репозитория:

```powershell
$env:JAVA_HOME = 'C:\путь\к\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location desktop
.\gradlew.bat --version
.\gradlew.bat check --console=plain
.\gradlew.bat createDistributable --console=plain
.\gradlew.bat runDistributable --console=plain
# Закрыть окно образа перед следующим шагом.
.\gradlew.bat packageMsi --console=plain
```

Compose Multiplatform `1.9.1` использует `WIX_PATH`, если он задан; иначе скачивает WiX 3.11.2 в инструменты сборки. WiX нужен разработчику/плагину, а не пользователю установленного приложения. Фактически использованный путь и версия записываются в протоколе ниже. Если сокращать runtime позднее, задача анализа называется `suggestRuntimeModules`; она не заменяет реальные TLS/UI-проверки.

Первый runtime включает все модули JDK (`includeAllModules = true`), включая криптографические провайдеры для HTTPS/WSS. TLS-проверка сертификатов сохраняется. Уменьшение runtime требует отдельной приёмки шрифтов/кириллицы, clipboard, Compose/Skiko и настоящего HTTPS/WSS.

Выходной каталог — `desktop/build/compose/binaries/`; локальный результат этого прогона: `C:\Users\user\.codex\worktrees\desktop-windows-package\focusDuo\desktop\build\compose\binaries\main\msi\FocusDuo-0.1.0.msi`. Его размер и SHA-256 необходимо сверить с протоколом ниже. Нельзя передавать владельцу другой файл под тем же названием без обновления SHA-256. `desktop/build/` не добавляется в Git.

## Установка и запуск

1. Проверить SHA-256 полученного MSI через `Get-FileHash -Algorithm SHA256`. Статус подписи сверить через `Get-AuthenticodeSignature`.
2. Открыть MSI обычным пользователем, выбрать каталог и завершить установку. Не предоставлять права администратора только ради запуска клиента.
3. Запустить FocusDuo с рабочего стола, затем отдельно через меню «Пуск». Приложение должно открываться без терминала/Gradle и использовать runtime из установленного каталога.
4. На форме входа указать базовый адрес общего сервера, например `https://focusduo.example`, без `/api/v1` и `/ws`, затем войти в свой аккаунт. Другой клиент указывает тот же сервер.
5. Для демо явно выбрать соответствующий режим в интерфейсе. Ошибка настоящего сервера не переключает приложение на fake-данные.

Личный Tailscale-домен не зашит в продукт или конфигурацию пакета. Default разработки остаётся `http://127.0.0.1:8090`: на компьютере друга это адрес самого компьютера друга. Backend/PostgreSQL не устанавливаются клиентским MSI.

Несекретные настройки находятся в `%USERPROFILE%\.focusduo\preferences.properties`: только `baseUrl` и `rememberedUsername`. После закрытия и повторного запуска пароль пустой, требуется новый вход; после него клиент восстанавливает текущую комнату. Пароль и bearer-токен не записываются на диск. `FOCUSDUO_BASE_URL`, если задана в окружении, имеет приоритет над сохранённым адресом.

## Лицензии и состав

В сборку входят собственная иконка (`src/main/resources/icons/focusduo.svg`, PNG/ICO) и уведомления поставляемых зависимостей/runtime; Manrope сохраняет `src/main/resources/fonts/OFL.txt`. Перечень библиотек и тексты уведомлений находятся в `src/main/distribution-resources/common/licenses/`; `manifest.json` связывает каждый runtime JAR с координатами, SHA-256 и текстами уведомлений. `verifyRuntimeLicenses` автоматически входит в `check`, `createDistributable` и `packageMsi`: изменение runtime-зависимости требует актуализации перечня. `writeRuntimeLicenseInputs` создаёт вход для воспроизведения уведомлений; Python не нужен для обычной упаковки. Уведомления не назначают проекту новую лицензию. Лицензии встроенного JDK должны оставаться в runtime, а перечень библиотек сверяется с итоговым образом.

Ресурсы воспроизводятся из `desktop/`:

```powershell
python scripts/generate-app-icon.py
.\gradlew.bat writeRuntimeLicenseInputs --console=plain
python scripts/generate-third-party-notices.py
```

Python 3 (stdlib) нужен только для этих процедур разработчика. Получение текстов third-party notices требует сети; обычная сборка использует сохранённые тексты. Подготовленный manifest содержит 66 runtime JAR и 110 текстов license/NOTICE/POM; фактическая Gradle-сверка 66 artifacts PASS в новом `check`. Состав установленного runtime фиксируется отдельно ниже. Генерация иконки повторена побайтно: ICO 7819 байт, SHA-256 `9f5eb9f54390054c0c52f4b267956b8dabba8a9909b58a94c129848c1c2895b4`; PNG 3887 байт, SHA-256 `102b6b7a838ac500da219a85d70c42d7763a6eb54d0cdfb314bcf5996d56e947`. ICO содержит размеры 16/24/32/48/64/128/256, PNG — 256×256 с прозрачностью; SVG сохраняет геометрию двух зелёных кругов. После изменения ресурсов их нужно пересобрать из исходника, а не подменять в готовом MSI.

В MSI не включаются `.env`, preferences, пароли/токены, SQL/PG data, тестовые стенды или локальные отчёты. Фактический образ содержит уведомления в `app/resources/licenses` и legal-файлы JDK в `runtime/legal`; результаты проверки состава приведены ниже.

## Удаление и повторная установка

Закрыть FocusDuo, открыть Параметры Windows → Приложения → Установленные приложения → FocusDuo → Удалить. Удаляются приложение, встроенный runtime и ярлыки. Backend, PostgreSQL и серверная история не затрагиваются.

Несекретный каталог `%USERPROFILE%\.focusduo` сохраняется при обычном удалении и обновлении. При необходимости пользователь удаляет его отдельно; установщик автоматически этого не делает. Повторная установка той же версии не должна создавать вторую запись продукта или дубли ярлыков. Проверка обновления на другую версию требует отдельного пакета и не подменяется повторной установкой `0.1.0`.

## Фактическая сборка

| Поле | Результат |
| --- | --- |
| Base / ветка | `467ed63` / `feat/desktop-windows-package`; локальные изменения ещё не закоммичены |
| Windows / архитектура / JDK | Windows 11 Pro `10.0.26300`, x64; Corretto `21.0.6.7.1` |
| Gradle / Compose | `package-windows.ps1 -Task version` PASS: Gradle `8.13`, JVM `21.0.6`; Compose `1.9.1` |
| WiX | Плагин скачал WiX 3.11.2; фактический `desktop/build/wix311/candle.exe` — `3.11.2.4516` |
| Тесты / `check` | Новый полный прогон **199 тестов, 0 failures/errors/skips**, 35 s. Следующий `check` PASS за 1 s (`test` UP-TO-DATE), guard проверил 66 runtime artifacts |
| `createDistributable` / `runDistributable` | PASS, 20 s / PASS, 42 s (включая время открытого окна); [окно образа](screenshots/windows-package-image-login.jpg) |
| `packageMsi` | Финальный PASS, 32 s. Первоначальный запуск выявил кодировку MSI metadata; description переведён в ASCII, русский UI сохранён |
| Файл / размер / SHA-256 | `build/compose/binaries/main/msi/FocusDuo-0.1.0.msi`, **91317919 байт**; SHA-256 `8C47303CA6D871F994CC7248C86D39C728EF69D88ABE99AAC660DC5284E4B987` |
| Цифровая подпись | `Get-AuthenticodeSignature`: **NotSigned**, MSI не подписан |
| Состав образа / runtime | В образе есть `app/resources/licenses/manifest.json`, `runtime/legal` (70 каталогов), `runtime/bin/server/jvm.dll`; `runtime/release`: Java `21.0.6`. Поиск `.env`, preferences и маркеров PG data: 0 совпадений. Состав установленного каталога проверяется отдельно |

До запуска установленного приложения проверен доступ к выбранному пользователем HTTPS-серверу: `/api/v1/me` вернул ожидаемый `401`, TLS-проверка curl — `0` (сертификат принят). Это только предварительный probe, не подтверждение CIO/WSS установленного клиента; личный адрес в продукт не добавлен.

## Приёмка установленного файла

`USER_PENDING` означает ожидание совместной проверки пользователем; `NOT_RUN` — проверка не проводилась. Эти статусы не равны PASS. Машинный протокол и SHA-256 всех **15 реальных JPEG**: [windows-package-2026-10-12.json](evidence/windows-package-2026-10-12.json).

| Проверка | Статус и свидетельство |
| --- | --- |
| Установка текущим пользователем без повышения прав | PASS: `msiexec /i` этого MSI с `/passive /norestart`, exit 0; токен обычного пользователя, Installer COM Context=2 и SID текущего пользователя. Каталог `%LOCALAPPDATA%\FocusDuo` |
| Имя/версия, выбор каталога | FocusDuo `0.1.0`; реальные [welcome](screenshots/windows-package-msi-welcome.jpg) и [directory](screenshots/windows-package-msi-directory.jpg) страницы. Фактическая установка — в default `%LOCALAPPDATA%\FocusDuo`, произвольный каталог отдельно не проверялся |
| Ярлык рабочего стола | PASS: `.lnk` указывает на установленный EXE, запуск через ярлык при пустом JAVA_HOME и PATH без JDK |
| Ярлык «Пуск» → FocusDuo | PASS: `Programs\FocusDuo\FocusDuo.lnk` указывает на установленный EXE, отдельный запуск через ярлык при пустом JAVA_HOME и PATH без JDK |
| Иконки окна/панели задач, Manrope/кириллица | Иконка окна, настоящего [ярлыка](screenshots/windows-package-shortcut-icon.jpg) и кириллица PASS; Manrope наблюдался в Compose UI. Отдельное изолированное доказательство taskbar-иконки не снималось |
| Самостоятельный запуск со встроенным runtime | PASS: оба ярлыка без JAVA_HOME, PATH только Windows/System32; в реальном процессе загружены `jvm.dll` из установленного runtime и Skiko DLL из установленного app. Gradle/run.ps1 для этого запуска не используются |
| Чистая Windows без отдельного JDK/Gradle | NOT_RUN — отдельная VM пока не предоставлена; установленный runtime проверяется отдельно |
| Сохранение адреса / недоступный сервер / исправление адреса | Адрес и `msi_package_qa` сохранены через UI в изолированном QA-profile и восстановлены после перезапуска: PASS, ровно 2 несекретных ключа. [Форма](screenshots/windows-package-installed-server.jpg), [сохранение](screenshots/windows-package-installed-saving.jpg). Недоступный сервер/исправление адреса в установленном клиенте **NOT_RUN** |
| Два установленных клиента, настоящий HTTPS/WSS, create/join, двусторонние задачи, FOCUS/итог | USER_PENDING — установленная авторизация и два клиента ещё не выполнялись; пользователь проверит вместе со вторым ПК |
| Повторный запуск, пустой пароль, восстановление комнаты после входа | Повторный запуск с сохранёнными HTTPS-адресом/username и пустым паролем PASS: [1000×700](screenshots/windows-package-installed-relaunch-1000.jpg). Настоящий вход и восстановление комнаты после него — USER_PENDING |
| Размеры 1280×800 / 1000×700, прокрутка, фокус, длинные русские строки | Установленное явное демо: [1280×800](screenshots/windows-package-installed-demo-room-1280.jpg), [1000×700](screenshots/windows-package-installed-demo-room-1000.jpg), [прокрутка](screenshots/windows-package-installed-demo-scroll.jpg)/[1000×700](screenshots/windows-package-installed-demo-scroll-1000.jpg), [maximize](screenshots/windows-package-installed-demo-maximized.jpg) PASS. В 1000×700 — одна колонка и доступная прокрутка; перенос русской задачи из 134 символов проверен при 1280×800 и maximize. Размеры указаны как параметры запуска; захват окна исключает невидимые края рамки. Tab покидает поле; полный keyboard/accessibility/loading/error audit этим не подтверждён |
| Удаление приложения/runtime/ярлыков через Windows | **PASS через Windows Installer CLI**, `msiexec /x` `/passive /norestart`, exit 0: EXE/runtime/install-dir/оба ярлыка/регистрация отсутствуют. [Реальный прогресс удаления](screenshots/windows-package-uninstall-progress.jpg). **Удаление кнопкой Параметров Windows NOT_RUN**: страница приложений открылась, поиск завис на loading; использован Windows Installer |
| Сохранение несекретных preferences после удаления | PASS: SHA-256 исходного пользовательского и изолированного QA preferences совпали до/после удаления; содержимое исходного файла не публикуется |
| Повторная установка той же версии без дублей | PASS: тот же MSI/SHA-256, `msiexec /i` exit 0; ProductCount=1, версия `0.1.0`, ShortcutCount=2. Приложение оставлено установленным после повторной установки; [реальный запуск после reinstall](screenshots/windows-package-installed-after-reinstall.jpg) восстановил URL/username, пароль пустой |
| Обновление на новую версию | NOT_RUN — отдельного пакета следующей версии нет |
| Второй физический ПК с установленным MSI | USER_PENDING — пользователь проверит переданный файл после локальной приёмки; пока не заявляется PASS |

Скриншоты сняты с настоящего образа/установленного приложения/Windows Installer; пароли и токены не сохраняются. Внешний общий сервер не изменяется установщиком. Существующее пользовательское Java-окно сохранено; тестовый ярлык проверяется с отдельным preferences-файлом в игнорируемом `desktop/build/windows-package-qa/`. Обычный запуск прочитал прежние настройки пользователя и показал пустой пароль, исходные preferences не подменялись. Все тестовые окна закрыты после приёмки; исходное пользовательское Java-окно сохранено. После uninstall/reinstall клиент намеренно оставлен установленным для пользователя. Общий backend, PostgreSQL и серверная история этой локальной проверкой не менялись; настоящие installed auth/команды серверу ещё не выполнялись.

## Передача MSI на второй ПК

Пользователь согласовал проверку второго физического ПК после локальной приёмки. Передаётся **тот же** `FocusDuo-0.1.0.msi`, версия `0.1.0`; публичный Release не создаётся. Подготовлена локальная папка `desktop/build/windows-handoff/`: [MSI](../build/windows-handoff/FocusDuo-0.1.0.msi), [SHA-256](../build/windows-handoff/FocusDuo-0.1.0.msi.sha256), [краткие результаты](../build/windows-handoff/LOCAL_CHECK_RESULTS.txt). Это игнорируемые результаты сборки, а не файлы Git; при clean/новой рабочей копии их нужно получить заново. Скопированный MSI имеет тот же размер и SHA-256, что исходный артефакт. После получения файла выполнить в каталоге загрузки:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath '.\FocusDuo-0.1.0.msi'
Get-AuthenticodeSignature -LiteralPath '.\FocusDuo-0.1.0.msi' |
    Select-Object Status
```

Ожидаемый SHA-256: `8C47303CA6D871F994CC7248C86D39C728EF69D88ABE99AAC660DC5284E4B987`; подпись `NotSigned`. Установить текущему пользователю, открыть оба ярлыка, указать общий HTTPS-сервер, проверить вход/create или join/двустороннюю задачу/FOCUS и итог. Закрыть приложение, открыть вновь, убедиться в пустом пароле и восстановлении комнаты после входа. Удаление выполняется через Параметры Windows, затем проверяются отсутствие приложения/ярлыков и сохранение несекретных preferences. Результаты второго ПК фиксируются отдельно: до сообщения участника это `USER_PENDING`, не локальный PASS.

## Краткое сообщение backend-разработчику

> Собран реальный Windows x64 MSI FocusDuo `0.1.0`, 91317919 байт. SHA-256: `8C47303CA6D871F994CC7248C86D39C728EF69D88ABE99AAC660DC5284E4B987`. Пакет пока не подписан (`NotSigned`), публичного Release нет. На Windows 11 установка обычному пользователю, оба ярлыка, работа со встроенным runtime без JAVA_HOME/Gradle, сохранение несекретного адреса/username с пустым паролем после перезапуска, размеры/прокрутка/кириллица, удаление через Windows Installer и повторная установка без дублей PASS. Новый прогон: 199 тестов без ошибок. Следующая совместная проверка — именно этот MSI на втором ПК: реальный вход по HTTPS/WSS, create/join, двусторонние задачи, FOCUS/итог и восстановление комнаты после нового входа. Чистая VM, удаление через кнопку Параметров Windows и обновление на другую версию ещё не подтверждены.

## Фиксация и PR

После просмотра staged-файлов выполнять по одной команде; при ошибке остановиться:

```powershell
Set-Location "C:\Users\user\.codex\worktrees\desktop-windows-package\focusDuo"
git status --short --branch
git add -- desktop
git diff --cached --stat
git commit -m "feat(desktop): package FocusDuo as a Windows MSI"
git push -u origin feat/desktop-windows-package
```

PR: `feat/desktop-windows-package` → `main`, [заголовок и описание](PR_WINDOWS_PACKAGE.md). Бинарный MSI не коммитится и не публикуется автоматически.
