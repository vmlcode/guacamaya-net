import MagicRings from "@/components/MagicRings";
import { useLanguage } from "@/lib/i18n";

export function Hero() {
  const { t } = useLanguage();
  return (
    <section id="top" className="relative min-h-[100svh] w-full overflow-hidden hairline-b">
      {/* Rings backdrop */}
      <div className="absolute inset-0 z-0">
        <MagicRings
          color="#faff69"
          colorTwo="#F43F5E"
          ringCount={7}
          speed={0.6}
          attenuation={9}
          lineThickness={2}
          baseRadius={0.28}
          radiusStep={0.11}
          scaleRate={0.08}
          opacity={0.9}
          blur={0}
          noiseAmount={0.08}
          rotation={0}
          ringGap={1.5}
          fadeIn={0.75}
          fadeOut={0.55}
          followMouse
          mouseInfluence={0.12}
          hoverScale={1.08}
          parallax={0.04}
          clickBurst
        />
      </div>

      {/* Radial vignette to seat the type */}
      <div
        className="absolute inset-0 pointer-events-none"
        style={{
          background:
            "radial-gradient(ellipse at center, rgba(10,10,10,0) 0%, rgba(10,10,10,0.55) 55%, rgba(10,10,10,0.95) 100%)",
        }}
      />

      {/* Corner ticks */}
      <Ticks />

      {/* Content — wrapper doesn't block pointer events so MagicRings stays interactive */}
      <div className="relative z-10 mx-auto max-w-[1440px] px-6 lg:px-10 min-h-[100svh] flex flex-col items-center justify-center text-center pt-24 pb-20 pointer-events-none [&_a]:pointer-events-auto [&_button]:pointer-events-auto">
        <div className="mono text-[11px] tracking-[0.24em] text-muted-foreground uppercase mb-8 reveal">
          <span className="text-primary">◆</span> <span className="ml-2">{t.hero.eyebrow}</span>
        </div>

        <h1 className="reveal font-sans font-light tracking-[-0.03em] leading-[0.95] text-[clamp(2.6rem,7.5vw,7.25rem)] max-w-[18ch]">
          {t.hero.titleA}{" "}
          <span className="italic font-extralight text-muted-foreground">{t.hero.titleB}</span>
          <br />
          <span className="font-medium">{t.hero.titleC}</span>
        </h1>

        <div className="reveal mt-12 flex items-center gap-4">
          <a
            href="#waitlist"
            className="group inline-flex items-center gap-3 px-6 py-3.5 rounded-md bg-primary text-primary-foreground font-medium text-sm hover:brightness-110 transition"
          >
            {t.hero.cta}
            <span className="mono text-xs opacity-70 group-hover:translate-x-0.5 transition-transform">→</span>
          </a>
          <a
            href="#architecture"
            className="mono text-[11px] tracking-wider uppercase text-muted-foreground hover:text-foreground transition"
          >
            {t.hero.scrollHint} ↓
          </a>
        </div>

        {/* Stat rail */}
        <div className="reveal mt-24 grid grid-cols-3 gap-8 md:gap-20 mono text-[11px] uppercase tracking-wider text-muted-foreground">
          <Stat n="22 B" l="PAYLOAD" />
          <Stat n="ED25519" l={t.nav.encrypted.replace(/^\d+:\s*/, "")} />
          <Stat n="0" l="SERVERS" />
        </div>
      </div>
    </section>
  );
}

function Stat({ n, l }: { n: string; l: string }) {
  return (
    <div className="flex flex-col items-center gap-1">
      <div className="text-foreground text-[15px] tracking-tight">{n}</div>
      <div>{l}</div>
    </div>
  );
}

function Ticks() {
  return (
    <div className="absolute inset-0 pointer-events-none">
      {[
        "top-4 left-4",
        "top-4 right-4",
        "bottom-4 left-4",
        "bottom-4 right-4",
      ].map((pos, i) => (
        <div key={i} className={`absolute ${pos} w-3 h-3`}>
          <div className="absolute inset-0 border-l border-t border-primary/40" style={i === 1 ? { transform: "rotate(90deg)" } : i === 2 ? { transform: "rotate(-90deg)" } : i === 3 ? { transform: "rotate(180deg)" } : undefined} />
        </div>
      ))}
    </div>
  );
}

