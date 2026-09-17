package com.lumena.android.agent.runtime

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumena.android.agent.core.*
import com.lumena.android.agent.local.*
import com.lumena.android.ollama.*
import com.lumena.android.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class LocalAgentUi(
    val session: LocalSessionSnapshot = LocalSessionSnapshot(),
    val connection: LumenaConnectionSettings = LumenaConnectionSettings(),
    val models: List<String> = emptyList(), val modelStatus: String = "Ollama ще не перевірено",
    val busy: Boolean = false, val stopping: Boolean = false, val stopAfterStep: Boolean = false,
    val stage: String = "Готово до запиту", val startedAt: Long = 0, val lastSignalAt: Long = 0,
    val pulse: ModelPulse = ModelPulse(), val toolOutput: String = ""
)
/** Activity-owned: tab removal/rotation does not create another writer or another job. */
class LocalAgentViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(LocalAgentUi(connection = LumenaPreferences.load(app)))
    val ui = _ui.asStateFlow()
    private var job: Job? = null
    private var discover: Job? = null
    private var control = RunControl()
    private var activeBridge: TermuxBridgeClient? = null
    @Volatile private var epoch = 0L

    init {
        var saved = LocalSessionStore.load(app)
        val running = saved.task?.status in setOf(TaskStatus.WAITING_MODEL, TaskStatus.EXECUTING, TaskStatus.VERIFYING, TaskStatus.PLANNING)
        if (running && saved.pending == null) {
            val task = saved.task?.copy(status = TaskStatus.CANCELLED)
            saved = saved.copy(task = task, control = saved.control?.let { c -> task?.let { c.copy(task = it) } },
                chat = saved.chat + PersistedChatMessage("status", "Попередній процес перервано. Нічого не перезапускається автоматично. Перевірте останній результат перед повтором."))
        }
        _ui.update { it.copy(session = saved, stage = if (saved.inFlight != null) "Результат попереднього інструмента невідомий" else "Сесію відновлено") }
        persist()
    }
    private fun persist() = LocalSessionStore.save(getApplication(), _ui.value.session)
    private fun updateSession(change: (LocalSessionSnapshot) -> LocalSessionSnapshot) {
        _ui.update { it.copy(session = change(it.session)) }; persist()
    }
    private fun note(text: String, role: String = "status") = updateSession {
        it.copy(chat = (it.chat + PersistedChatMessage(role, text)).takeLast(100))
    }
    fun setDraft(text: String) = updateSession { it.copy(inputDraft = text.take(8000)) }
    fun onVisible() {
        if (!_ui.value.busy && !_ui.value.stopping && _ui.value.session.pending == null)
            _ui.update { it.copy(connection = LumenaPreferences.load(getApplication())) }
        refreshModels()
    }
    fun setSetting(name: String, value: String) {
        if (_ui.value.busy || _ui.value.stopping || _ui.value.session.pending != null) return
        val app = getApplication<Application>()
        when (name) {
            "model" -> LumenaPreferences.saveSelectedModel(app, value)
            "ollama" -> LumenaPreferences.saveOllamaUrl(app, value)
            "bridge" -> LumenaPreferences.saveBridgeUrl(app, value)
            "token" -> LumenaPreferences.saveBridgeToken(app, value)
        }
        _ui.update { it.copy(connection = LumenaPreferences.load(app)) }
    }
    fun refreshModels() {
        if (discover?.isActive == true) return
        val url = _ui.value.connection.ollamaUrl
        discover = viewModelScope.launch {
            _ui.update { it.copy(modelStatus = "Перевірка Ollama…") }
            try {
                val names = OllamaClient(url).listModels().getOrThrow()
                if (url != _ui.value.connection.ollamaUrl) return@launch
                _ui.update { it.copy(models = names, modelStatus = "Ollama доступна · моделей: ${names.size}") }
                if (_ui.value.connection.selectedModel.isBlank() && names.isNotEmpty())
                    names.firstOrNull { !it.contains("cloud", true) }?.let { setSetting("model", it) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _ui.update { it.copy(modelStatus = "Ollama: ${e.message}") } }
        }
    }
    private fun history(s: LocalSessionSnapshot) = s.history.map { OllamaMessage(it.role, it.content) }
    private fun savedHistory(h: List<OllamaMessage>) = h.filterNot { it.role == "system" }.takeLast(48).map { PersistedHistoryMessage(it.role, it.content) }
    fun send() {
        val u = _ui.value
        if (u.busy || u.stopping || u.session.pending != null || u.session.inFlight != null) return
        val text = u.session.inputDraft.trim()
        if (text.isEmpty()) return
        if (u.connection.selectedModel.isBlank()) { note("Спочатку виберіть модель", "error"); return }
        val task = TaskState(UUID.randomUUID().toString(), null, text, TaskStatus.WAITING_MODEL)
        updateSession { it.copy(task = task, control = AgentControlState(task), inputDraft = "", pending = null,
            history = it.history + PersistedHistoryMessage("user", text),
            chat = it.chat + PersistedChatMessage("user", text)) }
        start(null)
    }
    fun approve() {
        val s = _ui.value.session
        if (_ui.value.busy || _ui.value.stopping) return
        val p = s.pending ?: return
        val c = p.control ?: s.control ?: s.task?.let { AgentControlState(it) } ?: return
        val plan = ToolGate.plan(PlannerDecision(ToolRequest(p.tool, p.args), p.reason))
        if (!plan.allowed) { reject(); note(plan.reason, "error"); return }
        start(PendingWorkflowTool(plan, p.history.map { OllamaMessage(it.role, it.content) }, c))
    }
    private fun start(pending: PendingWorkflowTool?) {
        val generation = ++epoch
        val s = _ui.value.session
        val task = s.task ?: return
        val conn = _ui.value.connection // freeze provider/credentials for this run
        control = RunControl()
        val runControl = control
        val now = SystemClock.elapsedRealtime()
        _ui.update { it.copy(busy = true, stopAfterStep = false, startedAt = now, lastSignalAt = now, stage = "Підготовка", pulse = ModelPulse(), toolOutput = "") }
        updateSession { it.copy(pending = null) }
        job = viewModelScope.launch {
            try {
                activeBridge = conn.bridgeToken.takeIf { it.isNotBlank() }?.let { TermuxBridgeClient(conn.bridgeUrl, it) }
                val runner = WorkflowRunner(OllamaClient(conn.ollamaUrl), activeBridge, conn.selectedModel,
                    runtime = runControl,
                    onPulse = { p -> if (generation == epoch) _ui.update { u ->
                        u.copy(pulse = p, lastSignalAt = SystemClock.elapsedRealtime(),
                            stage = if (u.stopAfterStep) u.stage else if (p.chunks == 0) "Очікування першого фрагмента моделі" else "Модель генерує відповідь") } },
                    onToolOutput = { result -> if (generation == epoch) _ui.update { u ->
                        u.copy(lastSignalAt = if (result.status == "unavailable") u.lastSignalAt else SystemClock.elapsedRealtime(),
                            toolOutput = if (result.status == "unavailable") "Потоковий стан bridge недоступний. Для зупинки процесу оновіть bridge."
                            else listOf(result.stdout, result.stderr).filter { it.isNotBlank() }.joinToString("\n").takeLast(6000)) } },
                    onToolStarted = { active -> if (generation == epoch) updateSession { it.copy(inFlight = active) } },
                    checkpoint = { h, c -> if (generation == epoch) updateSession { it.copy(history = savedHistory(h), task = c.task, control = c) } }
                )
                val progress: (String) -> Unit = { message ->
                    if (generation == epoch) {
                        _ui.update { it.copy(stage = if (it.stopAfterStep) it.stage else message.lineSequence().first(), lastSignalAt = SystemClock.elapsedRealtime()) }
                        note(message)
                    }
                }
                val outcome = if (pending == null) runner.run(history(s), task, s.control, progress)
                    else runner.approve(pending, progress)
                if (generation != epoch) return@launch
                when (outcome) {
                    is WorkflowOutcome.Finished -> { storeOutcome(outcome.history, outcome.control); note(outcome.text, "assistant"); setStage("Завершено") }
                    is WorkflowOutcome.Failed -> { storeOutcome(outcome.history, outcome.control); note(outcome.message, "error"); setStage("Помилка · див. журнал") }
                    is WorkflowOutcome.Stopped -> { storeOutcome(outcome.history, outcome.control); note(outcome.message); setStage("Зупинено користувачем") }
                    is WorkflowOutcome.NeedsConfirmation -> {
                        val p = outcome.pending
                        updateSession { it.copy(task = p.control.task, control = p.control, history = savedHistory(p.history),
                            pending = PersistedPendingTool(p.plan.request.tool, p.plan.request.args, p.plan.reason, p.control, savedHistory(p.history))) }
                        note("Очікує дозволу: ${p.plan.request.tool}"); setStage("Очікує вашого підтвердження")
                    }
                }
            } catch (e: CancellationException) {
                if (generation == epoch) markCancelled("Роботу перервано. Автоматичного повтору немає.")
                throw e
            } catch (e: Exception) {
                if (generation == epoch) { note(e.message ?: "Помилка", "error"); markCancelled("Роботу припинено після помилки") }
            } finally {
                if (generation == epoch) { _ui.update { it.copy(busy = false) }; persist() }
            }
        }
    }
    private fun setStage(text: String) { _ui.update { it.copy(stage = text) } }
    private fun storeOutcome(h: List<OllamaMessage>, c: AgentControlState) = updateSession {
        it.copy(task = c.task, control = c, history = savedHistory(h), pending = null)
    }
    private fun markCancelled(message: String) {
        updateSession { s -> s.copy(task = s.task?.copy(status = TaskStatus.CANCELLED),
            control = s.control?.let { it.copy(task = it.task.copy(status = TaskStatus.CANCELLED)) }, pending = null) }
        note(message)
    }
    fun stopAfterStep() {
        if (_ui.value.session.pending != null) { reject(); return }
        control.requestStopAfterStep()
        _ui.update { it.copy(stopAfterStep = true, stage = "Зупиню після поточного кроку; нових дій не буде") }
    }
    fun stopNow() {
        if (_ui.value.stopping) return
        control.requestStopAfterStep()
        val active = control.activeTool ?: _ui.value.session.inFlight
        ++epoch // invalidate callbacks BEFORE cancelling I/O
        job?.cancel(); job = null
        _ui.update { it.copy(busy = false, stopping = active != null, stopAfterStep = false, stage = "Зупиняю…") }
        markCancelled("Стоп: нові дії заблоковано. Уже внесені зміни не відкочуються.")
        if (active == null) { setStage("Агент зупинено; запит моделі скасовано"); return }
        reconcile(active, cancel = true)
    }
    fun checkStoppedTool() {
        val active = _ui.value.session.inFlight ?: return
        if (_ui.value.stopping || _ui.value.busy) return
        _ui.update { it.copy(stopping = true) }
        reconcile(active, cancel = false)
    }
    private fun reconcile(active: ActiveTool, cancel: Boolean) {
        val generation = epoch
        viewModelScope.launch {
            try {
                val c = _ui.value.connection
                val bridge = activeBridge ?: TermuxBridgeClient(c.bridgeUrl, c.bridgeToken)
                var status: BridgeJobStatus? = null
                withTimeoutOrNull(9000) {
                    status = bridge.jobStatus(active.id, cancel)
                    while (status?.status !in setOf("completed", "failed", "cancelled")) {
                        delay(300); status = bridge.jobStatus(active.id)
                    }
                }
                if (generation != epoch) return@launch
                val result = status?.result
                if (status?.status in setOf("completed", "failed", "cancelled")) {
                    result?.let { r ->
                        note("Фактичний результат ${active.request.tool}: ${status?.status}\nexit=${r.exitCode}\n${r.stdout.takeLast(6000)}\n${r.stderr.takeLast(3000)}\n${r.error.orEmpty()}")
                        updateSession { it.copy(history = it.history + savedHistory(listOf(LocalWorkflowAgent.toolResultMessage(active.request.tool, r.ok, r.stdout, r.stderr, r.error)))) }
                    }
                    control.activeTool = null
                    updateSession { it.copy(inFlight = null) }
                    setStage(if (status?.status == "completed") "Агент зупинено; поточна дія встигла завершитись" else "Bridge підтвердив завершення/зупинку поточної дії")
                } else { setStage("Агент зупинено; стан інструмента ще не підтверджений") }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (generation == epoch) { note("Не підтверджено зупинку Termux: ${e.message}. Перевірте процес вручну; не запускайте дію повторно.", "error"); setStage("Стан інструмента невідомий") }
            } finally { if (generation == epoch) _ui.update { it.copy(stopping = false) } }
        }
    }
    fun reject() {
        if (_ui.value.busy) return
        markCancelled("Дозвіл відхилено; інструмент не запускався.")
        setStage("Зупинено користувачем")
    }
    fun newChat() {
        if (_ui.value.busy || _ui.value.stopping || _ui.value.session.inFlight != null) return
        ++epoch; job?.cancel()
        _ui.update { it.copy(session = LocalSessionSnapshot(), stage = "Нова розмова", pulse = ModelPulse(), startedAt = 0, toolOutput = "") }
        persist()
    }
    fun acknowledgeManualCheck() {
        if (_ui.value.busy || _ui.value.stopping) return
        control.activeTool = null
        updateSession { it.copy(inFlight = null) }
        note("Користувач підтвердив ручну перевірку процесу. Автоматичне підтвердження виконання відсутнє.")
        setStage("Готово до нового запиту")
    }
}
