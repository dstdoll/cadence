package app.cadence.backend.real

/**
 * Insertion-ordered map shared between the UI thread, which reads it during composition, and the
 * background loaders (start-up seed, search, album/artist/playlist fetches), which write to it.
 *
 * Every operation takes the same lock and [values] returns a snapshot, so a reader can iterate
 * while a loader inserts. A plain `LinkedHashMap` here throws `ConcurrentModificationException`
 * in the reader, or corrupts its links, as soon as both run at once.
 */
internal class OrderedCache<K, V> {
    private val map = LinkedHashMap<K, V>()

    operator fun get(key: K): V? = synchronized(map) { map[key] }

    operator fun set(key: K, value: V) {
        synchronized(map) { map[key] = value }
    }

    /** The value for [key]; throws when absent, like `Map.getValue`. */
    fun getValue(key: K): V = synchronized(map) { map.getValue(key) }

    fun putIfAbsent(key: K, value: V) {
        synchronized(map) { if (!map.containsKey(key)) map[key] = value }
    }

    /** Stores [value] under [key] at the newest position, even when the key already existed. */
    fun moveToEnd(key: K, value: V) {
        synchronized(map) {
            map.remove(key)
            map[key] = value
        }
    }

    /** A snapshot in insertion order; later writes do not affect it. */
    val values: List<V> get() = synchronized(map) { ArrayList(map.values) }

    val size: Int get() = synchronized(map) { map.size }

    /** Runs [block] on the underlying map as one atomic step, for updates such as a reorder. */
    fun <R> mutate(block: (LinkedHashMap<K, V>) -> R): R = synchronized(map) { block(map) }
}
