package jetlin.html

import jetlin.protocol.JetlinJson
import jetlin.protocol.ListenerSpec
import jetlin.protocol.PropValue
import jetlin.protocol.ROOT_ID
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** HTML elements that must not be given a closing tag. */
private val VOID_ELEMENTS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input",
    "link", "meta", "source", "track", "wbr",
)

private val LISTENER_MAP_SERIALIZER = MapSerializer(String.serializer(), ListenerSpec.serializer())

/**
 * Sits between two text children so the browser keeps them apart.
 *
 * `Div { Text("a"); Text("b") }` serializes as `ab`, which an HTML parser turns into a single text
 * node — leaving the client one node short of what the server thinks it has, and every later child
 * index off by one.
 */
private const val TEXT_SEPARATOR = "<!--|-->"

/** Stands in for a text child with no content, which would otherwise leave no node to adopt. */
private const val EMPTY_TEXT = "<!--0-->"

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
    appendChildren(owner.root.children)
}

/**
 * Attributes for the element the rendered HTML is placed inside.
 *
 * The container is a node like any other — it is the root of the server's tree — but the markup for
 * it is written by the page shell rather than by [renderToHtml], so its identity and any text
 * markers for children it holds directly have to be handed over separately.
 */
public fun rootAttributes(owner: HtmlOwner): String = buildString {
    append(" data-jl=\"").append(ROOT_ID).append('"')
    textMarkers(owner.root)?.let { append(" data-jl-t=\"").append(it).append('"') }
}

/**
 * Writes children, keeping text nodes distinguishable once an HTML parser has been through them.
 *
 * The client rebuilds its index of the tree by walking this markup, and its whole correctness rests
 * on agreeing with the server about which node is at which index.
 */
private fun StringBuilder.appendChildren(children: List<HtmlNode>) {
    var previousWasText = false
    for (child in children) {
        when (child) {
            is TextNode -> {
                if (child.text.isEmpty()) {
                    append(EMPTY_TEXT)
                    // The comment is itself a boundary, so the next text child needs no separator.
                    previousWasText = false
                } else {
                    if (previousWasText) append(TEXT_SEPARATOR)
                    append(escapeText(child.text))
                    previousWasText = true
                }
            }

            is ElementNode -> {
                appendElement(child)
                previousWasText = false
            }
        }
    }
}

/**
 * `index:id` for each text child, so the client can name nodes that carry no attributes of their own.
 *
 * Null when there are none, and for an element whose content is raw HTML — those children belong to
 * whoever wrote the markup, not to the composition.
 */
private fun textMarkers(node: ElementNode): String? {
    if (node.hasUnsafeInnerHtml) return null
    val markers = node.children
        .withIndex()
        .filter { (_, child) -> child is TextNode }
        .joinToString(",") { (index, child) -> "$index:${child.id}" }
    return markers.ifEmpty { null }
}

private fun StringBuilder.appendElement(node: ElementNode) {
    append('<').append(node.tag)
    append(" data-jl=\"").append(node.id).append('"')
    textMarkers(node)?.let { append(" data-jl-t=\"").append(it).append('"') }
    // Tells the client the children below are markup someone supplied, not nodes it should index.
    if (node.hasUnsafeInnerHtml) append(" data-jl-raw")

    for ((name, value) in node.attributes) {
        append(' ').append(name)
        if (value.isNotEmpty()) append("=\"").append(escapeAttribute(value)).append('"')
    }

    // On first paint there is no DOM yet, so properties have to be expressed as the attributes
    // that seed them. Once the client is connected they travel as Op.SetProp instead.
    for ((name, value) in node.properties) {
        if (name == INNER_HTML) continue
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

    // innerHTML is the one place a caller can bypass escaping, so it is written out verbatim and
    // takes the place of children entirely. The applier rejects an element that has both.
    val raw = node.properties[INNER_HTML]
    if (raw is PropValue.Str) {
        append(raw.v)
    } else {
        appendChildren(node.children)
    }
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
