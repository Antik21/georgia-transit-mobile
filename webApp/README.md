# Geo Transit для Telegram

Первый сценарий Mini App — выбор города, карта с остановками и время прибытия. Веб-интерфейс использует существующие Kotlin ViewModel и Transit BFF. Тот же экран можно открыть в обычном браузере.

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

Smoke использует Chromium и тестовые данные, включая заглушку Telegram и стиль карты без публичных тайлов.

## Подключение к Telegram

Раздайте `webApp/dist` с HTTPS-хоста и проксируйте `/v1/` на Transit BFF по тому же origin. Сохраните пути `/v1/map/style.json` и `/v1/map/tiles/...` при проксировании. Укажите HTTPS URL Mini App в настройках бота через BotFather. Открытие внутри Telegram требует настройки бота и публичного хоста.
