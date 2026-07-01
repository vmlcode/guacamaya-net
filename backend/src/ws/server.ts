import { WebSocketServer, WebSocket } from "ws";
import { Server, IncomingMessage } from "http";
import { ChannelRecord, LocationPoint, ResolveReceipt } from "@guacamaya/shared";
import { verifyWsToken } from "../security/auth.js";
import { securityConfig, effectiveWsKey } from "../security/config.js";
import { sanitizeRecordForPublic } from "../channels/sanitize.js";

interface Client {
  ws: WebSocket;
  channels: Set<string>;
  authenticated: boolean;
}

const clients = new Set<Client>();
const SENSITIVE_CHANNELS = new Set([
  "locations",
  "alertas",
  "refugios",
  "ayuda-medica",
  "resuelto",
  "resolves",
]);

/**
 * Cap per-client subscriptions to bound memory growth. A legitimate dashboard
 * subscribes to a handful of channels; anything bigger is abuse or a bug.
 */
const MAX_SUBSCRIPTIONS_PER_CLIENT = securityConfig.wsMaxSubscriptionsPerClient;

function extractWsToken(request: IncomingMessage): string | null {
  const url = new URL(request.url || "", `http://${request.headers.host || "localhost"}`);
  const q = url.searchParams.get("token");
  if (q) return q;

  const header = request.headers["sec-websocket-protocol"];
  if (typeof header === "string" && header.startsWith("guacamaya.")) {
    return header.slice("guacamaya.".length);
  }
  return null;
}

function requiresAuth(channel: string): boolean {
  return SENSITIVE_CHANNELS.has(channel);
}

/**
 * Origin check — rejects cross-site WebSocket hijacking attempts from browsers.
 * Non-browser clients (curl, app, server-to-server) typically omit the header
 * and are allowed. When present, the Origin must be in the configured allowlist.
 */
function originAllowed(request: IncomingMessage): boolean {
  const origin = request.headers.origin;
  if (!origin) return true; // non-browser client
  const allow = securityConfig.corsOrigins;
  if (allow === true) return true; // dev: allow all
  return allow.includes(origin);
}

export function initWebSocketServer(server: Server) {
  const wss = new WebSocketServer({ noServer: true });
  const wsKey = effectiveWsKey();

  server.on("upgrade", (request, socket, head) => {
    const url = new URL(request.url || "", `http://${request.headers.host || "localhost"}`);
    if (url.pathname !== "/ws" && url.pathname !== "/ws/") return;

    // Block CSWSH from browser origins not in the allowlist. Non-browser
    // clients (no Origin header) pass through.
    if (!originAllowed(request)) {
      socket.destroy();
      return;
    }

    // Auth is enforced per channel at subscribe time, NOT at the upgrade. The
    // upgrade stays open so public community channels (solicito-ayuda, estoy-bien)
    // are reachable by phones that intentionally carry no key (LiveSosClient).
    // A valid token still unlocks SENSITIVE_CHANNELS (locations, official, …).
    // When no ws key is configured at all there is nothing to check → treat as
    // authenticated so a keyless dev dashboard still works.
    const token = extractWsToken(request);
    const authenticated = wsKey ? verifyWsToken(token, wsKey) : true;

    wss.handleUpgrade(request, socket, head, (ws) => {
      wss.emit("connection", ws, request, authenticated);
    });
  });

  wss.on("connection", (ws: WebSocket, _request: IncomingMessage, authenticated: boolean) => {
    const client: Client = { ws, channels: new Set(), authenticated };
    clients.add(client);

    ws.on("message", (message) => {
      try {
        const data = JSON.parse(message.toString());

        if (data.type === "subscribe" && typeof data.channel === "string") {
          if (requiresAuth(data.channel) && !client.authenticated) {
            ws.send(JSON.stringify({ error: "Authentication required for this channel" }));
            return;
          }
          if (client.channels.size >= MAX_SUBSCRIPTIONS_PER_CLIENT && !client.channels.has(data.channel)) {
            ws.send(JSON.stringify({ error: `Subscription cap reached (${MAX_SUBSCRIPTIONS_PER_CLIENT})` }));
            return;
          }
          client.channels.add(data.channel);
          ws.send(JSON.stringify({ type: "subscribed", channel: data.channel }));
        } else if (data.type === "unsubscribe" && typeof data.channel === "string") {
          client.channels.delete(data.channel);
          ws.send(JSON.stringify({ type: "unsubscribed", channel: data.channel }));
        }
      } catch {
        ws.send(JSON.stringify({ error: "Invalid message format" }));
      }
    });

    ws.on("close", () => clients.delete(client));
    ws.on("error", () => clients.delete(client));
  });
}

export function broadcastRecord(record: ChannelRecord) {
  // Community SOS records carry victim GPS; coarsen them on the wire the same way
  // the public HTTP read does (sanitizeRecordForPublic is a no-op for official
  // channels). Exact positions reach the dashboard only via the auth-gated
  // "locations" channel / GET /locations, never this public record stream.
  const publicView = sanitizeRecordForPublic(record);
  const payload = JSON.stringify({ type: "record", data: publicView });
  for (const client of clients) {
    if (client.channels.has(record.channel) && client.ws.readyState === WebSocket.OPEN) {
      client.ws.send(payload);
    }
  }
}

export function broadcastLocation(point: LocationPoint) {
  const payload = JSON.stringify({ type: "location", data: point });
  for (const client of clients) {
    if (client.channels.has("locations") && client.ws.readyState === WebSocket.OPEN) {
      client.ws.send(payload);
    }
  }
}

export function broadcastResolve(receipt: ResolveReceipt) {
  const payload = JSON.stringify({ type: "resolve", data: receipt });
  for (const client of clients) {
    if (client.channels.has("resolves") && client.ws.readyState === WebSocket.OPEN) {
      client.ws.send(payload);
    }
  }
}
