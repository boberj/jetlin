/**
 * Jetlin browser runtime.
 *
 * Deliberately small. It does two things: apply the mutation ops the server sends, and report DOM
 * events back. It holds no application state and makes no rendering decisions, so there is no
 * reconciliation step here — the server has already worked out which nodes to touch.
 */

type PropValue = { t: "s"; v: string } | { t: "b"; v: boolean };

type ClientTarget = { t: "self" } | { t: "closest"; className: string };

/**
 * Something the browser does for itself when an event fires.
 *
 * A closed set of verbs rather than a script: a disclosure or a menu needs no server, and a round
 * trip to open one is latency spent on nothing. Anything needing real logic stays on the server.
 */
type ClientCommand =
  | { t: "toggle"; name: string; target?: ClientTarget }
  | { t: "add"; name: string; target?: ClientTarget }
  | { t: "remove"; name: string; target?: ClientTarget }
  | { t: "focus"; target?: ClientTarget }
  | { t: "blur"; target?: ClientTarget };

interface ListenerSpec {
  extract?: string[];
  commands?: ClientCommand[];
  /** Absent means true: the server is told about this event unless it said otherwise. */
  notify?: boolean;
  debounceMs?: number;
  throttleMs?: number;
  preventDefault?: boolean;
  stopPropagation?: boolean;
}

type NodeSpec =
  | {
      t: "e";
      id: number;
      tag: string;
      attrs?: Record<string, string>;
      props?: Record<string, PropValue>;
      listeners?: Record<string, ListenerSpec>;
      children?: NodeSpec[];
    }
  | { t: "t"; id: number; text: string };

type Op =
  | { t: "ins"; parent: number; index: number; node: NodeSpec }
  | { t: "rm"; parent: number; index: number; count: number }
  | { t: "mv"; parent: number; from: number; to: number; count: number }
  | { t: "attr"; id: number; name: string; value: string | null }
  | { t: "prop"; id: number; name: string; value: PropValue }
  | { t: "text"; id: number; text: string }
  | { t: "on"; id: number; event: string; spec: ListenerSpec }
  | { t: "off"; id: number; event: string };

type ServerMessage =
  | { t: "patch"; rev: number; ack: number; ops: Op[] }
  | { t: "ready"; rev: number }
  | { t: "reset"; rev: number; children: NodeSpec[] }
  | { t: "nav"; url: string; replace?: boolean; title?: string }
  | { t: "error"; message: string; fatal?: boolean };

const ROOT_ID = 0;

/**
 * Marks where a text child with no content belongs.
 *
 * The server writes two kinds of comment. This one stands in for a node the parser would otherwise
 * not produce at all, so the client has to turn it back into a text node. The other separates two
 * adjacent text children, and needs no name here: its whole job was done by the HTML parser, which
 * kept them as two nodes instead of merging them into one.
 */
const EMPTY_TEXT_MARKER = "0";

/** `index:id` pairs naming the text children of an element, which carry no attributes of their own. */
/** Where a command lands: the element itself, or the nearest ancestor carrying a class. */
function resolveTarget(element: Element, target: ClientTarget | undefined): Element | null {
  if (!target || target.t === "self") return element;
  return element.closest(`.${CSS.escape(target.className)}`);
}

function parseTextMarkers(value: string | null): Map<number, number> {
  const markers = new Map<number, number>();
  if (!value) return markers;
  for (const entry of value.split(",")) {
    const [index, id] = entry.split(":");
    markers.set(Number(index), Number(id));
  }
  return markers;
}

/** Event name a component's pushes travel under, matching COMPONENT_EVENT on the server. */
const COMPONENT_EVENT = "jl:component";
const COMPONENT_ATTRIBUTE = "data-jl-component";
const COMPONENT_PROPS_ATTRIBUTE = "data-jl-props";

/**
 * An implementation the application registers for [ClientComponent] to render.
 *
 * [mount] may return a handle -- an editor, a chart, whatever it built -- which is given back to
 * [update] and [unmount] so the implementation need keep no registry of its own.
 */
export interface ClientComponentFactory<T = unknown> {
  mount(element: HTMLElement, props: Record<string, unknown>, push: PushFn): T;
  /** Called when the server sends new props. Without it, changed props remount the component. */
  update?(element: HTMLElement, props: Record<string, unknown>, handle: T): void;
  /** Called before the element leaves the page. The place to release listeners and timers. */
  unmount?(element: HTMLElement, handle: T): void;
}

export type PushFn = (event: string, payload?: Record<string, unknown>) => void;

const components = new Map<string, ClientComponentFactory<never>>();

/**
 * Registers [factory] under [name], for a ClientComponent on the server to ask for by that name.
 *
 * A registry rather than a lookup by global name: what the server sends is a key into a table the
 * application populated, and can never be a piece of code to run.
 */
export function clientComponent<T>(name: string, factory: ClientComponentFactory<T>): void {
  components.set(name, factory as ClientComponentFactory<never>);
}

interface Mounted {
  factory: ClientComponentFactory<never>;
  element: HTMLElement;
  handle: never;
}

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

export interface JetlinOptions {
  url?: string;
  token: string;
  container?: HTMLElement;
  /**
   * Whether to keep the server-rendered markup instead of asking for the tree again.
   *
   * On by default. Setting it to false forces the full-render path, which is worth having when
   * diagnosing a suspected adoption bug: it is one flag to determine whether adoption is implicated.
   */
  adopt?: boolean;
}

export function connect(options: JetlinOptions): Jetlin {
  return new Jetlin(options);
}

class Jetlin {
  private readonly container: HTMLElement;
  private readonly url: string;
  private readonly token: string;

  private socket: WebSocket | null = null;
  private reconnectAttempt = 0;
  /**
   * Whether the next hello should ask to keep the markup already on the page.
   *
   * True for at most one socket: only the browser holding markup this composition rendered can
   * adopt it, and after a disconnect the server stops recording, so its tree moves on unseen.
   */
  private pendingAdopt = false;
  /** Set when the server says the session is unrecoverable; stops the reconnect loop. */
  private fatal = false;

  /** Server node id -> DOM node. */
  private nodes = new Map<number, Node>();
  /** DOM node -> server node id. */
  private ids = new WeakMap<Node, number>();
  /**
   * Logical children per element, mirroring the server's child list.
   *
   * The DOM's own childNodes cannot be used for indexing: the browser merges adjacent text nodes
   * and third-party scripts inject siblings. Keeping our own array means every index in an op means
   * exactly what the server meant by it.
   */
  private children = new Map<number, Node[]>();
  private listeners = new Map<number, Record<string, ListenerSpec>>();

  /** Live client components, by node id, so they can be updated and torn down. */
  private mounted = new Map<number, Mounted>();

  /** Event types already delegated on the container. */
  private delegated = new Set<string>();

  private seq = 0;
  /** Highest event seq sent from each node, for the stale-write guard. */
  private sentFrom = new Map<number, number>();
  /**
   * Events produced while the socket is not open.
   *
   * First paint is interactive HTML that exists before the WebSocket finishes connecting, so a
   * fast click can genuinely land in that window; dropping it would lose a real user action. The
   * queue is bounded because a long disconnection should not accumulate work forever.
   */
  private outbox: string[] = [];
  private timers = new Map<string, number>();
  private lastFired = new Map<string, number>();

  constructor(options: JetlinOptions) {
    this.container = options.container ?? document.getElementById("jetlin-root")!;
    this.token = options.token;
    const scheme = location.protocol === "https:" ? "wss:" : "ws:";
    this.url = options.url ?? `${scheme}//${location.host}/jetlin`;
    this.register(ROOT_ID, this.container);
    this.children.set(ROOT_ID, []);

    // Back and forward move the address bar first; the server follows. It is told where the browser
    // went rather than asked for permission, so history stays authoritative.
    window.addEventListener("popstate", () => {
      this.sendRaw(JSON.stringify({ t: "nav", url: location.pathname + location.search }));
    });

    // Before connecting, so the hello can say whether the tree still needs sending.
    this.pendingAdopt = options.adopt !== false && this.adoptMarkup();

    this.open();
  }

  // ---------------------------------------------------------------- transport

  /**
   * Drops the socket. The runtime then reconnects on its normal backoff and the server hands back
   * the same composition, so this doubles as the way to exercise reconnection in tests.
   */
  public disconnect(): void {
    this.socket?.close();
  }

  private open(): void {
    const socket = new WebSocket(this.url);
    this.socket = socket;

    socket.onopen = () => {
      this.reconnectAttempt = 0;
      document.body.classList.remove("jl-disconnected");
      // The address bar is authoritative: if the session had to be woken from storage, the user may
      // have moved with the back button while this socket was down.
      socket.send(
        JSON.stringify({
          t: "hello",
          token: this.token,
          url: location.pathname + location.search,
          adopt: this.pendingAdopt,
        }),
      );
      // Spent: a later socket is holding markup the server has since edited without watching.
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

  private scheduleReconnect(): void {
    if (this.fatal) return;
    const delay = Math.min(1000 * 2 ** this.reconnectAttempt, 10000);
    this.reconnectAttempt += 1;
    setTimeout(() => this.open(), delay);
  }

  private receive(message: ServerMessage): void {
    switch (message.t) {
      case "reset":
        this.reset(message.children);
        break;
      case "patch":
        for (const op of message.ops) this.apply(op, message.ack);
        break;
      case "ready":
        // The markup we adopted stands. Anything that changed while we were connecting follows as
        // an ordinary patch, so there is nothing to do here.
        break;
      case "nav":
        // The DOM for this location arrived in the patch immediately before, so the address bar is
        // updated last and never points at content that is not on screen yet.
        if (message.replace) history.replaceState({ jetlin: true }, "", message.url);
        else history.pushState({ jetlin: true }, "", message.url);
        if (message.title) document.title = message.title;
        break;
      case "error":
        console.error("[jetlin]", message.message);
        // The session is gone for good — hibernated past its expiry, or never existed. Reconnecting
        // cannot help, so start a clean one rather than leaving a page that looks live but is not.
        if (message.fatal) {
          this.fatal = true;
          this.socket?.close();
          location.reload();
        }
        break;
    }
  }

  // ----------------------------------------------------------------- adoption

  /**
   * Indexes the markup the server already sent instead of waiting to be given the tree again.
   *
   * The DOM the browser parsed, laid out and painted is kept as it is. That saves transmitting the
   * tree twice, but more importantly it leaves alone whatever happened to the page in the meantime —
   * focus, a text selection, a scroll position, an element another script inserted — all of which a
   * rebuild would throw away.
   *
   * Best-effort by design. Any disagreement with the markup abandons the attempt and asks for a full
   * render, so a marker the server never wrote or a proxy that rewrote the HTML costs an
   * optimization rather than correctness.
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

  private adoptElement(element: Element, id: number): void {
    this.register(id, element);

    const specs = element.getAttribute("data-jl-on");
    if (specs) {
      const parsed = JSON.parse(specs) as Record<string, ListenerSpec>;
      this.listeners.set(id, parsed);
      for (const event of Object.keys(parsed)) this.delegate(event);
    }

    // Raw markup belongs to whoever wrote it. The composition has no children here to index, and
    // walking in would try to claim nodes the server has never heard of.
    if (element.hasAttribute("data-jl-raw")) {
      this.children.set(id, []);
      return;
    }

    // Same for a client component, which additionally has to be started: the markup it was served
    // is an empty shell, and the implementation fills it.
    if (element.hasAttribute(COMPONENT_ATTRIBUTE)) {
      this.mount(id, element as HTMLElement);
      return;
    }

    const textIds = parseTextMarkers(element.getAttribute("data-jl-t"));
    const logical: Node[] = [];

    // Snapshotted, because materializing an empty text node mutates the list being walked.
    for (const node of Array.from(element.childNodes)) {
      if (node.nodeType === Node.COMMENT_NODE) {
        if ((node as Comment).data === EMPTY_TEXT_MARKER) {
          // A text child with no content leaves nothing behind for the parser to produce, so the
          // server wrote a marker where the node should be and we put one there.
          const empty = document.createTextNode("");
          element.replaceChild(empty, node);
          logical.push(empty);
        }
        // Separators have already served their purpose, and other comments are not ours. Neither
        // is a child.
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

    // Every declared text child has to have been found. A mismatch means the markup and the server's
    // idea of this element have diverged, and every index below would be suspect.
    if (textCount !== textIds.size) {
      throw new Error(`node ${id} declares ${textIds.size} text children but ${textCount} are present`);
    }

    this.children.set(id, logical);
  }

  // ------------------------------------------------------------------ patches

  private reset(children: NodeSpec[]): void {
    // Every component goes before the DOM holding it does, or their listeners and timers outlive
    // the page they belonged to.
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

  private apply(op: Op, ack: number): void {
    switch (op.t) {
      case "ins": {
        const parent = this.nodes.get(op.parent) as Element;
        const siblings = this.children.get(op.parent)!;
        const node = this.build(op.node);
        // Reference must be read before splicing, or it points at the wrong sibling.
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
        // `to` is an index in the list as it was *before* the move, so when items shift left the
        // destination has to be adjusted by the number removed. This mirrors what the server-side
        // applier does; if the two ever disagree, lists silently reorder differently on each side.
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
        // The stale-write guard. If this node has produced an event the server had not yet seen
        // when it built this patch, the server's idea of `value` is older than what the user has
        // typed, and applying it would eat keystrokes.
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

  private build(spec: NodeSpec): Node {
    if (spec.t === "t") {
      const text = document.createTextNode(spec.text);
      this.register(spec.id, text);
      return text;
    }

    const element = document.createElement(spec.tag);
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

  private register(id: number, node: Node): void {
    this.nodes.set(id, node);
    this.ids.set(node, id);
  }

  /**
   * Hands an element over to the implementation registered under its component name.
   *
   * Its children are recorded as empty, so nothing Jetlin does afterwards will index or patch
   * inside it: whatever the implementation renders is its own, and the server has never heard of it.
   */
  private mount(id: number, element: HTMLElement): void {
    const name = element.getAttribute(COMPONENT_ATTRIBUTE)!;
    this.children.set(id, []);

    const factory = components.get(name);
    if (!factory) {
      // Left empty rather than fatal: a missing registration is a build problem in one corner of
      // the page, and taking the whole session down over it helps nobody.
      console.warn(`jetlin: no client component registered as "${name}"`);
      return;
    }

    const push: PushFn = (event, payload) =>
      this.send(id, COMPONENT_EVENT, { data: { event, payload: payload ?? {} } });

    const handle = factory.mount(element, parseProps(element), push) as never;
    this.mounted.set(id, { factory, element, handle });
  }

  /** Passes new props along, remounting when the implementation offers no cheaper way to take them. */
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
   * Tears a component down before its element leaves the page.
   *
   * Not optional. A third-party widget that is never told it is going holds on to listeners, timers
   * and observers, and a list that re-renders leaks a set of them every time.
   */
  private unmount(id: number): void {
    const live = this.mounted.get(id);
    if (!live) return;
    this.mounted.delete(id);
    try {
      live.factory.unmount?.(live.element, live.handle);
    } catch (error) {
      // One misbehaving widget must not stop the rest of the page being torn down correctly.
      console.warn("jetlin: a client component threw while unmounting", error);
    }
  }

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
   * One capture-phase listener per event type on the container.
   *
   * Capture rather than bubble so that events which do not bubble — focus, blur — still reach the
   * delegate, and so a single registration survives any amount of subtree churn.
   */
  private delegate(event: string): void {
    if (this.delegated.has(event)) return;
    this.delegated.add(event);
    this.container.addEventListener(event, (e) => this.onEvent(e), { capture: true });
  }

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
   * Runs the commands a listener declared, resolving each one's target from [element].
   *
   * Deliberately before any debounce or throttle, and before anything is sent: these exist so the
   * page reacts at once, and delaying them by the same interval that spares the server a round trip
   * would defeat the point of having them.
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

  private fire(id: number, element: Element, event: Event, spec: ListenerSpec): void {
    if (spec.preventDefault) event.preventDefault();
    if (spec.stopPropagation) event.stopPropagation();

    if (spec.commands?.length) this.runCommands(element, spec.commands);

    // Nothing on the server is waiting for this one. Declared with commands and no handler, so the
    // browser has already done everything there was to do.
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

  private static readonly MAX_QUEUED_EVENTS = 64;

  private send(id: number, event: string, payload: Record<string, unknown>): void {
    this.seq += 1;
    this.sentFrom.set(id, this.seq);
    this.sendRaw(JSON.stringify({ t: "event", node: id, event, seq: this.seq, payload }));
  }

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

window.Jetlin = { connect, clientComponent };
