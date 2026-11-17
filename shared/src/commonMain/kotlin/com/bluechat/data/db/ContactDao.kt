package com.bluechat.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    /**
     * Records that [contactId] is currently reachable at [transportId], creating the contact on
     * first sight.
     *
     * Written as an explicit upsert rather than `@Upsert` with an entity for two reasons. It
     * cannot clobber [ContactEntity.nickname], [ContactEntity.isFavourite] or
     * [ContactEntity.isBlocked] -- the columns the *user* owns -- with defaults reconstructed
     * from a packet. And it is a single statement, so it has no read-modify-write window; the
     * previous read-then-upsert could lose a favourite toggled between the two halves, since
     * inbound traffic and UI actions run on different coroutines.
     *
     * `COALESCE` is what keeps an announced name from being erased by a later message that
     * carries no name.
     */
    @Query(
        """
        INSERT INTO contacts (
            contactId, announcedName, nickname, lastTransportId,
            firstSeenAt, lastSeenAt, isFavourite, isBlocked
        )
        VALUES (:contactId, :announcedName, NULL, :transportId, :now, :now, 0, 0)
        ON CONFLICT(contactId) DO UPDATE SET
            lastTransportId = :transportId,
            lastSeenAt = :now,
            announcedName = COALESCE(:announcedName, announcedName)
        """
    )
    suspend fun touch(
        contactId: String,
        transportId: String,
        announcedName: String?,
        now: Long,
    )

    @Query("SELECT * FROM contacts WHERE lastTransportId = :transportId LIMIT 1")
    fun observeByTransportId(transportId: String): Flow<ContactEntity?>

    @Query("SELECT * FROM contacts WHERE contactId = :contactId LIMIT 1")
    suspend fun findById(contactId: String): ContactEntity?

    @Query("SELECT * FROM contacts ORDER BY lastSeenAt DESC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE isFavourite = 1 ORDER BY lastSeenAt DESC")
    fun observeFavourites(): Flow<List<ContactEntity>>

    @Query("SELECT isBlocked FROM contacts WHERE contactId = :contactId")
    suspend fun isBlocked(contactId: String): Boolean?

    @Query("UPDATE contacts SET isFavourite = :favourite WHERE contactId = :contactId")
    suspend fun setFavourite(contactId: String, favourite: Boolean)

    @Query("UPDATE contacts SET isBlocked = :blocked WHERE contactId = :contactId")
    suspend fun setBlocked(contactId: String, blocked: Boolean)

    /** A blank nickname is stored as `NULL`, so clearing an override falls back to the announced name. */
    @Query("UPDATE contacts SET nickname = :nickname WHERE contactId = :contactId")
    suspend fun setNickname(contactId: String, nickname: String?)

    @Query("DELETE FROM contacts WHERE contactId = :contactId")
    suspend fun delete(contactId: String)
}
