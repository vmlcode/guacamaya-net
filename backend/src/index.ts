import Fastify from "fastify";

const PORT = Number(process.env.PORT ?? 3000);

const app = Fastify({ logger: true });

app.get("/health", async () => ({ ok: true }));





// Handle graceful shutdown
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
} catch (err) {
    app.log.error(err);
    process.exit(1);
}

