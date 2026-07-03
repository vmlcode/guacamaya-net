import { useLanguage, type Lang } from "@/lib/i18n";
import { Logo } from "./Logo";

export function Nav() {
  const { lang, setLang, t } = useLanguage();
  return (
    <header className="fixed inset-x-0 top-0 z-50 hairline-b backdrop-blur-md bg-background/70">
      <div className="mx-auto max-w-[1440px] px-6 lg:px-10 h-20 flex items-center justify-between">
        <a href="#top" className="flex items-center gap-2.5 group">
          <Logo className="text-primary transition-transform group-hover:rotate-12 duration-500" size={66} />
          <span className="font-medium tracking-tight text-[15px]">GuacaMalla</span>
        </a>
        <div className="hidden md:flex items-center gap-6 mono text-[11px] text-muted-foreground uppercase">
          <span className="flex items-center gap-2">
            <span className="w-1.5 h-1.5 rounded-full bg-primary pulse-dot" />
            {t.nav.status}
          </span>
          <span>{t.nav.encrypted}</span>
        </div>
        <div className="flex items-center gap-3">
          <LangToggle lang={lang} setLang={setLang} />
          <a
            href="#waitlist"
            className="mono text-[11px] uppercase tracking-wider px-3 py-1.5 rounded-md bg-primary text-primary-foreground hover:brightness-110 transition"
          >
            {t.nav.cta}
          </a>
        </div>
      </div>
    </header>
  );
}

function LangToggle({ lang, setLang }: { lang: Lang; setLang: (l: Lang) => void }) {
  return (
    <div className="mono text-[11px] uppercase flex items-center gap-0.5 border border-border rounded-md p-0.5">
      {(["es", "en"] as const).map((l) => (
        <button
          key={l}
          onClick={() => setLang(l)}
          className={`px-2 py-1 rounded-sm transition ${
            lang === l ? "bg-foreground/10 text-foreground" : "text-muted-foreground hover:text-foreground"
          }`}
        >
          {l}
        </button>
      ))}
    </div>
  );
}
