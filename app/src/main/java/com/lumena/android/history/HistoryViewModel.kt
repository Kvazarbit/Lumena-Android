package com.lumena.android.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumena.android.agent.core.AgentControlState
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.local.TermuxBridgeClient
import com.lumena.android.ollama.*
import com.lumena.android.settings.LumenaConnectionSettings
import com.lumena.android.settings.LumenaPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** All screens observe this one owner. Leaving a tab never creates a second agent controller. */
data class HistoryUiState(
    val catalog: HistoryCatalog = HistoryCatalog(),
    val selected: Conversation? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val models: List<String> = emptyList(),
    val modelStatus: String = "Ollama ще не перевірено",
    val settings: LumenaConnectionSettings = LumenaConnectionSettings(),
    val runningSessionId: String? = null,
    val runningTaskId: String? = null,
    val scrollMessageId: String? = null
)

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = HistoryRepository(application)
    private val _ui = MutableStateFlow(HistoryUiState())
    val ui: StateFlow<HistoryUiState> = _ui.asStateFlow()
    private var activeJob: Job? = null
    private var metadataJob: Job? = null
    private var selectionSequence = 0L

    init {
        viewModelScope.launch {
            try {
                repository.initialize()
                val catalog = repository.catalog()
                val id = catalog.selectedId ?: catalog.conversations.firstOrNull()?.id
                val selected = id?.let { repository.select(it) }
                _ui.value = _ui.value.copy(catalog = catalog, selected = selected, loading = false,
                    settings = LumenaPreferences.load(getApplication()))
                refreshModels()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = "Історію не вдалося відкрити. Дані не стерто: ${e.message}")
            }
        }
    }

    fun visible() {
        _ui.value = _ui.value.copy(settings = LumenaPreferences.load(getApplication()))
        if (!_ui.value.loading) refreshModels()
    }
    fun clearError() { _ui.value = _ui.value.copy(error = null) }
    private fun error(e: Exception) { _ui.value = _ui.value.copy(error = e.message ?: e.javaClass.simpleName) }
    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { error(e) }
        }
    }
    private suspend fun changed(c: Conversation?) {
        if (c == null) return
        val catalog = repository.catalog()
        val same = _ui.value.selected?.meta?.id == c.meta.id
        _ui.value = _ui.value.copy(catalog = catalog, selected = if (same) c else _ui.value.selected)
    }
    private suspend fun activate(c: Conversation) {
        _ui.value = _ui.value.copy(catalog = repository.catalog(), selected = c, scrollMessageId = null)
    }

    fun select(id: String, messageId: String? = null) {
        val sequence = ++selectionSequence
        action {
            val c = repository.select(id)
            if (sequence == selectionSequence) {
                activate(c)
                _ui.value = _ui.value.copy(scrollMessageId = messageId)
            }
        }
    }
    fun consumedScroll() { _ui.value = _ui.value.copy(scrollMessageId = null) }

    fun createTopic(title: String) = action {
        if (title.isBlank()) return@action
        val topic = repository.createTopic(title)
        val c = repository.newConversation(topic.id, _ui.value.selected?.meta?.model.orEmpty())
        ++selectionSequence
        activate(c)
    }
    fun newConversation(topicId: String? = null) = action {
        val topic = topicId ?: _ui.value.selected?.meta?.topicId ?: _ui.value.catalog.topics.firstOrNull()?.id ?: return@action
        val c = repository.newConversation(topic, _ui.value.selected?.meta?.model ?: _ui.value.settings.selectedModel)
        ++selectionSequence
        activate(c)
    }
    fun fork(sessionId: String, taskId: String) = action {
        val c = repository.fork(sessionId, taskId)
        ++selectionSequence
        activate(c)
    }
    fun rename(id: String, title: String) = action {
        repository.rename(id, title); changed(repository.load(id))
    }
    fun renameTopic(id: String, title: String) = action {
        repository.renameTopic(id, title); _ui.value = _ui.value.copy(catalog = repository.catalog())
    }
    fun move(id: String, topicId: String) = action {
        repository.move(id, topicId); changed(repository.load(id))
    }

    fun draft(text: String) {
        val c = _ui.value.selected ?: return
        if (text.length > 8000) return
        _ui.value = _ui.value.copy(selected = c.copy(payload = c.payload.copy(draft = text)))
        // Capture the ID now, never consult selectedId from a delayed writer.
        action { repository.updatePayload(c.meta.id) { it.copy(draft = text) } }
    }
    fun model(name: String, mode: ToolMode) {
        val c = _ui.value.selected ?: return
        if (_ui.value.runningSessionId == c.meta.id || c.payload.pending != null) return
        _ui.value = _ui.value.copy(selected = c.copy(meta = c.meta.copy(model = name, toolMode = mode)))
        LumenaPreferences.saveSelectedModel(getApplication(), name)
        action { repository.setModel(c.meta.id, name, mode); changed(repository.load(c.meta.id)) }
    }
    fun bridgeUrl(value: String) {
        LumenaPreferences.saveBridgeUrl(getApplication(), value)
        _ui.value = _ui.value.copy(settings = _ui.value.settings.copy(bridgeUrl = value))
    }
    fun bridgeToken(value: String) {
        LumenaPreferences.saveBridgeToken(getApplication(), value)
        _ui.value = _ui.value.copy(settings = _ui.value.settings.copy(bridgeToken = value))
    }
    fun ollamaUrl(value: String) {
        LumenaPreferences.saveOllamaUrl(getApplication(), value)
        _ui.value = _ui.value.copy(settings = _ui.value.settings.copy(ollamaUrl = value))
    }
    fun refreshModels() {
        if (metadataJob?.isActive == true) return
        val url = _ui.value.settings.ollamaUrl
        _ui.value = _ui.value.copy(modelStatus = "Перевіряю Ollama…")
        metadataJob = viewModelScope.launch {
            try {
                val result = OllamaClient(url).listModels()
                if (url != _ui.value.settings.ollamaUrl) return@launch
                result.onSuccess { models ->
                    _ui.value = _ui.value.copy(models = models, modelStatus = "Ollama online · ${models.size} моделей")
                    // Do not silently change the model of an existing conversation or select a cloud model.
                }.onFailure { _ui.value = _ui.value.copy(modelStatus = "Ollama недоступна: ${it.message}", models = emptyList()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { _ui.value = _ui.value.copy(modelStatus = "Ollama: ${e.message}") }
        }
    }

    fun send() {
        val c = _ui.value.selected ?: return
        val text = c.payload.draft.trim()
        if (text.isEmpty() || _ui.value.runningTaskId != null || c.payload.pending != null) return
        if (c.meta.model.isBlank()) { _ui.value = _ui.value.copy(error = "Виберіть модель у налаштуваннях цієї розмови."); return }
        val task = TaskState(newHistoryId(), null, text, TaskStatus.WAITING_MODEL)
        val entry = ChatEntry("user", text)
        val settings = LumenaPreferences.load(getApplication())
        start(c.meta.id, task.id) {
            val saved = repository.updatePayload(c.meta.id) { p -> p.copy(
                chat = p.chat + entry, history = p.history + OllamaMessage("user", text),
                control = AgentControlState(task), pending = null, draft = "", recoveryNotice = null,
                executionUncertain = false,
                tasks = p.tasks + TaskMark(task.id, text, task.status, entry.id)
            ) } ?: return@start
            changed(saved)
            val runner = runner(c.meta, settings)
            val outcome = runner.run(saved.payload.history, task,
                onProgress = { progress(c.meta.id, task.id, it) },
                onCheckpoint = { history, control -> checkpoint(c.meta.id, task.id, history, control) })
            finish(c.meta.id, task.id, outcome)
        }
    }

    fun approve(sessionId: String, taskId: String) {
        if (_ui.value.runningTaskId != null) return
        val settings = LumenaPreferences.load(getApplication())
        start(sessionId, taskId) {
            val c = repository.load(sessionId)
            val pending = c.payload.pending ?: return@start
            require(pending.control.task.id == taskId) { "Підтвердження належить іншій задачі" }
            // Consume approval in the database before dispatch. A second tap cannot reuse it.
            val consumed = repository.updatePayload(sessionId, taskId) {
                it.copy(pending = null, control = pending.control.copy(task = pending.control.task.copy(status = TaskStatus.EXECUTING)), executionUncertain = true)
            } ?: return@start
            changed(consumed)
            val outcome = runner(c.meta, settings).approve(pending,
                onProgress = { progress(sessionId, taskId, it) },
                onCheckpoint = { history, control -> checkpoint(sessionId, taskId, history, control) })
            finish(sessionId, taskId, outcome)
        }
    }

    fun reject(sessionId: String, taskId: String) = action {
        if (_ui.value.runningSessionId == sessionId) return@action
        changed(repository.updatePayload(sessionId, taskId) { p ->
            val pending = p.pending ?: return@updatePayload p
            val replies = pending.history.lastOrNull()?.tool_calls.orEmpty().map {
                NativeTools.result(it.function.name, false, error = "Rejected by the user. Not executed.")
            }
            p.copy(pending = null, control = pending.control.copy(task = pending.control.task.copy(status = TaskStatus.CANCELLED)),
                chat = p.chat + ChatEntry("status", "Дію відхилено. Інструмент не запускався."),
                history = pending.history + replies,
                tasks = p.tasks.map { if (it.id == taskId) it.copy(status = TaskStatus.CANCELLED) else it })
        })
    }

    fun stop() { activeJob?.cancel() }

    private fun runner(meta: ConversationMeta, settings: LumenaConnectionSettings) = WorkflowRunner(
        OllamaClient(settings.ollamaUrl),
        settings.bridgeToken.takeIf { it.isNotBlank() }?.let { TermuxBridgeClient(settings.bridgeUrl, it) },
        meta.model, mode = meta.toolMode
    )

    private fun start(sessionId: String, taskId: String, work: suspend () -> Unit) {
        if (_ui.value.runningTaskId != null) return
        _ui.value = _ui.value.copy(runningSessionId = sessionId, runningTaskId = taskId)
        activeJob = viewModelScope.launch {
            try { work() }
            catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    try {
                        changed(repository.updatePayload(sessionId, taskId) { p ->
                            val notice = if (p.executionUncertain)
                                "Агент зупинено. Інструмент у Termux міг продовжити роботу; перевірте результат перед повтором."
                            else "Агент зупинено. Історію збережено."
                            p.copy(pending = null, control = p.control?.let { it.copy(task = it.task.copy(status = TaskStatus.CANCELLED)) },
                                chat = p.chat + ChatEntry("status", notice), recoveryNotice = notice,
                                tasks = p.tasks.map { if (it.id == taskId) it.copy(status = TaskStatus.CANCELLED) else it })
                        })
                    } catch (e: Exception) { error(e) }
                }
                throw cancelled
            } catch (e: Exception) {
                try {
                    changed(repository.updatePayload(sessionId, taskId) { p -> p.copy(
                        control = p.control?.let { it.copy(task = it.task.copy(status = TaskStatus.FAILED)) },
                        chat = p.chat + ChatEntry("error", e.message ?: "Помилка задачі"),
                        tasks = p.tasks.map { if (it.id == taskId) it.copy(status = TaskStatus.FAILED) else it }) })
                } catch (_: Exception) { /* Preserve the previous durable checkpoint. */ }
                error(e)
            } finally {
                if (_ui.value.runningTaskId == taskId) _ui.value = _ui.value.copy(runningSessionId = null, runningTaskId = null)
                activeJob = null
            }
        }
    }

    private suspend fun progress(id: String, taskId: String, text: String) {
        changed(repository.updatePayload(id, taskId) { p ->
            if (p.chat.lastOrNull()?.text == text && p.chat.lastOrNull()?.role == "status") p
            else p.copy(chat = p.chat + ChatEntry("status", text))
        })
    }
    private suspend fun checkpoint(id: String, taskId: String, history: List<OllamaMessage>, control: AgentControlState) {
        changed(repository.updatePayload(id, taskId) { p -> p.copy(
            history = history.filterNot { it.role == "system" }, control = control,
            executionUncertain = control.task.status == TaskStatus.EXECUTING,
            tasks = p.tasks.map { if (it.id == taskId) it.copy(status = control.task.status) else it }
        ) })
    }
    private suspend fun finish(id: String, taskId: String, outcome: WorkflowOutcome) {
        changed(repository.updatePayload(id, taskId) { p -> when (outcome) {
            is WorkflowOutcome.Finished -> {
                val entry = ChatEntry("assistant", outcome.text)
                val chat = p.chat + entry
                p.copy(chat = chat, history = outcome.history, control = outcome.control, pending = null, executionUncertain = false,
                    tasks = p.tasks.map { if (it.id == taskId) it.copy(status = outcome.control.task.status,
                        anchorMessageId = entry.id, chatEnd = chat.size, historyEnd = outcome.history.size) else it })
            }
            is WorkflowOutcome.NeedsConfirmation -> p.copy(
                pending = outcome.pending, control = outcome.pending.control, history = outcome.pending.history, executionUncertain = false,
                chat = p.chat + ChatEntry("status", "Потрібне підтвердження: ${outcome.pending.plan.request.tool}"),
                tasks = p.tasks.map { if (it.id == taskId) it.copy(status = TaskStatus.WAITING_CONFIRMATION) else it })
            is WorkflowOutcome.Failed -> p.copy(
                pending = null, control = outcome.control, history = outcome.history, executionUncertain = outcome.executionUncertain,
                chat = p.chat + ChatEntry("error", outcome.message),
                tasks = p.tasks.map { if (it.id == taskId) it.copy(status = TaskStatus.FAILED) else it })
        } })
    }
}
