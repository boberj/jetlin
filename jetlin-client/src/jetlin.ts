/**
 * Jetlin browser runtime.
 *
 * Deliberately small. It does two things: apply the mutation ops the server sends, and report DOM
 * events back. It holds no application state and makes no rendering decisions, so there is no
 * reconciliation step here — the server has already worked out which nodes to touch.
 */

type PropValue = { t: "s"; v: string } | { t: "b"; v: boolean };

interface ListenerSpec {
  extract?: string[];
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
  | { t: "reset"; rev: number; children: NodeSpec[] }
  | { t: "nav"; url: string; replace?: boolean; title?: string }
  | { t: "error"; message: string; fatal?: boolean };

const ROOT_ID = 0;

export interface JetlinOptions {
  url?: string;
  token: string;
  container?: HTMLElement;
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
      socket.send(JSON.stringify({ t: "hello", token: this.token }));
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
      case "nav":
        // The DOM for this location arrived in the patch immediately before, so the address bar is
        // updated last and never points at content that is not on screen yet.
        if (message.replace) history.replaceState({ jetlin: true }, "", message.url);
        else history.pushState({ jetlin: true }, "", message.url);
        if (message.title) document.title = message.title;
        break;
      case "error":
        console.error("[jetlin]", message.message);
        break;
    }
  }

  // ------------------------------------------------------------------ patches

  private reset(children: NodeSpec[]): void {
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

    const kids = (spec.children ?? []).map((child) => this.build(child));
    this.children.set(spec.id, kids);
    for (const kid of kids) element.appendChild(kid);
    return element;
  }

  private register(id: number, node: Node): void {
    this.nodes.set(id, node);
    this.ids.set(node, id);
  }

  private forget(node: Node): void {
    const id = this.ids.get(node);
    if (id === undefined) return;
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
          this.fire(id, event, spec);
          return;
        }
      }
      node = node.parentNode;
    }
  }

  private fire(id: number, event: Event, spec: ListenerSpec): void {
    if (spec.preventDefault) event.preventDefault();
    if (spec.stopPropagation) event.stopPropagation();

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
    Jetlin: { connect: typeof connect };
  }
}

window.Jetlin = { connect };
