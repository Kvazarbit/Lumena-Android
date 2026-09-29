package com.lumena.android.agent.core

data class SnakePoint(val x: Int, val y: Int)

enum class SnakeDirection(val dx: Int, val dy: Int) {
    UP(0, -1),
    DOWN(0, 1),
    LEFT(-1, 0),
    RIGHT(1, 0);

    fun oppositeOf(other: SnakeDirection): Boolean =
        dx + other.dx == 0 && dy + other.dy == 0
}

/**
 * body is ordered head -> tail.
 * food == null means food placement is currently unknown/outside this
 * deterministic transition. This prevents the world model from inventing a
 * spawn location after eating.
 */
data class SnakeState(
    val width: Int,
    val height: Int,
    val body: List<SnakePoint>,
    val food: SnakePoint?,
    val direction: SnakeDirection,
    val alive: Boolean = true,
    val score: Int = 0,
    val tick: Int = 0
) {
    init {
        require(width > 1 && height > 1)
        require(body.isNotEmpty())
        require(body.distinct().size == body.size)
        require(body.all { it.x in 0 until width && it.y in 0 until height })
        require(food == null || (food.x in 0 until width && food.y in 0 until height))
    }

    val head: SnakePoint get() = body.first()
    val tail: SnakePoint get() = body.last()
}

object SnakeCognitiveEnvironment : CognitiveEnvironment<SnakeState, SnakeDirection> {
    override fun legalActions(state: SnakeState): List<SnakeDirection> {
        if (!state.alive) return emptyList()
        return SnakeDirection.entries.filter { candidate ->
            state.body.size <= 1 || !candidate.oppositeOf(state.direction)
        }
    }

    override fun predict(state: SnakeState, action: SnakeDirection): SnakeState? {
        if (action !in legalActions(state)) return null

        val nextHead = SnakePoint(
            x = state.head.x + action.dx,
            y = state.head.y + action.dy
        )
        val inside = nextHead.x in 0 until state.width && nextHead.y in 0 until state.height
        if (!inside) {
            return state.copy(
                direction = action,
                alive = false,
                tick = state.tick + 1
            )
        }

        val eats = state.food != null && nextHead == state.food
        // If the snake does not grow, the old tail vacates before collision is
        // judged. Entering that exact cell is therefore legal.
        val occupied = if (eats) state.body else state.body.dropLast(1)
        if (nextHead in occupied) {
            return state.copy(
                direction = action,
                alive = false,
                tick = state.tick + 1
            )
        }

        val nextBody = if (eats) {
            listOf(nextHead) + state.body
        } else {
            listOf(nextHead) + state.body.dropLast(1)
        }

        return state.copy(
            body = nextBody,
            food = if (eats) null else state.food,
            direction = action,
            alive = true,
            score = state.score + if (eats) 1 else 0,
            tick = state.tick + 1
        )
    }

    override fun goalScore(state: SnakeState): Double {
        if (!state.alive) return -10_000.0 + state.score * 100.0

        val foodDistance = state.food?.let {
            kotlin.math.abs(state.head.x - it.x) + kotlin.math.abs(state.head.y - it.y)
        } ?: 0

        // Reward verified survival and food; prefer shorter distance only while
        // a deterministic food target is known.
        return 1_000.0 +
            state.score * 100.0 -
            foodDistance.toDouble() +
            legalActions(state).size * 0.1
    }

    override fun contextKey(state: SnakeState): String {
        val occupancyBucket = when {
            state.body.size * 4 >= state.width * state.height * 3 -> "dense"
            state.body.size * 4 >= state.width * state.height -> "mid"
            else -> "sparse"
        }
        return "${state.width}x${state.height}:$occupancyBucket"
    }
}

/**
 * Compact full-state payload for an external model. No derived "best move" is
 * leaked: the model sees geometry, ordered head->tail body, food, direction
 * and legal actions.
 */
object SnakeStateCodec {
    fun encode(state: SnakeState): String = buildString {
        append("width=").append(state.width)
        append(";height=").append(state.height)
        append(";direction=").append(state.direction.name)
        append(";alive=").append(state.alive)
        append(";score=").append(state.score)
        append(";tick=").append(state.tick)
        append(";food=")
        append(state.food?.let { "${it.x},${it.y}" } ?: "none")
        append(";body_head_to_tail=")
        append(state.body.joinToString("|") { "${it.x},${it.y}" })
        append(";legal=")
        append(SnakeCognitiveEnvironment.legalActions(state).joinToString(",") { it.name })
    }
}
