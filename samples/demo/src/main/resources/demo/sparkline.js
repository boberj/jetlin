// The browser half of the Sparkline client component.
//
// Registered by name before the session connects. Jetlin sends props down and this sends events
// up; what it renders in between is entirely its own, and Jetlin never patches inside it.
// Counted so a browser test can prove the teardown really happens. A widget that is never told
// it is going holds on to its listeners and timers, and a page that re-renders leaks a set every
// time — which is invisible unless something is watching.
window.sparklineMounts = 0;
window.sparklineUnmounts = 0;

Jetlin.clientComponent("sparkline", {
  mount(element, props, push) {
    window.sparklineMounts += 1;
    const draw = (points) => {
      element.replaceChildren();
      points.forEach((value, index) => {
        const bar = document.createElement("div");
        bar.className = "bar";
        bar.style.height = `${value * 11}%`;
        bar.dataset.bar = String(index);
        // Clicking is reported to the server, which owns the numbers and decides what to do.
        bar.addEventListener("click", () => push("picked", { index }));
        element.appendChild(bar);
      });
    };

    draw(props.points ?? []);
    // The handle is handed back to update and unmount, so this needs no registry of its own.
    return { draw };
  },

  update(element, props, handle) {
    handle.draw(props.points ?? []);
  },

  unmount(element) {
    window.sparklineUnmounts += 1;
    // Nothing here holds a timer or a global listener, but the listeners on the bars go with the
    // children, and saying so is the habit that keeps a real widget from leaking.
    element.replaceChildren();
  },
});
