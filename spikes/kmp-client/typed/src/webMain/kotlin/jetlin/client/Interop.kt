package jetlin.client

import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.events.Event

// Everything here is an escape hatch from Kotlin's typed DOM into plain JavaScript.
//
// The same helpers compile for both Kotlin/JS and Kotlin/Wasm because `js("...")` is allowed as the
// whole body of a top-level function in shared web code. Each one exists for one of three reasons,
// noted on it:
//  - (missing)  the DOM declarations in kotlinx-browser predate the API, e.g. `replaceChildren`.
//  - (opaque)   the value is JSON Jetlin never interprets, such as client component props, so
//               decoding it into Kotlin types would be wasted work and wasted code.
//  - (duck)     TypeScript reads `.value` from whatever the target is. In Kotlin, a cast to an
//               external class is an `instanceof` check, so `target as HTMLInputElement` would
//               throw for a `<textarea>` or `<select>` that has the same property.
//
// The code in `js("...")` is parsed by the Kotlin compiler's own JavaScript parser, which rejects
// ES2020 syntax such as `?.` and `??`. That's why these helpers spell out their null checks.

/** (opaque) Parses a client component's props, which go to the component untouched. */
internal fun jsonParse(text: String): JsAny = js("JSON.parse(text)")

/** (opaque) Serializes a client component's pushed payload, so kotlinx.serialization can re-read it. */
internal fun jsonStringify(value: JsAny?): String = js("JSON.stringify(value == null ? {} : value)")

/** (missing) `CSS.escape`, for building a class selector from a server-provided class name. */
internal fun cssEscape(value: String): String = js("CSS.escape(value)")

/** (missing) `Element.replaceChildren()` with no arguments, which empties the element. */
internal fun replaceChildren(element: Element): Unit = js("element.replaceChildren()")

/** (duck) The target's `value`, or `""`, for whichever control fired the event. */
internal fun targetValue(event: Event): String = js("event.target && event.target.value != null ? String(event.target.value) : ''")

/** (duck) The target's `checked`, or `false`. */
internal fun targetChecked(event: Event): Boolean = js("!!(event.target && event.target.checked)")

/** (duck) The event's `key`, or `null` when it isn't a keyboard event. */
internal fun eventKey(event: Event): String? = js("event.key == null ? null : event.key")

/** (missing) A form's fields, as a JSON object of strings. `FormData` has no iterator in Kotlin. */
internal fun formJson(form: HTMLFormElement): String =
    js("JSON.stringify(Object.fromEntries(new FormData(form)))")

/** Sets a DOM property by name, such as `value` or `checked`. Kotlin has no dynamic property write. */
internal fun setProperty(target: Element, name: String, value: JsAny?): Unit = js("target[name] = value")

/** The `detail` of the `jetlin:error` event. */
internal fun errorDetail(message: String, fatal: Boolean): JsAny = js("({ message: message, fatal: fatal })")

/** The state object Jetlin pushes onto the history stack. */
internal fun historyState(): JsAny = js("({ jetlin: true })")

internal fun now(): Double = js("Date.now()")

internal fun consoleWarn(message: String): Unit = js("console.warn('[jetlin] ' + message)")

internal fun consoleError(message: String): Unit = js("console.error('[jetlin]', message)")

// ------------------------------------------------------------------ the page-facing API

/** The options object the page's inline script passes to `Jetlin.connect`. */
internal external interface JetlinOptions : JsAny {
    val url: String?
    val token: String
    val container: HTMLElement?
    // No `adopt: Boolean?` here, on purpose. When the page leaves `adopt` out, Kotlin/JS reads the
    // missing property as `null`, but Kotlin/Wasm reads it as `false`. The same shared source then
    // adopts on one target and silently skips adoption on the other. See `adoptRequested`.
}

/**
 * Whether the page asked for adoption, which is the default: only an explicit `adopt: false` turns
 * it off. Checked in JS because a missing Boolean property converts differently on JS and Wasm.
 */
internal fun adoptRequested(options: JetlinOptions): Boolean = js("options.adopt !== false")

/**
 * A client component implementation the application registered: a plain JS object with `mount`
 * and optional `update` and `unmount` methods.
 *
 * The methods are called through helpers rather than declared here, because two of them are
 * optional, and an external interface can't express "this method might not exist".
 */
internal external interface ComponentFactory : JsAny

internal fun factoryMount(
    factory: ComponentFactory,
    element: HTMLElement,
    props: JsAny,
    push: (String, JsAny?) -> Unit,
): JsAny? = js("factory.mount(element, props, push)")

internal fun factoryHasUpdate(factory: ComponentFactory): Boolean = js("typeof factory.update === 'function'")

internal fun factoryUpdate(factory: ComponentFactory, element: HTMLElement, props: JsAny, handle: JsAny?): Unit =
    js("factory.update(element, props, handle)")

internal fun factoryUnmount(factory: ComponentFactory, element: HTMLElement, handle: JsAny?): Unit =
    js("{ if (typeof factory.unmount === 'function') factory.unmount(element, handle); }")

/** The object `Jetlin.connect` returns. The e2e tests and the demo call these two methods. */
internal fun connectionHandle(disconnect: () -> Unit, reload: () -> Unit): JsAny =
    js("({ disconnect: disconnect, reload: reload })")

/**
 * Installs `window.Jetlin`.
 *
 * Kotlin's own export mechanisms don't fit: `@JsExport` exports from a module, but the page loads
 * a classic script and calls a global, and in Wasm, exports live on the instance, not the window.
 */
internal fun installGlobal(
    connect: (JetlinOptions) -> JsAny,
    clientComponent: (String, ComponentFactory) -> Unit,
): Unit = js("{ window.Jetlin = { connect: connect, clientComponent: clientComponent }; }")
