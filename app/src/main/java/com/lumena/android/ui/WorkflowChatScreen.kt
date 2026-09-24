package com.lumena.android.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lumena.android.agent.core.AgentControlState
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.local.PlannerDecision
import com.lumena.android.agent.local.TermuxBridgeClient
import com.lumena.android.agent.local.ToolGate
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.runtime.AgentRunCoordinator
import com.lumena.android.llama.EmbeddedLlamaClient
import com.lumena.android.llama.EmbeddedLlamaRuntime
import com.lumena.android.llama.LlamaHardwareProfile
import com.lumena.android.ollama.ChatModelClient
import com.lumena.android.ollama.LocalWorkflowAgent
import com.lumena.android.ollama.ModelContextUsage
import com.lumena.android.ollama.OllamaClient
import com.lumena.android.ollama.OllamaMessage
import com.lumena.android.ollama.PendingWorkflowTool
import com.lumena.android.ollama.WorkflowOutcome
import com.lumena.android.ollama.WorkflowImage
import com.lumena.android.ollama.WorkflowRunner
import com.lumena.android.settings.LocalSessionSnapshot
import com.lumena.android.settings.LocalSessionStore
import com.lumena.android.settings.ContextCheckpointStore
import com.lumena.android.settings.AdaptiveWebResearchStore
import com.lumena.android.agent.core.ContextKernel
import com.lumena.android.agent.core.EvidenceApplicationStatus
import com.lumena.android.agent.core.FollowUpGoal
import com.lumena.android.agent.core.ProjectContextResolver
import com.lumena.android.agent.core.PreviousTaskOutcomeContext
import com.lumena.android.agent.core.ResearchThreadResolver
import com.lumena.android.agent.core.ResearchThreadState
import com.lumena.android.agent.core.ReflexRuntimeAdvice
import com.lumena.android.settings.ContextGenomeStats
import com.lumena.android.settings.ContextGenomeStore
import com.lumena.android.settings.ConstitutionGenomeStore
import com.lumena.android.settings.ExperienceMemoryStore
import com.lumena.android.settings.EvidenceGraphStore
import com.lumena.android.settings.ExperienceLandscapeStore
import com.lumena.android.settings.CoordinatorExperienceStore
import com.lumena.android.settings.ReflexExperienceRanker
import com.lumena.android.settings.GenomeCapsule
import com.lumena.android.settings.GenomeUnpackedUnit
import com.lumena.android.settings.LumenaPreferences
import com.lumena.android.settings.PersistedChatImage
import com.lumena.android.settings.PersistedChatMessage
import com.lumena.android.settings.PersistedHistoryMessage
import com.lumena.android.settings.PersistedPendingTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

private data class ChatBubble(
    val role: String,
    val text: String,
    val images: List<WorkflowImage> = emptyList()
)

private val remoteImageClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowChatScreen(
    agentWorkScope: CoroutineScope? = null,
    runCoordinator: AgentRunCoordinator? = null,
    onOpenHistory: (() -> Unit)? = null,
    onOpenAgent: (() -> Unit)? = null
) {
    val uiScope = rememberCoroutineScope()
    val workScope = agentWorkScope ?: uiScope
    val fallbackCoordinator = remember { AgentRunCoordinator() }
    val coordinator = runCoordinator ?: fallbackCoordinator
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val initial = remember { LumenaPreferences.load(context) }
    val restored = remember { LocalSessionStore.load(context) }
    val systemMessage = remember { OllamaMessage("system", LocalWorkflowAgent.systemPrompt) }

    val taskApprovals = remember { mutableStateMapOf<String, Set<String>>() }

    val bubbles = remember {
        mutableStateListOf<ChatBubble>().apply {
            val restoredChat = restored.chat.map { message ->
                ChatBubble(
                    role = message.role,
                    text = message.text,
                    images = message.images.map { image ->
                        WorkflowImage(
                            title = image.title,
                            thumbnailUrl = image.thumbnailUrl,
                            sourcePage = image.sourcePage,
                            source = image.source
                        )
                    }
                )
            }
            if (restoredChat.isNotEmpty()) addAll(restoredChat)
            else add(ChatBubble("assistant", "Привіт. Я Lumena. Чим можу допомогти?"))
        }
    }

    var input by rememberSaveable { mutableStateOf(restored.inputDraft) }
    var ollamaUrl by rememberSaveable { mutableStateOf(initial.ollamaUrl) }
    var bridgeUrl by rememberSaveable { mutableStateOf(initial.bridgeUrl) }
    var bridgeToken by rememberSaveable { mutableStateOf(initial.bridgeToken) }
    var selectedModel by rememberSaveable { mutableStateOf(initial.selectedModel) }
    var inferenceBackend by rememberSaveable { mutableStateOf(initial.inferenceBackend) }
    var computeMode by rememberSaveable { mutableStateOf(initial.computeMode) }
    var ggufPath by rememberSaveable { mutableStateOf(initial.ggufPath) }
    var ggufPickerStatus by rememberSaveable { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("Checking Ollama…") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var progressExpanded by rememberSaveable { mutableStateOf(false) }
    var currentTask by remember { mutableStateOf(restored.task) }
    var history by remember {
        mutableStateOf(listOf(systemMessage) + restored.history.map { OllamaMessage(it.role, it.content) })
    }
    var contextUsage by remember { mutableStateOf<ModelContextUsage?>(null) }
    var researchThread by remember {
        mutableStateOf(
            restored.researchThread
                ?: restored.researchGoal?.let { legacy ->
                    ResearchThreadState(rootGoal = legacy)
                }
        )
    }
    var busy by remember {
        mutableStateOf(
            restored.task?.status in setOf(
                TaskStatus.PLANNING,
                TaskStatus.WAITING_MODEL,
                TaskStatus.EXECUTING,
                TaskStatus.VERIFYING
            )
        )
    }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var experienceMemoryRevision by remember { mutableStateOf(0) }

    val experienceStats = remember(showSettings, experienceMemoryRevision) {
        ExperienceMemoryStore.stats(context)
    }
    val genomeStats = remember(showSettings, experienceMemoryRevision) {
        ContextGenomeStore.stats(context)
    }
    val genomeCapsules = remember(showSettings, experienceMemoryRevision) {
        ContextGenomeStore.capsules(context, limit = 6)
    }
    val ggufDisplayName = remember(ggufPath) { resolveGgufDisplayName(context, ggufPath) }
    val hardwareProfile = remember(showSettings, busy) { LlamaHardwareProfile.detect(context) }
    val modelLabel = when {
        inferenceBackend == "embedded" && ggufDisplayName.isNotBlank() -> ggufDisplayName
        inferenceBackend == "embedded" -> "Choose GGUF"
        selectedModel.isNotBlank() -> selectedModel
        else -> "No model"
    }
    val backendLabel = if (inferenceBackend == "embedded") {
        val runtime = EmbeddedLlamaRuntime.backendLabel()
        if (runtime == "not loaded yet") "Local" else runtime
    } else "Ollama"

    val ggufPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val selectedName = resolveGgufDisplayName(context, uri.toString())
            if (!selectedName.endsWith(".gguf", ignoreCase = true)) {
                ggufPickerStatus = "Choose a .gguf model file."
            } else {
                val permissionSaved = runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.isSuccess
                EmbeddedLlamaClient.cancelActiveGeneration()
                uiScope.launch { EmbeddedLlamaRuntime.unload() }
                ggufPath = uri.toString()
                inferenceBackend = "embedded"
                LumenaPreferences.saveGgufPath(context, ggufPath)
                LumenaPreferences.saveInferenceBackend(context, "embedded")
                ggufPickerStatus = if (permissionSaved) "✓ $selectedName · access saved" else "✓ $selectedName · selected"
            }
        }
    }

    fun restoredPendingFrom(saved: PersistedPendingTool?): PendingWorkflowTool? = saved?.let {
        val planned = ToolGate.plan(PlannerDecision(request = ToolRequest(it.tool, it.args, it.requestId), reason = it.reason))
        val control = it.control ?: currentTask?.let { task -> AgentControlState(task = task) } ?: return@let null
        if (planned.allowed) {
            PendingWorkflowTool(
                plan = planned,
                history = listOf(systemMessage) + it.history.map { h -> OllamaMessage(h.role, h.content) },
                control = control,
                images = it.images.map { image ->
                    WorkflowImage(
                        title = image.title,
                        thumbnailUrl = image.thumbnailUrl,
                        sourcePage = image.sourcePage,
                        source = image.source
                    )
                }
            )
        } else null
    }

    var pending by remember { mutableStateOf(restoredPendingFrom(restored.pending)) }

    fun persistSession() {
        LocalSessionStore.save(
            context,
            LocalSessionSnapshot(
                chat = bubbles.map { bubble ->
                    PersistedChatMessage(
                        role = bubble.role,
                        text = bubble.text,
                        images = bubble.images.map { image ->
                            PersistedChatImage(
                                title = image.title,
                                thumbnailUrl = image.thumbnailUrl,
                                sourcePage = image.sourcePage,
                                source = image.source
                            )
                        }
                    )
                },
                history = history.filterNot { it.role == "system" }
                    .map { PersistedHistoryMessage(it.role, it.content) },
                task = currentTask,
                pending = pending?.let { active ->
                    PersistedPendingTool(
                        tool = active.plan.request.tool,
                        args = active.plan.request.args,
                        requestId = active.plan.request.requestId,
                        reason = active.plan.reason,
                        control = active.control,
                        history = active.history.filterNot { it.role == "system" }
                            .map { PersistedHistoryMessage(it.role, it.content) },
                        images = active.images.map { image ->
                            PersistedChatImage(
                                title = image.title,
                                thumbnailUrl = image.thumbnailUrl,
                                sourcePage = image.sourcePage,
                                source = image.source
                            )
                        }
                    )
                },
                inputDraft = input,
                researchGoal = researchThread?.rootGoal,
                researchThread = researchThread
            )
        )
    }

    fun isCurrentTask(taskId: String): Boolean = LocalSessionStore.load(context).task?.id == taskId

    fun isApprovedForTask(taskId: String, request: ToolRequest): Boolean =
        ToolGate.approvalKey(request) in taskApprovals[taskId].orEmpty()

    fun grantApprovalForTask(taskId: String, request: ToolRequest) {
        val key = ToolGate.approvalKey(request)
        taskApprovals[taskId] = taskApprovals[taskId].orEmpty() + key
    }

    fun acceptControl(taskId: String, runToken: Long, control: AgentControlState) {
        if (!coordinator.isCurrent(runToken, taskId) || !isCurrentTask(taskId)) return
        currentTask = control.task
        persistSession()
    }

    fun syncFromStoredSession() {
        val stored = LocalSessionStore.load(context)
        if (stored.task?.id != currentTask?.id) return
        if (stored.task != currentTask) currentTask = stored.task

        val storedChat = stored.chat.map { message ->
            ChatBubble(
                role = message.role,
                text = message.text,
                images = message.images.map { image ->
                    WorkflowImage(
                        title = image.title,
                        thumbnailUrl = image.thumbnailUrl,
                        sourcePage = image.sourcePage,
                        source = image.source
                    )
                }
            )
        }
        if (storedChat.isNotEmpty() && storedChat != bubbles.toList()) {
            bubbles.clear()
            bubbles.addAll(storedChat)
        }

        val storedHistory = listOf(systemMessage) + stored.history.map { OllamaMessage(it.role, it.content) }
        if (storedHistory != history) history = storedHistory

        val storedPending = restoredPendingFrom(stored.pending)
        if (storedPending?.plan?.request != pending?.plan?.request) pending = storedPending
        val storedThread = stored.researchThread
            ?: stored.researchGoal?.let { legacy ->
                ResearchThreadState(rootGoal = legacy)
            }
        if (storedThread != researchThread) researchThread = storedThread

        busy = stored.task?.status in setOf(
            TaskStatus.PLANNING, TaskStatus.WAITING_MODEL, TaskStatus.EXECUTING, TaskStatus.VERIFYING
        )
    }

    fun stopCurrentTask(reason: String = "Stopped by user") {
        val task = currentTask ?: return
        EmbeddedLlamaClient.cancelActiveGeneration()
        coordinator.cancel(reason)
        pending = null
        busy = false
        taskApprovals.remove(task.id)
        currentTask = task.copy(status = TaskStatus.CANCELLED)
        persistSession()
    }

    fun clearConversation() {
        EmbeddedLlamaClient.cancelActiveGeneration()
        coordinator.cancel("New conversation")
        coordinator.clearFinished()
        input = ""
        pending = null
        taskApprovals.clear()
        currentTask = null
        history = listOf(systemMessage)
        contextUsage = null
        researchThread = null
        bubbles.clear()
        bubbles += ChatBubble("assistant", "Новий чат. Що хочеш зробити?")
        busy = false
        LocalSessionStore.clear(context)
        persistSession()
    }

    fun bridgeOrNull(): TermuxBridgeClient? {
        val saved = LumenaPreferences.load(context)
        val effectiveToken = LumenaPreferences.normalizeBridgeToken(
            bridgeToken.ifBlank { saved.bridgeToken }
        )
        if (effectiveToken.isBlank()) return null

        val effectiveUrl = bridgeUrl.ifBlank { saved.bridgeUrl }
        return TermuxBridgeClient(effectiveUrl, effectiveToken, context)
    }

    fun modelClient(): ChatModelClient =
        if (inferenceBackend == "embedded") {
            EmbeddedLlamaClient(context, ggufPath, computeMode = computeMode)
        } else {
            OllamaClient(
                ollamaUrl,
                runtimeProfile = LlamaHardwareProfile.detect(context)
            )
        }

    fun modelNameForRun(): String = if (inferenceBackend == "embedded") "embedded-gguf" else selectedModel

    fun workflowRunner(): WorkflowRunner {
        // Capture one environment for this runner; later UI changes cannot relabel its observations.
        val session = ExperienceLandscapeStore.session(context, inferenceBackend,
            if (inferenceBackend == "embedded") ggufPath else selectedModel, computeMode,
            "$bridgeUrl|$ollamaUrl")
        val researchGoalSnapshot = researchThread?.rootGoal
        val constitutionContributorModelId = if (inferenceBackend == "embedded") {
            "embedded:" + ggufDisplayName.ifBlank { "gguf" }
        } else {
            "ollama:" + selectedModel.ifBlank { "unknown" }
        }
        return WorkflowRunner(modelClient(), bridgeOrNull(), modelNameForRun(),
            relevantMemoryProvider = { task ->
                val advice = try {
                    ExperienceLandscapeStore.advice(context, session, task)
                } catch (_: Exception) {
                    listOf(
                        "Learned advice unavailable; use current task state and fixed controller rules."
                    )
                }
                val verifiedMemory = try {
                    ExperienceMemoryStore.relevant(context, task.goal)
                } catch (_: Exception) {
                    listOf(
                        "Verified memory unavailable; do not infer prior execution success."
                    )
                }
                val coordinatorExamples = try {
                    CoordinatorExperienceStore.relevant(
                        context = context,
                        query = task.goal,
                        limit = 4
                    )
                } catch (_: Exception) {
                    listOf(
                        "Coordinator playbook unavailable; continue from current verified evidence only."
                    )
                }
                (
                    advice.take(2) +
                        coordinatorExamples.take(2) +
                        verifiedMemory.take(4)
                    )
                    .distinct()
                    .take(8)
            },
            webStrategyAdviceProvider = { task, modelHistory ->
                val researchGoal =
                    researchGoalSnapshot
                        ?.takeIf { it.isNotBlank() }
                        ?: task.goal
                try {
                    AdaptiveWebResearchStore.advice(
                        context = context,
                        researchGoal = researchGoal,
                        history = modelHistory,
                        limit = 3
                    )
                } catch (_: Exception) {
                    listOf(
                        "JEV-like web calibration unavailable; continue with ordinary verified web research."
                    )
                }
            },
            evidenceProvider = { task ->
                EvidenceGraphStore.relevant(
                    context = context,
                    query = task.goal,
                    limit = 4
                )
            },
            constitutionProvider = { task ->
                ConstitutionGenomeStore.relevant(
                    context = context,
                    task = task,
                    limit = 6
                )
            },
            reflexAdviceProvider = { event, candidates, task ->
                try {
                    val query = buildString {
                        append(task.goal)
                        event.actionFamily
                            ?.takeIf { it.isNotBlank() }
                            ?.let { append(' ').append(it) }
                        append(' ').append(event.failureClass.name)
                    }
                    val examples = CoordinatorExperienceStore.examples(
                        context = context,
                        query = query,
                        limit = 64
                    )
                    val recommendation = ReflexExperienceRanker.rank(
                        event = event,
                        candidates = candidates,
                        examples = examples
                    )
                    recommendation.choice?.let { choice ->
                        ReflexRuntimeAdvice(
                            option = choice.best(),
                            confidence = choice.confidence,
                            evidenceCount = choice.evidenceCount,
                            calibrated = recommendation.calibrated
                        )
                    }
                } catch (_: Exception) {
                    null
                }
            },
            checkpoint = { control ->
                withContext(Dispatchers.IO) { ContextCheckpointStore.save(context, control) }
            },
            onToolExperience = { task, request, result, elapsedMs ->
                val eventId = ExperienceMemoryStore.record(context, request, result)

                // Advisory projections only. Failure here must never erase or
                // block the older verified experience stores below.
                runCatching {
                    AdaptiveWebResearchStore.record(
                        context = context,
                        task = task,
                        request = request,
                        result = result,
                        elapsedMs = elapsedMs,
                        evidenceId = eventId
                    )
                }
                runCatching {
                    EvidenceGraphStore.record(
                        context = context,
                        task = task,
                        request = request,
                        result = result,
                        evidenceId = eventId
                    )
                }
                task.projectId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { projectId ->
                        // Binding is advisory and is created only after this
                        // already-authorized mutation produced a known-success
                        // TOOL_RESULT. It cannot initiate or authorize a tool.
                        runCatching {
                            EvidenceGraphStore.autoBindForSuccessfulMutation(
                                context = context,
                                taskProjectId = projectId,
                                taskGoal = task.goal,
                                request = request,
                                result = result
                            )
                        }
                        val projectOutcomes =
                            runCatching {
                                EvidenceGraphStore
                                    .recordMatchingProjectOutcomes(
                                        context = context,
                                        taskProjectId = projectId,
                                        request = request,
                                        result = result,
                                        evidenceId = eventId
                                    )
                            }.getOrDefault(emptyList())

                        projectOutcomes
                            .asSequence()
                            .filter { it.accepted }
                            .mapNotNull { update ->
                                val bindingId =
                                    update.bindingId
                                        ?: return@mapNotNull null
                                update.state.applications
                                    .firstOrNull {
                                        it.id == bindingId &&
                                            it.status ==
                                            EvidenceApplicationStatus.VERIFIED
                                    }
                            }
                            .distinctBy { it.id }
                            .forEach { binding ->
                                runCatching {
                                    ConstitutionGenomeStore
                                        .ingestVerifiedProjectApplication(
                                            context = context,
                                            task = task,
                                            binding = binding,
                                            contributorModelId =
                                                constitutionContributorModelId
                                        )
                                }
                            }
                    }

                val episodeSessionId = task.projectId
                    ?.takeIf { it.isNotBlank() }
                    ?: task.id

                val coordinatorResult = runCatching {
                    CoordinatorExperienceStore.record(
                        context = context,
                        sessionId = episodeSessionId,
                        taskId = task.id,
                        request = request,
                        result = result,
                        experienceId = eventId,
                        modelId = constitutionContributorModelId
                    )
                }
                val coordinatorFailure = coordinatorResult.exceptionOrNull()

                val constitutionFailure =
                    if (coordinatorResult.isSuccess) {
                        runCatching {
                            val examples =
                                CoordinatorExperienceStore.examplesForTask(
                                    context = context,
                                    sessionId = episodeSessionId,
                                    taskId = task.id,
                                    limit = 64
                                )
                            ConstitutionGenomeStore.ingestVerifiedRecoveryExamples(
                                context = context,
                                task = task,
                                examples = examples,
                                contributorModelId = constitutionContributorModelId
                            )
                        }.exceptionOrNull()
                    } else {
                        null
                    }

                // New projections must not erase older verified experience.
                ExperienceLandscapeStore.record(
                    context,
                    session,
                    task,
                    request,
                    result,
                    elapsedMs,
                    eventId
                )

                coordinatorFailure?.let { throw it }
                constitutionFailure?.let { throw it }
            })
    }

    fun refreshModels() {
        status = "Checking Ollama…"
        uiScope.launch {
            val result = try { OllamaClient(ollamaUrl).listModels() } catch (t: Throwable) { Result.failure(t) }
            result.onSuccess { found ->
                models = found
                val resolved = when {
                    selectedModel.isNotBlank() && selectedModel in found -> selectedModel
                    found.isNotEmpty() -> found.first()
                    else -> selectedModel
                }
                if (resolved != selectedModel) {
                    selectedModel = resolved
                    LumenaPreferences.saveSelectedModel(context, resolved)
                }
                status = if (found.isEmpty()) "Ollama online · no local models" else "Ollama online"
            }.onFailure {
                models = emptyList()
                status = "Ollama offline"
            }
        }
    }

    fun reportContextUsage(
        taskId: String,
        runToken: Long,
        usage: ModelContextUsage
    ) {
        uiScope.launch {
            if (
                coordinator.isCurrent(runToken, taskId) &&
                isCurrentTask(taskId)
            ) {
                contextUsage = usage
            }
        }
    }

    fun reportProgress(taskId: String, runToken: Long, message: String) {
        if (message.isBlank() || !coordinator.isCurrent(runToken, taskId) || !isCurrentTask(taskId)) return
        coordinator.addProgress(runToken, message)
        // Progress belongs to the compact agent status, not the conversation itself.
    }

    fun applyOutcome(taskId: String, runToken: Long, outcome: WorkflowOutcome) {
        if (!coordinator.isCurrent(runToken, taskId) || !isCurrentTask(taskId)) return
        when (outcome) {
            is WorkflowOutcome.Finished -> {
                val finishedTask = outcome.control.task
                history =
                    if (finishedTask.status == TaskStatus.PARTIAL) {
                        outcome.history +
                            OllamaMessage(
                                "user",
                                PreviousTaskOutcomeContext.partial(
                                    task = finishedTask,
                                    message = outcome.text
                                )
                            )
                    } else {
                        outcome.history
                    }
                pending = null
                currentTask = finishedTask
                bubbles += ChatBubble(
                    role = "assistant",
                    text = outcome.text,
                    images = outcome.images
                )
                coordinator.finish(
                    runToken,
                    if (finishedTask.status == TaskStatus.PARTIAL) {
                        "Частково виконано"
                    } else {
                        "Done"
                    }
                )
                taskApprovals.remove(taskId)
            }
            is WorkflowOutcome.NeedsConfirmation -> {
                pending = outcome.pending
                history = outcome.pending.history
                currentTask = outcome.pending.control.task
                coordinator.pauseForApproval(runToken)
            }
            is WorkflowOutcome.Failed -> {
                val failedTask = outcome.control.task
                history =
                    outcome.history +
                        OllamaMessage(
                            "user",
                            PreviousTaskOutcomeContext.failure(
                                task = failedTask,
                                message = outcome.message
                            )
                        )
                pending = null
                currentTask = failedTask
                bubbles += ChatBubble("error", outcome.message)
                coordinator.finish(runToken, "Failed")
                taskApprovals.remove(taskId)
            }
        }
        researchThread = ResearchThreadResolver.observeHistory(
            history = history,
            thread = researchThread
        )
        busy = currentTask?.status in setOf(
            TaskStatus.PLANNING, TaskStatus.WAITING_MODEL, TaskStatus.EXECUTING, TaskStatus.VERIFYING
        )
        persistSession()
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank() || busy) return
        if (inferenceBackend == "embedded" && ggufPath.isBlank()) {
            showSettings = true
            ggufPickerStatus = "Choose a GGUF model first."
            return
        }
        if (inferenceBackend == "ollama" && selectedModel.isBlank()) {
            showSettings = true
            return
        }

        input = ""
        val previous = currentTask
        val resolution = ResearchThreadResolver.resolve(
            text = text,
            previousGoal = previous?.goal,
            thread = researchThread
        )
        val resolvedGoal = resolution.goal
        researchThread = resolution.thread
        val referenceUsesPreviousTask =
            previous != null &&
                FollowUpGoal.isReference(text) &&
                previous.goal == resolvedGoal
        val projectId = ProjectContextResolver.resolve(
            text = text,
            previousProjectId = previous?.projectId,
            carryForward =
                referenceUsesPreviousTask ||
                    resolution.followUpKind.name !=
                    "NONE"
        )
        val task = TaskState(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            goal = resolvedGoal,
            status = TaskStatus.WAITING_MODEL
        )
        taskApprovals.clear()
        currentTask = task
        bubbles += ChatBubble("user", text)

        val previousContext = if (referenceUsesPreviousTask)
            listOf(OllamaMessage("user", "HISTORICAL TASK CHECKPOINT; verify current state before acting. " +
                "Earlier tool success is not proof for this new task.\n" + ContextKernel.capsule(previous.kernel, 1200)))
            else emptyList()
        val researchContext = resolution.contextMessage
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(OllamaMessage("user", it)) }
            .orEmpty()
        val turnHistory =
            history +
                previousContext +
                researchContext +
                OllamaMessage(
                    "user",
                    if (resolvedGoal != text) {
                        "$text\nResolved research task:\n$resolvedGoal"
                    } else {
                        text
                    }
                )
        history = turnHistory
        busy = true
        persistSession()

        coordinator.launch(workScope, task.id, resetProgress = true) { runToken ->
            val outcome = try {
                workflowRunner().run(
                    history = turnHistory,
                    task = task,
                    onProgress = { reportProgress(task.id, runToken, it) },
                    onModelText = { text ->
                        if (text.isEmpty()) coordinator.beginModelTurn(runToken)
                        else coordinator.updateModelText(runToken, text)
                    },
                    onToolTelemetry = { coordinator.updateToolTelemetry(runToken, it) },
                    onContextUsage = {
                        reportContextUsage(task.id, runToken, it)
                    },
                    isApprovedForTask = ::isApprovedForTask,
                    onState = { acceptControl(task.id, runToken, it) }
                )
            } catch (_: CancellationException) {
                return@launch
            } catch (t: Throwable) {
                val failedTask = (currentTask?.takeIf { it.id == task.id } ?: task)
                    .copy(status = TaskStatus.FAILED, errors = listOf(t.message ?: t.toString()))
                WorkflowOutcome.Failed(
                    t.message ?: t.toString(),
                    turnHistory,
                    AgentControlState(task = failedTask)
                )
            }
            applyOutcome(task.id, runToken, outcome)
        }
    }

    LaunchedEffect(Unit) {
        val saved = try { withContext(Dispatchers.IO) { ContextCheckpointStore.load(context) } }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            bubbles += ChatBubble("error", "Контрольна точка не читається: ${error.message}")
            null
        }
        if (!coordinator.active && saved != null && saved.task.id == currentTask?.id &&
            (currentTask?.status in setOf(TaskStatus.PLANNING, TaskStatus.WAITING_MODEL, TaskStatus.EXECUTING, TaskStatus.VERIFYING) ||
                (currentTask?.status == TaskStatus.WAITING_CONFIRMATION && saved.task.kernel.inFlight != null))) {
            currentTask = if (saved.task.status == TaskStatus.WAITING_CONFIRMATION)
                saved.task.copy(status = TaskStatus.CANCELLED,
                    errors = saved.task.errors + "Перерване очікування дозволу. Створіть новий запит після перевірки стану.")
                else saved.task
            pending = null // Never re-authorize an action from an interrupted execution.
            busy = false
            persistSession()
        }
        val task = currentTask
        if (task != null &&
            task.status in setOf(TaskStatus.PLANNING, TaskStatus.WAITING_MODEL, TaskStatus.EXECUTING, TaskStatus.VERIFYING) &&
            !coordinator.active
        ) {
            currentTask = task.copy(
                status = TaskStatus.CANCELLED,
                errors = (task.errors + if (task.kernel.inFlight != null)
                    "Результат ${task.kernel.inFlight.tool} невідомий. Перед повтором перевірте фактичний стан."
                    else "Previous agent run was interrupted before this app session resumed.").takeLast(8)
            )
            busy = false
            persistSession()
        }
        refreshModels()
    }

    LaunchedEffect(input) {
        delay(300)
        persistSession()
    }

    LaunchedEffect(currentTask?.id) {
        while (currentTask?.status in setOf(
                TaskStatus.PLANNING, TaskStatus.WAITING_MODEL, TaskStatus.EXECUTING, TaskStatus.VERIFYING
            )) {
            delay(750)
            syncFromStoredSession()
        }
    }

    LaunchedEffect(coordinator.active, coordinator.startedAtMs) {
        while (coordinator.active) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    LaunchedEffect(bubbles.size) {
        if (bubbles.isNotEmpty()) listState.animateScrollToItem(bubbles.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        ModernChatHeader(
            modelLabel = modelLabel,
            backendLabel = backendLabel,
            busy = busy,
            contextUsage = contextUsage,
            onHistory = { onOpenHistory?.invoke() },
            onNew = { clearConversation() },
            onMore = {
                if (onOpenAgent != null) onOpenAgent() else showSettings = true
            },
            onModel = { showSettings = true }
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(
                items = bubbles.filter { it.role != "status" },
                key = { it.hashCode() }
            ) { bubble ->
                MessageBubble(bubble)
            }
        }

        CompactAgentStatus(
            task = currentTask,
            coordinator = coordinator,
            pendingApproval = pending != null,
            nowMs = nowMs,
            expanded = progressExpanded,
            contextUsage = contextUsage,
            onToggle = { progressExpanded = !progressExpanded },
            onStop = { stopCurrentTask() }
        )

        ModernComposer(
            value = input,
            onValueChange = { input = it },
            busy = busy,
            canSend = pending == null && input.isNotBlank(),
            onPlus = { showSettings = true },
            onSend = { send() },
            onStop = { stopCurrentTask() }
        )
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            ModelAndConnectionSheet(
                inferenceBackend = inferenceBackend,
                onBackend = {
                    inferenceBackend = it
                    contextUsage = null
                    LumenaPreferences.saveInferenceBackend(context, it)
                },
                computeMode = computeMode,
                onComputeMode = { mode ->
                    if (!busy && mode != computeMode) {
                        EmbeddedLlamaClient.cancelActiveGeneration()
                        computeMode = mode
                        LumenaPreferences.saveComputeMode(context, mode)
                        uiScope.launch { EmbeddedLlamaRuntime.unload() }
                    }
                },
                ggufPath = ggufPath,
                ggufDisplayName = ggufDisplayName,
                ggufPickerStatus = ggufPickerStatus,
                hardwareSummary = hardwareProfile.summary,
                runtimeSummary = EmbeddedLlamaRuntime.backendLabel(),
                busy = busy,
                onChooseGguf = { ggufPicker.launch(arrayOf("*/*")) },
                onForgetGguf = {
                    EmbeddedLlamaClient.cancelActiveGeneration()
                    uiScope.launch { EmbeddedLlamaRuntime.unload() }
                    ggufPath = ""
                    ggufPickerStatus = "GGUF selection cleared."
                    LumenaPreferences.saveGgufPath(context, "")
                },
                ollamaUrl = ollamaUrl,
                onOllamaUrl = {
                    ollamaUrl = it
                    contextUsage = null
                    LumenaPreferences.saveOllamaUrl(context, it)
                },
                selectedModel = selectedModel,
                onSelectedModel = {
                    selectedModel = it
                    contextUsage = null
                    LumenaPreferences.saveSelectedModel(context, it)
                },
                models = models,
                status = status,
                onRefreshModels = { refreshModels() },
                bridgeUrl = bridgeUrl,
                onBridgeUrl = {
                    bridgeUrl = it
                    LumenaPreferences.saveBridgeUrl(context, it)
                },
                bridgeToken = bridgeToken,
                onBridgeToken = {
                    val clean = LumenaPreferences.normalizeBridgeToken(it)
                    bridgeToken = clean
                    LumenaPreferences.saveBridgeToken(context, clean)
                },
                experiencePositive = experienceStats.positive,
                experienceNegative = experienceStats.negative,
                experienceUnresolved = experienceStats.unresolvedNegative,
                experienceTotal = experienceStats.total,
                genomeStats = genomeStats,
                genomeCapsules = genomeCapsules,
                onUnpackGenome = { id -> ContextGenomeStore.unpack(context, id) },
                onClearExperience = {
                    if (!busy) {
                        ExperienceMemoryStore.clear(context)
                        CoordinatorExperienceStore.clear(context)
                        experienceMemoryRevision += 1
                    }
                }
            )
        }
    }

    pending?.let { requested ->
        fun approvePending(cacheForTask: Boolean) {
            val taskId = requested.control.task.id
            if (cacheForTask) {
                grantApprovalForTask(taskId, requested.plan.request)
            }
            pending = null
            currentTask = requested.control.task.copy(status = TaskStatus.EXECUTING)
            busy = true
            persistSession()

            coordinator.launch(workScope, taskId, resetProgress = false) { runToken ->
                if (cacheForTask) {
                    reportProgress(
                        taskId,
                        runToken,
                        "APPROVAL CACHE · exact action approved for this task"
                    )
                }

                val outcome = try {
                    workflowRunner().approve(
                        pending = requested,
                        onProgress = { reportProgress(taskId, runToken, it) },
                        onModelText = { text ->
                            if (text.isEmpty()) coordinator.beginModelTurn(runToken)
                            else coordinator.updateModelText(runToken, text)
                        },
                        onToolTelemetry = { coordinator.updateToolTelemetry(runToken, it) },
                        onContextUsage = {
                            reportContextUsage(taskId, runToken, it)
                        },
                        isApprovedForTask = ::isApprovedForTask,
                        onState = { acceptControl(taskId, runToken, it) }
                    )
                } catch (_: CancellationException) {
                    return@launch
                } catch (t: Throwable) {
                    val failedControl = requested.control.copy(
                        task = (currentTask?.takeIf { it.id == taskId } ?: requested.control.task).copy(
                            status = TaskStatus.FAILED,
                            errors = (requested.control.task.errors + (t.message ?: t.toString())).takeLast(8)
                        )
                    )
                    WorkflowOutcome.Failed(
                        t.message ?: t.toString(),
                        requested.history,
                        failedControl
                    )
                }
                applyOutcome(taskId, runToken, outcome)
            }
        }

        AlertDialog(
            onDismissRequest = { },
            title = { Text("Allow local action?") },
            text = {
                val taskPlan = if (requested.taskPlan.isEmpty()) "" else
                    requested.taskPlan.mapIndexed { i, step -> "${i + 1}. $step" }
                        .joinToString(prefix = "Plan:\n", separator = "\n", postfix = "\n\n")
                val verify = requested.control.verificationReason
                    ?.let { "\n\nVerification pending: $it" }
                    .orEmpty()
                Text(
                    taskPlan +
                        "${requested.plan.reason}\n\nTool: ${requested.plan.request.tool}\nArgs: ${requested.plan.request.args}" +
                        verify +
                        "\n\nYou can allow this exact action once, or remember this exact tool + args until this task finishes."
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { approvePending(cacheForTask = false) }) {
                        Text("Allow once")
                    }
                    TextButton(onClick = { approvePending(cacheForTask = true) }) {
                        Text("Allow same action for task")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    taskApprovals.remove(requested.control.task.id)
                    stopCurrentTask("Approval cancelled by user")
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ModernChatHeader(
    modelLabel: String,
    backendLabel: String,
    busy: Boolean,
    contextUsage: ModelContextUsage?,
    onHistory: () -> Unit,
    onNew: () -> Unit,
    onMore: () -> Unit,
    onModel: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.clickable(onClick = onHistory),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text("☰", modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.titleLarge)
        }
        Column(
            modifier = Modifier.weight(1f).clickable(onClick = onModel).padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Lumena", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (busy) "Thinking…" else "$backendLabel · $modelLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            contextUsage?.let { usage ->
                Text(
                    usage.compactLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Surface(
            modifier = Modifier.clickable(onClick = onNew),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text("✎", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(1.dp).padding(horizontal = 2.dp))
        TextButton(onClick = onMore) { Text("⋮", style = MaterialTheme.typography.headlineSmall) }
    }
}

@Composable
private fun CompactAgentStatus(
    task: TaskState?,
    coordinator: AgentRunCoordinator,
    pendingApproval: Boolean,
    nowMs: Long,
    expanded: Boolean,
    contextUsage: ModelContextUsage?,
    onToggle: () -> Unit,
    onStop: () -> Unit
) {
    val active = coordinator.active || pendingApproval || task?.status in setOf(
        TaskStatus.PLANNING, TaskStatus.WAITING_MODEL, TaskStatus.EXECUTING,
        TaskStatus.VERIFYING, TaskStatus.WAITING_CONFIRMATION
    )
    if (!active) return

    val elapsed = if (coordinator.startedAtMs > 0L) {
        ((if (coordinator.active) nowMs else System.currentTimeMillis()) - coordinator.startedAtMs).coerceAtLeast(0L)
    } else 0L

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).clickable(onClick = onToggle)) {
                    Text(
                        when {
                            pendingApproval -> "Waiting for approval"
                            coordinator.stage.isNotBlank() -> coordinator.stage
                            else -> "Thinking…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        buildString {
                            task?.let { append("step ${it.step}/${it.maxSteps}") }
                            if (elapsed > 0) {
                                if (isNotEmpty()) append(" · ")
                                append(formatElapsed(elapsed))
                            }
                            append(if (expanded) " · hide details" else " · details")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onStop) { Text("Stop") }
            }
            if (coordinator.active) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            if (expanded) {
                contextUsage?.let { usage ->
                    Text(
                        buildString {
                            append("Context · prompt ")
                            append(if (usage.promptTokensExact) "" else "≈")
                            append(usage.promptTokens)
                            append(" tok · input budget ")
                            append(usage.inputBudgetTokens)
                            append(" · requested window ")
                            append(usage.requestedContextWindowTokens)
                            append(" · output reserve ")
                            append(usage.reservedOutputTokens)
                            usage.generatedTokens?.let { generated ->
                                append(" · generated ")
                                append(generated)
                            }
                            if (usage.compacted) append(" · compacted")
                        },
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Input budget is Lumena's request budget; a cloud provider's hard context limit may differ.",
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                coordinator.progress.takeLast(8).forEach {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernComposer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    canSend: Boolean,
    onPlus: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(30.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            TextButton(onClick = onPlus) { Text("＋", style = MaterialTheme.typography.headlineSmall) }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("Message Lumena") },
                minLines = 1,
                maxLines = 5,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp)
            )
            if (busy) {
                Button(onClick = onStop, shape = RoundedCornerShape(22.dp)) { Text("■") }
            } else {
                Button(
                    enabled = canSend,
                    onClick = onSend,
                    shape = RoundedCornerShape(22.dp)
                ) { Text("↑") }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatBubble) {
    val isUser = message.role == "user"
    if (message.role == "error") {
        FriendlyErrorBubble(message.text)
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            Surface(
                modifier = Modifier.widthIn(max = 330.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(message.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (message.text.isNotBlank()) {
                    Text(
                        message.text,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                message.images.take(4).forEach { image ->
                    RemoteImageCard(image)
                }
            }
        }
    }
}

@Composable
private fun RemoteImageCard(image: WorkflowImage) {
    val uriHandler = LocalUriHandler.current
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = image.thumbnailUrl
    ) {
        value = withContext(Dispatchers.IO) {
            loadRemoteImageBitmap(image.thumbnailUrl)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            bitmap?.let { preview ->
                Image(
                    bitmap = preview,
                    contentDescription = image.title.ifBlank { "Image result" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )
            } ?: Text(
                "Preview unavailable",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (image.title.isNotBlank()) {
                Text(
                    image.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            val sourceLabel = image.source.ifBlank { "Wikimedia Commons" }
            Text(
                sourceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isAllowedImageSourceUrl(image.sourcePage)) {
                TextButton(
                    onClick = {
                        runCatching { uriHandler.openUri(image.sourcePage) }
                    }
                ) {
                    Text("Open source")
                }
            }
        }
    }
}

private fun loadRemoteImageBitmap(rawUrl: String): ImageBitmap? {
    if (!isAllowedImagePreviewUrl(rawUrl)) return null
    val url = rawUrl.toHttpUrlOrNull() ?: return null

    val request = Request.Builder()
        .url(url)
        .header("Accept", "image/*")
        .header("User-Agent", "Lumena-Android/0.12")
        .get()
        .build()

    return runCatching {
        remoteImageClient.newCall(request).execute().use responseUse@ { response ->
            if (!response.isSuccessful) return@responseUse null
            val body = response.body ?: return@responseUse null
            val contentType = body.contentType()?.toString().orEmpty()
            if (!contentType.startsWith("image/", ignoreCase = true)) return@responseUse null

            val maxBytes = 8 * 1024 * 1024
            val declared = body.contentLength()
            if (declared > maxBytes) return@responseUse null

            val bytes = body.byteStream().use { input ->
                readBoundedBytes(input, maxBytes)
            }
            if (bytes == null) return@responseUse null

            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }.getOrNull()
}

private fun readBoundedBytes(
    input: InputStream,
    maxBytes: Int
): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0

    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) return null
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private fun isAllowedImagePreviewUrl(raw: String): Boolean {
    val url = raw.toHttpUrlOrNull() ?: return false
    if (url.scheme != "https") return false
    val host = url.host.lowercase()
    val wikimedia =
        host == "wikimedia.org" ||
            host.endsWith(".wikimedia.org")
    val openverse =
        host == "api.openverse.org" ||
            host == "openverse.org" ||
            host.endsWith(".openverse.org")
    return wikimedia || openverse
}

private fun isAllowedImageSourceUrl(raw: String): Boolean {
    val url = raw.toHttpUrlOrNull() ?: return false
    if (url.scheme != "https") return false
    val host = url.host.lowercase()
    return host == "commons.wikimedia.org" ||
        host == "openverse.org" ||
        host.endsWith(".openverse.org")
}

@Composable
private fun FriendlyErrorBubble(raw: String) {
    var details by remember(raw) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Не вдалося завершити запит.", fontWeight = FontWeight.SemiBold)
            Text(
                if (raw.contains("Unknown tool", ignoreCase = true)) "Модель запросила невідомий інструмент." else "Відкрий деталі, щоб побачити технічну причину.",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Details") }
            if (details) Text(raw, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModelAndConnectionSheet(
    inferenceBackend: String,
    onBackend: (String) -> Unit,
    computeMode: String,
    onComputeMode: (String) -> Unit,
    ggufPath: String,
    ggufDisplayName: String,
    ggufPickerStatus: String,
    hardwareSummary: String,
    runtimeSummary: String,
    busy: Boolean,
    onChooseGguf: () -> Unit,
    onForgetGguf: () -> Unit,
    ollamaUrl: String,
    onOllamaUrl: (String) -> Unit,
    selectedModel: String,
    onSelectedModel: (String) -> Unit,
    models: List<String>,
    status: String,
    onRefreshModels: () -> Unit,
    bridgeUrl: String,
    onBridgeUrl: (String) -> Unit,
    bridgeToken: String,
    onBridgeToken: (String) -> Unit,
    experiencePositive: Int,
    experienceNegative: Int,
    experienceUnresolved: Int,
    experienceTotal: Int,
    genomeStats: ContextGenomeStats,
    genomeCapsules: List<GenomeCapsule>,
    onUnpackGenome: (String) -> GenomeUnpackedUnit?,
    onClearExperience: () -> Unit
) {
    var unpackedGenome by remember(genomeCapsules) { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Model", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onBackend("embedded") }) {
                Text(if (inferenceBackend == "embedded") "✓ Embedded" else "Embedded")
            }
            OutlinedButton(onClick = { onBackend("ollama") }) {
                Text(if (inferenceBackend == "ollama") "✓ Ollama" else "Ollama")
            }
        }

        if (inferenceBackend == "embedded") {
            Text("Compute", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = { onComputeMode("auto") }
                ) { Text(if (computeMode == "auto") "✓ Auto" else "Auto") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { onComputeMode("cpu") }
                ) { Text(if (computeMode == "cpu") "✓ CPU" else "CPU") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { onComputeMode("gpu") }
                ) { Text(if (computeMode == "gpu") "✓ GPU" else "GPU") }
            }
            Text(
                when (computeMode) {
                    "cpu" -> "Requested: CPU only"
                    "gpu" -> "Requested: Vulkan GPU · CPU fallback if unavailable"
                    else -> "Requested: Auto · stability first; large GGUFs use a CPU-safe profile, smaller models may use partial Vulkan offload"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(if (ggufPath.isBlank()) "No GGUF selected" else ggufDisplayName, fontWeight = FontWeight.Medium)
            Text("Hardware: $hardwareSummary", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Actual inference: $runtimeSummary", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PerformanceSettingsAccordion(busy = busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = onChooseGguf) { Text(if (ggufPath.isBlank()) "Choose GGUF" else "Change GGUF") }
                if (ggufPath.isNotBlank()) TextButton(enabled = !busy, onClick = onForgetGguf) { Text("Forget") }
            }
            if (ggufPickerStatus.isNotBlank()) Text(ggufPickerStatus, style = MaterialTheme.typography.labelSmall)
        } else {
            Text(status, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = selectedModel,
                onValueChange = onSelectedModel,
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth(),
                supportingText = { if (models.isNotEmpty()) Text(models.joinToString(" · ")) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefreshModels) { Text("Refresh") }
                if (models.isNotEmpty()) {
                    TextButton(onClick = {
                        val current = models.indexOf(selectedModel).coerceAtLeast(0)
                        onSelectedModel(models[(current + 1) % models.size])
                    }) { Text("Next") }
                }
            }
            OutlinedTextField(
                value = ollamaUrl,
                onValueChange = onOllamaUrl,
                label = { Text("Ollama URL") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        HorizontalDivider()
        Text("Local tools", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = bridgeUrl,
            onValueChange = onBridgeUrl,
            label = { Text("Bridge URL") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = bridgeToken,
            onValueChange = onBridgeToken,
            label = { Text("Bridge token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider()
        Text("Verified experience memory", style = MaterialTheme.typography.titleMedium)
        ExperienceLandscapePanel(busy = busy, refreshKey = experienceTotal.toString() + genomeStats.events)
        Text(
            "Only real TOOL_RESULT outcomes are stored. Model prose is never written as an experience anchor.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Total $experienceTotal · positive $experiencePositive · negative $experienceNegative · unresolved $experienceUnresolved",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()
        Text("Context Genome", style = MaterialTheme.typography.titleMedium)
        Text(
            "events ${genomeStats.events} · anchors ${genomeStats.anchors} · links ${genomeStats.links} · capsules ${genomeStats.capsules}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "A = atomic experience · L1 = tool/target capsule · L2 = topic capsule. Capsules can be unpacked back to verified evidence.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        genomeCapsules.take(6).forEach { capsule ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "L${capsule.level} · ${capsule.topicKey}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        capsule.summary.take(700),
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(
                        onClick = {
                            unpackedGenome = formatGenomeUnpacked(
                                onUnpackGenome(capsule.id)
                            )
                        }
                    ) {
                        Text("Unpack")
                    }
                }
            }
        }

        unpackedGenome?.let { details ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("Genome evidence", fontWeight = FontWeight.SemiBold)
                    Text(details, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { unpackedGenome = null }) {
                        Text("Hide")
                    }
                }
            }
        }

        TextButton(
            enabled = !busy && experienceTotal > 0,
            onClick = {
                unpackedGenome = null
                onClearExperience()
            }
        ) {
            Text("Clear verified experience")
        }

        Spacer(Modifier.height(18.dp))
    }
}

private fun formatGenomeUnpacked(unit: GenomeUnpackedUnit?): String {
    if (unit == null) return "No evidence found for this genome unit."

    return buildString {
        appendLine("${unit.layer} · ${unit.id}")
        if (unit.summary.isNotBlank()) {
            appendLine(unit.summary.take(1_200))
        }

        if (unit.anchors.isNotEmpty()) {
            appendLine()
            appendLine("Anchors")
            unit.anchors.take(8).forEach { anchor ->
                append("- ")
                append(anchor.valence)
                if (anchor.resolvedAt != null) append(" resolved")
                append(" · ")
                append(anchor.tool)
                if (anchor.target.isNotBlank()) {
                    append(" · ")
                    append(anchor.target)
                }
                append(" · ")
                append(anchor.summary.take(500))
                append(" · seen=")
                append(anchor.occurrences)
                appendLine()
            }
        }

        if (unit.events.isNotEmpty()) {
            appendLine()
            appendLine("Verified events")
            unit.events.take(12).forEach { event ->
                append("- ")
                append(if (event.ok) "OK" else "FAIL")
                append(" · ")
                append(event.tool)
                if (event.target.isNotBlank()) {
                    append(" · ")
                    append(event.target)
                }
                append(" · ")
                append(event.evidenceExcerpt.take(600))
                appendLine()
            }
        }

        if (unit.links.isNotEmpty()) {
            appendLine()
            appendLine("Causal links")
            unit.links.take(12).forEach { link ->
                append("- ")
                append(link.relation)
                append(" · ")
                append(link.fromId.take(12))
                append(" → ")
                append(link.toId.take(12))
                appendLine()
            }
        }
    }.trim()
}

private fun formatElapsed(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1_000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun resolveGgufDisplayName(context: android.content.Context, modelRef: String): String {
    if (modelRef.isBlank()) return ""
    if (!modelRef.startsWith("content://")) return modelRef.substringAfterLast('/').ifBlank { modelRef }
    val uri = runCatching { Uri.parse(modelRef) }.getOrNull() ?: return "Selected GGUF"
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull().orEmpty().ifBlank { "Selected GGUF" }
}
