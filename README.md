# Orion

[![Android CI](https://github.com/michael-031/Orion/actions/workflows/ci.yml/badge.svg)](https://github.com/michael-031/Orion/actions/workflows/ci.yml)
[![GitHub Release](https://img.shields.io/github/v/release/michael-031/Orion)](https://github.com/michael-031/Orion/releases)
[![Android API 26+](https://img.shields.io/badge/Android-API%2026%2B-34D399)](https://developer.android.com/about/versions/oreo)

Minimalist Android Wi-Fi Direct hotspot and high-throughput local HTTP/HTTPS proxy server for sharing mobile data with laptops.

## Features

- **Wi-Fi Direct Group Owner**: Broadcasts a `DIRECT-…` Wi-Fi Direct network without requiring traditional carrier tethering or root access.
- **2.4 GHz & 5 GHz Band Switching**: Choose between wider-range 2.4 GHz and max-throughput 5 GHz bands on Android 10+, with automatic fallback to 2.4 GHz if 5 GHz P2P channels are restricted.
- **Customizable Hotspot Credentials**: Configure a custom 2-character tag, network name (`DIRECT-XX-...`), and WPA2 passphrase persisted locally with Jetpack DataStore, or switch back to auto-generated credentials.
- **High-Throughput Local HTTP/HTTPS Proxy**: Multithreaded HTTP and HTTPS (`CONNECT` tunnel) proxy listening on port `8282`, binding upstream sockets and DNS lookups directly to the active cellular `Network` via `ConnectivityManager.NetworkCallback`.
- **Persistent Foreground Service**: Runs a `connectedDevice` foreground service holding a `PARTIAL_WAKE_LOCK` and low-latency `WifiLock` for uninterrupted screen-off streaming.
- **Quick Settings Tile**: Start or stop Orion directly from Android's Quick Settings shade.
- **Real-Time Telemetry UI**: Dark Jetpack Compose dashboard showing active connections, TX/RX speed, total transferred bytes, frequency band, and session uptime.

## Installation

1. Download the latest APK from [GitHub Releases](https://github.com/michael-031/Orion/releases).
2. Install it on a **physical Android device** running **Android 8.0 (API 26)** or higher.
   - Custom SSID/passphrase configuration and 2.4 / 5 GHz band selection require **Android 10 (API 29)** or higher.

## Client Proxy Setup (Windows / Laptop)

1. Open **Orion** on your Android device, grant the requested permissions, and tap **Start Orion**.
2. On your laptop, connect to the `DIRECT-…` Wi-Fi network using the passphrase shown in Orion.
3. Configure your system's manual HTTP/HTTPS proxy:
   - **Proxy IP**: `192.168.49.1` (or the Group Owner IP displayed in Orion)
   - **Port**: `8282`

## Permissions

| Permission | Purpose |
| --- | --- |
| `NEARBY_WIFI_DEVICES` *(Android 13+)* | Create and manage the Wi-Fi Direct group without requiring location access. |
| `ACCESS_FINE_LOCATION` *(Android 8–12)* | Required by Android's `WifiP2pManager` APIs on API 26–32 to create a Wi-Fi Direct group and read credentials. |
| `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keep the proxy server and Wi-Fi Direct group active while the screen is off. |
| `WAKE_LOCK` | Prevent CPU sleep from throttling or dropping active proxy tunnels. |
| `POST_NOTIFICATIONS` *(Android 13+)* | Display the persistent service status notification with a quick Stop action. |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | Route proxied traffic over mobile data and control Wi-Fi Direct / low-latency Wi-Fi locks. |

## Building from Source

### Prerequisites

- **JDK 17+** (required by Android Gradle Plugin 9.1.1 and Gradle 9.3.1)
- **Android SDK** with **Android 16 minor platform (`platforms;android-36.1`)**
- [Android Studio](https://developer.android.com/studio) (optional, recommended for device deployment)

### Android Studio

1. Clone the repository and open it in **Android Studio**.
2. Allow Gradle to sync with the included Gradle Wrapper (`9.3.1`).
3. Select a physical Android device and click **Run**.

### Command Line

On Linux / macOS:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease
```

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

## Release Signing & Publishing

Release builds enable R8 code shrinking (`isMinifyEnabled = true`) and resource shrinking (`isShrinkResources = true`).

### Local Signed Release

Create a gitignored `keystore.properties` file in the repository root:

```properties
storeFile=path/to/release.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

Then build the signed APK or Android App Bundle:

```bash
./gradlew assembleRelease bundleRelease
```

*(If `keystore.properties` and signing environment variables are omitted, `./gradlew assembleRelease` produces an unsigned release APK.)*

### Automated GitHub Releases

Pushing a version tag (`v*`) triggers [`.github/workflows/release.yml`](.github/workflows/release.yml), which runs unit tests, builds release artifacts with SHA-256 checksums, and creates a GitHub Release.

To publish **signed** release artifacts automatically, add these repository secrets in GitHub (**Settings → Secrets and variables → Actions**):

- `KEYSTORE_BASE64`: Base64-encoded contents of your `.jks` / `.keystore` file
- `KEYSTORE_PASSWORD`: Keystore password
- `KEY_ALIAS`: Signing key alias
- `KEY_PASSWORD`: Signing key password

Then push a release tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```
