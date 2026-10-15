@file:Suppress("UnsafeCastFromDynamic", "NOTHING_TO_INLINE")

package jetlin.client

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.CustomEvent
import org.w3c.dom.CustomEventInit
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.Node
import org.w3c.dom.Text
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event

// A Kotlin/JS-only transliteration of jetlin-client/src/jetlin.ts, written to find the smallest
// Kotlin output, not the nicest Kotlin. It keeps the TypeScript design wholesale:
//
//  - The wire types are `external interface`s over whatever `JSON.parse` returns, like the TS
//    type declarations. Nothing checks them at runtime, and nothing ties them to jetlin-protocol.
//  - It uses JS's own `Map`, `WeakMap`, `Set` and arrays instead of Kotlin collections, because
//    Kotlin's `HashMap` and `ArrayList` are implemented in the stdlib and get bundled.
//
// The comments in jetlin.ts explain why the runtime behaves as it does, and they apply here.

// ------------------------------------------------------------------ wire types

/** `JSON.parse` output is never a Kotlin class, so each variant is read through its own view. */
external interface Tagged {
    val t: String
}

external interface PropValue : Tagged {
    val v: Any
}

external interface ClientTarget : Tagged {
    val className: String
}

external interface ClientCommand : Tagged {
    val name: String
    val target: ClientTarget?
}

external interface ListenerSpec {
    val extract: Array<String>?
    val commands: Array<ClientCommand>?
    val notify: Boolean?
    val debounceMs: Int?
    val throttleMs: Int?
    val preventDefault: Boolean?
    val stopPropagation: Boolean?
}

/** Both node kinds in one view, because Kotlin has no structural union to narrow on `t`. */
external interface NodeSpec : Tagged {
    val id: Int
    val tag: String
    val ns: String?
    val attrs: dynamic
    val props: dynamic
    val listeners: dynamic
    val children: Array<NodeSpec>?
    val text: String
}

/** Every op's fields in one view, for the same reason. Which fields exist depends on `t`. */
external interface Op : Tagged {
    val parent: Int
    val index: Int
    val count: Int
    val from: Int
    val to: Int
    val id: Int
    val name: String
    val value: dynamic
    val text: String
    val event: String
    val spec: ListenerSpec
    val node: NodeSpec
}

external interface ServerMessage : Tagged {
    val rev: Double
    val ack: Double
    val ops: Array<Op>
    val children: Array<NodeSpec>
    val url: String
    val replace: Boolean?
    val title: String?
    val post: dynamic
    val message: String
    val fatal: Boolean?
}

// ------------------------------------------------------------------ JS built-ins

@JsName("Map")
external class JsMap<K, V> {
    fun get(key: K): V?
    fun set(key: K, value: V)
    fun has(key: K): Boolean
    fun delete(key: K): Boolean
    fun clear()
    fun keys(): dynamic
}

@JsName("WeakMap")
external class JsWeakMap<K, V> {
    fun get(key: K): V?
    fun set(key: K, value: V)
}

@JsName("Set")
external class JsSet<T> {
    fun has(value: T): Boolean
    fun add(value: T)
}

external object JSON {
    fun parse(text: String): dynamic
    fun stringify(value: Any?): String
}

external val console: dynamic
external val CSS: dynamic
external val Object: dynamic
external val Array: dynamic
external val Date: dynamic
external fun setTimeout(handler: () -> Unit, timeout: Int): Int
external fun clearTimeout(handle: Int)
external fun parseInt(text: String): Int

/**
 * Throws a plain JS `Error`.
 *
 * Kotlin's own `throw Error(...)` would bundle Kotlin's exception class hierarchy, which is several
 * kilobytes this file otherwise doesn't need.
 */
private fun fail(message: String): Nothing = throw js("new Error(message)")

private inline fun <T> arrayFrom(iterable: dynamic): Array<T> = Array.from(iterable)

private inline fun keysOf(obj: dynamic): Array<String> = Object.keys(obj)

// ------------------------------------------------------------------ the runtime

private const val ROOT_ID = 0
private const val EMPTY_TEXT_MARKER = "0"
private const val COMPONENT_EVENT = "jl:component"
private const val COMPONENT_ATTRIBUTE = "data-jl-component"
private const val COMPONENT_PROPS_ATTRIBUTE = "data-jl-props"
private const val MAX_QUEUED_EVENTS = 64

private val components = JsMap<String, dynamic>()

private class Mounted(val factory: dynamic, val element: HTMLElement, val handle: dynamic)

private fun parseProps(element: Element): dynamic {
    val raw = element.getAttribute(COMPONENT_PROPS_ATTRIBUTE)
    if (raw.isNullOrEmpty()) return js("({})")
    return try {
        JSON.parse(raw)
    } catch (_: dynamic) {
        console.warn("jetlin: could not parse props for component \"${element.getAttribute(COMPONENT_ATTRIBUTE)}\"")
        js("({})")
    }
}

private class Jetlin(options: dynamic) {
    private val container: HTMLElement = options.container ?: document.getElementById("jetlin-root")
    private val token: String = options.token
    private val url: String = options.url ?: run {
        val scheme = if (window.location.protocol == "https:") "wss:" else "ws:"
        "$scheme//${window.location.host}/jetlin"
    }

    private var socket: WebSocket? = null
    private var reconnectAttempt = 0
    private var pendingAdopt = false
    private var fatal = false

    private val nodes = JsMap<Int, Node>()
    private var ids = JsWeakMap<Node, Int>()
    private val children = JsMap<Int, Array<Node>>()
    private val listeners = JsMap<Int, dynamic>()
    private val mounted = JsMap<Int, Mounted>()
    private val delegated = JsSet<String>()

    private var seq = 0
    private val sentFrom = JsMap<Int, Int>()
    private var outbox = arrayOf<String>()
    private val timers = JsMap<String, Int>()
    private val lastFired = JsMap<String, Double>()

    init {
        register(ROOT_ID, container)
        children.set(ROOT_ID, arrayOf())
        window.addEventListener("popstate", {
            // Every call on a `dynamic` receiver is a JS call, even `.also { }`, so build it step by step.
            val nav: dynamic = js("({ t: 'nav' })")
            nav.url = window.location.pathname + window.location.search
            sendRaw(JSON.stringify(nav))
        })
        pendingAdopt = options.adopt != false && adoptMarkup()
        open()
    }

    fun disconnect() {
        socket?.close()
    }

    fun reload() {
        window.location.reload()
    }

    private fun open() {
        val socket = WebSocket(url)
        this.socket = socket
        socket.onopen = {
            reconnectAttempt = 0
            document.body!!.classList.remove("jl-disconnected")
            val hello: dynamic = js("({ t: 'hello' })")
            hello.token = token
            hello.url = window.location.pathname + window.location.search
            hello.adopt = pendingAdopt
            socket.send(JSON.stringify(hello))
            pendingAdopt = false
            val pending = outbox
            outbox = arrayOf()
            for (frame in pending) socket.send(frame)
        }
        socket.onmessage = { event -> receive(JSON.parse(event.data.unsafeCast<String>())) }
        socket.onclose = {
            document.body!!.classList.add("jl-disconnected")
            scheduleReconnect()
        }
        socket.onerror = { socket.close() }
    }

    private fun reportError(message: String, fatal: Boolean) {
        val detail: dynamic = js("({})")
        detail.message = message
        detail.fatal = fatal
        val handled = !window.dispatchEvent(CustomEvent("jetlin:error", CustomEventInit(detail = detail, cancelable = true)))
        if (fatal) {
            this.fatal = true
            socket?.close()
            if (handled) {
                document.body!!.classList.add("jl-dead")
                return
            }
            reload()
            return
        }
        if (!handled) console.error("[jetlin]", message)
    }

    private fun scheduleReconnect() {
        if (fatal) return
        val delay = minOf(1000 shl minOf(reconnectAttempt, 10), 10000)
        reconnectAttempt += 1
        setTimeout({ open() }, delay)
    }

    private fun receive(message: ServerMessage) {
        when (message.t) {
            "reset" -> reset(message.children)
            "patch" -> for (op in message.ops) apply(op, message.ack)
            "nav" -> {
                val state: dynamic = js("({ jetlin: true })")
                if (message.replace == true) window.history.replaceState(state, "", message.url)
                else window.history.pushState(state, "", message.url)
                message.title?.let { document.title = it }
            }
            "load" -> load(message.url, message.post)
            "error" -> reportError(message.message, message.fatal == true)
        }
    }

    private fun load(url: String, post: dynamic) {
        fatal = true
        socket?.close()
        if (post == null) {
            window.location.assign(url)
            return
        }
        val form = document.createElement("form").unsafeCast<HTMLFormElement>()
        form.method = "post"
        form.action = url
        form.style.display = "none"
        for (name in keysOf(post)) {
            val input = document.createElement("input").unsafeCast<HTMLInputElement>()
            input.type = "hidden"
            input.name = name
            input.value = post[name]
            form.appendChild(input)
        }
        document.body!!.appendChild(form)
        form.submit()
    }

    private fun adoptMarkup(): Boolean =
        try {
            adoptElement(container, ROOT_ID)
            true
        } catch (error: dynamic) {
            console.warn("[jetlin] could not adopt the server-rendered markup; asking for a full render", error)
            nodes.clear()
            ids = JsWeakMap()
            children.clear()
            listeners.clear()
            register(ROOT_ID, container)
            children.set(ROOT_ID, arrayOf())
            false
        }

    private fun adoptElement(element: Element, id: Int) {
        register(id, element)

        val specs = element.getAttribute("data-jl-on")
        if (specs != null) {
            val parsed = JSON.parse(specs)
            listeners.set(id, parsed)
            for (event in keysOf(parsed)) delegate(event)
        }

        if (element.hasAttribute("data-jl-raw")) {
            children.set(id, arrayOf())
            return
        }
        if (element.hasAttribute(COMPONENT_ATTRIBUTE)) {
            mount(id, element.unsafeCast<HTMLElement>())
            return
        }

        val textIds = JsMap<Int, Int>()
        // `String.split` and `toInt` would bundle Kotlin's ArrayList, Regex-free splitting code and
        // number parsing, so this calls the JS methods instead.
        val markers = element.getAttribute("data-jl-t")
        if (markers != null && markers != "") {
            val entries: Array<String> = markers.asDynamic().split(",")
            for (entry in entries) {
                val pair: Array<String> = entry.asDynamic().split(":")
                textIds.set(parseInt(pair[0]), parseInt(pair[1]))
            }
        }
        val logical = arrayOf<Node>()

        for (node in arrayFrom<Node>(element.childNodes)) {
            // Literal node types, because `Node.COMMENT_NODE` goes through a Kotlin companion object.
            if (node.nodeType == 8.toShort()) {
                if (node.asDynamic().data == EMPTY_TEXT_MARKER) {
                    val empty = document.createTextNode("")
                    element.replaceChild(empty, node)
                    logical.asDynamic().push(empty)
                }
                continue
            }
            logical.asDynamic().push(node)
        }

        var textCount = 0
        for (index in 0 until logical.size) {
            val child = logical[index]
            when (child.nodeType) {
                1.toShort() -> {
                    val childElement = child.unsafeCast<Element>()
                    val childId = childElement.getAttribute("data-jl")
                        ?: fail("<${childElement.tagName}> under node $id has no data-jl")
                    adoptElement(childElement, parseInt(childId))
                }
                3.toShort() -> {
                    val textId = textIds.get(index) ?: fail("node $id has unexpected text at index $index")
                    register(textId, child)
                    textCount += 1
                }
                else -> fail("node $id has an unexpected child of type ${child.nodeType}")
            }
        }

        val declared: Int = textIds.asDynamic().size
        if (textCount != declared) fail("node $id declares $declared text children but $textCount are present")
        children.set(id, logical)
    }

    private fun reset(specs: Array<NodeSpec>) {
        for (id in arrayFrom<Int>(mounted.keys())) unmount(id)
        container.asDynamic().replaceChildren()
        nodes.clear()
        children.clear()
        listeners.clear()
        register(ROOT_ID, container)
        val built: Array<Node> = specs.asDynamic().map { spec: NodeSpec -> build(spec) }
        children.set(ROOT_ID, built)
        for (node in built) container.appendChild(node)
    }

    private fun apply(op: Op, ack: Double) {
        when (op.t) {
            "ins" -> {
                val parent = nodes.get(op.parent)!!
                val siblings = children.get(op.parent)!!
                val node = build(op.node)
                val before: Node? = siblings.asDynamic()[op.index]
                siblings.asDynamic().splice(op.index, 0, node)
                parent.insertBefore(node, before)
            }
            "rm" -> {
                val removed: Array<Node> = children.get(op.parent)!!.asDynamic().splice(op.index, op.count)
                for (node in removed) {
                    forget(node)
                    node.asDynamic().remove()
                }
            }
            "mv" -> {
                val parent = nodes.get(op.parent)!!
                val siblings = children.get(op.parent)!!.asDynamic()
                val dest = if (op.from > op.to) op.to else op.to - op.count
                val moved: Array<Node> = siblings.splice(op.from, op.count)
                siblings.splice.apply(siblings, arrayOf<Any>(dest, 0).asDynamic().concat(moved))
                val before: Node? = siblings[dest + op.count]
                for (node in moved) parent.insertBefore(node, before)
            }
            "attr" -> {
                val element = nodes.get(op.id).unsafeCast<Element>()
                if (op.value == null) element.removeAttribute(op.name) else element.setAttribute(op.name, op.value)
                if (op.name == COMPONENT_PROPS_ATTRIBUTE) updateComponent(op.id)
            }
            "prop" -> {
                val pending = sentFrom.get(op.id)
                val isUserState = op.name == "value" || op.name == "checked"
                if (isUserState && pending != null && pending > ack) return
                nodes.get(op.id).asDynamic()[op.name] = op.value.v
            }
            "text" -> nodes.get(op.id).unsafeCast<Text>().data = op.text
            "on" -> {
                val specs = listeners.get(op.id) ?: js("({})")
                specs[op.event] = op.spec
                listeners.set(op.id, specs)
                delegate(op.event)
            }
            "off" -> {
                val specs = listeners.get(op.id)
                if (specs != null) js("delete specs[op.event]")
            }
        }
    }

    private fun build(spec: NodeSpec): Node {
        if (spec.t == "t") {
            val text = document.createTextNode(spec.text)
            register(spec.id, text)
            return text
        }

        val element = if (spec.ns == "svg") {
            document.createElementNS("http://www.w3.org/2000/svg", spec.tag)
        } else {
            document.createElement(spec.tag)
        }
        register(spec.id, element)
        element.setAttribute("data-jl", spec.id.toString())

        // Not `spec.attrs?.let { }`: on a `dynamic` receiver, even `let` compiles to a JS method call,
        // and the object has no method by that name. The compiler can't catch it.
        val attrs = spec.attrs
        if (attrs != null) for (name in keysOf(attrs)) element.setAttribute(name, attrs[name])
        val props = spec.props
        if (props != null) for (name in keysOf(props)) element.asDynamic()[name] = props[name].v
        val specs = spec.listeners
        if (specs != null) {
            listeners.set(spec.id, specs)
            for (event in keysOf(specs)) delegate(event)
        }

        if (element.hasAttribute(COMPONENT_ATTRIBUTE)) {
            mount(spec.id, element.unsafeCast<HTMLElement>())
            return element
        }

        val kids: Array<Node> = (spec.children ?: arrayOf()).asDynamic().map { child: NodeSpec -> build(child) }
        children.set(spec.id, kids)
        for (kid in kids) element.appendChild(kid)
        return element
    }

    private fun register(id: Int, node: Node) {
        nodes.set(id, node)
        ids.set(node, id)
    }

    private fun mount(id: Int, element: HTMLElement) {
        val name = element.getAttribute(COMPONENT_ATTRIBUTE)!!
        children.set(id, arrayOf())
        val factory = components.get(name)
        if (factory == null) {
            console.warn("jetlin: no client component registered as \"$name\"")
            return
        }
        val push = { event: String, payload: dynamic ->
            val data: dynamic = js("({})")
            data.event = event
            data.payload = payload ?: js("({})")
            val wrapped: dynamic = js("({})")
            wrapped.data = data
            send(id, COMPONENT_EVENT, wrapped)
        }
        mounted.set(id, Mounted(factory, element, factory.mount(element, parseProps(element), push)))
    }

    private fun updateComponent(id: Int) {
        val live = mounted.get(id) ?: return
        val props = parseProps(live.element)
        if (live.factory.update) {
            live.factory.update(live.element, props, live.handle)
            return
        }
        if (live.factory.unmount) live.factory.unmount(live.element, live.handle)
        live.element.asDynamic().replaceChildren()
        mounted.delete(id)
        mount(id, live.element)
    }

    private fun unmount(id: Int) {
        val live = mounted.get(id) ?: return
        mounted.delete(id)
        try {
            if (live.factory.unmount) live.factory.unmount(live.element, live.handle)
        } catch (error: dynamic) {
            console.warn("jetlin: a client component threw while unmounting", error)
        }
    }

    private fun forget(node: Node) {
        val id = ids.get(node) ?: return
        unmount(id)
        children.get(id)?.forEach(::forget)
        nodes.delete(id)
        children.delete(id)
        listeners.delete(id)
        sentFrom.delete(id)
    }

    private fun delegate(event: String) {
        if (delegated.has(event)) return
        delegated.add(event)
        container.addEventListener(event, { onEvent(it) }, js("({ capture: true })"))
    }

    private fun onEvent(event: Event) {
        var node: Node? = event.target.unsafeCast<Node?>()
        while (node != null && node != container.parentNode) {
            val id = ids.get(node)
            if (id != null) {
                // An index, not `.get(...)`, which on `dynamic` would call a method named `get`.
                val specs = listeners.get(id)
                val spec: ListenerSpec? = if (specs != null) specs[event.type] else null
                if (spec != null) {
                    fire(id, node.unsafeCast<Element>(), event, spec)
                    return
                }
            }
            node = node.parentNode
        }
    }

    private fun runCommands(element: Element, commands: Array<ClientCommand>) {
        for (command in commands) {
            val t = command.target
            val target = if (t == null || t.t == "self") element else element.closest(".${CSS.escape(t.className)}")
            if (target == null) continue
            when (command.t) {
                "toggle" -> target.classList.toggle(command.name)
                "add" -> target.classList.add(command.name)
                "remove" -> target.classList.remove(command.name)
                "focus" -> target.unsafeCast<HTMLElement>().focus()
                "blur" -> target.unsafeCast<HTMLElement>().blur()
            }
        }
    }

    private fun fire(id: Int, element: Element, event: Event, spec: ListenerSpec) {
        if (spec.preventDefault == true) event.preventDefault()
        if (spec.stopPropagation == true) event.stopPropagation()
        spec.commands?.let { if (it.isNotEmpty()) runCommands(element, it) }
        if (spec.notify == false) return

        val key = "$id:${event.type}"
        val payload = payload(event, spec)

        val throttle = spec.throttleMs
        if (throttle != null && throttle > 0) {
            val now: Double = Date.now()
            val last = lastFired.get(key) ?: 0.0
            if (now - last < throttle) return
            lastFired.set(key, now)
        }

        val debounce = spec.debounceMs
        if (debounce != null && debounce > 0) {
            timers.get(key)?.let(::clearTimeout)
            timers.set(key, setTimeout({
                timers.delete(key)
                send(id, event.type, payload)
            }, debounce))
            return
        }

        send(id, event.type, payload)
    }

    private fun payload(event: Event, spec: ListenerSpec): dynamic {
        val payload: dynamic = js("({})")
        val target: dynamic = event.target
        for (field in spec.extract ?: arrayOf()) {
            when (field) {
                "value" -> payload.value = target?.value ?: ""
                "checked" -> payload.checked = target?.checked ?: false
                "key" -> payload.key = event.asDynamic().key
                "form" -> {
                    val form = event.target.unsafeCast<Element>().closest("form")
                    if (form != null) payload.form = Object.fromEntries(js("new FormData(form)"))
                }
            }
        }
        return payload
    }

    private fun send(id: Int, event: String, payload: dynamic) {
        seq += 1
        sentFrom.set(id, seq)
        val frame: dynamic = js("({ t: 'event' })")
        frame.node = id
        frame.event = event
        frame.seq = seq
        frame.payload = payload
        sendRaw(JSON.stringify(frame))
    }

    private fun sendRaw(frame: String) {
        val socket = socket
        if (socket != null && socket.readyState == WebSocket.OPEN) {
            socket.send(frame)
            return
        }
        outbox.asDynamic().push(frame)
        if (outbox.size > MAX_QUEUED_EVENTS) outbox.asDynamic().shift()
    }
}

fun main() {
    val api: dynamic = js("({})")
    api.connect = { options: dynamic ->
        val jetlin = Jetlin(options)
        val handle: dynamic = js("({})")
        handle.disconnect = { jetlin.disconnect() }
        handle.reload = { jetlin.reload() }
        handle
    }
    api.clientComponent = { name: String, factory: dynamic -> components.set(name, factory) }
    window.asDynamic().Jetlin = api
}
