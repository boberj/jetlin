// The browser half of the vessel map.
//
// This is the one block on the page that a server-rendered tree cannot own. Leaflet builds and
// mutates its own DOM continuously — tiles load, layers pan, the marker moves — and a framework
// that patches the document from the server has no business reaching inside that. So the server
// owns the props and hears the events, and everything between mount and unmount is Leaflet's.
//
// Registered before the session connects, so the implementation exists when the runtime takes up
// the markup it was served.
Jetlin.clientComponent("vessel-map", {
  mount(element, props, push) {
    if (typeof L === "undefined") {
      // Leaflet did not load. Say so rather than leaving an empty grey box that looks like a bug in
      // the framework, which is the failure this component is meant to demonstrate handling well.
      // Add to the classes rather than replacing them: the server put the box's size there, and
      // taking it away collapses the block instead of showing a message inside it.
      element.classList.add("flex", "items-center", "justify-center", "text-sm", "text-muted-foreground");
      element.textContent = "Map unavailable: Leaflet did not load.";
      return { update() {}, destroy() {} };
    }

    const map = L.map(element, { zoomControl: true, attributionControl: true });
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 18,
      attribution: "&copy; OpenStreetMap contributors",
    }).addTo(map);

    const marker = L.circleMarker([props.lat, props.lon], {
      radius: 7,
      color: "#166534",
      fillColor: "#22c55e",
      fillOpacity: 1,
      weight: 2,
    }).addTo(map);

    // A geofence the vessel is inside, drawn the way the original draws its current one.
    const fence = L.circle([props.lat, props.lon], {
      radius: 40000,
      color: "#f59e0b",
      fillOpacity: 0.05,
      weight: 2,
    }).addTo(map);

    map.setView([props.lat, props.lon], props.zoom ?? 6);

    // Clicking the vessel tells the server, which owns what that means.
    marker.on("click", () => push("picked", { lat: props.lat, lon: props.lon }));

    return {
      update(next) {
        marker.setLatLng([next.lat, next.lon]);
        fence.setLatLng([next.lat, next.lon]);
      },
      destroy() {
        map.remove();
      },
    };
  },

  update(element, props, handle) {
    handle.update(props);
  },

  // Leaflet holds listeners on window and a tile pipeline of its own; a map that is never told it
  // is going leaks both, once per navigation, invisibly.
  unmount(element, handle) {
    handle.destroy();
  },
});
