package moe.reimu.nekoassistant

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import moe.reimu.nekoassistant.ui.ConversationDrawer
import moe.reimu.nekoassistant.ui.AgentStatusRow
import moe.reimu.nekoassistant.ui.ChatBubble
import moe.reimu.nekoassistant.ui.ChatInputBar
import moe.reimu.nekoassistant.ui.DefaultCard
import moe.reimu.nekoassistant.ui.LiveScreenPreview
import moe.reimu.nekoassistant.ui.LiveScreenPreviewHeight
import moe.reimu.nekoassistant.ui.ProviderManagementPage
import moe.reimu.nekoassistant.ui.SelectedLlmConfigurationCard
import moe.reimu.nekoassistant.ui.ToolApprovalDialog
import moe.reimu.nekoassistant.ui.TakeOverNotice
import moe.reimu.nekoassistant.ui.AvailableAppNotice
import moe.reimu.nekoassistant.ui.theme.NekoAssistantTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val requestedConversation = intent.getStringExtra(AgentService.EXTRA_CONVERSATION_ID)
        intent.removeExtra(AgentService.EXTRA_CONVERSATION_ID)
        setContent {
            NekoAssistantTheme {
                MainActivityContent(requestedConversation)
            }
        }
    }
}

@Composable
fun MainActivityContent(requestedConversation: String? = null) {
    val navController = rememberNavController()
    var pendingConversation by remember { mutableStateOf(requestedConversation) }

    Surface {
        NavHost(
            navController = navController,
            startDestination = "main",
        ) {
            composable("main") {
                MainPage(requestedConversation = pendingConversation,
                    onConversationRequestHandled = { pendingConversation = null },
                    onManageProviders = { navController.navigate("providers") })
            }
            composable("providers") {
                ProviderManagementPage(onBack = { navController.popBackStack() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun MainPage(
    mainViewModel: MainViewModel = viewModel(),
    requestedConversation: String? = null,
    onConversationRequestHandled: () -> Unit = {},
    onManageProviders: () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(requestedConversation) {
        requestedConversation?.let {
            mainViewModel.selectConversation(it)
            onConversationRequestHandled()
        }
    }
    LifecycleResumeEffect(mainViewModel to drawerState.isOpen) {
        mainViewModel.setChatVisible(!drawerState.isOpen)
        onPauseOrDispose { mainViewModel.setChatVisible(false) }
    }
    val context = LocalContext.current
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val notificationPermissionState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(
            android.Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        object : PermissionState {
            override val permission = "NOT_NEEDED"
            override val status = PermissionStatus.Granted
            override fun launchPermissionRequest() {}
        }
    }

    val listState = key(uiState.selectedConversationId) { rememberLazyListState() }

    // Follow the newest message, unless the user scrolled up to read something — an agent
    // turn streams for a while, and yanking the transcript away mid-read is unusable.
    var followTail by remember(uiState.selectedConversationId) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .drop(1)
            .collect { scrolling ->
                if (scrolling) {
                    return@collect
                }
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                followTail = info.totalItemsCount == 0 || last?.index == info.totalItemsCount - 1
            }
    }
    LaunchedEffect(uiState.selectedConversationId, uiState.agent.messages.lastOrNull()?.text, followTail) {
        if (followTail && uiState.agent.messages.isNotEmpty()) {
            listState.scrollToItem(uiState.agent.messages.lastIndex)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                conversations = uiState.conversations,
                selectedId = uiState.selectedConversationId,
                activeId = uiState.activeConversationId,
                canManage = !uiState.isChatOperationPending,
                onDelete = mainViewModel::deleteConversation,
                onSelect = { id -> mainViewModel.selectConversation(id); scope.launch { drawerState.close() } },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = uiState.title, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Chat history")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = mainViewModel::newChat,
                            enabled = uiState.activeConversationId == null && !uiState.isChatOperationPending,
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddComment,
                                contentDescription = "New chat",
                            )
                        }
                        IconButton(onClick = onManageProviders) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "LLM providers",
                            )
                        }
                    },
                )
            },
            bottomBar = {
                Column {
                    if (uiState.isReadOnly) {
                        Text("This chat is read-only while another chat is active.", modifier = Modifier.padding(16.dp))
                        TextButton(onClick = { uiState.activeConversationId?.let(mainViewModel::selectConversation) }) {
                            Text("Return to active chat")
                        }
                    } else {
                        uiState.agent.toolApproval?.let { request ->
                            key(request.id) {
                                ToolApprovalDialog(request, mainViewModel::respondToToolApproval)
                            }
                        }
                        uiState.agent.takeOverMessage?.let { message ->
                            TakeOverNotice(message = message, onResume = mainViewModel::resumeAgent)
                        }
                        if (!uiState.agent.isAgentRunning) {
                            uiState.agent.availableApp?.let { app ->
                                AvailableAppNotice(
                                    appName = app.label,
                                    onBringToFront = { mainViewModel.bringAppToFront(context) },
                                    onCloseDisplay = mainViewModel::closeRetainedDisplay,
                                )
                            }
                        }
                        // Only while something is running: a finished job leaves its last status,
                        // including a failed one, sitting in the map.
                        if (uiState.agent.isAgentRunning) {
                            AgentStatusRow(uiState.agent.inferenceStatuses)
                        }
                        ChatInputBar(
                            value = uiState.agentPrompt,
                            onValueChange = mainViewModel::updateAgentPrompt,
                            isRunning = uiState.agent.isAgentRunning,
                            canSend = uiState.isServiceConnected && !uiState.isChatOperationPending && uiState.agentPrompt.isNotBlank() && uiState.hasActiveLlmConfiguration,
                            onSend = mainViewModel::startAgent,
                            onStop = mainViewModel::stopAgent,
                        )
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    SetupBanners(
                        shizukuStatus = uiState.agent.shizukuStatus,
                        onRequestShizukuPermission = mainViewModel::requestShizukuPermission,
                        notificationPermissionState = notificationPermissionState,
                        uiState = uiState,
                        onManageProviders = onManageProviders,
                    )

                    uiState.localError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    }
                    if (uiState.agent.messages.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Tell the agent what to do on the virtual display.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(32.dp),
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 16.dp,
                                // Room for the floating preview to sit over.
                                bottom = if (uiState.agent.hasDisplay) {
                                    LiveScreenPreviewHeight + 32.dp
                                } else {
                                    16.dp
                                },
                            ),
                        ) {
                            items(uiState.agent.messages) { message ->
                                ChatBubble(message)
                            }
                        }
                    }
                }

                if (uiState.agent.hasDisplay) PreviewOverlay(mainViewModel)
            }
        }
    }
}

/** Anything the app needs before it can run, above the transcript rather than in it. */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun SetupBanners(
    shizukuStatus: ShizukuStatus,
    onRequestShizukuPermission: () -> Unit,
    notificationPermissionState: PermissionState,
    uiState: UiState,
    onManageProviders: () -> Unit,
) {
    if (shizukuStatus.granted && shizukuStatus.available &&
        notificationPermissionState.status == PermissionStatus.Granted &&
        uiState.hasActiveLlmConfiguration && uiState.agent.errorMessage == null
    ) {
        return
    }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!shizukuStatus.granted || !shizukuStatus.available) {
            ShizukuCard(shizukuStatus, onRequestShizukuPermission)
        }

        if (notificationPermissionState.status != PermissionStatus.Granted) {
            DefaultCard(onClick = { notificationPermissionState.launchPermissionRequest() }) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Notification permission not granted",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }

        if (!uiState.hasActiveLlmConfiguration) {
            SelectedLlmConfigurationCard(
                providers = uiState.llmProviders,
                onClick = onManageProviders,
            )
        }

        uiState.agent.errorMessage?.let { message ->
            DefaultCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Error",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
fun ShizukuCard(status: ShizukuStatus, onRequestPermission: () -> Unit) {
    DefaultCard(onClick = {
        if (!status.granted) onRequestPermission()
    }) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (status.available) {
                    if (status.granted) {
                        "Shizuku granted"
                    } else {
                        "Shizuku not granted"
                    }
                } else {
                    "Shizuku unavailable"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Shizuku permission required for virtual displays",
            )
        }
    }
}

/** Frame changes recompose only the preview, not the transcript and composer. */
@Composable
private fun PreviewOverlay(mainViewModel: MainViewModel) {
    val bitmap by mainViewModel.previewFrames.collectAsStateWithLifecycle(initialValue = null)
    bitmap?.let { LiveScreenPreview(it) }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MainActivityContent()
}
