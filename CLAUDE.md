# CLAUDE.md — Bluechat-KMP

BLE peer-to-peer chat app. Kotlin Multiplatform with Compose Multiplatform UI, for Android
(primary) and iOS. Read `README.md` for setup. For status and decisions, `doc/roadmap.md`
is the source of truth. `doc/plan.md` Part 1 is the architecture reference (routes, wire
protocol, GATT, schema). Its Part 2 is history.

## Build and verify

```bash
./gradlew :composeApp:assembleDebug
./gradlew :shared:testDebugUnitTest                 # commonTest, run this after any logic change
./gradlew :shared:compileKotlinIosSimulatorArm64    # run this after touching commonMain or iosMain
```

- `../kable` must exist, on local branch **`bluechat-pin`**: upstream commit `dbb7599`
  (`0.42.0` plus two commits) plus one local commit that adds the foojay resolver. It is a
  composite build, not a Maven dependency. Kable 0.43+ needs Gradle 9.5+ and Kotlin 2.3+
  (0.44+ also AGP 9), so it does not build here. Do not bump kable without bumping this
  project's toolchain too, and do not switch kable off `bluechat-pin`.
- kable needs a **JDK 17** toolchain, which Gradle downloads through that foojay commit.
  If the build says "Cannot find a Java installation ... 17", kable is not on `bluechat-pin`.
- A full clean build (Android + tests + iOS compile) takes about 11 minutes on this machine,
  so run it in the background.
- BLE behaviour can only be verified on two physical devices. Compiling and passing unit
  tests does not prove that a BLE change works, so say so instead of claiming it does.

## Platform completeness

| Feature | Android | iOS |
|---|---|---|
| Central (scan/connect/chat) | kable — device-verified | kable — compiles, not device-verified |
| Peripheral / GATT server | `AndroidBlePeripheralServer` (`BluetoothGattServer` + `BluetoothLeAdvertiser`) — device-verified | `IosBlePeripheralServer` (`CBPeripheralManager`) — not device-verified |
| MTU negotiation | `MtuNegotiation.android.kt` (`requestMtu(517)`) | `MtuNegotiation.ios.kt` (OS-negotiated) |
| Background session | `BluechatService` foreground service (`connectedDevice` type) | none (F2) |
| Notifications | `AndroidMessageNotifier`: one `MessagingStyle` notification per conversation, tap opens the chat | `IosMessageNotifier`: grouped per conversation, tap does not open the chat yet |
| Preferences | `SharedPreferences` (`bluechat_prefs`) | `NSUserDefaults` |
| Peer id in transport | MAC address | CoreBluetooth UUID |

## Architecture: what to know before changing things

- **Layers:** `presentation` (Compose + ViewModel exposing `StateFlow<UiState>`) → `domain`
  (interfaces + models) → `data` (implementations). Nearly everything lives in
  `shared/src/commonMain`. `composeApp` is only the app shell, plus the Android service.
- **Identity:** conversations and contacts are keyed on a **stable per-install peer id**
  that is exchanged in an `Envelope.Profile`, never on the transport address. The address
  maps to the peer id through `PeerDirectory`. Do not key new persistence on MAC/UUID.
- **Inbound messages:** `IncomingMessageCoordinator` is the **only** subscriber to both
  inbound flows (central and server). It persists every message once and raises
  notifications. ViewModels read from Room and must **never** collect `incomingMessages`
  themselves, because that drops messages when no screen is open (defect #4).
- **Singletons:** `BleController`, `BlePeripheralServer`, the coordinator and
  `BleSessionMonitor` are Koin `single`s that live as long as the process. ViewModels must not
  call `release()` on them in `onCleared()` (defect #10).
- **Scanning is shared:** new code holds the scan with `BleController.setScanHold(holder, true)`
  and releases only its own hold. Never call `startScan()`/`stopScan()` from anything but the
  device picker, or you stop someone else's scan (B5).
- **"On screen" is resume/pause, not ViewModel lifetime.** Anything that should only happen
  while a screen is visible (suppressing notifications, read receipts, presence scanning)
  hangs off `onVisibilityChanged`, which `App.kt` calls from `LifecycleResumeEffect`. A
  ViewModel stays alive with the app in the background.
- **Wire protocol** (`data/protocol/`): payload = JSON `Envelope` (`text`, `profile`, `read`,
  `typing`), split into frames with a 10-byte header (`Frame.kt` documents the layout),
  reassembled by `Reassembler`. Decode UTF-8 only after reassembly. Writes use
  `WriteType.WithResponse`, because the characteristic has no write-without-response.
- **GATT:** one service, one message characteristic (`BleGattProfile`). Notifications
  require the standard CCCD `0x2902`. The custom descriptor alone is not enough (defect #3).
- **Conversations** are derived from messages. The `conversations` table holds only
  pinned/muted/archived flags. A conversation records which BLE role owns it (C2).
- **Navigation:** string routes in `composeApp/.../App.kt`. The start destination is
  `conversations`.

## Database rule

**No migrations.** A schema change is a version bump in `ChatDatabase`, and Room wipes and
recreates the database. Do not write `Migration` objects. Tests seed their own data. This
is a deliberate decision with a **release gate** (F0): before any install outside the two
test phones, the current schema becomes v1 and a real migration chain starts. See
`ChatDatabase.kt` and the roadmap.

## Patterns

**Add a screen:**
1. `presentation/<feature>/` → `XUiState` (data class), `XViewModel` (exposes `state: StateFlow`),
   `XScreen(state, callbacks…)` (stateless).
2. Register it in `di/SharedModule.kt`: `viewModelOf(::XViewModel)`, or
   `viewModel { params -> XViewModel(params.get(), get(), …) }` when it takes a route argument.
3. Add a `composable("route")` in `App.kt` that calls `koinViewModel<XViewModel>()` and
   `collectAsStateWithLifecycle()`.
4. Add a ViewModel test in `shared/src/commonTest/` using fakes (see `DeviceViewModelTest`).

**Platform-specific code:** use an interface in `domain/repository/` with implementations
bound in `AndroidModule` / `IosModule`. Use `expect/actual` only for small stateless helpers
(`MtuNegotiation`, `TimeFormatter`).

## Traps

- **Kotlin functions starting with `init` are renamed for Swift.** Kotlin/Native exports
  `initKoin()` as `doInitKoin()`, because Objective-C reserves the `init` prefix. Check the
  generated `ComposeApp.h` when Swift can't find a Kotlin symbol.
- **Endless ticker flows hang `advanceUntilIdle()`.** `PresenceTracker` re-evaluates every
  5 s. Tests construct it with `tickMillis = 0`, which evaluates once.
- **Don't hard-code `JAVA_HOME` in the Xcode build phase.** It used to point at a Homebrew
  path from another machine. The phase now falls back to Android Studio's JBR.
- **Verify iOS changes with `xcodebuild`.** `compileKotlinIosSimulatorArm64` doesn't compile
  the Swift side or link the framework, so it missed both issues above.

- **Never rename the phone's Bluetooth adapter.** The display name goes over the wire in
  `Envelope.Profile`. The advertisement carries only the service UUID, because a 128-bit UUID
  leaves no room for a name in the 31-byte budget. The device's own name, if any, goes in
  the scan response (defects #7, #9, task B4).
- Android ≤ 11 needs the legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` permissions and Location on to
  scan. Do not remove the legacy manifest entries (defect #1).
- Never swallow errors in `catch {}` on BLE flows. Report them through `lastError`
  (defect #5).
- Bonded Android devices can serve a stale GATT cache, which shows up as "service not found".
  Toggling Bluetooth does not clear it; unpairing does.
- `compose.xxx` dependency accessors are deprecated but necessary. Direct Maven coordinates
  for Compose MP Material3 do not resolve.
- `kotlin.uuid` and `kotlin.time.Clock` are opted in globally in `shared/build.gradle.kts`.

## Docs upkeep

When a roadmap task is finished, update its status line in `doc/roadmap.md` (and the
"Done so far / Next" summary at the top). Update `doc/plan.md` Part 1 if routes, tables,
wire format or platform boundaries changed, and add an
entry to the Decisions log if a design choice was made. Record defects found on a device in
`doc/device-test-findings.md` with the same structure as the existing entries: where, root
cause, fix, re-verified.
