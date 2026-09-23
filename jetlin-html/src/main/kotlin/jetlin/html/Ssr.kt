package jetlin.html

import jetlin.protocol.JetlinJson
import jetlin.protocol.ListenerSpec
import jetlin.protocol.Namespace
import jetlin.protocol.PropValue
import jetlin.protocol.ROOT_ID
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The HTML elements that must not have a closing tag.
 *
 * This rule applies only to HTML. Inside an `<svg>`, the parser is in foreign content, where no
 * element is void and every element must be closed: an unclosed `<path d="…">` would swallow its
 * siblings as children. No SVG element shares a name with this list today, so checking the
 * namespace guards against a future problem instead of fixing a current one. It costs one
 * comparison.
 */
private val VOID_ELEMENTS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input",
    "link", "meta", "source", "track", "wbr",
)

/** Serializes an element's listener specs into its `data-jl-on` attribute. */
private val LISTENER_MAP_SERIALIZER = MapSerializer(String.serializer(), ListenerSpec.serializer())

/**
 * Separates two adjacent text children, so the browser keeps them apart.
 *
 * Without it, `Div { Text("a"); Text("b") }` serializes as `ab`, which an HTML parser turns into one
 * text node. The client would then have one node fewer than the server, and every later child
 * index would be off by one.
 */
private const val TEXT_SEPARATOR = "<!--|-->"

/**
 * Stands in for an empty text child. Without it, the parser would create no node for the client to
 * adopt.
 */
private const val EMPTY_TEXT = "<!--0-->"

/**
 * Serializes the current tree to HTML for the initial page load.
 *
 * The first paint is real HTML, so the page can be indexed and read before any JavaScript runs.
 * Each element carries its server node ID in `data-jl` and its listener specs in `data-jl-on`, so
 * the client can bind to the markup it was given.
 *
 * The composition that produced this HTML stays alive, and the WebSocket takes it over when it
 * connects. So a page is composed once, not once per request.
 *
 * An SVG subtree needs no `xmlns` and no marker of its own. The parser switches into foreign
 * content at `<svg>` and back out at `</svg>`, and assigns the namespaces itself. Only the client,
 * when it builds nodes from ops, needs to be told, and [jetlin.protocol.NodeSpec] tells it.
 * Attribute names are written exactly as the composition gave them, which `viewBox` and
 * `preserveAspectRatio` need: the parser lowercases every attribute name, then restores the case of
 * the ones SVG defines.
 */
public fun renderToHtml(owner: HtmlOwner): String = buildString {
    appendChildren(owner.root.children)
}

/**
 * Returns the attributes of the element that contains the rendered HTML.
 *
 * The container is a node like any other, because it's the root of the server's tree. But the page
 * shell writes its markup, not [renderToHtml], so its identity and the text markers for its direct
 * children have to be passed along separately.
 */
public fun rootAttributes(owner: HtmlOwner): String = buildString {
    append(" data-jl=\"").append(ROOT_ID).append('"')
    textMarkers(owner.root)?.let { append(" data-jl-t=\"").append(it).append('"') }
}

/**
 * Writes [children] so that each text node stays separate after an HTML parser reads them.
 *
 * The client rebuilds its index of the tree by walking this markup. Everything it does depends on
 * agreeing with the server about which node is at which index.
 */
private fun StringBuilder.appendChildren(children: List<HtmlNode>) {
    var previousWasText = false
    for (child in children) {
        when (child) {
            is TextNode -> {
                if (child.text.isEmpty()) {
                    append(EMPTY_TEXT)
                    // The comment is a boundary, so the next text child needs no separator.
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
 * Returns `index:id` for each text child, so the client can identify nodes that have no attributes.
 *
 * Returns `null` when there are no text children, and for an element whose content is raw HTML,
 * because those children belong to whoever wrote the markup, not to the composition.
 */
private fun textMarkers(node: ElementNode): String? {
    if (node.hasUnsafeInnerHtml) return null
    val markers = node.children
        .withIndex()
        .filter { (_, child) -> child is TextNode }
        .joinToString(",") { (index, child) -> "$index:${child.id}" }
    return markers.ifEmpty { null }
}

/** Writes [node] and its subtree as HTML. */
private fun StringBuilder.appendElement(node: ElementNode) {
    append('<').append(node.tag)
    append(" data-jl=\"").append(node.id).append('"')
    textMarkers(node)?.let { append(" data-jl-t=\"").append(it).append('"') }
    // Tell the client that the children are supplied markup, not nodes to index.
    if (node.hasUnsafeInnerHtml) append(" data-jl-raw")

    for ((name, value) in node.attributes) {
        append(' ').append(name)
        if (value.isNotEmpty()) append("=\"").append(escapeAttribute(value)).append('"')
    }

    // Before the first paint there's no DOM, so write properties as the attributes that set their
    // initial values. Once the client connects, properties travel as Op.SetProp instead.
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
    if (node.namespace == Namespace.HTML && node.tag in VOID_ELEMENTS) return

    // innerHTML is the one place where a caller can bypass escaping, so write it verbatim, in place
    // of any children. The applier rejects an element that has both.
    val raw = node.properties[INNER_HTML]
    if (raw is PropValue.Str) {
        append(raw.v)
    } else {
        appendChildren(node.children)
    }
    append("</").append(node.tag).append('>')
}

/** Escapes [value] for use as element content. */
private fun escapeText(value: String): String = buildString(value.length) {
    for (c in value) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        else -> append(c)
    }
}

/** Escapes [value] for use inside a double-quoted attribute value. */
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
