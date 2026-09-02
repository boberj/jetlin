// Showing the user that something failed.
//
// Jetlin raises `jetlin:error` on the window and stops there: what an error looks like is an
// application's decision, not a framework's. This is one small answer to it.
//
// The message is deliberately generic — the server sends a fixed sentence, because an exception's
// own text carries paths and identifiers that are nobody's business in a browser. The real one is
// in the server log and in whatever JetlinConfig.onError forwards it to.
window.addEventListener("jetlin:error", (event) => {
  const { message, fatal } = event.detail;

  const toast = document.createElement("div");
  toast.className = fatal ? "toast toast-fatal" : "toast";
  toast.dataset.test = fatal ? "toast-fatal" : "toast";
  toast.textContent = fatal ? `${message} Reloading…` : message;
  document.body.appendChild(toast);

  // A fatal error reloads the page from under us, so that one is left to be swept away with it.
  if (!fatal) setTimeout(() => toast.remove(), 4000);
});
