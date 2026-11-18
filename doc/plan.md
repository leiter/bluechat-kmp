# Bluechat-KMP — Architecture and Build History

**Last updated:** 2026-11-18 (`main` at `e3a6e4f`)

This document has two parts:

1. **Architecture reference.** How the app is put together *today*: modules, layers, routes,
   wire protocol, database and platform boundaries.
2. **Build history (Phases 1–8).** How the first version was built, kept for the reasoning
   behind early decisions. Where a later change replaced something, the history says so.

For **status, open work and dated decisions**, [`roadmap.md`](roadmap.md) is the source of
truth. Phase 9 and everything after it (phases A–F) is documented there. For defects found
on devices, see [`device-test-findings.md`](device-test-findings.md). For setup, see the
[`README`](../README.md).

---

# Part 1 — Architecture reference

## Overview

Bluechat-KMP is a peer-to-peer chat app over Bluetooth Low Energy, built with Kotlin
Multiplatform and a shared Compose Multiplatform UI for Android and iOS.

The two BLE roles are **asymmetric by design** (decision E3 in the roadmap). One device
goes into **Server Mode** (GATT peripheral, advertises and accepts several clients). Other
devices **scan and connect** to it (GATT central, built on kable). The protocol layer above
the transport doesn't depend on which role a device has.

Features as of today:

- **Conversation list** as the home screen. It is derived from the messages and has
  pinned/muted/archived flags.
- **Contacts** keyed on a stable per-install peer id. The name a peer announces and the
  local nickname are kept separate, and contacts can be favourited or blocked.
- **1:1 chat** in both roles, plus **group/broadcast chat** on the server.
- **Framed wire protocol** with MTU negotiation (up to 517), so message length isn't
  limited.
- **Delivery and read receipts, typing indicator, an outbox with retry, and auto-reconnect**
  with exponential backoff.
- **Notifications**, plus an Android **foreground service** that keeps the session alive in
  the background.
- **Settings:** display name (sent to peers in the profile envelope) and theme
  (System/Light/Dark).

## Project structure

```
bluechat-kmp/
├── settings.gradle.kts            # includeBuild("../kable") — kable dbb7599, see README
├── gradle/libs.versions.toml
├── composeApp/                    # app shell only
│   └── src/
│       ├── commonMain/.../App.kt              # NavHost, all routes
│       ├── androidMain/.../
│       │   ├── MainActivity.kt                # runtime permissions + setContent
│       │   ├── BluechatApp.kt                 # startKoin, starts coordinator + session launcher
│       │   └── service/
│       │       ├── BluechatService.kt         # foreground service (connectedDevice)
│       │       └── SessionServiceLauncher.kt  # starts/stops it from BleSessionMonitor
│       └── iosMain/.../  MainViewController.kt, KoinInit.kt
├── shared/
│   ├── schemas/                   # Room exports: 1, 2, 5, 6, 7, 8 (3 and 4 were never exported)
│   └── src/
│       ├── commonMain/kotlin/com/bluechat/
│       │   ├── data/
│       │   │   ├── ble/           # KableCentralController, BleGattProfile, MtuNegotiation (expect)
│       │   │   ├── protocol/      # Frame, Reassembler, Envelope, ProtocolCodec, PeerMessageMapper
│       │   │   ├── db/            # ChatDatabase (v8), entities + DAOs for messages, outbox,
│       │   │   │                  #   contacts, conversations; ConversationSummaryRow
│       │   │   └── repository/    # Chat, Contact, Conversation, MessageQueue, PeerDirectory impls
│       │   ├── domain/
│       │   │   ├── model/         # BleDevice, ChatMessage, Contact, Conversation, DeliveryStatus,
│       │   │   │                  #   ConnectionState, ServerState, ConnectedClient, PeerMessage,
│       │   │   │                  #   PendingMessage
│       │   │   ├── repository/    # interfaces: BleController, BlePeripheralServer, ChatRepository,
│       │   │   │                  #   ContactRepository, ConversationRepository,
│       │   │   │                  #   MessageQueueRepository, PeerDirectory, AppPreferences
│       │   │   ├── messaging/     # IncomingMessageCoordinator
│       │   │   ├── session/       # BleSessionMonitor
│       │   │   └── notification/  # MessageNotifier (expect)
│       │   ├── presentation/      # conversations, contacts, device, chat, server, settings,
│       │   │                      #   components (ChatBubble, DateSeparator, TypingIndicator,
│       │   │                      #   ReconnectingBanner, SignalStrengthIndicator, list items)
│       │   ├── di/                # SharedModule
│       │   ├── ui/theme/          # Colors (OldRose, Vanilla), Theme
│       │   └── util/              # TimeFormatter (expect), SignalStrength
│       ├── commonTest/            # 7 test files, 67 tests (protocol, repository, ViewModels)
│       ├── androidMain/           # AndroidBlePeripheralServer, MtuNegotiation, DatabaseFactory,
│       │                          #   AndroidAppPreferences, MessageNotifier, AndroidModule
│       └── iosMain/               # IosBlePeripheralServer, MtuNegotiation, DatabaseFactory,
│                                  #   IosAppPreferences, MessageNotifier, IosModule
├── iosApp/                        # Xcode project; build phase runs embedAndSignAppleFrameworkForXcode
└── doc/                           # this file, roadmap.md, device-test-findings.md
```

## Layers

```
┌───────────────────────────────────────────────────────────────────────┐
│ Presentation  (Compose MP + ViewModel → StateFlow<UiState>)           │
│  Conversations · Contacts · Device (new-chat picker) · Chat ·         │
│  Server · ServerChat · BroadcastChat · Settings                       │
└──────────────────────────────┬────────────────────────────────────────┘
                               │ reads Room-backed flows, calls repositories
┌──────────────────────────────▼────────────────────────────────────────┐
│ Domain                                                                │
│  interfaces: BleController, BlePeripheralServer, *Repository,         │
│              PeerDirectory, AppPreferences, MessageNotifier           │
│  process-wide: IncomingMessageCoordinator, BleSessionMonitor          │
└──────────────────────────────┬────────────────────────────────────────┘
                               │ implementations
┌──────────────────────────────▼────────────────────────────────────────┐
│ Data                                                                  │
│  commonMain: KableCentralController · protocol/ · Room ChatDatabase · │
│              repository impls                                         │
│  androidMain: AndroidBlePeripheralServer (BluetoothGattServer)        │
│  iosMain:     IosBlePeripheralServer (CBPeripheralManager)            │
└───────────────────────────────────────────────────────────────────────┘
```

**Inbound messages flow in one direction only.** Both roles' `incomingMessages` flows are
collected by `IncomingMessageCoordinator`, which is started from `BluechatApp.onCreate()` /
`initKoin()` and runs for the life of the process. It decodes, drops messages from blocked
contacts, persists, mirrors server traffic into the `broadcast` thread, and raises
notifications. ViewModels only read from Room. This rule fixed defect #4.

**Long-lived singletons.** `BleController`, `BlePeripheralServer`, the coordinator and
`BleSessionMonitor` are Koin `single`s. ViewModels never `release()` them (defect #10).
`BleSessionMonitor` combines both roles' state into one flow. The Android
`SessionServiceLauncher` uses that flow to start or stop the foreground service.

## Navigation routes

Start destination: `conversations`.

```
conversations                     # home: chat list
├── devices                       # "New chat": scan + connect (central)
│   └── chat/{deviceId}           # 1:1 chat, central role
├── contacts                      # contacts list
├── server                        # Server Mode control panel (peripheral)
│   ├── server/chat/{clientId}    # 1:1 chat with a connected client
│   └── server/broadcast          # group chat to all clients
└── settings                      # display name, theme
```

The chat list chooses between `chat/…` and `server/chat/…` based on the conversation's
`peerTransportId`, checked against the server's connected clients (decision C2). A
conversation can only be answered through the role that owns the connection.

## Platform boundaries

| Concern | Android | iOS |
|---|---|---|
| Koin init | `BluechatApp.onCreate()` with `androidContext()` | `KoinInit.initKoin()` |
| DB builder | `Room.databaseBuilder(context, name)` via `AndroidModule` | `Room.databaseBuilder(name)` via `IosModule` |
| Permissions | Runtime request in `MainActivity`. API 31+ `BLUETOOTH_SCAN/CONNECT/ADVERTISE`, ≤ 30 legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` + location, `POST_NOTIFICATIONS` | `Info.plist` usage strings. No BLE background modes yet (F2) |
| Transport id | MAC address | CoreBluetooth UUID |
| Peripheral server | `BluetoothGattServer` + `BluetoothLeAdvertiser` | `CBPeripheralManager` (not device-verified) |
| MTU | `requestMtu(517)` | Negotiated by CoreBluetooth |
| Background | `BluechatService` foreground service | none yet |
| Preferences | `SharedPreferences` (`bluechat_prefs`) | `NSUserDefaults` |
| Notifications | `NotificationManager` | `UNUserNotificationCenter` |

## Wire protocol

All traffic goes over one GATT characteristic. In both directions a payload is:

1. **An envelope**: a JSON-serialized `Envelope` (kotlinx.serialization, `ProtocolCodec`):

   | Type (`@SerialName`) | Fields | Purpose |
   |---|---|---|
   | `text` | `senderId`, `messageId`, `sentAt`, `body` | chat message |
   | `profile` | `senderId`, `displayName` | identity exchange, sent by both sides on connect |
   | `read` | `senderId`, `messageId` | read receipt |
   | `typing` | `senderId`, `isTyping` | typing indicator, never persisted |

   `senderId` is the stable per-install peer id. `PeerDirectory` maps transport addresses to it.

2. **Split into frames** (`Frame`, `Reassembler`) sized to the negotiated MTU (`MTU − 3`
   bytes per write). Each frame has a 10-byte big-endian header:
   `version(1) flags(1) messageId(4) sequence(2) total(2)`. Incomplete sets time out, and
   reassembly is capped at 256 KiB. UTF-8 is decoded only after reassembly.

Writes from the central always use `WriteType.WithResponse`, because the characteristic
declares only `PROPERTY_WRITE` (defect #2).

### GATT profile (`BleGattProfile.kt`)

| Name | UUID | Use |
|---|---|---|
| `SERVICE_UUID` | `1706BBC0-88AB-4B8D-877E-2237916EE929` | the service. Advertised, and used as the scan filter |
| `CHARACTERISTIC_MESSAGE_UUID` | `275348FB-C14D-4FD5-B434-7C3F351DEA5F` | read / write / notify, carries all frames |
| `DESCRIPTOR_MESSAGE_UUID` | `45bda094-ff40-4cb8-835d-0da8742bb1eb` | custom descriptor on the message characteristic (inherited from BluetoothLEChat) |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` | standard notification descriptor. Required, see defect #3 |
| `CHARACTERISTIC_BLE_TRANSFER_UUID` | `482f1096-137b-46cc-8ca8-3457c15cc433` | reserved, not used |
| `DESCRIPTOR_BLE_TRANSFER_UUID` | `421ecb34-bb49-4b70-a5ea-042c1f38ec32` | reserved, not used |

**Advertising:** the advertisement carries only the 128-bit service UUID, which uses 18 of
the 31 bytes. The phone's own Bluetooth name, unchanged, goes in the scan response. If it
doesn't fit, the server retries without it. The app never renames the adapter (defect #7,
B4).

## Database

Room KMP with the bundled SQLite driver, **schema version 8**:

| Table | Holds |
|---|---|
| `chat_messages` | message history per conversation, `messageUuid` (wire id), `deliveryStatus`, `readAt` |
| `pending_messages` | outbox, drained on reconnect |
| `contacts` | one row per peer id: `announcedName` (sent by the peer, untrusted), `nickname` (local, takes precedence), favourite, blocked |
| `conversations` | **only** pinned/muted/archived flags. Created when a flag is first set. Last message, preview and unread count are computed in `ConversationDao.observeSummaries` (decision C1) |

`DeliveryStatus`: `Queued, Sending, Sent, Delivered, Read, Failed` (`Delivered` is not set
yet). `ConnectionState`: `Disconnected, Connecting, Connected, Disconnecting, Reconnecting`.
`ServerState`: `Idle, Advertising, Error`.

**Schema policy: no migrations.** A schema change is a version bump, and
`fallbackToDestructiveMigration(dropAllTables = true)` wipes the database. This is a
deliberate pre-release shortcut with a release gate (F0 in the roadmap). The v1 → v6
`Migration` objects mentioned in the history below were deleted.

## Tech stack

| Component | Library | Version | Notes |
|---|---|---|---|
| Kotlin | kotlin-multiplatform | 2.2.21 | matches kable dbb7599 |
| AGP | android-application/library | 8.12.2 | |
| Gradle | wrapper | 9.2.1 | matches kable dbb7599 |
| Compose MP | org.jetbrains.compose | 1.10.1 | `compose.xxx` accessors (deprecated but needed) |
| BLE | kable-core | `dbb7599` = 0.42.0 + 2 (composite build) | `includeBuild("../kable")` |
| Serialization | kotlinx-serialization-json | 1.9.0 | wire envelope |
| DI | Koin | 4.1.0 | koin-compose-viewmodel |
| ViewModel | lifecycle-viewmodel-compose | 2.9.6 | JetBrains KMP |
| Navigation | navigation-compose | 2.9.2 | JetBrains KMP, string routes |
| Persistence | Room KMP | 2.7.1 | KSP 2.2.21-2.0.4 |
| SQLite | sqlite-bundled | 2.5.0 | |
| Coroutines | kotlinx-coroutines | 1.10.2 | |
| Tests | kotlin-test, coroutines-test, Turbine 1.2.0 | | `commonTest` |
| Android SDK | | compile 36 / target 35 / min 24 | compile 36 required by androidx.core 1.17.0 |
| Toolchain | | JDK 17 for kable | provided by the foojay resolver, see README |

## Kable integration

```kotlin
// settings.gradle.kts
includeBuild("../kable")          // Gradle substitutes com.juul.kable:* automatically

// shared/build.gradle.kts
implementation(libs.kable.core)   // no version, resolved from the composite build
```

`../kable` must be upstream [JuulLabs/kable](https://github.com/JuulLabs/kable) at tag
commit `dbb7599` (`0.42.0` plus two maintenance commits). 0.43+ needs Gradle 9.5+
and Kotlin 2.3+, and 0.44+ also needs AGP 9. The project uses
`AndroidPeripheral.requestMtu` and `maximumWriteValueLengthForType`, which are both present
there. A local edit to kable's `settings.gradle.kts` adds the foojay toolchain resolver so
Gradle can download JDK 17. The README has the snippet.

## Verification

Automated:

```bash
./gradlew :composeApp:assembleDebug
./gradlew :shared:testDebugUnitTest                 # 67 tests
./gradlew :shared:compileKotlinIosSimulatorArm64
```

On devices (two phones; there's no BLE in emulators). The procedure and the last results
are in `device-test-findings.md`:

1. Server Mode on phone A, **New chat** → scan → connect on phone B.
2. Client → server and server → client messages, including a long one (> 300 chars,
   multi-frame).
3. Group chat with all clients.
4. Read receipt: the server's message reaches `Read` once the client displays it.
5. Persistence across app restarts. Notification while on another screen or in the
   background.
6. Background the server: the foreground service stays `isForeground=true` and the peer can
   still connect.

## Known issues / notes

- Compose MP `compose.xxx` accessors are deprecated in 1.10.1 but still needed. Direct Maven
  coordinates such as `org.jetbrains.compose.material3:material3` don't exist.
- `kotlin.uuid.Uuid` and `kotlin.time.Clock` are experimental in Kotlin 2.2.21. A global
  opt-in is set in `shared/build.gradle.kts`.
- `kable-default-permissions` only adds manifest entries. Runtime permission requests happen
  in `MainActivity`.
- iOS has never been run on a device. See roadmap F2.
- Open defects and deferred work are tracked in the roadmap's **Deferred** table.

---

# Part 2 — Build history (Phases 1–8)

> These phases describe the first version of the app. Later work has replaced several
> details. Notes marked **Superseded** point to the replacement. Phase 9 and phases A–F are
> in `roadmap.md`.

## Original context

The project began as a KMP port of the BLE messaging in `BluetoothLEChat`, using kable as
the transport.

**Blueprint projects** (siblings on the original development machine, not in this repo):
- `BluetoothLEChat`: BLE features (GATT profile, central/peripheral roles, chat over BLE).
  `BLEChatProfile.java` is the source of the UUIDs, and `BLEPeripheralHelper.java` was ported
  for the GATT server.
- `BluetoothChat` / `BlueChat`: Compose UI patterns (DeviceScreen, ChatScreen, ChatBubble,
  ViewModel + StateFlow).

**Initial decisions** (still valid unless noted):
- kable as a **composite build** (`includeBuild("../kable")`).
- **Central role first**, peripheral later (Phase 7).
- **Material 3** with Compose Multiplatform.
- **Platform DB builders through Koin modules**, not expect/actual, because Android needs
  a `Context`.
- **Timestamps from `kotlin.time.Clock.System`**, without kotlinx-datetime.

### Phase 1: Project skeleton — DONE
- Gradle setup, `shared` + `composeApp` modules, minimal `App.kt` / `MainActivity` /
  `MainViewController`, Gradle wrapper 9.2.1.
- Deviations: the `compose-multiplatform` plugin was added back for the `compose.xxx`
  accessors; `compilerOptions { jvmTarget }` replaced `kotlinOptions`; compileSdk was raised
  to 36 for `androidx.core 1.17.0`; `activity-compose` was added for `setContent`.

### Phase 2: Domain models + Room — DONE
- Domain: BleDevice, ChatMessage, ConnectionState, BleController, ChatRepository.
- Room: ChatMessageEntity, ChatMessageDao, ChatDatabase, with `BundledSQLiteDriver`.
- `DatabaseFactory` is a plain function, and the platform builder comes from Koin.

### Phase 3: BLE central (kable) — DONE
- `BleGattProfile` UUIDs ported from BluetoothLEChat.
- `KableCentralController`: a scanner filtered on `SERVICE_UUID`, an advertisement cache by
  identifier, `characteristicOf(Uuid, Uuid)`, `observe` for incoming, `write` for outgoing.
- Global opt-in for `ExperimentalUuidApi` and `ExperimentalTime`.
- **Superseded:** raw-string payloads were replaced by the framed envelope protocol (Phase
  A). Writes now use `WithResponse` (defect #2). The incoming flow is a `flatMapLatest` over
  the current connection (defect #4).

### Phase 4: DI + wiring — DONE
- `SharedModule` plus `AndroidModule` / `IosModule`, `startKoin` in `BluechatApp` /
  `KoinInit`.
- Parametrised ViewModel injection for route arguments
  (`viewModel { params -> ChatViewModel(params.get(), …) }`).

### Phase 5: Compose UI (Material 3) — DONE
- DeviceScreen (scan list), ChatScreen (bubbles, input, disconnect), ChatBubble (asymmetric
  15 dp corners; OldRose `0xFFCD8B76` local, Vanilla `0xFFE1D89F` remote).
- `koinViewModel()` + `collectAsStateWithLifecycle()`; auto-scroll with
  `LaunchedEffect(messages.size)`.
- No Material Icons in KMP by default, so text buttons are used instead.
- **Superseded:** the home screen is now `conversations`, and `devices` is the "New chat"
  picker (C2). DeviceScreen got a `Scaffold`/`TopAppBar` (defect #6).

### Phase 6: Permissions — DONE
- Manifest: `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT`,
  `BLUETOOTH_ADVERTISE`, `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`, and
  `uses-feature bluetooth_le`.
- Runtime request in `MainActivity` with an API 31+ split.
- iOS: `NSBluetoothAlwaysUsageDescription`, `NSBluetoothPeripheralUsageDescription`.
- **Later additions:** legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` for API ≤ 30 (defect #1), and
  `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` (E1).

### Phase 7: BLE peripheral/server — DONE
- `BlePeripheralServer` interface: `startAdvertising` / `stopAdvertising`, `sendToAll`,
  `sendTo`, and flows for `serverState`, `connectedClients`, `incomingMessages`.
- Android: `BluetoothGattServer` + `BluetoothLeAdvertiser`, clients tracked by MAC address.
- iOS: `CBPeripheralManager`, centrals tracked by UUID.
- Server UI: `ServerScreen` / `ServerViewModel`.
- **Later changes:** a standard CCCD was added (defect #3), `lastError` with readable
  advertise failure codes (defect #9), and leaving the screen no longer stops the server
  (defect #11).

### Phase 8: Quality-of-life — DONE
- Connection retry with exponential backoff, later extended by auto-reconnect (E2).
- Delivery status (`Sending/Sent/Failed`, later extended), broadcast chat
  (`BroadcastChatViewModel`, the `broadcast` conversation id), server 1:1 chat
  (`ServerChatViewModel`), notifications (`MessageNotifier`), Settings (display name, theme).
- **Superseded:** the display name was originally advertised by renaming the phone's
  Bluetooth adapter. That was removed in B4, and the name now travels in `Envelope.Profile`.
  The Room v1 → v2 migration for `deliveryStatus` was deleted under the no-migrations policy.
