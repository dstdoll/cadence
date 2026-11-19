package app.cadence.state

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

private val Context.store by preferencesDataStore("cadence")
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** One serializable value persisted under [key] in a shared DataStore, exposed as hot state. */
class JsonStore<T>(
    context: Context,
    private val scope: CoroutineScope,
    key: String,
    private val serializer: KSerializer<T>,
    private val default: T,
) {
    private val ds = context.applicationContext.store
    private val prefKey = stringPreferencesKey(key)

    private fun decode(raw: String?) = raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: default

    // First read is blocking so the first frame already has the user's theme (no flash).
    private val initial: T = runBlocking { decode(ds.data.first()[prefKey]) }

    val state: StateFlow<T> = ds.data.map { decode(it[prefKey]) }.stateIn(scope, SharingStarted.Eagerly, initial)

    fun update(transform: (T) -> T) {
        scope.launch { ds.edit { prefs -> prefs[prefKey] = json.encodeToString(serializer, transform(decode(prefs[prefKey]))) } }
    }
}
