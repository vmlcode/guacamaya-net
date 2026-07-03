import { useLanguage } from "@/lib/i18n";
import { Asterisk } from "./Asterisk";

export function HowItWorks() {
  const { t } = useLanguage();
  return (
    <section id="how" className="relative py-28 md:py-40 hairline-b">
      <div className="mx-auto max-w-[1440px] px-6 lg:px-10">
        <div className="grid grid-cols-12 gap-8 mb-16 md:mb-24">
          <div className="col-span-12 md:col-span-3 mono text-[11px] uppercase tracking-[0.2em] text-muted-foreground reveal">
            <span className="text-primary">◆</span> {t.how.section}
          </div>
          <div className="col-span-12 md:col-span-9">
            <h2 className="reveal font-light tracking-[-0.02em] leading-[1.05] text-[clamp(2rem,4.6vw,3.75rem)]">
              {t.how.title}
            </h2>
            <p className="reveal mt-4 text-muted-foreground max-w-[52ch]">{t.how.sub}</p>
          </div>
        </div>

        <RelayStrip />

        <div className="grid grid-cols-1 md:grid-cols-3 gap-px bg-hairline border border-hairline">
          {t.how.steps.map((s, i) => (
            <div
              key={i}
              className="bg-background p-8 md:p-10 flex flex-col gap-6 min-h-[280px] reveal"
            >
              <div className="flex items-center justify-between mono text-[11px] uppercase tracking-wider">
                <span className="text-primary text-[13px] tabular-nums">0{i + 1}</span>
                <span className="text-muted-foreground">TTL {8 - i}</span>
              </div>
              <div className="text-[19px] leading-snug font-light tracking-tight">{s.t}</div>
              <div className="mt-auto text-[14px] leading-relaxed font-light text-muted-foreground pt-6 border-t border-hairline">
                {s.d}
              </div>
            </div>
          ))}
        </div>

        <div className="reveal mt-6 flex items-start gap-3 border border-hairline bg-surface/40 backdrop-blur-sm rounded-sm px-5 py-4">
          <span className="text-primary mt-0.5 leading-none">◆</span>
          <p className="text-[13px] leading-relaxed font-light text-muted-foreground max-w-[80ch]">
            {t.how.proof}
          </p>
        </div>
      </div>
    </section>
  );
}

type NodeKind = "origin" | "hop" | "received";

function RelayStrip() {
  const { t } = useLanguage();
  const nodes: { kind: NodeKind; label: string }[] = [
    { kind: "origin", label: t.how.relay.origin },
    { kind: "hop", label: t.how.relay.hop1 },
    { kind: "hop", label: t.how.relay.hop2 },
    { kind: "received", label: t.how.relay.received },
  ];
  return (
    <div className="reveal relative mt-4 mb-24 md:mb-28 px-1 md:px-2">
      {/* one continuous line running through every node, aligned to circle center */}
      <div className="pointer-events-none absolute inset-x-0 top-[22px] md:top-[28px] -translate-y-1/2 h-px">
        <div className="absolute inset-0" style={{ background: "var(--hairline)" }} />
        <div className="absolute inset-0 relay-line" />
      </div>
      <div className="relative flex items-start justify-between">
        {nodes.map((n, i) => (
          <RelayNode key={i} kind={n.kind} label={n.label} index={i} />
        ))}
      </div>
    </div>
  );
}

function RelayNode({ kind, label, index }: { kind: NodeKind; label: string; index: number }) {
  const isOrigin = kind === "origin";
  const isReceived = kind === "received";
  const ringColor = isOrigin ? "var(--accent)" : "var(--primary)";
  const labelColor = isOrigin
    ? "var(--accent)"
    : isReceived
      ? "var(--primary)"
      : "var(--muted-foreground)";
  // stagger so the pulse propagates origin → received in step with the line
  const delay = index * 0.55;
  const breatheClass = isOrigin
    ? "relay-node-origin"
    : isReceived
      ? "relay-node-recv"
      : "relay-node";

  return (
    <div className="relative flex-none">
      {/* two offset ripple rings → continuous outward ripple */}
      <span
        className="pointer-events-none absolute inset-0 rounded-full border-2 relay-ripple"
        style={{ borderColor: ringColor, animationDelay: `${delay}s` }}
      />
      <span
        className="pointer-events-none absolute inset-0 rounded-full border-2 relay-ripple"
        style={{ borderColor: ringColor, animationDelay: `${delay + 1.2}s` }}
      />
      <div
        className={`relative flex items-center justify-center rounded-full w-11 h-11 md:w-14 md:h-14 ${breatheClass} ${
          isOrigin
            ? "bg-accent"
            : isReceived
              ? "bg-primary"
              : "border-2 border-primary/70 bg-transparent"
        }`}
        style={{ animationDelay: `${delay}s` }}
      >
        {isOrigin && <Asterisk className="text-white" size={18} />}
        {isReceived && (
          <svg
            viewBox="0 0 24 24"
            className="w-4 h-4 md:w-5 md:h-5"
            fill="none"
            stroke="currentColor"
            strokeWidth={2.4}
            strokeLinecap="round"
            strokeLinejoin="round"
            style={{ color: "var(--primary-foreground)" }}
            aria-hidden
          >
            <path d="M5 12.5l4.2 4.2L19 7" />
          </svg>
        )}
        {!isOrigin && !isReceived && (
          <svg
            viewBox="0 0 24 24"
            className="w-3.5 h-3.5 md:w-4 md:h-4"
            fill="none"
            stroke="currentColor"
            strokeWidth={2}
            strokeLinecap="round"
            strokeLinejoin="round"
            style={{ color: "var(--primary)" }}
            aria-hidden
          >
            <rect x="7" y="3" width="10" height="18" rx="2.5" />
            <line x1="11" y1="17.5" x2="13" y2="17.5" />
          </svg>
        )}
      </div>
      <div
        className="absolute top-full left-1/2 -translate-x-1/2 mt-3 w-[72px] md:w-28 text-center mono text-[10px] md:text-[11px] leading-tight tracking-wide text-balance"
        style={{ color: labelColor }}
      >
        {label}
      </div>
    </div>
  );
}
