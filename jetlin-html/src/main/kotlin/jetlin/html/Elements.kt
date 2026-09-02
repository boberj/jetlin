package jetlin.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import jetlin.protocol.ClientCommand
import jetlin.protocol.ClientTarget
import jetlin.protocol.EventPayload
import jetlin.protocol.Extract
import jetlin.protocol.ListenerSpec
import jetlin.protocol.PropValue

public val LocalHtmlOwner: ProvidableCompositionLocal<HtmlOwner> =
    staticCompositionLocalOf { error("No HtmlOwner in composition; use JetlinView to host content") }

/**
 * Whether [AttrsScope.testTag] should also be written into the markup as `data-test`.
 *
 * Off by default, so tags cost nothing in production. Static because it is fixed for the life of a
 * session, which keeps reading it in every element free of invalidation tracking.
 */
public val LocalTestTagsExposed: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/** The attribute a [AttrsScope.testTag] takes when tags are exposed. */
internal const val TEST_TAG_ATTRIBUTE: String = "data-test"

/**
 * Builds the list of things the browser should do for itself; see [AttrsScope.clientOnly].
 */
public class ClientCommandsScope internal constructor() {
    internal val commands: MutableList<ClientCommand> = mutableListOf()

    /** Adds [name] when absent and removes it when present. The verb behind most disclosure UI. */
    public fun toggleClass(name: String, on: ClientTarget = ClientTarget.Self) {
        commands += ClientCommand.ToggleClass(name, on)
    }

    public fun addClass(name: String, on: ClientTarget = ClientTarget.Self) {
        commands += ClientCommand.AddClass(name, on)
    }

    public fun removeClass(name: String, on: ClientTarget = ClientTarget.Self) {
        commands += ClientCommand.RemoveClass(name, on)
    }

    public fun focus(on: ClientTarget = ClientTarget.Self) {
        commands += ClientCommand.Focus(on)
    }

    public fun blur(on: ClientTarget = ClientTarget.Self) {
        commands += ClientCommand.Blur(on)
    }
}

/**
 * Targets the nearest enclosing element carrying [className], starting with the element itself.
 *
 * How a control reaches the thing it controls — a button opening the card that contains it. A class
 * rather than a node reference because the browser has to resolve this on its own, without asking.
 */
public fun closest(className: String): ClientTarget = ClientTarget.Closest(className)

/** Declares attributes, DOM properties and event handlers for one element. */
public class AttrsScope internal constructor(
    /** The element being configured. Held only so that a rejected declaration can name it. */
    private val tag: String,
    private val exposeTestTags: Boolean = false,
) {
    private val attributes = LinkedHashMap<String, String>()
    private val properties = LinkedHashMap<String, PropValue>()
    private val listeners = LinkedHashMap<String, ListenerSpec>()
    private val handlers = LinkedHashMap<String, EventHandler>()

    private var testTag: String? = null

    public fun attr(name: String, value: String?) {
        if (value == null) attributes.remove(name) else attributes[name] = value
    }

    /**
     * Names this element for tests, without putting anything in the page.
     *
     * A test finds the element by this name rather than by a class or a wording, so it keeps working
     * when the design or the copy changes and breaks only when the behaviour does. The tag is held
     * on the node and never serialized, so unlike an ordinary `data-` attribute it costs nothing on
     * the wire and tells a reader of the page source nothing about the internals.
     *
     * Browser tests are the exception, since Playwright can only select on what is really in the
     * DOM. Setting `exposeTestTags` on the server writes tags out as `data-test` as well, and an
     * application turns that on outside production.
     */
    public fun testTag(value: String) {
        testTag = value
        // Written as a real attribute rather than added by the serializer, so that it travels in
        // NodeSpec, is patched by Op.SetAttr when it changes, and reaches nodes that arrive after
        // first paint — none of which would be true of markup conjured up at render time.
        if (exposeTestTags) attr(TEST_TAG_ATTRIBUTE, value)
    }

    public fun classes(value: String?): Unit = attr("class", value)
    public fun id(value: String?): Unit = attr("id", value)
    public fun style(value: String?): Unit = attr("style", value)
    public fun type(value: String): Unit = attr("type", value)
    public fun placeholder(value: String): Unit = attr("placeholder", value)
    public fun name(value: String): Unit = attr("name", value)
    public fun href(value: String): Unit = attr("href", value)
    public fun disabled(value: Boolean) { if (value) attr("disabled", "") else attr("disabled", null) }

    /** Shows a [Details] or a [Dialog] inline. Absent rather than `open="false"`, like [disabled]. */
    public fun open(value: Boolean) { if (value) attr("open", "") else attr("open", null) }

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

    /**
     * Listens for [event] and calls [handler] when the browser reports it.
     *
     * One handler per event per element. A second declaration is rejected rather than allowed to
     * replace the first: listeners are keyed by event name the whole way down — here, in the node,
     * in the op, and in the client's table — so there is nowhere for a second one to live, and a
     * page whose handler silently never runs is a bad afternoon.
     */
    public fun on(event: String, spec: ListenerSpec = ListenerSpec(), handler: EventHandler) {
        check(event !in handlers) {
            "<$tag> declares two handlers for '$event'. Only one can run, since listeners are keyed " +
                "by event name — note that onChecked and onChange both listen for 'change'. Declare " +
                "the one you meant, or do both things in a single on(\"$event\") { }."
        }
        listeners[event] = merge(listeners[event], spec)
        handlers[event] = handler
    }

    /**
     * Adds [handler] to run after whatever is already listening for [event].
     *
     * Internal, and for the framework's own composables only. [Link] wraps an element the caller
     * also gets to configure, and both of them have a legitimate claim on the click: the caller may
     * want to record it, and the link still has to navigate. Two parties are not the same situation
     * as one composable declaring two handlers, which [on] is right to reject. The wrapper runs
     * last, so the caller sees the click before the view is swapped out from under it.
     */
    internal fun alsoOn(event: String, spec: ListenerSpec = ListenerSpec(), handler: EventHandler) {
        val existing = handlers[event]
        listeners[event] = merge(listeners[event], spec)
        handlers[event] = if (existing == null) handler else { payload ->
            existing(payload)
            handler(payload)
        }
    }

    /**
     * Declares work the browser does for itself when [event] fires, with no round trip.
     *
     * Opening a menu, expanding a disclosure, focusing a field: the server has no opinion about any
     * of it, so asking it costs a network hop and buys nothing. What can be declared here is a fixed
     * set of verbs, not a script — the moment client behaviour can be arbitrary it becomes a second
     * application to keep in step with the first, which is the thing this framework exists to avoid.
     *
     * ```kotlin
     * Button({ clientOnly { toggleClass("open", on = closest("card")) } }) { Text("Details") }
     * ```
     *
     * Combining is allowed: an element declaring both this and [onClick] runs the commands
     * immediately and still tells the server. With no handler declared, the browser acts and stays
     * quiet.
     *
     * The classes named here are the browser's to manage. If the composition also writes `class` on
     * the same element, its value is authoritative and the next patch overwrites whatever was
     * toggled.
     */
    public fun clientOnly(event: String = "click", block: ClientCommandsScope.() -> Unit) {
        val scope = ClientCommandsScope().apply(block)
        listeners[event] = merge(listeners[event], ListenerSpec(commands = scope.commands))
    }

    /** Keeps whichever of the two declarations for one event carries each field. */
    private fun merge(existing: ListenerSpec?, added: ListenerSpec): ListenerSpec {
        if (existing == null) return added
        return ListenerSpec(
            extract = existing.extract.ifEmpty { added.extract },
            commands = existing.commands + added.commands,
            debounceMs = maxOf(existing.debounceMs, added.debounceMs),
            throttleMs = maxOf(existing.throttleMs, added.throttleMs),
            preventDefault = existing.preventDefault || added.preventDefault,
            stopPropagation = existing.stopPropagation || added.stopPropagation,
        )
    }

    public fun onClick(handler: () -> Unit): Unit = on("click") { handler() }

    /** Fires as the user types. [debounceMs] keeps a keystroke-per-round-trip design honest. */
    public fun onInput(debounceMs: Int = 0, handler: (String) -> Unit): Unit =
        on("input", ListenerSpec(extract = listOf(Extract.VALUE), debounceMs = debounceMs)) {
            handler(it.value.orEmpty())
        }

    /**
     * Fires when a control's value is committed rather than while it is being edited.
     *
     * The handler a `<select>` wants. [onInput] does work on one — a browser raises both — but it
     * is the verb for keystrokes, and reading `onInput` on a dropdown leaves the next person
     * wondering what a half-typed choice would be.
     *
     * `change` is also what a checkbox raises, so this and [onChecked] are the same listener asked
     * to extract two different things and cannot both be declared on one element. Doing so is
     * rejected where it is written rather than resolved silently in favour of whichever came last.
     */
    public fun onChange(handler: (String) -> Unit): Unit =
        on("change", ListenerSpec(extract = listOf(Extract.VALUE))) {
            handler(it.value.orEmpty())
        }

    /** Ticks and unticks. Shares the `change` event with [onChange]; see there. */
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

    /**
     * Registers a handler with no listener spec attached.
     *
     * For events the browser does not raise: a client component pushing to the server calls into the
     * runtime directly, so there is nothing to add a DOM listener for, and adding one would put a
     * pointless capture handler on the container.
     */
    internal fun handle(event: String, handler: EventHandler) {
        handlers[event] = handler
    }

    internal fun data(): ElementData = ElementData(
        attributes = attributes,
        properties = properties,
        // Derived rather than declared: the browser is told to report an event exactly when a
        // handler exists to receive it, so the two can never drift apart.
        listeners = listeners.mapValues { (event, spec) -> spec.copy(notify = event in handlers) },
        testTag = testTag,
    )
    internal fun handlers(): Map<String, EventHandler> = handlers
}

@Composable
public fun Element(
    tag: String,
    attrs: (AttrsScope.() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val owner = LocalHtmlOwner.current
    val scope = AttrsScope(tag, exposeTestTags = LocalTestTagsExposed.current)
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

@Composable
public fun Tfoot(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("tfoot", attrs, content)

/** The table's own heading. Announced with the table by a screen reader, unlike a heading above it. */
@Composable
public fun Caption(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("caption", attrs, content)

@Composable
public fun H4(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("h4", attrs, content)

@Composable
public fun H5(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("h5", attrs, content)

@Composable
public fun H6(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("h6", attrs, content)

@Composable
public fun Ol(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("ol", attrs, content)

/**
 * A disclosure the browser opens and closes for itself.
 *
 * Nothing is sent when the user toggles one: `<details>` owns its own `open` state and there is no
 * handler here for the `toggle` event. That is the same bargain as [AttrsScope.clientOnly] and is
 * usually the right one. The consequence is that [AttrsScope.open] is a declaration rather than a
 * mirror — set it and the next patch that changes it overwrites whatever the user had done.
 */
@Composable
public fun Details(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("details", attrs, content)

/** The always-visible line of a [Details]. Must be its first child, or the browser supplies one. */
@Composable
public fun Summary(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("summary", attrs, content)

/**
 * A `<dialog>`. Showing it is the caller's job, and only one of the two ways is available here.
 *
 * [AttrsScope.open] shows it inline, so a composition can drive it like any other attribute.
 * `showModal()` — top layer, backdrop, trapped focus, Escape to close — is a DOM *method*, and
 * Jetlin has no vocabulary for calling one: the ops are attributes, properties and listeners, and
 * adding a "call this method" op would open the door to the browser running arbitrary instructions
 * from the server, which the protocol exists to avoid. A modal needs a [ClientComponent] or a
 * script of the application's own. This is the element and nothing more, rather than an `open` that
 * silently is not modal.
 */
@Composable
public fun Dialog(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("dialog", attrs, content)

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
            // Added to whatever the caller declared rather than declared outright: a link is one of
            // the few elements two people configure, and a caller's own onClick would otherwise be
            // rejected for colliding with the navigation it knows nothing about.
            alsoOn("click", ListenerSpec(preventDefault = true)) { navigator.push(href) }
        },
        content = content,
    )
}
