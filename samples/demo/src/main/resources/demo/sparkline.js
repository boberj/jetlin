// The browser half of the Sparkline client component.
//
// It's registered by name before the session connects. Jetlin sends props down, and this code sends
// events up. What it renders in between is its own, and Jetlin never patches inside it.

// Count mounts and unmounts, so a browser test can prove that teardown happens. A widget that's
// never told it's leaving holds on to its listeners and timers, and a page that re-renders leaks a
// set every time, which nobody notices unless something is counting.
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
        // Report the click to the server, which owns the numbers and decides what to do.
        bar.addEventListener("click", () => push("picked", { index }));
        element.appendChild(bar);
      });
    };

    draw(props.points ?? []);
    // Jetlin passes the handle back to update and unmount, so this code needs no registry of its own.
    return { draw };
  },

  update(element, props, handle) {
    handle.draw(props.points ?? []);
  },

  unmount(element) {
    window.sparklineUnmounts += 1;
    // Nothing here holds a timer or a global listener, and the bars' listeners go with the
    // children. Cleaning up anyway is the habit that keeps a real widget from leaking.
    element.replaceChildren();
  },
});
