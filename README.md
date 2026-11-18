# Bluechat-KMP

Peer-to-peer chat over Bluetooth Low Energy, without servers, accounts or internet. Built
with Kotlin Multiplatform and Compose Multiplatform for Android and iOS.

A device can act in either BLE role:

- **Central (client):** scans for nearby Bluechat devices, connects and chats one-to-one.
- **Peripheral (server):** advertises a GATT service, accepts several clients and chats with
  each one or with all of them (group/broadcast chat).

On top of the transport the app is a regular chat app: a conversation list as the home
screen, contacts tied to a stable per-install identity (not the MAC address), message
history persisted in Room, delivery and read receipts, typing indicator, an outbox with
retry, auto-reconnect, notifications, and an Android foreground service that keeps the
session alive in the background.

## Status

| Area | Android | iOS |
|---|---|---|
| Central role (scan, connect, chat) | Verified on two phones | Compiles, not verified on a device |
| Peripheral role (GATT server) | Verified on two phones | `CBPeripheralManager` implementation, not verified on a device |
| Background operation | Foreground service (E1) | Not yet (BLE background modes are task F2) |

Android is the primary platform and the only one tested on real devices. See
[`doc/device-test-findings.md`](doc/device-test-findings.md) for what was tested and how.

## Prerequisites

- **A JDK 17 toolchain for the kable build** (`jvmToolchain(17)`). Android Studio's bundled
  JBR 21 runs Gradle fine but doesn't satisfy this. The kable setup below makes Gradle
  download JDK 17 itself.
- **Android SDK** with compileSdk 36. Put its path in `local.properties` (`sdk.dir=...`), or
  open the project once in Android Studio, which writes the file for you.
- **Xcode** for the iOS app.
- **kable** checked out next to this repo, see below.

### kable (required, sibling checkout)

The BLE transport is [kable](https://github.com/JuulLabs/kable), included as a Gradle
**composite build** (`includeBuild("../kable")` in `settings.gradle.kts`) instead of as a
Maven dependency. The project needs `AndroidPeripheral.requestMtu` and
`maximumWriteValueLengthForType` for MTU negotiation.

Use commit **`dbb7599`** (`dbb75996baf92fe4642f10b9a4518cf4ab572b96`, tag `0.42.0` plus two
maintenance commits). It matches this project's toolchain (Gradle 9.2.1, Kotlin 2.2.21).
Later tags need Gradle 9.6+ and AGP 9, and the build fails with
*"Minimum supported Gradle version is 9.6.0"*.

```bash
cd ..                       # the directory that contains bluechat-kmp
git clone https://github.com/JuulLabs/kable.git
cd kable && git checkout dbb75996baf92fe4642f10b9a4518cf4ab572b96
```

Then add Gradle's toolchain resolver to `../kable/settings.gradle.kts`, after the
`pluginManagement { … }` block. Gradle then downloads JDK 17 into `~/.gradle/jdks` on the
first build:

```kotlin
// Local (bluechat-kmp): lets Gradle download the JDK 17 toolchain kable requires.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
```

This is an uncommitted local edit on a detached `HEAD`. It survives as long as you don't
switch kable's checkout. Alternatively, install any JDK 17 (e.g. Temurin via IntelliJ →
*Download JDK*), and Gradle will detect it without the edit.

## Build and run

```bash
./gradlew :composeApp:assembleDebug                 # Android APK
./gradlew :composeApp:installDebug                  # install on a connected device
./gradlew :shared:testDebugUnitTest                 # unit tests (commonTest, run on the JVM)
./gradlew :shared:compileKotlinIosSimulatorArm64    # check that the iOS code compiles
```

**iOS:** open `iosApp/iosApp.xcodeproj` in Xcode and run it. A build phase calls
`./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`, which builds the framework.

**Testing BLE** needs two physical devices, because emulators and simulators have no
Bluetooth. Start **Server Mode** on one phone, then use **New chat** on the other to scan and
connect.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Minimum supported Gradle version is 9.6.0` | `../kable` is on a newer commit. Check out `dbb7599`. |
| `Cannot find a Java installation ... languageVersion=17` | The foojay snippet is missing from `../kable/settings.gradle.kts`. See the kable setup. |
| Gradle cannot download dependencies (`No route to host`) but `curl` can | The JVM prefers IPv6. Add `systemProp.java.net.preferIPv4Stack=true` to `~/.gradle/gradle.properties`. |
| Scan finds nothing on Android ≤ 11 | Location services must be switched on for BLE scanning below API 31. |
| Connect fails with "service not found" between paired phones | Android serves a stale GATT cache for bonded devices. Unpair the phones. |
| App crashes after switching branches | The database is wiped on a schema change, but a mismatched install can still break. Run `adb shell pm clear com.bluechat`. |

## Project layout

```
composeApp/   Android/iOS app shell: App.kt (NavHost), MainActivity, BluechatApp,
              foreground service (Android), MainViewController + KoinInit (iOS)
shared/       all real code, in commonMain unless it is platform-specific
  data/ble/        kable central controller, Android/iOS GATT servers, GATT UUIDs, MTU
  data/protocol/   wire format: Frame/Reassembler (chunking), Envelope/ProtocolCodec (JSON)
  data/db/         Room KMP database, DAOs, entities
  data/repository/ repository implementations
  domain/          models, repository interfaces, IncomingMessageCoordinator, BleSessionMonitor
  presentation/    screens, ViewModels, UI state, shared components
  di/              Koin modules (shared + per-platform)
iosApp/       Xcode project
doc/          design and working notes
```

## Documentation

| Doc | What it covers |
|---|---|
| [`doc/roadmap.md`](doc/roadmap.md) | **Start here for status.** Phases A–F with status per task, deferred items, dated decision log |
| [`doc/device-test-findings.md`](doc/device-test-findings.md) | Two-phone test report: each defect with root cause, fix and re-verification, plus platform pitfalls |
| [`doc/plan.md`](doc/plan.md) | Architecture reference (layers, routes, wire protocol, GATT profile, database, platform boundaries) plus the Phase 1–8 build history |
| [`CLAUDE.md`](CLAUDE.md) | Working rules, architecture pointers and traps, written for AI coding assistants but useful to anyone |

## Tech stack

Kotlin 2.2.21 · Compose Multiplatform 1.10.1 (Material 3) · kable (composite build) ·
Room KMP 2.7.1 + bundled SQLite · Koin 4.1.0 · Navigation Compose (KMP) · kotlinx.serialization ·
kotlinx.coroutines · Gradle 9.2.1 · AGP 8.12.2 · minSdk 24, targetSdk 35, compileSdk 36.
