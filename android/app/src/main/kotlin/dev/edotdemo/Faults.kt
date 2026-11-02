package dev.edotdemo

import kotlin.random.Random

/**
 * Real bugs, triggered by realistic conditions (DESIGN.md, "Where things break").
 */
object Faults {
    /** Fault #1: the Stats summary is computed on the main thread. Sometimes it takes 8 s. */
    fun computeStats(tasks: List<Task>): Stats {
        if (Random.nextDouble() < AppContext.faults.anrRate) {
            Thread.sleep(8_000) // stands in for a heavy synchronous aggregation on the UI thread
        }
        return Stats(total = tasks.size, done = tasks.count { it.done })
    }

    /**
     * Fault #3: the notification preview is built in a buffer sized by *character* count but
     * filled with UTF-8 *bytes*. ASCII titles are fine; emoji and accents overflow it.
     */
    fun notificationPreview(title: String): ByteArray {
        val buf = ByteArray(title.length)
        title.toByteArray(Charsets.UTF_8).copyInto(buf) // IndexOutOfBounds for multi-byte text
        return buf
    }
}

data class Stats(val total: Int, val done: Int)
