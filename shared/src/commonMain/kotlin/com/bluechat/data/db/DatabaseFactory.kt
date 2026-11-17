package com.bluechat.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * No migrations are registered, by design -- see the schema policy on [ChatDatabase].
 *
 * `dropAllTables = true` makes the wipe total rather than leaving orphaned tables behind,
 * which matters because tables get *removed* in this phase (B1 absorbs `favorite_devices`).
 *
 * This single call also covers downgrades, and it must stay a single call. Adding
 * `fallbackToDestructiveMigrationOnDowngrade` alongside it looks additive but is not: both
 * write Room's one `requireMigration` flag, and the downgrade variant sets it back to `true`.
 * Having both silently disabled the fallback entirely, and the app died on launch with
 * "A migration from 6 to 7 was required but not found" -- the very error this is here to
 * prevent. Caught on a device, not by the test suite, which never opens an older database.
 *
 * TODO(F0, release prep only): this is unconditional, so a **release** build would wipe user data
 *  just as silently as a debug one. The wipe must become debug-only before anything ships.
 *  That needs a build-variant value visible from `commonMain`, which this project cannot
 *  currently express: there is no `buildSrc` or `build-logic`, and Android's `BuildConfig` is
 *  not reachable from common code. The intended shape is a KMP convention plugin that
 *  generates an `isDebug` (or a `BuildVariant` enum) into a common source set, after which:
 *
 *    - debug   -> `fallbackToDestructiveMigration`, as now;
 *    - release -> `addMigrations(...)` only, with a **missing migration failing the build**
 *                 rather than degrading into a wipe.
 *
 *  Tracked in claude-mem `deferred-debts`, and in the roadmap's Deferred section as a
 *  release gate.
 *
 * TODO(F0, release prep only): keep a populated `.db` snapshot per schema version alongside the exported
 *  JSON in `shared/schemas/`. Two jobs: migrating a real old database forward is the only
 *  honest test of a chain, and a ready-made database saves every test from seeding by hand.
 *  Snapshots must come from a reproducible fixture builder, not be pulled off a phone -- and
 *  a database copied off a device reads empty unless its `-wal` is checkpointed in first.
 */
fun createDatabase(builder: RoomDatabase.Builder<ChatDatabase>): ChatDatabase {
    return builder
        .fallbackToDestructiveMigration(dropAllTables = true)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
