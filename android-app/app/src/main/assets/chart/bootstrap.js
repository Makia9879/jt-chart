(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.JTChartBootstrap = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  // Native WebViewCompat.postWebMessage has no web document source, so Chromium exposes
  // an empty event.origin. Kotlin already targets the exact appassets origin and blocks
  // all other top-level navigation; accepting any non-empty web origin would weaken that gate.
  function acceptsNativePortEvent(event, alreadyConnected) {
    return !alreadyConnected
      && event
      && event.origin === ""
      && event.ports
      && event.ports.length === 1;
  }

  function viewportHeightCss(innerHeight) {
    return Number.isFinite(innerHeight) && innerHeight > 0 ? `${Math.round(innerHeight)}px` : null;
  }

  return { acceptsNativePortEvent, viewportHeightCss };
});
