package moe.reimu.nekoassistant

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.reimu.nekoassistant.ai.tool.Finish
import moe.reimu.nekoassistant.ai.AgentMessage
import moe.reimu.nekoassistant.ai.ExecutorAgent
import moe.reimu.nekoassistant.ai.createLlmClient
import moe.reimu.nekoassistant.ai.PlannerAgent
import moe.reimu.nekoassistant.data.ConversationRepository
import moe.reimu.nekoassistant.ai.ChatMessage
import moe.reimu.nekoassistant.ai.generateConversationTitle
import moe.reimu.nekoassistant.data.ActiveLlmConfiguration
import moe.reimu.nekoassistant.data.LlmConfigurationRepository
import moe.reimu.displaycontrol.*
import rikka.shizuku.Shizuku
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.time.Duration.Companion.milliseconds

@OptIn(FlowPreview::class)
class AgentService : LifecycleService(), Shizuku.OnBinderReceivedListener,
    Shizuku.OnRequestPermissionResultListener {

    private val binder = LocalBinder()
    private val stateStore = AgentStateStore()
    private val toolApprovals = ToolApprovalGate { request ->
        stateStore.update { it.copy(toolApproval = request) }
    }
    private var chatVisible = false
    val state = stateStore.state
    private val frames = MutableStateFlow<Bitmap?>(null)
    val previewFrames = frames.asStateFlow()

    private var currentJob: Job? = null
    private val pendingUserMessages = ConcurrentLinkedQueue<String>()

    val hasPendingUserMessages: Boolean get() = pendingUserMessages.isNotEmpty()

    fun sendUserMessage(conversationId: String, text: String): Boolean {
        val message = text.trim()
        if (message.isEmpty() || !state.value.isAcceptingMessages ||
            state.value.conversationId != conversationId || currentJob?.isActive != true) return false
        stateStore.addUserMessage(message)
        pendingUserMessages.add(message)
        return true
    }
    private val displayStarted: Boolean get() = state.value.hasDisplay
    @Volatile
    private var destroying = false
    private val launchedPackages = CopyOnWriteArraySet<String>()
    @Volatile
    private var lastConfirmedApp: AvailableApp? = null
    private val availableApp: AvailableApp? get() = state.value.availableApp
    private lateinit var llmConfigurationRepository: LlmConfigurationRepository
    private lateinit var conversations: ConversationRepository
    private lateinit var conversationRecovery: Deferred<Unit>
    private val transcriptPersistence = Mutex()
    private var persistedMessages: List<ChatMessage> = emptyList()

    inner class LocalBinder : Binder() {
        fun getService(): AgentService = this@AgentService
    }

    private val accessibilityAccess by lazy { AccessibilityAccess(this) }
    private val notificationAccess by lazy { NotificationAccess(this) }
    private val userServices = MutableStateFlow<IUserService?>(null)
    private var userServiceBinding = false

    val displayMetrics = DisplayMetrics()
    var screenshotSize = Size(0, 0)

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, UserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(p0: ComponentName, p1: IBinder) {
            if (destroying) return
            Log.i(TAG, "UserService onServiceConnected")
            val service = IUserService.Stub.asInterface(p1)
            synchronized(imageLock) {
                deviceTools.connected(service)
                userServices.value = service
            }
            accessibilityAccess.connected(userServices.value)
            notificationAccess.connected(userServices.value)
            stateStore.update { it.copy(isUserServiceConnected = true) }
        }

        override fun onServiceDisconnected(p0: ComponentName) {
            if (destroying) return
            Log.i(TAG, "UserService onServiceDisconnected")
            disconnectUserService()
        }
    }

    private val frameThread = HandlerThread("display-frames").apply { start() }
    private val frameHandler = Handler(frameThread.looper)

    private var agentDisplayId: Int? = null
    private val deviceTools by lazy {
        DeviceToolExecutor(applicationContext, onFrameAvailable = {
            frameHandler.post { synchronized(imageLock) { schedulePreview() } }
        })
    }

    private val imageLock = Any()
    private var lastPreviewFrameAtMs = 0L

    private val renderPreview = Runnable {
        synchronized(imageLock) {
            if (frames.subscriptionCount.value > 0 && agentDisplayId != null) {
                try {
                    // Own the pixels: closing an Image permits its producer to reuse its
                    // buffer even while Compose is still drawing the previous bitmap.
                    frames.value = getBitmap()
                    lastPreviewFrameAtMs = SystemClock.elapsedRealtime()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to preview frame", e)
                }
            }
        }
    }

    private fun schedulePreview() {
        if (frames.subscriptionCount.value == 0 || frameHandler.hasCallbacks(renderPreview)) return
        val delay = (PREVIEW_FRAME_INTERVAL_MS -
            (SystemClock.elapsedRealtime() - lastPreviewFrameAtMs)).coerceAtLeast(0L)
        // Keep a trailing update so the final frame of an animation is also shown.
        frameHandler.postDelayed(renderPreview, delay)
    }

    private fun releaseCapture() {
        synchronized(imageLock) {
            stateStore.update { it.copy(hasDisplay = false, availableApp = null) }
            launchedPackages.clear()
            lastConfirmedApp = null
            frameHandler.removeCallbacks(renderPreview)
            agentDisplayId = null
            lastPreviewFrameAtMs = 0L
            frames.value = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        llmConfigurationRepository = LlmConfigurationRepository(this)
        conversations = ConversationRepository(this)
        conversationRecovery = lifecycleScope.async(Dispatchers.IO) { conversations.prepareForService() }

        Shizuku.addBinderReceivedListenerSticky(this)
        Shizuku.addRequestPermissionResultListener(this)
        Shizuku.addBinderDeadListener(binderDeadListener)
        lifecycleScope.launch {
            frames.subscriptionCount.map { it > 0 }.distinctUntilChanged().collect { observed ->
                synchronized(imageLock) {
                    if (observed) schedulePreview() else {
                        frameHandler.removeCallbacks(renderPreview)
                        frames.value = null
                    }
                }
            }
        }

        val dm = getSystemService(DisplayManager::class.java)
        @Suppress("DEPRECATION")
        dm.getDisplay(0).getMetrics(displayMetrics)
        screenshotSize = Size(
            displayMetrics.widthPixels,
            displayMetrics.heightPixels
        ).scaleToMaxDimension(1024)
    }

    override fun onDestroy() {
        destroying = true
        accessibilityAccess.close()
        notificationAccess.close()
        super.onDestroy()

        Shizuku.removeBinderReceivedListener(this)
        Shizuku.removeRequestPermissionResultListener(this)
        Shizuku.removeBinderDeadListener(binderDeadListener)

        currentJob?.cancel()
        releaseCapture()
        deviceTools.close()
        frameThread.quitSafely()
        stopJobForeground()
        cancelTakeOverNotification()
        try {
            if (userServiceBinding) {
                // Remove pending bindings as well as a connected remote service.
                Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy user service", e)
        } finally {
            try {
                if (userServiceBinding) {
                    // remove=true stops the remote process; remove=false clears local callbacks.
                    Shizuku.unbindUserService(userServiceArgs, userServiceConnection, false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister user service connection", e)
            }
            userServiceBinding = false
            userServices.value = null
            stateStore.update { it.copy(isUserServiceConnected = false) }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onBinderReceived() {
        tryBindUserService()
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        tryBindUserService()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        stateStore.update { it.copy(shizukuStatus = ShizukuStatus()) }
        disconnectUserService()
    }

    private fun disconnectUserService() {
        accessibilityAccess.connected(null)
        notificationAccess.connected(null)
        synchronized(imageLock) {
            userServices.value = null
            deviceTools.connected(null)
        }
        userServiceBinding = false
        stateStore.update { it.copy(isUserServiceConnected = false) }
        if (currentJob != null) {
            notifyError("Shizuku user service disconnected")
            currentJob?.cancel(UserServiceDisconnected())
        }
        releaseCapture()
        stopIfIdle()
    }

    fun requestShizukuPermission() {
        try {
            Shizuku.requestPermission(0)
        } catch (e: Exception) {
            notifyError(e.message ?: "Failed to request Shizuku permission")
        }
    }

    private fun tryBindUserService() {
        if (destroying) return
        try {
            val available = Shizuku.pingBinder()
            val granted = available && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            stateStore.update { it.copy(shizukuStatus = ShizukuStatus(granted, available)) }
            if (!granted || userServiceBinding) return
            userServiceBinding = true
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } catch (e: Exception) {
            if (userServiceBinding) {
                try {
                    // Shizuku registers the callback before attempting the remote bind.
                    Shizuku.unbindUserService(userServiceArgs, userServiceConnection, false)
                } catch (cleanupError: Exception) {
                    Log.w(TAG, "Failed to unregister failed binding", cleanupError)
                }
            }
            userServiceBinding = false
            Log.e(TAG, "Failed to bind user service", e)
            notifyError(e.message ?: "Failed to bind user service")
        }
    }

    private class ConversationStorageFailure : CancellationException("Conversation storage failed")

    private class UserServiceDisconnected : CancellationException("Shizuku user service disconnected")

    fun stopDisplay(): Boolean = synchronized(imageLock) {
        val id = agentDisplayId ?: return@synchronized false
        try {
            deviceTools.execute(StopDisplay(id))
            true
        } finally { releaseCapture() }
    }

    fun stopAgent() {
        Log.i(TAG, "stopAgent requested")
        currentJob?.cancel()
    }

    fun respondToToolApproval(id: String, approved: Boolean, message: String?) {
        toolApprovals.respond(id, approved, message)
    }

    suspend fun awaitToolApproval(details: ToolApprovalDetails): ToolApprovalResponse =
        withContext(Dispatchers.Main.immediate) {
            try {
                state.value.conversationId?.let { conversations.setStatus(it, "paused") }
                if (!chatVisible) postTakeOverNotification(details.title)
                toolApprovals.request(details).also {
                    state.value.conversationId?.let { id -> conversations.setStatus(id, "running") }
                }
            } finally {
                cancelTakeOverNotification()
            }
        }

    fun resumeAgent() {
        Log.i(TAG, "resumeAgent requested")
        takeOverResume?.complete(Unit)
    }

    /**
     * Parks the agent until the user says it may continue. The action itself does the waiting,
     * so the step loop resumes on the same planner history instead of restarting the task,
     * with the display and the live preview still up.
     */
    suspend fun awaitUserTakeOver(message: String) {
        val resume = CompletableDeferred<Unit>()
        // The signal first: a resume landing between the two writes would otherwise find no
        // request to answer and leave the agent waiting on a message the user already saw.
        takeOverResume = resume
        try {
            withContext(Dispatchers.Main) {
                state.value.conversationId?.let { conversations.setStatus(it, "paused") }
                stateStore.update { it.copy(takeOverMessage = message) }
                if (!chatVisible) postTakeOverNotification(message)
            }
            resume.await()
            state.value.conversationId?.let { conversations.setStatus(it, "running") }
        } finally {
            // Also the cancellation path: stopping the agent mid-takeover has to clear the
            // request, or the screen keeps asking for something nobody is waiting on.
            takeOverResume = null
            stateStore.update { it.copy(takeOverMessage = null) }
            cancelTakeOverNotification()
        }
    }

    suspend fun executeDeviceCall(call: DeviceToolCall<*, *>): moe.reimu.nekoassistant.ai.ActionResult = withContext(Dispatchers.IO) {
        val result = synchronized(imageLock) {
            val result = call.execute(deviceTools)
            val command = call.command
            if (command is LaunchApp) launchedPackages.add(command.`package`)
            if (command is DisplayTarget || command is StartDisplay) {
                val displays = deviceTools.managedDisplays()
                val targetId = (command as? DisplayTarget)?.displayId ?: displays.lastOrNull()?.displayId
                val display = displays.firstOrNull { it.displayId == targetId }
                if (display != null) {
                    agentDisplayId = display.displayId
                    displayMetrics.widthPixels = display.width
                    displayMetrics.heightPixels = display.height
                    displayMetrics.densityDpi = display.dpi
                    screenshotSize = Size(display.width, display.height).scaleToMaxDimension(1024)
                    stateStore.update { it.copy(hasDisplay = true) }
                    schedulePreview()
                } else if (targetId == agentDisplayId) releaseCapture()
            }
            result
        }
        when (result) {
            is DeviceToolResult.Text -> moe.reimu.nekoassistant.ai.ActionResult.success(result.text)
            // captureStep saves and supplies the selected display to both models next iteration.
            is DeviceToolResult.Image -> moe.reimu.nekoassistant.ai.ActionResult.success(
                "Screenshot captured; the selected display is shown with the next step."
            )
        }
    }

    private fun deviceDisplayContext(): String = synchronized(imageLock) {
        agentDisplayId?.let {
            "当前截图属于 displayId=$it；设备原始尺寸 ${displayMetrics.widthPixels}×${displayMetrics.heightPixels}，" +
                "截图尺寸 ${screenshotSize.width}×${screenshotSize.height}。输入坐标必须按原始尺寸换算。"
        } ?: "当前没有选中的虚拟显示器。先调用 displays_list 或 display_start 获取 displayId。"
    }

    fun getBitmap(): Bitmap? = synchronized(imageLock) { agentDisplayId?.let(deviceTools::bitmap) }

    private fun focusedPackage(): String? = synchronized(imageLock) {
        if (agentDisplayId == null || userServices.value == null) null
        else deviceTools.execute(FocusedApp(checkNotNull(agentDisplayId))).takeUnless { it == "No focused package" }
    }

    fun getFocusedAppName(): String? {
        if (!displayStarted) return null
        val focusedPackageName = focusedPackage() ?: return null
        confirmAvailableApp(focusedPackageName)

        return try {
            val appInfo = packageManager.getApplicationInfo(focusedPackageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Package not found: $focusedPackageName", e)
            focusedPackageName
        }
    }


    private fun confirmAvailableApp(packageName: String): AvailableApp? {
        // A launch request alone is insufficient: UserService may have rejected it.
        if (packageName !in launchedPackages) return null
        val app = try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            AvailableApp(packageName, packageManager.getApplicationLabel(info).toString())
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        lastConfirmedApp = app
        return app
    }

    private fun finishDisplaySession(): AvailableApp? {
        if (destroying) return null
        val app = if (displayStarted) {
            try {
                focusedPackage()?.let(::confirmAvailableApp) ?: lastConfirmedApp
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check the background app", e)
                lastConfirmedApp
            }
        } else null
        if (app == null) {
            try {
                stopDisplay()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop the empty display", e)
            }
        }
        return app
    }

    fun bringAppToFront(context: Context) {
        if (currentJob != null) return
        val app = availableApp ?: return
        try {
            val intent = packageManager.getLaunchIntentForPackage(app.packageName)
                ?: error("${app.label} can no longer be opened")
            // Match the existing task without resetting its activity stack.
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            val options = ActivityOptions.makeBasic().apply {
                launchDisplayId = Display.DEFAULT_DISPLAY
            }
            context.startActivity(intent, options.toBundle())
            // Release only after the foreground launch has returned successfully.
            closeRetainedDisplay()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bring app to front", e)
            notifyError(e.message ?: "Failed to bring app to front")
        }
    }

    fun forgetConversation(id: String) {
        if (currentJob != null || state.value.conversationId != id) return
        closeRetainedDisplay()
        cancelTakeOverNotification()
        clearTaskResultNotification()
        stateStore.update { it.copy(conversationId = null, messages = emptyList(),
            errorMessage = null, inferenceStatuses = emptyMap(), takeOverMessage = null) }
    }

    fun closeRetainedDisplay() {
        if (currentJob != null || availableApp == null) return
        try {
            stopDisplay()
        } catch (e: Exception) {
            notifyError(e.message ?: "Failed to close display")
        } finally {
            stopIfIdle()
        }
    }

    private suspend fun persistTranscript(conversationId: String) = transcriptPersistence.withLock {
        // Read inside the lock so a delayed streaming write cannot overwrite a newer step.
        val messages = state.value.messages
        val firstChanged = messages.indices.firstOrNull { messages[it] != persistedMessages.getOrNull(it) }
        if (firstChanged != null) {
            conversations.saveMessages(conversationId, messages.drop(firstChanged), firstChanged)
        }
        persistedMessages = messages
    }

    private suspend fun captureStep(conversationId: String, step: Int): String? {
        if (!displayStarted) return null
        val bitmap = withTimeoutOrNull(5_000) {
            var frame = getBitmap()
            while (frame == null) {
                delay(50)
                frame = getBitmap()
            }
            frame
        } ?: error("No screenshot is available for step $step")
        try {
            val (message, modelBytes) = conversations.saveScreenshot(bitmap, screenshotSize, step)
            stateStore.appendMessage(message)
            // Persist the screenshot and its place in the transcript before asking either model.
            persistTranscript(conversationId)
            return "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(modelBytes)}"
        } finally {
            bitmap.recycle()
        }
    }

    @Volatile
    private var takeOverResume: CompletableDeferred<Unit>? = null

    suspend fun mainJob(
        userPrompt: String,
        configuration: ActiveLlmConfiguration,
        client: com.aallam.openai.client.OpenAI,
        conversationId: String,
        history: List<ChatMessage>,
    ): String {

        val plannerAgent = PlannerAgent(
            userPrompt, configuration, client,
            history = history,
            isTermuxAvailable = { TermuxCommandRunner.isAvailable(this) },
            onStatus = { stateStore.updateInferenceStatus("Planner", it) },
            onStream = { stateStore.updateAgentMessage(AgentMessage("Planner", it)) },
        )
        val executorAgent = ExecutorAgent(
            { screenshotSize.width to screenshotSize.height },
            configuration,
            client,
            isTermuxAvailable = { TermuxCommandRunner.isAvailable(this) },
            displayContext = ::deviceDisplayContext,
            onStatus = { stateStore.updateInferenceStatus("Executor", it) },
            onStream = { stateStore.updateAgentMessage(AgentMessage("Executor", it)) },
        )

        for (step in 0..<100) {
            Log.i(TAG, "Agent step $step")

            while (true) {
                val message = pendingUserMessages.poll() ?: break
                plannerAgent.addUserMessage(message)
            }
            persistTranscript(conversationId)
            val currentScreenshot = captureStep(conversationId, step + 1)
            val currentApp = getFocusedAppName()

            val planResult = plannerAgent.plan(currentScreenshot, currentApp)
                ?: error("Failed to plan the next action")
            stateStore.updateAgentMessage(AgentMessage("Planner", planResult))

            if (hasPendingUserMessages) continue

            // Parse action
            val agentResponse = executorAgent.execute(
                planResult, currentScreenshot, currentApp
            )
            if (agentResponse == null) {
                Log.e(TAG, "Failed to parse agent response, retry")
                continue
            }

            if (hasPendingUserMessages) continue

            Log.i(TAG, "Action: ${agentResponse.action}")

            val executorText = buildString {
                if (agentResponse.think.isNotBlank()) {
                    appendLine("Think: ${agentResponse.think}")
                }
                append("Action: ${agentResponse.action}")
            }
            stateStore.updateAgentMessage(AgentMessage("Executor", executorText))

            // Check if we're done
            if (agentResponse.action is Finish) {
                // Serialize completion with UI submissions so an accepted message cannot
                // be stranded between the last queue check and ending the run.
                val canFinish = withContext(Dispatchers.Main.immediate) {
                    if (hasPendingUserMessages) false else {
                        stateStore.update { it.copy(isAcceptingMessages = false) }
                        true
                    }
                }
                if (!canFinish) continue
                Log.i(TAG, "Agent finished")
                return agentResponse.action.message
            }

            val actionResult = agentResponse.action.execute(this)
            Log.i(TAG, "Action result: $actionResult")
            stateStore.appendMessage(ChatMessage("Action result", actionResult.prompt, System.currentTimeMillis()))
            persistTranscript(conversationId)
            if (actionResult.stop) error(actionResult.prompt)
            plannerAgent.addActionResult(agentResponse.action, actionResult.prompt)
            delay(3000.milliseconds) // Wait for the action to take effect and screen to update
        }

        error("Task stopped after reaching the 100-step limit")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent == null) {
            Log.w(TAG, "Intent is null, not starting")
            stopIfIdle()
            return START_NOT_STICKY
        }

        if (currentJob != null) {
            Log.w(TAG, "A job is running, not starting")
            return START_NOT_STICKY
        }

        val agentPrompt = intent.getStringExtra(EXTRA_AGENT_PROMPT)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (agentPrompt == null) {
            Log.w(TAG, "Agent prompt is null or blank, not starting")
            stopIfIdle()
            return START_NOT_STICKY
        }

        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
        if (conversationId.isNullOrBlank()) {
            notifyError("Select a conversation before starting a task")
            stopIfIdle()
            return START_NOT_STICKY
        }
        val previousConversationId = state.value.conversationId
        currentJob = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            var runStarted = false
            var persistenceJob: Job? = null
            var outcome = TaskOutcome.COMPLETED
            var completionMessage: String? = null
            try {
                // Start while the launch is still user-initiated, before binding or network waits.
                startJobForeground()
                tryBindUserService()
                completionMessage = withContext(Dispatchers.IO) {
                    conversationRecovery.await()
                    if (previousConversationId != conversationId && displayStarted) stopDisplay()
                    conversations.beginRun(conversationId)
                    runStarted = true
                    val history = conversations.messages(conversationId)
                    stateStore.update { it.copy(messages = history) }
                    persistedMessages = history
                    stateStore.addUserMessage(agentPrompt)
                    stateStore.update { it.copy(isAcceptingMessages = true) }
                    persistTranscript(conversationId)
                    persistenceJob = lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            state.map { it.messages }.distinctUntilChanged().sample(500).collect {
                                persistTranscript(conversationId)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                notifyError("Could not save conversation: ${e.message}")
                                currentJob?.cancel(ConversationStorageFailure())
                            }
                        }
                    }
                    val configuration = llmConfigurationRepository.activeConfiguration()
                        ?: error("No LLM provider and model are selected")
                    if (conversations.get(conversationId)?.titleGenerated == false) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                generateConversationTitle(configuration, history.firstOrNull { it.fromUser }?.text ?: agentPrompt)?.let {
                                    conversations.setTitle(conversationId, it)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "Title generation failed; keeping prompt title", e)
                            }
                        }
                    }
                    createLlmClient(configuration).use { client ->
                        mainJob(agentPrompt, configuration, client, conversationId, history)
                    }
                }
            } catch (e: CancellationException) {
                outcome = if (e is UserServiceDisconnected || e is ConversationStorageFailure) TaskOutcome.FAILED else TaskOutcome.STOPPED
                throw e
            } catch (e: Exception) {
                outcome = TaskOutcome.FAILED
                Log.e(TAG, "mainJob failed", e)
                notifyError(e.message ?: "Agent job failed")
            } finally {
                stateStore.update { it.copy(isAcceptingMessages = false) }
                // Cancellation still retains a successfully launched app. Publish completion
                // and handoff together, on main, before accepting another task.
                withContext(NonCancellable) {
                    persistenceJob?.cancelAndJoin()
                    var app: AvailableApp? = null
                    try {
                        app = withContext(Dispatchers.IO) { finishDisplaySession() }
                    } catch (e: Exception) {
                        outcome = TaskOutcome.FAILED
                        if (!destroying) notifyError(e.message ?: "Failed to release task resources")
                    } finally {
                        if (runStarted) {
                            val resultText = completionMessage ?: state.value.errorMessage ?: "Task stopped"
                            stateStore.appendMessage(ChatMessage(
                                if (outcome == TaskOutcome.COMPLETED) "Assistant" else "System",
                                resultText, System.currentTimeMillis(),
                            ))
                            try {
                                conversations.finishRun(conversationId, state.value.messages,
                                    outcome.name.lowercase(), state.value.errorMessage)
                            } catch (e: Exception) {
                                outcome = TaskOutcome.FAILED
                                notifyError("Could not save conversation: ${e.message}")
                            }
                        }
                        pendingUserMessages.clear()
                        currentJob = null
                        val retainedApp = app.takeIf { !destroying && displayStarted }
                        stateStore.finishTask(retainedApp)
                        if (retainedApp != null) startJobForeground() else stopIfIdle()
                        if (!destroying && !chatVisible) {
                            val notice = taskEndNotice(outcome, retainedApp?.label, completionMessage)
                                ?: state.value.errorMessage?.takeIf { retainedApp != null }?.let {
                                    TaskNotice("Task failed", it)
                                }
                            notice?.let {
                                postTaskNotification(it.title, it.message)
                            }
                        }
                    }
                }
            }
        }
        stateStore.startTask(conversationId)
        getSystemService(NotificationManager::class.java).cancel(TASK_RESULT_NOTIFICATION_ID)
        currentJob?.start()

        return START_NOT_STICKY
    }

    private fun startJobForeground() {
        ensureForegroundNotificationChannel()
        val notification = buildForegroundNotification()
        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun stopJobForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun stopIfIdle() {
        if (destroying || currentJob != null || displayStarted) return
        stopJobForeground()
        stopSelf()
    }

    private fun ensureForegroundNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(FOREGROUND_CHANNEL_ID) != null) {
            return
        }

        val channel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            FOREGROUND_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = FOREGROUND_CHANNEL_DESCRIPTION
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification() =
        NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (availableApp != null) "App ready to take over" else "NekoAssistant is running")
            .setContentText(availableApp?.let { "${it.label} is available. Tap to bring it to the front or close its display." }
                ?: "Agent task is in progress")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent())
            .build()

    private fun postTaskNotification(title: String, message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(TASK_EVENTS_CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(
                TASK_EVENTS_CHANNEL_ID, "Task results", NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Task failures, completion, and stops" })
        }
        val retainedDisplay = availableApp != null && !state.value.isAgentRunning
        val notification = NotificationCompat.Builder(this, TASK_EVENTS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(!retainedDisplay)
            .setOngoing(retainedDisplay)
            .setContentIntent(openAppPendingIntent())
            .build()
        if (retainedDisplay) {
            // The display still needs a foreground service. Use its existing notification
            // for the result instead of adding a second entry with the same handoff text.
            manager.cancel(TASK_RESULT_NOTIFICATION_ID)
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        } else {
            manager.notify(TASK_RESULT_NOTIFICATION_ID, notification)
        }
    }

    /**
     * Alerts rather than informs: the run is parked until the user answers, so this one gets a
     * channel of its own instead of riding along on the quiet foreground one.
     */
    private fun postTakeOverNotification(message: String) {
        ensureTakeOverNotificationChannel()
        val notification = NotificationCompat.Builder(this, TAKE_OVER_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("NekoAssistant needs you")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(TAKE_OVER_NOTIFICATION_ID, notification)
    }

    private fun cancelTakeOverNotification() {
        getSystemService(NotificationManager::class.java).cancel(TAKE_OVER_NOTIFICATION_ID)
    }

    private fun openAppPendingIntent() =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CONVERSATION_ID, state.value.conversationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun ensureTakeOverNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(TAKE_OVER_CHANNEL_ID) != null) {
            return
        }

        val channel = NotificationChannel(
            TAKE_OVER_CHANNEL_ID,
            TAKE_OVER_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = TAKE_OVER_CHANNEL_DESCRIPTION
        }
        manager.createNotificationChannel(channel)
    }

    private fun notifyError(message: String) {
        Log.e(TAG, "Agent error: $message")
        stateStore.update { it.copy(errorMessage = message) }
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            if (!chatVisible) postTaskNotification("Task failed", message)
        }
    }

    // Called on main by the chat lifecycle; independent of StateFlow's stop timeout.
    fun setChatVisible(visible: Boolean) {
        chatVisible = visible
        if (chatVisible) {
            cancelTakeOverNotification()
            clearTaskResultNotification()
        } else {
            state.value.toolApproval?.let { postTakeOverNotification(it.details.title) }
        }
    }

    private fun clearTaskResultNotification() {
        getSystemService(NotificationManager::class.java).cancel(TASK_RESULT_NOTIFICATION_ID)
        if (availableApp != null && !state.value.isAgentRunning) {
            // Dismiss the result presentation while retaining the required quiet service entry.
            startJobForeground()
        }
    }

    companion object {
        private const val TAG = "AgentService"
        private const val PREVIEW_FRAME_INTERVAL_MS = 33L
        const val EXTRA_CONVERSATION_ID = "moe.reimu.nekoassistant.extra.CONVERSATION_ID"
        const val EXTRA_AGENT_PROMPT = "moe.reimu.nekoassistant.extra.AGENT_PROMPT"
        private const val FOREGROUND_CHANNEL_ID = "agent_job"
        private const val FOREGROUND_CHANNEL_NAME = "Agent job"
        private const val FOREGROUND_CHANNEL_DESCRIPTION = "Runs agent automation tasks"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val TASK_RESULT_NOTIFICATION_ID = 1002
        private const val TASK_EVENTS_CHANNEL_ID = "agent_task_results"
        private const val TAKE_OVER_NOTIFICATION_ID = 1003
        private const val TAKE_OVER_CHANNEL_ID = "agent_take_over"
        private const val TAKE_OVER_CHANNEL_NAME = "Agent needs you"
        private const val TAKE_OVER_CHANNEL_DESCRIPTION =
            "Asks for help while an agent task is waiting on you"
    }
}
