package app.cadence.backend.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The queue contract the Downloads window depends on: whatever order jobs were
 * enqueued in, active work is shown FIFO and finished work newest-first.
 */
class DownloadQueueTest {

    private fun job(id: String, phase: String, order: Long) = DownloadEngine.Job(
        trackId = id,
        title = id,
        subtitle = "",
        phase = phase,
        fraction = 0f,
        message = "",
        order = order,
    )

    @Test
    fun `active jobs are first in fifo order`() {
        val jobs = listOf(
            job("done-1", "done", 1),
            job("queued-2", "queued", 4),
            job("queued-1", "queued", 2),
            job("downloading", "downloading", 3),
            job("done-2", "done", 5),
        )
        assertEquals(
            listOf("queued-1", "downloading", "queued-2", "done-2", "done-1"),
            DownloadEngine.ordered(jobs).map { it.trackId },
        )
    }

    @Test
    fun `errors and done jobs leave the active section`() {
        val jobs = listOf(
            job("a", "error", 1),
            job("b", "done", 2),
            job("c", "decrypting", 3),
        )
        assertEquals(listOf("c", "b", "a"), DownloadEngine.ordered(jobs).map { it.trackId })
    }
}
