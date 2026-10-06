package jetlin.html

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull

/** Tests the guards that [Principals] builds, when it creates its own key. */
class PrincipalsTest {

    private val principals = Principals<String>(signIn = "/sign-in")

    @Test
    fun `a principal stored under the key is the one read back`() {
        val request = RequestContext("/").with(principals.key, "alice")

        assertEquals("alice", principals.of(request))
        assertEquals(Access.Allow, principals.signedIn.check(request))
    }

    @Test
    fun `a request without a principal is sent to the sign-in page`() {
        val request = RequestContext("/todos")

        assertNull(principals.of(request))
        assertEquals(Access.Redirect("/sign-in?next=/todos"), principals.signedIn.check(request))
    }

    @Test
    fun `each instance has a key of its own`() {
        // Keys compare by identity, so two applications' principals can't be read as each other's.
        assertNotSame(principals.key, Principals<String>().key)
    }
}
