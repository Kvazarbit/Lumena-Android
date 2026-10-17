package com.lumena.android.llama

import com.lumena.android.settings.SemanticMemoryPolicy

/** Fixed diagnostic codes only. Native exception messages may contain private paths. */
enum class LiteRtImportCode(val advice: String) {
    BAD_URI("Вибери .litertlm через Android picker."),
    PICKER_FAILURE("Не вдалося прочитати опис вибраного документа."),
    NO_FILENAME("Android не повернув назву вибраного файла."),
    BAD_EXTENSION("Потрібен .litertlm, а не GGUF чи ZIP."),
    STORAGE_FAILURE("Недоступне приватне сховище або бракує місця."),
    SOURCE_READ_FAILURE("Не вдалося прочитати вибраний документ."),
    TOO_SMALL("Пакет надто малий для LiteRT-LM."),
    TOO_LARGE("Файл перевищує межу імпорту 1,2 GB."),
    METADATA_UNAVAILABLE("Нативний LiteRT-LM 0.18.0 не прочитав метадані пакета."),
    NOT_EMBEDDING("Пакет не має типу Embedding."),
    NO_TEXT_ENCODER("Не виявлено текстового embedding-енкодера."),
    INVALID_DIMENSION("Не встановлено допустимої розмірності вектора."),
    CPU_UNSUPPORTED("Пакет явно не оголошує CPU для текстового енкодера."),
    CONFLICTING_DESTINATION("Існуюча копія з тим самим digest не пройшла перевірку."),
    COMMIT_FAILED("Не вдалося завершити локальний імпорт."),
    NOT_INSTALLED("Спочатку імпортуй файл .litertlm."),
    ENGINE_INIT_FAILED("Не вдалося ініціалізувати нативний LiteRT CPU рушій."),
    INFERENCE_FAILED("Нативний CPU рушій не зміг обчислити embedding."),
    VECTOR_DIM_MISMATCH("Розмір вектора не відповідає метаданим.")
}

class LiteRtImportRefusal(
    val code: LiteRtImportCode,
    cause: Throwable? = null
) : IllegalStateException(code.name, cause) {
    fun safeStatus(): String = "LITERT_" + code.name + ": " + code.advice
}

/** Validate the text encoder, never exclude a valid text model by media flags. */
object LiteRtTextImportPolicy {
    data class Capabilities(
        val isEmbedding: Boolean,
        val text: Boolean,
        val vision: Boolean,
        val audio: Boolean,
        val video: Boolean,
        val dimension: Int?,
        // null means hardware capability is NOT DECLARED, not CPU proven.
        val cpuDeclared: Boolean?
    )

    data class Accepted(val dimension: Int, val cpuDeclared: Boolean)

    fun validate(capabilities: Capabilities): Accepted {
        if (!capabilities.isEmbedding) throw LiteRtImportRefusal(LiteRtImportCode.NOT_EMBEDDING)
        if (!capabilities.text) throw LiteRtImportRefusal(LiteRtImportCode.NO_TEXT_ENCODER)
        val dim = capabilities.dimension
        if (dim == null || dim !in 2..SemanticMemoryPolicy.MAX_DIM) {
            throw LiteRtImportRefusal(LiteRtImportCode.INVALID_DIMENSION)
        }
        if (capabilities.cpuDeclared == false) {
            throw LiteRtImportRefusal(LiteRtImportCode.CPU_UNSUPPORTED)
        }
        return Accepted(dim, capabilities.cpuDeclared == true)
    }
}
