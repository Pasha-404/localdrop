# LocalDrop Windows — план реализации и журнал

**Статус:** ACTIVE · **Создан:** 2026-09-08 · **Базовая версия:** Windows 2.3.0, Protocol v2-open.

Это живой план Windows-клиента. В нём фиксируются не только будущие задачи, но и решения, коммиты, доказательства проверок, отклонения от плана и незакрытые риски. Не заменяет [code review](CODE_REVIEW_GPT-6.md), [общий план UI/UX](../../docs/UI_UX_REVIEW_GPT-6.md), [дизайн доверия устройств](../../docs/DEVICE_TRUST_DESIGN_GPT-6.md) или каноническую [спецификацию протокола](../../android/docs/protocol/localdrop-protocol-v2.md).

## Как пользоваться планом

- Статус задачи: `PLANNED` → `IN PROGRESS` → `DONE`, `BLOCKED` или `DEFERRED`.
- Перед началом меняйте статус и создавайте запись в «Журнале реализации». После завершения добавляйте точные файлы, коммит(ы), тесты, ручные сценарии и оставшиеся ограничения.
- Общая задача `CP-*` не считается закрытой, пока Android и Windows не реализовали одно решение, спецификация/общие vectors обновлены, оба набора тестов прошли и выполнена реальная передача Android → Windows и Windows → Android.
- Не заменять доказательство фразой «работает». Для приёма файлов проверять байты существующего и нового файла; для протокола — положительные и отрицательные vectors; для lifecycle/инсталлятора — соответствующий ручной сценарий.
- Не добавлять pairing, ключи, TLS или новую v3-функциональность в v2-исправления. Это отдельный поздний этап `L-*`.

## Карта приоритетов

| Очередь | Цель | Когда начинать |
| --- | --- | --- |
| 0. Обязательное: сохранность файлов | Нельзя удалить или перезаписать пользовательский файл; незавершённый файл не публикуется | Сразу |
| 1. Обязательное: общий v2-контракт | Windows и Android одинаково отклоняют некорректную сессию и одинаково понимают путь/ACK | После согласования общей спецификации; реализация на обеих платформах синхронно |
| 2. Обязательное: честное состояние UI | Очередь не посылает скрытые файлы и не застревает в ложном `SENDING`; готовность не рисуется без сервера | После очереди 0; часть задач независима от протокола |
| 3. Обязательное перед следующим Windows-релизом | Установщик, metadata, upgrade и release assets соответствуют стандарту | После очередей 0–2, до публикации следующего stable release |
| 4. Желательное: устойчивость | Ограничение зависаний/соединений, recovery, multihoming, конфигурация | После обязательного функционального ядра |
| 5. Желательное: UX и размеры окна | Компактный режим, корректное восстановление bounds, полезные статусы | После очередей 0–2; не раньше исправления истинности статусов |
| 6. Позже | Доверенные устройства v3 и необязательные оптимизации | Только после завершения обязательного и желательного либо отдельного решения о смене продукта/протокола |

## Этапы разработки

План разделён на три независимых очереди: Windows-only, Android-only и common. В текущем цикле завершены source-изменения Windows-only и начаты согласованные common C1-C2. Общий wire-контракт не меняется в одной платформе: C1 реализуется синхронно в Windows и Android и остаётся открытым до physical smoke.

До завершения Windows-части остаёмся в одном локальном рабочем дереве: **не коммитим, не push-им и не создаём GitHub release или публичный installer**. Локальная сборка development/release-candidate артефактов разрешена только как доказательство проверки и не означает публикацию.

| Очередь | Этап | Статус | Состав задач | Результат и exit criteria | Зависимости / применяемые skills |
| --- | --- | --- | --- | --- | --- |
| Windows-only | E0. Базовая линия и управление планом | DONE | Сверка `IMPLEMENTATION_PLAN_GPT-6.md`, code review и UI/UX review; создание этапной карты. | Приоритеты, v2-open границы и запрет на раннюю публикацию зафиксированы. Код не менялся. | `docs-and-readme-writer`, `core-release-verification` |
| Windows-only | E1. Безопасный приём файлов | IN PROGRESS | `W-FILE-01…04` (`X-01`, `W-01`, `W-02`, `W-09`). | Staging принадлежит конкретной операции, создаётся эксклюзивно; cleanup консервативен и идемпотентен; final publish никогда не заменяет существующий файл; containment учитывает filesystem links/reparse points. | Не зависит от wire-изменений. `core-safe-file-transactions` |
| Windows-only | E2. Истинное состояние отправки и приёма | IN PROGRESS | `W-STATE-01…04` (`W-03`, `W-04`, `W-05`, Windows-часть `X-07`). | Явная модель фаз и ownership batch: поздние события не меняют terminal item, clear/remove не скрывают запланированную отправку, READY отражает listener/folder, а confirmed/failed/unknown результаты различимы. | После E1 code scope. `core-state-machine-workflows`, `net-lan-discovery` |
| Windows-only | E3. Устойчивость Windows и lifecycle | IN PROGRESS | `W-RES-03…05` (`W-10…12`). | Tray/single-instance/no-tray/shutdown имеют один lifecycle путь; UDP/TCP listener восстанавливается с bounded retry без spin; config write атомарен и повреждённый config не затирается defaults. | После E2 code scope. `windows-desktop-lifecycle`, `core-safe-file-transactions`, `core-state-machine-workflows` |
| Windows-only | E4. Адаптивный Windows UX | IN PROGRESS | `W-UX-01…04`, presentation-части `W-STATE-03…04`, `W-13`. | Content-width-driven compact mode; safe restore normal bounds/maximized/monitor state; полезные network/receive/queue статусы; RU/EN, keyboard, tray и DPI matrix. | После E2/E3 code scope. `windows-desktop-lifecycle`, `docs-and-readme-writer` |
| Windows-only | E5. Windows release contract и installer hardening | IN PROGRESS | `W-REL-01…04` (`W-06…08`, `W-14`). | Версия/repository/AppId имеют один источник; ICO соответствует контракту; CI не переписывает published asset с тем же тегом. | После E1–E4. `windows-installer-update`, `core-release-verification` |
| Windows-only | E6. Windows release-candidate evidence | IN PROGRESS | `W-REL-05`. | Clean install, Inno upgrade, legacy MSI migration, uninstall и locked/active-app scenarios проверены с `PASS`/`FAIL`/`NOT TESTED`/`BLOCKED` evidence. | Подготовлен checklist; локальный RC допустим, публикация запрещена. `windows-installer-update`, `core-release-verification` |
| Android-only | A1. Android implementation plan | PLANNED | Без выполнения в этой ветке: отдельный анализ Android review, SAF receive, UI state и Android release requirements. | Android задачи планируются и реализуются только в `android/` после отдельного старта Android-цикла. | Не начинается в текущем Windows-цикле. |
| Common | C1. Protocol v2 contract | IN PROGRESS | `CP-01…05` (`X-02…05`). | Revision `2026-09-09-r3`, identical positive/negative vectors и matching source checks готовы. Необходимы реальные Android ↔ Windows transfer smoke. | `net-versioned-protocol`, `core-state-machine-workflows` |
| Common | C2. Network resilience and topology | IN PROGRESS | `W-RES-01`, `W-RES-02`, `CP-06`, `CP-07` (`X-06`, `X-08`). | Общие budgets connect/header/ACK/payload-idle и admission limit согласованы в source; bounded endpoint freshness есть в обеих платформах. Матрица Wi-Fi/Ethernet/VPN/DHCP/multihoming остаётся. | После C1 source work. `net-timeout-retry-budget`, `net-lan-discovery`, `net-versioned-protocol` |
| Common | C3. Финализация и публикация | IN PROGRESS | Существенное повышение версии, единые commits платформ, push/tag и GitHub releases. | Android 2.0.0 и Windows 3.0.0 готовятся к публикации по явной команде; published artifacts остаются неизменяемыми. | `windows-installer-update`, `core-release-verification` |

### Правило отметки этапов

1. Перед работой поменять статус этапа на `IN PROGRESS` и добавить в журнал запись с ID задач, границей scope и планируемыми проверками.
2. После работы обновить статусы всех входящих ID, этапную строку и журнал: перечислить изменённые файлы, фактические проверки и `PASS`/`FAIL`/`NOT TESTED`/`BLOCKED`.
3. Этап нельзя отметить `DONE`, пока его exit criteria не доказаны. Незакрытая общая задача `CP-*` оставляет её и соответствующий межплатформенный gate открытыми, даже если Windows-код уже готов.
4. Переход к E9 не происходит автоматически и не является разрешением на публикацию без явной команды пользователя.

## 0. Обязательное: безопасный приём файлов

| ID | Статус | Что сделать | Готово, когда |
| --- | --- | --- | --- |
| W-FILE-01 | DONE | Заменить cleanup по суффиксу `.localdrop-part` на cleanup только доказуемо принадлежащих незавершённым операциям артефактов. | Старый пользовательский файл с допустимым суффиксом сохранён; удаляются только зарегистрированные abandoned artifacts; cleanup идемпотентен. Связано с `X-01`. |
| W-FILE-02 | DONE | Создавать staging-файл с уникальным идентификатором операции и эксклюзивным созданием; не открывать существующий staging-кандидат на truncate. | Конкурирующий/пользовательский файл не меняется ни до, ни после ошибки; запись/cleanup знает точный owned path. |
| W-FILE-03 | DONE | Сделать публикацию final файла строго no-clobber во всех путях; убрать `REPLACE_EXISTING` как fallback финализации. | Файл, появившийся между выбором имени и публикацией, сохраняет исходные байты; операция либо выбирает новое имя, либо безопасно завершается ошибкой. |
| W-FILE-04 | DONE | Усилить containment при receive root с учётом junction/symlink/reparse ancestor и гонок. | Тест с disposable junction/symlink не создаёт файл за пределами receive root; обычные вложенные каталоги работают. |

**Затронутые области:** `TransferServer`, `FileUtils`, модель owned staging/recovery, `FileUtilsTest` и новые receive integration tests.

**Минимальная проверка очереди:** существующие данные не меняются при staging collision, final collision, обрыве, отказе записи и cleanup; partial output не имеет пользовательского final-имени; повторная передача выбирает новый безопасный final name.

## 1. Обязательное: общий Protocol v2

| ID | Статус | Что сделать в Windows | Общая граница готовности |
| --- | --- | --- | --- |
| CP-01 | IN PROGRESS | Совместно с Android зафиксировать новый revision v2: common envelope для каждого control message, точные terminal totals, error precedence ACK, единую семантику `relativePath`/`fileName`/depth и обязательный `tcpPort`. | Спецификация revision `2026-09-09-r3` и одинаковые positive/negative vectors готовы; physical smoke остаётся. |
| CP-02 | IN PROGRESS | Вынести проверку общего envelope и phase-specific correlation в один понятный слой; проверить version/messageId/timestamp/device/session/file там, где они обязательны. Заполнять identity в `SESSION_REJECTED`. | Matching source checks Android/Windows зелёные; initial malformed header имеет описанное ограничение. Cross-device evidence остаётся. |
| CP-03 | IN PROGRESS | Ужесточить FILE_ACK: `errorCode != NONE` не может быть успешным ACK; сохранить документированную совместимость поддерживаемых positive markers. | Общий negative vector и tests на обеих платформах готовы. |
| CP-04 | IN PROGRESS | Реализовать согласованную path validation/output mapping и raw-segment checks до normalisation. | Обе платформы требуют raw leaf = `fileName` и отклоняют separators/absolute paths/dot segments. |
| CP-05 | IN PROGRESS | Добавить в Windows все новые vectors/unit/integration cases и выполнить двусторонний device smoke. | Gradle checks зелёные; реальные передачи в обе стороны ещё не выполнены. |

**Запрет на частичное завершение:** не выпускать/не считать совместимым изменение `CP-*`, реализованное только на Windows. Перед кодом обновить `android/docs/protocol/localdrop-protocol-v2.md` и оба набора resources `protocol-vectors`.

## 2. Обязательное: Windows-состояние передачи и готовность

| ID | Статус | Что сделать | Готово, когда |
| --- | --- | --- | --- |
| W-STATE-01 | IN PROGRESS | Разделить telemetry progress и переходы состояния в `MainController`/`TransferClient`; поздний progress не может вернуть terminal/retry item в `SENDING`. | Connect refusal, receiver rejection, write/ACK failure и success дают правильные message/status/action; retry/remove доступны только там, где действительно безопасны. |
| W-STATE-02 | IN PROGRESS | Резервировать весь dispatch batch до фоновой отправки либо корректно отменять операцию; `Clear`/remove не должны скрывать уже запланированный файл. | Очистка/удаление до connect и между файлами не приводит к скрытой отправке; новые несвязанные items обрабатываются отдельно. |
| W-STATE-03 | IN PROGRESS | Связать большую карточку «Приём» с действительным readiness receiver/folder/listener, а не с фиксированным green text. | `READY`, bind failure, folder failure, busy, stopped и recovery видны без противоречивой зелёной надписи. |
| W-STATE-04 | IN PROGRESS | Явно представить подтверждённые, неотправленные и неопределённые результаты batch после разрыва/потери ACK. | UI не предлагает слепо переотправить уже принятый файл; подтверждённые элементы не возвращаются в retry. Связано с `X-07`. |

**Проверка:** тестировать реальное связывание controller/listener, не только callback `TransferClient`; сценарии clear/remove, late events и потерянный FINISH_ACK обязательны.

## 3. Обязательное перед следующим stable Windows release

| ID | Статус | Что сделать | Готово, когда |
| --- | --- | --- | --- |
| W-REL-01 | IN PROGRESS | Перевести version/repository/AppId metadata на единый валидируемый источник; синхронизировать final JAR, About, diagnostics, installer и manifest. | Встроенные данные final app image и registry/read-back согласованы; diagnostics не использует устаревший fallback. |
| W-REL-02 | DONE | Для следующего стандартизированного релиза добавить `desktopShortcutTask: desktopicon`, baseline AppFleet 2.0.0 и полный schema validation. | `verifyReleaseArtifacts` строит expected schema 1 contract целиком и отклоняет лишние, отсутствующие или расходящиеся поля; manifest содержит `desktopShortcutTask=desktopicon` и `minimumAppFleetVersion=2.0.0`. Исторический v2.3.0 не переписывается. |
| W-REL-03 | DONE | Заменить single-layer ICO на утверждённый multi-resolution ICO и проверить ресурсы final EXE. | Generated ICO и готовый app-image EXE проверены: 16/32/48/64/128/256, `RT_GROUP_ICON=1`, `RT_ICON=6`. |
| W-REL-04 | IN PROGRESS | Сделать GitHub release assets неизменяемыми: matching published assets — no-op, различие — failure/new version; отдельно обработать draft. | Нельзя `--clobber`-ом заменить опубликованный release candidate; тесты/CI покрывают mismatched/matching cases. |
| W-REL-05 | IN PROGRESS | Выполнить clean install, Inno upgrade, legacy MSI migration, uninstall и активное приложение/locked-file scenarios на disposable profile/VM. | Checklist подготовлен; нужны PASS/FAIL/NOT TESTED evidence: exit code, registry, shortcuts/icon, сохранность config, 3 artifacts/hash/manifest. |

## 4. Желательное: сеть, lifecycle и данные Windows

| ID | Статус | Что сделать | Зависимость / критерий |
| --- | --- | --- | --- |
| W-RES-01 | IN PROGRESS | Ввести ownership active sockets, bounded admission и idle watchdog, который реально закрывает зависший read/write socket. | Active sockets already owned; Windows и Android допускают максимум четыре pending handshakes и сохраняют distinct header/ACK/payload-idle budgets. Physical stall evidence остаётся. |
| CP-06 | IN PROGRESS | Согласовать обе реализации: отдельные budgets connect/header/ACK/payload idle, bounded half-open connections, cancellation/recovery evidence. | Source checks зелёные; stalled writer, partial header, slow transfer и cancel на двух реальных клиентах не выполнены. |
| W-RES-02 | IN PROGRESS | Хранить ограниченный набор свежих endpoints для одного device ID; предпочитать успешный и пробовать остальные в одном connect budget. | До трёх latest-first endpoints хранятся per device; fallback происходит только до передачи payload. |
| CP-07 | IN PROGRESS | Проверить multi-homed/DHCP/VPN/interface-switch behaviour и diagnostics на обеих платформах. | Физическая матрица пока не выполнена; cross-subnet discovery не обещается. |
| W-RES-03 | IN PROGRESS | Исправить no-tray close fallback, shutdown active sockets и single-instance handshake/user-session scope. | Manual lifecycle: close/tray/restore, second instance, no tray, exit mid-transfer, installer close. |
| W-RES-04 | IN PROGRESS | Добавить bounded listener recovery после UDP/TCP startup failure; Refresh не подменяет restart listener. | Occupied then released port/socket case восстанавливается без duplicate services/log spin. |
| W-RES-05 | IN PROGRESS | Сделать конфигурационные записи атомарными/recoverable; не затирать повреждённый config defaults после read failure. | Fault injection проверяет write/replace/malformed/concurrent save и сохранность исходных байтов. |

## 5. Желательное: UI/UX и initial window size

| ID | Статус | Что сделать | Готово, когда |
| --- | --- | --- | --- |
| W-UX-01 | IN PROGRESS | Реализовать компактный content-width-driven layout mode; до него определить измеренные useful minima для RU/EN и реального queue content. | На узкой logical work area нет routine horizontal scroll и скрытых primary actions; три колонки сохраняются там, где действительно помещаются. |
| W-UX-02 | IN PROGRESS | Восстанавливать normal bounds/monitor/maximized state отдельно; first-run выбирать bounded centered size по алгоритму UI/UX review. | Не сохраняются bounds максимизированного/hidden stage как normal; исчезнувший монитор/negative coordinates/DPI не оставляют окно off-screen. |
| W-UX-03 | IN PROGRESS | Убрать повторяющиеся empty-state hints, отображать actual saved final name и заменить guessed adapter label полезным status/diagnostics split. | Queue/receiver actions остаются понятны с 0/1/many items; long paths/names не раздувают окно. |
| W-UX-04 | IN PROGRESS | Проверить keyboard, focus, RU/EN, tray и state variants после W-STATE-*; зафиксировать Windows/DPI matrix. | PASS/FAIL/NOT TESTED matrix, а не только скриншот одного разрешения. |

## 6. Позже: не начинать до решения о продукте

| ID | Статус | Что можно рассмотреть позже | Условие старта |
| --- | --- | --- | --- |
| L-TRUST-01 | DEFERRED | Реализация v3: постоянные identity keys, pinning, mutual TLS, одноразовое взаимное знакомство, trust storage/revocation и UI. | Обязательные и желательные очереди завершены; threat model и UX первого знакомства утверждены; есть совместимый Android/Java TLS spike. |
| L-OPT-01 | DEFERRED | Экономичная защита от same-size изменения исходного файла во время отправки. | Сначала сформулировать product guarantee: snapshot, fail-on-change или другой ограниченный подход; не копировать по умолчанию до 100 GiB. |
| L-OPT-02 | DEFERRED | Очистка packaging input через exact sync и дальнейшие non-critical diagnostics polish. | После release gate и без расширения scope текущих исправлений. |

## Общие release gates

Перед любым изменением файлового приёма: unit/integration failure paths и ручная безопасная проверка. Перед любым `CP-*`: обе Gradle-проверки, vectors, реальный Android ↔ Windows smoke. Перед Windows release: `buildWindowsInstaller`, exact artifact/hash/manifest inspection, clean-install/upgrade evidence. Перед пометкой `DONE` у задачи из очереди 4–5: перечислить, что реально проверено, а что осталось `NOT TESTED`.

## Журнал реализации

Добавлять записи сверху вниз: самая новая — первой.

| Дата | ID | Статус / решение | Реализация и доказательства | Ограничения / следующий шаг |
| --- | --- | --- | --- | --- |
| 2026-09-09 | C3: Windows 3.0.0 | IN PROGRESS | `gradle.properties` поднят до `3.0.0`. `clean buildWindowsInstaller --no-daemon` — PASS: Java tests, embedded metadata, 16/32/48/64/128/256 icon resources, Inno Setup 6.7.1 и `verifyReleaseArtifacts`. В `dist/release/3.0.0` ровно три файла: `LocalDrop-Setup-3.0.0-x64.exe`, его SHA-256 и `appfleet-manifest.json`; локальная SHA-256 сверена, manifest schema 1 содержит `desktopShortcutTask=desktopicon` и `minimumAppFleetVersion=2.0.0`. Commit `362f50a` отправлен в `main`, annotated tag `v3.0.0` опубликован; GitHub Actions run `34402634265` — PASS и создал GitHub Release с ровно этими тремя AppFleet-совместимыми файлами. | Clean install/upgrade/uninstall и реальные Android transfers остаются `NOT TESTED` до пользовательской проверки. |
| 2026-09-09 | C1: `CP-01…05` | IN PROGRESS | Windows и Android синхронизированы на protocol revision `2026-09-09-r3`: common envelope, phase correlation, terminal exact totals, FILE_ACK error precedence, strict raw receive path и mandatory discovery `tcpPort`. Добавлены одинаковые `file-ack-error-precedence-v2.json` и `session-terminal-invariants-v2.json`; последний исполняется tests обеих платформ. `windows: test` — PASS; `android: testDebugUnitTest` и `assembleDebug` — PASS. | Реальные Android → Windows и Windows → Android transfers — NOT TESTED, поэтому C1 не DONE. |
| 2026-09-09 | C2: `W-RES-01/02`, `CP-06/07` | IN PROGRESS | `TransferServer` ограничивает pending handshakes четырьмя owned sockets. Discovery хранит максимум три fresh endpoints per device, а `TransferClient` использует fallback только в TCP connect phase до protocol payload, разделяя один пятисекундный budget между attempts. `TransferClientTest` покрывает connection fallback на рабочий endpoint. Аналогичные source changes есть в Android. `windows: test` — PASS. | Wi-Fi/Ethernet, DHCP, VPN, stale endpoint, stalled header/writer, cancel/recovery matrix — NOT TESTED. |
| 2026-09-09 | E1, W-FILE-01…04 | IN PROGRESS | Начат аудит staging, publication, cleanup и containment. Планируемые проверки: collision, interruption, no-clobber race, user suffix preservation, link/reparse containment и idempotent cleanup. | Реализация и версии протокола пока не менялись; commit/push/release запрещены до E9. |
| 2026-09-09 | E0 | DONE | План декомпозирован на E0–E9 по `CODE_REVIEW_GPT-6.md` и `UI_UX_REVIEW_GPT-6.md`; определены Windows-only и межплатформенные gates. Код, Git и release assets не менялись. | Следующий этап: E1, `W-FILE-01…04`. |
| 2026-09-08 | План | Создан | Приоритеты сформированы по `CODE_REVIEW_GPT-6.md`, UI/UX review и trust design. Код не менялся. | Начать с W-FILE-01…04; CP-01 согласовать с Android до wire-изменений. |

### Текущий прогресс

- 2026-09-09, E6 / W-REL-05: `IN PROGRESS`. Подготовлен `WINDOWS_RELEASE_CANDIDATE_EVIDENCE.md` с safety boundary, точным набором manual scenarios, командами read-back и текущим честным статусом `NOT TESTED`; installer не запускался, existing installation и GitHub release не менялись.
- 2026-09-09, C1 / CP-01…05: `IN PROGRESS`. Spec revision r3, shared positive/negative vectors и matching source tests готовы; Windows/Android Gradle checks PASS. Двусторонняя реальная передача остаётся `NOT TESTED`.
- 2026-09-09, C2 / W-RES-01/02, CP-06/07: `IN PROGRESS`. Bounded handshake admission, endpoint freshness и pre-payload connect fallback реализованы в обеих платформах. Физическая LAN matrix остаётся `NOT TESTED`.
- 2026-09-09, План: очереди разделены на Windows-only, Android-only и common. Windows-only source этапы выполнены, C1/C2 начаты как совместный цикл обеих платформ; C3 release по-прежнему отложен до evidence gates.
- 2026-09-09, E2 / W-STATE-01…04: реализованы ownership dispatch batch, telemetry-only progress, `DELIVERY_UNKNOWN`, правдивые receive/discovery status и TCP regression tests. `./gradlew.bat clean test --no-daemon`: PASS. Ручная JavaFX-проверка пока `NOT TESTED`.
- 2026-09-09, E1 / W-FILE-01…04: реализованы operation-owned staging, no-clobber publication, safe cleanup, containment и receive regression tests. `./gradlew.bat test --no-daemon`: PASS. Ручная проверка пока `NOT TESTED`.
- 2026-09-09, E5 / W-REL-01…04: `IN PROGRESS`. Реализован единый `gradle.properties` metadata source (`version`, AppId, repository, naming), embedding в final JAR/build-info/About/diagnostics, multi-resolution ICO, проверка final app-image EXE, строгая schema 1 manifest/checksum verification и immutable GitHub release workflow без upload overwrite. `W-REL-02` и `W-REL-03` — `DONE`. `./gradlew.bat test verifyGitHubReleasePublicationPolicy --no-daemon`: `PASS`; `./gradlew.bat buildWindowsInstaller --no-daemon`: `PASS` (23 tasks), final EXE: `RT_GROUP_ICON=1`, `RT_ICON=6`, layers 16/32/48/64/128/256, ровно 3 release artifacts. `NOT TESTED`: installed-app/registry read-back, live GitHub published/draft matching/mismatch branches, clean install/upgrade/migration/uninstall (E6). Выпуск и публикация запрещены до E9.
- 2026-09-09, E3 / W-RES-03…05: добавлены verified single-instance handshake в per-user-session loopback scope, no-tray exit fallback, ownership/closure active TCP sockets, bounded UDP socket recovery и bounded retry initial TCP/UDP startup, atomic config publication и сохранение malformed config. Новые tests: `SingleInstanceServiceTest`, `TransferServerLifecycleTest`, `DiscoveryServiceRecoveryTest`, `ConfigServiceTest`; `./gradlew.bat test --no-daemon`: PASS. Ручные Windows lifecycle/network сценарии и fault injection на реальной файловой системе пока `NOT TESTED`.
- 2026-09-09, E4 / W-UX-01…04: начаты compact layout и window-state hardening. Реализованы layout switch по фактической ширине, компактная drop area при очереди, bounded first-run size, normal bounds/maximized persistence, multi-monitor clamp и history с фактическим final filename. `WindowLayoutTest` и полный `./gradlew.bat test --no-daemon`: PASS. Ручная проверка JavaFX на DPI/размерах/RU/EN/keyboard/tray пока `NOT TESTED`.

### Шаблон подробной записи

```markdown
#### YYYY-MM-DD — <ID> — <короткий итог>

- Статус: `IN PROGRESS` / `DONE` / `BLOCKED` / `DEFERRED`.
- Изменённые файлы и коммиты: …
- Решение: …
- Проверки: команда/сценарий → PASS / FAIL / NOT TESTED; что именно проверено.
- Совместимость: revision/vectors/версия Android, если это `CP-*`.
- Остаточные риски и следующий шаг: …
```
