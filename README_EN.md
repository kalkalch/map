# MAP

MAP = **Mobile Access Proxy**.

Android application with local `SOCKS5/HTTP` proxies and an SSTP tunnel for cascading traffic to a remote upstream proxy.

Основная документация (RU): [`README.md`](README.md)

Minimum Android version: **5.0 (API 21)**

## Overview

- Android: `minSdk 21`, `targetSdk 34`
- Local proxies: `SOCKS5`, `HTTP`
- SSTP pipeline: `TLS -> HTTPS establish -> SSTP control -> PPP -> relay`
- Data plane: `IPv4`
- Routing: only the route to the remote upstream proxy is pushed into the tunnel (not full-tunnel)
- Device-wide traffic is not redirected to the SSTP tunnel by default

## Supported Features

- Local proxies with authentication and upstream chain support.
- SSTP/PPP negotiation with TUN setup and upstream route management.
- Runtime statuses for proxy, SSTP, and remote healthcheck.
- SSTP/cascade diagnostics via logs.
- In-app update via GitHub Releases (version check, APK download, validation, install).

## How It Works

```text
Client app / browser / Telegram
            |
            v
Local MAP (SOCKS5/HTTP on device)
            |
            v
SSTP transport:
TLS -> HTTPS establish -> SSTP control -> PPP -> TUN relay
            |
            v
Remote upstream proxy (SOCKS5/HTTP)
            |
            v
Internet
```

In short:
- Apps on the device connect to local MAP `SOCKS5/HTTP` proxy.
- MAP establishes SSTP, completes PPP negotiation, and creates TUN relay.
- Traffic is cascaded to remote upstream proxy and then to the Internet.

## Security and Design Benefits

- MAP is not a full-tunnel VPN: only the route to the remote upstream proxy is tunneled.
- Default device traffic is not redirected, which reduces attack surface and side effects for unrelated apps.
- A remote upstream proxy (`SOCKS5` and/or `HTTP`) behind SSTP is required for cascade mode.
- Without upstream behind SSTP, the architecture loses its purpose: local proxy cannot perform intended cascading.
- Local proxy must use authentication, and remote upstream should also enforce authentication.
- This layered setup (local auth + remote auth + limited route) makes unauthorized use of proxy/tunnel harder.
- Limiting routing to upstream reduces exposure of network paths and helps minimize remote server address compromise risk.

Important: this is not absolute protection, but a practical hardening approach when configured correctly.

## Build and Test

### Option 1 (recommended): Makefile + Docker

Requirements:
- Docker Desktop (or compatible Docker Engine)
- Internet access for initial dependency download

Commands from repository root:
- `make test` — run unit tests
- `make build-debug` — build debug APK
- `make build-release` — build release APK

Artifacts:
- `cache/build/app-debug.apk`
- `cache/build/app-release.apk`

### Option 2: local Gradle

Requirements:
- JDK 17
- Android SDK / Build Tools

Commands:
- `./gradlew :app:testDebugUnitTest` — unit tests
- `./gradlew :app:assembleDebug` — debug APK
- `./gradlew :app:assembleRelease` — release APK

Artifacts:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`
