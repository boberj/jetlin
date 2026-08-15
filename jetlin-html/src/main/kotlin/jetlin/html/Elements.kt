package jetlin.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import jetlin.protocol.EventPayload
import jetlin.protocol.Extract
import jetlin.protocol.ListenerSpec
import jetlin.protocol.PropValue

public val LocalHtmlOwner: ProvidableCompositionLocal<HtmlOwner> =
    staticCompositionLocalOf { error("No HtmlOwner in composition; use JetlinView to host content") }

/** Declares attributes, DOM properties and event handlers for one element. */
public class AttrsScope internal constructor() {
    private val attributes = LinkedHashMap<String, String>()
    private val properties = LinkedHashMap<String, PropValue>()
    private val listeners = LinkedHashMap<String, ListenerSpec>()
    private val handlers = LinkedHashMap<String, EventHandler>()

    public fun attr(name: String, value: String?) {
        if (value == null) attributes.remove(name) else attributes[name] = value
    }

    public fun classes(value: String?): Unit = attr("class", value)
    public fun id(value: String?): Unit = attr("id", value)
    public fun style(value: String?): Unit = attr("style", value)
    public fun type(value: String): Unit = attr("type", value)
    public fun placeholder(value: String): Unit = attr("placeholder", value)
    public fun name(value: String): Unit = attr("name", value)
    public fun href(value: String): Unit = attr("href", value)
    public fun disabled(value: Boolean) { if (value) attr("disabled", "") else attr("disabled", null) }

    /**
     * Sets a DOM property. Use for `value`/`checked`: assigning the attribute instead only changes
     * the control's default and stops taking effect once the user has touched it.
     */
    public fun prop(name: String, value: String) { properties[name] = PropValue.Str(value) }
    public fun prop(name: String, value: Boolean) { properties[name] = PropValue.Bool(value) }

    public fun value(value: String): Unit = prop("value", value)
    public fun checked(value: Boolean): Unit = prop("checked", value)

    /**
     * Writes [html] into the element without escaping it.
     *
     * Everywhere else in Jetlin, text is a node and cannot become markup. This is the one deliberate
     * exception, for content that is already HTML and already trusted — rendered Markdown, a sanitized
     * fragment, an inline SVG. Passing anything derived from user input makes the page vulnerable.
     *
     * The element may not also have composable children; the applier rejects that combination rather
     * than letting the two silently overwrite each other.
     */
    public fun unsafeInnerHtml(html: String): Unit = prop(INNER_HTML, html)

    public fun on(event: String, spec: ListenerSpec = ListenerSpec(), handler: EventHandler) {
        listeners[event] = spec
        handlers[event] = handler
    }

    public fun onClick(handler: () -> Unit): Unit = on("click") { handler() }

    /** Fires as the user types. [debounceMs] keeps a keystroke-per-round-trip design honest. */
    public fun onInput(debounceMs: Int = 0, handler: (String) -> Unit): Unit =
        on("input", ListenerSpec(extract = listOf(Extract.VALUE), debounceMs = debounceMs)) {
            handler(it.value.orEmpty())
        }

    public fun onChecked(handler: (Boolean) -> Unit): Unit =
        on("change", ListenerSpec(extract = listOf(Extract.CHECKED))) {
            handler(it.checked ?: false)
        }

    public fun onSubmit(handler: (Map<String, String>) -> Unit): Unit =
        on("submit", ListenerSpec(extract = listOf(Extract.FORM), preventDefault = true)) {
            handler(it.form.orEmpty())
        }

    public fun onKeyDown(handler: (String) -> Unit): Unit =
        on("keydown", ListenerSpec(extract = listOf(Extract.KEY))) {
            handler(it.key.orEmpty())
        }

    /** Marks a `<option>` as chosen. A property rather than an attribute, like `value`. */
    public fun selected(value: Boolean): Unit = prop("selected", value)

    internal fun data(): ElementData = ElementData(attributes, properties, listeners)
    internal fun handlers(): Map<String, EventHandler> = handlers
}

@Composable
public fun Element(
    tag: String,
    attrs: (AttrsScope.() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val owner = LocalHtmlOwner.current
    val scope = AttrsScope()
    attrs?.invoke(scope)
    val data = scope.data()
    val handlers = scope.handlers()

    ComposeNode<ElementNode, HtmlApplier>(
        factory = { owner.createElement(tag) },
        update = {
            // Structural: only reaches the client when something genuinely differs.
            set(data) { applyData(it) }
            // Identity: fresh closures every recomposition, stored but never transmitted.
            set(handlers) { this.handlers = it }
        },
        content = content,
    )
}

/**
 * Emits a text node.
 *
 * Text is a node, never a string spliced into markup, so there is no interpolation point at which
 * user data could become HTML. Escaping is a property of the architecture rather than a rule
 * authors have to remember.
 */
@Composable
public fun Text(value: String) {
    val owner = LocalHtmlOwner.current
    ComposeNode<TextNode, HtmlApplier>(
        factory = { owner.createText(value) },
        update = { set(value) { text = it } },
    )
}

@Composable
public fun Div(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("div", attrs, content)

@Composable
public fun Span(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("span", attrs, content)

@Composable
public fun P(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("p", attrs, content)

@Composable
public fun H1(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("h1", attrs, content)

@Composable
public fun H2(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("h2", attrs, content)

@Composable
public fun Button(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("button", attrs, content)

@Composable
public fun Ul(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("ul", attrs, content)

@Composable
public fun Li(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("li", attrs, content)

@Composable
public fun Form(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("form", attrs, content)

@Composable
public fun Label(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("label", attrs, content)

@Composable
public fun A(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("a", attrs, content)

@Composable
public fun Input(attrs: (AttrsScope.() -> Unit)? = null): Unit = Element("input", attrs)

@Composable
public fun H3(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("h3", attrs, content)

@Composable
public fun Nav(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("nav", attrs, content)

@Composable
public fun Header(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("header", attrs, content)

@Composable
public fun Footer(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("footer", attrs, content)

@Composable
public fun Section(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("section", attrs, content)

@Composable
public fun Strong(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("strong", attrs, content)

@Composable
public fun Em(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("em", attrs, content)

@Composable
public fun Code(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("code", attrs, content)

@Composable
public fun Pre(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("pre", attrs, content)

@Composable
public fun Img(attrs: (AttrsScope.() -> Unit)? = null): Unit = Element("img", attrs)

@Composable
public fun TextArea(attrs: (AttrsScope.() -> Unit)? = null): Unit = Element("textarea", attrs)

@Composable
public fun Select(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("select", attrs, content)

@Composable
public fun Option(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("option", attrs, content)

@Composable
public fun Table(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("table", attrs, content)

@Composable
public fun Thead(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("thead", attrs, content)

@Composable
public fun Tbody(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("tbody", attrs, content)

@Composable
public fun Tr(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("tr", attrs, content)

@Composable
public fun Th(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("th", attrs, content)

@Composable
public fun Td(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("td", attrs, content)

/**
 * An anchor that navigates within the live session instead of reloading the page.
 *
 * It renders as an ordinary `<a href>`, so it is a real link: crawlers follow it, middle-click and
 * "open in new tab" work, and with JavaScript disabled it falls back to a normal request that
 * happens to start a fresh session on the same path. With the client running, the click is
 * intercepted and the session moves without a page load.
 */
@Composable
public fun Link(
    href: String,
    attrs: (AttrsScope.() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val navigator = LocalNavigator.current
    Element(
        tag = "a",
        attrs = {
            href(href)
            attrs?.invoke(this)
            on("click", ListenerSpec(preventDefault = true)) { navigator.push(href) }
        },
        content = content,
    )
}
