package com.edgehybrid.agent.nativeactions

import android.content.Context
import com.edgehybrid.agent.data.local.NoteDao
import com.edgehybrid.agent.data.local.NoteEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

        override suspend fun getRecentNotes(limit: Int): List<NoteEntity> =
            notes.takeLast(limit)

        override suspend fun updateNote(note: NoteEntity): Long {
            val index = notes.indexOfFirst { it.id == note.id }
            if (index >= 0) notes[index] = note
            return note.id
        }

        override suspend fun deleteNote(note: NoteEntity): Int {
            val removed = notes.removeIf { it.id == note.id }
            return if (removed) 1 else 0
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
    fun testActionConfirmationCarriesNoExecutableParameters() = runTest {
        // The record handed to the UI must be inert: it identifies the action for
        // display but cannot carry arguments that something else could execute.
        val confirmation = ActionConfirmation(
            id = "test-conf-1",
            tool = NativeTool.SEND_SMS.toolName,
            title = "Send SMS",
            summary = "Send an SMS to +1234567890",
            tier = ActionRiskTier.CONFIRM_STRICT,
            details = listOf(
                ConfirmationDetail("To (phone)", "+1234567890"),
                ConfirmationDetail("Message", "Hello from test!")
            )
        )
        assertEquals("test-conf-1", confirmation.id)
        assertEquals(NativeTool.SEND_SMS.toolName, confirmation.tool)
        assertTrue(confirmation.isStrict)
        assertEquals("+1234567890", confirmation.details[0].value)
        assertEquals("Hello from test!", confirmation.details[1].value)
    }

    @Test
    fun testRegistryIsOneShotAndUnforgeable() = runTest {
        val registry = ActionConfirmationRegistry()
        var executed = 0

        val confirmation = registry.register(
            tool = NativeTool.SEND_SMS.toolName,
            title = "Send SMS",
            summary = "test",
            tier = ActionRiskTier.CONFIRM_STRICT,
            details = emptyList(),
            run = { "sent".also { executed++ } }
        )

        // Before approval the action has not run.
        assertEquals(0, executed)
        assertEquals(1, registry.pendingCount)

        val run = registry.consume(confirmation.id)
        assertNotNull(run)
        run!!.invoke()
        assertEquals(1, executed)

        // Replaying the same id is a no-op: the entry is removed before execution.
        assertNull(registry.consume(confirmation.id))
        assertEquals(1, executed)
        assertEquals(0, registry.pendingCount)
    }

    @Test
    fun testRegistryRejectsUnknownConfirmationId() = runTest {
        val registry = ActionConfirmationRegistry()
        assertNull(registry.consume("never-registered"))
    }

    @Test
    fun testCancelDropsPendingActionWithoutRunningIt() = runTest {
        val registry = ActionConfirmationRegistry()
        var executed = 0

        val confirmation = registry.register(
            tool = "set_alarm",
            title = "Set alarm",
            summary = "test",
            tier = ActionRiskTier.CONFIRM,
            details = emptyList(),
            run = { "done".also { executed++ } }
        )

        registry.cancel(confirmation.id)

        assertNull(registry.consume(confirmation.id))
        assertEquals(0, executed)
    }
}
