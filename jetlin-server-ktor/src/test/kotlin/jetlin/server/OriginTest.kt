package jetlin.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OriginTest {

    @Test
    fun `same origin is allowed`() {
        assertTrue(originAllowed("https://app.example", "app.example", emptySet()))
    }

    @Test
    fun `a different origin is rejected`() {
        assertFalse(originAllowed("https://evil.example", "app.example", emptySet()))
    }

    @Test
    fun `a port mismatch is a different origin`() {
        assertFalse(originAllowed("http://localhost:9999", "localhost:8080", emptySet()))
    }

    @Test
    fun `an origin that merely starts with the host is rejected`() {
        assertFalse(originAllowed("https://app.example.evil.test", "app.example", emptySet()))
    }

    @Test
    fun `a configured origin is allowed even when it differs from the host`() {
        assertTrue(
            originAllowed("https://app.example", "internal.svc", setOf("https://app.example")),
        )
    }

    @Test
    fun `configuring origins excludes everything not listed`() {
        assertFalse(
            originAllowed("https://other.example", "other.example", setOf("https://app.example")),
        )
    }

    @Test
    fun `a missing Origin header is treated as a non-browser caller`() {
        assertTrue(originAllowed(null, "app.example", emptySet()))
    }

    @Test
    fun `a malformed origin is rejected`() {
        assertFalse(originAllowed("app.example", "app.example", emptySet()))
    }
}
