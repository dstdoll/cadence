package app.cadence.backend.engine.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class CdnResolverTest {
    private fun ip(vararg b: Int) = InetAddress.getByAddress(b.map { it.toByte() }.toByteArray())
    private val apple = ip(17, 253, 29, 153)
    private val known = ip(152, 231, 110, 240)
    private val host = "cdn.example.com"

    @Test
    fun theResolvedEdgeComesFirstAndTheKnownOneIsTheNextRoute() {
        val r = CdnResolver(system = { listOf(apple) }, doh = { emptyList() }, fallbacks = mapOf(host to known))
        assertEquals(listOf(apple, known), r.lookup(host))
        // Never listed twice when the resolver already answers with it.
        val same = CdnResolver(system = { listOf(known) }, doh = { emptyList() }, fallbacks = mapOf(host to known))
        assertEquals(listOf(known), same.lookup(host))
    }

    @Test
    fun answersAreCachedUntilTheyExpire() {
        var calls = 0
        var clock = 0L
        val r = CdnResolver(system = { calls++; listOf(apple) }, doh = { emptyList() }, fallbacks = emptyMap(), cacheMs = 1_000, now = { clock })
        r.lookup(host); r.lookup(host)
        assertEquals(1, calls)
        clock = 1_001
        r.lookup(host)
        assertEquals(2, calls)
    }

    @Test
    fun aStalledSystemResolverFallsBackToDoh() {
        val r = CdnResolver(system = { null }, doh = { listOf(apple) }, fallbacks = mapOf(host to known))
        assertEquals(listOf(apple, known), r.lookup(host))
    }

    @Test
    fun theKnownEdgeAnswersOnlyWhenResolutionFails() {
        val r = CdnResolver(system = { null }, doh = { emptyList() }, fallbacks = mapOf(host to known))
        assertEquals(listOf(known), r.lookup(host))
    }

    @Test
    fun withNothingAtAllItFailsClearly() {
        val r = CdnResolver(system = { emptyList() }, doh = { emptyList() }, fallbacks = emptyMap())
        assertThrows(UnknownHostException::class.java) { r.lookup(host) }
    }

    @Test
    fun ipv6IsUsedWhenItIsTheOnlyAnswer() {
        val v6 = InetAddress.getByName("2001:db8::1")
        val r = CdnResolver(system = { listOf(v6) }, doh = { emptyList() }, fallbacks = emptyMap())
        assertEquals(listOf(v6), r.lookup(host))
    }

    @Test
    fun whenEverythingFailsTheLastGoodAnswerIsServedEvenIfOld() {
        var up = true
        var clock = 0L
        val r = CdnResolver(system = { if (up) listOf(apple) else null }, doh = { emptyList() }, fallbacks = mapOf(host to known), cacheMs = 1_000, now = { clock })
        assertEquals(listOf(apple, known), r.lookup(host))
        up = false
        clock = 10_000_000 // long expired
        assertEquals(listOf(apple, known), r.lookup(host))
    }

    @Test
    fun goodAnswersSurviveARestart() {
        val file = java.io.File.createTempFile("edges", ".json").apply { delete(); deleteOnExit() }
        val moved = ip(17, 253, 99, 7) // the CDN moved to a new edge
        CdnResolver(system = { listOf(moved) }, doh = { emptyList() }, fallbacks = mapOf(host to known))
            .apply { rememberIn(FileEdgeStore(file)) }
            .lookup(host)
        // After a restart, with the resolver down, the learned edge is used before the known one.
        val restarted = CdnResolver(system = { null }, doh = { emptyList() }, fallbacks = mapOf(host to known))
        restarted.rememberIn(FileEdgeStore(file))
        assertEquals(listOf(moved, known), restarted.lookup(host))
    }

    @Test
    fun theStoreIsOnlyWrittenWhenTheAnswerChanges() {
        var saves = 0
        val store = object : EdgeStore {
            override fun load() = emptyMap<String, List<InetAddress>>()
            override fun save(edges: Map<String, List<InetAddress>>) { saves++ }
        }
        var clock = 0L
        val r = CdnResolver(system = { listOf(apple) }, doh = { emptyList() }, fallbacks = emptyMap(), cacheMs = 1, now = { clock })
        r.rememberIn(store)
        repeat(5) { clock += 10; r.lookup(host) }
        assertEquals(1, saves)
    }
}
