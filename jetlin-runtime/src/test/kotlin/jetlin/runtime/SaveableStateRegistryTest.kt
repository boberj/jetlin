package jetlin.runtime

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The registry on its own, without a composition in front of it.
 *
 * Which is the only way left to reach the collision guard deliberately. Compose derives the
 * automatic key from position, and since 1.12 it tells apart the case that used to collide most
 * easily — two `rememberSaved` calls side by side. The guard still matters, because position is not
 * an identity in a loop over reorderable data, but a test that has to arrange a real collision
 * through the composer is a test pinned to one runtime's key derivation. This one is not.
 */
class SaveableStateRegistryTest {

    @Test
    fun `two providers on one key are reported rather than one overwriting the other`() {
        val registry = SaveableStateRegistry()
        registry.registerProvider("draft") { JsonPrimitive("first") }
        registry.registerProvider("draft") { JsonPrimitive("second") }

        val failure = assertFailsWith<IllegalStateException> { registry.performSave() }
        assertEquals(true, failure.message?.contains("share a key"), failure.message)
    }

    @Test
    fun `unregistering leaves the key free for another value`() {
        val registry = SaveableStateRegistry()
        val first = registry.registerProvider("draft") { JsonPrimitive("first") }
        first.unregister()
        registry.registerProvider("draft") { JsonPrimitive("second") }

        assertEquals(mapOf("draft" to JsonPrimitive("second")), registry.performSave())
    }

    @Test
    fun `a restored value seeds exactly one caller`() {
        val registry = SaveableStateRegistry(mapOf("draft" to JsonPrimitive("stored")))

        assertEquals(JsonPrimitive("stored"), registry.consumeRestored("draft"))
        assertNull(registry.consumeRestored("draft"), "a second reader must not get it too")
    }
}
