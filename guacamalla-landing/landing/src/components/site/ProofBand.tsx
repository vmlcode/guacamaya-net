import { useLanguage } from "@/lib/i18n";

export function ProofBand() {
  const { t } = useLanguage();
  return (
    <section className="relative hairline-b">
      <div className="mx-auto max-w-[1440px] px-6 lg:px-10">
        <div className="grid grid-cols-2 md:grid-cols-4 gap-px bg-hairline border-x border-hairline">
          {t.band.items.map((it, i) => (
            <div
              key={i}
              className="bg-background px-6 py-12 md:py-16 flex flex-col gap-3 reveal"
            >
              <div
                className={`font-light tracking-[-0.03em] leading-none text-[clamp(2.5rem,4.4vw,3.5rem)] ${
                  i < 2 ? "text-primary" : "text-foreground"
                }`}
              >
                {it.n}
              </div>
              <div className="mono text-[11px] uppercase tracking-wider text-muted-foreground">
                {it.l}
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
