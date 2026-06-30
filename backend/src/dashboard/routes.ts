import { FastifyInstance } from "fastify";

/**
 * Mapa de alertas — dashboard mínimo servido por el backend.
 *
 * Página HTML autocontenida (Leaflet + basemap oscuro por CDN) que lee los SOS
 * geolocalizados del canal comunitario PÚBLICO `solicito-ayuda` — esos registros
 * llevan lat/lon en su payload (derivados de frames mesh verificados en /ingest).
 * Al usar un endpoint público no expone ninguna API key en el navegador; refresca
 * de forma incremental por `since`.
 *
 * Privacidad: este endpoint público entrega coordenadas DEGRADADAS (~1 km, ver
 * `channels/sanitize.ts`). La posición EXACTA de un SOS solo está disponible para
 * organismos regulados / de rescate vía GET /locations (read key) — nunca aquí.
 *
 * Estética: portada 1:1 del design system del app móvil (`android/.../ui/Theme.kt`
 * → DESIGN.md): "yellow + black brand voltage", dark-only, capa semántica de
 * emergencia (danger/warning/success/info). Tokens espejados como CSS vars abajo.
 *
 * helmet corre con contentSecurityPolicy:false (ver index.ts), así que el script
 * inline + CDN + tiles cargan sin bloqueo.
 */
const PAGE = `<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>GuacaMalla Net — Mapa de Alertas</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<style>
  /* ── Design tokens — espejo de GuacamayaPalette (android/.../ui/Theme.kt) ── */
  :root {
    color-scheme: dark;
    --brand:#FAFF69; --on-brand:#0A0A0A;
    --canvas:#0A0A0A; --surface-soft:#121212; --surface-card:#1A1A1A; --surface-elevated:#242424;
    --hairline:#2A2A2A; --hairline-strong:#3A3A3A;
    --ink:#FFFFFF; --body:#CCCCCC; --body-strong:#E6E6E6; --muted:#888888; --muted-soft:#5A5A5A;
    --danger:#FF453A; --danger-soft:#3A1512; --warning:#FF9F0A; --warning-soft:#3A2710;
    --success:#30D158; --info:#0A84FF; --info-soft:#0A1F3A;
    --r-sm:8px; --r-md:12px; --r-lg:16px;
  }
  * { box-sizing: border-box; }
  html, body {
    margin: 0; height: 100%; background: var(--canvas); color: var(--body);
    font-family: system-ui, -apple-system, Roboto, "Segoe UI", sans-serif;
    -webkit-font-smoothing: antialiased;
  }
  #map { position: absolute; inset: 0; background: var(--canvas); }

  /* Panel = SurfaceCard con hairline, radios y spacing del design system */
  #panel {
    position: absolute; z-index: 1000; top: 16px; left: 16px; width: 280px; max-width: calc(100vw - 32px);
    background: var(--surface-card); border: 1px solid var(--hairline); border-radius: var(--r-lg);
    box-shadow: 0 8px 32px rgba(0,0,0,.55); overflow: hidden;
  }
  .pad { padding: 16px 20px; }
  .head { display: flex; align-items: center; gap: 10px; border-bottom: 1px solid var(--hairline); }
  .mark {
    width: 34px; height: 34px; flex: none; border-radius: var(--r-md);
    background: var(--brand); color: var(--on-brand);
    display: grid; place-items: center; font-size: 19px; line-height: 1;
  }
  h1 { margin: 0; font-size: 17px; font-weight: 700; letter-spacing: -.3px; color: var(--ink); }
  .sub { margin: 1px 0 0; font-size: 11px; font-weight: 700; letter-spacing: 1.2px; text-transform: uppercase; color: var(--muted); }

  .stat { display: flex; align-items: baseline; gap: 8px; }
  #count { font-size: 34px; font-weight: 700; letter-spacing: -1px; color: var(--brand); line-height: 1; }
  .stat .label { font-size: 13px; color: var(--body); }
  .stat code { color: var(--body-strong); background: var(--surface-elevated); padding: 1px 6px; border-radius: var(--r-sm); font-size: 12px; }

  .legend { display: flex; gap: 16px; margin-top: 14px; font-size: 13px; color: var(--body); }
  .legend .item { display: flex; align-items: center; gap: 7px; }
  .dot { width: 11px; height: 11px; border-radius: 50%; box-shadow: 0 0 0 2px var(--surface-card); }
  .crit { background: var(--danger); } .norm { background: var(--info); }

  /* Nota de privacidad — callout info, capa semántica del design system */
  .note {
    margin-top: 16px; display: flex; gap: 10px; padding: 12px;
    background: var(--info-soft); border: 1px solid var(--hairline);
    border-left: 3px solid var(--info); border-radius: var(--r-md);
  }
  .note .ico { flex: none; font-size: 15px; line-height: 1.3; }
  .note .txt { font-size: 12px; line-height: 1.5; color: var(--body); }
  .note .txt b { color: var(--body-strong); font-weight: 600; }

  #status {
    padding: 10px 20px; border-top: 1px solid var(--hairline); background: var(--surface-soft);
    font-size: 11px; font-weight: 700; letter-spacing: 1.2px; text-transform: uppercase; color: var(--muted-soft);
  }
  #status.err { color: var(--danger); }

  /* Leaflet sobre tema oscuro */
  .leaflet-container { background: var(--canvas); }
  /* Basemap oscuro sin depender de un CDN de tiles oscuros: invertimos los
     tiles claros de OSM. El filtro afecta SOLO al pane de tiles — los dots
     (marker/overlay pane) conservan sus colores semánticos. */
  .leaflet-tile-pane { filter: invert(1) hue-rotate(180deg) brightness(.92) contrast(.95) saturate(.85); }
  .leaflet-popup-content-wrapper, .leaflet-popup-tip {
    background: var(--surface-elevated); color: var(--body); border: 1px solid var(--hairline-strong);
    box-shadow: 0 8px 32px rgba(0,0,0,.6); border-radius: var(--r-md);
  }
  .leaflet-popup-content { margin: 12px 14px; font-size: 13px; line-height: 1.5; }
  .leaflet-popup-content b { color: var(--ink); }
  .leaflet-popup-close-button { color: var(--muted) !important; }
  .leaflet-control-attribution { background: rgba(10,10,10,.7) !important; color: var(--muted-soft) !important; }
  .leaflet-control-attribution a { color: var(--muted) !important; }
  .leaflet-bar a { background: var(--surface-card) !important; color: var(--ink) !important; border-color: var(--hairline) !important; }
  .pop-tipo { font-weight: 700; }
  .pop-crit { color: var(--danger); font-weight: 700; }
  .pop-coord { color: var(--body); } .pop-approx { color: var(--warning); font-size: 11px; }
  .pop-meta { color: var(--muted); font-size: 11px; }
</style>
</head>
<body>
<div id="map"></div>
<div id="panel">
  <div class="pad head">
    <div class="mark">🦜</div>
    <div>
      <h1>Mapa de Alertas</h1>
      <p class="sub">GuacaMalla Net</p>
    </div>
  </div>
  <div class="pad">
    <div class="stat">
      <span id="count">0</span>
      <span class="label">SOS activos en <code>solicito-ayuda</code></span>
    </div>
    <div class="legend">
      <span class="item"><span class="dot crit"></span>Crítico</span>
      <span class="item"><span class="dot norm"></span>Normal</span>
    </div>
    <div class="note">
      <span class="ico">🔒</span>
      <span class="txt">Ubicaciones <b>aproximadas (~1 km)</b>. La posición exacta de una alerta SOS
      solo es visible para <b>organismos regulados o de rescate</b> autorizados.</span>
    </div>
  </div>
  <div id="status">cargando…</div>
</div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
  var MAP_DEFAULT = [10.4806, -66.9036]; // Caracas
  var map = L.map("map", { zoomControl: true }).setView(MAP_DEFAULT, 12);
  // Tiles estándar de OSM (sin CDN extra); el tema oscuro lo da el filtro CSS
  // sobre .leaflet-tile-pane, así no dependemos de un basemap oscuro externo.
  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19, attribution: "&copy; OpenStreetMap"
  }).addTo(map);

  var seen = {}, bounds = [], lastTs = 0, count = 0, fitted = false;
  var TYPE = { medical:"Médica", distress:"Auxilio", food:"Comida", water:"Agua",
               shelter:"Refugio", fire:"Fuego", violence:"Violencia", other:"Otro" };

  function addMarker(rec) {
    var p = rec.payload || {};
    if (typeof p.lat !== "number" || typeof p.lon !== "number") return;
    if (seen[rec.id]) return; seen[rec.id] = true;
    var crit = !!p.critical;
    var m = L.circleMarker([p.lat, p.lon], {
      radius: crit ? 11 : 8, color: "#0A0A0A", weight: 2,
      fillColor: crit ? "#FF453A" : "#0A84FF", fillOpacity: 0.95
    }).addTo(map);
    var when = new Date(rec.timestamp).toLocaleString();
    var tipo = TYPE[p.sosType] || p.sosType || "Otro";
    m.bindPopup(
      '<span class="pop-tipo">' + tipo + "</span>" +
      (crit ? ' <span class="pop-crit">⚠ CRÍTICO</span>' : "") +
      '<br><span class="pop-coord">' + p.lat.toFixed(2) + ", " + p.lon.toFixed(2) + "</span>" +
      ' <span class="pop-approx">~1 km</span>' +
      '<br><span class="pop-meta">' + when + "</span>" +
      '<br><span class="pop-meta">' + (rec.author || "") + "</span>"
    );
    bounds.push([p.lat, p.lon]); count++;
    if (rec.timestamp > lastTs) lastTs = rec.timestamp;
  }

  function load(since) {
    fetch("/channels/solicito-ayuda/records?since=" + since)
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (Array.isArray(data)) data.forEach(addMarker);
        document.getElementById("count").textContent = count;
        var s = document.getElementById("status");
        s.classList.remove("err");
        s.textContent = "actualizado " + new Date().toLocaleTimeString();
        if (!fitted && bounds.length > 0) {
          map.fitBounds(bounds, { padding: [40, 40], maxZoom: 14 }); fitted = true;
        }
      })
      .catch(function (e) {
        var s = document.getElementById("status");
        s.classList.add("err");
        s.textContent = "error de red";
        console.error(e);
      });
  }

  load(0);
  setInterval(function () { load(lastTs); }, 10000); // refresco incremental
</script>
</body>
</html>`;

export async function dashboardRoutes(fastify: FastifyInstance) {
  // Mapa de alertas. Público (lee solo el canal comunitario público).
  fastify.get("/dashboard", async (_request, reply) => {
    reply.type("text/html").send(PAGE);
  });
}
