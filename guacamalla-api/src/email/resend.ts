import { Resend } from "resend";

/**
 * Resend integration for waitlist signups: adds the contact to a "Waitlist"
 * audience and sends a confirmation email. Mirrors the Supabase pattern
 * (db/supabase.ts) — absent RESEND_API_KEY, everything here becomes a no-op
 * so `bun run dev` keeps working without a Resend account configured.
 *
 * Resend's docs describe "Audiences" as deprecated in favor of "Segments,"
 * but the published `resend` SDK (checked at v4.8.0, the current latest) only
 * exposes `resend.audiences`/`resend.contacts({ audienceId })` — no
 * `resend.segments` yet. Using Audiences here since that's what the installed
 * SDK actually supports; revisit once Segments ships in the SDK.
 */
const RESEND_API_KEY = process.env.RESEND_API_KEY;
const RESEND_FROM_EMAIL = process.env.RESEND_FROM_EMAIL ?? "admin@guacamalla.org";
const AUDIENCE_NAME = "Waitlist";

export const isResendConfigured = Boolean(RESEND_API_KEY);

const resend = isResendConfigured ? new Resend(RESEND_API_KEY) : null;

// Resolved once per process and cached — avoids an audiences.list() round
// trip on every signup. Set RESEND_AUDIENCE_ID to skip the lookup/creation.
let audienceIdPromise: Promise<string | null> | null = null;

function getOrCreateWaitlistAudienceId(): Promise<string | null> {
  if (!resend) return Promise.resolve(null);
  if (audienceIdPromise) return audienceIdPromise;

  audienceIdPromise = (async () => {
    const envId = process.env.RESEND_AUDIENCE_ID?.trim();
    if (envId) return envId;

    try {
      const { data: list, error: listError } = await resend!.audiences.list();
      if (listError) throw listError;

      const existing = list?.data?.find((a) => a.name === AUDIENCE_NAME);
      if (existing) return existing.id;

      const { data: created, error: createError } = await resend!.audiences.create({ name: AUDIENCE_NAME });
      if (createError || !created) throw createError ?? new Error("audiences.create returned no data");

      console.warn(
        `[resend] created "${AUDIENCE_NAME}" audience (id: ${created.id}) — ` +
          "set RESEND_AUDIENCE_ID to that value to skip this lookup on future boots.",
      );
      return created.id;
    } catch (err) {
      console.error("[resend] failed to resolve/create the waitlist audience:", err);
      return null;
    }
  })();

  return audienceIdPromise;
}

/** Adds the contact to the waitlist audience. Never throws. */
export async function addToWaitlistSegment(email: string): Promise<void> {
  if (!resend) return;
  const audienceId = await getOrCreateWaitlistAudienceId();
  if (!audienceId) return;

  try {
    const { error } = await resend.contacts.create({
      email,
      audienceId,
      unsubscribed: false,
    });
    if (error) throw error;
  } catch (err) {
    console.error("[resend] failed to add contact to waitlist audience:", err);
  }
}

const CONFIRMATION_COPY = {
  es: {
    subject: "Estás en la lista de espera — GuacaMalla",
    html: `
      <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;max-width:480px;margin:0 auto;padding:32px 24px;color:#111">
        <div style="display:flex;align-items:center;gap:8px;margin-bottom:24px">
          <span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:#faff69"></span>
          <strong style="font-size:16px;letter-spacing:-0.02em">GuacaMalla</strong>
        </div>
        <h1 style="font-size:20px;font-weight:600;margin:0 0 12px">Estás en la lista.</h1>
        <p style="font-size:15px;line-height:1.6;color:#333;margin:0 0 16px">
          Te avisaremos a este correo en cuanto el APK esté listo para instalar. Sin spam, sin más pasos.
        </p>
        <p style="font-size:13px;color:#888;margin-top:32px">GuacaMalla — red de auxilio sin conexión, hecha para Venezuela.</p>
      </div>`,
  },
  en: {
    subject: "You're on the waitlist — GuacaMalla",
    html: `
      <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;max-width:480px;margin:0 auto;padding:32px 24px;color:#111">
        <div style="display:flex;align-items:center;gap:8px;margin-bottom:24px">
          <span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:#faff69"></span>
          <strong style="font-size:16px;letter-spacing:-0.02em">GuacaMalla</strong>
        </div>
        <h1 style="font-size:20px;font-weight:600;margin:0 0 12px">You're on the list.</h1>
        <p style="font-size:15px;line-height:1.6;color:#333;margin:0 0 16px">
          We'll email you the moment the APK is ready to install. No spam, no extra steps.
        </p>
        <p style="font-size:13px;color:#888;margin-top:32px">GuacaMalla — offline emergency mesh, built for Venezuela.</p>
      </div>`,
  },
} as const;

/** Sends the waitlist confirmation email. Never throws. */
export async function sendWaitlistConfirmation(email: string, lang: "es" | "en"): Promise<void> {
  if (!resend) return;
  const copy = CONFIRMATION_COPY[lang] ?? CONFIRMATION_COPY.es;

  try {
    const { error } = await resend.emails.send({
      from: `GuacaMalla <${RESEND_FROM_EMAIL}>`,
      to: email,
      subject: copy.subject,
      html: copy.html,
    });
    if (error) throw error;
  } catch (err) {
    console.error("[resend] failed to send confirmation email:", err);
  }
}
