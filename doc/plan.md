# Bluechat-KMP Project Plan

## Context

KMP project at `/home/mandroid/Videos/AllBlue/Bluechat-KMP/` that replicates the BluetoothLEChat BLE messaging features using the local kable fork as the BLE transport layer. The app targets Android + iOS with shared Compose Multiplatform UI, Room KMP for persistence, Koin for DI, and Jetpack Navigation Multiplatform.

**Blueprint projects:**
- `BluetoothLEChat` - BLE features (GATT profile, central/peripheral roles, scanning, advertising, chat over BLE)
- `BluetoothChat` / `BlueChat` - Compose UI patterns (DeviceScreen, ChatScreen, ChatBubble, ViewModel+StateFlow)
- `kable` (local fork) - KMP BLE library (Scanner, Peripheral, observe/write APIs)

**Key constraint:** kable only supports the Central role. The Peripheral/GATT Server role requires platform-specific `expect/actual` implementations (deferred to Phase 7).

**Decisions:**
- Kable integration: **Composite build** (`includeBuild("../kable")`)
- Initial BLE scope: **Central role first** (scan + connect + chat against existing BluetoothLEChat devices)
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
│       │   └── App.kt           # NavHost root ("devices", "chat/{deviceId}")
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
│   ├── schemas/                  # Room auto-generated
│   └── src/
│       ├── commonMain/kotlin/com/bluechat/
│       │   ├── domain/model/     # BleDevice, ChatMessage, ConnectionState
│       │   ├── domain/repository/ # BleController, ChatRepository interfaces
│       │   ├── data/ble/         # BleGattProfile, KableCentralController
│       │   ├── data/db/          # Room: ChatDatabase, ChatMessageDao, Entity, DatabaseFactory
│       │   ├── data/repository/  # ChatRepositoryImpl
│       │   ├── di/               # SharedModule (Koin)
│       │   ├── presentation/
│       │   │   ├── device/       # DeviceViewModel, DeviceScreen, DeviceUiState
│       │   │   ├── chat/         # ChatViewModel, ChatScreen, ChatUiState
│       │   │   └── components/   # ChatBubble, DeviceListItem
│       │   └── ui/theme/         # Colors (OldRose, Vanilla)
│       ├── androidMain/kotlin/com/bluechat/
│       │   ├── data/db/          # DatabaseFactory.android.kt (Room builder with Context)
│       │   └── di/               # AndroidModule (provides DB builder)
│       └── iosMain/kotlin/com/bluechat/
│           ├── data/db/          # DatabaseFactory.ios.kt (Room builder with NSHomeDirectory)
│           └── di/               # IosModule (provides DB builder)
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
Presentation (Compose MP + ViewModel)
    | StateFlow<UiState>
Domain (interfaces: BleController, ChatRepository; models: BleDevice, ChatMessage)
    | implementations
Data
  ├── KableCentralController (commonMain, uses kable Scanner + Peripheral)
  ├── Room ChatDatabase (commonMain, platform DB builder via Koin)
  └── ChatRepositoryImpl (commonMain)
```

### Platform Boundaries

| Concern | Android | iOS |
|---|---|---|
| DB Builder | `Room.databaseBuilder(context, name)` via Koin | `Room.databaseBuilder(name)` via Koin |
| Koin Init | `BluechatApp.onCreate()` with `androidContext()` | `KoinInit.initKoin()` |
| Permissions | `ActivityResultContracts.RequestMultiplePermissions` | Info.plist (future) |
| BLE Identifier | MAC address (String) | UUID |

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
- AndroidManifest: `BLUETOOTH_SCAN` (with `neverForLocation`), `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`
- `uses-feature android.hardware.bluetooth_le required=true`
- Runtime: `ActivityResultContracts.RequestMultiplePermissions` in MainActivity
- API 31+ check for BLE-specific permissions vs location fallback
- iOS: Info.plist `NSBluetoothAlwaysUsageDescription` (to be added when building iOS target)

### Phase 7 (Future): BLE Peripheral/Server -- DEFERRED
Will add `expect class BlePeripheralServer` with:
- Android: `BluetoothGattServer` + `BluetoothLeAdvertiser` (port from `BLEPeripheralHelper.java`)
- iOS: `CBPeripheralManager`
- Additional permission: `BLUETOOTH_ADVERTISE`
- "Start Server" button on DeviceScreen

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
4. **BLE Central test:** Start scan -> devices appear -> tap device -> ChatScreen -> send message -> verify on remote
5. **Persistence test:** Send/receive messages -> kill app -> reopen -> messages restored from Room
6. **BLE Peripheral test (Phase 7):** Start Server -> verify service advertised -> receive from remote

---

## Critical Reference Files

- `BluetoothLEChat/.../BLEChatProfile.java` - GATT UUID constants (ported to BleGattProfile.kt)
- `BluetoothLEChat/.../BLEPeripheralHelper.java` - GATT Server to port in Phase 7
- `BluetoothChat/.../BluetoothViewModel.kt` - ViewModel pattern reference
- `BluetoothChat/.../ChatMessage.kt` - ChatBubble UI reference (replicated)
- `kable/kable-core/src/commonMain/kotlin/Peripheral.kt` - kable API
- `kable/kable-core/src/commonMain/kotlin/Scanner.kt` - Scanner API

---

## Known Issues / Notes

- Compose MP `compose.xxx` accessors are deprecated in 1.10.1 but still functional. Direct Maven coordinates don't work (e.g. `org.jetbrains.compose.material3:material3` doesn't exist). Will migrate when Compose MP provides a BOM or correct artifact names.
- `kotlin.uuid.Uuid` and `kotlin.time.Clock` are still experimental in Kotlin 2.2.21 -- global opt-in configured in `shared/build.gradle.kts`.
- Room KSP generates schema into `shared/schemas/` on Android and iOS targets.
- The `kable-default-permissions` module only provides manifest entries; runtime permission requests are handled manually in `MainActivity`.
