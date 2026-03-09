# Bluechat-KMP Project Plan

## Overview

Bluechat-KMP is a Kotlin Multiplatform BLE (Bluetooth Low Energy) chat application that enables peer-to-peer messaging between devices. It supports both **Central** (client) and **Peripheral** (server) roles, allowing devices to either scan for and connect to other devices, or advertise themselves and accept incoming connections.

## Context

KMP project that replicates and extends the BluetoothLEChat BLE messaging features using the local kable fork as the BLE transport layer. The app targets Android + iOS with shared Compose Multiplatform UI, Room KMP for persistence, Koin for DI, and Jetpack Navigation Multiplatform.

**Blueprint projects:**
- `BluetoothLEChat` - BLE features (GATT profile, central/peripheral roles, scanning, advertising, chat over BLE)
- `BluetoothChat` / `BlueChat` - Compose UI patterns (DeviceScreen, ChatScreen, ChatBubble, ViewModel+StateFlow)
- `kable` (local fork) - KMP BLE library (Scanner, Peripheral, observe/write APIs)

**Key features:**
- **Central Mode**: Scan, connect, and chat with BLE peripherals
- **Peripheral Mode**: Advertise as a GATT server and accept multiple client connections
- **Message Persistence**: SQLite database stores chat history per device
- **Delivery Status Tracking**: Messages show Sending/Sent/Failed states
- **Broadcast Chat**: Send messages to all connected clients simultaneously
- **Notifications**: Platform-specific message notifications
- **Settings**: Customizable display name and theme preferences

**Decisions:**
- Kable integration: **Composite build** (`includeBuild("../kable")`)
- BLE scope: **Both Central and Peripheral roles** fully implemented
- UI framework: **Material 3** with Compose Multiplatform
- Database factory: **Koin platform modules** (not expect/actual, since Android needs Context)
- Timestamps: **kotlin.time.Clock.System** (stdlib, no kotlinx-datetime needed)

---

## Project Structure (Implemented)

```
Bluechat-KMP/
├── settings.gradle.kts          # includeBuild("../kable")
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── composeApp/
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/kotlin/com/bluechat/
│       │   └── App.kt           # NavHost with all routes
│       ├── androidMain/
│       │   ├── AndroidManifest.xml
│       │   ├── res/values/styles.xml
│       │   └── kotlin/com/bluechat/
│       │       ├── MainActivity.kt      # Permissions + setContent
│       │       └── BluechatApp.kt       # Application + startKoin
│       └── iosMain/kotlin/com/bluechat/
│           ├── MainViewController.kt
│           └── KoinInit.kt
├── shared/
│   ├── build.gradle.kts
│   ├── schemas/                  # Room auto-generated (v1, v2)
│   └── src/
│       ├── commonMain/kotlin/com/bluechat/
│       │   ├── domain/
│       │   │   ├── model/
│       │   │   │   ├── BleDevice.kt          # Scanned device (id, name, rssi)
│       │   │   │   ├── ChatMessage.kt        # Message with delivery status
│       │   │   │   ├── ConnectionState.kt    # Disconnected/Connecting/Connected/Disconnecting
│       │   │   │   ├── DeliveryStatus.kt     # Sending/Sent/Failed
│       │   │   │   ├── ConnectedClient.kt    # Server mode client info
│       │   │   │   └── ServerState.kt        # Idle/Advertising/Error
│       │   │   └── repository/
│       │   │       ├── BleController.kt      # Central role interface
│       │   │       ├── BlePeripheralServer.kt # Peripheral role interface
│       │   │       ├── ChatRepository.kt     # Message persistence
│       │   │       └── AppPreferences.kt     # User settings interface
│       │   ├── data/
│       │   │   ├── ble/
│       │   │   │   ├── BleGattProfile.kt         # GATT UUIDs
│       │   │   │   └── KableCentralController.kt # Central implementation (kable)
│       │   │   ├── db/
│       │   │   │   ├── ChatDatabase.kt       # Room database (v2 with migration)
│       │   │   │   ├── ChatMessageEntity.kt  # Entity with deliveryStatus
│       │   │   │   ├── ChatMessageDao.kt     # Data access object
│       │   │   │   └── DatabaseFactory.kt    # Builder interface
│       │   │   └── repository/
│       │   │       └── ChatRepositoryImpl.kt # Repository implementation
│       │   ├── di/
│       │   │   └── SharedModule.kt           # Cross-platform DI
│       │   ├── presentation/
│       │   │   ├── device/       # DeviceViewModel, DeviceScreen, DeviceUiState
│       │   │   ├── chat/         # ChatViewModel, ChatScreen, ChatUiState
│       │   │   ├── server/       # ServerViewModel, ServerScreen, ServerUiState
│       │   │   │               # ServerChatViewModel, BroadcastChatViewModel
│       │   │   ├── settings/     # SettingsViewModel, SettingsScreen, SettingsUiState
│       │   │   ├── notification/ # MessageNotifier interface
│       │   │   └── components/   # ChatBubble, DeviceListItem
│       │   └── ui/theme/         # Colors (OldRose, Vanilla)
│       ├── androidMain/kotlin/com/bluechat/
│       │   ├── data/
│       │   │   ├── ble/
│       │   │   │   └── AndroidBlePeripheralServer.kt  # GATT Server + Advertiser
│       │   │   ├── db/
│       │   │   │   └── DatabaseFactory.android.kt     # Room builder with Context
│       │   │   └── preferences/
│       │   │       └── AndroidAppPreferences.kt       # DataStore preferences
│       │   ├── di/
│       │   │   └── AndroidModule.kt                   # Android DI bindings
│       │   └── presentation/notification/
│       │       └── AndroidMessageNotifier.kt          # Android notifications
│       └── iosMain/kotlin/com/bluechat/
│           ├── data/
│           │   ├── ble/
│           │   │   └── IosBlePeripheralServer.kt      # CBPeripheralManager
│           │   ├── db/
│           │   │   └── DatabaseFactory.ios.kt         # Room builder (NSHomeDirectory)
│           │   └── preferences/
│           │       └── IosAppPreferences.kt           # NSUserDefaults preferences
│           ├── di/
│           │   └── IosModule.kt                       # iOS DI bindings
│           └── presentation/notification/
│               └── IosMessageNotifier.kt              # iOS notifications
└── doc/
    └── plan.md                   # This file
```

---

## Tech Stack (Actual Versions)

| Component | Library | Version | Notes |
|-----------|---------|---------|-------|
| Kotlin | kotlin-multiplatform | 2.2.21 | Aligned with kable fork |
| AGP | android-application/library | 8.12.2 | Aligned with kable fork |
| Compose MP | org.jetbrains.compose | 1.10.1 | `compose.xxx` accessors (deprecated but functional) |
| Compose Compiler | kotlin.plugin.compose | 2.2.21 | Bundled with Kotlin |
| BLE | kable-core (local fork) | composite build | `includeBuild("../kable")` |
| DI | Koin | 4.1.0 | koin-compose-viewmodel for KMP VM injection |
| ViewModel | lifecycle-viewmodel-compose | 2.9.6 | JetBrains KMP wrapper |
| Navigation | navigation-compose | 2.9.2 | JetBrains KMP wrapper |
| Persistence | Room KMP | 2.7.1 | KSP 2.2.21-2.0.4 |
| SQLite | sqlite-bundled | 2.5.0 | Bundled driver for KMP |
| Coroutines | kotlinx-coroutines | 1.10.2 | |
| Activity | activity-compose | 1.10.1 | Android only (setContent) |
| compileSdk | | 36 | Required by androidx.core:core:1.17.0 |
| minSdk | | 24 | |
| Gradle | | 9.2.1 | Aligned with kable fork |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                 Presentation (Compose MP + ViewModel)           │
│   DeviceScreen, ChatScreen, ServerScreen, SettingsScreen        │
└────────────────────────────┬────────────────────────────────────┘
                             │ StateFlow<UiState>
┌────────────────────────────▼────────────────────────────────────┐
│                        Domain Layer                              │
│  Interfaces: BleController, BlePeripheralServer, ChatRepository  │
│              AppPreferences, MessageNotifier                     │
│  Models: BleDevice, ChatMessage, ConnectionState, DeliveryStatus │
│          ConnectedClient, ServerState                            │
└────────────────────────────┬────────────────────────────────────┘
                             │ implementations
┌────────────────────────────▼────────────────────────────────────┐
│                         Data Layer                               │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ commonMain                                                │   │
│  │  ├── KableCentralController (kable Scanner + Peripheral)  │   │
│  │  ├── Room ChatDatabase (v2 with migration)                │   │
│  │  └── ChatRepositoryImpl                                   │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌───────────────────────┐  ┌────────────────────────────────┐  │
│  │ androidMain           │  │ iosMain                        │  │
│  │  ├── BlePeripheralSrv │  │  ├── BlePeripheralServer      │  │
│  │  │   (BluetoothGatt)  │  │  │   (CBPeripheralManager)    │  │
│  │  ├── AppPreferences   │  │  ├── AppPreferences           │  │
│  │  │   (DataStore)      │  │  │   (NSUserDefaults)         │  │
│  │  └── MessageNotifier  │  │  └── MessageNotifier          │  │
│  └───────────────────────┘  └────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### Navigation Routes

```
devices                          # Home screen - scan & server mode
  ├── chat/{deviceId}           # 1-to-1 chat (Central mode)
  ├── settings                  # User preferences
  └── server                    # Server control panel (Peripheral mode)
        ├── server/chat/{clientId}   # Chat with specific client
        └── server/broadcast         # Broadcast to all clients
```

### Platform Boundaries

| Concern | Android | iOS |
|---|---|---|
| DB Builder | `Room.databaseBuilder(context, name)` via Koin | `Room.databaseBuilder(name)` via Koin |
| Koin Init | `BluechatApp.onCreate()` with `androidContext()` | `KoinInit.initKoin()` |
| Permissions | `ActivityResultContracts.RequestMultiplePermissions` | Info.plist |
| BLE Identifier | MAC address (String) | UUID |
| Peripheral Server | `BluetoothGattServer` + `BluetoothLeAdvertiser` | `CBPeripheralManager` |
| Preferences | DataStore | NSUserDefaults |
| Notifications | NotificationManager | UNUserNotificationCenter |

---

## Implementation Phases

### Phase 1: Project Skeleton -- DONE
- settings.gradle.kts, root build.gradle.kts, libs.versions.toml, gradle.properties
- shared + composeApp module build files
- Minimal App.kt, MainActivity.kt, MainViewController.kt
- Gradle wrapper (9.2.1 from kable)
- Verified: `assembleDebug` + `compileKotlinIosSimulatorArm64` pass

**Deviations from plan:**
- Added `compose-multiplatform` Gradle plugin back (needed for `compose.xxx` accessors since direct Maven coords like `org.jetbrains.compose.material3:material3` don't exist)
- Used `compilerOptions { jvmTarget.set(...) }` instead of deprecated `kotlinOptions`
- compileSdk bumped to 36 (required by transitive `androidx.core:core:1.17.0`)
- Added `activity-compose` dependency for `setContent` in Activity

### Phase 2: Domain Models + Room Database -- DONE
- Domain: BleDevice, ChatMessage, ConnectionState, BleController, ChatRepository
- Room: ChatMessageEntity, ChatMessageDao, ChatDatabase
- DatabaseFactory as plain function (not expect/actual) -- platform builder provided via Koin
- ChatRepositoryImpl with entity<->domain mappers
- Uses `Dispatchers.IO` (imported as `kotlinx.coroutines.IO` for KMP)

**Deviations from plan:**
- No expect/actual for `getDatabaseBuilder()` -- Android needs Context parameter, so the builder is provided through platform-specific Koin modules instead
- `BundledSQLiteDriver` used for cross-platform SQLite

### Phase 3: BLE Central (kable) -- DONE
- BleGattProfile with UUIDs from BluetoothLEChat (SERVICE_UUID, MESSAGE characteristic, etc.)
- KableCentralController implementing BleController interface
- Scanner with `filters { match { services = listOf(SERVICE_UUID) } }`
- Advertisement cache by identifier for later `Peripheral()` creation
- `characteristicOf(Uuid, Uuid)` for lazy characteristic references (String variant deprecated at ERROR level)
- `observe(messageChar)` for incoming, `write(messageChar, data)` for sending
- Global opt-in: `kotlin.uuid.ExperimentalUuidApi`, `kotlin.time.ExperimentalTime`

### Phase 4: DI + Wiring -- DONE
- SharedModule: DB, DAO, repositories, BLE controller, ViewModels
- AndroidModule: `getDatabaseBuilder(context)`
- IosModule: `getDatabaseBuilder()`
- BluechatApp: `startKoin { androidContext(); modules(androidModule, sharedModule) }`
- KoinInit: `startKoin { modules(iosModule, sharedModule) }`
- ChatViewModel uses parametrized injection: `viewModel { params -> ChatViewModel(params.get(), get(), get()) }`

### Phase 5: Compose UI (Material 3) -- DONE
- DeviceScreen: LazyColumn of scanned devices, Start/Stop Scan buttons, connecting indicator
- ChatScreen: LazyColumn of ChatBubbles, TextField + Send button, Disconnect in top bar
- ChatBubble: Asymmetric rounded corners (15.dp), OldRose (`0xFFCD8B76`) for local, Vanilla (`0xFFE1D89F`) for remote
- NavHost in App.kt: `"devices"` and `"chat/{deviceId}"` routes
- ViewModels injected via `koinViewModel<>()` with `collectAsStateWithLifecycle()`
- Auto-scroll to latest message via `LaunchedEffect(messages.size)`

**Deviations from plan:**
- No Material Icons (not available in KMP Compose by default) -- used TextButton("Disconnect") and Button("Send") instead of icon buttons
- Timestamps via `kotlin.time.Clock.System.now().toEpochMilliseconds()` (stdlib) instead of kotlinx-datetime (Clock.System removed in 0.7.x)

### Phase 6: Permissions -- DONE
- AndroidManifest:
  - `BLUETOOTH_SCAN` (with `neverForLocation`)
  - `BLUETOOTH_CONNECT`
  - `BLUETOOTH_ADVERTISE` (for server mode)
  - `ACCESS_FINE_LOCATION` (fallback for BLE on older APIs)
  - `POST_NOTIFICATIONS` (Android 13+)
- `uses-feature android.hardware.bluetooth_le required=true`
- Runtime: `ActivityResultContracts.RequestMultiplePermissions` in MainActivity
- API 31+ check for BLE-specific permissions vs location fallback
- iOS: Info.plist entries:
  - `NSBluetoothAlwaysUsageDescription`
  - `NSBluetoothPeripheralUsageDescription`

### Phase 7: BLE Peripheral/Server -- DONE
- `BlePeripheralServer` interface in domain layer with:
  - `startAdvertising()`, `stopAdvertising()`
  - `sendToAll(message)`, `sendTo(clientId, message)`
  - StateFlow streams: `serverState`, `connectedClients`, `incomingMessages`
- Android implementation (`AndroidBlePeripheralServer.kt`):
  - `BluetoothGattServer` + `BluetoothLeAdvertiser`
  - Tracks connected devices by MAC address
  - Handles GATT read/write/descriptor requests
- iOS implementation (`IosBlePeripheralServer.kt`):
  - `CBPeripheralManager` from CoreBluetooth
  - Tracks connected centrals by UUID
  - Uses Kotlin/Native interop for NSData conversion
- Server UI: `ServerScreen`, `ServerViewModel`, `ServerUiState`
- Additional permission: `BLUETOOTH_ADVERTISE` in AndroidManifest

### Phase 8: Quality of Life Improvements -- DONE
- **Display Name Advertising**: Customizable device name in settings, used for BLE advertising
- **Connection Retry**: Exponential backoff (1s, 2s, 4s delays, 3 attempts max)
- **Delivery Status Tracking**: Messages show Sending/Sent/Failed states
  - `DeliveryStatus` enum in domain layer
  - `ChatMessageEntity` updated with `deliveryStatus` column
  - Room database migration v1 → v2
- **Broadcast Chat**: Send messages to all connected clients simultaneously
  - `BroadcastChatViewModel` with `sendToAll()` functionality
  - Special "broadcast" device ID for message persistence
- **Notifications**: Platform-specific message notifications
  - `MessageNotifier` interface with Android/iOS implementations
  - Shows notification when message received from remote device
- **Settings Screen**: User preferences UI
  - `SettingsViewModel`, `SettingsScreen`, `SettingsUiState`
  - `AppPreferences` interface with platform implementations
  - Theme options: System, Light, Dark
- **Server Chat**: 1-to-1 chat with specific connected clients
  - `ServerChatViewModel` with client filtering

---

## Kable Integration (Local Fork)

```kotlin
// settings.gradle.kts
includeBuild("../kable")  // Gradle auto-substitutes com.juul.kable:* dependencies

// shared/build.gradle.kts
implementation(libs.kable.core)  // version-less, resolved from composite build
```

Fallback if composite build fails: `cd ../kable && ./gradlew publishToMavenLocal`, then add `mavenLocal()` to repositories.

---

## Verification Strategy

1. **Build check:** `./gradlew :composeApp:assembleDebug` -- PASSING
2. **iOS check:** `./gradlew :shared:compileKotlinIosSimulatorArm64` -- PASSING
3. **Install:** `./gradlew :composeApp:installDebug`
4. **BLE Central test:** Start scan → devices appear → tap device → ChatScreen → send message → verify on remote
5. **Persistence test:** Send/receive messages → kill app → reopen → messages restored from Room
6. **BLE Peripheral test:** Start Server → verify service advertised → receive from remote → reply
7. **Broadcast test:** Start server → connect multiple clients → send broadcast → verify all clients receive
8. **Delivery status test:** Send message → verify "Sending" → verify "Sent" (or "Failed" on error)
9. **Settings test:** Change display name → start server → verify name appears in BLE advertisements
10. **Notification test:** Receive message while app in background → verify notification appears

---

## Critical Project Files

### Core BLE
- `shared/src/commonMain/kotlin/com/bluechat/data/ble/KableCentralController.kt` - Central BLE implementation
- `shared/src/commonMain/kotlin/com/bluechat/data/ble/BleGattProfile.kt` - GATT UUIDs
- `shared/src/androidMain/kotlin/com/bluechat/data/ble/AndroidBlePeripheralServer.kt` - Android GATT Server
- `shared/src/iosMain/kotlin/com/bluechat/data/ble/IosBlePeripheralServer.kt` - iOS Peripheral Manager

### Domain
- `shared/src/commonMain/kotlin/com/bluechat/domain/repository/BleController.kt` - Central interface
- `shared/src/commonMain/kotlin/com/bluechat/domain/repository/BlePeripheralServer.kt` - Server interface
- `shared/src/commonMain/kotlin/com/bluechat/domain/model/` - All domain models

### Database
- `shared/src/commonMain/kotlin/com/bluechat/data/db/ChatDatabase.kt` - Room database
- `shared/schemas/` - Room schema files (v1, v2)

### Presentation
- `shared/src/commonMain/kotlin/com/bluechat/presentation/chat/ChatViewModel.kt` - Central chat logic
- `shared/src/commonMain/kotlin/com/bluechat/presentation/server/ServerViewModel.kt` - Server logic
- `composeApp/src/commonMain/kotlin/com/bluechat/App.kt` - Navigation routes

### DI
- `shared/src/commonMain/kotlin/com/bluechat/di/SharedModule.kt` - Cross-platform DI
- `shared/src/androidMain/kotlin/com/bluechat/di/AndroidModule.kt` - Android bindings
- `shared/src/iosMain/kotlin/com/bluechat/di/IosModule.kt` - iOS bindings

### External Dependencies
- `kable/kable-core/` - KMP BLE library (local fork via composite build)

---

## Known Issues / Notes

- Compose MP `compose.xxx` accessors are deprecated in 1.10.1 but still functional. Direct Maven coordinates don't work (e.g. `org.jetbrains.compose.material3:material3` doesn't exist). Will migrate when Compose MP provides a BOM or correct artifact names.
- `kotlin.uuid.Uuid` and `kotlin.time.Clock` are still experimental in Kotlin 2.2.21 -- global opt-in configured in `shared/build.gradle.kts`.
- Room KSP generates schema into `shared/schemas/` on Android and iOS targets.
- The `kable-default-permissions` module only provides manifest entries; runtime permission requests are handled manually in `MainActivity`.
- Room database migration from v1 to v2 adds `deliveryStatus` column with default value "Sent".

---

## Future Enhancements (Phase 9+)

### High Priority
1. **Read Receipts**: Show when the other party has read your message
2. **Typing Indicators**: Show when the other party is typing
3. **Message Timestamps in UI**: Display message send/receive times in chat bubbles
4. **Connection Quality Indicator**: Show RSSI signal strength during active connection

### Medium Priority
5. **Offline Message Queue**: Queue messages when disconnected, send when reconnected
6. **Message Search**: Search through chat history
7. **Chat Export**: Export chat history to file
8. **Multiple Simultaneous Connections (Central)**: Connect to multiple peripherals at once
9. **Auto-Reconnect**: Automatically reconnect when connection drops unexpectedly
10. **Device Favorites/Bookmarks**: Save frequently used devices for quick access

### Nice to Have
11. **File/Image Transfer**: Send files or images over BLE (chunked transfer)
12. **End-to-End Encryption**: Encrypt messages for privacy
13. **Group Chat Names**: Name server chat rooms
14. **Accessibility**: VoiceOver/TalkBack support, dynamic text sizing
15. **Widgets**: Quick-access widgets for Android/iOS

### Technical Improvements
16. **Unit Tests**: ViewModel and Repository tests with Turbine/MockK
17. **UI Tests**: Compose UI tests with compose-test framework
18. **CI/CD Pipeline**: GitHub Actions for build/test/deploy
19. **Crash Reporting**: Integrate Firebase Crashlytics or similar
20. **Analytics**: Usage tracking for feature prioritization

---

## GATT Profile Reference

```kotlin
// Service and Characteristic UUIDs (BleGattProfile.kt)
SERVICE_UUID          = "1706BBC0-88AB-4B8D-877E-2237916EE929"
CHARACTERISTIC_MESSAGE = "275348FB-C14D-4FD5-B434-7C3F351DEA5F"
DESCRIPTOR_MESSAGE    = "45bda094-ff40-4cb8-835d-0da8742bb1eb"
```

Characteristic properties: Read, Write, Notify
