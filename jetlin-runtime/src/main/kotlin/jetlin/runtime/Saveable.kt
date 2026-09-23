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
 * A session's composition can be torn down while the user is away and rebuilt when they return,
 * possibly on a different server. Everything held in `remember` is lost then, because it lived in
 * the discarded slot table. Only what was registered here survives. That's why the code has to make
 * the difference visible: `remember` is scratch space, and [rememberSaved] is state the user would
 * notice losing.
 */
public interface SaveableStateRegistry {

    /**
     * Removes and returns the value that an earlier composition saved under [key].
     *
     * This consumes the value instead of reading it. A restored value seeds exactly one caller, so if
     * two composables collide on a key, they can't both receive it without anyone noticing.
     *
     * @return the saved value, or `null` if nothing was saved under [key] or it was already consumed.
     */
    public fun consumeRestored(key: String): JsonElement?

    /**
     * Registers [provider] as the source of the value saved under [key].
     *
     * [performSave] calls [provider] at save time. A provider that returns `null` saves nothing.
     *
     * @return a registration to cancel when the value no longer needs saving.
     */
    public fun registerProvider(key: String, provider: () -> JsonElement?): Registration

    /**
     * Asks every registered provider for its current value.
     *
     * @return the non-null values, by key.
     * @throws IllegalStateException if two providers are registered under the same key.
     */
    public fun performSave(): Map<String, JsonElement>

    /** A provider registered with [registerProvider]. */
    public fun interface Registration {
        /** Removes the provider, so later saves no longer include its value. */
        public fun unregister()
    }
}

/**
 * Creates a [SaveableStateRegistry].
 *
 * @param restored the values saved by an earlier composition, which
 *   [SaveableStateRegistry.consumeRestored] hands out.
 */
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

/**
 * The error for two saved values that share a key. [performSave][SaveableStateRegistry.performSave]
 * throws it.
 */
internal const val COLLISION_MESSAGE: String =
    "Two rememberSaved values share a key, so one would overwrite the other. Pass an explicit " +
        "key to each: rememberSaved(key = \"draft\") { ... }. Automatic keys come from the " +
        "composable's position in the tree, which is not unique where the tree itself moves — a " +
        "loop over reorderable data being the usual way to arrive here."

/**
 * The registry that [rememberSaved] registers with, or `null` if nothing saves state here.
 *
 * Without a registry, [rememberSaved] behaves like `remember`.
 */
public val LocalSaveableStateRegistry: ProvidableCompositionLocal<SaveableStateRegistry?> =
    staticCompositionLocalOf { null }

/**
 * The codec for saved state.
 *
 * It's separate from the wire codec, because saved state has a different reader and lives much
 * longer. [Json.ignoreUnknownKeys] lets state written by a newer version still load.
 */
internal val SavedStateJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * Like `remember`, but the value survives the composition being torn down and rebuilt.
 *
 * Use it for state that a user would be annoyed to lose after a dropped connection or a server
 * restart, such as a half-typed form, a selected tab, or an expanded row. Keep anything derived or
 * cheap to recompute in plain `remember`, so the saved state stays small.
 *
 * A saved value that no longer deserializes, because its type changed after it was written, falls
 * back to [init] instead of failing the session. Saved state outlives deployments, so meeting an
 * older shape is normal, not exceptional.
 *
 * @param serializer the serializer for the value.
 * @param key the key the value is saved under. It defaults to the composable's position in the
 *   composition. That tells apart saved values in different composables and, since Compose 1.12,
 *   two side by side in the same composable. A position isn't a stable identity where the tree
 *   itself moves, so pass an explicit key inside a loop over data that can be reordered. If two
 *   values do collide, saving reports an error instead of losing one of them.
 * @param init produces the initial value when nothing was saved.
 * @return the state holding the value. Writes to it are saved.
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

/**
 * Like `remember`, but the value survives the composition being torn down and rebuilt.
 *
 * This overload looks up the serializer for [T]. See the other `rememberSaved` for details.
 */
@Composable
public inline fun <reified T> rememberSaved(
    key: String? = null,
    noinline init: () -> T,
): MutableState<T> = rememberSaved(serializer<T>(), key, init)
