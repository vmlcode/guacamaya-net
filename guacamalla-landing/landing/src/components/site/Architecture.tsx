import { useLanguage } from "@/lib/i18n";

export function Architecture() {
  const { t } = useLanguage();
  const items = [t.arch.a, t.arch.b, t.arch.c];
  return (
    <section id="architecture" className="relative py-28 md:py-40 hairline-b">
      <div className="mx-auto max-w-[1440px] px-6 lg:px-10">
        <div className="grid grid-cols-12 gap-8 mb-16 md:mb-24">
          <div className="col-span-12 md:col-span-3 mono text-[11px] uppercase tracking-[0.2em] text-muted-foreground reveal">
            <span className="text-primary">◆</span> {t.arch.section}
          </div>
          <h2 className="col-span-12 md:col-span-9 reveal font-light tracking-[-0.02em] leading-[1.05] text-[clamp(2rem,4.6vw,3.75rem)]">
            {t.arch.title}
          </h2>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-px bg-hairline border border-hairline">
          {items.map((it, i) => (
            <div key={i} className="bg-background p-8 md:p-10 flex flex-col gap-6 min-h-[280px] reveal">
              <div className="flex items-center justify-between mono text-[11px] uppercase tracking-wider">
                <span className="text-primary">{it.k}</span>
                <span className="text-muted-foreground">0{i + 1}</span>
              </div>
              <div className="text-[19px] leading-snug font-light tracking-tight">{it.t}</div>
              <div className="mt-auto mono text-[11px] text-muted-foreground pt-6 border-t border-hairline">{it.d}</div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
