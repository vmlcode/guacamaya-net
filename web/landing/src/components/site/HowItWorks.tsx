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

        <MeshViz />

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

type MeshKind = "origin" | "hop" | "reached" | "idle";
type MeshNodeDef = {
  id: string;
  kind: MeshKind;
  x: number; // 0–100, node centre
  y: number;
  label?: string;
  labelPos?: "above" | "below" | "right";
  order?: number; // relay position → animation stagger
};

function MeshViz() {
  const { t } = useLanguage();
  // Signal travels top → bottom: SOS origin up top, "help reached" at the bottom.
  const nodes: MeshNodeDef[] = [
    { id: "origin", kind: "origin", x: 24, y: 23, label: t.how.mesh.origin, labelPos: "above", order: 0 },
    { id: "hop1", kind: "hop", x: 49, y: 40, label: t.how.mesh.hop1, labelPos: "right", order: 1 },
    { id: "hop2", kind: "hop", x: 69, y: 53, label: t.how.mesh.hop2, labelPos: "right", order: 2 },
    { id: "reached", kind: "reached", x: 62, y: 78, label: t.how.mesh.reached, labelPos: "below", order: 3 },
    { id: "idle1", kind: "idle", x: 35, y: 65 },
    { id: "idle2", kind: "idle", x: 77, y: 22 },
  ];
  return (
    <div className="reveal relative mx-auto w-full max-w-[520px] aspect-square mt-4 mb-20 md:mb-24">
      <svg viewBox="0 0 100 100" className="absolute inset-0 h-full w-full" aria-hidden>
        {/* dim links out to the idle phones */}
        <line x1="49" y1="40" x2="35" y2="65" className="mesh-dim" />
        <line x1="69" y1="53" x2="77" y2="22" className="mesh-dim" />
        {/* the active relay path — faint base + dash flowing origin → reached (top → bottom) */}
        <polyline points="24,23 49,40 69,53 62,78" className="mesh-base" />
        <polyline points="24,23 49,40 69,53 62,78" className="mesh-flow" />
      </svg>
      {nodes.map((n) => (
        <div
          key={n.id}
          className="absolute"
          style={{ left: `${n.x}%`, top: `${n.y}%`, transform: "translate(-50%, -50%)" }}
        >
          <MeshNode {...n} />
        </div>
      ))}
    </div>
  );
}

function MeshNode({ kind, label, labelPos = "below", order = 0 }: MeshNodeDef) {
  const isOrigin = kind === "origin";
  const isReached = kind === "reached";
  const isIdle = kind === "idle";
  const isHop = kind === "hop";
  const delay = order * 0.55;
  const rippleColor = isOrigin ? "var(--accent)" : "var(--primary)";
  const breatheClass = isOrigin ? "relay-node-origin" : isIdle ? "" : "relay-node-recv";

  const circleClass = isOrigin
    ? "w-16 h-16 bg-accent"
    : isReached
      ? "w-14 h-14 bg-primary"
      : isHop
        ? "w-12 h-12 border-2 border-primary/80 bg-transparent"
        : "w-11 h-11 border border-hairline bg-background/70 opacity-60";

  const labelColor = isOrigin
    ? "var(--accent)"
    : isReached
      ? "var(--primary)"
      : "var(--muted-foreground)";
  const labelPosClass =
    labelPos === "above"
      ? "bottom-full mb-2 left-1/2 -translate-x-1/2"
      : labelPos === "right"
        ? "left-full ml-3 top-1/2 -translate-y-1/2"
        : "top-full mt-2 left-1/2 -translate-x-1/2";

  return (
    <div className="relative flex items-center justify-center">
      {!isIdle && (
        <>
          <span
            className="pointer-events-none absolute inset-0 rounded-full border-2 relay-ripple"
            style={{ borderColor: rippleColor, animationDelay: `${delay}s` }}
          />
          <span
            className="pointer-events-none absolute inset-0 rounded-full border-2 relay-ripple"
            style={{ borderColor: rippleColor, animationDelay: `${delay + 1.2}s` }}
          />
        </>
      )}
      <div
        className={`relative flex items-center justify-center rounded-full ${circleClass} ${breatheClass}`}
        style={!isIdle ? { animationDelay: `${delay}s` } : undefined}
      >
        {isOrigin && <Asterisk className="text-white" size={22} />}
        {isReached && <CheckIcon />}
        {(isHop || isIdle) && (
          <PhoneIcon color={isIdle ? "var(--muted-foreground)" : "var(--primary)"} />
        )}
      </div>
      {label && (
        <div
          className={`absolute ${labelPosClass} whitespace-nowrap mono text-[10px] md:text-[11px] tracking-wide`}
          style={{ color: labelColor }}
        >
          {label}
        </div>
      )}
    </div>
  );
}

function CheckIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      className="w-5 h-5"
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
  );
}

function PhoneIcon({ color }: { color: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className="w-4 h-4"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      style={{ color }}
      aria-hidden
    >
      <rect x="7" y="3" width="10" height="18" rx="2.5" />
      <line x1="11" y1="17.5" x2="13" y2="17.5" />
    </svg>
  );
}
