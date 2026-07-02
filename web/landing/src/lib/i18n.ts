import { useEffect, useState, useCallback } from "react";

export type Lang = "es" | "en";

export const dict = {
  es: {
    nav: {
      status: "01: EN LÍNEA",
      encrypted: "02: CIFRADO",
      cta: "Únete",
    },
    hero: {
      eyebrow: "PROTOCOLO DE EMERGENCIA · v0.1",
      titleA: "Cuando la red",
      titleB: "colapsa,",
      titleC: "tu señal sigue viva.",
      sub: "Red de emergencia BLE-mesh sin conexión. Cada teléfono retransmite un paquete SOS firmado, salto a salto. Sin torres, sin internet, sin servidor.",
      cta: "Únete a la lista",
      scrollHint: "Desplázate",
    },
    arch: {
      section: "ARQUITECTURA",
      title: "Diseñado para sobrevivir a la infraestructura.",
      a: { k: "CERO DEPENDENCIA", t: "Sin torres, sin internet, sin servidor central. El protocolo vive en los teléfonos.", d: "BLE 4.0+ · Android nativo" },
      b: { k: "PRUEBA CRIPTOGRÁFICA", t: "Cada paquete de 22 bytes lleva una firma Ed25519. No se puede falsificar un SOS.", d: "Ed25519 · 22 B payload" },
      c: { k: "ALCANCE INFINITO", t: "Cada teléfono es un nodo. El mensaje salta hasta que alguien lo escuche.", d: "TTL configurable · dedupe por hash" },
    },
    proof: {
      section: "EVIDENCIA",
      title: "El paquete pesa 22 bytes. Cabe en un latido.",
      sub: "Ejemplo de payload SOS transmitido durante pruebas de campo en Caracas.",
      labels: { hdr: "cabecera", nid: "nodo origen", geo: "coordenadas", ttl: "ttl", sig: "firma ed25519 (truncada)" },
    },
    showcase: {
      section: "APLICACIÓN",
      title: "Una sola pantalla. Un solo botón.",
      sub: "La aplicación Android funciona sin señal, sin cuenta, sin registro. Se enciende, escucha, retransmite.",
      shots: {
        a: "Activación de nodo",
        b: "Emisión de SOS",
        c: "Registro de saltos",
      },
    },
    waitlist: {
      section: "ACCESO ANTICIPADO",
      title: "Sé de los primeros en llevar la red.",
      sub: "Te avisamos cuando la beta abra en tu país. Sin spam. Sin ruido.",
      email: "tu@correo.com",
      country: "País",
      submit: "Solicitar acceso",
      submitting: "Enviando…",
      success: "Estás dentro. Te escribiremos.",
      errorInvalid: "Correo no válido.",
      errorNetwork: "Sin conexión. Intenta de nuevo.",
      errorServer: "Algo falló. Intenta más tarde.",
      privacy: "Solo email y país. Nada más.",
    },
    footer: {
      built: "Construido para Venezuela 🇻🇪 · 2026",
      openSource: "Hackathon",
      contact: "GitHub",
    },
  },
  en: {
    nav: {
      status: "01: ONLINE",
      encrypted: "02: ENCRYPTED",
      cta: "Join",
    },
    hero: {
      eyebrow: "EMERGENCY PROTOCOL · v0.1",
      titleA: "When the network",
      titleB: "collapses,",
      titleC: "your signal stays alive.",
      sub: "Connectionless BLE-mesh emergency network. Every phone relays a signed SOS packet, hop by hop. No towers, no internet, no server.",
      cta: "Join the waitlist",
      scrollHint: "Scroll",
    },
    arch: {
      section: "ARCHITECTURE",
      title: "Built to outlive the infrastructure.",
      a: { k: "ZERO DEPENDENCY", t: "No towers, no internet, no central server. The protocol lives on the phones.", d: "BLE 4.0+ · native Android" },
      b: { k: "CRYPTOGRAPHIC PROOF", t: "Every 22-byte packet carries an Ed25519 signature. An SOS cannot be forged.", d: "Ed25519 · 22 B payload" },
      c: { k: "INFINITE REACH", t: "Every phone is a node. The message hops until someone hears it.", d: "Configurable TTL · hash dedupe" },
    },
    proof: {
      section: "EVIDENCE",
      title: "The packet weighs 22 bytes. It fits in a heartbeat.",
      sub: "Sample SOS payload captured during field tests in Caracas.",
      labels: { hdr: "header", nid: "origin node", geo: "coordinates", ttl: "ttl", sig: "ed25519 signature (truncated)" },
    },
    showcase: {
      section: "APPLICATION",
      title: "One screen. One button.",
      sub: "The Android app works without signal, without account, without registration. Turn it on — it listens, it relays.",
      shots: {
        a: "Node activation",
        b: "SOS broadcast",
        c: "Hop log",
      },
    },
    waitlist: {
      section: "EARLY ACCESS",
      title: "Be first to carry the network.",
      sub: "We'll ping you when the beta opens in your country. No spam. No noise.",
      email: "you@email.com",
      country: "Country",
      submit: "Request access",
      submitting: "Sending…",
      success: "You're in. We'll write.",
      errorInvalid: "Invalid email.",
      errorNetwork: "Offline. Try again.",
      errorServer: "Something failed. Try later.",
      privacy: "Just email and country. Nothing else.",
    },
    footer: {
      built: "Built for Venezuela 🇻🇪 · 2026",
      openSource: "Hackathon",
      contact: "GitHub",
    },
  },
} as const;

const KEY = "guacamalla:lang";

function detectLang(): Lang {
  if (typeof window === "undefined") return "es";
  const stored = window.localStorage.getItem(KEY) as Lang | null;
  if (stored === "es" || stored === "en") return stored;
  // Default to Spanish for this Venezuela-first product.
  return "es";
}

export function useLanguage() {
  const [lang, setLangState] = useState<Lang>("es");

  useEffect(() => {
    const l = detectLang();
    setLangState(l);
    document.documentElement.lang = l;
  }, []);

  const setLang = useCallback((l: Lang) => {
    setLangState(l);
    if (typeof window !== "undefined") {
      window.localStorage.setItem(KEY, l);
      document.documentElement.lang = l;
    }
  }, []);

  const t = dict[lang];
  return { lang, setLang, t };
}
