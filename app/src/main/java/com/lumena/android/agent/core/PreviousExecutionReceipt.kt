package com.lumena.android.agent.core

/**
 * Read-only, task-scoped audit of the previous task's LOCAL execution.
 * No model summary, lastResult text, or predicted outcome is an execution fact.
 *
 * This is not a capability token, approval, or permission.
 */
data class PreviousExecutionReceipt(
    val sourceTaskId: String,
    val goal: String,
    val previousStatus: TaskStatus,
    val toolResultsObserved: Int,
    val retainedResults: Int,
    val retainedSuccessfulResults: Int,
    val executedTools: List<String> = emptyList(),
    val unresolvedInFlight: Boolean = false
)

/**
 * Fail-closed answer to explicit questions about whether a prior action ran.
 * A conversational answer can be DONE without any tool call, but it must not
 * claim that an earlier search or command executed without the previous task's
 * own kernel receipts.
 */
object ExecutionClaimGuard {
    fun explain(receipt: PreviousExecutionReceipt): String {
        val count = receipt.toolResultsObserved.coerceAtLeast(0)
        if (count == 0 && !receipt.unresolvedInFlight) {
            return "У попередній задачі немає підтвердженого виконання інструмента: " +
                "локальний журнал містить 0 TOOL_RESULT. " +
                "Текст на кшталт web.search(query=...) — пропозиція виклику, а не результат пошуку; " +
                "expect_ok та confidence — прогнози моделі, не вимірювання. " +
                "Статус ${receipt.previousStatus} стосується завершення відповіді, " +
                "а не доводить, що інструмент запускався чи дані були отримані."
        }

        if (receipt.unresolvedInFlight) {
            return "Попередня задача містить дію з НЕВІДОМИМ результатом. " +
                "Не можна стверджувати, що інструмент завершився успішно, " +
                "або повторювати зміну стану без окремої перевірки. " +
                "Записаних результатів: $count."
        }

        val known = receipt.executedTools
            .distinct()
            .take(8)
            .joinToString(", ")
            .ifBlank { "детальні записи вже недоступні" }
        return "У попередній задачі локальний ContextKernel зафіксував " +
            "$count результат(ів) інструментів. " +
            "У доступній частині журналу: $known; " +
            "успішних записів: ${receipt.retainedSuccessfulResults} " +
            "із ${receipt.retainedResults} збережених. " +
            "Це доказ отримання результату інструмента, але не автоматичний доказ " +
            "досягнення всієї мети. Попередній статус: ${receipt.previousStatus}."
    }
}
