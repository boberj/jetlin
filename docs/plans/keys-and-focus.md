# Keys and focus

This plan is a proposal. None of it is implemented. It adds three things to Jetlin: handling
individual keys, moving focus from the server, and keyboard shortcuts that work anywhere on the page.
Together they replace the hand-written `app.js` code that applications currently need for keyboard
interaction.

- Audience: Claude Code, working in the Jetlin repository.
- Background reading: `docs/architecture.md` §5 (protocol), §6 (browser runtime), §8 "Latency and
  typing", and §12 "Interactions that the browser handles". Then `AttrsScope` and
  `ClientCommandsScope` in `jetlin-html/src/main/kotlin/jetlin/html/Elements.kt`, `ListenerSpec`,
  `ClientCommand`, and `ClientTarget` in `jetlin-protocol/src/main/kotlin/jetlin/protocol/Ops.kt`,
  and `fire`, `runCommands`, and `resolveTarget` in `jetlin-client/src/jetlin.ts`.

## 1. The problem

Two real applications ran into the same wall, and both worked around it with JavaScript outside the
framework.

**A list of fields you type into one after another.** Pollster's poll form has a field per option.
Pressing Enter should move to the next option, and pressing it in the last option should add a new
one and move there. Pollster's `app.js` does it with a document-level `keydown` listener, a
`preventDefault`, a click on the "Add Option" button, and a `MutationObserver` that waits for the new
field to arrive so it can focus it.

**A keyboard-driven application.** `samples/issue-tracker` has page-wide shortcuts (`c` creates an
issue, `g` then `b` opens the board, `Cmd+K` opens the command palette, Escape dismisses a dialog),
and dialogs that focus their first field when they open. Its `app.js` is 161 lines. About a third
of that is keyboard and focus code: a global `keydown` listener that clicks hidden buttons, and a
`MutationObserver` that focuses anything marked `data-autofocus`.

Both workarounds exist because of three gaps.

### 1.1 A key listener can't tell keys apart

`onKeyDown` listens for every key. Its `ListenerSpec` can set `preventDefault`, but that applies to
every key, so an input that prevents Enter also prevents typing. And because the server only hears
about the key after the fact, it can't decide to prevent the default either. A browser decides what a
key does synchronously, while the event is being dispatched, long before any round trip could finish.

There's a cost even when nobody needs `preventDefault`: an `onKeyDown` handler that cares only about
Enter sends a message to the server for every keystroke, and ignores almost all of them.

### 1.2 The server can't move focus

Nothing in the protocol moves focus. `clientOnly { focus() }` exists, but it runs when an event fires
in the browser, and it can only target the element itself or an ancestor (`ClientTarget.Self` and
`ClientTarget.Closest`). It can't target an element that doesn't exist yet, such as the field a
handler just added, and it can't target a sibling, such as the next field in a list.

The HTML `autofocus` attribute only applies to the page as it was first loaded. An element that a
patch inserts later is ignored, which is why the issue tracker invented `data-autofocus`.

### 1.3 Shortcuts have nowhere to listen

A shortcut such as `c` applies when nothing in particular is focused. The event's target is then
`<body>`, which is outside the Jetlin container, so the container's delegated listeners never see
it. There's no element an application could attach `onKeyDown` to.

### 1.4 What isn't the problem

Focus that the user moves themselves already works. Patches set properties on existing nodes and
never replace a container's `innerHTML`, so focus, selection, and scroll position survive updates
(architecture.md §8). This plan doesn't change that, and must not break it.

## 2. Principles

These are the existing rules that the design has to respect. Each one rules out an easier design,
listed in §7.

1. **The server owns behavior. The browser runs only a fixed set of commands.** No arbitrary scripts
   in the protocol, and no general way to run code on a key. Every addition here is a declaration
   that the client interprets, like `clientOnly`'s commands.
2. **Anything decided while an event is dispatching has to be declared ahead of time.** That applies
   to `preventDefault` today, and to which keys a listener reacts to after this plan. The server can't
   be asked, because the browser won't wait.
3. **The user owns focus.** The server can ask for focus to move as a consequence of something the
   user did. It must never take focus away from where the user is now typing. This is the same rule
   as the stale-write guard for `value`, which drops a server value that's older than the user's
   latest keystroke.
4. **Headless tests can check it.** Every behavior needs an assertion in `jetlin-testing`, even when
   only a browser can perform it, as `assertClientCommands` does for `clientOnly`.

## 3. The design

### 3.1 Keys: a filter on the listener

`ListenerSpec` gets an optional list of keys:

```kotlin
public data class ListenerSpec(
    // ...existing fields...
    /**
     * The keys this listener reacts to, for keyboard events. When it's set, any other key is
     * ignored completely: no commands run, the default isn't prevented, and nothing is sent.
     */
    val keys: List<KeyMatch>? = null,
)

@Serializable
public data class KeyMatch(
    /** `KeyboardEvent.key`, such as `Enter`, `Escape`, `ArrowDown`, or `k`. */
    val key: String,
    /** Modifiers that must be held (`true`) or must not be held (`false`). `null` means either. */
    val shift: Boolean? = null,
    val ctrl: Boolean? = null,
    val alt: Boolean? = null,
    val meta: Boolean? = null,
)
```

The client checks the filter first in `fire`, before `preventDefault`, commands, debouncing, or
sending. A keystroke that doesn't match behaves exactly as if there were no listener. That solves
both halves of §1.1: preventing Enter no longer prevents typing, and typing no longer sends anything.

The client always ignores an event with `isComposing` set, whatever the filter says. A user writing
Japanese or Chinese presses Enter to confirm a candidate in their input method. That Enter belongs to
the input method, not to the page.

The API, on `AttrsScope`:

```kotlin
/**
 * Calls [handler] when one of [keys] is pressed. Other keys are ignored by the browser, and are
 * never sent.
 *
 * @param preventDefault whether to stop the browser's own action for these keys, such as Enter
 *   submitting a form. It applies only to [keys].
 */
public fun onKey(
    vararg keys: KeyMatch,
    preventDefault: Boolean = false,
    client: (ClientCommandsScope.() -> Unit)? = null,
    handler: (key: String) -> Unit,
)
```

Plus constants and a builder, so that the common cases read naturally:

```kotlin
Input({ onKey(Key.Enter, preventDefault = true) { save() } })
Input({ onKey(Key.Escape) { cancel() } })
Div({ onKey(Key.ArrowUp, Key.ArrowDown) { key -> move(if (key == "ArrowUp") -1 else 1) } })
Input({ onKey(Key.Enter.with(meta = true)) { send() } })
```

`Key.Enter` is a `KeyMatch` with all modifiers set to `null`. A plain `onKey(Key.Enter)` also fires
for Shift+Enter. That's the common intent, and a stricter match is one call away:
`Key.Enter.with(shift = false)`.

One listener per event still holds (`AttrsScope.on` throws on a second `keydown` handler). An
element that reacts to several keys declares them in one `onKey` and branches on `key`, as the third
example does. That keeps one entry per event in the client's listener table, which the existing
design depends on.

`onKeyDown` stays as it is, for the rare listener that really wants every key, such as a key
recorder. Its KDoc should point to `onKey` and say why: it sends a message for every keystroke.

The `client` parameter declares commands that run in the browser for the matched keys only, before
the event is sent. It's the same mechanism as `clientOnly`, filtered by the same keys. §3.3 is where
this pays off.

### 3.2 Flushing typed input before a key event

The field being typed in has a debounced `input` listener (`bind` debounces by 150 ms). A user who
types "Museum" and presses Enter within 150 ms sends the key event before the debounced input. The
server then runs the Enter handler before it knows the field's value.

Pollster ran into this with form submission and worked around it by reading the submitted form's
fields (`PollFormState.takeSubmitted`). A key handler has no such data to fall back on.

The fix belongs in the client: **before sending any event that isn't itself debounced, send every
pending debounced event first, in the order they were raised.** Debouncing exists to save round trips
while the user is typing, and the user pressing a key that does something is exactly when the typing
is over. This keeps events in causal order without each application having to think about it.

It's a behavior change for all events, not only keys, so it gets its own phase (§5, phase 1) and its
own browser test. The server-side `FORM` extract stays useful, but it stops being needed for
correctness.

### 3.3 Focus targets the browser can resolve by itself

`ClientTarget` gets two more variants:

```kotlin
/** The next element after this one, in document order, that has the class [className]. */
@SerialName("next") public data class Next(val className: String, val within: String? = null) : ClientTarget
/** The previous element before this one, in document order, that has the class [className]. */
@SerialName("prev") public data class Previous(val className: String, val within: String? = null) : ClientTarget
```

`within` limits the search to the nearest ancestor with that class, such as the list the field is
in, so "next option" can't escape into another form on the same page. The client resolves them with
`querySelectorAll` inside the scope and an index lookup. A target that doesn't exist resolves to
nothing, and the command is skipped, as `runCommands` already does for a missing `Closest`.

Moving to a field that already exists then needs no round trip at all:

```kotlin
Input({
    classes("option")
    onKey(Key.Enter, preventDefault = true, client = { focus(on = next("option", within = "options")) }) {
        if (row == state.rows.last()) state.addRow(focus = true)
    }
})
```

In every row except the last, the browser moves focus at once, and the server's handler does
nothing. In the last row there is no next option, so the browser does nothing, and the server adds a
row and asks for it to be focused. That uses §3.4.

### 3.4 Focus requested by the server

A handler asks for focus through a requester, modeled on Compose UI's `FocusRequester`, which Kotlin
developers already know:

```kotlin
val focus = remember { FocusRequester() }
Input({ focusRequester(focus) })
Button({ onClick { focus.requestFocus() } }) { Text("Edit") }
```

`requestFocus` doesn't touch the DOM. It marks the requester as pending, which invalidates the
composition. When `HtmlApplier` drains its op buffer at the end of a recomposition pass, it appends
an `Op.Focus(id)` for the element the requester is attached to:

```kotlin
/** Moves focus to the element [id], if the user hasn't moved on. Always the last op in a patch. */
@SerialName("focus") public data class Focus(val id: NodeId, val after: Long) : Op
```

The details that make it correct:

- **Requested before the element exists.** The common case: a handler adds a row and asks for its
  field to be focused, in the same call. The new field is created in the recomposition that follows,
  and it's attached to the requester during that same pass. The focus op is emitted at the end of the
  drain, after the `ins` op that created the element, so the element always exists when the client
  applies it. A request whose requester is still attached to nothing at the end of a pass stays
  pending until it's attached, or until the requester leaves the composition, where it's dropped. In
  development, `onError` gets a warning when a request is dropped this way, because it's nearly always
  a requester that was never attached.
- **The client applies it last.** The client applies every other op in the patch first, then
  focuses. Focusing mid-patch could scroll to an element whose siblings haven't arrived yet.
- **The user owns focus.** `after` is the sequence number of the event whose handler made the
  request. It's 0 for a request that no event caused, such as a timer. The client skips the focus if
  it has sent any event from a *different* node since `after`. In that case the user has already
  clicked or typed somewhere else, and pulling them back would be the bug that principle 3 forbids.
  This mirrors the existing stale-`value` guard, and reuses the sequence numbers that the client
  already tracks per node (`sentFrom`).
- **The first paint.** A request that's pending when the page is first rendered is written into the
  HTML as the `autofocus` attribute on that element, and not sent again in a patch. That's the one
  case `autofocus` handles correctly, and it works before the WebSocket connects.
- **Adoption and reset.** A `reset` rebuilds the DOM, which loses focus anyway. A request that was
  applied before a reset isn't replayed. Requests aren't saved when a session hibernates: they're
  consequences of an interaction, not state.

Pollster's case with this and §3.3 together:

```kotlin
class OptionRow(val key: Int, val existing: PollOption?, text: String) {
    var text by mutableStateOf(text)
    val focus = FocusRequester()
}

fun PollFormState.addRow(focus: Boolean = false) {
    val row = OptionRow(nextKey++, null, "")
    rows += row
    if (focus) row.focus.requestFocus()
}
```

The issue tracker's dialogs replace `data-autofocus` with a requester that the dialog requests when
it's first composed. That's a `LaunchedEffect(Unit) { focus.requestFocus() }`, or a small `autoFocus`
helper in `jetlin-html` that does the same.

### 3.5 Shortcuts anywhere on the page

Page-wide shortcuts need a listener that isn't on an element. A new composable registers them:

```kotlin
@Composable
public fun KeyShortcuts(content: KeyShortcutsScope.() -> Unit)

KeyShortcuts {
    shortcut(Key("k").with(meta = true), preventDefault = true) { palette.open() }
    shortcut(Key("c"), whileTyping = false) { createIssue() }
    shortcut(Key.Escape) { overlays.dismiss() }
}
```

It renders nothing visible. It emits an element (a `<jl-shortcuts hidden>`, so it can carry
listeners and an ID like any node) whose `keydown` listener spec has a new flag, `scope = Document`.
The client registers one document-level `keydown` listener the first time it sees such a spec. That
listener runs the shortcut only when no element listener inside the container already handled the
key, so a field's own `onKey(Key.Escape)` takes precedence over a page-wide Escape.

`whileTyping = false` skips the shortcut when the event's target is an input, a text area, a select,
or content-editable. A shortcut on a bare letter would otherwise fire while the user types that
letter. It defaults to `true` for keys that type nothing (Escape, arrows, anything with Ctrl, Alt, or
Meta) and to `false` for printable keys. That's the right default in nearly every case, and it's why
the issue tracker's `isTyping` check exists.

Several `KeyShortcuts` blocks can be composed at once, for example one in `app { }` and one in a
dialog. The client keeps them in composition order, and the most recently composed block wins for a
key that several declare. That's how an open dialog's Escape takes precedence over the page's.

Two-key sequences, such as the issue tracker's `g` then `b`, are out of scope for the first version.
They need timing state in the client. A follow-up can add `sequence("g", "b")` to the same scope, with
the timeout as a declared value (§8).

### 3.6 Testing without a browser

`jetlin-testing` gets:

- **`pressKey(key, shift, ctrl, alt, meta)`** respects the filter, and the scope of shortcuts. A key
  that the nearest listener doesn't match bubbles on, as the browser does. If nothing matches, it
  fails with the same "nothing listens" explanation that `click` gives, instead of silently doing
  nothing.
- **`assertKeyDefaultPrevented(key)`** on a node, which checks the declaration, like
  `assertClientCommands`.
- **`focusedNode`** on `ViewTest` and **`assertFocused()`** on a selection. The harness doesn't have
  a DOM, so it records the target of the last `Op.Focus` it applied, the way the client would,
  including the stale-focus rule. Client-side focus commands (§3.3) aren't performed, and pressing a
  key whose listener has them fails with an explanation, as clicking a client-only node does today.
  That keeps headless tests honest about what they can and can't see.
- **`pressShortcut(key)`** on `ViewTest`, for page-wide shortcuts, with an optional "while typing in"
  selection to test `whileTyping`.

## 4. Protocol and size

| Change | Where |
|---|---|
| `ListenerSpec.keys: List<KeyMatch>?` | `jetlin-protocol`, client `ListenerSpec`, `data-jl-on` in the first paint |
| `ListenerSpec.scope` (`Element` by default, or `Document`) | Same |
| `ClientTarget.Next` and `ClientTarget.Previous` | `jetlin-protocol`, client `resolveTarget` |
| `Op.Focus(id, after)` | `jetlin-protocol`, `HtmlApplier`, client patch application, SSR `autofocus` |

Every field is optional with an absent default, so a page that uses none of this sends exactly the
same bytes as today. The client grows by an estimated 0.5 kB minified. Measure it at each phase, and
record the number in architecture.md §6, which currently says 8.6 kB.

## 5. Phases

Each phase ends with `./gradlew build` and the browser suite passing, and `npm --prefix jetlin-client
run build` rerun so the checked-in `jetlin.js` matches the source. Framework tests assert exact op
streams, per CLAUDE.md.

### Phase 1: flush pending input before other events

Implement §3.2 in the client alone. It's independent of the rest, and it fixes a latent ordering bug
on its own.

- Acceptance: a browser test types into a bound field and immediately clicks a button whose handler
  reads the field. The handler sees the typed value. It fails on `main` today.

### Phase 2: key filters

Implement §3.1: `KeyMatch`, `ListenerSpec.keys`, `onKey`, `Key` constants, the client filter and the
`isComposing` rule, and `pressKey` and `assertKeyDefaultPrevented` in `jetlin-testing`.

- Acceptance: a unit test shows that a listener with a filter serializes the filter into `data-jl-on`
  and `Op.Listen`. A browser test shows that `onKey(Key.Enter, preventDefault = true)` in an input
  inside a form stops the submit, lets the user type normally, and sends exactly one event for
  "abc" followed by Enter. `samples/demo` gets an input that uses it.

### Phase 3: focus

Implement §3.3 and §3.4: `Next` and `Previous` targets, `FocusRequester`, `Op.Focus`, the
end-of-drain emission, the stale-focus rule, SSR `autofocus`, and `focusedNode` and `assertFocused`.

- Acceptance: exact-op-stream tests show that `Op.Focus` comes after the `ins` that creates its
  target when both happen in one handler, and that a request for an unattached requester produces no
  op. A browser test covers the list-of-fields case from §3.3 end to end, including focusing a field
  that a patch added. A second browser test shows that a focus request is dropped when the user
  clicked into another field while it was in flight.

### Phase 4: shortcuts

Implement §3.5, and `pressShortcut` in `jetlin-testing`.

- Acceptance: `samples/issue-tracker` drops the shortcut and autofocus parts of its `app.js`, and its
  application tests and browser tests still pass. Its drag-and-drop code stays, because that's out of
  scope.

### Phase 5: documentation

Update architecture.md §5 (the new op and spec fields), §6 (client size), §8 (a "Keys and focus"
section), §12 (testing), and §13 (remove "key-up" from the missing events only if phase 2 added it,
and note that navigation still doesn't move focus, see §8 below). Add a README example.

## 6. Applications as the test of the design

The design is done when both motivating applications lose their workarounds:

- **Pollster** deletes its `keydown` listener and `MutationObserver` from `app.js`, and the option rows
  use §3.3 and §3.4 as shown. Its clipboard code stays, because copying is a browser API the server
  has no business driving.
- **The issue tracker** deletes its shortcut and autofocus code (phase 4).

If either one still needs JavaScript for keys or focus afterwards, the design has a gap, and the gap
should be recorded in §9 instead of being patched with another escape hatch.

## 7. Alternatives considered

| Alternative | Why it was rejected |
|---|---|
| Let the server decide whether to prevent the default | Impossible. The browser decides synchronously during dispatch, and won't wait for a round trip. |
| Send every key and filter on the server | Costs a round trip per keystroke, which architecture.md §8 calls the most common way to make this architecture feel slow, and still can't prevent defaults. |
| A declarative `focused = true` attribute, kept in sync like `value` | Focus is owned by the user, not the composition. A declarative value would have to be "corrected" every time the user clicks somewhere, or it would fight them. A one-shot request with a staleness rule matches what applications mean. |
| Server-side focus through `ClientComponent` | Works, but it's an escape hatch for widgets the framework can't draw. Focus is basic enough to belong to the core. |
| Arbitrary key-handling scripts in the protocol | Rejected for the same reason `clientOnly` is a fixed set of commands (architecture.md §12): it would be a second application living in the browser. |
| A conditional client command ("focus next, or else tell the server") | Not needed. A command with a missing target is already skipped, and the server knows whether a row is the last, so §3.3's "both run" arrangement covers it without conditional logic in the client. |

## 8. Follow-ups this enables

- **Accessible navigation.** architecture.md §13 notes that a client-side route change doesn't move
  focus or announce anything. With `Op.Focus`, the router can focus the new view's main heading after
  navigation, which is what screen-reader users expect. This needs its own small design: which
  element, and how a view opts out.
- **Focus after validation.** A submit handler can focus the first invalid field. That's just
  `requestFocus`, but a `rememberField` helper could make it one line.
- **Key sequences**, such as `g` then `b` (§3.5).
- **`keyup`, focus, and blur events.** §13 lists them as missing. `onKey` makes the `keyup` variant a
  one-parameter change.

## 9. Open questions

- **Naming.** `onKey` next to `onKeyDown` may confuse. An alternative is to give `onKeyDown` a `keys`
  parameter and add no new name. `onKey` was chosen because it reads well in the common case, and
  because `onKeyDown`'s current behavior of sending every key should look like the special case.
- **`key` or `code`.** `KeyboardEvent.key` follows the keyboard layout, so `Key("z")` is the key
  labeled Z, wherever it is. `code` is the physical position, which games want. Shortcuts want `key`.
  Start with `key`, and add a `code` field to `KeyMatch` only if an application needs it.
- **Requests to focus elements inside a client component.** `Op.Focus` targets server-rendered
  nodes. Focusing inside a component is the component's business, through its props. Is that enough?
- **Whether the stale-focus rule should also cover pointer interaction.** Today it counts only events
  that were sent to the server. A click on an element with no listener sends nothing, so it wouldn't
  count. A client-side "last interaction" timestamp, compared with the request, would cover it, at the
  cost of another listener. Decide this during phase 3, with a browser test for each case.

## 10. Decision log

Record corrections to this plan here, with the date and the reason, instead of silently doing
something different.
