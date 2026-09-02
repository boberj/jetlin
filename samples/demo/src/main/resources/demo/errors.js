// Showing the user that something failed.
//
// Jetlin raises `jetlin:error` on the window and stops there: what an error looks like is an
// application's decision, not a framework's. This is one small answer to it.
//
// The message is deliberately generic — the server sends a fixed sentence, because an exception's
// own text carries paths and identifiers that are nobody's business in a browser. The real one is
// in the server log and in whatever JetlinConfig.onError forwards it to.
//
// The event is cancelable. Calling preventDefault() on a fatal one means "we will handle this",
// and Jetlin stops reloading the page. Merely listening does not count, which is deliberate: an
// application listening only to forward errors to its telemetry should still get the default
// recovery. This demo takes over only when the url says to, so both paths can be seen.
window.addEventListener("jetlin:error", (event) => {
  const { message, fatal } = event.detail;

  if (fatal && new URLSearchParams(location.search).has("handle")) {
    event.preventDefault();
    showDeadBanner(message);
    return;
  }

  const toast = document.createElement("div");
  toast.className = fatal ? "toast toast-fatal" : "toast";
  toast.dataset.test = fatal ? "toast-fatal" : "toast";
  toast.textContent = fatal ? `${message} Reloading…` : message;
  document.body.appendChild(toast);

  // A fatal error reloads the page from under us, so that one is left to be swept away with it.
  if (!fatal) setTimeout(() => toast.remove(), 4000);
});

/**
 * What is left after cancelling the default: a page that cannot change again.
 *
 * Worth being blunt about. Nothing here responds any more — the composition is gone and the socket
 * is closed — so the only honest thing to offer is a way to start over. Jetlin adds `jl-dead` to
 * the body for exactly this, so the rest of the page can be dimmed without any of it being guessed.
 */
function showDeadBanner(message) {
  const banner = document.createElement("div");
  banner.className = "banner";
  banner.dataset.test = "dead-banner";
  banner.textContent = `${message} This page is no longer live. `;

  const button = document.createElement("button");
  button.className = "btn";
  button.dataset.test = "dead-reload";
  button.textContent = "Start a new session";
  button.addEventListener("click", () => window.jetlin.reload());

  banner.appendChild(button);
  document.body.appendChild(banner);
}
