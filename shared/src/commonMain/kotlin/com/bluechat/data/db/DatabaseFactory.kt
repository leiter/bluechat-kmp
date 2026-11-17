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
 * The downgrade case is covered too, so checking out an older branch does not wedge the app.
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
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
