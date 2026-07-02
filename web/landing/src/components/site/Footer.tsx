import { useLanguage } from "@/lib/i18n";
import { Logo } from "./Logo";

export function Footer() {
  const { t } = useLanguage();
  return (
    <footer className="py-14">
      <div className="mx-auto max-w-[1440px] px-6 lg:px-10 flex flex-col md:flex-row items-start md:items-center justify-between gap-6">
        <div className="flex items-center gap-3">
          <Logo className="text-primary" size={72} />
          <span className="font-medium tracking-tight">GuacaMalla</span>
          <span className="mono text-[11px] text-muted-foreground ml-3">v0.1 · pre-alpha</span>
        </div>
        <div className="mono text-[11px] uppercase tracking-wider text-muted-foreground flex gap-6">
          <span>{t.footer.built}</span>
          <a
            href="https://build4venezuela.com/en/p/guacamalla"
            target="_blank"
            rel="noopener noreferrer"
            className="hover:text-foreground transition"
          >
            {t.footer.openSource}
          </a>
          <a
            href="https://github.com/vmlcode/guacamaya-net"
            target="_blank"
            rel="noopener noreferrer"
            className="hover:text-foreground transition"
          >
            {t.footer.contact}
          </a>
        </div>
      </div>
    </footer>
  );
}
