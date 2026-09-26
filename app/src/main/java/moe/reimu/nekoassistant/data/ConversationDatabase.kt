package moe.reimu.nekoassistant.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import androidx.core.graphics.scale
import androidx.room.*
import androidx.room.withTransaction
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import moe.reimu.nekoassistant.ai.ChatMessage

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val titleGenerated: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val status: String = "idle",
    val errorMessage: String? = null,
)

@Entity(
    tableName = "conversation_messages",
    primaryKeys = ["conversationId", "position"],
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ConversationMessageEntity(
    val conversationId: String,
    val position: Int,
    val sender: String,
    val text: String,
    val timestamp: Long,
    val fromUser: Boolean,
    val screenshotPath: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val stepNumber: Int? = null,
) {
    fun toMessage() = ChatMessage(sender, text, timestamp, fromUser, screenshotPath,
        imageWidth, imageHeight, stepNumber)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC, createdAt DESC, id")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC, createdAt DESC, id LIMIT 1")
    suspend fun latest(): ConversationEntity?

    @Query("SELECT * FROM conversation_messages WHERE conversationId = :id ORDER BY position")
    fun observeMessages(id: String): Flow<List<ConversationMessageEntity>>

    @Query("SELECT * FROM conversation_messages WHERE conversationId = :id ORDER BY position")
    suspend fun messages(id: String): List<ConversationMessageEntity>

    @Insert
    suspend fun insert(conversation: ConversationEntity)

    @Upsert
    suspend fun upsertMessages(messages: List<ConversationMessageEntity>)

    @Query("SELECT id FROM conversations WHERE status IN ('running', 'paused') LIMIT 1")
    suspend fun activeId(): String?

    @Query("UPDATE conversations SET status = :status, errorMessage = :error, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: String, error: String?, now: Long)

    @Query("UPDATE conversations SET title = :title, titleGenerated = 1 WHERE id = :id")
    suspend fun setTitle(id: String, title: String)

    @Query("UPDATE conversations SET status = 'interrupted', errorMessage = 'Task interrupted when the app process stopped' WHERE status IN ('running', 'paused')")
    suspend fun recoverInterrupted()

    @Query("SELECT screenshotPath FROM conversation_messages WHERE screenshotPath IS NOT NULL")
    suspend fun screenshotPaths(): List<String>
}

@Database(entities = [ConversationEntity::class, ConversationMessageEntity::class], version = 1, exportSchema = false)
abstract class ConversationDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    private var initialization: Deferred<Unit>? = null

    internal fun initializeOnce(block: suspend () -> Unit): Deferred<Unit> = synchronized(this) {
        // A recreated service must not mark another instance's finishing task interrupted.
        // This job belongs to the process/database, not a screen or service lifecycle.
        initialization ?: CoroutineScope(Dispatchers.IO).async { block() }.also { initialization = it }
    }

    companion object {
        @Volatile private var instance: ConversationDatabase? = null
        fun getInstance(context: Context): ConversationDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,
                ConversationDatabase::class.java, "conversations.db").build().also { instance = it }
        }
    }
}

class ConversationRepository(
    context: Context,
    private val database: ConversationDatabase = ConversationDatabase.getInstance(context),
    private val screenshotDirectory: File = File(context.filesDir, "conversation-screenshots"),
) {
    private val dao = database.conversations()

    fun observeConversations() = dao.observeConversations()
    fun observeMessages(id: String) = dao.observeMessages(id)
    suspend fun latest() = dao.latest()
    suspend fun get(id: String) = dao.get(id)
    suspend fun messages(id: String) = dao.messages(id).map { it.toMessage() }

    suspend fun create(prompt: String): String {
        val conversation = ConversationEntity(
            title = prompt.lineSequence().first().trim().take(64).ifBlank { "New chat" },
        )
        dao.insert(conversation)
        return conversation.id
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val paths = database.withTransaction {
            val chat = dao.get(id) ?: return@withTransaction emptyList<String>()
            check(chat.status != "running" && chat.status != "paused") { "Stop the task before deleting this chat" }
            val paths = dao.messages(id).mapNotNull { it.screenshotPath }
            dao.delete(id)
            paths
        }
        // Delete rows first: interrupted cleanup can safely be completed during startup recovery.
        paths.forEach { File(screenshotDirectory, it).delete() }
    }

    suspend fun prepareForService() = database.initializeOnce { recoverInterrupted() }.await()

    // Only initial process recovery uses this; UI observers never reset active tasks.
    suspend fun recoverInterrupted() = withContext(Dispatchers.IO) {
        dao.recoverInterrupted()
        val referenced = dao.screenshotPaths().toSet()
        screenshotDirectory.listFiles()?.filter { it.name !in referenced }?.forEach { it.delete() }
    }

    suspend fun beginRun(id: String) = database.withTransaction {
        checkNotNull(dao.get(id)) { "Conversation no longer exists" }
        val active = dao.activeId()
        check(active == null) { "Another conversation is already active" }
        dao.setStatus(id, "running", null, System.currentTimeMillis())
    }

    suspend fun setStatus(id: String, status: String, error: String? = null) =
        dao.setStatus(id, status, error, System.currentTimeMillis())

    suspend fun setTitle(id: String, title: String) {
        val cleaned = title.lineSequence().first().trim().trim('"', '“', '”').take(80)
        if (cleaned.isNotBlank()) dao.setTitle(id, cleaned)
    }

    suspend fun saveMessages(id: String, messages: List<ChatMessage>, fromPosition: Int = 0) {
        dao.upsertMessages(messages.mapIndexed { position, m ->
            ConversationMessageEntity(id, fromPosition + position, m.sender, m.text, m.timestamp, m.fromUser,
                m.screenshotPath, m.imageWidth, m.imageHeight, m.stepNumber)
        })
    }

    suspend fun finishRun(id: String, messages: List<ChatMessage>, status: String, error: String?) =
        database.withTransaction {
            saveMessages(id, messages)
            dao.setStatus(id, status, error, System.currentTimeMillis())
        }

    /** Persist only the full-resolution JPEG; model-sized bytes exist only in memory. */
    suspend fun saveScreenshot(bitmap: Bitmap, modelSize: Size, step: Int): Pair<ChatMessage, ByteArray> =
        withContext(Dispatchers.IO) {
            check(screenshotDirectory.isDirectory || screenshotDirectory.mkdirs()) { "Cannot create screenshot storage" }
            val key = UUID.randomUUID().toString()
            val original = File(screenshotDirectory, "$key-full.jpg")
            try {
                original.outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) { "Cannot encode screenshot" }
                }
                val scaled = bitmap.scale(modelSize.width, modelSize.height)
                val bytes = try {
                    ByteArrayOutputStream().use {
                        check(scaled.compress(Bitmap.CompressFormat.JPEG, 95, it)) { "Cannot encode model screenshot" }
                        it.toByteArray()
                    }
                } finally {
                    if (scaled !== bitmap) scaled.recycle()
                }
                ChatMessage("Screen", "Step $step", System.currentTimeMillis(),
                    screenshotPath = original.name,
                    imageWidth = bitmap.width, imageHeight = bitmap.height, stepNumber = step) to bytes
            } catch (e: Exception) {
                original.delete()
                throw e
            }
        }
}
