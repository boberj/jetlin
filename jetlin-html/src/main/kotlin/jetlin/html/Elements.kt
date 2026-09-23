package jetlin.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import jetlin.protocol.ClientCommand
import jetlin.protocol.ClientTarget
import jetlin.protocol.EventPayload
import jetlin.protocol.Extract
import jetlin.protocol.ListenerSpec
import jetlin.protocol.Namespace
import jetlin.protocol.PropValue

/** The tree that element composables add their nodes to. `JetlinView` provides it. */
public val LocalHtmlOwner: ProvidableCompositionLocal<HtmlOwner> =
    staticCompositionLocalOf { error("No HtmlOwner in composition; use JetlinView to host content") }

/**
 * Whether [AttrsScope.testTag] also writes the tag into the markup as `data-test`.
 *
 * It's off by default, so test tags cost nothing in production. It's a static local because it's
 * fixed for the life of a session, so reading it in every element doesn't need invalidation
 * tracking.
 */
public val LocalTestTagsExposed: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/** The attribute that holds an [AttrsScope.testTag] when test tags are exposed. */
internal const val TEST_TAG_ATTRIBUTE: String = "data-test"

/**
 * The document language that elements are emitted in. See [Svg].
 *
 * This is deliberately not public. Every element inherits it, so providing it would let you declare
 * that a `<div>` is an SVG element. The browser accepts that, renders nothing, and reports no error.
 * [Svg] and [ForeignObject] are the only places where the language changes, and both are in this
 * file.
 *
 * It's a static local because it never changes for a given element. An element that moved between
 * languages would have to be recreated, and a composable that emits one is written either inside
 * `Svg { }` or outside it, never both.
 */
internal val LocalNamespace: ProvidableCompositionLocal<Namespace> =
    staticCompositionLocalOf { Namespace.HTML }

/**
 * Builds the list of commands that the browser runs by itself. See [AttrsScope.clientOnly].
 *
 * Each command applies to the element the listener is on, unless you pass another target in `on`.
 */
public class ClientCommandsScope internal constructor() {
    internal val commands: MutableList<ClientCommand> = mutableListOf()

    /**
     * Adds the CSS class [name] if it's missing, and removes it if it's present.
     *
     * Most disclosure UI, such as menus and expanding panels, uses this command.
     */
    public fun toggleClass(name: String, on: ClientTarget = ClientTarget.Self) {
        commands += ClientCommand.ToggleClass(name, on)
    }

    /** Adds the CSS class [name]. */
    public fun addClass(name: String, on: ClientTarget = ClientTarget.Self) {
        commands += ClientCommand.AddClass(name, on)
    }

    /** Removes the CSS class [name]. */
    public fun removeClass(name: String, on: ClientTarget = ClientTarget.Self) {
        commands += ClientCommand.RemoveClass(name, on)
    }

    /** Moves focus to the target. */
    public fun focus(on: ClientTarget = ClientTarget.Self) {
        commands += ClientCommand.Focus(on)
    }

    /** Removes focus from the target. */
    public fun blur(on: ClientTarget = ClientTarget.Self) {
        commands += ClientCommand.Blur(on)
    }
}

/**
 * Returns a target for the nearest enclosing element that has the CSS class [className], starting
 * with the element itself.
 *
 * Use it to reach the thing a control controls, such as the card that contains a button. It takes a
 * class instead of a node reference because the browser resolves it without asking the server.
 */
public fun closest(className: String): ClientTarget = ClientTarget.Closest(className)

/** Declares the attributes, DOM properties, and event handlers of one element. */
public class AttrsScope internal constructor(
    /** The tag of the element being configured. It's kept only so that errors can name it. */
    private val tag: String,
    private val exposeTestTags: Boolean = false,
) {
    private val attributes = LinkedHashMap<String, String>()
    private val properties = LinkedHashMap<String, PropValue>()
    private val listeners = LinkedHashMap<String, ListenerSpec>()
    private val handlers = LinkedHashMap<String, EventHandler>()

    private var testTag: String? = null

    /** Sets the HTML attribute [name] to [value], or removes it if [value] is `null`. */
    public fun attr(name: String, value: String?) {
        if (value == null) attributes.remove(name) else attributes[name] = value
    }

    /**
     * Names this element for tests, without adding anything to the page.
     *
     * A test finds the element by this name instead of by a class or its text, so the test keeps
     * working when the design or the wording changes, and breaks only when the behavior does. The
     * tag is stored on the node and never serialized. Unlike a `data-` attribute, it costs nothing
     * on the wire and reveals nothing about the internals to someone reading the page source.
     *
     * Browser tests are the exception, because Playwright can select only what's in the DOM. When
     * `exposeTestTags` is set on the server, tags are also written as `data-test` attributes.
     * Applications turn it on outside production.
     */
    public fun testTag(value: String) {
        testTag = value
        // Write a real attribute instead of having the serializer add one. Then it travels in
        // NodeSpec, Op.SetAttr patches it when it changes, and it reaches nodes that arrive after
        // the first paint. Markup added only at render time would do none of that.
        if (exposeTestTags) attr(TEST_TAG_ATTRIBUTE, value)
    }

    /** Sets the `class` attribute, or removes it if [value] is `null`. */
    public fun classes(value: String?): Unit = attr("class", value)

    /** Sets the `id` attribute, or removes it if [value] is `null`. */
    public fun id(value: String?): Unit = attr("id", value)

    /** Sets the `style` attribute, or removes it if [value] is `null`. */
    public fun style(value: String?): Unit = attr("style", value)

    /** Sets the `type` attribute. */
    public fun type(value: String): Unit = attr("type", value)

    /** Sets the `placeholder` attribute. */
    public fun placeholder(value: String): Unit = attr("placeholder", value)

    /** Sets the `name` attribute. */
    public fun name(value: String): Unit = attr("name", value)

    /** Sets the `href` attribute. For navigation within the session, use [Link]. */
    public fun href(value: String): Unit = attr("href", value)

    /** Disables the element if [value] is `true`. A disabled element has the `disabled` attribute. */
    public fun disabled(value: Boolean) { if (value) attr("disabled", "") else attr("disabled", null) }

    /**
     * Shows a [Details] or a [Dialog] inline if [value] is `true`.
     *
     * Like [disabled], a `false` value removes the attribute instead of writing `open="false"`.
     */
    public fun open(value: Boolean) { if (value) attr("open", "") else attr("open", null) }

    /**
     * Sets the DOM property [name] to a string.
     *
     * Use a property for `value` and `checked`. Setting the attribute instead changes only the
     * control's default, which stops having any effect once the user has touched the control.
     */
    public fun prop(name: String, value: String) { properties[name] = PropValue.Str(value) }

    /** Sets the DOM property [name] to a Boolean. See the `String` overload. */
    public fun prop(name: String, value: Boolean) { properties[name] = PropValue.Bool(value) }

    /** Sets the `value` property of an input, text area, or select. */
    public fun value(value: String): Unit = prop("value", value)

    /** Sets the `checked` property of a checkbox or radio button. */
    public fun checked(value: Boolean): Unit = prop("checked", value)

    /**
     * Writes [html] into the element without escaping it.
     *
     * Everywhere else in Jetlin, text is a node and can't become markup. This method is the one
     * deliberate exception. Use it for content that's already HTML and already trusted, such as
     * rendered Markdown, a sanitized fragment, or an inline SVG. Passing anything derived from user
     * input makes the page vulnerable to cross-site scripting.
     *
     * An element with raw HTML can't also have composable children. The applier throws instead of
     * letting the two overwrite each other.
     */
    public fun unsafeInnerHtml(html: String): Unit = prop(INNER_HTML, html)

    /**
     * Listens for [event] and calls [handler] when the browser reports it.
     *
     * An element can have one handler per event. A second declaration throws instead of replacing
     * the first. Listeners are keyed by event name everywhere: here, in the node, in the op, and in
     * the client's table. So there's nowhere to keep a second handler, and a handler that never runs
     * without any error is hard to debug.
     *
     * @param spec what the client extracts from the event, and how it debounces or throttles it.
     * @throws IllegalStateException if this element already has a handler for [event].
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
     * Adds [handler] to run after any handler already listening for [event].
     *
     * This is internal, for the framework's own composables. [Link] wraps an element that the caller
     * also configures, and both have a legitimate use for the click: the caller might record it, and
     * the link still has to navigate. That's different from one composable declaring two handlers,
     * which [on] rightly rejects. The wrapper's handler runs last, so the caller sees the click
     * before the view changes.
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
     * Declares work that the browser does by itself when [event] fires, without a round trip.
     *
     * The server doesn't care about opening a menu, expanding a disclosure, or focusing a field, so
     * asking it adds a network hop for nothing. You can declare only a fixed set of commands here,
     * not a script. Arbitrary client behavior would be a second application to keep in step with the
     * first, which is what this framework exists to avoid.
     *
     * ```kotlin
     * Button({ clientOnly { toggleClass("open", on = closest("card")) } }) { Text("Details") }
     * ```
     *
     * You can combine this with a handler. An element that declares both this and [onClick] runs
     * the commands immediately and still tells the server. With no handler, the browser runs the
     * commands and sends nothing.
     *
     * The browser manages the classes named here. If the composition also sets `class` on the same
     * element, the composition wins, and the next patch overwrites whatever the browser toggled.
     */
    public fun clientOnly(event: String = "click", block: ClientCommandsScope.() -> Unit) {
        val scope = ClientCommandsScope().apply(block)
        listeners[event] = merge(listeners[event], ListenerSpec(commands = scope.commands))
    }

    /** Combines two declarations for one event, keeping each field that either one sets. */
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

    /** Calls [handler] when the element is clicked. */
    public fun onClick(handler: () -> Unit): Unit = on("click") { handler() }

    /**
     * Calls [handler] with the new value as the user types.
     *
     * @param debounceMs how long, in milliseconds, typing must pause before the client sends the
     *   value. Without it, every keystroke is a round trip.
     */
    public fun onInput(debounceMs: Int = 0, handler: (String) -> Unit): Unit =
        on("input", ListenerSpec(extract = listOf(Extract.VALUE), debounceMs = debounceMs)) {
            handler(it.value.orEmpty())
        }

    /**
     * Calls [handler] with the new value when the user commits it, not while they're editing it.
     *
     * Use this for a `<select>`. [onInput] also works on one, because the browser raises both
     * events, but `onInput` is meant for typing, and on a dropdown it makes the next reader wonder
     * what a half-typed choice would be.
     *
     * A checkbox also raises `change`, so this and [onChecked] are the same listener extracting two
     * different things. You can't declare both on one element. [on] throws when you try, instead of
     * letting the last one win.
     */
    public fun onChange(handler: (String) -> Unit): Unit =
        on("change", ListenerSpec(extract = listOf(Extract.VALUE))) {
            handler(it.value.orEmpty())
        }

    /**
     * Calls [handler] with the new state when the user checks or clears a checkbox or radio button.
     *
     * It shares the `change` event with [onChange]. See that method.
     */
    public fun onChecked(handler: (Boolean) -> Unit): Unit =
        on("change", ListenerSpec(extract = listOf(Extract.CHECKED))) {
            handler(it.checked ?: false)
        }

    /**
     * Calls [handler] with the form's fields, by name, when the form is submitted.
     *
     * The browser's own submission is prevented, so the page doesn't reload.
     */
    public fun onSubmit(handler: (Map<String, String>) -> Unit): Unit =
        on("submit", ListenerSpec(extract = listOf(Extract.FORM), preventDefault = true)) {
            handler(it.form.orEmpty())
        }

    /** Calls [handler] with the key's name, such as `Enter`, when a key is pressed. */
    public fun onKeyDown(handler: (String) -> Unit): Unit =
        on("keydown", ListenerSpec(extract = listOf(Extract.KEY))) {
            handler(it.key.orEmpty())
        }

    /** Selects an `<option>`. Like `value`, it's set as a property, not an attribute. */
    public fun selected(value: Boolean): Unit = prop("selected", value)

    /**
     * Registers a handler without a listener spec.
     *
     * Use it for events that the browser doesn't raise. A client component sending an event to the
     * server calls the runtime directly, so there's no DOM event to listen for, and a listener would
     * only add a useless handler to the container.
     */
    internal fun handle(event: String, handler: EventHandler) {
        handlers[event] = handler
    }

    /** Returns what this scope declared, for the applier to compare with the previous pass. */
    internal fun data(): ElementData = ElementData(
        attributes = attributes,
        properties = properties,
        // Derive notify instead of declaring it. The browser reports an event exactly when a
        // handler exists to receive it, so the two can never disagree.
        listeners = listeners.mapValues { (event, spec) -> spec.copy(notify = event in handlers) },
        testTag = testTag,
    )
    /** Returns the declared handlers, by event name. */
    internal fun handlers(): Map<String, EventHandler> = handlers
}

/**
 * Emits an element with any tag.
 *
 * Use this for tags that have no composable of their own. Inside [Svg], the element is an SVG
 * element.
 *
 * @param tag the tag name. SVG tag names are case-sensitive.
 * @param attrs declares the element's attributes, properties, and handlers.
 * @param content the element's children.
 */
@Composable
public fun Element(
    tag: String,
    attrs: (AttrsScope.() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val owner = LocalHtmlOwner.current
    val namespace = LocalNamespace.current
    val scope = AttrsScope(tag, exposeTestTags = LocalTestTagsExposed.current)
    attrs?.invoke(scope)
    val data = scope.data()
    val handlers = scope.handlers()

    ComposeNode<ElementNode, HtmlApplier>(
        factory = { owner.createElement(tag, namespace) },
        update = {
            // Compared by value, so this reaches the client only when something really changed.
            set(data) { applyData(it) }
            // Compared by identity. Every recomposition makes new closures, which are stored but
            // never sent.
            set(handlers) { this.handlers = it }
        },
        content = content,
    )
}

/**
 * Emits a text node.
 *
 * Text is a node, never a string spliced into markup, so there's no point at which user data could
 * become HTML. Escaping is built into the design, instead of being a rule that you have to remember.
 */
@Composable
public fun Text(value: String) {
    val owner = LocalHtmlOwner.current
    ComposeNode<TextNode, HtmlApplier>(
        factory = { owner.createText(value) },
        update = { set(value) { text = it } },
    )
}

/** Emits a `<div>`. */
@Composable
public fun Div(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("div", attrs, content)

/** Emits a `<span>`. */
@Composable
public fun Span(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("span", attrs, content)

/** Emits a `<p>`. */
@Composable
public fun P(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("p", attrs, content)

/** Emits an `<h1>`. */
@Composable
public fun H1(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("h1", attrs, content)

/** Emits an `<h2>`. */
@Composable
public fun H2(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("h2", attrs, content)

/** Emits a `<button>`. */
@Composable
public fun Button(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("button", attrs, content)

/** Emits a `<ul>`. */
@Composable
public fun Ul(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("ul", attrs, content)

/** Emits a `<li>`. */
@Composable
public fun Li(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("li", attrs, content)

/** Emits a `<form>`. */
@Composable
public fun Form(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("form", attrs, content)

/** Emits a `<label>`. */
@Composable
public fun Label(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("label", attrs, content)

/** Emits an `<a>`. For navigation within the session, use [Link]. */
@Composable
public fun A(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("a", attrs, content)

/** Emits an `<input>`. It can't have children. */
@Composable
public fun Input(attrs: (AttrsScope.() -> Unit)? = null): Unit = Element("input", attrs)

/** Emits an `<h3>`. */
@Composable
public fun H3(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("h3", attrs, content)

/** Emits a `<nav>`. */
@Composable
public fun Nav(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("nav", attrs, content)

/** Emits a `<header>`. */
@Composable
public fun Header(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("header", attrs, content)

/** Emits a `<footer>`. */
@Composable
public fun Footer(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("footer", attrs, content)

/** Emits a `<section>`. */
@Composable
public fun Section(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("section", attrs, content)

/** Emits a `<strong>`. */
@Composable
public fun Strong(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("strong", attrs, content)

/** Emits an `<em>`. */
@Composable
public fun Em(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("em", attrs, content)

/** Emits a `<code>`. */
@Composable
public fun Code(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("code", attrs, content)

/** Emits a `<pre>`. */
@Composable
public fun Pre(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("pre", attrs, content)

/** Emits an `<img>`. It can't have children. */
@Composable
public fun Img(attrs: (AttrsScope.() -> Unit)? = null): Unit = Element("img", attrs)

/** Emits a `<textarea>`. Set its text with [AttrsScope.value], not with children. */
@Composable
public fun TextArea(attrs: (AttrsScope.() -> Unit)? = null): Unit = Element("textarea", attrs)

/** Emits a `<select>`. */
@Composable
public fun Select(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("select", attrs, content)

/** Emits an `<option>`. */
@Composable
public fun Option(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("option", attrs, content)

/** Emits a `<table>`. */
@Composable
public fun Table(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("table", attrs, content)

/** Emits a `<thead>`. */
@Composable
public fun Thead(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("thead", attrs, content)

/** Emits a `<tbody>`. */
@Composable
public fun Tbody(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("tbody", attrs, content)

/** Emits a `<tr>`. */
@Composable
public fun Tr(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("tr", attrs, content)

/** Emits a `<th>`. */
@Composable
public fun Th(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("th", attrs, content)

/** Emits a `<td>`. */
@Composable
public fun Td(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("td", attrs, content)

/** Emits a `<tfoot>`. */
@Composable
public fun Tfoot(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("tfoot", attrs, content)

/**
 * Emits a `<caption>`, the table's own heading.
 *
 * A screen reader announces it with the table, which it doesn't do for a heading above the table.
 */
@Composable
public fun Caption(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("caption", attrs, content)

/** Emits an `<h4>`. */
@Composable
public fun H4(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("h4", attrs, content)

/** Emits an `<h5>`. */
@Composable
public fun H5(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("h5", attrs, content)

/** Emits an `<h6>`. */
@Composable
public fun H6(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("h6", attrs, content)

/** Emits an `<ol>`. */
@Composable
public fun Ol(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("ol", attrs, content)

/**
 * Emits a `<details>`, a disclosure that the browser opens and closes by itself.
 *
 * Toggling one sends nothing to the server. `<details>` keeps its own `open` state, and there's no
 * handler for the `toggle` event. That's the same trade-off as [AttrsScope.clientOnly], and it's
 * usually the right one. As a result, [AttrsScope.open] declares a state instead of reflecting it:
 * the next patch that changes `open` overwrites whatever the user did.
 */
@Composable
public fun Details(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("details", attrs, content)

/**
 * Emits a `<summary>`, the line of a [Details] that's always visible.
 *
 * It must be the first child of the [Details]. Otherwise, the browser adds a default one.
 */
@Composable
public fun Summary(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("summary", attrs, content)

/**
 * Emits a `<dialog>`. Showing it is up to you, and only one of the two ways works here.
 *
 * [AttrsScope.open] shows the dialog inline, so a composition can control it like any other
 * attribute. A modal dialog, with a top layer, a backdrop, trapped focus, and Escape to close,
 * needs the DOM method `showModal()`. Jetlin can't call DOM methods: ops set attributes,
 * properties, and listeners. An op that calls a method would let the server make the browser run
 * arbitrary instructions, which the protocol is designed to prevent. For a modal, use a
 * [ClientComponent] or your own script. This composable emits the element and nothing more, so
 * nothing pretends to be modal when it isn't.
 */
@Composable
public fun Dialog(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    Element("dialog", attrs, content)

/**
 * Emits a link that navigates within the live session instead of reloading the page.
 *
 * It renders as an ordinary `<a href>`, so it's a real link. Crawlers follow it, middle-click and
 * "Open in new tab" work, and with JavaScript turned off it falls back to a normal request, which
 * starts a new session on the same path. When the client is running, it intercepts the click and
 * the session moves to [href] without a page load.
 *
 * @param href the location to navigate to.
 * @param attrs declares the link's other attributes and handlers. An [AttrsScope.onClick] handler
 *   declared here runs before the navigation.
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
            // Add to the caller's handler instead of declaring one. Both the caller and Link
            // configure this element, and otherwise the caller's own onClick would be rejected for
            // colliding with a navigation handler it doesn't know about.
            alsoOn("click", ListenerSpec(preventDefault = true)) { navigator.push(href) }
        },
        content = content,
    )
}

/**
 * Emits an `<svg>` drawing. Every element composed inside it is an SVG element, not an HTML one.
 *
 * ```kotlin
 * Svg({ attr("viewBox", "0 0 100 40"); classes("spark") }) {
 *     SvgTitle { Text("Speed over the last hour") }
 *     Polyline({ attr("points", points); attr("fill", "none"); attr("stroke", "currentColor") })
 * }
 * ```
 *
 * The difference matters because a mistake is invisible. `createElement("circle")` produces an
 * `HTMLUnknownElement`, with no error and no warning, just an empty box where the chart should be.
 * So the language is a property of the tree instead of a guess based on the tag name, and it
 * travels to the browser with each node.
 *
 * The composables in this file cover what a chart needs. For anything else, use [Element] with the
 * tag. Inside this block, it gets the right language automatically. SVG tags and attributes are
 * case-sensitive, so write `Element("linearGradient")` and `attr("viewBox", …)`. The browser ignores
 * lowercase spellings without an error.
 *
 * Paint and geometry, such as `fill`, `stroke`, `d`, `cx`, and `r`, are presentation attributes, so
 * set them with [AttrsScope.attr], not [AttrsScope.prop]. `class` and `style` work as they do in
 * HTML.
 */
@Composable
public fun Svg(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}) {
    CompositionLocalProvider(LocalNamespace provides Namespace.SVG) {
        Element("svg", attrs, content)
    }
}

/**
 * Emits a `<foreignObject>`, which puts HTML back inside a drawing, such as wrapped text, a table,
 * or a form that the browser lays out.
 *
 * This is the one place where the language changes back to HTML, and it's why each node carries its
 * namespace instead of the rule being "everything under an `<svg>`." Set `x`, `y`, `width`, and
 * `height`. SVG doesn't lay out its children, so a foreign object without a size shows nothing.
 */
@Composable
public fun ForeignObject(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    SvgElement("foreignObject", attrs) {
        CompositionLocalProvider(LocalNamespace provides Namespace.HTML) { content() }
    }

/** Emits a `<g>`, which groups shapes under one transform, class, or accessible name. */
@Composable
public fun G(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    SvgElement("g", attrs, content)

/**
 * Emits a `<defs>`, for definitions such as gradients, markers, and clip paths. They draw nothing
 * until something refers to them.
 */
@Composable
public fun Defs(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    SvgElement("defs", attrs, content)

/** Emits a `<path>`. */
@Composable
public fun Path(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    SvgElement("path", attrs, content)

/** Emits a `<circle>`. */
@Composable
public fun Circle(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    SvgElement("circle", attrs, content)

/** Emits an `<ellipse>`. */
@Composable
public fun Ellipse(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    SvgElement("ellipse", attrs, content)

/** Emits a `<rect>`. */
@Composable
public fun Rect(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    SvgElement("rect", attrs, content)

/** Emits a `<line>`. */
@Composable
public fun Line(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    SvgElement("line", attrs, content)

/** Emits a `<polyline>`. */
@Composable
public fun Polyline(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    SvgElement("polyline", attrs, content)

/** Emits a `<polygon>`. */
@Composable
public fun Polygon(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    SvgElement("polygon", attrs, content)

/**
 * Emits an SVG `<text>`, which draws characters at a position.
 *
 * The name has a prefix because [Text] already emits a text node, which is what puts the characters
 * inside this element. The two appear together, as in `SvgText({ attr("x", "8") }) { Text("18 kn") }`,
 * so the prefix saves the reader from working out which one is meant. [SvgTitle] has the prefix for
 * the same reason, because `title` means something different in HTML.
 */
@Composable
public fun SvgText(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    SvgElement("text", attrs, content)

/** Emits a `<tspan>`, a run of text inside an [SvgText] that's positioned or styled separately. */
@Composable
public fun Tspan(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    SvgElement("tspan", attrs, content)

/**
 * Emits an SVG `<title>`: the accessible name of a drawing or a shape, and its tooltip.
 *
 * A chart is a picture, and this is the only part of it that a screen reader can use. Make it the
 * first child of an [Svg], or of a [G] that represents one series.
 */
@Composable
public fun SvgTitle(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    SvgElement("title", attrs, content)

/** Emits a `<linearGradient>`. Put [Stop] elements inside it, and define it inside [Defs]. */
@Composable
public fun LinearGradient(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    SvgElement("linearGradient", attrs, content)

/** Emits a `<stop>`, one color stop of a gradient. */
@Composable
public fun Stop(attrs: (AttrsScope.() -> Unit)? = null, content: @Composable () -> Unit = {}): Unit =
    SvgElement("stop", attrs, content)

/**
 * Emits an SVG element, and throws if it's outside a drawing.
 *
 * It reads the language instead of providing it. Providing it on every shape would add a
 * composition group and a provider map to each of the hundreds of nodes in a chart, only to state
 * what they inherit anyway. Reading it costs one comparison and catches the mistake the first time
 * the page renders. Nothing else would catch it, because a `<circle>` outside an `<svg>` is just an
 * empty space.
 */
@Composable
private fun SvgElement(
    tag: String,
    attrs: (AttrsScope.() -> Unit)?,
    content: @Composable () -> Unit = {},
) {
    check(LocalNamespace.current == Namespace.SVG) {
        "<$tag> is an SVG element and has to be composed inside Svg { }. Outside one the browser " +
            "makes an unknown HTML element of the same name, which draws nothing and reports no error."
    }
    Element(tag, attrs, content)
}
