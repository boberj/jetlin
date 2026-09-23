package jetlin.testing

import jetlin.protocol.EventPayload

/*
 * Interactions with a view, as a browser would send them.
 *
 * There's no geometry and nothing to hit-test. An interaction names a node and an event, and the
 * server calls the lambda it holds for that pair. That's the whole input model, and it's why these
 * functions are called `click()` instead of simulating a pointer press at a coordinate.
 *
 * Each one returns once the recomposition it caused has been applied, so an assertion on the next
 * line sees the result, and no test needs to sleep. Each one throws an AssertionError if nothing
 * listens for the event.
 */

/** Clicks the node, as a user would. */
public suspend fun NodeSelection.click(): NodeSelection = apply {
    send("click", EventPayload())
}

/**
 * Replaces the contents of a text input with [text].
 *
 * It sends what a client sends once its debounce has elapsed: the field's new value, not a
 * keystroke. So this is one event, however long [text] is.
 */
public suspend fun NodeSelection.type(text: String): NodeSelection = apply {
    send("input", EventPayload(value = text))
}

/** Checks a checkbox, or clears it if [checked] is `false`. */
public suspend fun NodeSelection.check(checked: Boolean = true): NodeSelection = apply {
    send("change", EventPayload(checked = checked))
}

/**
 * Chooses an option in a `<select>` by the option's value.
 *
 * A browser reports the chosen value, not which option was clicked, so this function doesn't look
 * at the options. That also means nothing checks that [value] is one of them. A test that chooses
 * something the list no longer offers fails on its next assertion, not on this line. If the options
 * matter, assert on them too.
 */
public suspend fun NodeSelection.choose(value: String): NodeSelection = apply {
    send("change", EventPayload(value = value))
}

/** Submits a form with [fields], the values the browser would have collected, by name. */
public suspend fun NodeSelection.submit(fields: Map<String, String> = emptyMap()): NodeSelection = apply {
    send("submit", EventPayload(form = fields))
}

/** Presses a key, such as `pressKey("Enter")`. */
public suspend fun NodeSelection.pressKey(key: String): NodeSelection = apply {
    send("keydown", EventPayload(key = key))
}

/**
 * Sends [event] to the nearest element that listens for it, starting at the selected node and
 * working outward.
 *
 * This is bubbling, as a browser does it. A handler is often on a wrapper instead of on the element
 * with the text a test matched, and clicking `Button { Span { Text("Save") } }` should work
 * whichever of the two the query selected.
 *
 * It fails when nothing in the chain listens. A control whose handler was never connected is a real
 * defect, and an interaction that did nothing would let a test pass on a page that can't be used.
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
        // consumes it instead of letting it reach a handler further out. Failing here is better
        // than dispatching to nothing and leaving the test author to wonder why the page didn't
        // change.
        if (listening.listenerSpec(event)?.notify == false) {
            throw AssertionError(
                "The listener for '$event' on the node matching $describedBy is client-only: it " +
                    "declares commands and no handler, so there is nothing here to dispatch to. Its " +
                    "effect happens in the browser. Pin the declaration with assertClientCommands, " +
                    "and cover the behavior with a browser test.\n\nThe node was:\n" +
                    listening.describe(),
            )
        }
        listening
    }
    test.dispatchEvent(target.id, event, payload)
}
