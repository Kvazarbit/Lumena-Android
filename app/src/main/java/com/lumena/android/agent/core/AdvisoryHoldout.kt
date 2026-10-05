package com.lumena.android.agent.core

import java.security.MessageDigest

enum class AdvisoryArm {
    EXPOSED,
    WITHHELD
}

/**
 * Task-level ON/OFF assignment for advisory memory layers.
 *
 * Counting how often advice appeared next to success cannot show that the
 * advice helped. A fixed fraction of tasks therefore runs without the layer,
 * chosen by a stable hash of the task id (known propensity, no model input),
 * so outcomes of EXPOSED and WITHHELD tasks can be compared later.
 *
 * Only advisory layers may be withheld. Hard invariants, ToolGate, user
 * constraints and verification gates are never part of an experiment.
 */
object AdvisoryHoldout {
    const val DEFAULT_WITHHELD_PERCENT = 20

    fun arm(
        taskId: String,
        layer: String,
        withheldPercent: Int = DEFAULT_WITHHELD_PERCENT
    ): AdvisoryArm {
        val percent = withheldPercent.coerceIn(0, 50)
        if (percent == 0 || taskId.isBlank()) return AdvisoryArm.EXPOSED
        return if (bucket(taskId, layer) < percent) {
            AdvisoryArm.WITHHELD
        } else {
            AdvisoryArm.EXPOSED
        }
    }

    /** Stable bucket 0..99 for (task, layer). */
    fun bucket(taskId: String, layer: String): Int {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("advisory-holdout|$layer|${taskId.trim()}".toByteArray(Charsets.UTF_8))
        val value =
            ((digest[0].toInt() and 0xff) shl 8) or
                (digest[1].toInt() and 0xff)
        return value % 100
    }
}
