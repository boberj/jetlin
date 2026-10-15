package jetlin.client

import jetlin.protocol.COMPONENT_EVENT
import jetlin.protocol.ClientCommand
import jetlin.protocol.ClientMessage
import jetlin.protocol.ClientTarget
import jetlin.protocol.EventPayload
import jetlin.protocol.Extract
import jetlin.protocol.JetlinJson
import jetlin.protocol.ListenerSpec
import jetlin.protocol.Namespace
import jetlin.protocol.NodeId
import jetlin.protocol.NodeSpec
import jetlin.protocol.Op
import jetlin.protocol.PropValue
import jetlin.protocol.ROOT_ID
import jetlin.protocol.ServerMessage
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.w3c.dom.CustomEvent
import org.w3c.dom.CustomEventInit
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.MessageEvent
import org.w3c.dom.Node
import org.w3c.dom.Text
import org.w3c.dom.WebSocket
import org.w3c.dom.asList
import org.w3c.dom.events.Event

// A port of jetlin-client/src/jetlin.ts. The comments in that file explain why the runtime behaves
// as it does, and they apply here unchanged. Comments in this file are only about the port.
//
// The difference that matters: this file has no hand-written mirror of the wire types. It decodes
// into the data classes in jetlin-protocol, compiled from the same source files the server uses.

private const val EMPTY_TEXT_MARKER = "0"
private const val COMPONENT_ATTRIBUTE = "data-jl-component"
private const val COMPONENT_PROPS_ATTRIBUTE = "data-jl-props"
private const val MAX_QUEUED_EVENTS = 64

// DOM node types. kotlinx-browser declares them on `Node`'s companion as `Short`, and comparing a
// `Short` to an `Int` literal needs a conversion either way, so plain constants read better.
private const val ELEMENT_NODE: Short = 1
private const val TEXT_NODE: Short = 3
private const val COMMENT_NODE: Short = 8

private val NAMESPACE_URIS = mapOf(Namespace.SVG to "http://www.w3.org/2000/svg")

/** The registered client component implementations, by name. */
internal val components = mutableMapOf<String, ComponentFactory>()

private class Mounted(val factory: ComponentFactory, val element: HTMLElement, val handle: JsAny?)

private val listenerSpecsSerializer = MapSerializer(String.serializer(), ListenerSpec.serializer())
private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())

internal class Jetlin(options: JetlinOptions) {
    private val container: HTMLElement = options.container ?: document.getElementById("jetlin-root") as HTMLElement
    private val token: String = options.token
    private val url: String = options.url ?: run {
        val scheme = if (window.location.protocol == "https:") "wss:" else "ws:"
        "$scheme//${window.location.host}/jetlin"
    }

    private var socket: WebSocket? = null
    private var reconnectAttempt = 0
    private var pendingAdopt = false
    private var fatal = false

    private val nodes = mutableMapOf<NodeId, Node>()

    /**
     * The server node ID for each DOM node.
     *
     * The TypeScript client uses a `WeakMap`. Common Kotlin has no weak map, so this is an ordinary
     * map, and `forget` removes entries explicitly instead of relying on the garbage collector.
     */
    private val ids = mutableMapOf<Node, NodeId>()
    private val children = mutableMapOf<NodeId, MutableList<Node>>()
    private val listeners = mutableMapOf<NodeId, MutableMap<String, ListenerSpec>>()
    private val mounted = mutableMapOf<NodeId, Mounted>()
    private val delegated = mutableSetOf<String>()

    private var seq = 0L
    private val sentFrom = mutableMapOf<NodeId, Long>()
    private val outbox = ArrayDeque<String>()
    private val timers = mutableMapOf<String, Int>()
    private val lastFired = mutableMapOf<String, Double>()

    /** What `Jetlin.connect` returns to the page. */
    val handle: JsAny = connectionHandle(disconnect = ::disconnect, reload = ::reload)

    init {
        register(ROOT_ID, container)
        children[ROOT_ID] = mutableListOf()

        window.addEventListener("popstate", {
            send(ClientMessage.Navigate(window.location.pathname + window.location.search))
        })

        pendingAdopt = adoptRequested(options) && adoptMarkup()
        open()
    }

    // ---------------------------------------------------------------- transport

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
            socket.send(
                encode(
                    ClientMessage.Hello(
                        token = token,
                        url = window.location.pathname + window.location.search,
                        adopt = pendingAdopt,
                    ),
                ),
            )
            pendingAdopt = false
            while (outbox.isNotEmpty()) socket.send(outbox.removeFirst())
        }

        socket.onmessage = { event: MessageEvent ->
            // `data` is `JsAny?` in the shared DOM declarations, and a text frame is a JS string.
            val text = event.data!!.unsafeCast<JsString>().toString()
            receive(JetlinJson.decodeFromString(ServerMessage.serializer(), text))
        }

        socket.onclose = {
            document.body!!.classList.add("jl-disconnected")
            scheduleReconnect()
        }

        socket.onerror = { socket.close() }
    }

    private fun reportError(message: String, fatal: Boolean) {
        val event = CustomEvent("jetlin:error", CustomEventInit(detail = errorDetail(message, fatal), cancelable = true))
        val handled = !window.dispatchEvent(event)

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

        if (!handled) consoleError(message)
    }

    private fun scheduleReconnect() {
        if (fatal) return
        val delay = minOf(1000 shl minOf(reconnectAttempt, 10), 10000)
        reconnectAttempt += 1
        window.setTimeout({
            open()
            null
        }, delay)
    }

    private fun receive(message: ServerMessage) {
        // The compiler checks that this covers every message. In TypeScript, a forgotten case in a
        // `switch` over a union compiles silently unless the code opts into an exhaustiveness check.
        when (message) {
            is ServerMessage.Reset -> reset(message.children)
            is ServerMessage.Patch -> for (op in message.ops) apply(op, message.ack)
            is ServerMessage.Ready -> Unit
            is ServerMessage.Navigate -> {
                if (message.replace) window.history.replaceState(historyState(), "", message.url)
                else window.history.pushState(historyState(), "", message.url)
                message.title?.let { document.title = it }
            }
            is ServerMessage.Load -> load(message.url, message.post)
            is ServerMessage.Error -> reportError(message.message, message.fatal)
        }
    }

    private fun load(url: String, post: Map<String, String>?) {
        fatal = true
        socket?.close()
        if (post == null) {
            window.location.assign(url)
            return
        }
        val form = document.createElement("form") as HTMLFormElement
        form.method = "post"
        form.action = url
        form.style.display = "none"
        for ((name, value) in post) {
            val input = document.createElement("input") as HTMLInputElement
            input.type = "hidden"
            input.name = name
            input.value = value
            form.appendChild(input)
        }
        document.body!!.appendChild(form)
        form.submit()
    }

    // ----------------------------------------------------------------- adoption

    private fun adoptMarkup(): Boolean =
        try {
            adoptElement(container, ROOT_ID)
            true
        } catch (error: Throwable) {
            consoleWarn("could not adopt the server-rendered markup; asking for a full render: ${error.message}")
            nodes.clear()
            ids.clear()
            children.clear()
            listeners.clear()
            register(ROOT_ID, container)
            children[ROOT_ID] = mutableListOf()
            false
        }

    private fun adoptElement(element: Element, id: NodeId) {
        register(id, element)

        element.getAttribute("data-jl-on")?.let { specs ->
            val parsed = JetlinJson.decodeFromString(listenerSpecsSerializer, specs)
            listeners[id] = parsed.toMutableMap()
            parsed.keys.forEach(::delegate)
        }

        if (element.hasAttribute("data-jl-raw")) {
            children[id] = mutableListOf()
            return
        }

        if (element.hasAttribute(COMPONENT_ATTRIBUTE)) {
            mount(id, element as HTMLElement)
            return
        }

        val textIds = parseTextMarkers(element.getAttribute("data-jl-t"))
        val logical = mutableListOf<Node>()

        for (node in element.childNodes.asList().toList()) {
            if (node.nodeType == COMMENT_NODE) {
                if (node.textContent == EMPTY_TEXT_MARKER) {
                    val empty = document.createTextNode("")
                    element.replaceChild(empty, node)
                    logical.add(empty)
                }
                continue
            }
            logical.add(node)
        }

        var textCount = 0
        for ((index, child) in logical.withIndex()) {
            when (child.nodeType) {
                ELEMENT_NODE -> {
                    child as Element
                    val childId = child.getAttribute("data-jl")
                        ?: error("<${child.tagName.lowercase()}> under node $id has no data-jl")
                    adoptElement(child, childId.toInt())
                }
                TEXT_NODE -> {
                    val textId = textIds[index] ?: error("node $id has unexpected text at index $index")
                    register(textId, child)
                    textCount += 1
                }
                else -> error("node $id has an unexpected child of type ${child.nodeType}")
            }
        }

        check(textCount == textIds.size) {
            "node $id declares ${textIds.size} text children but $textCount are present"
        }

        children[id] = logical
    }

    private fun parseTextMarkers(value: String?): Map<Int, NodeId> {
        if (value.isNullOrEmpty()) return emptyMap()
        return value.split(",").associate { entry ->
            val (index, id) = entry.split(":")
            index.toInt() to id.toInt()
        }
    }

    // ------------------------------------------------------------------ patches

    private fun reset(specs: List<NodeSpec>) {
        mounted.keys.toList().forEach(::unmount)
        replaceChildren(container)
        nodes.clear()
        children.clear()
        listeners.clear()
        register(ROOT_ID, container)

        val built = specs.mapTo(mutableListOf(), ::build)
        children[ROOT_ID] = built
        for (node in built) container.appendChild(node)
    }

    private fun apply(op: Op, ack: Long) {
        when (op) {
            is Op.Insert -> {
                val parent = nodes.getValue(op.parent)
                val siblings = children.getValue(op.parent)
                val node = build(op.node)
                val before = siblings.getOrNull(op.index)
                siblings.add(op.index, node)
                parent.insertBefore(node, before)
            }
            is Op.Remove -> {
                val siblings = children.getValue(op.parent)
                val range = siblings.subList(op.index, op.index + op.count)
                val removed = range.toList()
                range.clear()
                for (node in removed) {
                    forget(node)
                    node.parentNode?.removeChild(node)
                }
            }
            is Op.Move -> {
                val parent = nodes.getValue(op.parent)
                val siblings = children.getValue(op.parent)
                val dest = if (op.from > op.to) op.to else op.to - op.count
                val range = siblings.subList(op.from, op.from + op.count)
                val moved = range.toList()
                range.clear()
                siblings.addAll(dest, moved)
                val before = siblings.getOrNull(dest + op.count)
                for (node in moved) parent.insertBefore(node, before)
            }
            is Op.SetAttr -> {
                val element = nodes.getValue(op.id) as Element
                if (op.value == null) element.removeAttribute(op.name) else element.setAttribute(op.name, op.value)
                if (op.name == COMPONENT_PROPS_ATTRIBUTE) updateComponent(op.id)
            }
            is Op.SetProp -> {
                val pending = sentFrom[op.id]
                val isUserState = op.name == "value" || op.name == "checked"
                if (isUserState && pending != null && pending > ack) return
                setProp(nodes.getValue(op.id) as Element, op.name, op.value)
            }
            is Op.SetText -> (nodes.getValue(op.id) as Text).data = op.text
            is Op.Listen -> {
                listeners.getOrPut(op.id) { mutableMapOf() }[op.event] = op.spec
                delegate(op.event)
            }
            is Op.Unlisten -> listeners[op.id]?.remove(op.event)
        }
    }

    private fun setProp(element: Element, name: String, value: PropValue) {
        val js: JsAny = when (value) {
            is PropValue.Str -> value.v.toJsString()
            is PropValue.Bool -> value.v.toJsBoolean()
        }
        setProperty(element, name, js)
    }

    private fun build(spec: NodeSpec): Node {
        when (spec) {
            is NodeSpec.Text -> {
                val text = document.createTextNode(spec.text)
                register(spec.id, text)
                return text
            }
            is NodeSpec.Element -> {
                val namespace = NAMESPACE_URIS[spec.namespace]
                val element = if (namespace != null) {
                    document.createElementNS(namespace, spec.tag)
                } else {
                    document.createElement(spec.tag)
                }
                register(spec.id, element)
                element.setAttribute("data-jl", spec.id.toString())

                for ((name, value) in spec.attrs) element.setAttribute(name, value)
                for ((name, value) in spec.props) setProp(element, name, value)
                if (spec.listeners.isNotEmpty()) {
                    listeners[spec.id] = spec.listeners.toMutableMap()
                    spec.listeners.keys.forEach(::delegate)
                }

                if (element.hasAttribute(COMPONENT_ATTRIBUTE)) {
                    mount(spec.id, element as HTMLElement)
                    return element
                }

                val kids = spec.children.mapTo(mutableListOf(), ::build)
                children[spec.id] = kids
                for (kid in kids) element.appendChild(kid)
                return element
            }
        }
    }

    private fun register(id: NodeId, node: Node) {
        nodes[id] = node
        ids[node] = id
    }

    private fun mount(id: NodeId, element: HTMLElement) {
        val name = element.getAttribute(COMPONENT_ATTRIBUTE)!!
        children[id] = mutableListOf()

        val factory = components[name]
        if (factory == null) {
            consoleWarn("no client component registered as \"$name\"")
            return
        }

        val push: (String, JsAny?) -> Unit = { event, payload ->
            // The payload is arbitrary JSON from the application, and the protocol carries it as a
            // `JsonObject`. Going through a string is the simplest bridge from a JS object to a
            // kotlinx.serialization tree, and these events are rare enough that the cost is moot.
            val data = JsonObject(
                mapOf(
                    "event" to JsonPrimitive(event),
                    "payload" to JetlinJson.parseToJsonElement(jsonStringify(payload)).jsonObject,
                ),
            )
            send(id, COMPONENT_EVENT, EventPayload(data = data))
        }

        val handle = factoryMount(factory, element, parseProps(element), push)
        mounted[id] = Mounted(factory, element, handle)
    }

    private fun parseProps(element: Element): JsAny {
        val raw = element.getAttribute(COMPONENT_PROPS_ATTRIBUTE)
        if (raw.isNullOrEmpty()) return jsonParse("{}")
        return try {
            jsonParse(raw)
        } catch (_: Throwable) {
            consoleWarn("could not parse props for component \"${element.getAttribute(COMPONENT_ATTRIBUTE)}\"")
            jsonParse("{}")
        }
    }

    private fun updateComponent(id: NodeId) {
        val live = mounted[id] ?: return
        val props = parseProps(live.element)
        if (factoryHasUpdate(live.factory)) {
            factoryUpdate(live.factory, live.element, props, live.handle)
            return
        }
        factoryUnmount(live.factory, live.element, live.handle)
        replaceChildren(live.element)
        mounted.remove(id)
        mount(id, live.element)
    }

    private fun unmount(id: NodeId) {
        val live = mounted.remove(id) ?: return
        try {
            factoryUnmount(live.factory, live.element, live.handle)
        } catch (error: Throwable) {
            consoleWarn("a client component threw while unmounting: ${error.message}")
        }
    }

    private fun forget(node: Node) {
        val id = ids.remove(node) ?: return
        unmount(id)
        children[id]?.forEach(::forget)
        nodes.remove(id)
        children.remove(id)
        listeners.remove(id)
        sentFrom.remove(id)
    }

    // ------------------------------------------------------------------- events

    private fun delegate(event: String) {
        if (!delegated.add(event)) return
        container.addEventListener(event, ::onEvent, true)
    }

    private fun onEvent(event: Event) {
        var node: Node? = event.target?.unsafeCast<Node>()
        val stop = container.parentNode
        while (node != null && node != stop) {
            val id = ids[node]
            if (id != null) {
                val spec = listeners[id]?.get(event.type)
                if (spec != null) {
                    fire(id, node as Element, event, spec)
                    return
                }
            }
            node = node.parentNode
        }
    }

    private fun runCommands(element: Element, commands: List<ClientCommand>) {
        for (command in commands) {
            val target = when (val t = command.target) {
                ClientTarget.Self -> element
                is ClientTarget.Closest -> element.closest(".${cssEscape(t.className)}")
            } ?: continue
            when (command) {
                is ClientCommand.ToggleClass -> target.classList.toggle(command.name)
                is ClientCommand.AddClass -> target.classList.add(command.name)
                is ClientCommand.RemoveClass -> target.classList.remove(command.name)
                is ClientCommand.Focus -> (target as HTMLElement).focus()
                is ClientCommand.Blur -> (target as HTMLElement).blur()
            }
        }
    }

    private fun fire(id: NodeId, element: Element, event: Event, spec: ListenerSpec) {
        if (spec.preventDefault) event.preventDefault()
        if (spec.stopPropagation) event.stopPropagation()

        if (spec.commands.isNotEmpty()) runCommands(element, spec.commands)
        if (!spec.notify) return

        val key = "$id:${event.type}"
        val payload = payload(event, spec)

        if (spec.throttleMs > 0) {
            val now = now()
            val last = lastFired[key] ?: 0.0
            if (now - last < spec.throttleMs) return
            lastFired[key] = now
        }

        if (spec.debounceMs > 0) {
            timers[key]?.let(window::clearTimeout)
            timers[key] = window.setTimeout({
                timers.remove(key)
                send(id, event.type, payload)
                null
            }, spec.debounceMs)
            return
        }

        send(id, event.type, payload)
    }

    private fun payload(event: Event, spec: ListenerSpec): EventPayload {
        var payload = EventPayload()
        for (field in spec.extract) {
            payload = when (field) {
                Extract.VALUE -> payload.copy(value = targetValue(event))
                Extract.CHECKED -> payload.copy(checked = targetChecked(event))
                Extract.KEY -> payload.copy(key = eventKey(event))
                Extract.FORM -> {
                    val form = event.target?.unsafeCast<Element>()?.closest("form")
                    if (form == null) payload
                    else payload.copy(form = JetlinJson.decodeFromString(stringMapSerializer, formJson(form as HTMLFormElement)))
                }
            }
        }
        return payload
    }

    private fun send(id: NodeId, event: String, payload: EventPayload) {
        seq += 1
        sentFrom[id] = seq
        send(ClientMessage.Event(node = id, event = event, seq = seq, payload = payload))
    }

    private fun send(message: ClientMessage) {
        val frame = encode(message)
        val socket = socket
        if (socket != null && socket.readyState == WebSocket.OPEN) {
            socket.send(frame)
            return
        }
        outbox.addLast(frame)
        if (outbox.size > MAX_QUEUED_EVENTS) outbox.removeFirst()
    }

    private fun encode(message: ClientMessage): String = JetlinJson.encodeToString(ClientMessage.serializer(), message)
}
