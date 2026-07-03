import { FastifyInstance } from "fastify";
import { waitlistRepo } from "../db/waitlistRepo.js";
import { securityConfig } from "../security/config.js";
import { isValidEmail, isValidWaitlistCountry, normalizeEmail } from "../security/validation.js";
import { addToWaitlistSegment, sendWaitlistConfirmation } from "../email/resend.js";

export async function waitlistRoutes(fastify: FastifyInstance) {
  fastify.post<{ Body: { email?: unknown; country?: unknown; lang?: unknown } }>(
    "/waitlist",
    { config: { rateLimit: securityConfig.waitlistRateLimit } },
    async (request, reply) => {
      const { email, country, lang } = request.body ?? {};

      if (typeof email !== "string" || !isValidEmail(email)) {
        return reply.code(400).send({ error: "Invalid email" });
      }
      if (country !== undefined && !isValidWaitlistCountry(country)) {
        return reply.code(400).send({ error: "Invalid country" });
      }

      const entry = {
        email: normalizeEmail(email),
        country: typeof country === "string" ? country : "",
        createdAt: Date.now(),
      };

      try {
        const added = await waitlistRepo.add(entry);

        // Only notify on a genuinely new signup — resubmitting the same email
        // (double-click, retry) must not re-trigger the confirmation email.
        if (added) {
          const resolvedLang = lang === "en" ? "en" : "es";
          const results = await Promise.allSettled([
            addToWaitlistSegment(entry.email),
            sendWaitlistConfirmation(entry.email, resolvedLang),
          ]);
          for (const r of results) {
            if (r.status === "rejected") request.log.error(r.reason, "Resend waitlist notification failed");
          }
        }

        return reply.code(200).send({ ok: true, added });
      } catch (err) {
        request.log.error(err, "Failed to persist waitlist signup");
        return reply.code(500).send({ error: "Failed to join waitlist" });
      }
    },
  );
}
