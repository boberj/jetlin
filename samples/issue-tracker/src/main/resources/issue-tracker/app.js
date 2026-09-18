// The issue tracker's browser-side code: the three things a server cannot do from where it sits.
//
// Each one ends by handing a plain DOM event back to the page, where Jetlin's own delegated
// listeners pick it up and report it like any click or keystroke. So this file decides *when*
// something happens, and never *what* happens — that is still Kotlin, and still testable without a
// browser.

(() => {
  const isTyping = (target) =>
    target instanceof HTMLElement &&
    (target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName));

  /** Clicks one of the hidden buttons the Shell renders for shortcuts. */
  const trigger = (name) => {
    const button = document.querySelector(`[data-shortcut="${name}"]`);
    if (button) button.click();
  };

  // ---------------------------------------------------------------------------------- shortcuts
  //
  // There is no element to listen on for a key pressed anywhere on the page, so the key is read
  // here and turned into a click on a button that names the action.

  let pendingG = 0;

  document.addEventListener("keydown", (event) => {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
      event.preventDefault();
      trigger("palette");
      return;
    }
    if (event.key === "Escape") {
      trigger("dismiss");
      return;
    }
    if (event.metaKey || event.ctrlKey || event.altKey || isTyping(event.target)) return;

    if (pendingG && Date.now() - pendingG < 1000) {
      pendingG = 0;
      const target = { i: "go-my-issues", b: "go-board", p: "go-projects" }[event.key.toLowerCase()];
      if (target) {
        event.preventDefault();
        trigger(target);
      }
      return;
    }

    switch (event.key.toLowerCase()) {
      case "c":
        event.preventDefault();
        trigger("create");
        break;
      case "g":
        pendingG = Date.now();
        break;
    }
  });

  // ---------------------------------------------------------------------------------- autofocus
  //
  // `autofocus` only applies to the page as it was first loaded. A dialog patched in later is
  // marked with data-autofocus instead, and focused here the moment it arrives.

  const focusWithin = (node) => {
    if (!(node instanceof Element)) return;
    const target = node.matches("[data-autofocus]") ? node : node.querySelector("[data-autofocus]");
    if (target) target.focus();
  };

  new MutationObserver((mutations) => {
    for (const mutation of mutations) mutation.addedNodes.forEach(focusWithin);
  }).observe(document.body, { childList: true, subtree: true });

  // ------------------------------------------------------------------------------ drag and drop
  //
  // The server cannot hear `dragover`, and should not have to: it needs one fact, at the end. Each
  // board column carries a hidden input, and a drop writes the card's issue id into it and raises
  // `input` — which reaches the column's server handler exactly as typing would.
  //
  // Highlights are attributes the composition never writes, so a patch arriving mid-drag (another
  // user moving a card) cannot wipe them. Both carry a value the stylesheet reads: a column is
  // "foreign" when the card being held is not its own, and the card left behind is "away" once it
  // is over such a column — which is when its slot stops meaning anything and closes up.

  let draggedId = null;
  let sourceStatus = null;
  let cardState = "";

  const columnOf = (node) => (node instanceof Element ? node.closest("[data-drop-status]") : null);

  /** Looked up rather than held, since a patch can replace the card's node mid-drag. */
  const draggedCard = () => (draggedId ? document.querySelector(`[data-issue-id="${draggedId}"]`) : null);

  /** Remembered as well as written, since the first write is deferred past the drag image. */
  const markCard = (state) => {
    cardState = state;
    draggedCard()?.setAttribute("data-dragging", state);
  };

  const clearHighlights = () => {
    document.querySelectorAll("[data-drag-over]").forEach((el) => el.removeAttribute("data-drag-over"));
    document.querySelectorAll("[data-dragging]").forEach((el) => el.removeAttribute("data-dragging"));
  };

  document.addEventListener("dragstart", (event) => {
    const card = event.target instanceof Element && event.target.closest("[data-issue-id]");
    if (!card) return;
    draggedId = card.getAttribute("data-issue-id");
    sourceStatus = columnOf(card)?.getAttribute("data-drop-status") ?? null;
    cardState = "";
    event.dataTransfer.effectAllowed = "move";
    event.dataTransfer.setData("text/plain", draggedId);
    // The card that follows the cursor is a snapshot the browser takes of this one as the handler
    // returns. Emptying the card into a slot has to wait until after that, or the snapshot is of
    // the empty slot and nothing legible is being carried. `dragover` may land first and choose
    // "away", so what is written a frame later is whatever state the drag has reached by then.
    requestAnimationFrame(() => markCard(cardState));
  });

  document.addEventListener("dragover", (event) => {
    if (!draggedId) return;
    const column = columnOf(event.target);
    if (!column) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = "move";
    const state = column.getAttribute("data-drop-status") === sourceStatus ? "" : "foreign";
    if (column.getAttribute("data-drag-over") === state) return;
    document.querySelectorAll("[data-drag-over]").forEach((el) => el.removeAttribute("data-drag-over"));
    column.setAttribute("data-drag-over", state);
    markCard(state === "foreign" ? "away" : "");
  });

  document.addEventListener("dragleave", (event) => {
    const column = columnOf(event.target);
    if (!column || column.contains(event.relatedTarget)) return;
    column.removeAttribute("data-drag-over");
    // Off the board entirely: the card is headed back where it came from, so its slot returns.
    // Moving between columns leaves via the one and enters the other, and is left to `dragover`.
    if (!columnOf(event.relatedTarget)) markCard("");
  });

  document.addEventListener("drop", (event) => {
    const column = event.target instanceof Element && event.target.closest("[data-drop-status]");
    const id = draggedId;
    draggedId = null;
    sourceStatus = null;
    clearHighlights();
    if (!column || !id) return;
    event.preventDefault();
    const input = column.querySelector("[data-drop-input]");
    if (!input) return;
    input.value = id;
    input.dispatchEvent(new Event("input", { bubbles: true }));
  });

  document.addEventListener("dragend", () => {
    draggedId = null;
    sourceStatus = null;
    clearHighlights();
  });
})();
