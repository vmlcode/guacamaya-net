import { WebSocketServer, WebSocket } from "ws";
import { Server } from "http";
import { ChannelRecord } from "@guacamaya/shared";

interface Client {
  ws: WebSocket;
  channels: Set<string>;
}

const clients = new Set<Client>();

export function initWebSocketServer(server: Server) {
  const wss = new WebSocketServer({ noServer: true });

  server.on("upgrade", (request, socket, head) => {
    // Listen on /ws path
    const url = new URL(request.url || "", `http://${request.headers.host || "localhost"}`);
    if (url.pathname === "/ws" || url.pathname === "/ws/") {
      wss.handleUpgrade(request, socket, head, (ws) => {
        wss.emit("connection", ws, request);
      });
    }
  });

  wss.on("connection", (ws: WebSocket) => {
    const client: Client = { ws, channels: new Set<string>() };
    clients.add(client);

    ws.on("message", (message) => {
      try {
        const data = JSON.parse(message.toString());
        
        if (data.type === "subscribe" && typeof data.channel === "string") {
          client.channels.add(data.channel);
          ws.send(JSON.stringify({ type: "subscribed", channel: data.channel }));
        } else if (data.type === "unsubscribe" && typeof data.channel === "string") {
          client.channels.delete(data.channel);
          ws.send(JSON.stringify({ type: "unsubscribed", channel: data.channel }));
        }
      } catch (err) {
        ws.send(JSON.stringify({ error: "Invalid message format" }));
      }
    });

    ws.on("close", () => {
      clients.delete(client);
    });

    ws.on("error", () => {
      clients.delete(client);
    });
  });
}

/**
 * Broadcasts a ChannelRecord to all connected clients subscribed to its channel.
 */
export function broadcastRecord(record: ChannelRecord) {
  const payload = JSON.stringify({ type: "record", data: record });
  for (const client of clients) {
    if (client.channels.has(record.channel) && client.ws.readyState === WebSocket.OPEN) {
      client.ws.send(payload);
    }
  }
}
