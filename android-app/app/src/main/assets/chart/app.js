(function () {
  "use strict";

  const BOOTSTRAP_TYPE = "chart.bootstrap";
  const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
  let activePort = null;
  let activeRuntime = null;

  function syncViewportHeight() {
    const height = JTChartBootstrap.viewportHeightCss(window.innerHeight);
    if (!height) return;
    document.documentElement.style.height = height;
    document.body.style.height = height;
    document.getElementById("chart-root").style.height = height;
  }

  syncViewportHeight();
  window.addEventListener("resize", syncViewportHeight);

  function parseBootstrap(data) {
    if (typeof data !== "string") return null;
    try {
      const message = JSON.parse(data);
      if (message
        && message.v === 1
        && message.type === BOOTSTRAP_TYPE
        && UUID_PATTERN.test(message.pageInstanceId || "")) return message;
    } catch (_error) {
      return null;
    }
    return null;
  }

  window.addEventListener("message", (event) => {
    if (!JTChartBootstrap.acceptsNativePortEvent(event, Boolean(activePort))) return;
    const bootstrap = parseBootstrap(event.data);
    if (!bootstrap) return;
    syncViewportHeight();
    const port = event.ports[0];
    const renderer = JTChartRenderer.createChartRenderer({
      LightweightCharts,
      priceContainer: document.getElementById("price-chart"),
      oscillatorContainer: document.getElementById("oscillator-chart"),
      overlayCanvas: document.getElementById("bear-overlay"),
      ResizeObserver,
      requestAnimationFrame: window.requestAnimationFrame.bind(window),
    });
    const runtime = JTChartBridge.createRuntime({
      pageInstanceId: bootstrap.pageInstanceId,
      renderer,
      send(message) { port.postMessage(JSON.stringify(message)); },
    });
    port.onmessage = (portEvent) => runtime.receive(portEvent.data);
    port.start();
    activePort = port;
    activeRuntime = runtime;
  });

  window.addEventListener("pagehide", () => {
    if (activeRuntime) activeRuntime.dispose();
    if (activePort) activePort.close();
    activeRuntime = null;
    activePort = null;
    window.removeEventListener("resize", syncViewportHeight);
  });
})();
