/**
 * The Jetlin browser runtime.
 *
 * It's deliberately small, and does two things: it applies the DOM ops the server sends, and it
 * reports DOM events back. It holds no application state and makes no rendering decisions, so
 * there's no reconciliation step here. The server has already worked out which nodes to change.
 *
 * The types below mirror the Kotlin protocol in `jetlin-protocol`. Keep them in step.
 */

/** The value of a DOM property: a string (`s`) or a Boolean (`b`). */
type PropValue = { t: "s"; v: string } | { t: "b"; v: boolean };

/**
 * The element a command applies to: the listener's own element, or its nearest ancestor with a
 * class.
 */
type ClientTarget = { t: "self" } | { t: "closest"; className: string };

/**
 * Something the browser does by itself when an event fires.
 *
 * It's a fixed set of commands instead of a script. A disclosure or a menu needs no server, and a
 * round trip to open one only adds latency. Anything that needs real logic stays on the server.
 */
type ClientCommand =
  | { t: "toggle"; name: string; target?: ClientTarget }
  | { t: "add"; name: string; target?: ClientTarget }
  | { t: "remove"; name: string; target?: ClientTarget }
  | { t: "focus"; target?: ClientTarget }
  | { t: "blur"; target?: ClientTarget };

/** What to do when an event fires on a node, and what to send the server. */
interface ListenerSpec {
  /** The fields to read from the event: `value`, `checked`, `key`, or `form`. */
  extract?: string[];
  /** The commands to run in the browser before sending anything. */
  commands?: ClientCommand[];
  /** Whether to send the event to the server. When it's missing, the event is sent. */
  notify?: boolean;
  /** How long the event must stop firing, in milliseconds, before it's sent. */
  debounceMs?: number;
  /** The minimum time between two sends of the event, in milliseconds. */
  throttleMs?: number;
  preventDefault?: boolean;
  stopPropagation?: boolean;
}

/** A node and its subtree: an element (`e`) or a text node (`t`). */
type NodeSpec =
  | {
      t: "e";
      id: number;
      tag: string;
      /** The namespace. When it's missing, the element is HTML, which almost every node is. */
      ns?: "html" | "svg";
      attrs?: Record<string, string>;
      props?: Record<string, PropValue>;
      listeners?: Record<string, ListenerSpec>;
      children?: NodeSpec[];
    }
  | { t: "t"; id: number; text: string };

/** One change to the DOM. See `Op` in `jetlin-protocol` for what each one does. */
type Op =
  | { t: "ins"; parent: number; index: number; node: NodeSpec }
  | { t: "rm"; parent: number; index: number; count: number }
  | { t: "mv"; parent: number; from: number; to: number; count: number }
  | { t: "attr"; id: number; name: string; value: string | null }
  | { t: "prop"; id: number; name: string; value: PropValue }
  | { t: "text"; id: number; text: string }
  | { t: "on"; id: number; event: string; spec: ListenerSpec }
  | { t: "off"; id: number; event: string };

/** A message from the server. See `ServerMessage` in `jetlin-protocol`. */
type ServerMessage =
  | { t: "patch"; rev: number; ack: number; ops: Op[] }
  | { t: "ready"; rev: number }
  | { t: "reset"; rev: number; children: NodeSpec[] }
  | { t: "nav"; url: string; replace?: boolean; title?: string }
  | { t: "error"; message: string; fatal?: boolean };

/** The ID of the root node, which is the container element. */
const ROOT_ID = 0;

/**
 * The namespace URIs for elements that must be created with `createElementNS` instead of
 * `createElement`.
 *
 * `createElement("circle")` gives no error and no warning. It makes an `HTMLUnknownElement` that
 * takes up no space, so a chart built that way is just missing. The server decides each node's
 * namespace and sends it with the node, because the tag doesn't decide it: `a`, `title`, `style`,
 * and `script` exist in both languages.
 */
const NAMESPACE_URIS: Record<string, string> = {
  svg: "http://www.w3.org/2000/svg",
};

/**
 * The content of the comment that marks where an empty text child belongs.
 *
 * The server writes two kinds of comment. This one stands in for a node that the parser wouldn't
 * produce at all, so the client has to turn it back into a text node. The other kind separates two
 * adjacent text children, and needs no constant here: the HTML parser already did its job by
 * keeping them as two nodes instead of merging them.
 */
const EMPTY_TEXT_MARKER = "0";

/**
 * Returns the element that a command applies to: `element` itself, or its nearest ancestor with a
 * class. Returns `null` if no ancestor has the class.
 */
function resolveTarget(element: Element, target: ClientTarget | undefined): Element | null {
  if (!target || target.t === "self") return element;
  return element.closest(`.${CSS.escape(target.className)}`);
}

/**
 * Parses a `data-jl-t` attribute: `index:id` pairs that identify an element's text children, which
 * have no attributes of their own. Returns the ID for each child index.
 */
function parseTextMarkers(value: string | null): Map<number, number> {
  const markers = new Map<number, number>();
  if (!value) return markers;
  for (const entry of value.split(",")) {
    const [index, id] = entry.split(":");
    markers.set(Number(index), Number(id));
  }
  return markers;
}

/** The event name that a component's events travel under. It matches `COMPONENT_EVENT` on the server. */
const COMPONENT_EVENT = "jl:component";

/** The attribute that names a client component's implementation. */
const COMPONENT_ATTRIBUTE = "data-jl-component";

/** The attribute that holds a client component's props, as JSON. */
const COMPONENT_PROPS_ATTRIBUTE = "data-jl-props";

/**
 * An implementation that the application registers for a `ClientComponent` to render.
 *
 * `mount` can return a handle, such as the editor or chart it built. The runtime passes the handle
 * back to `update` and `unmount`, so the implementation doesn't need a registry of its own.
 */
export interface ClientComponentFactory<T = unknown> {
  /**
   * Renders into `element`, which starts empty. Call `push` to send an event to the server.
   * Returns the handle that `update` and `unmount` receive.
   */
  mount(element: HTMLElement, props: Record<string, unknown>, push: PushFn): T;
  /** Called when the server sends new props. Without it, changed props remount the component. */
  update?(element: HTMLElement, props: Record<string, unknown>, handle: T): void;
  /** Called before the element leaves the page. Release listeners and timers here. */
  unmount?(element: HTMLElement, handle: T): void;
}

/** Sends a named event, with an optional payload, from a client component to the server. */
export type PushFn = (event: string, payload?: Record<string, unknown>) => void;

/** The registered client component implementations, by name. */
const components = new Map<string, ClientComponentFactory<never>>();

/**
 * Registers `factory` under `name`, so a `ClientComponent` on the server can ask for it by name.
 *
 * It's a registry instead of a lookup by global name. What the server sends is a key into a table
 * that the application filled in, so it can never be code to run.
 */
export function clientComponent<T>(name: string, factory: ClientComponentFactory<T>): void {
  components.set(name, factory as ClientComponentFactory<never>);
}

/** A client component that's mounted, with the handle its `mount` returned. */
interface Mounted {
  factory: ClientComponentFactory<never>;
  element: HTMLElement;
  handle: never;
}

/** Parses an element's component props, or returns `{}` and logs a warning if they aren't valid JSON. */
function parseProps(element: Element): Record<string, unknown> {
  const raw = element.getAttribute(COMPONENT_PROPS_ATTRIBUTE);
  if (!raw) return {};
  try {
    return JSON.parse(raw) as Record<string, unknown>;
  } catch {
    console.warn(`jetlin: could not parse props for component "${element.getAttribute(COMPONENT_ATTRIBUTE)}"`);
    return {};
  }
}

/** Options for `connect`. */
export interface JetlinOptions {
  /** The WebSocket URL. It defaults to `/jetlin` on the page's host. */
  url?: string;
  /** The session token that the server rendered into the page. */
  token: string;
  /** The element that holds the page's content. It defaults to the element with the ID `jetlin-root`. */
  container?: HTMLElement;
  /**
   * Whether to keep the server-rendered markup instead of asking for the tree again.
   *
   * It's on by default. Setting it to `false` forces a full render, which helps when you suspect an
   * adoption bug: one flag tells you whether adoption is involved.
   */
  adopt?: boolean;
}

/** Connects the page to its server session. The page's inline script calls this. */
export function connect(options: JetlinOptions): Jetlin {
  return new Jetlin(options);
}

/** One page's connection to its server session, and the DOM it manages. */
class Jetlin {
  private readonly container: HTMLElement;
  private readonly url: string;
  private readonly token: string;

  private socket: WebSocket | null = null;
  private reconnectAttempt = 0;
  /**
   * Whether the next hello should ask to keep the markup already on the page.
   *
   * It's `true` for at most one socket. Only the browser that holds markup this composition rendered
   * can adopt it, and after a disconnect the server stops recording, so its tree changes unseen.
   */
  private pendingAdopt = false;
  /** Whether the server said the session can't recover. It stops the reconnect loop. */
  private fatal = false;

  /** The DOM node for each server node ID. */
  private nodes = new Map<number, Node>();
  /** The server node ID for each DOM node. */
  private ids = new WeakMap<Node, number>();
  /**
   * Each element's children as the server sees them, by node ID.
   *
   * The DOM's own `childNodes` can't be used for indexing, because the browser merges adjacent text
   * nodes and third-party scripts insert siblings. With a separate array, every index in an op means
   * exactly what the server meant.
   */
  private children = new Map<number, Node[]>();
  /** Each node's listener specs by event type, by node ID. */
  private listeners = new Map<number, Record<string, ListenerSpec>>();

  /** The mounted client components, by node ID, so they can be updated and torn down. */
  private mounted = new Map<number, Mounted>();

  /** The event types that already have a delegated listener on the container. */
  private delegated = new Set<string>();

  /** The sequence number of the last event sent. */
  private seq = 0;
  /** The highest event sequence number sent from each node, for the stale-write guard. */
  private sentFrom = new Map<number, number>();
  /**
   * The frames produced while the socket isn't open.
   *
   * The first paint is interactive HTML that exists before the WebSocket finishes connecting, so a
   * fast click can land in that window, and dropping it would lose a real user action. The queue is
   * bounded, so a long disconnection doesn't pile up work forever.
   */
  private outbox: string[] = [];
  /** The pending debounce timer for each node and event type, keyed `id:type`. */
  private timers = new Map<string, number>();
  /** When each node and event type was last sent, for throttling, keyed `id:type`. */
  private lastFired = new Map<string, number>();

  constructor(options: JetlinOptions) {
    this.container = options.container ?? document.getElementById("jetlin-root")!;
    this.token = options.token;
    const scheme = location.protocol === "https:" ? "wss:" : "ws:";
    this.url = options.url ?? `${scheme}//${location.host}/jetlin`;
    this.register(ROOT_ID, this.container);
    this.children.set(ROOT_ID, []);

    // Back and forward change the address bar first, and the server follows. The server is told
    // where the browser went instead of being asked, so the browser history stays authoritative.
    window.addEventListener("popstate", () => {
      this.sendRaw(JSON.stringify({ t: "nav", url: location.pathname + location.search }));
    });

    // Adopt the markup before connecting, so the hello can say whether the tree still needs sending.
    this.pendingAdopt = options.adopt !== false && this.adoptMarkup();

    this.open();
  }

  // ---------------------------------------------------------------- transport

  /**
   * Closes the socket.
   *
   * The runtime then reconnects with its normal backoff, and the server hands back the same
   * composition. Tests use this to exercise reconnection.
   */
  public disconnect(): void {
    this.socket?.close();
  }

  /** Opens a socket and sets up its handlers. */
  private open(): void {
    const socket = new WebSocket(this.url);
    this.socket = socket;

    socket.onopen = () => {
      this.reconnectAttempt = 0;
      document.body.classList.remove("jl-disconnected");
      // The address bar is authoritative. If the server has to wake the session from storage, the
      // user might have pressed the back button while this socket was down.
      socket.send(
        JSON.stringify({
          t: "hello",
          token: this.token,
          url: location.pathname + location.search,
          adopt: this.pendingAdopt,
        }),
      );
      // Only one socket may adopt. A later socket holds markup that the server changed unobserved.
      this.pendingAdopt = false;
      const pending = this.outbox;
      this.outbox = [];
      for (const frame of pending) socket.send(frame);
    };

    socket.onmessage = (event) => {
      const message = JSON.parse(event.data as string) as ServerMessage;
      this.receive(message);
    };

    socket.onclose = () => {
      document.body.classList.add("jl-disconnected");
      this.scheduleReconnect();
    };

    socket.onerror = () => socket.close();
  }

  /**
   * Tells the page that something failed, and does the default thing unless the page takes over.
   *
   * It dispatches a `jetlin:error` event on the window, so an application can show the error its own
   * way, such as a toast or a banner. The framework shouldn't decide what an error looks like, but it
   * does have to make one noticeable. A click that did nothing, with no explanation, is the worst
   * outcome.
   *
   * The event is cancelable, and a page takes over by calling `preventDefault()`. Listening alone
   * doesn't count. Many applications add a listener only to forward errors to their telemetry, and
   * turning off recovery for them without warning would be a nasty surprise. Taking over has to be a
   * decision made for each error, not a side effect of wanting to hear about errors.
   *
   * By default, a fatal error reloads the page, and any other error is logged to the console.
   */
  private reportError(message: string, fatal: boolean): void {
    const event = new CustomEvent("jetlin:error", {
      detail: { message, fatal },
      cancelable: true,
    });
    // The browser reports a listener's exception to the global handler instead of throwing it here,
    // so a page with a broken listener still gets the default behavior.
    const handled = !window.dispatchEvent(event);

    if (fatal) {
      // The session is gone for good: it hibernated past its expiry, never existed, or its
      // composition stopped. Nothing on this page can change again.
      this.fatal = true;
      this.socket?.close();
      if (handled) {
        // The page asked to stay. It's now showing something that can't respond, so mark that in a
        // way CSS can use. Whoever cancelled the default decides what the user sees from here.
        document.body.classList.add("jl-dead");
        return;
      }
      // Otherwise, start a new session instead of leaving a page that looks live but isn't.
      this.reload();
      return;
    }

    if (!handled) console.error("[jetlin]", message);
  }

  /**
   * Reloads the page, which starts a new session. A page that cancelled a fatal error can offer
   * this.
   */
  public reload(): void {
    location.reload();
  }

  /** Reconnects after an exponential backoff of up to 10 seconds, unless the session is gone. */
  private scheduleReconnect(): void {
    if (this.fatal) return;
    const delay = Math.min(1000 * 2 ** this.reconnectAttempt, 10000);
    this.reconnectAttempt += 1;
    setTimeout(() => this.open(), delay);
  }

  /** Handles one message from the server. */
  private receive(message: ServerMessage): void {
    switch (message.t) {
      case "reset":
        this.reset(message.children);
        break;
      case "patch":
        for (const op of message.ops) this.apply(op, message.ack);
        break;
      case "ready":
        // The adopted markup stands. Anything that changed during the connection follows as an
        // ordinary patch, so there's nothing to do here.
        break;
      case "nav":
        // The DOM for this location arrived in the patch just before, so the address bar changes
        // last and never points at content that isn't on screen yet.
        if (message.replace) history.replaceState({ jetlin: true }, "", message.url);
        else history.pushState({ jetlin: true }, "", message.url);
        if (message.title) document.title = message.title;
        break;
      case "error":
        this.reportError(message.message, !!message.fatal);
        break;
    }
  }

  // ----------------------------------------------------------------- adoption

  /**
   * Indexes the markup the server already sent, instead of waiting to receive the tree again.
   *
   * The DOM that the browser parsed, laid out, and painted stays as it is. That saves sending the
   * tree twice. More importantly, it keeps whatever happened to the page in the meantime, such as
   * focus, a text selection, a scroll position, or an element another script inserted. A rebuild
   * would throw all of that away.
   *
   * This is best-effort by design. Any disagreement with the markup abandons the attempt and asks
   * for a full render. A missing marker or a proxy that rewrote the HTML costs an optimization, not
   * correctness.
   *
   * Returns whether adoption succeeded.
   */
  private adoptMarkup(): boolean {
    try {
      this.adoptElement(this.container, ROOT_ID);
      return true;
    } catch (error) {
      console.warn("[jetlin] could not adopt the server-rendered markup; asking for a full render", error);
      this.nodes.clear();
      this.ids = new WeakMap();
      this.children.clear();
      this.listeners.clear();
      this.register(ROOT_ID, this.container);
      this.children.set(ROOT_ID, []);
      return false;
    }
  }

  /**
   * Indexes `element` as node `id`, with its listeners and subtree. Throws if the markup doesn't
   * match.
   */
  private adoptElement(element: Element, id: number): void {
    this.register(id, element);

    const specs = element.getAttribute("data-jl-on");
    if (specs) {
      const parsed = JSON.parse(specs) as Record<string, ListenerSpec>;
      this.listeners.set(id, parsed);
      for (const event of Object.keys(parsed)) this.delegate(event);
    }

    // Raw markup belongs to whoever wrote it. The composition has no children here to index, and
    // walking into it would claim nodes the server has never heard of.
    if (element.hasAttribute("data-jl-raw")) {
      this.children.set(id, []);
      return;
    }

    // The same goes for a client component, which also has to be started. The markup it was served
    // is an empty shell, and the implementation fills it.
    if (element.hasAttribute(COMPONENT_ATTRIBUTE)) {
      this.mount(id, element as HTMLElement);
      return;
    }

    const textIds = parseTextMarkers(element.getAttribute("data-jl-t"));
    const logical: Node[] = [];

    // Copy the list, because creating an empty text node changes the list being walked.
    for (const node of Array.from(element.childNodes)) {
      if (node.nodeType === Node.COMMENT_NODE) {
        if ((node as Comment).data === EMPTY_TEXT_MARKER) {
          // The parser produces nothing for an empty text child, so the server wrote a marker where
          // the node belongs, and the node goes there.
          const empty = document.createTextNode("");
          element.replaceChild(empty, node);
          logical.push(empty);
        }
        // Separators have already done their job, and other comments aren't Jetlin's. Neither is
        // a child.
        continue;
      }
      logical.push(node);
    }

    let textCount = 0;
    for (let index = 0; index < logical.length; index++) {
      const child = logical[index];
      if (child.nodeType === Node.ELEMENT_NODE) {
        const childId = (child as Element).getAttribute("data-jl");
        if (childId === null) {
          throw new Error(`<${(child as Element).tagName.toLowerCase()}> under node ${id} has no data-jl`);
        }
        this.adoptElement(child as Element, Number(childId));
      } else if (child.nodeType === Node.TEXT_NODE) {
        const textId = textIds.get(index);
        if (textId === undefined) {
          throw new Error(`node ${id} has unexpected text at index ${index}`);
        }
        this.register(textId, child);
        textCount += 1;
      } else {
        throw new Error(`node ${id} has an unexpected child of type ${child.nodeType}`);
      }
    }

    // Every declared text child must have been found. A mismatch means the markup and the server's
    // idea of this element have diverged, and every index below would be suspect.
    if (textCount !== textIds.size) {
      throw new Error(`node ${id} declares ${textIds.size} text children but ${textCount} are present`);
    }

    this.children.set(id, logical);
  }

  // ------------------------------------------------------------------ patches

  /** Replaces the whole tree with `children`. */
  private reset(children: NodeSpec[]): void {
    // Unmount every component before removing the DOM that holds it, or its listeners and timers
    // outlive the page they belonged to.
    for (const id of Array.from(this.mounted.keys())) this.unmount(id);
    this.container.replaceChildren();
    this.nodes.clear();
    this.children.clear();
    this.listeners.clear();
    this.register(ROOT_ID, this.container);

    const built = children.map((spec) => this.build(spec));
    this.children.set(ROOT_ID, built);
    for (const node of built) this.container.appendChild(node);
  }

  /**
   * Applies one op. `ack` is the patch's acknowledged event sequence number, for the stale-write
   * guard.
   */
  private apply(op: Op, ack: number): void {
    switch (op.t) {
      case "ins": {
        const parent = this.nodes.get(op.parent) as Element;
        const siblings = this.children.get(op.parent)!;
        const node = this.build(op.node);
        // Read the reference node before splicing, or it points at the wrong sibling.
        const before = siblings[op.index] ?? null;
        siblings.splice(op.index, 0, node);
        parent.insertBefore(node, before);
        break;
      }
      case "rm": {
        const siblings = this.children.get(op.parent)!;
        const removed = siblings.splice(op.index, op.count);
        for (const node of removed) {
          this.forget(node);
          (node as ChildNode).remove();
        }
        break;
      }
      case "mv": {
        const parent = this.nodes.get(op.parent) as Element;
        const siblings = this.children.get(op.parent)!;
        // `to` is an index in the list as it was before the move, so when items move toward the end,
        // the destination has to be adjusted by the number removed. This mirrors the server-side
        // applier. If the two ever disagree, lists reorder differently on each side, without an
        // error.
        const dest = op.from > op.to ? op.to : op.to - op.count;
        const moved = siblings.splice(op.from, op.count);
        siblings.splice(dest, 0, ...moved);
        const before = siblings[dest + op.count] ?? null;
        for (const node of moved) parent.insertBefore(node, before);
        break;
      }
      case "attr": {
        const element = this.nodes.get(op.id) as Element;
        if (op.value === null) element.removeAttribute(op.name);
        else element.setAttribute(op.name, op.value);
        if (op.name === COMPONENT_PROPS_ATTRIBUTE) this.updateComponent(op.id);
        break;
      }
      case "prop": {
        const element = this.nodes.get(op.id) as Element;
        // The stale-write guard. If this node sent an event that the server hadn't seen when it
        // built this patch, the server's `value` is older than what the user has typed, and
        // applying it would lose keystrokes.
        const pending = this.sentFrom.get(op.id);
        const isUserState = op.name === "value" || op.name === "checked";
        if (isUserState && pending !== undefined && pending > ack) break;
        (element as unknown as Record<string, unknown>)[op.name] = op.value.v;
        break;
      }
      case "text": {
        (this.nodes.get(op.id) as Text).data = op.text;
        break;
      }
      case "on": {
        const specs = this.listeners.get(op.id) ?? {};
        specs[op.event] = op.spec;
        this.listeners.set(op.id, specs);
        this.delegate(op.event);
        break;
      }
      case "off": {
        const specs = this.listeners.get(op.id);
        if (specs) delete specs[op.event];
        break;
      }
    }
  }

  /** Creates the DOM for `spec` and its subtree, and registers every node. */
  private build(spec: NodeSpec): Node {
    if (spec.t === "t") {
      const text = document.createTextNode(spec.text);
      this.register(spec.id, text);
      return text;
    }

    const namespace = spec.ns ? NAMESPACE_URIS[spec.ns] : undefined;
    const element = namespace
      ? document.createElementNS(namespace, spec.tag)
      : document.createElement(spec.tag);
    this.register(spec.id, element);
    element.setAttribute("data-jl", String(spec.id));

    for (const [name, value] of Object.entries(spec.attrs ?? {})) {
      element.setAttribute(name, value);
    }
    for (const [name, value] of Object.entries(spec.props ?? {})) {
      (element as unknown as Record<string, unknown>)[name] = value.v;
    }
    if (spec.listeners) {
      this.listeners.set(spec.id, spec.listeners);
      for (const event of Object.keys(spec.listeners)) this.delegate(event);
    }

    if (element.hasAttribute(COMPONENT_ATTRIBUTE)) {
      this.mount(spec.id, element as HTMLElement);
      return element;
    }

    const kids = (spec.children ?? []).map((child) => this.build(child));
    this.children.set(spec.id, kids);
    for (const kid of kids) element.appendChild(kid);
    return element;
  }

  /** Records that `node` is server node `id`. */
  private register(id: number, node: Node): void {
    this.nodes.set(id, node);
    this.ids.set(node, id);
  }

  /**
   * Hands an element to the implementation registered under its component name.
   *
   * Its children are recorded as empty, so Jetlin never indexes or patches inside it afterward.
   * Whatever the implementation renders belongs to it, and the server has never heard of it.
   */
  private mount(id: number, element: HTMLElement): void {
    const name = element.getAttribute(COMPONENT_ATTRIBUTE)!;
    this.children.set(id, []);

    const factory = components.get(name);
    if (!factory) {
      // Leave the element empty instead of failing. A missing registration is a build problem in one
      // corner of the page, and ending the whole session over it helps nobody.
      console.warn(`jetlin: no client component registered as "${name}"`);
      return;
    }

    const push: PushFn = (event, payload) =>
      this.send(id, COMPONENT_EVENT, { data: { event, payload: payload ?? {} } });

    const handle = factory.mount(element, parseProps(element), push) as never;
    this.mounted.set(id, { factory, element, handle });
  }

  /** Passes new props to a component, and remounts it if its implementation has no `update`. */
  private updateComponent(id: number): void {
    const live = this.mounted.get(id);
    if (!live) return;
    const props = parseProps(live.element);
    if (live.factory.update) {
      live.factory.update(live.element, props, live.handle);
      return;
    }
    live.factory.unmount?.(live.element, live.handle);
    live.element.replaceChildren();
    this.mounted.delete(id);
    this.mount(id, live.element);
  }

  /**
   * Tears down a component before its element leaves the page.
   *
   * This isn't optional. A third-party widget that's never told it's leaving holds on to listeners,
   * timers, and observers, and a list that re-renders leaks a set of them every time.
   */
  private unmount(id: number): void {
    const live = this.mounted.get(id);
    if (!live) return;
    this.mounted.delete(id);
    try {
      live.factory.unmount?.(live.element, live.handle);
    } catch (error) {
      // One misbehaving widget must not stop the rest of the page from being torn down correctly.
      console.warn("jetlin: a client component threw while unmounting", error);
    }
  }

  /** Unregisters `node` and its subtree, and unmounts any components in it. */
  private forget(node: Node): void {
    const id = this.ids.get(node);
    if (id === undefined) return;
    this.unmount(id);
    for (const child of this.children.get(id) ?? []) this.forget(child);
    this.nodes.delete(id);
    this.children.delete(id);
    this.listeners.delete(id);
    this.sentFrom.delete(id);
  }

  // ------------------------------------------------------------------- events

  /**
   * Adds one capture-phase listener for `event` on the container, unless it already has one.
   *
   * It listens in the capture phase instead of the bubble phase, so events that don't bubble, such
   * as `focus` and `blur`, still reach it. One listener on the container also survives any amount of
   * change to the subtree.
   */
  private delegate(event: string): void {
    if (this.delegated.has(event)) return;
    this.delegated.add(event);
    this.container.addEventListener(event, (e) => this.onEvent(e), { capture: true });
  }

  /** Finds the nearest node from the event's target outward that listens for it, and fires it there. */
  private onEvent(event: Event): void {
    let node: Node | null = event.target as Node;
    while (node && node !== this.container.parentNode) {
      const id = this.ids.get(node);
      if (id !== undefined) {
        const spec = this.listeners.get(id)?.[event.type];
        if (spec) {
          this.fire(id, node as Element, event, spec);
          return;
        }
      }
      node = node.parentNode;
    }
  }

  /**
   * Runs the commands a listener declared, resolving each one's target from `element`.
   *
   * They deliberately run before any debounce or throttle, and before anything is sent. Commands
   * exist so the page reacts at once. Delaying them by the interval that spares the server a round
   * trip would defeat their purpose.
   */
  private runCommands(element: Element, commands: ClientCommand[]): void {
    for (const command of commands) {
      const target = resolveTarget(element, command.target);
      if (!target) continue;
      switch (command.t) {
        case "toggle":
          target.classList.toggle(command.name);
          break;
        case "add":
          target.classList.add(command.name);
          break;
        case "remove":
          target.classList.remove(command.name);
          break;
        case "focus":
          (target as HTMLElement).focus();
          break;
        case "blur":
          (target as HTMLElement).blur();
          break;
      }
    }
  }

  /**
   * Handles `event` on node `id`: runs its commands, then sends it, debounced or throttled as
   * `spec` says.
   */
  private fire(id: number, element: Element, event: Event, spec: ListenerSpec): void {
    if (spec.preventDefault) event.preventDefault();
    if (spec.stopPropagation) event.stopPropagation();

    if (spec.commands?.length) this.runCommands(element, spec.commands);

    // Nothing on the server is waiting for this event. It was declared with commands and no
    // handler, so the browser has already done everything there was to do.
    if (spec.notify === false) return;

    const key = `${id}:${event.type}`;
    const payload = this.payload(event, spec);

    if (spec.throttleMs) {
      const now = Date.now();
      const last = this.lastFired.get(key) ?? 0;
      if (now - last < spec.throttleMs) return;
      this.lastFired.set(key, now);
    }

    if (spec.debounceMs) {
      const existing = this.timers.get(key);
      if (existing) clearTimeout(existing);
      this.timers.set(
        key,
        setTimeout(() => {
          this.timers.delete(key);
          this.send(id, event.type, payload);
        }, spec.debounceMs) as unknown as number,
      );
      return;
    }

    this.send(id, event.type, payload);
  }

  /** Reads the fields that `spec` asks for from `event`. */
  private payload(event: Event, spec: ListenerSpec): Record<string, unknown> {
    const payload: Record<string, unknown> = {};
    const target = event.target as HTMLInputElement | null;
    for (const field of spec.extract ?? []) {
      switch (field) {
        case "value":
          payload.value = target?.value ?? "";
          break;
        case "checked":
          payload.checked = target?.checked ?? false;
          break;
        case "key":
          payload.key = (event as KeyboardEvent).key;
          break;
        case "form": {
          const form = (event.target as HTMLElement).closest("form");
          if (form) {
            const data: Record<string, string> = {};
            for (const [name, value] of new FormData(form) as unknown as Iterable<[string, string]>) {
              data[name] = value;
            }
            payload.form = data;
          }
          break;
        }
      }
    }
    return payload;
  }

  /** The most frames to queue while the socket is closed. Older frames are dropped first. */
  private static readonly MAX_QUEUED_EVENTS = 64;

  /** Sends an event from node `id`, numbering it for the stale-write guard. */
  private send(id: number, event: string, payload: Record<string, unknown>): void {
    this.seq += 1;
    this.sentFrom.set(id, this.seq);
    this.sendRaw(JSON.stringify({ t: "event", node: id, event, seq: this.seq, payload }));
  }

  /** Sends `frame` now if the socket is open, and queues it otherwise. */
  private sendRaw(frame: string): void {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(frame);
      return;
    }
    this.outbox.push(frame);
    if (this.outbox.length > Jetlin.MAX_QUEUED_EVENTS) this.outbox.shift();
  }
}

declare global {
  interface Window {
    Jetlin: { connect: typeof connect; clientComponent: typeof clientComponent };
  }
}

// The page's inline script and the application's own scripts reach the runtime through this global.
window.Jetlin = { connect, clientComponent };
