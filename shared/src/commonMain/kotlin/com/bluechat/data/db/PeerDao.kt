package com.bluechat.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {

    @Upsert
    suspend fun upsert(peer: PeerEntity)

    @Query("SELECT * FROM peers WHERE lastTransportId = :transportId LIMIT 1")
    fun observeByTransportId(transportId: String): Flow<PeerEntity?>

    @Query("SELECT * FROM peers WHERE peerId = :peerId LIMIT 1")
    suspend fun findById(peerId: String): PeerEntity?

    @Query("SELECT * FROM peers ORDER BY lastSeenAt DESC")
    fun observeAll(): Flow<List<PeerEntity>>
}
