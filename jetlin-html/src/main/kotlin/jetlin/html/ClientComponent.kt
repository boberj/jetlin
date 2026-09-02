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

/** Names the registered implementation that should render into an element. */
internal const val COMPONENT_ATTRIBUTE: String = "data-jl-component"

/** Carries the props for that implementation, as JSON. */
internal const val COMPONENT_PROPS_ATTRIBUTE: String = "data-jl-props"

private val EMPTY_PROPS = JsonObject(emptyMap())

/**
 * An element rendered by JavaScript rather than by the composition.
 *
 * For the things a server-side tree cannot sensibly produce: a map, a chart, a rich-text editor, a
 * date picker — anything with its own rendering and its own lifecycle. The composition creates the
 * element and stops there; what goes inside belongs to an implementation the application registered
 * in its own bundle:
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
 * **Props go down, events come up, and the DOM in between is disposable.** Nothing is preserved
 * across a reconnect that had to resend the tree: the element is rebuilt and the implementation
 * mounted afresh, with props derived from state the server still holds. That is the same bargain
 * `remember` makes, and it is why this needs no machinery for keeping a subtree alive through a
 * rebuild.
 *
 * The rule that makes it safe: **nothing the user authored may live only in here.** A map's pan and
 * zoom can be recreated and nobody minds. Text somebody typed cannot, so it is pushed up and held in
 * a `rememberSaved` field — which then survives a reconnect, and hibernation too, through machinery
 * that already exists.
 *
 * [name] is looked up in a registry the application populated; it is never evaluated as code. An
 * unregistered name leaves the element empty and warns, rather than failing the page.
 *
 * There are no composable children: the element's contents belong to the implementation, and Jetlin
 * neither indexes them nor patches them. That also means nothing renders here with JavaScript
 * disabled, so anything that has to work without it does not belong in one.
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
    val scope = AttrsScope(exposeTestTags = LocalTestTagsExposed.current)
    attrs?.invoke(scope)
    scope.attr(COMPONENT_ATTRIBUTE, name)
    scope.attr(COMPONENT_PROPS_ATTRIBUTE, JetlinJson.encodeToString(JsonObject.serializer(), props))
    // Registered as a handler with no listener spec: pushes are made by the implementation calling
    // back into the runtime, not by a DOM event, so there is nothing for the browser to listen for.
    scope.handle(COMPONENT_EVENT) { payload ->
        val data = payload.data ?: return@handle
        val event = data[COMPONENT_EVENT_NAME]?.jsonPrimitive?.content ?: return@handle
        onEvent(event, data[COMPONENT_EVENT_PAYLOAD]?.jsonObject ?: EMPTY_PROPS)
    }

    val data = scope.data()
    val handlers = scope.handlers()

    ComposeNode<ElementNode, HtmlApplier>(
        factory = { owner.createElement(tag) },
        update = {
            set(data) { applyData(it) }
            set(handlers) { this.handlers = it }
        },
    )
}
