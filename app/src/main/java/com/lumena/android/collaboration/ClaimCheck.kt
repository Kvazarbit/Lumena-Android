package com.lumena.android.collaboration

import com.lumena.android.agent.core.WorkspaceProject

/**
 * Checks a model's prose against this app's own receipts. A deterministic lookup, not a judgement:
 * no model call, no side effects, no approval. A missing receipt is not proof of a lie, and a
 * supported claim is not proof that the owner's goal is met.
 */
object ClaimCheck {
    enum class Kind { PASS, FAIL, NOT_RUN, WROTE }
    enum class Verdict { SUPPORTED, STALE, CONTRADICTED, NO_RECEIPT, UNVERIFIABLE }
    data class Claim(val sentence: String, val kind: Kind, val target: String?, val count: Int?)
    data class Finding(val claim: Claim, val verdict: Verdict, val receiptId: String?, val note: String)

    const val MAX_INPUT = 20_000
    const val MAX_CLAIMS = 40
    private val checks = setOf("python.run", "python.tests")
    private val writes = setOf("file.write", "file.patch")
    private val sentences = Regex("(?<=[.!?;])\\s+|\\n+")
    private val pyTarget = Regex("[a-z0-9_./-]*[a-z0-9_]\\.py(?![a-z0-9_])")
    private val fileTarget = Regex("[a-z0-9_./-]*[a-z0-9_]\\.(?:py|kt|kts|md|json|txt|toml|ya?ml|cfg|ini|sh|js|ts|html|css|csv)(?![a-z0-9_])")
    private val testWord = Regex("\\btests?\\b|тест|\\bpytest\\b|unittest")
    private val runVerb = Regex("запуст|викона|выполн|\\bran\\b|\\brun\\b|\\bexecuted\\b")
    // Cyrillic stems are plain substrings: Java's \b is not reliable around Cyrillic letters.
    private val hypothetical = listOf("якщо", "мають пройти", "має пройти", "повинн", "запущу", "запустимо", "спробуй", "буде ",
        "если", "должн", "\\bif\\b", "\\bshould\\b", "\\bwill\\b", "\\bwould\\b", "\\btry\\b").map { Regex(it) }
    private val notRun = listOf("нічого не викон", "нічого не запуск", "не запускав", "не запускала", "не виконував", "не виконувала",
        "не було виконано", "не було запущено", "ничего не выполн", "ничего не запуск", "не запускал",
        "nothing was run", "nothing was executed", "did not run", "didn't run", "not executed", "never ran")
    private val notFail = Regex("\\b0\\s+(?:failures?|errors?|failed)|\\bno\\s+(?:failures?|errors?)|failures=0|errors=0|без\\s+помил|без\\s+ошиб")
    private val fixedBug = Regex("(?:виправ|исправ)\\S*\\s+(?:\\S+\\s+)?(?:помилк|ошибк|баг)\\S*|\\bfix(?:ed)?\\s+(?:the\\s+|an?\\s+)?(?:bug|error|issue)s?")
    private val fail = listOf("\\bfail", "неуспіш", "неуспеш", "\\bunsuccess", "не\\s+пройш", "не\\s+прох", "не\\s+прошл", "впав", "впал",
        "упал", "провал", "помилк", "ошибк", "не\\s+працю", "[1-9]\\d*\\s+errors?").map { Regex(it) }
    private val pass = listOf("\\bok\\b", "(?<![а-яіїєґa-z])ок(?![а-яіїєґa-z])", "\\bpass(?:ed|es|ing)?\\b", "пройш", "проход",
        "успішн", "успешн", "прошл", "зелен", "\\bgreen\\b", "\\bsucce").map { Regex(it) }
    private val wrote = listOf("виправ", "змінив", "змінила", "створив", "створила", "записав", "записала", "додав", "додала",
        "оновив", "оновила", "исправ", "создал", "изменил", "обновил",
        "\\b(?:fixed|updated|created|wrote|patched|modified|edited)\\b").map { Regex(it) }
    private val digitCounts = listOf(Regex("\\bran\\s+(\\d{1,5})\\s+tests?"), Regex("(\\d{1,5})\\s+(?:tests?|тест)"),
        Regex("(\\d{1,5})\\s+passed"))
    private val receiptCounts = listOf(Regex("\"tests_count\":\\s*(\\d{1,5})"), Regex("\\bran\\s+(\\d{1,5})\\s+tests?"),
        Regex("(\\d{1,5})\\s+passed"))
    private val words = mapOf("один" to 1, "одна" to 1, "одного" to 1, "one" to 1, "два" to 2, "дві" to 2, "двох" to 2, "two" to 2,
        "три" to 3, "трьох" to 3, "three" to 3, "чотири" to 4, "четыре" to 4, "four" to 4, "п'ять" to 5, "п’ять" to 5,
        "пять" to 5, "five" to 5, "шість" to 6, "шесть" to 6, "six" to 6, "сім" to 7, "семь" to 7, "seven" to 7,
        "вісім" to 8, "восемь" to 8, "eight" to 8, "дев'ять" to 9, "дев’ять" to 9, "девять" to 9, "nine" to 9,
        "десять" to 10, "ten" to 10)
    private val wordCount = Regex("(?<![а-яіїєґa-z'’])(" + words.keys.joinToString("|") + ")\\s+(?:tests?|тест)")

    fun extract(text: String): List<Claim> {
        val out = mutableListOf<Claim>()
        for (raw in text.take(MAX_INPUT).split(sentences)) {
            if (out.size >= MAX_CLAIMS) break
            val sentence = raw.trim().take(400)
            val s = sentence.lowercase()
            if (s.length < 3 || s.endsWith('?') || s.startsWith("чи ") || hypothetical.any { it.containsMatchIn(s) }) continue
            // Preserve case: Termux paths are case sensitive. The surrounding
            // prose may be folded, but file identity must not be folded.
            val py = Regex(pyTarget.pattern, RegexOption.IGNORE_CASE).findAll(sentence).map { it.value.removePrefix("./").trim('/') }.toList()
            val target = py.firstOrNull { "test" in it.substringAfterLast('/').lowercase() } ?: py.firstOrNull()?.takeIf { runVerb.containsMatchIn(s) }
            if (notRun.any { it in s }) { out += Claim(sentence, Kind.NOT_RUN, target, null); continue }
            val file = Regex(fileTarget.pattern, RegexOption.IGNORE_CASE).find(sentence)?.value?.removePrefix("./")?.trim('/')
            if (file != null && wrote.any { it.containsMatchIn(s) }) out += Claim(sentence, Kind.WROTE, file, null)
            val count = claimedCount(s)
            if (!testWord.containsMatchIn(s) && count == null && target == null) continue
            val cleaned = notFail.replace(fixedBug.replace(s, " "), " ")
            val kind = when {
                fail.any { it.containsMatchIn(cleaned) } -> Kind.FAIL
                pass.any { it.containsMatchIn(cleaned) } -> Kind.PASS
                else -> null
            } ?: continue
            out += Claim(sentence, kind, target, count)
        }
        return out.take(MAX_CLAIMS)
    }

    fun check(text: String, entries: List<CollaborationEntry>, project: WorkspaceProject, appSource: String,
              current: SourceObservation? = null): List<Finding> {
        val results = entries.filter { it.kind == "RESULT" && it.result != null }
        val scoped = results.filter { it.scope.projectId == project.id && it.scope.root == project.root }.sortedByDescending { it.at }
        val unassigned = results.filter { it.scope.projectId == "UNASSIGNED" }.sortedByDescending { it.at }
        return extract(text).map { verdict(it, scoped, unassigned, project, appSource, current) }
    }

    private fun verdict(c: Claim, scoped: List<CollaborationEntry>, unassigned: List<CollaborationEntry>,
                        project: WorkspaceProject, appSource: String, current: SourceObservation?): Finding {
        val tools = if (c.kind == Kind.WROTE) writes else checks
        fun relevant(e: CollaborationEntry) = e.tool in tools && (c.target == null || matches(e, c.target))
        val candidates = scoped.filter(::relevant)
        val latest = candidates.firstOrNull()
        if (latest?.result?.outcomeUnknown == true) return Finding(c, Verdict.UNVERIFIABLE, latest.id,
            "Останній результат невідомий. Старий успіх не підтверджує цю заяву; автоматично повторювати дію не можна.")
        if (c.kind == Kind.NOT_RUN) {
            return if (latest == null) Finding(c, Verdict.UNVERIFIABLE, null,
                "Квитанцій запуску в цьому проєкті немає, але це не доводить, що нічого не запускали.")
            else Finding(c, Verdict.CONTRADICTED, latest.id, "Журнал має запуск: ${describe(latest)}.")
        }
        if (latest == null) {
            val loose = unassigned.firstOrNull(::relevant)
            return Finding(c, Verdict.NO_RECEIPT, null, if (loose == null) "У журналі цього проєкту такої дії немає."
                else "Є квитанція без прив’язки до проєкту: ${describe(loose)}. Перевір вручну, чи це той самий файл.")
        }
        if (c.kind == Kind.WROTE) {
            return if (latest.result?.ok == true) Finding(c, Verdict.SUPPORTED, latest.id, describe(latest) + ".")
            else Finding(c, Verdict.CONTRADICTED, latest.id, "Запис не вдався: ${describe(latest)}.")
        }
        fun agrees(e: CollaborationEntry): Boolean {
            if (observed(e) != c.kind) return false
            val seen = receiptCount(e)
            return c.kind != Kind.PASS || c.count == null || seen == null || seen == c.count
        }
        if (!agrees(latest)) {
            val earlier = candidates.firstOrNull(::agrees)
            if (earlier != null) return Finding(c, Verdict.STALE, earlier.id,
                "Так було раніше: ${describe(earlier)}. Остання квитанція: ${describe(latest)}.")
            val seen = receiptCount(latest)
            val why = if (observed(latest) == c.kind && seen != null && c.count != null) "у квитанції $seen тестів, у заяві ${c.count}"
                else "остання квитанція: ${describe(latest)}"
            return Finding(c, Verdict.CONTRADICTED, latest.id, "Не збігається: $why.")
        }
        if (c.kind == Kind.PASS && c.count != null && receiptCount(latest) == null)
            return Finding(c, Verdict.UNVERIFIABLE, latest.id, "Є результат запуску, але заявлена кількість тестів не підтверджена.")
        val fresh = CollaborationProtocol.freshness(latest, project, appSource, current)
        val countNote = if (c.count != null && receiptCount(latest) == null) " Кількість тестів у квитанції не видно." else ""
        val verdict = if (fresh in setOf("STALE_SOURCE", "STALE_GOAL", "STALE_BUILD", "LEGACY_GOAL_REVISION")) Verdict.STALE else Verdict.SUPPORTED
        return Finding(c, verdict, latest.id, "${describe(latest)} · ${human(fresh)}.$countNote")
    }

    private fun human(fresh: String) = when (fresh) {
        "SAME_COVERED_SOURCE" -> "файли проєкту ті самі"
        "STALE_SOURCE" -> "файли змінилися після цього запуску"
        "STALE_GOAL" -> "мету проєкту змінено після цього запуску"
        "LEGACY_GOAL_REVISION" -> "запис зі старої версії мети"
        "STALE_BUILD" -> "запис з іншої збірки Lumena"
        else -> "чи змінилися файли після запуску, тут не перевірялось"
    }

    fun report(findings: List<Finding>): String = buildString {
        if (findings.isEmpty()) {
            append("Заяв про запуск, тести чи зміну файлів не знайдено. Перевірка шукає лише такі заяви.")
            return@buildString
        }
        val counts = findings.groupingBy { it.verdict }.eachCount()
        appendLine("Заяв: ${findings.size} · підтверджено ${counts[Verdict.SUPPORTED] ?: 0} · суперечить ${counts[Verdict.CONTRADICTED] ?: 0}" +
            " · немає квитанції ${counts[Verdict.NO_RECEIPT] ?: 0} · застаріло ${counts[Verdict.STALE] ?: 0}")
        findings.forEach { f ->
            val label = when (f.verdict) {
                Verdict.SUPPORTED -> "ПІДТВЕРДЖЕНО"
                Verdict.STALE -> "ЗАСТАРІЛО"
                Verdict.CONTRADICTED -> "СУПЕРЕЧИТЬ"
                Verdict.NO_RECEIPT -> "НЕМАЄ КВИТАНЦІЇ"
                Verdict.UNVERIFIABLE -> "НЕ ПЕРЕВІРЯЄТЬСЯ"
            }
            appendLine("$label · «${f.claim.sentence.replace(Regex("\\s+"), " ").take(160)}»")
            appendLine("    ${f.note}")
        }
        append("Це пошук у журналі цього застосунку, не думка моделі. Немає квитанції ≠ доведена брехня; підтверджено ≠ мету досягнуто.")
    }

    private fun matches(e: CollaborationEntry, target: String): Boolean {
        val root = e.scope.root.takeUnless { it == "." }?.plus("/").orEmpty()
        val t = target.removePrefix("./").trim('/').removePrefix(root)
        if (e.tool == "python.tests" && e.result?.verification?.let {
                it.valid() && it.complete && it.boundaryUnchanged && t in it.testFiles
            } == true) return true
        return e.targets.values.any { raw ->
            val n = raw.removePrefix("./").trim('/').removePrefix(root)
            n == t
        }
    }

    private fun observed(e: CollaborationEntry): Kind? {
        val r = e.result ?: return null
        if (r.outcomeUnknown) return null
        return if (r.ok && (r.exitCode == null || r.exitCode == 0)) Kind.PASS else Kind.FAIL
    }

    private fun receiptCount(e: CollaborationEntry): Int? {
        val r = e.result ?: return null
        val text = (r.stdout + "\n" + r.stderr).lowercase()
        return receiptCounts.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.toIntOrNull() }
    }

    private fun claimedCount(s: String): Int? =
        digitCounts.firstNotNullOfOrNull { it.find(s)?.groupValues?.get(1)?.toIntOrNull() }
            ?: wordCount.find(s)?.groupValues?.get(1)?.let(words::get)

    private fun describe(e: CollaborationEntry): String {
        val target = listOf("script", "path", "cwd", "file").firstNotNullOfOrNull { e.targets[it] } ?: "?"
        val status = if (observed(e) == Kind.PASS) "OK" else "FAIL"
        val count = receiptCount(e)?.let { ", тестів $it" }.orEmpty()
        return "#${e.intentId.take(8)} ${e.tool} ${target.take(90)} $status$count"
    }
}
