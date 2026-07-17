import { useState } from "react";
import { z } from "zod";
import { useLanguage } from "@/lib/i18n";
import { Asterisk } from "./Asterisk";

type Status = "idle" | "sending" | "success" | "invalid" | "network" | "server";

const emailSchema = z.string().trim().email();

const ENDPOINT =
  (import.meta.env.VITE_WAITLIST_API_URL as string | undefined) || "/waitlist";

export function Waitlist() {
  const { lang, t } = useLanguage();
  const [email, setEmail] = useState("");
  const [country, setCountry] = useState(lang === "es" ? "Venezuela" : "Venezuela");
  const [hp, setHp] = useState(""); // honeypot
  const [status, setStatus] = useState<Status>("idle");

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (hp.trim() !== "") {
      // silent success on bot
      setStatus("success");
      return;
    }
    const parsed = emailSchema.safeParse(email);
    if (!parsed.success) {
      setStatus("invalid");
      return;
    }
    setStatus("sending");
    try {
      const res = await fetch(ENDPOINT, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: parsed.data, country, lang }),
      });
      if (res.ok) {
        setStatus("success");
        setEmail("");
        return;
      }
      if (res.status >= 400 && res.status < 500) setStatus("invalid");
      else setStatus("server");
    } catch {
      setStatus("network");
    }
  };

  const message = (() => {
    switch (status) {
      case "success": return { text: t.waitlist.success, tone: "ok" as const };
      case "invalid": return { text: t.waitlist.errorInvalid, tone: "err" as const };
      case "network": return { text: t.waitlist.errorNetwork, tone: "err" as const };
      case "server": return { text: t.waitlist.errorServer, tone: "err" as const };
      default: return null;
    }
  })();

  return (
    <section id="waitlist" className="relative py-28 md:py-40 hairline-b">
      <div className="mx-auto max-w-[1440px] px-6 lg:px-10">
        <div className="grid grid-cols-12 gap-8">
          <div className="col-span-12 md:col-span-4 space-y-6 reveal">
            <div className="mono text-[11px] uppercase tracking-[0.2em] text-muted-foreground">
              <span className="text-primary">◆</span> {t.waitlist.section}
            </div>
            <div className="relative w-fit">
              <div className="absolute inset-0 bg-primary/20 blur-3xl rounded-full scale-150" />
              <Asterisk className="relative text-primary" size={64} />
            </div>
            <h2 className="font-light tracking-[-0.02em] leading-[1.05] text-[clamp(1.8rem,3.6vw,3rem)]">
              {t.waitlist.title}
            </h2>
            <p className="text-muted-foreground max-w-[38ch]">{t.waitlist.sub}</p>
          </div>

          <div className="col-span-12 md:col-span-8 md:col-start-6 reveal">
            <form
              onSubmit={onSubmit}
              className="border border-hairline bg-surface/40 backdrop-blur-sm rounded-sm p-6 md:p-10"
            >
              {/* honeypot */}
              <div aria-hidden className="absolute -left-[9999px] w-0 h-0 overflow-hidden">
                <label>
                  Company
                  <input
                    tabIndex={-1}
                    autoComplete="off"
                    value={hp}
                    onChange={(e) => setHp(e.target.value)}
                  />
                </label>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-5 gap-4">
                <label className="md:col-span-3 flex flex-col gap-2">
                  <span className="mono text-[10px] uppercase tracking-[0.18em] text-muted-foreground">EMAIL</span>
                  <input
                    type="email"
                    required
                    placeholder={t.waitlist.email}
                    value={email}
                    onChange={(e) => { setEmail(e.target.value); if (status !== "idle") setStatus("idle"); }}
                    className="bg-transparent border-b border-hairline focus:border-primary outline-none px-0 py-2.5 text-[16px] font-light placeholder:text-muted-foreground/50 transition-colors"
                  />
                </label>
                <label className="md:col-span-2 flex flex-col gap-2">
                  <span className="mono text-[10px] uppercase tracking-[0.18em] text-muted-foreground">{t.waitlist.country.toUpperCase()}</span>
                  <input
                    type="text"
                    value={country}
                    onChange={(e) => setCountry(e.target.value)}
                    className="bg-transparent border-b border-hairline focus:border-primary outline-none px-0 py-2.5 text-[16px] font-light transition-colors"
                  />
                </label>
              </div>

              <div className="mt-8 flex flex-col md:flex-row md:items-center md:justify-between gap-4">
                <button
                  type="submit"
                  disabled={status === "sending"}
                  className="inline-flex items-center justify-center gap-3 px-6 py-3.5 rounded-md bg-primary text-primary-foreground font-medium text-sm hover:brightness-110 disabled:opacity-60 transition"
                >
                  {status === "sending" ? t.waitlist.submitting : t.waitlist.submit}
                  <span className="mono text-xs opacity-70">→</span>
                </button>
                <div className="mono text-[11px] text-muted-foreground">{t.waitlist.privacy}</div>
              </div>

              {message && (
                <div
                  role="status"
                  className={`mt-6 mono text-[12px] px-3 py-2 border ${
                    message.tone === "ok"
                      ? "border-primary/40 text-primary bg-primary/5"
                      : "border-accent/40 text-accent bg-accent/5"
                  }`}
                >
                  {message.text}
                </div>
              )}
            </form>
          </div>
        </div>
      </div>
    </section>
  );
}
