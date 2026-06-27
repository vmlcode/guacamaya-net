import Fastify from "fastify";
import cors from "@fastify/cors";
import rateLimit from "@fastify/rate-limit";
import { channelRoutes } from "./channels/routes.js";
import { initWebSocketServer } from "./ws/server.js";

const PORT = Number(process.env.PORT ?? 3000);

const app = Fastify({ logger: true });

// Register plugins
await app.register(cors, { origin: "*" });
await app.register(rateLimit, { max: 100, timeWindow: "1 minute" });

// Register routes
await app.register(channelRoutes);

app.get("/health", async () => ({ ok: true }));

app.get("/", async () => ({ message: "Welcome to Guacamaya Net!" }));


const shutdown = async (signal: string) => {
    app.log.info(`Received ${signal}. Shutting down gracefully...`);
    try {
        await app.close();
        app.log.info("Server closed successfully.");
        process.exit(0);
    } catch (err) {
        app.log.error(err, "Error during server shutdown");
        process.exit(1);
    }
};

process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));

try {
    await app.listen({ port: PORT, host: "0.0.0.0" });
    initWebSocketServer(app.server);
} catch (err) {
    app.log.error(err);
    process.exit(1);
}

