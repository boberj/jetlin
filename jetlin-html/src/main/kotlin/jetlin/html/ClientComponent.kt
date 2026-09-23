package jetlin.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import jetlin.protocol.COMPONENT_EVENT
import jetlin.protocol.COMPONENT_EVENT_NAME
import jetlin.protocol.COMPONENT_EVENT_PAYLOAD
import jetlin.protocol.JetlinJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The attribute that names the registered implementation that renders into an element. */
internal const val COMPONENT_ATTRIBUTE: String = "data-jl-component"

/** The attribute that holds the implementation's props, as JSON. */
internal const val COMPONENT_PROPS_ATTRIBUTE: String = "data-jl-props"

/** Props for a component that has none. */
private val EMPTY_PROPS = JsonObject(emptyMap())

/**
 * Emits an element that JavaScript renders, instead of the composition.
 *
 * Use it for things a server-side tree can't reasonably produce, such as a map, a chart, a rich-text
 * editor, or a date picker: anything with its own rendering and its own lifecycle. The composition
 * creates the element and stops there. What goes inside belongs to an implementation that the
 * application registered in its own JavaScript bundle:
 *
 * ```kotlin
 * val body = rememberSavedField(note.body, key = "body")
 *
 * ClientComponent(
 *     name = "editor",
 *     props = buildJsonObject { put("content", body.value) },
 *     onEvent = { event, payload ->
 *         if (event == "changed") body.edit(payload["html"]!!.jsonPrimitive.content)
 *     },
 * )
 * ```
 *
 * ```js
 * Jetlin.clientComponent("editor", {
 *   mount(element, props, push) { ... return handle },
 *   update(element, props, handle) { ... },
 *   unmount(element, handle) { ... },
 * });
 * ```
 *
 * Props go down, events come up, and the DOM in between is disposable. When a reconnect has to
 * resend the tree, nothing inside the element is kept: the element is rebuilt, and the
 * implementation is mounted again with props computed from state the server still holds. That's the
 * same trade-off `remember` makes, and it's why no machinery is needed to keep a subtree alive
 * through a rebuild.
 *
 * One rule makes this safe: don't keep anything the user created only inside the component. A
 * map's pan and zoom can be recreated, and nobody minds. Text someone typed can't, so send it to the
 * server and keep it in a `rememberSaved` field. The existing machinery then keeps it through a
 * reconnect, and through hibernation too.
 *
 * The element has no composable children. Its contents belong to the implementation, and Jetlin
 * neither indexes nor patches them. That also means nothing renders here when JavaScript is turned
 * off, so anything that must work without JavaScript doesn't belong in a client component.
 *
 * @param name the name the implementation was registered under. The client looks it up in the
 *   application's registry and never evaluates it as code. An unregistered name leaves the element
 *   empty and logs a warning, instead of failing the page.
 * @param props the data the implementation renders. When it changes, the implementation's
 *   `update` runs.
 * @param tag the element's tag.
 * @param attrs declares the element's other attributes.
 * @param onEvent handles an event that the implementation sent, with its name and payload.
 */
@Composable
public fun ClientComponent(
    name: String,
    props: JsonObject = EMPTY_PROPS,
    tag: String = "div",
    attrs: (AttrsScope.() -> Unit)? = null,
    onEvent: (event: String, payload: JsonObject) -> Unit = { _, _ -> },
) {
    val owner = LocalHtmlOwner.current
    // Follow the same rule as any other element. A component inside Svg { } that asks for a <g>
    // gets an SVG one, because an HTML <g> would render as nothing, without an error.
    val namespace = LocalNamespace.current
    val scope = AttrsScope(tag, exposeTestTags = LocalTestTagsExposed.current)
    attrs?.invoke(scope)
    scope.attr(COMPONENT_ATTRIBUTE, name)
    scope.attr(COMPONENT_PROPS_ATTRIBUTE, JetlinJson.encodeToString(JsonObject.serializer(), props))
    // Register a handler without a listener spec. The implementation sends events by calling the
    // runtime, not through a DOM event, so there's nothing for the browser to listen for.
    scope.handle(COMPONENT_EVENT) { payload ->
        val data = payload.data ?: return@handle
        val event = data[COMPONENT_EVENT_NAME]?.jsonPrimitive?.content ?: return@handle
        onEvent(event, data[COMPONENT_EVENT_PAYLOAD]?.jsonObject ?: EMPTY_PROPS)
    }

    val data = scope.data()
    val handlers = scope.handlers()

    ComposeNode<ElementNode, HtmlApplier>(
        factory = { owner.createElement(tag, namespace) },
        update = {
            set(data) { applyData(it) }
            set(handlers) { this.handlers = it }
        },
    )
}
