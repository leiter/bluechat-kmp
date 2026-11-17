# Bluechat-KMP — Roadmap to a Standard Chat Application

**Date:** 2026-11-17
**Branch:** `dev` (at `8f85e6a`)
**Starting point:** BLE messaging works end to end on Android — discovery, connect, both
directions, group broadcast, notifications and persistence (see
[`device-test-findings.md`](device-test-findings.md)). What exists is a working BLE transport
demo. This document lists what stands between that and an application people would recognise
as a chat app.

---

## Known blocker — RESOLVED in Phase A

> **Update 2026-11-17:** fixed and measured. The MTU is now negotiated to 517 and payloads are
> framed, so messages are no longer capped. Verified on device: a 330-character message
> client→server and a 720-character message server→client (the latter genuinely multi-frame)
> both arrived intact. The original diagnosis follows.

**There is no MTU negotiation and no chunking anywhere in the codebase.** A grep for
`mtu|chunk|requestMtu` across `shared/src` returns nothing. The default ATT MTU is 23, which
leaves **20 usable bytes per write/notify**.

Every message used in testing so far has been under 20 characters ("DroppedMsgTest",
"ServerReplyOK", "MsgFromPixel2"), so this path has never actually been exercised. An attempt
to confirm the failure mode with a 60-character message did not complete, so the exact
behaviour (truncation vs. rejection) is **unconfirmed** — but the absence of any framing code
is certain.

Assume a ~20-character message ceiling until proven otherwise. This is the single largest gap
between the current state and a usable chat app, and it is addressed by task A2 below.

---

## Phase A — Foundations — DONE

Everything else depends on these. They are one protocol change and should be done together:
retrofitting them later means a second schema and wire-format migration.

**Delivered.** `data/protocol/` holds the framing (`Frame`, `Reassembler`) and the wire
envelope (`Envelope`, `ProtocolCodec`), covered by 17 unit tests — the project's first. A
`peers` table plus `PeerDirectory` keeps the stable-identity mapping, added by migration
v2→v3. Both BLE roles negotiate the MTU and chunk accordingly, and both announce their
identity as soon as the peer can actually hear it.

### A1. Stable peer identity — done

Conversations are currently keyed on the transport address — MAC on Android, an opaque UUID on
iOS. That identity is wrong in three distinct ways:

- it changes under Android's Bluetooth address randomisation,
- it cannot be correlated across platforms, so an Android and an iOS peer can never recognise
  each other,
- the same person reappears as a brand-new "device", fragmenting their history.

Generate a per-install UUID, exchange it on connect over a dedicated characteristic (the
blueprint profile already reserves `CHARACTERISTIC_VERSION_UUID` and `DESCRIPTOR_*_UUID`), and
key all persistence on that instead of on the address.

### A2. MTU negotiation and message framing — done

Request a larger MTU on connect, then chunk any payload above the negotiated size:

- frame header carrying `{messageId, seq, total}`,
- reassembly buffer keyed by `messageId`,
- timeout and discard for incomplete sets.

Note that `decodeToString()` applied to an arbitrary chunk boundary will corrupt multi-byte
UTF-8. Reassemble the bytes first, decode once at the end.

### A3. A real wire envelope — done

The payload currently *is* the message text — there is nowhere to put anything else. Introduce
an envelope serialized with kotlinx.serialization:

```
type      text | receipt | typing | profile | ack
messageId UUID
timestamp epoch millis
senderId  stable peer id from A1
body      type-dependent
```

This is what makes receipts, typing indicators, profile exchange and de-duplication possible.
A3 gates most of Phase D.

---

## Phase B — Contacts

| # | Task | Notes |
|---|------|-------|
| B1 | `Contact` entity + Room table | `contactId` (stable id from A1), local `displayName` override, `lastSeen`, `isBlocked`, `isFavourite`, avatar seed. Needs a v2→v3 migration. |
| B2 | Add-contact flow | From a discovered device; auto-create on first successful exchange. |
| B3 | Contacts screen | List, search, rename, delete, block. |
| B4 | Profile exchange | Send own display name on connect; store as the peer's *claimed* name, with the local nickname taking precedence. |
| B5 | Presence | In-range / out-of-range derived from scan results plus connection state. |

B4 also removes the current need to hijack the **system-wide Bluetooth adapter name** as the
identity broadcast — see defect #7 in the test findings.

---

## Phase C — Conversations

| # | Task | Notes |
|---|------|-------|
| C1 | `Conversation` entity | `lastMessageAt`, preview, `unreadCount`, pinned / muted / archived. |
| C2 | **Chat list becomes the home screen** | `startDestination` is currently `"devices"` — a scanner. A chat app opens on conversations; scanning becomes a "new chat" action. |
| C3 | Unread tracking | Mark read on visibility, badge counts. |
| C4 | Pagination | `ChatMessageDao` returns the entire history as one `Flow<List<…>>` into a `LazyColumn`. Fine at 3 messages, not at 3,000. Room `PagingSource` + Paging 3. |
| C5 | Delete conversation / clear history | `deleteMessagesForDevice` already exists and is unused. |

---

## Phase D — Message features

- **D1. Delivery and read receipts.** `DeliveryStatus` already has `Sending`/`Sent`/`Failed`;
  add `Delivered` and `Read`, driven by receipt frames. Requires A3.
- **D2. Outbox and retry.** Queue failed sends, flush on reconnect, tap-to-retry.
- **D3. Day separators, message grouping, proper time formatting.**
- **D4. Typing indicator.** Requires A3.
- **D5. Long-press actions** — copy, delete, reply-quote.
- **D6. Image attachments** with chunked transfer and progress. Scope this carefully: BLE
  throughput is roughly 1–10 KB/s, so a photo takes tens of seconds. The `BluetoothLEChat`
  project in this repo solved exactly this by negotiating over BLE and switching to RFCOMM for
  bulk transfer; that pattern is worth copying rather than reinventing.

---

## Phase E — Lifecycle

This is what makes the app *feel* like a chat app rather than a demo.

- **E1. Foreground service** owning the GATT server and active connections. Today everything
  lives in Activity-scoped objects, so backgrounding the app stops messaging entirely. This is
  the biggest gap after identity.
- **E2. Auto-reconnect to known contacts.** Background scan, connect on sight, drain the outbox.
- **E3. Simultaneous central + peripheral.** Currently a mode switch — one phone must be
  explicitly told to be "Server Mode". A standard chat app is both roles at once, so any two
  installs simply talk. This is a genuine architectural change and should be decided early,
  because Phases B and C assume symmetry.
- **E4. Notification fixes.** `notificationId++` means every message spawns a separate
  notification, and there is no `contentIntent`, so tapping one does nothing. Use one
  notification per conversation, `MessagingStyle`, a deep link into that chat, and direct reply.
- **E5. Connection-state UX** — reconnecting banner, offline indicator.

---

## Phase F — Hardening

- **F1. Trust and encryption.** The GATT service is unauthenticated: any app that knows the
  service UUID can connect and write into a conversation. A pairing handshake plus end-to-end
  encryption (X25519 + ChaCha20-Poly1305) is table stakes before this carries real
  conversations.
- **F2. iOS parity.** Run the same two-device matrix cross-platform, add Info.plist usage
  strings, enable BLE background modes.
- **F3. Tests.** There are none. Fakes for `BleController` and `BlePeripheralServer`, ViewModel
  tests with Turbine, and a Room migration test — the v1→v2 migration has never been exercised.

---

## Recommended order

1. ~~**A1 + A2 + A3 as a single piece of work.**~~ Done.
2. **E1 (foreground service).** Makes messages arrive when the user is not staring at the app.
   This is now the largest remaining gap.
3. **B + C.** The visible chat-app shape, built on a foundation that will not need redoing.

## Decisions

### E3 — symmetric peer: NO (decided 2026-11-17)

The app keeps the **asymmetric** model: one device is explicitly put into Server Mode and the
other scans and connects to it. Phase A did not change this — the protocol layer is
role-agnostic, but the roles themselves stay distinct.

Consequences to keep in mind for later phases:

- Two installs cannot talk until somebody volunteers as the server; that is accepted.
- Phase B/C must not assume a conversation can be opened from either side at will. The
  contact list and chat list need to show that a peer is only reachable when one end is
  advertising.
- **E3 is therefore struck from Phase E**, and E2 (auto-reconnect) means "the client
  reconnects to a known server", not a mutual rendezvous.
