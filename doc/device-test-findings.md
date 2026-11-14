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

### 4. Incoming messages are dropped unless that chat screen is open — **highest priority**

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

**Fix direction:** Persist incoming messages at the source, not in the ViewModel. A long-lived
singleton collector (started at app init) should write to Room and raise notifications;
ViewModels should then only *read* from Room.

---

### 5. Scan and connect errors are swallowed

**Where:** `KableCentralController.kt:79` — `.catch { /* scan ended or error */ }`

The scan flow's `catch` discards the throwable. This is why defect #1 presented as "scanning
does nothing" with no error in the UI *or* in logcat — the hardest possible failure to
diagnose. Connection failures behave the same way: after the 3 retry attempts are exhausted
the UI shows only a red status dot with no explanation.

**Fix direction:** Surface the error into `DeviceUiState` / `ChatUiState` and render it. Log
the throwable at minimum.

---

### 6. `DeviceScreen` is not wrapped in a `Scaffold`/`Surface`

**Where:** `DeviceScreen.kt:36` — a bare `Column` with `padding(16.dp)`

Two user-visible symptoms from one cause:

- **The settings gear is untappable at its visual centre.** The top row is drawn under the
  system status bar (no inset handling), so the status bar consumes the touch. Tapping ~50 px
  lower was required to open Settings.
- **The screen renders light while every other screen is dark.** With no themed `Surface`
  background, the light `windowBackground` from `styles.xml` shows through.

---

### 7. Display name does not apply until the app is restarted

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

---

### 8. Keyboard pans the whole window instead of resizing

**Where:** `MainActivity` (no `windowSoftInputMode`), `ChatScreen.kt` (no IME insets)

When the IME opens, the entire window is panned upward: the top app bar (with the
Disconnect button and connection status dot) and the message list scroll off-screen. During
testing this made a sent message appear not to render at all until the keyboard was dismissed.

---

## Environmental notes — not app bugs, but worth handling

- **Stale GATT cache on bonded devices.** The two phones are bonded, so Android served a
  cached GATT database (17 ms "discovery" vs 1.6 s for a real one) that predated Bluechat's
  service, and the central could not find it. Toggling Bluetooth does **not** clear this for a
  bonded device. Real users will hit this; consider the `BluetoothGatt.refresh()` workaround
  or documenting unpair-to-recover.
- **Location services must be on for BLE scanning below API 31.** Location was off on the
  Pixel 2 and scanning returned zero results. The app should detect this and tell the user
  (which it cannot currently do — see defect #5).
