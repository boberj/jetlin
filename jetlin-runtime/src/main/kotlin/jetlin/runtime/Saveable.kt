package jetlin.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

/**
 * Holds the state that outlives a composition.
 *
 * A session's composition can be torn down while the user is away and rebuilt when they come back —
 * possibly on a different server. Everything held in `remember` is gone at that point, because it
 * lived in the slot table that was discarded. What survives is what was registered here, which is
 * why the distinction has to be visible in the code: `remember` is scratch space, [rememberSaved]
 * is state the user would notice losing.
 */
public interface SaveableStateRegistry {

    /**
     * Takes the value stored under [key] by a previous incarnation, or null.
     *
     * Consuming rather than reading: a restored value seeds exactly one caller, so two composables
     * that collide on a key cannot both silently receive it.
     */
    public fun consumeRestored(key: String): JsonElement?

    /** Registers a source for [key]'s value, to be asked at save time. */
    public fun registerProvider(key: String, provider: () -> JsonElement?): Registration

    /** Asks every registered provider for its current value. */
    public fun performSave(): Map<String, JsonElement>

    public fun interface Registration {
        public fun unregister()
    }
}

public fun SaveableStateRegistry(
    restored: Map<String, JsonElement> = emptyMap(),
): SaveableStateRegistry = DefaultSaveableStateRegistry(restored)

private class DefaultSaveableStateRegistry(
    restored: Map<String, JsonElement>,
) : SaveableStateRegistry {

    private val restored: MutableMap<String, JsonElement> = restored.toMutableMap()
    private val providers = LinkedHashMap<String, MutableList<() -> JsonElement?>>()

    override fun consumeRestored(key: String): JsonElement? = restored.remove(key)

    override fun registerProvider(
        key: String,
        provider: () -> JsonElement?,
    ): SaveableStateRegistry.Registration {
        val forKey = providers.getOrPut(key) { mutableListOf() }
        forKey += provider
        return SaveableStateRegistry.Registration {
            forKey -= provider
            if (forKey.isEmpty()) providers.remove(key)
        }
    }

    override fun performSave(): Map<String, JsonElement> = buildMap {
        for ((key, forKey) in providers) {
            check(forKey.size == 1) {
                "$COLLISION_MESSAGE (key '$key' has ${forKey.size} values)"
            }
            forKey.single().invoke()?.let { put(key, it) }
        }
    }
}

internal const val COLLISION_MESSAGE: String =
    "Two rememberSaved values share a key, so one would overwrite the other. Pass an explicit " +
        "key to each: rememberSaved(key = \"draft\") { ... }. Automatic keys come from the " +
        "composable's position in the tree, which is not unique where the tree itself moves — a " +
        "loop over reorderable data being the usual way to arrive here."

public val LocalSaveableStateRegistry: ProvidableCompositionLocal<SaveableStateRegistry?> =
    staticCompositionLocalOf { null }

/** Codec for persisted state. Separate from the wire codec: different audience, different lifetime. */
internal val SavedStateJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * Like `remember`, but the value survives the composition being torn down and rebuilt.
 *
 * Use it for state a user would be annoyed to lose across a dropped connection or a server
 * restart — a half-typed form, a selected tab, an expanded row. Leave everything derived or
 * cheap to recompute in plain `remember`, so the saved payload stays small.
 *
 * [key] defaults to the composable's position in the composition, which tells apart saved values
 * living in different composables and, since Compose 1.12, two sitting side by side in the same
 * one. Position is still not an identity where the tree itself moves, so prefer an explicit key for
 * anything inside a loop over reorderable data. Any collision that does happen is detected at save
 * time and reported rather than allowed to lose data.
 *
 * A value that no longer deserializes — because the type changed since it was written — falls back
 * to [init] rather than failing the session. Stored state outlives deployments, so encountering
 * yesterday's shape is a normal event, not an exceptional one.
 */
@Composable
public fun <T> rememberSaved(
    serializer: KSerializer<T>,
    key: String? = null,
    init: () -> T,
): MutableState<T> {
    val registry = LocalSaveableStateRegistry.current
    val resolvedKey = key ?: currentCompositeKeyHash.toString(36)

    val state = remember {
        val restored = registry?.consumeRestored(resolvedKey)
        val initial = if (restored == null) {
            init()
        } else {
            runCatching { SavedStateJson.decodeFromJsonElement(serializer, restored) }.getOrElse { init() }
        }
        mutableStateOf(initial)
    }

    if (registry != null) {
        DisposableEffect(registry, resolvedKey) {
            val registration = registry.registerProvider(resolvedKey) {
                runCatching { SavedStateJson.encodeToJsonElement(serializer, state.value) }.getOrNull()
            }
            onDispose { registration.unregister() }
        }
    }

    return state
}

@Composable
public inline fun <reified T> rememberSaved(
    key: String? = null,
    noinline init: () -> T,
): MutableState<T> = rememberSaved(serializer<T>(), key, init)
