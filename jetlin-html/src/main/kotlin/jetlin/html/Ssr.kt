package jetlin.html

import jetlin.protocol.JetlinJson
import jetlin.protocol.ListenerSpec
import jetlin.protocol.PropValue
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** HTML elements that must not be given a closing tag. */
private val VOID_ELEMENTS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input",
    "link", "meta", "source", "track", "wbr",
)

private val LISTENER_MAP_SERIALIZER = MapSerializer(String.serializer(), ListenerSpec.serializer())

/**
 * Serializes the current tree to HTML for the initial page load.
 *
 * First paint is real HTML, so the page is indexable and readable before any JavaScript runs.
 * Elements carry their server node id in `data-jl` and their listener specs in `data-jl-on`, which
 * lets the client bind to the markup it was given.
 *
 * The composition that produced this HTML stays alive and is handed to the WebSocket when it
 * connects, so a page is composed once rather than once per request.
 */
public fun renderToHtml(owner: HtmlOwner): String = buildString {
    owner.root.children.forEach { appendNode(it) }
}

private fun StringBuilder.appendNode(node: HtmlNode) {
    when (node) {
        is TextNode -> append(escapeText(node.text))
        is ElementNode -> appendElement(node)
    }
}

private fun StringBuilder.appendElement(node: ElementNode) {
    append('<').append(node.tag)
    append(" data-jl=\"").append(node.id).append('"')

    for ((name, value) in node.attributes) {
        append(' ').append(name)
        if (value.isNotEmpty()) append("=\"").append(escapeAttribute(value)).append('"')
    }

    // On first paint there is no DOM yet, so properties have to be expressed as the attributes
    // that seed them. Once the client is connected they travel as Op.SetProp instead.
    for ((name, value) in node.properties) {
        when (value) {
            is PropValue.Str -> append(' ').append(name).append("=\"").append(escapeAttribute(value.v)).append('"')
            is PropValue.Bool -> if (value.v) append(' ').append(name)
        }
    }

    if (node.listeners.isNotEmpty()) {
        val json = JetlinJson.encodeToString(LISTENER_MAP_SERIALIZER, node.listeners)
        append(" data-jl-on=\"").append(escapeAttribute(json)).append('"')
    }

    append('>')
    if (node.tag in VOID_ELEMENTS) return

    node.children.forEach { appendNode(it) }
    append("</").append(node.tag).append('>')
}

private fun escapeText(value: String): String = buildString(value.length) {
    for (c in value) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        else -> append(c)
    }
}

private fun escapeAttribute(value: String): String = buildString(value.length) {
    for (c in value) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&#39;")
        else -> append(c)
    }
}
