package moe.reimu.nekoassistant.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import moe.reimu.nekoassistant.ai.ChatMessage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationPersistenceTest {
    private lateinit var context: Context
    private lateinit var database: ConversationDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var directory: File
    private lateinit var databaseName: String

    @Before fun openDatabase() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "conversation-test-${UUID.randomUUID()}.db"
        directory = File(context.cacheDir, databaseName).apply { mkdirs() }
        reopen()
    }

    private fun reopen() {
        database = Room.databaseBuilder(context, ConversationDatabase::class.java, databaseName).build()
        repository = ConversationRepository(context, database, directory)
    }

    @After fun cleanUp() {
        database.close()
        context.deleteDatabase(databaseName)
        directory.deleteRecursively()
    }

    @Test fun transcriptAndFullScreenshotSurviveReopenWithoutPersistingModelImage() = runBlocking {
        val id = repository.create("Find a restaurant")
        repository.beginRun(id)
        val bitmap = Bitmap.createBitmap(370, 230, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff358aca.toInt())
        val (screen, modelBytes) = repository.saveScreenshot(bitmap, Size(160, 100), 1)
        bitmap.recycle()
        val messages = listOf(ChatMessage("You", "Find a restaurant", 1, true), screen,
            ChatMessage("Assistant", "Found three options", 2))
        repository.finishRun(id, messages, "completed", null)
        repository.setTitle(id, "Restaurant options")
        database.close()
        reopen()

        assertEquals(messages, repository.messages(id))
        assertEquals("Restaurant options", repository.get(id)?.title)
        val original = BitmapFactory.decodeFile(File(directory, screen.screenshotPath!!).path)
        val model = BitmapFactory.decodeByteArray(modelBytes, 0, modelBytes.size)
        assertEquals(370, original.width)
        assertEquals(230, original.height)
        assertEquals(160, model.width)
        assertEquals(100, model.height)
        assertEquals(listOf(screen.screenshotPath), directory.listFiles()!!.map { it.name })
        original.recycle()
        model.recycle()
    }

    @Test fun concurrentStartsAndPausedChatsKeepSingleActiveConversation() = runBlocking {
        val first = repository.create("First")
        val second = repository.create("Second")
        val attempts = coroutineScope {
            listOf(first, second).map { id -> async { id to runCatching { repository.beginRun(id) }.isSuccess } }
                .map { it.await() }
        }
        assertEquals(1, attempts.count { it.second })
        val active = attempts.single { it.second }.first
        val other = attempts.single { !it.second }.first
        repository.setStatus(active, "paused")
        assertTrue(runCatching { repository.beginRun(other) }.isFailure)
        repository.finishRun(active, emptyList(), "stopped", null)
        repository.beginRun(other)
        assertEquals(other, database.conversations().activeId())
    }

    @Test fun deletionCascadesWithoutDeletingOtherChatFiles() = runBlocking {
        val first = repository.create("First")
        val second = repository.create("Second")
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
        val (firstScreen, _) = repository.saveScreenshot(bitmap, Size(20, 30), 1)
        val (secondScreen, _) = repository.saveScreenshot(bitmap, Size(20, 30), 1)
        bitmap.recycle()
        repository.saveMessages(first, listOf(firstScreen))
        repository.saveMessages(second, listOf(secondScreen))
        repository.setStatus(second, "completed")
        database.close()
        reopen()
        repository.beginRun(first)
        assertTrue(runCatching { repository.delete(first) }.isFailure)
        repository.setStatus(first, "paused")
        assertTrue(runCatching { repository.delete(first) }.isFailure)
        repository.finishRun(first, listOf(firstScreen), "stopped", null)
        repository.delete(first)
        assertNull(repository.get(first))
        assertTrue(repository.messages(first).isEmpty())
        assertFalse(File(directory, firstScreen.screenshotPath!!).exists())
        assertEquals(listOf(secondScreen), repository.messages(second))
        assertTrue(File(directory, secondScreen.screenshotPath!!).exists())
    }

    @Test fun interruptedRecoveryPreservesEvidenceAndRemovesOnlyOrphanFiles() = runBlocking {
        val id = repository.create("Interrupted task")
        repository.beginRun(id)
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
        val (screen, _) = repository.saveScreenshot(bitmap, Size(20, 30), 1)
        bitmap.recycle()
        repository.saveMessages(id, listOf(screen))
        repository.setStatus(id, "paused")
        val orphan = File(directory, "unfinished.jpg").apply { writeText("partial") }
        database.close()
        reopen()
        repository.prepareForService()

        assertEquals("interrupted", repository.get(id)?.status)
        assertEquals(listOf(screen), repository.messages(id))
        assertTrue(File(directory, screen.screenshotPath!!).exists())
        assertFalse(orphan.exists())
        assertNull(database.conversations().activeId())
        repository.beginRun(id)
        // Recreating a service in the same process must not interrupt the current run.
        ConversationRepository(context, database, directory).prepareForService()
        assertEquals("running", repository.get(id)?.status)
    }
}
