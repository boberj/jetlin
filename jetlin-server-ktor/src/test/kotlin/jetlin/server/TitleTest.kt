package jetlin.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import jetlin.html.DocumentTitle
import jetlin.html.Text
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests that the document title is escaped in the rendered page.
 *
 * A title can come from record data, so it's user input, and it's written into `<head>` before any
 * of the body's escaping applies.
 */
class TitleTest {

    @Test
    fun `a title from the composition can't inject markup`(): Unit = testApplication {
        application {
            jetlin {
                view("/", title = "Fallback") {
                    DocumentTitle("</title><script>alert(1)</script> & more")
                    Text("body")
                }
            }
        }

        val body = client.get("/").bodyAsText()

        assertFalse("<script>alert(1)</script>" in body, body)
        assertTrue("<title>&lt;/title&gt;&lt;script&gt;alert(1)&lt;/script&gt; &amp; more</title>" in body, body)
    }

    @Test
    fun `a title from the route table is escaped too`(): Unit = testApplication {
        application {
            jetlin {
                view("/", title = "Tom & Jerry <3") { Text("body") }
            }
        }

        val body = client.get("/").bodyAsText()

        assertTrue("<title>Tom &amp; Jerry &lt;3</title>" in body, body)
    }
}
