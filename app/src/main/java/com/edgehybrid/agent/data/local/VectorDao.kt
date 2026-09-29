package com.edgehybrid.agent.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "vector_chunks")
data class VectorChunkEntity(
    @PrimaryKey
    val id: String,
    val text: String,
    val vectorCsv: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface VectorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(chunk: VectorChunkEntity)

    @Query("SELECT * FROM vector_chunks ORDER BY timestamp DESC")
    suspend fun getAllChunks(): List<VectorChunkEntity>

    @Query("DELETE FROM vector_chunks WHERE id = :id")
    suspend fun deleteChunk(id: String)

    @Query("DELETE FROM vector_chunks")
    suspend fun clearAll()
}
