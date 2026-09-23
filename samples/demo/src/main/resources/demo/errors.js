// Shows the user that something failed.
//
// Jetlin dispatches `jetlin:error` on the window and stops there, because what an error looks like
// is the application's decision, not the framework's. This is one small way to show it.
//
// The message is deliberately generic. The server sends a fixed sentence, because an exception's
// own text contains paths and identifiers that don't belong in a browser. The real exception is in
// the server log and in whatever JetlinConfig.onError forwards it to.
//
// The event is cancelable. Calling preventDefault() on a fatal error means "the page handles this,"
// and Jetlin doesn't reload the page. Listening alone doesn't count, deliberately: an application
// that listens only to forward errors to its telemetry should still get the default recovery. This
// demo takes over only when the URL has a `handle` query parameter, so you can see both paths.
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

  // A fatal error reloads the page, which removes the toast along with everything else.
  if (!fatal) setTimeout(() => toast.remove(), 4000);
});

/**
 * Shows a banner after the page cancels a fatal error, because the page can't change again.
 *
 * Nothing on the page responds anymore: the composition is gone, and the socket is closed. So the
 * only honest thing to offer is a way to start over. Jetlin adds the `jl-dead` class to the body for
 * this case, so the style sheet can dim the rest of the page.
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
