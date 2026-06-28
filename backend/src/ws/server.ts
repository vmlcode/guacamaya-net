import { WebSocketServer, WebSocket } from "ws";
import { Server, IncomingMessage } from "http";
import { ChannelRecord, LocationPoint, ResolveReceipt } from "@guacamaya/shared";
import { verifyWsToken } from "../security/auth.js";
import { effectiveWsKey } from "../security/config.js";

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

export function initWebSocketServer(server: Server) {
  const wss = new WebSocketServer({ noServer: true });
  const wsKey = effectiveWsKey();

  server.on("upgrade", (request, socket, head) => {
    const url = new URL(request.url || "", `http://${request.headers.host || "localhost"}`);
    if (url.pathname !== "/ws" && url.pathname !== "/ws/") return;

    if (wsKey) {
      const token = extractWsToken(request);
      if (!verifyWsToken(token, wsKey)) {
        socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
        socket.destroy();
        return;
      }
    }

    wss.handleUpgrade(request, socket, head, (ws) => {
      wss.emit("connection", ws, request);
    });
  });

  wss.on("connection", (ws: WebSocket) => {
    const client: Client = { ws, channels: new Set(), authenticated: Boolean(wsKey) };
    clients.add(client);

    ws.on("message", (message) => {
      try {
        const data = JSON.parse(message.toString());

        if (data.type === "subscribe" && typeof data.channel === "string") {
          if (requiresAuth(data.channel) && !client.authenticated) {
            ws.send(JSON.stringify({ error: "Authentication required for this channel" }));
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
  const payload = JSON.stringify({ type: "record", data: record });
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
