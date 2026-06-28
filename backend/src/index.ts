import Fastify from "fastify";
import cors from "@fastify/cors";
import helmet from "@fastify/helmet";
import rateLimit from "@fastify/rate-limit";
import { channelRoutes } from "./channels/routes.js";
import { locationRoutes } from "./locations/routes.js";
import { resolveRoutes } from "./resolve/routes.js";
import { dashboardRoutes } from "./dashboard/routes.js";
import { resolvesRepo } from "./db/resolvesRepo.js";
import { initWebSocketServer } from "./ws/server.js";
import { securityConfig } from "./security/config.js";

const PORT = Number(process.env.PORT ?? 3000);
const SWEEP_INTERVAL_MS = 60_000;

const app = Fastify({ logger: true });

await app.register(helmet, {
  contentSecurityPolicy: false,
  crossOriginResourcePolicy: { policy: "cross-origin" },
});

await app.register(cors, {
  origin: securityConfig.corsOrigins,
  methods: ["GET", "POST", "OPTIONS"],
  allowedHeaders: ["Content-Type", "Authorization", "X-Api-Key"],
});

await app.register(rateLimit, securityConfig.globalRateLimit);

await app.register(channelRoutes);
await app.register(locationRoutes);
await app.register(resolveRoutes);
await app.register(dashboardRoutes);

app.get("/health", async () => ({ ok: true }));
app.get("/", async () => ({ message: "Welcome to Guacamaya Net!" }));

let sweepTimer: ReturnType<typeof setInterval> | null = null;

const shutdown = async (signal: string) => {
  app.log.info(`Received ${signal}. Shutting down gracefully...`);
  if (sweepTimer) clearInterval(sweepTimer);
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

  // Cooldown sweeper — every 60 s, promote pending receipts whose cooldown
  // expired (and which were not disputed) to cleared. Emits a `resuelto`
  // record per promotion so subscribers see the final state.
  sweepTimer = setInterval(async () => {
    try {
      const promoted = await resolvesRepo.getExpiredPendingClears(Date.now());
      for (const receipt of promoted) {
        app.log.info({ receiptId: receipt.id, targetSosId: receipt.targetSosId }, "resolve receipt cleared by cooldown");
      }
    } catch (err) {
      app.log.error(err, "cooldown sweep failed");
    }
  }, SWEEP_INTERVAL_MS);
} catch (err) {
  app.log.error(err);
  process.exit(1);
}
