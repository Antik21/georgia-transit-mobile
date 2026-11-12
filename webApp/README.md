# Geo Transit для Telegram

В Mini App доступны карта с остановками и временем прибытия, выбор города, поиск, выбор и отображение маршрутов, а также построение маршрута A→B по длинному нажатию на карту. Веб-интерфейс использует существующие Kotlin ViewModel и Transit BFF. Тот же экран можно открыть в обычном браузере.

## Локальный запуск

Понадобятся JDK 17, Android SDK для конфигурации Gradle, Node.js 22.12+ и Yarn Classic. Из корня репозитория:

```sh
./gradlew :shared:jsProductionLibraryCompileSync :kotlinNpmInstall
npm --prefix webApp ci
npm --prefix webApp run dev
```

Откройте `http://127.0.0.1:5173`. Vite проксирует `/v1/` на действующий BFF. Для локального BFF укажите `TRANSIT_BFF_ORIGIN` в `webApp/.env.local`. После изменений Kotlin повторите команду Gradle.

## Сборка и проверка

```sh
npm --prefix webApp run build
CHROME_BIN=/path/to/google-chrome npm --prefix webApp run smoke
```

Smoke использует Chromium и тестовые данные, включая заглушку Telegram, тайл карты и геометрию маршрута.

После успешного задания Browser в PR можно скачать готовый `webApp/dist` из артефакта `georgia-transit-web` и открыть его через Vite Preview с тем же прокси `/v1/`.

## Подключение к Telegram

Продовый клиент раздаёт статический сайт Render `antik21-georgia-transit-web` с прокси `/v1/` на Transit BFF ([ADR 0021](../docs/adr/0021-telegram-web-hosting.md)). Его бандл публикует CI в ветку `web-dist` после всех проверок на `main`; вручную туда ничего не коммитится. Выкат, проверка прокси и настройка бота в BotFather описаны в [render-deployment.md](../docs/development/render-deployment.md#telegram-web-client).

Для другого хоста условия те же: HTTPS, `webApp/dist` и `/v1/` на одном origin, пути `/v1/map/style.json` и `/v1/map/tiles/...` при проксировании не меняются.
