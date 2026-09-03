package jetlin.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Holds onto each key's [rememberSaved] values while that key is not composed.
 *
 * [SaveableStateRegistry] answers "what should survive this composition being thrown away", which is
 * hibernation. This answers a narrower question that a router asks constantly: what should survive
 * *leaving a page and coming back to it*. Without it, `rememberSaved` in a view means only "survives
 * hibernation", which is not what the name suggests and not what a back button needs — a half-typed
 * form or a scrolled list comes back blank.
 *
 * Each key composes against its own child registry, seeded from whatever that key saved last time.
 * Leaving stores the child's values; returning hands them back.
 */
public interface SaveableStateHolder {

    /**
     * Composes [content] against the state saved under [key], and saves it again on the way out.
     *
     * [key] identifies the thing being restored, not the thing being composed: a router passes the
     * location, so that two paths sharing one route pattern do not inherit each other's state.
     */
    @Composable
    public fun SaveableStateProvider(key: String, content: @Composable () -> Unit)
}

/**
 * A [SaveableStateHolder] that keeps the [maxKeys] most recently left keys.
 *
 * The cap exists because keys arrive from the outside — a router keyed on the location has as many
 * as the user cares to visit. Holding every one for the life of a session is a leak with a polite
 * name, and the state that matters is nearly always somewhere the user has just been.
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

    // Everything the holder is keeping is one value as far as the enclosing registry is concerned,
    // so a session that hibernates mid-route takes its saved pages with it.
    if (parent != null) {
        DisposableEffect(parent, holder) {
            val registration = parent.registerProvider(HOLDER_KEY, holder::save)
            onDispose { registration.unregister() }
        }
    }
    return holder
}

/**
 * The key the holder occupies in the enclosing registry.
 *
 * Namespaced because it shares a map with whatever the application saved: an app that picks this
 * exact string for its own `rememberSaved` gets the collision error, which is the right outcome.
 */
internal const val HOLDER_KEY: String = "jetlin.routes"

private class DefaultSaveableStateHolder(private val maxKeys: Int) : SaveableStateHolder {

    /** Keys that are not composed right now, most recently left last. */
    private val stashes = LinkedHashMap<String, Map<String, JsonElement>>()

    /** Keys composed right now. A router has one; nothing stops there being more. */
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
     * Everything worth keeping, or null when that is nothing.
     *
     * Null rather than an empty object because a session whose state is empty is not written to the
     * store at all, and a holder that always answered would quietly store every session that ever
     * rendered a page.
     */
    fun save(): JsonElement? {
        val all = LinkedHashMap<String, Map<String, JsonElement>>(stashes)
        for ((key, registry) in live) {
            // What a composed view says now beats what it left behind last time, including when
            // what it says now is nothing.
            val values = registry.performSave()
            if (values.isEmpty()) all.remove(key) else all[key] = values
        }
        return if (all.isEmpty()) null else JsonObject(all.mapValues { (_, values) -> JsonObject(values) })
    }

    fun restoreFrom(element: JsonElement) {
        for ((key, values) in element.jsonObject) stash(key, values.jsonObject)
    }

    private fun stash(key: String, values: Map<String, JsonElement>) {
        if (values.isEmpty()) {
            // A page with nothing to save should not push a page that has something off the end.
            stashes.remove(key)
            return
        }
        stashes.remove(key)
        stashes[key] = values
        while (stashes.size > maxKeys) stashes.remove(stashes.keys.first())
    }
}

/**
 * A registry that keeps a provider's last value when the provider goes away.
 *
 * The holder saves a key's values from `onDispose`, by which time the view's own `rememberSaved`
 * calls may already have unregistered — they dispose their effects in the same teardown, and which
 * runs first is the runtime's business, not something to depend on. Capturing on the way out makes
 * the order stop mattering.
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
        // A live provider is the better answer than whatever was captured from a previous one.
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
