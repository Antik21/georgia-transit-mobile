# Подготовка общей логики для Telegram Mini App

На этом этапе `:shared` получает браузерную цель Kotlin/JS и интерфейс для запуска сессии, выбора города и получения ETA по остановке. Браузерная навигация по маршрутам, Telegram Mini App, бот, Telegram SDK, веб-интерфейс и рендерер карты, публикация и настройка CORS относятся к следующему этапу.

## Реализация

- [x] Создать отдельную рабочую копию и ветку `feature/telegram-web-preparation` от `origin/main` (`e8229be`).
- [x] Зафиксировать границы работ, порядок миграции и проверки в этом плане.
- [x] Описать разделение общей логики, мобильного UI и браузерных адаптеров в [ADR 0019](../adr/0019-browser-ready-shared-client.md).
- [x] Добавить браузерную цель Kotlin/JS в существующий `:shared`. Перенести Compose-экраны, ресурсы, предпросмотры, `Navigation3AppHost` и его Compose-интеграцию Koin в `mobileMain`, общие тесты мобильного UI в `mobileTest`. Сохранить точки входа Android и iOS.
- [x] Оставить домен, BFF-контракты, маппинг, сессию, MVI ViewModel, состояния, действия и эффекты в `commonMain`. Отделить переносимые контракты карты и геолокации от мобильных Compose `expect`. Сохранить общие типизированные пункты назначения и политику переходов. Стеком Android и iOS продолжает управлять Navigation 3.
- [x] Добавить браузерный HTTP-клиент Ktor, реализации `TransitCacheStore`, `SelectedCityStore` и источника `RuntimeBootstrapConfiguration`. Браузерный адаптер обращается к Transit BFF через существующий репозиторий.
- [x] Добавить типизированный JS-интерфейс для запуска сессии, выбора города, наблюдения за остановками и ETA, повторных запросов и освобождения подписок. Настроить генерацию деклараций TypeScript.
- [x] Добавить браузерные тесты для сохранения кэша и выбранного города между экземплярами хранилища, резервного хранения в памяти и сценария «остановка → ETA» с тестовым репозиторием и проверкой освобождения ресурсов.

## Проверка

- [ ] JS. Установить Node.js и Yarn Classic, затем запустить `./gradlew :shared:compileKotlinJs :shared:jsBrowserTest` с Chrome Headless. После успешной сборки библиотеки проверить сгенерированные декларации на минимальном TypeScript-клиенте.
- [ ] Android. Запустить `./gradlew :shared:testAndroidHostTest :androidApp:assembleSandbox :androidApp:assembleProd`. На эмуляторе выполнить `ui-tests/maestro/flows/shell-smoke.yaml` и существующие сценарии выбора маршрута, карты и геолокации.
- [ ] iOS. Запустить `./gradlew :shared:iosSimulatorArm64Test`. Собрать `Sandbox` и `Prod`, подставляя имя схемы в оба аргумента команды `xcodebuild -project iosApp/iosApp.xcodeproj -scheme <scheme> -configuration <scheme> -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build`. На симуляторе выполнить те же проверки оболочки, маршрута, карты и геолокации.

### GitHub Actions

В [прогоне №123](https://github.com/Antik21/georgia-transit-mobile/actions/runs/42898084388) прошли Android host-тесты, lint и сборка Sandbox/Prod, а также тесты на iOS-симуляторе и сборка обеих схем. Проверки Maestro остаются в чек-листе. Kotlin/JS скомпилировался, Chrome Headless выполнил 242 теста, два из которых завершились ошибкой.

### Локальные проверки

| Проверка | Результат |
| --- | --- |
| Статическая проверка | `git diff --check` прошёл. Входные файлы приложений и точки входа нативных хостов сохранены. Содержимое перемещённых ресурсов побайтово совпадает с исходным. |
| Контрактные тесты Android | Четыре изменённых класса проверок ресурсов и исходников запущены напрямую через Kotlin 2.4.10 и JUnit 4.13.2. Все 30 тестов прошли. |
| Навигационная политика на JVM | Текущие `Destination.kt`, `PlatformNavigationKey.kt`, `NavigationTransitionPolicy.kt` и тесты скомпилированы напрямую Kotlin 2.4.10 с плагином сериализации. Все 7 тестов прошли на JUnit 4.13.2. Для проверки использованы ранее собранный `CityId` и кэшированный Android Nav3. |
| Gradle 9.3.1, JS, Android, iOS | Перечисленные задачи блокируются до конфигурации проекта при запуске с `--offline` и временным `GRADLE_USER_HOME`. `FileLockContentionHandler` завершается с `SocketException: Operation not permitted`. Ошибка воспроизводится и на исходной ревизии `e8229be`. |
| Chrome Headless и TypeScript | Браузерные тесты не запустились из-за блокировки Gradle. Для независимой компиляции не хватает JS-артефактов зависимостей в локальном кэше. Декларации TypeScript ещё не проверены компилятором. |
| Android-эмулятор | ADB не смог открыть локальный порт `5037`. Сценарии Maestro не выполнены. |
| iOS, Xcode 27.2 | Обе схемы для generic iOS Simulator завершились до компиляции с кодом `74`. Xcode не смог разрешить `github.com` для Swift-пакета MapLibre. При повторе с локальной копией точной версии пакета SwiftPM завершился из-за запрета записи в `~/Library/Caches/org.swift.swiftpm/manifests/ManifestLoading`. |
| Kotlin/Native и Swift | Kotlin/Native 2.4.10 с плагином сериализации отдельно собрал `Destination.kt` и мобильный `PlatformNavigationKey.kt` в KLIB для `ios_simulator_arm64`. `swiftc -frontend -parse` проверил синтаксис исходников iOS-хоста. Проверка типов Swift и сборка приложения остаются открытыми. |
| iOS Simulator | Подключение к `CoreSimulatorService` завершилось с `Connection refused`. Сценарии Maestro не выполнены. |
