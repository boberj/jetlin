package jetlin.testing

import jetlin.protocol.EventPayload

/**
 * Doing things to a view, as a browser would.
 *
 * There is no geometry here and nothing to hit-test: an interaction names a node and an event, and
 * the server calls the lambda it is holding for that pair. That is the whole input model, and it is
 * why these read as `click()` rather than as a pointer press at a coordinate.
 *
 * Each of these returns once the recomposition it caused has been applied, so an assertion on the
 * next line sees the result and no test needs to sleep.
 */

/** Clicks, as a user would. */
public suspend fun NodeSelection.click(): NodeSelection = apply {
    send("click", EventPayload())
}

/**
 * Replaces the contents of a text input.
 *
 * Sends what a client sends after its debounce has elapsed — the field's new value, not a
 * keystroke — so this is one event however long [text] is.
 */
public suspend fun NodeSelection.type(text: String): NodeSelection = apply {
    send("input", EventPayload(value = text))
}

/** Ticks or unticks a checkbox. */
public suspend fun NodeSelection.check(checked: Boolean = true): NodeSelection = apply {
    send("change", EventPayload(checked = checked))
}

/**
 * Picks an option in a `<select>`, naming it by the value the option carries.
 *
 * A browser reports the value that was chosen rather than which option was clicked, so there is
 * nothing to resolve against the options here — and equally nothing checking that the value named is
 * one of them. A test picking something the list no longer offers fails on its next assertion rather
 * than on this line, so assert on the options too where they are the point.
 */
public suspend fun NodeSelection.choose(value: String): NodeSelection = apply {
    send("change", EventPayload(value = value))
}

/** Submits a form, carrying the field values the browser would have collected. */
public suspend fun NodeSelection.submit(fields: Map<String, String> = emptyMap()): NodeSelection = apply {
    send("submit", EventPayload(form = fields))
}

/** Presses a key, e.g. `pressKey("Enter")`. */
public suspend fun NodeSelection.pressKey(key: String): NodeSelection = apply {
    send("keydown", EventPayload(key = key))
}

/**
 * Sends [event] to the nearest element that is listening for it, starting at the selected node and
 * working outwards.
 *
 * Bubbling, as a browser does it: a handler is often on a wrapper rather than on the element
 * holding the text a test matched, and clicking `Button { Span { Text("Save") } }` should work
 * whichever of the two the query picked out.
 *
 * Fails when nothing in the chain is listening. A control whose handler was never wired up is a
 * real defect, and an interaction that quietly did nothing would let a test pass while asserting on
 * a page that cannot be used.
 */
private suspend fun NodeSelection.send(event: String, payload: EventPayload) {
    val target = withPath { path ->
        val listening = path.lastOrNull { event in it.eventNames }
            ?: throw AssertionError(
                "Nothing listens for '$event' on the node matching $describedBy, " +
                    "or on anything containing it. It has " +
                    path.last().eventNames.let { if (it.isEmpty()) "no listeners" else "listeners for $it" } +
                    ".\n\nThe node was:\n" + path.last().describe(),
            )

        // The browser stops at the first element listening for an event, so a client-only listener
        // consumes it rather than letting it reach a handler further out. Saying so beats dispatching
        // into nothing and leaving the test to wonder why the page did not change.
        if (listening.listenerSpec(event)?.notify == false) {
            throw AssertionError(
                "The listener for '$event' on the node matching $describedBy is client-only: it " +
                    "declares commands and no handler, so there is nothing here to dispatch to. Its " +
                    "effect happens in the browser. Pin the declaration with assertClientCommands, " +
                    "and cover the behaviour with a browser test.\n\nThe node was:\n" +
                    listening.describe(),
            )
        }
        listening
    }
    test.dispatchEvent(target.id, event, payload)
}
