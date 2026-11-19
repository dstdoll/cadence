package app.cadence.backend.engine.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * A native key exchange that never returns must fail only its own caller. Downloads and streaming
 * both go through [KdResolver], so a stuck exchange that kept the lock would stop every later
 * download and every streamed song that needs a new key until the process died.
 */
class KdResolverTest {
    private val original = KdResolver.exchange
    private val release = CountDownLatch(1)

    @After
    fun restore() {
        KdResolver.exchange = original
        release.countDown() // lets the "stuck" exchanges of this test finish
    }

    private fun plan(keyUri: String) = KeyPlan("1", listOf(KeyPlan.SegmentKey(keyUri, "1")))

    private fun stuckUnlessOk() {
        KdResolver.exchange = { _, keyUri -> if (keyUri == STUCK) { release.await(); 0L } else HANDLE }
    }

    @Test
    fun aStuckExchangeDoesNotBlockTheNextOne() {
        stuckUnlessOk()
        assertThrows(IllegalStateException::class.java) { KdResolver.resolveWithTimeout(plan(STUCK), 300) }

        val started = System.nanoTime()
        val handles = KdResolver.resolveWithTimeout(plan(OK), 2_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(HANDLE, handles.single())
        assertTrue("the next exchange waited ${elapsedMs}ms behind the stuck one", elapsedMs < 1_500)
    }

    @Test
    fun aCallerThatGivesUpWhileWaitingLeavesNoBlockedThread() {
        stuckUnlessOk()
        // A long exchange holds the lock (its own deadline is far away)...
        val holder = thread(isDaemon = true) { runCatching { KdResolver.resolveWithTimeout(plan(STUCK), 30_000) } }
        Thread.sleep(200)
        // ...and a caller with a short deadline gives up while waiting for it.
        assertThrows(IllegalStateException::class.java) { KdResolver.resolveWithTimeout(plan(OK), 300) }
        Thread.sleep(300)

        val waiting = Thread.getAllStackTraces().filter { (t, stack) ->
            t.isAlive && stack.any { it.className == "java.util.concurrent.locks.ReentrantLock" && it.methodName == "lockInterruptibly" }
        }
        assertTrue("threads still waiting for the exchange lock: ${waiting.keys.map { it.name }}", waiting.isEmpty())
        // The holder is still stuck, yet a new exchange goes through at once.
        assertEquals(HANDLE, KdResolver.resolveWithTimeout(plan(OK), 2_000).single())
        assertTrue(holder.isAlive)
    }

    @Test
    fun healthyExchangesStillRunOneAtATime() {
        val inside = java.util.concurrent.atomic.AtomicInteger()
        val overlap = java.util.concurrent.atomic.AtomicBoolean(false)
        KdResolver.exchange = { _, _ ->
            if (inside.incrementAndGet() > 1) overlap.set(true)
            Thread.sleep(30)
            inside.decrementAndGet()
            HANDLE
        }
        val callers = List(6) { i -> thread { KdResolver.resolveWithTimeout(plan("skd://k/$i"), 5_000) } }
        callers.forEach { it.join(10_000) }
        assertTrue("two exchanges ran inside the native call at once", !overlap.get())
    }

    private companion object {
        const val STUCK = "skd://itunes.apple.com/P1/stuck"
        const val OK = "skd://itunes.apple.com/P1/c23"
        const val HANDLE = 42L
    }
}
