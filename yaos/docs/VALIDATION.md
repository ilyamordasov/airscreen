# Проверка FreeCast TV 0.1.0

Дата сборки: 2026-11-08. Это первая сборка; совместимость с конкретным телевизором YaOS и iPhone пока не подтверждена.

## Доставленный APK

| Поле | Значение |
| --- | --- |
| Файл | `FreeCast-TV-0.1.0.apk` |
| Размер | 14,842,057 байт |
| applicationId | `ru.freecast.tv` |
| Версия | `0.1.0`, versionCode `100` |
| Минимальный Android | 7.0 / API 24 |
| compileSdk / targetSdk | 34 / 34 |
| Native ABI | ARM64 (`arm64-v8a`), ARMv7 (`armeabi-v7a`), `x86_64` |
| Debuggable | Нет |
| Подпись | APK Signature Scheme v2, RSA 3072, отдельный постоянный ключ FreeCast TV |
| Launcher | MAIN + LAUNCHER + LEANBACK_LAUNCHER |

SHA-256 APK:

```text
606a614f5b961e993a4e003ce59be82a3e9eb652b73fa3510a41d7522951cd21
```

SHA-256 сертификата подписи:

```text
f7b5180056f212c59085b6ac0e3e9f92f06411f85ce83bfb8bf29e0af17f373b
```

`apksigner verify --verbose --print-certs` и `zipalign -c -p 4` завершились успешно.
ZIP-контроль целостности APK прошёл. Проверены имя пакета, версия, отсутствие debuggable,
наличие трёх ABI, TV launcher и отсутствие старых растровых баннеров AirTV.

## Выполненные проверки

| Проверка | Результат |
| --- | --- |
| Сборка debug APK | Успешно |
| Сборка подписанного release APK | Успешно |
| Сборка Android instrumentation APK | Успешно; запуск тестов не выполнен |
| JVM unit tests, release | 53 теста, 0 ошибок, 0 пропусков |
| JVM unit tests, debug, до последних исправлений жизненного цикла | 53 теста, 0 ошибок, 0 пропусков |
| Android Lint, release | 0 ошибок, 13 предупреждений |
| HTTP/RTSP parser, native host | 63 regression cases прошли |
| Mirror packet bounds, native host | 177 cases и 10 000 детерминированных случайных пакетов прошли |
| DNS-SD Unicode names, native host | 26 проверок прошли |
| SRP pairing, native host | 18 проверок состояния и границ прошли |
| Архивы исходников OpenSSL/libplist | SHA-256 и размеры совпали с `third_party_sources/SOURCES.json` |
| XML и VectorDrawable | Android resource compile/link прошли в общей сборке |

Результаты JVM и Lint, вывод проверки подписи и манифеста находятся в `docs/verification/`.
Предупреждения Lint: DataExtractionRules, DiscouragedApi, HardcodedText, HardwareIds, NestedWeights, Overdraw, UnusedResources, VectorPath.
Они не были скрыты baseline-файлом или отключением abortOnError. Фиксированная
ориентация и Android ID используются для телевизионного интерфейса и локального
стабильного AirPlay-идентификатора соответственно.

Native host tests выполнялись с AddressSanitizer и UndefinedBehaviorSanitizer:

```bash
ASAN_OPTIONS=detect_leaks=0 bash app/src/main/cpp/airplay/tests/run_host_tests.sh
```

LeakSanitizer в этой среде отключён из-за ограничения ptrace; проверка утечек не заявляется.
Этот набор проверяет указанные парсеры и границы, а не все возможные сетевые входы.

## Что ещё не проверено

- Установка и запуск на настоящем телевизоре YaOS.
- Полное подключение, обмен ключами, изображение и AAC-звук с настоящего iPhone.
- Реальные mDNS-объявления через домашний маршрутизатор.
- Работа конкретных аппаратных H.264/H.265/AAC-декодеров, поворот телефона и долгий сеанс.
- Внешний вид на работающем телевизоре и полный проход пультом по диалогам.
- Декодирование после FLUSH и независимость активной сессии от закрытия discovery socket
  исправлены по коду; их поведение на Android ещё требуется проверить.

Headless Android 34 x86_64 emulator без аппаратного ускорения за 300 секунд не достиг
`sys.boot_completed=1`. Процесс остановлен. Instrumentation tests и снимки работающего
интерфейса не получены; успешная компиляция test APK не считается выполнением тестов.

## Границы реализации

Основной путь — «Пункт управления → Повтор экрана» с AAC-звуком. Отдельный HLS handoff
из кнопки AirPlay внутри видеоприложений и ALAC audio-only не реализованы. Защищённое
DRM-видео не декодируется. Отдельного PIN и автозапуска после включения ТВ нет.

Рекомендуемая первая проверка: домашний экран iPhone → поворот телефона → собственное
видео из «Фото» → остановка повтора → повторное подключение. При сбое откройте
«Диагностика» и сохраните фото экрана вместе с моделью ТВ и версией iOS.

## Воспроизведение сборки

Использованы JDK 17.0.21+9, Gradle 8.7, AGP 8.4.2, Kotlin 1.9.24, SDK/Build Tools 34,
NDK 26.1.10909125 и CMake 3.22.1. Команды:

```bash
./gradlew testReleaseUnitTest assembleRelease assembleDebug assembleDebugAndroidTest lintRelease
./gradlew connectedDebugAndroidTest  # требует работающий Android; здесь не выполнено
```

Для release нужны переменные подписи из README. Приватный ключ предоставляется отдельным
резервным архивом и не входит в исходники. В среде сборки использован отдельный кэш Gradle,
поскольку синхронизируемый каталог создавал неполные transform workspaces.

Статические libcrypto/libplist взяты из зафиксированного AirTV; их соответствующие
исходные архивы и скрипт пересборки включены. Повторная компиляция самих статических
зависимостей в рамках этой проверки не выполнялась. Побайтовая воспроизводимость
всего APK не проверялась.
