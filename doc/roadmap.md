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
| B1 | `Contact` entity + Room table | **DONE (2026-11-17).** `contacts` (schema v7) is the Phase A `peers` table grown up; `favorite_devices` is gone, absorbed rather than kept alongside. |
| B2 | Add-contact flow | From a discovered device; auto-create on first successful exchange. |
| B3 | Contacts screen | **DONE (2026-11-18).** List, search, rename, favourite, block, delete. Reached from the chat list. |
| B4 | Profile exchange | **DONE (2026-11-18).** The wire half already existed; B4 removed the adapter-name hijack that it made unnecessary (defect #7). |
| B5 | Presence | In-range / out-of-range derived from scan results plus connection state. |

Phase 9 (merged) delivered a `favorite_devices` table with a star control and a Favourites
section on the device list. That was not B1: it keyed on the **transport address**, not the
stable peer id from A1, so a favourite did not survive address randomisation. B1 absorbed it
rather than leaving it alongside -- there is now one row per identity and no second table.

**What B1 changed, beyond the table.**

- Two name columns, not one. `announcedName` is what the peer *claims* (arrives over the air,
  untrusted); `nickname` is what *we* call them locally and always wins. The resolved name is
  computed in the domain model, never stored, so a third copy cannot go stale. B4 then becomes
  pure wiring: it fills `announcedName` and touches nothing else.
- `ContactDao.touch` is an explicit `ON CONFLICT DO UPDATE` rather than `@Upsert` with an
  entity. An entity upsert would reconstruct `nickname`, `isFavourite` and `isBlocked` from
  packet data and clobber the columns the *user* owns, and the previous read-then-write had a
  window in which a favourite toggled between the two halves was lost.
- `isBlocked` is enforced in `IncomingMessageCoordinator`, the one process-wide inbound entry
  point: a blocked contact's message is not stored and raises no notification. The flag is real
  rather than decorative, even though B3 has yet to expose a way to set it.
- **A star is only offered for a device we have already met.** A favourite stored against a
  stranger would have nothing stable to attach to, which is the original defect. Strangers get
  no star; the affordance appears once an identity has been announced. Verified on device.

No migration was written: the version was bumped 6 -> 7 and the database recreated empty.

**B4 turned out to be mostly already built.** Both roles were already exchanging an
`Envelope.Profile` on connect, and B1 had already split the claimed name from the local
nickname. What remained was the consequence: the app no longer needs the advertisement to carry
an identity, so it stopped hijacking the **system-wide Bluetooth adapter name** — defect #7.

That hijack set `bluetoothAdapter.name`, the name the phone shows in car head units and every
other device's pairing list, to this app's display name, restoring it on stop. If the process
died while advertising, it was never restored at all. It is gone: the scan response now carries
the phone's own name, unmodified, and identity travels over GATT where it belongs.

Two consequences worth recording:

- The advertised name is now the user's, so it can be any length. Rather than refuse to
  advertise when it does not fit the 31-byte scan response, the server retries without the
  name; a peer learns who we are on connect anyway.
- Scanning preferred the OS's **cached** name for an address over the one being broadcast right
  now. That was invisible while we controlled the adapter name, and became wrong the moment we
  stopped: a server advertising "Pixel 2" still listed as "Bluechat" on a phone that had seen
  the old build. The scan record now wins, with the cache as fallback.

---

## Phase C — Conversations

| # | Task | Notes |
|---|------|-------|
| C1 | `Conversation` entity | **DONE (2026-11-18).** Schema v8. Holds *only* pinned/muted/archived; `lastMessageAt`, preview and `unreadCount` are derived, not stored. |
| C2 | **Chat list becomes the home screen** | **DONE (2026-11-18).** `startDestination` is `"conversations"`; the scanner is now a "New chat" picker. |
| C3 | Unread tracking | **PARTLY DONE (2026-11-18).** Badge counts and per-row unread ship with C1; both chat screens now mark read on open. Still missing: server-side read *receipts*, so the peer is not told. |
| C4 | Pagination | `ChatMessageDao` returns the entire history as one `Flow<List<…>>` into a `LazyColumn`. Fine at 3 messages, not at 3,000. Room `PagingSource` + Paging 3. |
| C5 | Delete conversation / clear history | **DONE (2026-11-18).** Long-press a chat → Delete. One transaction over messages, outbox and flags. |

---

## Phase D — Message features

- **D1. Delivery and read receipts.** **MOSTLY DONE** (Phase 9, merged). `DeliveryStatus` now
  carries `Queued`/`Delivered`/`Read`, and receipts ride the framed protocol as
  `Envelope.ReadReceipt`. Verified round trip: a message the server sent shows `Read` with
  `readAt` set once the client has it on screen. Still missing: `Delivered` is never set (only
  `Sent` then `Read`), and the server side does not send receipts for what it receives, so
  acknowledgement is client-to-server only.
- **D2. Outbox and retry.** **DONE** (Phase 9, merged). `pending_messages` plus
  `MessageQueueRepository`; the queue drains on reconnect. No tap-to-retry yet.
- **D3. Day separators, message grouping, proper time formatting.** **DONE** (Phase 9, merged)
  for separators and relative times; grouping is still outstanding.
- **D4. Typing indicator.** **DONE** (Phase 9, merged) as `Envelope.Typing`. Client-to-server
  only, and deliberately never persisted. Not wired into the server's chat screens.
- **D5. Long-press actions** — copy, delete, reply-quote.
- **D6. Image attachments** with chunked transfer and progress. Scope this carefully: BLE
  throughput is roughly 1–10 KB/s, so a photo takes tens of seconds. The `BluetoothLEChat`
  project in this repo solved exactly this by negotiating over BLE and switching to RFCOMM for
  bulk transfer; that pattern is worth copying rather than reinventing.

---

## Phase E — Lifecycle

This is what makes the app *feel* like a chat app rather than a demo.

- **E1. Foreground service** owning the GATT server and active connections. **DONE
  (2026-11-17).** `BluechatService` (`composeApp/androidMain/service/`) runs with
  `foregroundServiceType="connectedDevice"` for as long as a session is live, driven by the
  new `BleSessionMonitor` in `commonMain`. The service owns no BLE state: it watches one
  folded `StateFlow`, mirrors it into an ongoing notification, and stops itself the moment the
  session goes idle. Leaving the Server Mode screen no longer stops the server.
- **E2. Auto-reconnect to known contacts.** **MOSTLY DONE** (Phase 9, merged). Five attempts on
  an exponential backoff, re-scanning when the advertisement has gone stale, draining the
  outbox on success. The merge added MTU renegotiation and a re-announced identity on
  reconnect, without which every later message silently fell back to 20-byte frames. Still
  client-initiated only: there is no background scan when the app is not already connected.
- **E3. Simultaneous central + peripheral.** Currently a mode switch — one phone must be
  explicitly told to be "Server Mode". A standard chat app is both roles at once, so any two
  installs simply talk. This is a genuine architectural change and should be decided early,
  because Phases B and C assume symmetry.
- **E4. Notification fixes.** `notificationId++` means every message spawns a separate
  notification, and there is no `contentIntent`, so tapping one does nothing. Use one
  notification per conversation, `MessagingStyle`, a deep link into that chat, and direct reply.
- **E5. Connection-state UX** — **PARTLY DONE** (Phase 9, merged): a reconnecting banner and a
  signal-strength indicator driven by RSSI polling. Still missing an in-app view of session
  state, which matters because Android 14+ lets the user swipe the foreground-service
  notification away (see the test findings).

---

## Phase F — Hardening

- **F0. Database durability.** Migrations, a schema snapshot per version, populated `.db`
  fixtures, and a build-variant split so the destructive wipe is debug-only. **Starts when
  release builds and intensive testing start — not before** (decided 2026-11-17). Full
  description under *F0 — database durability* in Decisions.

- **F1. Trust and encryption.** The GATT service is unauthenticated: any app that knows the
  service UUID can connect and write into a conversation. A pairing handshake plus end-to-end
  encryption (X25519 + ChaCha20-Poly1305) is table stakes before this carries real
  conversations.
- **F2. iOS parity.** Run the same two-device matrix cross-platform, add Info.plist usage
  strings, enable BLE background modes.
- **F3. Tests.** There are none. Fakes for `BleController` and `BlePeripheralServer`, ViewModel
  tests with Turbine, and **seeded database fixtures** — tests that wipe, fill the tables with
  the preconditions under test, and assert against them. This replaces the Room migration test
  that used to sit here, which is void now that there is no migration chain to exercise. What
  remains is a release gate, not a test: a genuine chain must be built before the first install
  outside the two test phones.

---

## Recommended order

1. ~~**A1 + A2 + A3 as a single piece of work.**~~ Done.
2. ~~**E1 (foreground service).**~~ Done.
3. **B + C.** The visible chat-app shape, built on a foundation that will not need redoing.
   This is now the next piece of work.

---

## Deferred — consciously skipped, not forgotten

Tracked in claude-mem work state as `deferred-debts`, so it resurfaces at session start.

| Item | Why it is parked | What would un-park it |
|------|------------------|-----------------------|
| **Database durability: migrations, schemas, snapshots** | Not a concern while the only installs are the two test phones. | **F0 — release preparation.** See below; deliberately not touched before then. |
| **C4 pagination** | A whole-history `Flow<List<…>>` into a `LazyColumn` is fine at 30 messages. | Histories in the hundreds, or a visible scroll stutter. |
| **Seeded database fixtures** | Parked indefinitely (user, 2026-11-18). Every test builds its world from fakes; no test opens a real Room database. | No date. Revisit only if something a fixture would have caught actually bites — the `ConversationDao` summary join is the exposed surface. |
| **Defect #12** | Scan list never cleared between scans; lives in the picker. | Still open after C2. |
| **`DeliveryStatus.Delivered`** | Never set; the status jumps `Sent` → `Read`. | Needs a transport-level ack distinct from the read receipt. |
| **Server-side read receipts** | Acknowledgement is client-to-server only. | E3 was decided *against*, so the asymmetry is permanent and this is a real gap. |
| **Typing indicator on server screens** | Wired client-side only. | Cheap; fold into B/C when touching those screens. |
| **D3 message grouping, D2 tap-to-retry** | Separators and relative times shipped; these did not. | Polish pass after C. |

---

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

### E1 — the server keeps advertising while backgrounded (decided 2026-11-17)

Android requires a persistent notification for any process that holds the radio in the
background, so the question was whether to accept one. We did.

- A chat app that only receives while you are looking at it is not a chat app, so the server
  keeps advertising and connections stay open after the app leaves the screen.
- The cost is an ongoing "Bluechat is active" notification on the low-importance
  `bluechat_session` channel. It is silent, badge-free, carries a **Stop** action and a tap
  target into the app, and it names what is actually happening ("Discoverable, 1 connected").
- Stopping is now explicit -- the Stop Server button or the notification action -- rather than
  a side effect of navigating back.
- The service does **not** restart itself after a process kill (`START_NOT_STICKY`). The GATT
  server and any connection are gone by then, so restarting would only put up a notification
  for a session that no longer exists.

Note for E2: this is what auto-reconnect will hang off. Starting the service from a background
trigger rather than a tap will run into the API 31+ background-start restriction, which the
current launcher sidesteps because every session today begins with a user action.

### Merge with Phase 9 (2026-11-17)

`origin/dev` had grown a Phase 9 from the same `b59f670` base: offline queue, read receipts,
typing indicators, auto-reconnect, favourites, signal quality and 23 tests. Merged here.

**Wire protocol: one had to go.** Phase 9's `BleMessageProtocol` (a one-byte type tag plus
`"uuid|text"`) was removed in favour of `data/protocol/`. It has no framing and no MTU
negotiation, so keeping it would have reinstated the ~20-byte ceiling that Phase A existed to
remove. Its two features were ported onto the framed protocol as `Envelope.ReadReceipt` and
`Envelope.Typing`, so nothing Phase 9 could do was lost.

**Schema.** Both branches had written a different `MIGRATION_2_3`. The published chain won and
the peer directory moved to 5->6; the database is now version 6 with all four tables.

**Three regressions were kept out.** Phase 9 predates the blocker fixes, so its
`write()` calls still use kable's `WithoutResponse` default, its `ChatViewModel` still
collects inbound messages per-screen, and its `DeviceViewModel.onCleared()` still calls
`release()` on an application-scoped singleton. The merged code keeps the fixed forms.

### Schema changes: wipe, never migrate (decided 2026-11-17)

The five hand-written `Migration` objects (v1 through v6) were **deleted**, and
`createDatabase` now registers `fallbackToDestructiveMigration(dropAllTables = true)` plus the
same on downgrade. From here a schema change is a version bump and nothing else: Room drops
every table and recreates the database empty, and tests seed the preconditions they need.

Why, on the user's instruction: the project is pre-release and the only two installs are the
test phones, which are `pm clear`ed routinely. The chain protected nobody. It was also already
partly fiction — Room had never exported schemas `3.json` or `4.json`, so two of its six links
had never been validated — and it had already cost real work, since Phase A and Phase 9 each
independently claimed version 3 and the collision had to be untangled during the merge.

Every exported snapshot is **kept** (`1`, `2`, `5`, `6`), and export stays on. They were
briefly deleted as dead weight and then restored on the user's instruction, which is the right
call: a snapshot per version is what makes the chain testable once it is rebuilt. `3` and `4`
were never generated by Room at all and would have to be reconstructed.

The cost is recorded in **Deferred** above as a release gate, and it is a hard one: with no
chain, the first external install cannot ever be upgraded in place.

### F0 — database durability (release preparation only)

**Scope note (decided 2026-11-17):** none of this is current work. Migrations, per-version
schemas and populated snapshots are picked up **when release builds and intensive testing
start**, and not before. Until then a schema change stays a version bump plus a wipe, and
tests seed what they need. The point of writing it down now is so the shortcut is a recorded
decision with a known price, rather than something discovered late.

What F0 consists of

:

**1. The wipe is unconditional, and must not stay that way.** `createDatabase` registers
`fallbackToDestructiveMigration` for every build, so a **release** APK would erase a real
user's conversations exactly as silently as a debug one does on the test phones. Making it
debug-only is not a one-line change: the value has to be visible from `commonMain`, and this
project has no `buildSrc` or `build-logic`, while Android's `BuildConfig` cannot be reached
from common code. The intended shape is the project's **first KMP convention plugin**,
generating an `isDebug` / `BuildVariant` into a common source set, after which:

| Variant | Behaviour |
|---------|-----------|
| debug | `fallbackToDestructiveMigration`, as now — wipe freely, reseed from fixtures. |
| release | `addMigrations(…)` only. A **missing migration fails the build** rather than quietly degrading into a wipe. |

That last clause is the point of the whole exercise: the failure mode we are avoiding is not
"no migration written", it is "no migration written and nobody noticed".

**2. Keep a populated database snapshot per schema version.** Two artefacts that are easy to
confuse:

- **Exported schema JSON** — Room already writes these to `shared/schemas/`. `1`, `2`, `5` and
  `6` are present. `3` and `4` were never generated, so they would have to be reconstructed.
- **A populated `.db` file per version**, committed as a fixture. This earns its keep twice
  over: migrating a real old database forward is the only honest test of a chain, and a
  ready-made database with messages, peers and contacts already in it spares every test from
  seeding its own preconditions — which is the testing approach this phase is relying on.

Snapshots must be produced by a reproducible fixture builder rather than pulled off a handset.
A database copied from a device reads **empty** unless its `-wal` file is checkpointed in
first; that already cost us an investigation during the E1 device testing.

### C1 — the conversation list is derived, not stored (decided 2026-11-18)

`conversations` holds **only** the three flags a user sets: pinned, muted, archived. The things
a chat list usually caches -- `lastMessageAt`, the message preview, the unread count -- are
computed by `ConversationDao.observeSummaries` on every read instead.

The reason is that storing them means writing two tables on every message and then owning the
bug where they disagree. A derived list cannot drift from the history it summarises, and there
is no "create the conversation" step to forget: a conversation exists because it has messages.
The flags row is created lazily, the first time a flag is set, which is why the query LEFT
JOINs it — an absent row means "all three false", not missing data.

If that query ever becomes the bottleneck, caching it is a measurable optimisation to make
then. At the current scale it is not one, and the roadmap's own C4 note about history size is
the signal to revisit.

### C2 — a conversation has to know which BLE role owns it (found on device, 2026-11-18)

Making the chat list the home screen exposed something the old navigation hid. The list routes
by conversation id, but the two BLE roles are deliberately asymmetric (see the E3 decision), and
a conversation with a peer that dialled *us* can only be replied to through the peripheral role.

Routing everything to the client chat screen looked right -- the history rendered perfectly --
and would have failed every reply, because the central-role connection does not exist on the
server. It showed up on device as a red "disconnected" dot on a screen that had just displayed
a live conversation.

So `Conversation` carries `peerTransportId`, and the list checks it against the server's
connected clients to choose the screen. Verified both directions on two phones: client to
server, then a reply from the server's own chat list back to the client.

This also forced **defect #14** to be fixed rather than deferred. The `broadcast` mirror ran for
both roles, which was a harmless duplicate row while a client had no group screen to show it --
but the chat list would have surfaced it as a phantom "Group Chat" conversation on a device with
no group. Inbound messages now carry their role, and only the server mirrors.

### C5 — there is no "clear history" separate from "delete chat" (2026-11-18)

The roadmap listed these as one item, and building it showed they are the same operation.

Because C1 made the chat list **derived from the messages**, a conversation with no messages
does not appear at all. Clearing a conversation's history therefore removes it from the list;
there is no empty-but-present conversation to leave behind. So the app offers one action,
Delete, rather than two that differ only in a distinction the data model cannot express.

What it removes, in a single transaction: the messages, anything still queued in the outbox for
that conversation, and the conversation's own flags row. The outbox matters -- deleting the
messages but not the queue would leave a conversation that has vanished from the UI while still
trying to transmit.

The **contact is kept**, mirroring B3's contact delete, which keeps the messages. Each action
removes only what the user pointed at, and the confirmation dialog says which is which.

That pairing also closes a dead end B3 created: deleting a contact leaves its conversation
behind, titled by a raw peer id. Until C5 there was no way to remove it.

One wording bug worth recording, caught on device: the dialog reassured the user that the
contact "stays in your contacts" unconditionally -- including for exactly the orphaned
conversation whose contact had already been deleted, which is the case where the title is a raw
id. It now checks `isKnownContact` and says the peer is not in contacts instead.
