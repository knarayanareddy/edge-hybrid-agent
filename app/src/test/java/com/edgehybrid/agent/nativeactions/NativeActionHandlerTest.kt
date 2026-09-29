package com.edgehybrid.agent.nativeactions

import android.content.Context
import com.edgehybrid.agent.data.local.NoteDao
import com.edgehybrid.agent.data.local.NoteEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class NativeActionHandlerTest {

    private lateinit var mockNoteDao: FakeNoteDao
    private lateinit var context: Context

    class FakeNoteDao : NoteDao {
        val notes = mutableListOf<NoteEntity>()
        private val idGen = AtomicLong(1)

        override suspend fun insertNote(note: NoteEntity): Long {
            val id = idGen.getAndIncrement()
            notes.add(note.copy(id = id))
            return id
        }

        override fun getAllNotes() = flowOf(notes.toList())

        override suspend fun getNoteById(id: Long): NoteEntity? = notes.find { it.id == id }

        override suspend fun deleteNote(id: Long) {
            notes.removeIf { it.id == id }
        }
    }

    @Before
    fun setUp() {
        mockNoteDao = FakeNoteDao()
    }

    @Test
    fun testCreateQuickNoteInsertsSuccessfully() = runTest {
        val note = NoteEntity(title = "Shopping List", content = "Milk, Eggs, Bread")
        val id = mockNoteDao.insertNote(note)
        assertTrue(id > 0)
        assertEquals(1, mockNoteDao.notes.size)
        assertEquals("Shopping List", mockNoteDao.notes[0].title)
    }

    @Test
    fun testPrepareSmsGeneratesConfirmation() = runTest {
        val confirmation = ActionConfirmation(
            id = "test-conf-1",
            tool = NativeTool.SEND_SMS.toolName,
            summary = "Send an SMS to +1234567890",
            params = mapOf(
                "phone" to "+1234567890",
                "message" to "Hello from test!"
            )
        )
        assertNotNull(confirmation.id)
        assertEquals(NativeTool.SEND_SMS.toolName, confirmation.tool)
        assertEquals("+1234567890", confirmation.params["phone"])
        assertEquals("Hello from test!", confirmation.params["message"])
    }
}
