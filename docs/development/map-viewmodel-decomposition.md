# План декомпозиции MapViewModel

Статус: реализовано для `MapViewModel`; дальнейшая декомпозиция остальных ранее крупных файлов остаётся отдельной задачей. Архитектурное решение зафиксировано в [ADR 0018](../adr/0018-map-presentation-mvi.md). Исходный `MapViewModel.kt` содержал 2073 строки; после рефакторинга — 500. Сохранены один entry-scoped `MapViewModel` и один Orbit `Container<ViewState, SideEffect>`. Выбор MVI и границ ответственности сделан исходя из сложности текущего потока данных и принципов SOLID; уже существующие классы не считались утверждённым шаблоном.

## Исходные ответственности и выбранные границы

| Область в `MapViewModel.kt` | Примерный участок | Целевой владелец |
| --- | --- | --- |
| Сборка состояния при изменении города, выбора маршрутов и location; `dispatchAction` | 61–255 | `MapViewModel` как MVI-вход; чистые `MapInputReducer` и `MapSessionProjection` |
| Камера, viewport, нормализация координат/insets, команды location | 316–384, 1015–1095, 1867–1958 | `MapCameraController` и `MapViewportPolicy` в presentation; команды остаются эффектами VM |
| Nearby и остановки вокруг пользователя, восстановление city/query, выбранные остановки маршрутов | 257–280, 295–315, 430–575, 1039–1280 | `MapStopsHandler` с отдельными request keys для viewport, location и выбранных маршрутов |
| Маршрутная геометрия | 406–429, 576–602 | `MapGeometryHandler`; оценить, удовлетворяет ли его контракт существующий `RouteGeometryCoordinator` |
| Polling транспорта, кадры, бейджи, интерполяция и доступность | 281–294, 603–993 | `MapVehiclesHandler` + чистые reducer/interpolator/marker mapper; проверить и при необходимости переделать существующие вычислители |
| Лист прибытия, polling, refresh названий маршрутов, преобразование строк | 1309–1810 | `StopArrivalsHandler` + чистый `StopArrivalsUiMapper` |
| Объединение остановок, видимости, подсветки и ревизий источников | 322–373, 1811–1865 | `MapStateReducer`/`MapRenderProjector` с явными входными данными; счётчик ревизии у владельца источника |

Указанные диапазоны ориентировочные: методы `MapViewModel` сейчас перемежаются. Существующие `RouteGeometryCoordinator.kt` (635 строк), `VehicleRealtimeCoordinator.kt` (718 строк) и `BffTransitRepository.kt` (543 строки) также превышают новый предел. При работе в этих файлах разделять их по ответственности; не переносить их содержимое целиком в новые файлы без разделения ролей. Фактические владельцы перечислены ниже; указанные в таблице названия были предварительными.

## Целевой контракт

- `MapViewModel` принимает типизированный `Action`, слушает `TransitSession` и `LocationSession`, передаёт обработчикам контекст (город/возможности, выбранные `RouteId`, locale, viewport, видимость/lifecycle), публикует один `ViewState` и `SideEffect`. Навигация и platform location commands остаются здесь. Orbit `intent` сериализует применение результатов.
- Асинхронный обработчик создаётся на срок жизни Map entry через конструктор/DI, владеет только своими jobs, поколением запросов, блокировками и временным кэшем. Он возвращает результат своего сценария (`NearbyResult`, `ArrivalsResult`, `VehiclesResult`, `GeometryResult`) с ключом актуальности. VM проверяет ключ и передаёт результат чистому reducer соответствующей области; обработчик не знает `ContainerHost`, `ViewState`, Compose и Koin. Один обработчик не вызывает другой: VM передаёт необходимые immutable входы. Долгие запросы и polling выполняются вне Orbit `intent`, чтобы не блокировать его обработку.
- Небольшие `NearbyReducer`, `ArrivalsReducer`, `VehiclesReducer` вычисляют части неизменяемого состояния; чистый `MapRenderProjector` собирает render state из принятых частей. Общий `MapStateReducer` допустим только как тонкая композиция этих функций, без огромного `when` на все события карты. Не использовать один универсальный payload, callback bag или дублирующие mutable копии UI state. Для атомарной смены города/выбора VM сначала применяет reset для затронутых областей, затем публикует согласованное состояние.
- Чистые `Projector`/`Mapper`/`Policy` функции принимают значения и возвращают значения без I/O и скрытого состояния. Правила транзита, не зависящие от UI (например, выбор пригодных данных или доменная валидация), при фактическом обнаружении перемещаются в `domain` interactor/policy. Репозиторий и BFF DTO/cache остаются в `data`; новый сетевой слой не нужен.
- Для взаимозависимых результатов VM собирает один render projection после применения входов. При смене города/выбора сначала инвалидируются соответствующие поколения и очищаются устаревшие данные, затем публикуется новое состояние. Старый ответ даже от некорректно отменяемого репозитория не может попасть в новый город, locale или selection.

## Порядок миграции

1. **Зафиксировать поведение.** Запустить узкие существующие тесты `MapViewModelTest`, `MapNearbyViewModelTest`, `StopArrivalsMapViewModelTest`, `MapVehicleRealtimeViewModelTest`, `RouteGeometryMapViewModelTest` и coordinator tests; отметить текущие падения. Добавить только недостающие проверки пересечений: смена города/locale/selection во время запроса, background/foreground, capability kill switch, поздний некооперативный ответ, смена source revision и отсутствие старых маркеров.
2. **Ввести MVI-контракты и чистые вычисления.** Определить закрытые варианты результатов по областям и ключи контекста (`cityId`, locale, selection revision, generation по области), небольшие чистые reducers, viewport/camera policy, route-selection projection и sheet/render/marker mapping. Сохранить публичные `Action`, `ViewState`, эффекты и значения ревизий. Тестировать reducers на последовательностях результатов и отбрасывании устаревших результатов.
3. **Выделить прибытия.** Перенести polling, mutex, generation и route-label refresh в `StopArrivalsHandler`; результат проходит через reducer. В VM остаются маршрутизация open/dismiss/retry и публикация эффекта. Сохранить текущие интервалы, stale/unavailable поведение и fail-closed capability.
4. **Выделить остановки.** Разделить viewport, user-location и selected-route загрузки на небольшие обработчики с независимыми keys/generations; `MapStopsHandler` вводить только если он упрощает согласование их результатов. Повторную доменную проверку города при `CityNotFound` оформить отдельным interactor, если она остаётся общей бизнес-процедурой, а не привязывать к UI state. Сохранить выбранную остановку при требуемых переходах, дедупликацию, viewport debounce, recovery и stale-response guards. Затем подключить чистый `MapRenderProjector`.
5. **Выделить транспорт и проверить геометрию.** `MapVehiclesHandler` забирает polling, frame ticker, locks, path/motion cache и revision ownership. Существующие `VehicleRealtimeReducer`, interpolation policy и `RouteGeometryCoordinator` сохранить только там, где они удовлетворяют типизированным входам, единоличному ownership и ограничению 500 строк; иначе разделить/заменить. Сохранить ограничение числа маркеров, частоту кадров, identity continuity и доступность.
6. **Упростить VM и создание зависимостей.** Свести `onCreate` к обновлению input context, сбору результатов и одному месту применения reducers/эффектов. Обработчики создаются на срок жизни VM из переданных ей зависимостей; тестовые фабрики инжектируют управляемые clock/ticker и fake repository. Удалить дублируемое mutable state. Проверить `wc -l` всех затронутых Kotlin-файлов, целевой `MapViewModel.kt` — не больше 500 строк.

## Проверка завершения

- Публичные action/state/effect контракты и Navigation 3 маршруты сохраняются; нет нового Orbit контейнера, навигационного стека, сетевого клиента или доступа presentation к data implementation.
- Каждый job/lock/generation имеет одного владельца. Отмена и поколение защищают от позднего результата; city/locale/selection/capability переходы не показывают данные предыдущего контекста.
- Проходят указанные common tests, `:shared:testAndroidHostTest`, `:androidApp:assembleSandbox`, `:androidApp:assembleProd`. Для изменённого UI потока проверить `ui-tests/maestro/flows/shell-smoke.yaml` на Android emulator и iOS simulator согласно [правилам проверки](../../.agents/docs/07-testing-verification.md); если хост недоступен, явно записать пробел проверки.
- PR с реализацией показывает таблицу «старый владелец → новый владелец», список изменённых контрактов и точный список файлов, всё ещё превышающих 500 строк.

## Реализованное разделение

| Исходная ответственность VM | Новый владелец |
| --- | --- |
| Каталог выбранного города и маршрутов, флаги переходов | `MapSessionSelection` и чистый `MapSessionStateReducer` |
| Камера, выбор остановки, переходы сброса и сборка кадра | `MapCameraController`, `MapStopSelection`, `MapStopTransitions`, `MapRenderProjector` |
| Nearby viewport, восстановление после ошибки, supplemental stops | `MapNearbyStops`, `MapNearbyWorkflow`, `NearbyStopsLoader`, `MapSupplementalStops`, `MapStopsProjector` |
| Polling транспорта и его отображение | `MapVehicleFeature`, `MapVehiclePoller`, `MapVehiclePresentation` |
| Открытая остановка, polling прибытия и sheet UI | `StopArrivalsFeature`, `StopArrivalsPoller`, `StopArrivalsPresentation`, `StopArrivalsUiMapper` |
| Location commands и viewport normalization | `MapLocationActions`, `MapViewportPolicy` |

`MapViewModel` остаётся единственным владельцем Orbit container, сериализует применение результатов, маршрутизирует `Action` и публикует `SideEffect`. Публичные `Action`, `ViewState` и navigation-контракты сохранены. Превышающие лимит файлы вне этой декомпозиции (`RouteGeometryCoordinator`, `VehicleRealtimeCoordinator`, `BffTransitRepository`) остаются в списке отдельных задач, поскольку их изменение требует проверки собственных контрактов и границ.

## Проверено в текущем рабочем дереве

- `:shared:testAndroidHostTest` и `:shared:compileKotlinIosSimulatorArm64` — успешно.
- `:androidApp:assembleSandbox` и `:androidApp:assembleProd` — успешно.
- iOS Sandbox scheme собрана для iPhone 18 Pro Simulator (iOS 27.0); приложение установлено, запущено, карта отрисовалась. Полный `shell-smoke.yaml` не завершился: Maestro завис на шаге запуска, проверка прервана.
- Android UI smoke не запускался: подключённого Android emulator нет.
- `git diff --check` — успешно. Все новые Kotlin-файлы и `MapViewModel.kt` не превышают 500 строк.
