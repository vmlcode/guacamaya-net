import { useLanguage } from "@/lib/i18n";

export function ProofPacket() {
  const { t } = useLanguage();
  return (
    <section className="relative py-28 md:py-40 hairline-b">
      <div className="mx-auto max-w-[1440px] px-6 lg:px-10">
        <div className="grid grid-cols-12 gap-8 mb-16">
          <div className="col-span-12 md:col-span-3 mono text-[11px] uppercase tracking-[0.2em] text-muted-foreground reveal">
            <span className="text-primary">◆</span> {t.proof.section}
          </div>
          <div className="col-span-12 md:col-span-9">
            <h2 className="reveal font-light tracking-[-0.02em] leading-[1.05] text-[clamp(2rem,4.6vw,3.75rem)]">
              {t.proof.title}
            </h2>
            <p className="reveal mt-4 text-muted-foreground max-w-[52ch]">{t.proof.sub}</p>
          </div>
        </div>

        <div className="reveal border border-hairline bg-surface/60 backdrop-blur-sm rounded-sm overflow-hidden">
          <div className="flex items-center justify-between px-5 py-3 border-b border-hairline mono text-[11px] uppercase tracking-wider text-muted-foreground">
            <span className="flex items-center gap-2">
              <span className="w-1.5 h-1.5 rounded-full bg-primary pulse-dot" />
              SOS_PACKET · 22 B
            </span>
            <span>0x00 → 0x15</span>
          </div>
          <div className="divide-y divide-hairline mono text-[13px]">
            <Row label={t.proof.labels.hdr} hex="A7 3B" data="V1 · TYPE=SOS" />
            <Row label={t.proof.labels.nid} hex="9F E1 04 22" data="node/4f9e1" />
            <Row label={t.proof.labels.geo} hex="10 62 4A 8C · 68 D3 A1 22" data="10.4806° N, 66.9036° W" />
            <Row label={t.proof.labels.ttl} hex="0F" data="15 hops max" />
            <Row label={t.proof.labels.sig} hex="4B 7A · 22 91 · E0 5C · D8 …" data="verified ✓" accent />
          </div>
        </div>
      </div>
    </section>
  );
}

function Row({ label, hex, data, accent }: { label: string; hex: string; data: string; accent?: boolean }) {
  return (
    <div className="grid grid-cols-12 gap-4 px-5 py-4 items-center">
      <div className="col-span-4 md:col-span-3 mono text-[11px] uppercase text-muted-foreground tracking-wider">{label}</div>
      <div className={`col-span-8 md:col-span-5 mono ${accent ? "text-primary" : "text-foreground"}`}>{hex}</div>
      <div className="col-span-12 md:col-span-4 mono text-[11px] text-muted-foreground text-right hidden md:block">{data}</div>
    </div>
  );
}
