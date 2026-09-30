package com.edgehybrid.agent.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity): Long

    @Query("SELECT * FROM notes ORDER BY timestamp DESC")
    fun getAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentNotes(limit: Int = 10): List<NoteEntity>

    /**
     * Overwrites an existing note in place, preserving its id.
     *
     * Used to scrub a secret out of a stored record after it has been copied into the
     * encrypted keystore.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateNote(note: NoteEntity): Long

    @Delete
    suspend fun deleteNote(note: NoteEntity): Int
}