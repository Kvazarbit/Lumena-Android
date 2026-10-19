package com.lumena.android.collaboration

data class RepairLabConfig(
    val id: String, val scope: String, val projectId: String, val route: String,
    val modelDigest: String, val appSource: String, val device: String,
    val library: List<RepairCase>, val active: Boolean = true
)
data class RepairLabRow(val episodeId: String, val taskId: String, val exposed: Boolean,
    val cases: List<String>, val ended: Boolean = false, val rejectedDone: Int = 0, val acceptedDone: Int = 0,
    val invalidConditions: Boolean = false)
data class RepairLabState(val config: RepairLabConfig, val rows: List<RepairLabRow> = emptyList())

object RepairLabPolicy {
    fun arm(experiment: String, episode: String): Boolean =
        CollaborationProtocol.hash("repair-50/50|$experiment|$episode").take(8).toLong(16) % 2L == 0L

    fun assign(state: RepairLabState, episode: RepairEpisode): RepairLabState {
        if (!state.config.active || episode.scope != state.config.scope || episode.status != "OPEN") return state
        val eligible = state.config.library.filter { it.scope == episode.scope && it.check == episode.check && it.signature == episode.signature }
            .takeLast(2).map { it.id }
        if (eligible.isEmpty()) return state
        val existing = state.rows.firstOrNull { it.episodeId == episode.id }
        if (existing != null) return state.copy(rows = state.rows.map { if (it == existing && !it.invalidConditions) it.copy(taskId = episode.taskId, ended = false) else it })
        require(state.rows.size < 256) { "Experiment observation budget reached; export diagnostics before starting a new experiment" }
        return state.copy(rows = state.rows + RepairLabRow(episode.id, episode.taskId,
            arm(state.config.id, episode.id), eligible))
    }
    fun report(state: RepairLabState, snapshot: RepairSnapshot): String = buildString {
        appendLine("Лабораторія проєктного досвіду · ${state.config.id.take(8)} · модель ${state.config.modelDigest.take(12)}")
        appendLine("Призначення 50/50; бібліотека зафіксована: ${state.config.library.size}. Нові кейси не підмішуються.")
        val episodes = snapshot.episodes.associateBy { it.id }
        for (exposed in listOf(true, false)) {
            val rows = state.rows.filter { it.exposed == exposed }
            val solved = rows.filterNot { it.invalidConditions }.mapNotNull { episodes[it.episodeId] }.filter { it.status == "SOLVED" }
            val pending = rows.count { !it.ended && !it.invalidConditions && episodes[it.episodeId]?.status == "OPEN" }
            val unresolved = rows.size - solved.size - pending
            val costs = solved.map { it.steps }.sorted()
            val median = if (costs.isEmpty()) "?" else if (costs.size % 2 == 1) costs[costs.size / 2].toString()
                else ((costs[costs.size / 2 - 1] + costs[costs.size / 2]) / 2.0).toString()
            appendLine("${if (exposed) "З кейсами" else "Без кейсів"}: призначено ${rows.size}; SOLVED ${solved.size}; UNRESOLVED $unresolved; відкрито $pending; змінені умови ${rows.count { it.invalidConditions }}; медіана кроків $median; відхилених DONE ${rows.sumOf { it.rejectedDone }}; прийнятих DONE ${rows.sumOf { it.acceptedDone }}.")
        }
        val enough = listOf(true, false).all { arm -> state.rows.count {
            it.exposed == arm && (it.ended || it.invalidConditions || episodes[it.episodeId]?.status in setOf("SOLVED", "UNRESOLVED"))
        } >= 40 }
        appendLine(if (!enough) "ЗАМАЛО ДАНИХ: потрібно щонайменше 40 призначень у кожному плечі." else
            "ОПИСОВИЙ ЗВІТ: повтори схожих задач залежні. Ці числа самі не доводять користь.")
        append("Рішення/дозволи/квитанції не вимикаються. Відхилений DONE — спроба завершення без достатнього доказу, не доведена брехня. Автоматичної промоції правил немає.")
    }
}
