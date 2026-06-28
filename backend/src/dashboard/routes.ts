import { FastifyInstance } from "fastify";

/**
 * Mapa de alertas — dashboard mínimo servido por el backend.
 *
 * Página HTML autocontenida (Leaflet + OpenStreetMap por CDN) que lee los SOS
 * geolocalizados del canal comunitario PÚBLICO `solicito-ayuda` — esos registros
 * llevan lat/lon en su payload (derivados de frames mesh verificados en /ingest).
 * Al usar un endpoint público no expone ninguna API key en el navegador; refresca
 * de forma incremental por `since`. Para un mapa con auth-gated location history
 * usar GET /locations (read key) — aquí no hace falta.
 *
 * helmet corre con contentSecurityPolicy:false (ver index.ts), así que el script
 * inline + CDN + tiles de OSM cargan sin bloqueo.
 */
const PAGE = `<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>Guacamaya Net — Mapa de Alertas</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<style>
  :root { color-scheme: light dark; }
  html, body { margin: 0; height: 100%; font-family: system-ui, -apple-system, sans-serif; }
  #map { position: absolute; inset: 0; }
  #panel {
    position: absolute; z-index: 1000; top: 12px; left: 12px;
    background: rgba(255,255,255,.94); border-radius: 10px; padding: 10px 14px;
    box-shadow: 0 2px 10px rgba(0,0,0,.25); font-size: 13px; line-height: 1.5; max-width: 240px;
  }
  #panel h1 { margin: 0 0 4px; font-size: 15px; }
  #count { font-weight: 700; }
  .dot { display:inline-block; width:10px; height:10px; border-radius:50%; vertical-align:middle; margin-right:6px; }
  .crit { background:#e11d48; } .norm { background:#2563eb; }
  #status { color:#888; font-size:11px; }
</style>
</head>
<body>
<div id="map"></div>
<div id="panel">
  <h1>🦜 Mapa de Alertas</h1>
  <div><span id="count">0</span> SOS en <code>solicito-ayuda</code></div>
  <div><span class="dot crit"></span>Crítico &nbsp; <span class="dot norm"></span>Normal</div>
  <div id="status">cargando…</div>
</div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
  var MAP_DEFAULT = [14.6349, -90.5069]; // Ciudad de Guatemala
  var map = L.map("map").setView(MAP_DEFAULT, 12);
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
      radius: 9, color: "#fff", weight: 2,
      fillColor: crit ? "#e11d48" : "#2563eb", fillOpacity: 0.9
    }).addTo(map);
    var when = new Date(rec.timestamp).toLocaleString();
    var tipo = TYPE[p.sosType] || p.sosType || "Otro";
    m.bindPopup(
      "<b>" + tipo + "</b>" + (crit ? ' <span style="color:#e11d48">⚠ CRÍTICO</span>' : "") +
      "<br>" + p.lat.toFixed(5) + ", " + p.lon.toFixed(5) +
      "<br><small>" + when + "</small>" +
      '<br><small style="color:#888">' + (rec.author || "") + "</small>"
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
        document.getElementById("status").textContent =
          "actualizado " + new Date().toLocaleTimeString();
        if (!fitted && bounds.length > 0) {
          map.fitBounds(bounds, { padding: [40, 40], maxZoom: 14 }); fitted = true;
        }
      })
      .catch(function (e) {
        document.getElementById("status").textContent = "error de red";
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
