package dev.healthtracker.watch

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Lightweight in-memory FIFO buffer for pending samples.
 *
 * Room DB would be more durable across process death, but for a personal-use MVP the
 * uploader runs frequently enough that in-memory + WorkManager retries are acceptable.
 * Upgrade path: swap this out for a Room DAO with the same enqueueAll / drainAll surface.
 */
data class BufferedSample(
    val metric: String,
    val ts: Long,
    val value: Double,
    val unit: String,
)

object SampleBuffer {
    private val queue = ConcurrentLinkedQueue<BufferedSample>()

    fun enqueue(s: BufferedSample) { queue.add(s) }
    fun enqueueAll(list: List<BufferedSample>) { queue.addAll(list) }
    fun size(): Int = queue.size

    /** Removes and returns all currently queued samples. */
    fun drainAll(): List<BufferedSample> {
        val out = mutableListOf<BufferedSample>()
        while (true) {
            val s = queue.poll() ?: break
            out.add(s)
        }
        return out
    }
}
