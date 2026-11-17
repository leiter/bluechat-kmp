# Bluechat-KMP — On-Device Test Findings

**Date:** 2026-11-14
**Branch:** `dev` (at `b59f670`, Phase 8 merge)
**Devices:** Pixel 7a — Android 17 / SDK 37 · Pixel 2 — Android 11 / SDK 30
**Method:** Real two-phone BLE testing over ADB (install, drive UI, logcat, Room DB dumps)

---

## Summary

At the start of testing, BLE chat was **completely non-functional** on both phones. Three
independent blocking defects were found, root-caused and fixed (commit `f738853`). After
those fixes the full round trip works: discovery → connect → client→server → server→client →
group broadcast → notifications → persistence across restarts.

Five further defects remain open. They are listed below in priority order.

---

## Fixed (commit `f738853`)

| # | Defect | Root cause |
|---|--------|-----------|
| 1 | Hard crash on Android ≤ 11; scanning silently dead | Manifest declared only API 31+ permissions; `BLUETOOTH` / `BLUETOOTH_ADMIN` missing although `minSdk = 24` |
| 2 | Every outgoing message marked `Failed` | kable's `write()` defaults to `WriteType.WithoutResponse`; the characteristic only declares `PROPERTY_WRITE` |
| 3 | Server→client delivery impossible | Message characteristic exposed a custom descriptor instead of the standard CCCD `0x2902` |

---

## Remaining defects

> **Status: all fixed and re-verified on device** (commit below). Each entry keeps the original
> diagnosis, followed by what was changed. Two further defects (#9, #10) were found while
> fixing these.

### 4. Incoming messages are dropped unless that chat screen is open — **highest priority** — FIXED

**Where:** `AndroidBlePeripheralServer.kt` (`_incomingMessages`), `ServerChatViewModel.kt` /
`ChatViewModel.kt` (`init` collectors)

`_incomingMessages` is a `MutableSharedFlow(extraBufferCapacity = 64)` with **`replay = 0`**.
Its only collector lives in `ServerChatViewModel.init`, which exists only while that specific
client's chat screen is composed. `tryEmit` on a SharedFlow with no subscriber discards the
value.

**Observed:** A message sent at 13:00:07 reached the GATT server
(`onCharacteristicWriteRequest` logged) but was never stored and never notified. The Room
database was empty. The message is lost permanently. A second message, sent while the screen
was open, arrived and persisted correctly.

**Consequence:** Phase 8's push-notification feature can only fire while the user is already
looking at that exact conversation — i.e. precisely when a notification is not needed. Any
message arriving while the app is backgrounded, on the device list, or in a different
conversation is silently lost.

**Fixed by:** new `IncomingMessageCoordinator` (`domain/messaging/`), an application-scoped
singleton started from `BluechatApp.onCreate()` / `initKoin()`. It owns the only subscription to
both inbound flows, persists every message exactly once and raises the notification. The three
chat ViewModels no longer subscribe at all — they only read from Room. Inbound server traffic is
also mirrored into the `broadcast` thread so Group Chat shows the whole conversation.

Also fixed alongside it: `KableCentralController.incomingMessages` was a `get()`-backed property
resolving `peripheral?.observe(...) ?: emptyFlow()` once at collection time. Collected before a
connection existed it returned `emptyFlow()` permanently. It is now a `flatMapLatest` over a
`StateFlow` of the current connection, and carries the device id so the coordinator knows which
conversation a payload belongs to.

**Re-verified:** with the server sitting on the Server Mode screen (not the client's chat), a
message from the Pixel 2 was persisted to both `40:4E:36:A1:A6:5A` and `broadcast`, and raised a
notification. Before the fix this exact scenario lost the message silently.

---

### 5. Scan and connect errors are swallowed — FIXED

**Where:** `KableCentralController.kt:79` — `.catch { /* scan ended or error */ }`

The scan flow's `catch` discards the throwable. This is why defect #1 presented as "scanning
does nothing" with no error in the UI *or* in logcat — the hardest possible failure to
diagnose. Connection failures behave the same way: after the 3 retry attempts are exhausted
the UI shows only a red status dot with no explanation.

**Fixed by:** `BleController` now exposes `lastError: StateFlow<String?>`. The scan `catch`
records the throwable instead of discarding it, and an exhausted connect records a reason.
`DeviceViewModel` and `ChatViewModel` fold it into their UI state, and both screens render it.
A "service not found" failure gets a specific message naming the stale-GATT-cache cause and the
unpair recovery, since the raw exception tells the user nothing.

**Re-verified:** a failed connect now reads *"Connection failed after 3 attempts: Disconnect
detected"* on the chat screen instead of showing only a silent red dot.

---

### 6. `DeviceScreen` is not wrapped in a `Scaffold`/`Surface` — FIXED

**Where:** `DeviceScreen.kt:36` — a bare `Column` with `padding(16.dp)`

Two user-visible symptoms from one cause:

- **The settings gear is untappable at its visual centre.** The top row is drawn under the
  system status bar (no inset handling), so the status bar consumes the touch. Tapping ~50 px
  lower was required to open Settings.
- **The screen renders light while every other screen is dark.** With no themed `Surface`
  background, the light `windowBackground` from `styles.xml` shows through.

**Fixed by:** wrapping the screen in a `Scaffold` with a `TopAppBar` that carries the title and
the settings action, matching every other screen.

**Re-verified:** the screen is now dark and sits below the status bar, and the gear opens
Settings when tapped at its visual centre.

---

### 7. Display name does not apply until the app is restarted — FIXED

**Where:** `AndroidBlePeripheralServer.kt` — `currentDisplayName`, `startAdvertising()`

The preference saves correctly (verified in `shared_prefs/bluechat_prefs.xml`), and after an
app restart the new name *is* advertised. Within a single session it is not.

Two contributing problems:

- `bluetoothAdapter.name = currentDisplayName` is applied asynchronously by the Bluetooth
  stack, but `startAdvertising()` builds and starts the advertisement immediately afterwards,
  so the payload still carries the old name.
- `currentDisplayName` is a non-`@Volatile` `var` written from a `Dispatchers.Default`
  coroutine and read from the main thread — no happens-before guarantee.

**Also note:** this writes the **phone's system-wide Bluetooth name**. During testing both
phones were silently renamed to "Bluechat" and stayed that way after the app was closed. The
original name should be restored when advertising stops.

**Fixed by:** `currentDisplayName` is now `@Volatile`; `startAdvertising()` sets the adapter name
and waits (polling, capped at ~1s) for the stack to report it back before building the
advertisement; `stopAdvertising()` restores the adapter name that was in place before we first
overwrote it.

**Re-verified:** changing the name and starting the server **without restarting the app** now
advertises the new name, and stopping the server puts the phone's original name back.

---

### 8. Keyboard pans the whole window instead of resizing — FIXED

**Where:** `MainActivity` (no `windowSoftInputMode`), `ChatScreen.kt` (no IME insets)

When the IME opens, the entire window is panned upward: the top app bar (with the
Disconnect button and connection status dot) and the message list scroll off-screen. During
testing this made a sent message appear not to render at all until the keyboard was dismissed.

**Fixed by:** `android:windowSoftInputMode="adjustResize"` on `MainActivity` plus
`Modifier.imePadding()` on the chat content column.

**Re-verified:** with the keyboard open the top bar and the message list both stay on screen.

---

## Found while fixing the above

### 9. Any display name longer than ~8 characters broke advertising outright — FIXED

**Where:** `AndroidBlePeripheralServer.startAdvertising()`

Masked by defect #7: because the name never actually changed from the 8-character default
"Bluechat", the advertisement happened to fit. The moment a real name was applied, advertising
failed with `ADVERTISE_FAILED_DATA_TOO_LARGE` (code 1) and Server Mode just showed "Error".

A 128-bit service UUID consumes 18 of the 31 advertisement bytes, leaving no room for the name.

**Fixed by:** the name moved to the **scan response**, which has its own 31-byte budget, while
the service UUID stays in the advertisement so scan filters still match. The name is also
clamped to 26 characters. `AdvertiseCallback.onStartFailure` codes are now mapped to readable
text and surfaced through a new `BlePeripheralServer.lastError`, which `ServerViewModel` shows
instead of the hardcoded "Failed to start server".

**Re-verified:** a 24-character display name advertises successfully and is discovered in full
by the other phone.

### 10. ViewModels released application-scoped singletons — FIXED

**Where:** `DeviceViewModel.onCleared()`, `ServerViewModel.onCleared()`

Both called `release()` on their injected dependency. `BleController` and `BlePeripheralServer`
are Koin `single`s, so navigating away from the device list or the server screen cancelled the
singleton's `CoroutineScope` for the remaining life of the process, leaving scanning and
messaging permanently dead.

**Fixed by:** removing both `onCleared()` overrides, with a comment explaining why.

**Re-verified:** scanning still finds the peer after opening a chat and navigating back.

---

## Environmental notes — not app bugs, but worth handling

- **Stale GATT cache on bonded devices.** The two phones are bonded, so Android served a
  cached GATT database (17 ms "discovery" vs 1.6 s for a real one) that predated Bluechat's
  service, and the central could not find it. Toggling Bluetooth does **not** clear this for a
  bonded device. Real users will hit this; consider the `BluetoothGatt.refresh()` workaround
  or documenting unpair-to-recover.
- **Location services must be on for BLE scanning below API 31.** Location was off on the
  Pixel 2 and scanning returned zero results. Now that defect #5 is fixed the scan failure is at
  least reported instead of looking like an empty room, but the app still does not check the
  location-services setting up front.

---

## Found while implementing E1 (2026-11-17)

### 11. Leaving the Server Mode screen stopped the server — FIXED

**Where:** `App.kt`, the `"server"` destination's `onBack`

`onBack` called `viewModel.stopServer()` before popping the back stack, so backing out of
Server Mode tore down advertising. Harmless while nothing survived backgrounding anyway;
directly at odds with a foreground service that exists to keep the session alive.

**Fixed by:** `onBack` now only pops. Stopping is explicit -- the Stop Server button or the
notification's Stop action.

**Re-verified:** with the server running, pressing Back and then Home left
`isForeground=true types=0x00000010` and the Pixel 2 still discovered and connected to the
backgrounded app.

### 12. The scan list is never cleared between scans — OPEN, minor

**Where:** `DeviceViewModel` / `KableCentralController.scannedDevices`

After the server stopped advertising, a fresh scan on the Pixel 2 still listed "Bluechat" at
-57 dBm. Force-stopping the app and scanning again returned nothing, confirming the entry was
stale state rather than a live advertisement.

**Consequence:** the device list shows peers that are no longer reachable, and tapping one
leads to a connect attempt that can only fail. Worth clearing on `startScan()`, and worth
ageing entries out by `lastSeenAt` once B5 (presence) lands.

---

## Platform behaviour worth knowing

- **Android 14+ lets users swipe away a foreground-service notification** without stopping the
  service. Observed directly: the "Bluechat is active" notification was dismissed while
  `dumpsys` still reported `isForeground=true`, and it reappeared on the next session change.
  The service is not harmed by this, but the user can lose their only visible handle on it, so
  E5 (connection-state UX) should surface session state in-app as well.
