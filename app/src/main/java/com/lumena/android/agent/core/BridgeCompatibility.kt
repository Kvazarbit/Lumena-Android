package com.lumena.android.agent.core

/**
 * Which Termux bridge is actually installed on the phone.
 *
 * The APK and the bridge are updated separately: a new app can meet an old
 * bridge that lacks a module's tools. The bridge already reports its version
 * in `health`; a module whose minimum bridge is newer than the OBSERVED one is
 * unavailable, so Lumena never routes to tools the phone does not have.
 *
 * An unknown version never blocks: only an observed older bridge does.
 */
object BridgeCompatibility {
    private val HEALTH_VERSION = Regex("""(?m)^version=(\d+(?:\.\d+)*)\s*$""")

    @Volatile
    private var observed: String? = null

    fun observedVersion(): String? = observed

    /** Records a version seen in a bridge result; anything unparseable is ignored. */
    fun observe(version: String?) {
        if (version != null && parse(version) != null) observed = version.trim()
    }

    /** Reads `version=0.29` from the bridge `health` output. */
    fun versionFromHealth(stdout: String): String? =
        HEALTH_VERSION.find(stdout)?.groupValues?.get(1)

    fun parse(version: String): List<Int>? {
        val parts = version.trim().split('.')
        if (parts.isEmpty()) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    /** Numeric segment comparison: 0.30 > 0.29 > 0.28; null if either is unparseable. */
    fun compare(a: String, b: String): Int? {
        val left = parse(a) ?: return null
        val right = parse(b) ?: return null
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 } - right.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    fun satisfies(required: String?): Boolean {
        if (required == null) return true
        val seen = observed ?: return true
        return (compare(seen, required) ?: return true) >= 0
    }

    /** Runs [block] as if [version] had been observed, then restores the previous value. */
    fun <T> withObserved(version: String?, block: () -> T): T {
        val before = observed
        observed = version
        try {
            return block()
        } finally {
            observed = before
        }
    }
}
