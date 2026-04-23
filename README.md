# MAP

MAP = **Mobile Access Proxy**.

Android-приложение с локальными `SOCKS5/HTTP` прокси и SSTP-туннелем для каскадирования трафика к удаленному upstream-прокси.

English documentation: [`README_EN.md`](README_EN.md)

Минимальная версия Android: **5.0 (API 21)**

## Основное

- Android: `minSdk 21`, `targetSdk 34`
- Локальные прокси: `SOCKS5`, `HTTP`
- SSTP pipeline: `TLS -> HTTPS establish -> SSTP control -> PPP -> relay`
- Data plane: `IPv4`
- Маршрутизация: в туннель прокидывается только route до удаленного upstream-прокси (не full-tunnel)
- Основной трафик устройства по умолчанию не направляется в SSTP-туннель

## Что поддерживается

- Локальные прокси с auth и upstream-цепочкой.
- SSTP/PPP negotiation с подъемом TUN и маршрутов до upstream.
- Runtime-статусы прокси/SSTP/remote healthcheck.
- Диагностика SSTP и каскада через логи.
- In-app update через GitHub Releases (проверка версии, загрузка APK, валидация, установка).

## Схема работы

```text
Клиентское приложение / браузер / Telegram
            |
            v
Локальный MAP (SOCKS5/HTTP на устройстве)
            |
            v
SSTP transport:
TLS -> HTTPS establish -> SSTP control -> PPP -> TUN relay
            |
            v
Remote upstream proxy (SOCKS5/HTTP)
            |
            v
Интернет
```

Коротко по цепочке:
- Приложения на устройстве ходят в локальный `SOCKS5/HTTP` прокси MAP.
- MAP поднимает SSTP-сессию, проходит PPP-negotiation и создает TUN relay.
- Трафик каскадируется к удаленному upstream-прокси и далее выходит в сеть.

## Преимущества подхода

- MAP не работает как full-tunnel VPN: в SSTP-туннель направляется только маршрут до удаленного upstream-прокси.
- Основной трафик устройства не перенаправляется в туннель по умолчанию, что уменьшает поверхность атаки и снижает побочные риски для других приложений.
- За SSTP-сервером должен быть доступен удаленный upstream-прокси (`SOCKS5` и/или `HTTP`) — это обязательное условие для каскадного режима.
- Если upstream за SSTP отсутствует, архитектурный смысл приложения теряется: локальный прокси не сможет выполнять целевое каскадирование через удаленную точку.
- На локальном прокси используется авторизация, и удаленный upstream также должен быть защищен авторизацией.
- Такая каскадная схема (локальный auth + удаленный auth + ограниченный route) усложняет несанкционированное использование прокси и туннеля со стороны вредоносных приложений.
- Ограничение маршрута только до upstream снижает вероятность утечки сетевой информации и в целом минимизирует риск компрометации адреса удаленного сервера.

Важно: это не абсолютная защита, но существенное усиление практической безопасности при корректной настройке и эксплуатации.

## Сборка и тесты

### Вариант 1 (рекомендуется): через Makefile и Docker

Требования:
- Docker Desktop (или совместимый Docker Engine)
- доступ к интернету для загрузки зависимостей при первом запуске

Команды из корня проекта:

- `make test` — запустить unit-тесты
- `make build-debug` — собрать debug APK
- `make build-release` — собрать release APK

Где искать артефакты:
- `cache/build/app-debug.apk`
- `cache/build/app-release.apk`

### Вариант 2: локально через Gradle

Требования:
- JDK 17
- Android SDK / Build Tools

Команды:
- `./gradlew :app:testDebugUnitTest` — unit-тесты
- `./gradlew :app:assembleDebug` — debug APK
- `./gradlew :app:assembleRelease` — release APK

Где искать артефакты:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

