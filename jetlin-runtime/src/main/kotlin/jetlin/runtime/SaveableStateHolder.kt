package jetlin.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Keeps each key's [rememberSaved] values while that key isn't composed.
 *
 * [SaveableStateRegistry] decides what survives when the whole composition is thrown away, which is
 * what hibernation needs. This interface covers a narrower case that a router hits all the time:
 * what survives when the user leaves a page and comes back. Without it, `rememberSaved` in a view
 * would only survive hibernation. That isn't what the name suggests, and it isn't what a back button
 * needs: a half-typed form or a scrolled list would come back blank.
 *
 * Each key composes against its own child registry, seeded with what that key saved last time.
 * Leaving the key stores the child's values, and returning hands them back.
 */
public interface SaveableStateHolder {

    /**
     * Composes [content] with the state saved under [key], and saves that state again when
     * [content] leaves the composition.
     *
     * @param key identifies the state to restore. `RouteHost` passes the matched route pattern, the
     *   same key it gives Compose's `key`, so a view's registry lives exactly as long as the view.
     */
    @Composable
    public fun SaveableStateProvider(key: String, content: @Composable () -> Unit)
}

/**
 * Remembers a [SaveableStateHolder] that keeps state for the [maxKeys] most recently left keys.
 *
 * The limit exists because the caller chooses the keys, and nothing bounds how many it uses. Keeping
 * every key for the life of a session could leak memory, and the state that matters is nearly
 * always on a page the user has just left.
 *
 * The holder saves itself into the enclosing [LocalSaveableStateRegistry], if there is one, so the
 * pages it keeps survive hibernation too.
 *
 * @throws IllegalArgumentException if [maxKeys] isn't positive.
 */
@Composable
public fun rememberSaveableStateHolder(maxKeys: Int = 32): SaveableStateHolder {
    require(maxKeys > 0) { "maxKeys must be positive, was $maxKeys" }
    val parent = LocalSaveableStateRegistry.current
    val holder = remember {
        DefaultSaveableStateHolder(maxKeys).also { holder ->
            parent?.consumeRestored(HOLDER_KEY)?.let(holder::restoreFrom)
        }
    }

    // Save everything the holder keeps as one value in the enclosing registry, so a session that
    // hibernates on a page takes the other pages' saved state with it.
    if (parent != null) {
        DisposableEffect(parent, holder) {
            val registration = parent.registerProvider(HOLDER_KEY, holder::save)
            onDispose { registration.unregister() }
        }
    }
    return holder
}

/**
 * The key the holder saves under in the enclosing registry.
 *
 * It has a `jetlin.` prefix because it shares a map with the application's own saved values. An
 * application that uses this exact key for its own `rememberSaved` gets the collision error, which
 * is the right outcome.
 */
internal const val HOLDER_KEY: String = "jetlin.routes"

private class DefaultSaveableStateHolder(private val maxKeys: Int) : SaveableStateHolder {

    /** The saved values of keys that aren't composed, with the most recently left key last. */
    private val stashes = LinkedHashMap<String, Map<String, JsonElement>>()

    /** The registries of keys that are composed. A router has one, but more are allowed. */
    private val live = LinkedHashMap<String, RetainingSaveableStateRegistry>()

    @Composable
    override fun SaveableStateProvider(key: String, content: @Composable () -> Unit) {
        val registry = remember(key) { RetainingSaveableStateRegistry(stashes[key].orEmpty()) }

        DisposableEffect(key, registry) {
            live[key] = registry
            onDispose {
                live.remove(key)
                stash(key, registry.performSave())
            }
        }

        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) { content() }
    }

    /**
     * Returns everything worth keeping, or `null` if there's nothing.
     *
     * It returns `null` instead of an empty object because a session with empty state isn't written
     * to the store at all. A holder that always returned something would store every session that
     * ever rendered a page.
     */
    fun save(): JsonElement? {
        val all = LinkedHashMap<String, Map<String, JsonElement>>(stashes)
        for ((key, registry) in live) {
            // A composed view's current values replace what it left behind last time, even when
            // it currently has nothing to save.
            val values = registry.performSave()
            if (values.isEmpty()) all.remove(key) else all[key] = values
        }
        return if (all.isEmpty()) null else JsonObject(all.mapValues { (_, values) -> JsonObject(values) })
    }

    /** Loads the keys that [save] returned before hibernation. */
    fun restoreFrom(element: JsonElement) {
        for ((key, values) in element.jsonObject) stash(key, values.jsonObject)
    }

    /** Stores [values] as the most recently left key, dropping the oldest key when over the limit. */
    private fun stash(key: String, values: Map<String, JsonElement>) {
        if (values.isEmpty()) {
            // Don't let a page with nothing to save push out a page that has something.
            stashes.remove(key)
            return
        }
        stashes.remove(key)
        stashes[key] = values
        while (stashes.size > maxKeys) stashes.remove(stashes.keys.first())
    }
}

/**
 * A registry that keeps a provider's last value after the provider unregisters.
 *
 * The holder saves a key's values in `onDispose`. By then, the view's own `rememberSaved` calls
 * might have unregistered already, because they dispose their effects in the same teardown, and the
 * runtime doesn't promise an order. Capturing each value as its provider leaves makes the order
 * irrelevant.
 */
private class RetainingSaveableStateRegistry(
    restored: Map<String, JsonElement>,
) : SaveableStateRegistry {

    private val restored: MutableMap<String, JsonElement> = restored.toMutableMap()
    private val providers = LinkedHashMap<String, MutableList<() -> JsonElement?>>()
    private val retained = LinkedHashMap<String, JsonElement>()

    override fun consumeRestored(key: String): JsonElement? = restored.remove(key)

    override fun registerProvider(
        key: String,
        provider: () -> JsonElement?,
    ): SaveableStateRegistry.Registration {
        val forKey = providers.getOrPut(key) { mutableListOf() }
        forKey += provider
        // A live provider's value is more current than one captured from an earlier provider.
        retained.remove(key)
        return SaveableStateRegistry.Registration {
            forKey -= provider
            if (forKey.isEmpty()) {
                providers.remove(key)
                provider()?.let { retained[key] = it }
            }
        }
    }

    override fun performSave(): Map<String, JsonElement> = buildMap {
        putAll(retained)
        for ((key, forKey) in providers) {
            check(forKey.size == 1) {
                "$COLLISION_MESSAGE (key '$key' has ${forKey.size} values)"
            }
            forKey.single().invoke()?.let { put(key, it) }
        }
    }
}
