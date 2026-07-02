import { useLanguage } from "@/lib/i18n";
import appScreenshot from "@/assets/app_screenshot.jpeg";

export function Showcase() {
  const { t } = useLanguage();
  return (
    <section className="relative py-28 md:py-40 hairline-b">
      <div className="mx-auto max-w-[1440px] px-6 lg:px-10">
        <div className="grid grid-cols-12 gap-8 mb-16">
          <div className="col-span-12 md:col-span-3 mono text-[11px] uppercase tracking-[0.2em] text-muted-foreground reveal">
            <span className="text-primary">◆</span> {t.showcase.section}
          </div>
          <div className="col-span-12 md:col-span-9">
            <h2 className="reveal font-light tracking-[-0.02em] leading-[1.05] text-[clamp(2rem,4.6vw,3.75rem)]">
              {t.showcase.title}
            </h2>
            <p className="reveal mt-4 text-muted-foreground max-w-[52ch]">{t.showcase.sub}</p>
          </div>
        </div>

        <div className="reveal flex flex-col items-center gap-6">
          <PhoneMock src={appScreenshot} />
          <div className="mono text-[11px] uppercase tracking-wider text-muted-foreground flex items-center gap-3">
            <span>01 · {t.showcase.shots.a}</span>
            <span className="opacity-40">·</span>
            <span>APK</span>
          </div>
        </div>
      </div>
    </section>
  );
}

function PhoneMock({ src }: { src: string }) {
  return (
    <div className="relative mx-auto aspect-[9/19] max-w-[320px] w-full rounded-[38px] border border-hairline bg-gradient-to-b from-surface to-background p-2.5 shadow-[0_60px_120px_-40px_rgba(250,255,105,0.18)]">
      <div className="relative w-full h-full rounded-[30px] bg-[#050505] overflow-hidden border border-hairline">
        <img
          src={src}
          alt="GuacaMalla Android app — SOS broadcast screen"
          className="absolute inset-0 w-full h-full object-cover"
          loading="lazy"
        />
      </div>
    </div>
  );
}
